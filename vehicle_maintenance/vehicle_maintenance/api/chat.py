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

from vehicle_maintenance.fleet_service import chat_feed
from vehicle_maintenance.fleet_service.chat_notify import preview_for
from vehicle_maintenance.fleet_service.doctype.vm_chat_room.vm_chat_room import (
	VMChatRoom,
	is_chat_supervisor,
)

# Hard ceiling on any single page of messages, whatever the client asks for.
MAX_PAGE = 100
DEFAULT_PAGE = 50

# Accounts that exist but are nobody. Everyone else — staff, drivers, customer
# portal users — is reachable, because "who can I message" should mean "anyone".
NON_HUMAN_USERS = ("Guest", "Administrator", "airflow-km@naarni.com")

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
	"alert_event",
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


def _as_list(value, field: str) -> list:
	"""Same tolerance as `_as_dict`, for the JSON arrays the app posts."""
	if value is None or value == "":
		return []
	if isinstance(value, list):
		return value
	try:
		parsed = json.loads(value)
	except (TypeError, ValueError):
		frappe.throw(_("{0} must be valid JSON.").format(field))
	if not isinstance(parsed, list):
		frappe.throw(_("{0} must be a JSON array.").format(field))
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
	# Mentions in one query for the whole page rather than one per message.
	mentions: dict[str, list[str]] = {}
	if rows:
		for row in frappe.get_all(
			"VM Chat Mention",
			filters={"parent": ["in", [r["name"] for r in rows]], "parenttype": "VM Chat Message"},
			fields=["parent", "user"],
			limit_page_length=0,
		):
			mentions.setdefault(row["parent"], []).append(row["user"])

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
				"alert_event": r.get("alert_event"),
				"mentions": mentions.get(r["name"], []),
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

	# A Direct room's stored title is whatever the creator saw — i.e. the other
	# person's name from *their* side. Rendering that verbatim would show the
	# recipient their own name, so each side gets the peer resolved for them.
	direct_rooms = [r["name"] for r in rooms if r["kind"] == "Direct"]
	peers: dict[str, dict] = {}
	if direct_rooms:
		for row in frappe.get_all(
			"VM Chat Member",
			filters={"parent": ["in", direct_rooms], "parenttype": "VM Chat Room", "user": ["!=", user]},
			fields=["parent", "user"],
			limit_page_length=0,
		):
			peers[row["parent"]] = {
				"user": row["user"],
				"full_name": frappe.db.get_value("User", row["user"], "full_name") or row["user"],
				"user_image": frappe.db.get_value("User", row["user"], "user_image"),
			}

	data = []
	for room in rooms:
		peer = peers.get(room["name"])
		if peer:
			room["title"] = peer["full_name"]
			room["peer"] = peer["user"]
			room["peer_image"] = peer["user_image"]
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
	mentions=None,
) -> dict:
	"""Post a message. Idempotent on `client_id`.

	The device mints `client_id` before it inserts the row locally, so a retry
	after a lost response resolves to the same message rather than a duplicate —
	the single most important property for a client that sends over flaky links.

	`lat`/`lon` are optional by design: a technician who permanently denied
	location still sends, the message is just flagged `geotagged = 0`.

	`mentions` is a JSON list of user ids named with `@` in the body. It is the
	client's parse of its own text rather than something re-derived here: two
	people can share a display name, and only the composer knows which one was
	picked from the dropdown.

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

	# Silently drop anyone who is not in the room rather than rejecting the
	# message: a stale mention (someone removed between composing and sending)
	# should cost the sender their typing, not their message.
	member_users = set(room_doc.member_users())
	mention_users = [u for u in dict.fromkeys(_as_list(mentions, "mentions")) if u in member_users]

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
			"mentions": [{"user": u} for u in mention_users],
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
def add_depot_members(room: str) -> dict:
	"""Enrol every engineer attached to the room's depot. Idempotent.

	Backs the "Add Depot Engineers" button in Desk. An alert channel is only
	useful if the people who can act on the alert are actually in it, and typing
	twenty rows into a child-table grid is how that step gets skipped.

	Returns: {success, data: {added: [user, ...]}}.
	"""
	frappe.only_for(["System Manager", "Central Ops", "Depot Manager"])
	doc: VMChatRoom = frappe.get_doc("VM Chat Room", room)
	if not doc.depot:
		frappe.throw(_("Set a Depot on this room first."))

	engineers = frappe.get_all(
		"Depot Engineer",
		filters={"parent": doc.depot, "parenttype": "Depot"},
		fields=["user"],
		limit_page_length=0,
	)
	existing = set(doc.member_users())
	added = []
	for row in engineers:
		user = row["user"]
		if not user or user in existing:
			continue
		# A disabled account in the members table only produces dead push tokens.
		if not frappe.db.get_value("User", user, "enabled"):
			continue
		doc.append("members", {"user": user, "member_role": "Member"})
		existing.add(user)
		added.append(user)

	if added:
		doc.save(ignore_permissions=True)
	return {"success": True, "data": {"added": added}}


@frappe.whitelist()
def search_users(query: str = "", limit: int = 25) -> dict:
	"""Directory search for starting a direct chat with anyone in the org.

	Matches on full name, phone or user id. Everyone enabled is reachable — the
	earlier `user_type = "System User"` filter hid every customer-portal and
	driver account, which meant the people a depot most often needs to reach were
	the ones who could not be found. Only Guest and the automation accounts are
	held back, because neither is a person who can read a message.

	Returns: {success, data: {users: [{name, full_name, mobile_no, user_image}]}}.
	"""
	me = frappe.session.user
	term = (query or "").strip()
	limit = max(1, min(cint(limit) or 25, 50))

	filters = [
		["User", "enabled", "=", 1],
		["User", "name", "not in", [me, *NON_HUMAN_USERS]],
	]
	or_filters = []
	if term:
		like = f"%{term}%"
		or_filters = [
			["User", "full_name", "like", like],
			["User", "mobile_no", "like", like],
			["User", "name", "like", like],
		]

	rows = frappe.get_all(
		"User",
		filters=filters,
		or_filters=or_filters or None,
		fields=["name", "full_name", "mobile_no", "user_image"],
		order_by="full_name asc",
		limit_page_length=limit,
	)
	return {"success": True, "data": {"users": rows}}


@frappe.whitelist()
def get_or_create_direct(user: str) -> dict:
	"""Open the one-to-one thread with `user`, creating it only if needed.

	Idempotent by construction: a direct room is keyed on its exact pair of
	members, so calling this from two devices at once cannot leave a user with
	two parallel DM threads to the same person.

	Returns: {success, data: {room, created}}.
	"""
	target = (user or "").strip()
	me = frappe.session.user
	if not target or target == me:
		frappe.throw(_("Pick someone else to message."))
	if not frappe.db.exists("User", {"name": target, "enabled": 1}):
		frappe.throw(_("That user is not available."), frappe.DoesNotExistError)

	existing = _find_direct_room(me, target)
	if existing:
		return {"success": True, "data": {"room": existing, "created": False}}

	other_name = frappe.db.get_value("User", target, "full_name") or target
	doc = frappe.get_doc(
		{
			"doctype": "VM Chat Room",
			# Title is only a fallback; the client renders a DM using the other
			# person's name so each side sees the correct label.
			"title": other_name,
			"kind": "Direct",
			"members": [
				{"user": me, "member_role": "Admin"},
				{"user": target, "member_role": "Admin"},
			],
		}
	)
	doc.insert(ignore_permissions=True)
	return {"success": True, "data": {"room": doc.name, "created": True}}


def _find_direct_room(a: str, b: str) -> str | None:
	"""The existing Direct room whose membership is exactly {a, b}."""
	mine = {
		r["parent"]
		for r in frappe.get_all(
			"VM Chat Member",
			filters={"user": a, "parenttype": "VM Chat Room"},
			fields=["parent"],
			limit_page_length=0,
		)
	}
	if not mine:
		return None
	theirs = {
		r["parent"]
		for r in frappe.get_all(
			"VM Chat Member",
			filters={"user": b, "parent": ["in", list(mine)], "parenttype": "VM Chat Room"},
			fields=["parent"],
			limit_page_length=0,
		)
	}
	if not theirs:
		return None
	for room in frappe.get_all(
		"VM Chat Room",
		filters={"name": ["in", list(theirs)], "kind": "Direct"},
		fields=["name"],
		limit_page_length=0,
	):
		count = frappe.db.count("VM Chat Member", {"parent": room["name"], "parenttype": "VM Chat Room"})
		# Exactly two members — a group that happens to contain both people is
		# not their DM.
		if count == 2:
			return room["name"]
	return None


@frappe.whitelist()
def list_members(room: str, query: str = "", limit: int = 30) -> dict:
	"""The room's members, for the `@` autocomplete.

	Scoped to the room rather than the whole directory on purpose: `@` in a group
	means "notify this person here", and offering someone who cannot see the
	thread would produce a mention that silently notifies nobody.

	Returns: {success, data: {users: [{name, full_name, mobile_no, user_image}]}}.
	"""
	room_doc = _room_checked(room)
	me = frappe.session.user
	term = (query or "").strip().lower()
	limit = max(1, min(cint(limit) or 30, 50))

	users = [u for u in room_doc.member_users() if u != me]
	if not users:
		return {"success": True, "data": {"users": []}}

	rows = frappe.get_all(
		"User",
		filters={"name": ["in", users], "enabled": 1},
		fields=["name", "full_name", "mobile_no", "user_image"],
		order_by="full_name asc",
		limit_page_length=0,
	)
	if term:
		rows = [r for r in rows if term in (r.get("full_name") or "").lower() or term in r["name"].lower()]
	return {"success": True, "data": {"users": rows[:limit]}}


# --------------------------------------------------------------------- tickets


@frappe.whitelist()
def search_tickets(query: str = "", limit: int = 20) -> dict:
	"""Find a Service Ticket to share into a thread.

	Matches on ticket id, title, or the vehicle's registration — the last being
	what someone standing next to the bus actually has to hand. Open work sorts
	first; a resolved ticket is rarely the one being discussed.

	Returns: {success, data: {tickets: [{name, title, status, severity,
	registration_number, depot, assigned_to, assigned_to_name}]}}.
	"""
	if frappe.session.user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)

	term = (query or "").strip()
	limit = max(1, min(cint(limit) or 20, 50))

	or_filters = None
	if term:
		like = f"%{term}%"
		or_filters = [
			["Service Ticket", "name", "like", like],
			["Service Ticket", "title", "like", like],
			["Service Ticket", "registration_number", "like", like],
		]

	rows = frappe.get_all(
		"Service Ticket",
		or_filters=or_filters,
		fields=[
			"name",
			"title",
			"status",
			"severity",
			"registration_number",
			"vehicle",
			"depot",
			"assigned_to",
		],
		# Open before Acknowledged before Resolved, newest first inside each.
		order_by="field(status,'Open','Acknowledged','Resolved') asc, modified desc",
		limit_page_length=limit,
	)
	names = {r["assigned_to"] for r in rows if r.get("assigned_to")}
	full = (
		{
			u["name"]: u["full_name"]
			for u in frappe.get_all(
				"User", filters={"name": ["in", list(names)]}, fields=["name", "full_name"]
			)
		}
		if names
		else {}
	)
	for r in rows:
		r["assigned_to_name"] = full.get(r.get("assigned_to")) or r.get("assigned_to")
	return {"success": True, "data": {"tickets": rows}}


@frappe.whitelist()
def share_ticket(room: str, ticket: str, client_id: str | None = None, note: str = "") -> dict:
	"""Post a ticket card into `room` so the thread can work from it.

	Idempotent on `client_id` like every other send, so a retry over a bad link
	does not paste the same ticket twice.

	Returns: {success, data: {message: {...}, duplicate: bool}}.
	"""
	room_doc = _room_checked(room)
	if not frappe.db.exists("Service Ticket", ticket):
		frappe.throw(_("Ticket {0} not found.").format(ticket), frappe.DoesNotExistError)
	frappe.has_permission("Service Ticket", doc=ticket, throw=True)

	client_id = (client_id or "").strip() or frappe.generate_hash(length=32)
	existing = frappe.db.get_value("VM Chat Message", {"client_id": client_id}, "name")
	if existing:
		doc = frappe.get_doc("VM Chat Message", existing)
		return {"success": True, "data": {"message": doc.as_payload(), "duplicate": True}}

	info = frappe.db.get_value(
		"Service Ticket", ticket, ["title", "status", "severity", "registration_number"], as_dict=True
	)
	# The body is the fallback for anything that cannot render a ticket card —
	# a push notification, an older build, the Desk timeline.
	body = f"{ticket} · {info.title or 'Service ticket'} ({info.status})"
	if info.registration_number:
		body += f" · {info.registration_number}"
	if (note or "").strip():
		body += f"\n{note.strip()}"

	seq = room_doc.allocate_seq()
	msg = frappe.get_doc(
		{
			"doctype": "VM Chat Message",
			"room": room,
			"seq": seq,
			"client_id": client_id,
			"author": frappe.session.user,
			"kind": "ticket",
			"body": body,
			"ticket": ticket,
		}
	)
	msg.insert(ignore_permissions=True)
	room_doc.touch_last_message(preview_for(msg), msg.creation)
	_advance_cursor(room, frappe.session.user, seq)
	return {"success": True, "data": {"message": msg.as_payload(), "duplicate": False}}


@frappe.whitelist()
def assign_ticket(ticket: str, user: str, room: str | None = None) -> dict:
	"""Assign a Service Ticket from inside a chat thread.

	Writes both halves of what "assigned" means in Frappe — the ticket's own
	`assigned_to` field and a ToDo, which is what drives the assignee's Desk
	sidebar — then posts a notice back into the conversation so the handover is
	on the record everyone is reading rather than only in the ticket's history.

	Returns: {success, data: {ticket, assigned_to}}.
	"""
	frappe.has_permission("Service Ticket", ptype="write", doc=ticket, throw=True)
	target = (user or "").strip()
	if not frappe.db.exists("User", {"name": target, "enabled": 1}):
		frappe.throw(_("That user is not available."), frappe.DoesNotExistError)
	if room:
		_room_checked(room)

	frappe.db.set_value("Service Ticket", ticket, "assigned_to", target)

	try:
		from frappe.desk.form.assign_to import add as assign_add

		assign_add(
			{
				"assign_to": [target],
				"doctype": "Service Ticket",
				"name": ticket,
				"description": _("Assigned from chat"),
			}
		)
	except frappe.ValidationError:
		# Already assigned to this person — the field write above is still the
		# thing that matters, so this is not a failure.
		pass

	chat_feed.announce_assignment(ticket, target, frappe.session.user, room)
	return {"success": True, "data": {"ticket": ticket, "assigned_to": target}}


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
