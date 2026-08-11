# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Chat endpoints for the mobile app.

The client keeps a Room database as its single source of truth and treats the
socket as a latency optimisation, never a delivery guarantee. That shapes this
API in three ways:

* **`sync` is the correctness path.** The client sends the highest `seq` it holds
  per room and gets back only what it is missing. Every reconnect, foreground and
  push runs it, so a dropped socket event is not a special case.
* **`send_message` is idempotent on `client_id`.** A retry after a timeout that
  actually succeeded returns the original row instead of duplicating it.
* **Ordering is `seq`, never a timestamp.** See VMChatRoom.allocate_seq.

Every method returns the app's standard `{success, data, message}` envelope.
"""

import json

import frappe
from frappe import _
from frappe.utils import cint, now_datetime

from vehicle_maintenance.fleet_service.chat_notify import preview_for
from vehicle_maintenance.fleet_service.doctype.vm_chat_room.vm_chat_room import (
	VMChatRoom,
	is_chat_supervisor,
)

# Hard ceiling on any single page of messages, whatever the client asks for.
MAX_PAGE = 100
DEFAULT_PAGE = 50

# Fields we ever put on the wire for a message. Explicit — never SELECT *.
MESSAGE_FIELDS = (
	"name",
	"room",
	"seq",
	"client_id",
	"author",
	"kind",
	"body",
	"file_url",
	"file_name",
	"file_size",
	"duration_ms",
	"transcript",
	"reply_to",
	"vehicle",
	"ticket",
	"geotagged",
	"lat",
	"lon",
	"deleted",
	"creation",
)


# --------------------------------------------------------------------- helpers


def _as_dict(value, field: str):
	"""Frappe hands JSON bodies through as strings over HTTP; accept both."""
	if value is None or value == "":
		return {}
	if isinstance(value, dict):
		return value
	try:
		parsed = json.loads(value)
	except (TypeError, ValueError):
		frappe.throw(_("{0} must be valid JSON.").format(field))
	if not isinstance(parsed, dict):
		frappe.throw(_("{0} must be a JSON object.").format(field))
	return parsed


def _room_checked(room: str, user: str | None = None) -> VMChatRoom:
	"""Load a room, throwing PermissionError unless `user` is a member."""
	user = user or frappe.session.user
	if not room or not frappe.db.exists("VM Chat Room", room):
		frappe.throw(_("Chat room not found."), frappe.DoesNotExistError)
	doc: VMChatRoom = frappe.get_doc("VM Chat Room", room)
	if not doc.is_member(user) and not is_chat_supervisor(user):
		frappe.throw(_("You are not a member of this room."), frappe.PermissionError)
	return doc


def _my_room_names(user: str) -> list[str]:
	rows = frappe.get_all(
		"VM Chat Member",
		filters={"user": user, "parenttype": "VM Chat Room"},
		fields=["parent"],
		limit_page_length=0,
	)
	return [r["parent"] for r in rows]


def _serialise(rows: list[dict]) -> list[dict]:
	"""Shape DB rows into the client wire format, blanking soft-deleted bodies."""
	authors = {r["author"] for r in rows}
	names = (
		{
			u["name"]: u["full_name"]
			for u in frappe.get_all(
				"User",
				filters={"name": ["in", list(authors)]},
				fields=["name", "full_name"],
				limit_page_length=0,
			)
		}
		if authors
		else {}
	)
	out = []
	for r in rows:
		deleted = bool(r.get("deleted"))
		out.append(
			{
				"name": r["name"],
				"room": r["room"],
				"seq": r["seq"],
				"client_id": r["client_id"],
				"author": r["author"],
				"author_name": names.get(r["author"]) or r["author"],
				"kind": r["kind"],
				"body": "" if deleted else (r.get("body") or ""),
				"file_url": None if deleted else r.get("file_url"),
				"file_name": r.get("file_name"),
				"file_size": r.get("file_size"),
				"duration_ms": r.get("duration_ms"),
				"transcript": r.get("transcript"),
				"reply_to": r.get("reply_to"),
				"vehicle": r.get("vehicle"),
				"ticket": r.get("ticket"),
				"geotagged": bool(r.get("geotagged")),
				"lat": r.get("lat"),
				"lon": r.get("lon"),
				"deleted": deleted,
				"created_at": str(r.get("creation")),
			}
		)
	return out


# ----------------------------------------------------------------------- rooms


@frappe.whitelist()
def list_rooms() -> dict:
	"""Every room the caller belongs to, with their unread count.

	Returns: {success, data: {rooms: [{name, title, kind, depot, vehicle, ticket,
	job_card, last_seq, last_read_seq, unread, muted, last_message_preview,
	last_message_at, member_count}]}}.
	"""
	user = frappe.session.user
	names = _my_room_names(user)
	if not names:
		return {"success": True, "data": {"rooms": []}}

	rooms = frappe.get_all(
		"VM Chat Room",
		filters={"name": ["in", names], "is_active": 1},
		fields=[
			"name",
			"title",
			"kind",
			"depot",
			"vehicle",
			"ticket",
			"job_card",
			"last_seq",
			"last_message_preview",
			"last_message_at",
		],
		order_by="last_message_at desc",
		limit_page_length=0,
	)

	cursors = {
		r["parent"]: r
		for r in frappe.get_all(
			"VM Chat Member",
			filters={"user": user, "parent": ["in", names], "parenttype": "VM Chat Room"},
			fields=["parent", "last_read_seq", "muted"],
			limit_page_length=0,
		)
	}
	counts = {}
	for r in frappe.get_all(
		"VM Chat Member",
		filters={"parent": ["in", names], "parenttype": "VM Chat Room"},
		fields=["parent", "count(name) as n"],
		group_by="parent",
		limit_page_length=0,
	):
		counts[r["parent"]] = r["n"]

	data = []
	for room in rooms:
		cur = cursors.get(room["name"], {})
		last_read = cint(cur.get("last_read_seq"))
		last_seq = cint(room.get("last_seq"))
		data.append(
			{
				**room,
				"last_seq": last_seq,
				"last_read_seq": last_read,
				"unread": max(0, last_seq - last_read),
				"muted": bool(cur.get("muted")),
				"member_count": counts.get(room["name"], 0),
				"last_message_at": str(room["last_message_at"]) if room.get("last_message_at") else None,
			}
		)
	return {"success": True, "data": {"rooms": data}}


@frappe.whitelist()
def list_messages(room: str, before_seq: int | None = None, limit: int = DEFAULT_PAGE) -> dict:
	"""One page of history, newest first, for infinite scroll.

	`before_seq` pages backwards: pass the lowest seq you hold to get older ones.
	Returns: {success, data: {room, messages: [...], has_more}}.
	"""
	_room_checked(room)
	limit = max(1, min(cint(limit) or DEFAULT_PAGE, MAX_PAGE))

	filters: dict = {"room": room}
	if before_seq:
		filters["seq"] = ["<", cint(before_seq)]

	rows = frappe.get_all(
		"VM Chat Message",
		filters=filters,
		fields=list(MESSAGE_FIELDS),
		order_by="seq desc",
		limit_page_length=limit + 1,
	)
	has_more = len(rows) > limit
	return {
		"success": True,
		"data": {"room": room, "messages": _serialise(rows[:limit]), "has_more": has_more},
	}


@frappe.whitelist()
def sync(cursors=None) -> dict:
	"""Delta sync — the client's correctness path.

	`cursors` is {room_name: highest_seq_held}. Rooms the caller belongs to but
	omits are treated as seq 0, so a fresh install gets recent history for each.
	Only rooms with something newer appear in the response.

	Returns: {success, data: {rooms: {room: {messages: [...], last_seq}}, server_time}}.
	"""
	user = frappe.session.user
	cursors = _as_dict(cursors, "cursors")
	mine = _my_room_names(user)
	if not mine:
		return {"success": True, "data": {"rooms": {}, "server_time": str(now_datetime())}}

	out: dict[str, dict] = {}
	for room in mine:
		since = cint(cursors.get(room))
		last_seq = cint(frappe.db.get_value("VM Chat Room", room, "last_seq"))
		if last_seq <= since:
			continue
		rows = frappe.get_all(
			"VM Chat Message",
			filters={"room": room, "seq": [">", since]},
			fields=list(MESSAGE_FIELDS),
			order_by="seq asc",
			# A device offline for a week should not pull the whole backlog in one
			# response; it pages forward by calling sync again with a moved cursor.
			limit_page_length=MAX_PAGE,
		)
		out[room] = {
			"messages": _serialise(rows),
			"last_seq": last_seq,
			"more": bool(rows) and rows[-1]["seq"] < last_seq,
		}

	return {"success": True, "data": {"rooms": out, "server_time": str(now_datetime())}}


# -------------------------------------------------------------------- sending


@frappe.whitelist()
def send_message(
	room: str,
	client_id: str,
	body: str | None = None,
	kind: str = "text",
	reply_to: str | None = None,
	file_url: str | None = None,
	file_name: str | None = None,
	file_size: int | None = None,
	duration_ms: int | None = None,
	lat: float | None = None,
	lon: float | None = None,
	vehicle: str | None = None,
	ticket: str | None = None,
) -> dict:
	"""Post a message. Idempotent on `client_id`.

	The device mints `client_id` before it inserts the row locally, so a retry
	after a lost response resolves to the same message rather than a duplicate —
	the single most important property for a client that sends over flaky links.

	`lat`/`lon` are optional by design: a technician who permanently denied
	location still sends, the message is just flagged `geotagged = 0`.

	Returns: {success, data: {message: {...}, duplicate: bool}}.
	"""
	client_id = (client_id or "").strip()
	if not client_id:
		frappe.throw(_("client_id is required."))

	# Idempotency first — before any lock, so a retry is cheap and never allocates
	# a seq it would have to give back.
	existing = frappe.db.get_value("VM Chat Message", {"client_id": client_id}, "name")
	if existing:
		doc = frappe.get_doc("VM Chat Message", existing)
		return {"success": True, "data": {"message": doc.as_payload(), "duplicate": True}}

	room_doc = _room_checked(room)
	kind = (kind or "text").strip()
	if kind not in ("text", "image", "video", "audio"):
		frappe.throw(_("Unsupported message kind: {0}").format(kind))
	if kind == "text" and not (body or "").strip():
		frappe.throw(_("Message cannot be empty."))
	if reply_to and not frappe.db.exists("VM Chat Message", {"name": reply_to, "room": room}):
		frappe.throw(_("The message being replied to is not in this room."))

	seq = room_doc.allocate_seq()

	msg = frappe.get_doc(
		{
			"doctype": "VM Chat Message",
			"room": room,
			"seq": seq,
			"client_id": client_id,
			"author": frappe.session.user,
			"kind": kind,
			"body": (body or "").strip(),
			"reply_to": reply_to,
			"file_url": file_url,
			"file_name": file_name,
			"file_size": cint(file_size) or None,
			"duration_ms": cint(duration_ms) or None,
			"lat": lat,
			"lon": lon,
			"geotagged": 1 if (lat is not None and lon is not None) else 0,
			"vehicle": vehicle,
			"ticket": ticket,
		}
	)
	msg.insert(ignore_permissions=True)  # membership already enforced above + in validate

	room_doc.touch_last_message(preview_for(msg), msg.creation)
	# The sender has by definition read their own message.
	_advance_cursor(room, frappe.session.user, seq)

	return {"success": True, "data": {"message": msg.as_payload(), "duplicate": False}}


@frappe.whitelist()
def mark_read(room: str, seq: int) -> dict:
	"""Move the caller's read cursor forward. Never backwards.

	Returns: {success, data: {room, last_read_seq}}.
	"""
	_room_checked(room)
	new_seq = _advance_cursor(room, frappe.session.user, cint(seq))
	return {"success": True, "data": {"room": room, "last_read_seq": new_seq}}


def _advance_cursor(room: str, user: str, seq: int) -> int:
	"""Monotonic read-cursor update; returns the resulting value.

	Clamped forward-only because messages can be marked read out of order — a
	push tap opens the newest message while older ones are still unseen, and the
	badge must not resurrect them.
	"""
	row = frappe.db.get_value(
		"VM Chat Member",
		{"parent": room, "parenttype": "VM Chat Room", "user": user},
		["name", "last_read_seq"],
		as_dict=True,
	)
	if not row:
		return 0
	current = cint(row["last_read_seq"])
	if seq <= current:
		return current
	frappe.db.set_value("VM Chat Member", row["name"], "last_read_seq", seq, update_modified=False)
	return seq


# ------------------------------------------------------------ room management


@frappe.whitelist()
def create_room(
	title: str,
	kind: str = "Group",
	members=None,
	depot: str | None = None,
	vehicle: str | None = None,
	ticket: str | None = None,
	job_card: str | None = None,
) -> dict:
	"""Create a room. The caller is always enrolled as an Admin member.

	`members` is a JSON list of user ids. Returns: {success, data: {room}}.
	"""
	frappe.only_for(["System Manager", "Central Ops", "Depot Manager", "Service Engineer", "Aftersales Eng"])

	if isinstance(members, str):
		try:
			members = json.loads(members)
		except (TypeError, ValueError):
			frappe.throw(_("members must be valid JSON."))
	members = list(members or [])

	me = frappe.session.user
	rows = [{"user": me, "member_role": "Admin"}]
	rows += [{"user": u, "member_role": "Member"} for u in dict.fromkeys(members) if u != me]

	doc = frappe.get_doc(
		{
			"doctype": "VM Chat Room",
			"title": title,
			"kind": kind,
			"depot": depot,
			"vehicle": vehicle,
			"ticket": ticket,
			"job_card": job_card,
			"members": rows,
		}
	)
	doc.insert()
	return {"success": True, "data": {"room": doc.name}}


@frappe.whitelist()
def set_muted(room: str, muted: int = 1) -> dict:
	"""Mute or unmute a room for the caller. Returns {success, data: {muted}}."""
	_room_checked(room)
	row = frappe.db.get_value(
		"VM Chat Member",
		{"parent": room, "parenttype": "VM Chat Room", "user": frappe.session.user},
		"name",
	)
	if not row:
		frappe.throw(_("You are not a member of this room."), frappe.PermissionError)
	value = 1 if cint(muted) else 0
	frappe.db.set_value("VM Chat Member", row, "muted", value, update_modified=False)
	return {"success": True, "data": {"muted": bool(value)}}
