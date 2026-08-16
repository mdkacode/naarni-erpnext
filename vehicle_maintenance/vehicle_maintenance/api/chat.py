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
from datetime import datetime

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

	# One pass over the member rows instead of three queries.
	#
	# The caller's own cursor, the member count, and the receipt marks all come
	# from the same small set of rows — a room has tens of members, not
	# thousands — so fetching them once and folding in Python costs one round
	# trip where separate COUNT and per-user queries cost three.
	cursors: dict[str, dict] = {}
	counts: dict[str, int] = {}
	others_read: dict[str, int] = {}
	others_delivered: dict[str, int] = {}

	for m in frappe.get_all(
		"VM Chat Member",
		filters={"parent": ["in", names], "parenttype": "VM Chat Room"},
		fields=["parent", "user", "last_read_seq", "last_delivered_seq", "muted"],
		limit_page_length=0,
	):
		parent = m["parent"]
		counts[parent] = counts.get(parent, 0) + 1
		if m["user"] == user:
			cursors[parent] = m
			continue
		# The *minimum* across everyone else: a group message is only "read"
		# once the last person has read it, which is what the two blue ticks
		# claim. Taking the maximum would turn one reader into "everyone".
		read = cint(m.get("last_read_seq"))
		delivered = cint(m.get("last_delivered_seq"))
		others_read[parent] = min(others_read.get(parent, read), read)
		others_delivered[parent] = min(others_delivered.get(parent, delivered), delivered)

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
				# Receipt marks for the caller's *own* messages. Read implies
				# delivered, so delivered is floored at read: a member whose
				# delivery cursor lagged would otherwise show a message as read
				# but not delivered, which the UI would have to special-case.
				"read_upto": others_read.get(room["name"], 0),
				"delivered_upto": max(
					others_delivered.get(room["name"], 0),
					others_read.get(room["name"], 0),
				),
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
	page = rows[:limit]

	# Opening a thread is a delivery, but this is deliberately NOT where that is
	# recorded. The client fetches this endpoint with GET, and Frappe rolls back
	# the transaction for every safe method (`app.py::sync_database`), so a
	# cursor advanced here is discarded on the way out — proven against the
	# running bench: after a GET the member row stayed at delivered 0, and the
	# same message marked through the POST endpoint moved it to 1.
	#
	# Writing here would therefore be code that reads as if it works, passes a
	# test that calls the function directly, and does nothing at all in the app.
	# `mark_delivered` below is the POST the client actually uses.

	return {
		"success": True,
		"data": {"room": room, "messages": _serialise(page), "has_more": has_more},
	}


@frappe.whitelist()
def mark_delivered(room: str, seq: int) -> dict:
	"""The client acknowledging it has stored a message it was pushed.

	The socket and FCM hand a message to the device without the server learning
	anything about it, so without this the only delivery evidence is a later
	`sync` or `list_messages` — which is why a phone that had already displayed
	a message could still show the sender one tick.

	Deliberately separate from `mark_read`: receiving is not reading. A message
	that arrives while the app is on another screen is delivered and unread, and
	collapsing the two would turn every push into a false blue tick.
	"""
	_room_checked(room)
	_mark_delivered(frappe.session.user, {room: cint(seq)})
	return {"success": True, "data": {"room": room, "seq": cint(seq)}}


@frappe.whitelist()
def sync(cursors: str | dict | None = None) -> dict:
	"""Delta sync — the client's correctness path.

	`cursors` is {room_name: highest_seq_held}, and arrives either as a dict (a
	server-side caller) or as a JSON string (over HTTP, where Frappe hands form
	bodies through unparsed) — `_as_dict` normalises both.

	Rooms the caller belongs to but
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
	delivered: dict[str, int] = {}
	for room in mine:
		since = cint(cursors.get(room))
		last_seq = cint(frappe.db.get_value("VM Chat Room", room, "last_seq"))
		# The cursor is the client stating what it already holds, and a device
		# cannot hold a message it never received — so a cursor *is* a delivery
		# receipt, for every room, whether or not anything new comes back.
		#
		# This is the path that was missing. A phone handed a message over the
		# socket or a push, whose ack was lost with the connection that carried
		# it, would stay undelivered until it happened to be given a fresh row —
		# so the sender sat on one tick while the recipient had the message on
		# screen. Now every sync repairs it.
		#
		# Clamped to the room's own last_seq: a cursor is client-supplied, and a
		# delivery for a message that does not exist yet must not be writable.
		if since > 0:
			delivered[room] = min(since, last_seq)
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
		if rows:
			delivered[room] = cint(rows[-1]["seq"])

	_mark_delivered(user, delivered)
	return {"success": True, "data": {"rooms": out, "server_time": str(now_datetime())}}


def _mark_delivered(user: str, upto: dict[str, int]) -> None:
	"""Advance the caller's delivery cursor for the rooms just handed over.

	This is the honest definition of "delivered": the device asked for these
	messages and we returned them. Marking on push would only prove that *we*
	sent a notification, which says nothing about whether it arrived — and a
	second tick that appears when the recipient's phone is off is a lie.

	One statement for every room rather than a document write each. These rows
	are cursors, not records anybody audits, so `update_modified` stays off:
	touching the parent's timestamp on every sync would invalidate the room
	cache for all its members every time any one of them polled.

	`GREATEST` keeps it monotonic, so an out-of-order or replayed sync can
	never walk the cursor backwards and un-deliver a message.
	"""
	if not upto:
		return
	touched = []
	for room, seq in upto.items():
		if seq <= 0:
			continue
		frappe.db.sql(
			"""
			UPDATE `tabVM Chat Member`
			   SET last_delivered_seq = GREATEST(COALESCE(last_delivered_seq, 0), %(seq)s)
			 WHERE parenttype = 'VM Chat Room'
			   AND parent = %(room)s
			   AND user = %(user)s
			   AND COALESCE(last_delivered_seq, 0) < %(seq)s
			""",
			{"seq": seq, "room": room, "user": user},
		)
		touched.append(room)
	publish_receipts(touched)


# Where the last-published receipt pair for each room is remembered, so that a
# room whose numbers have not moved costs nothing on the wire.
_RECEIPT_PUB_KEY = "vm_chat_receipt_pub"


def publish_receipts(rooms) -> None:
	"""Tell each room's open threads how far its slowest member has got.

	Without this the ticks are only as fresh as the last `list_rooms` call, so a
	sender watching the thread they just sent into — the exact moment anybody
	looks at a tick — would never see it turn. The list screen would show the
	blue tick that the conversation itself refused to.

	Two things make this cheap enough to fire on every read and every sync:

	* **One aggregate query for every room at once**, not one per room. A sync
	  that touched thirty rooms still costs a single round trip.
	* **Publish only on change.** Cursors advance far more often than the
	  *minimum* across members moves — in a group of twenty, nineteen reads
	  change nothing anybody can see. The last published pair is kept in the
	  cache and compared before anything goes out.

	The minimum is taken across *all* members rather than all-but-the-viewer,
	which is what `list_rooms` computes. That looks like a discrepancy and is
	deliberate: this payload has one shape for every recipient, so it cannot
	depend on who receives it. Including the viewer can only ever hold the
	number *back*, never inflate it, and for the person actually looking at the
	thread the two agree anyway — reading it is what advanced their own cursor
	to the top. So the tick can lag by one refresh, but it can never claim a
	message was read when it was not, which is the only error that matters.
	"""
	rooms = [r for r in dict.fromkeys(rooms or []) if r]
	if not rooms:
		return

	try:
		rows = frappe.db.sql(
			"""
			SELECT parent AS room,
			       user,
			       COALESCE(last_read_seq, 0) AS read_seq,
			       COALESCE(last_delivered_seq, 0) AS delivered_seq
			  FROM `tabVM Chat Member`
			 WHERE parenttype = 'VM Chat Room'
			   AND parent IN %(rooms)s
			""",
			{"rooms": tuple(rooms)},
			as_dict=True,
		)
	except Exception:
		frappe.log_error(title="Chat receipt aggregate failed", message=frappe.get_traceback())
		return

	# Folded here rather than with GROUP BY because the same rows carry both the
	# minimum and the membership, and the membership is who has to be told.
	marks: dict[str, dict] = {}
	for row in rows:
		mark = marks.setdefault(row["room"], {"read": None, "delivered": None, "members": []})
		mark["members"].append(row["user"])
		read = cint(row["read_seq"])
		delivered = cint(row["delivered_seq"])
		mark["read"] = read if mark["read"] is None else min(mark["read"], read)
		mark["delivered"] = delivered if mark["delivered"] is None else min(mark["delivered"], delivered)

	cache = frappe.cache()
	for room, mark in marks.items():
		read = cint(mark["read"])
		# A member who has read a message plainly received it. Flooring here
		# spares every client from having to know that and render "read but not
		# delivered" — a state that cannot exist.
		delivered = max(cint(mark["delivered"]), read)

		stamp = f"{read}:{delivered}"
		if cache.hget(_RECEIPT_PUB_KEY, room) == stamp:
			continue
		cache.hset(_RECEIPT_PUB_KEY, room, stamp)

		_emit_receipt({"room": room, "read_upto": read, "delivered_upto": delivered}, mark["members"])


def _emit_receipt(payload: dict, members: list[str]) -> None:
	"""Send one receipt to the open thread *and* to each member's own socket.

	The doc room alone is where the tick was being lost. It reaches only clients
	that have `doc_subscribe`d — i.e. somebody with that exact thread on screen.
	A sender who has gone back to the conversation list, or moved to another
	thread, has already unsubscribed, so their ticks froze until the next
	`list_rooms` — which the app runs on foreground and network changes, not on
	a timer. The list screen is precisely where people look at ticks.

	Every socket joins `user:{name}` automatically on connect, so the per-member
	publish needs no subscribe round-trip and survives every reconnect. Clients
	apply receipts with a MAX, so receiving both copies is a no-op.
	"""
	room = payload["room"]
	try:
		frappe.publish_realtime(
			event="vm_chat_receipt",
			message=payload,
			doctype="VM Chat Room",
			docname=room,
			# The cursor row is already written; waiting for commit would
			# hold a tick behind a transaction that has nothing to do with it.
			after_commit=False,
		)
	except Exception:
		frappe.log_error(title=f"Chat receipt publish failed ({room})", message=frappe.get_traceback())

	for user in members:
		try:
			frappe.publish_realtime(
				event="vm_chat_receipt",
				message=payload,
				user=user,
				after_commit=False,
			)
		except Exception:
			frappe.log_error(
				title=f"Chat receipt user publish failed ({room})", message=frappe.get_traceback()
			)


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
	new_seq, moved = _advance_cursor(room, frappe.session.user, cint(seq))
	# Only when this call actually moved something. A thread left open re-marks
	# the same seq on every foreground, and none of those need to reach anyone.
	if moved:
		publish_receipts([room])
	return {"success": True, "data": {"room": room, "last_read_seq": new_seq}}


def _advance_cursor(room: str, user: str, seq: int) -> tuple[int, bool]:
	"""Monotonic read-cursor update; returns (resulting value, whether it moved).

	Clamped forward-only because messages can be marked read out of order — a
	push tap opens the newest message while older ones are still unseen, and the
	badge must not resurrect them.

	Delivery is carried along with it. Reading a message is proof of having
	received it, and a sender is holding the message they just sent — without
	this the author's own delivery cursor would sit at zero for a thread they
	are actively typing in, and since the room's second tick is the *minimum*
	across its members, one author would hold the whole room at one tick
	forever.
	"""
	row = frappe.db.get_value(
		"VM Chat Member",
		{"parent": room, "parenttype": "VM Chat Room", "user": user},
		["name", "last_read_seq", "last_delivered_seq"],
		as_dict=True,
	)
	if not row:
		return 0, False
	current = cint(row["last_read_seq"])
	if seq <= current:
		# The read cursor has not moved, but delivery may still owe a catch-up
		# if this row predates the delivered column.
		if cint(row["last_delivered_seq"]) < current:
			frappe.db.set_value(
				"VM Chat Member", row["name"], "last_delivered_seq", current, update_modified=False
			)
		return current, False
	frappe.db.set_value(
		"VM Chat Member",
		row["name"],
		{"last_read_seq": seq, "last_delivered_seq": max(seq, cint(row["last_delivered_seq"]))},
		update_modified=False,
	)
	return seq, True


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


# ------------------------------------------------------- presence and typing

# How long a typing signal stands before the reader should discard it. The
# client re-sends while a key is still being pressed, so this only has to
# outlive the gap between two keystrokes — long enough to survive a slow link,
# short enough that a composer abandoned mid-word stops claiming to be active.
TYPING_TTL_SECONDS = 8

# How long after a heartbeat a user is still considered reachable. Deliberately
# a multiple of the client's heartbeat interval: one missed beat on a depot's
# link must not blink somebody offline in front of the person messaging them.
PRESENCE_TTL_SECONDS = 75

# Where the live beat is kept. Being *online* is a cache-only fact: it is
# worthless the moment it is stale, and a restart that forgets it costs one
# heartbeat interval to rebuild.
_PRESENCE_KEY = "vm_chat_presence"

# "Last seen", unlike "online", is worth keeping — it is the answer when
# somebody is *not* reachable, which is exactly when the cache has nothing to
# say. So the beat is also written down, but at a fraction of its rate.
_PERSIST_KEY = "vm_chat_presence_saved"

# How stale the written-down copy is allowed to get. At three minutes a user
# online all day costs twenty writes rather than the thousand-odd a write per
# beat would cost, and "last seen" is a phrase nobody reads to the second.
# Precision is not lost while Redis is warm: reads take the later of the two.
PRESENCE_PERSIST_SECONDS = 180


@frappe.whitelist()
def set_typing(room: str, typing: int = 1) -> dict:
	"""Tell the rest of `room` that the caller is (or has stopped) composing.

	Fire-and-forget from the client's point of view: nothing is persisted, and
	the event is published straight to the doc room so only people with the
	thread actually open pay for it.

	Args:
	        room: the room being typed in. Membership is enforced.
	        typing: 1 while composing, 0 on send/clear/blur.

	Returns: {success, data: {room, typing}}.
	"""
	_room_checked(room)
	is_typing = 1 if cint(typing) else 0

	try:
		frappe.publish_realtime(
			event="vm_chat_typing",
			message={
				"room": room,
				"user": frappe.session.user,
				"user_name": frappe.db.get_value("User", frappe.session.user, "full_name")
				or frappe.session.user,
				"typing": is_typing,
				"ttl": TYPING_TTL_SECONDS,
			},
			doctype="VM Chat Room",
			docname=room,
			# Not after_commit: there is no transaction worth waiting for, and a
			# typing dot that arrives after the message it was predicting is
			# worse than no typing dot at all.
			after_commit=False,
		)
	except Exception:
		# A failed typing publish is never worth failing a request over.
		frappe.log_error(title=f"Chat typing publish failed ({room})", message=frappe.get_traceback())

	return {"success": True, "data": {"room": room, "typing": bool(is_typing)}}


@frappe.whitelist()
def heartbeat() -> dict:
	"""Record that the caller is online, and report who else is.

	One call does both halves because they happen on the same schedule: a client
	that wants fresh presence is by definition still running, and splitting it
	would double the request count for no extra information.

	Only people the caller actually shares a room with are returned — presence is
	a fact about a conversation, not a directory of who is at work today.

	Returns: {success, data: {online: [user_id, …], last_seen: {user: iso}, ttl}}.
	"""
	user = frappe.session.user
	if user in NON_HUMAN_USERS:
		return {"success": True, "data": {"online": [], "last_seen": {}, "ttl": PRESENCE_TTL_SECONDS}}

	cache = frappe.cache()
	now_ts = now_datetime().timestamp()
	cache.hset(_PRESENCE_KEY, user, now_ts)
	_persist_last_seen(user, now_ts)

	rooms = _my_room_names(user)
	if not rooms:
		return {"success": True, "data": {"online": [], "last_seen": {}, "ttl": PRESENCE_TTL_SECONDS}}

	# Everyone who shares at least one room with the caller. One query rather
	# than one per room — a depot manager is in dozens.
	peers = frappe.get_all(
		"VM Chat Member",
		filters={"parenttype": "VM Chat Room", "parent": ["in", rooms]},
		fields=["distinct user as user"],
		limit_page_length=0,
	)

	# One hgetall rather than an hget per peer: a depot manager shares rooms with
	# dozens of people, and this is called by every client every 30 seconds.
	#
	# Frappe's RedisWrapper.hgetall un-pickles the *values* but leaves the keys as
	# raw bytes, so a str lookup silently misses every entry — which presents as
	# "presence works but nobody is ever online". Encoded lookup, and float() to
	# tolerate either a pickled float or a raw string.
	stamps = cache.hgetall(_PRESENCE_KEY) or {}

	peer_ids = [row["user"] for row in peers if row["user"] != user and row["user"] not in NON_HUMAN_USERS]

	# The written-down copy, for everyone the cache has nothing recent about —
	# and the opt-out, which is read here rather than at write time so that
	# turning it on immediately hides the history already recorded.
	stored: dict[str, object] = {}
	hidden: set[str] = set()
	if peer_ids:
		for row in frappe.get_all(
			"VM Chat Presence",
			filters={"user": ["in", peer_ids]},
			# `seen_at`, never `last_seen` — Frappe drops any fieldname
			# containing `_seen` from the result. See the doctype controller.
			fields=["user", "seen_at", "hidden_from_peers"],
			limit_page_length=0,
		):
			if cint(row.get("hidden_from_peers")):
				hidden.add(row["user"])
			elif row.get("seen_at"):
				stored[row["user"]] = row["seen_at"]

	online = []
	last_seen: dict[str, str] = {}
	for peer in peer_ids:
		seen = _cached_stamp(stamps, peer)
		if seen is not None and now_ts - seen <= PRESENCE_TTL_SECONDS:
			online.append(peer)
			continue
		if peer in hidden:
			continue
		# Whichever is later: the cache is precise but forgetful, the row is
		# durable but up to PRESENCE_PERSIST_SECONDS behind.
		candidates = []
		if seen is not None:
			candidates.append(str(datetime.fromtimestamp(seen)))
		if peer in stored:
			candidates.append(str(stored[peer]))
		if candidates:
			last_seen[peer] = max(candidates)

	return {
		"success": True,
		"data": {"online": online, "last_seen": last_seen, "ttl": PRESENCE_TTL_SECONDS},
	}


def _cached_stamp(stamps: dict, peer: str) -> float | None:
	"""Read one user's beat out of a `hgetall` result.

	Frappe's RedisWrapper.hgetall un-pickles the *values* but leaves the keys as
	raw bytes, so a str lookup silently misses every entry — which presents as
	"presence works but nobody is ever online". Both spellings are tried, and
	float() tolerates either a pickled float or a raw string.
	"""
	raw = stamps.get(peer.encode())
	if raw is None:
		raw = stamps.get(peer)
	if raw is None:
		return None
	try:
		return float(raw)
	except (TypeError, ValueError):
		return None


def _persist_last_seen(user: str, now_ts: float) -> None:
	"""Write the beat down, but only every PRESENCE_PERSIST_SECONDS.

	The throttle is what makes a heartbeat affordable: without it, every client
	in every depot would be issuing a database write every thirty seconds for a
	field whose whole purpose is to be read approximately.
	"""
	cache = frappe.cache()
	saved = cache.hget(_PERSIST_KEY, user)
	try:
		if saved is not None and now_ts - float(saved) < PRESENCE_PERSIST_SECONDS:
			return
	except (TypeError, ValueError):
		pass

	stamp = datetime.fromtimestamp(now_ts)
	try:
		if frappe.db.exists("VM Chat Presence", user):
			frappe.db.set_value("VM Chat Presence", user, "seen_at", stamp, update_modified=False)
		else:
			frappe.get_doc({"doctype": "VM Chat Presence", "user": user, "seen_at": stamp}).insert(
				ignore_permissions=True
			)
		# Committed explicitly because a heartbeat is a read as far as the caller
		# is concerned, and Frappe discards writes made during one. Safe here
		# only because this runs before the endpoint touches anything else —
		# there is no other pending work for the commit to sweep up.
		frappe.db.commit()
	except Exception:
		# Losing a heartbeat write costs at most one stale "last seen". It must
		# never cost the caller their presence response.
		# Losing a heartbeat write costs at most one stale "last seen". It must
		# never cost the caller their presence response.
		frappe.db.rollback()
		frappe.log_error(title=f"Chat presence write failed ({user})", message=frappe.get_traceback())
		return

	cache.hset(_PERSIST_KEY, user, now_ts)


@frappe.whitelist()
def set_last_seen_visible(visible: int = 1) -> dict:
	"""Choose whether peers may see when the caller was last reachable.

	Last seen is mutual by nature — a technician can see a manager's and a
	manager can see a technician's — so it comes with a way out. Hiding it does
	not stop presence being recorded, and does not hide the green dot: someone
	with the thread open is visibly there either way, and pretending otherwise
	would be the kind of half-truth that makes people distrust the whole screen.

	Returns: {success, data: {visible}}.
	"""
	user = frappe.session.user
	if user in NON_HUMAN_USERS:
		frappe.throw(_("Not available for this account."))

	hidden = 0 if cint(visible) else 1
	if frappe.db.exists("VM Chat Presence", user):
		frappe.db.set_value("VM Chat Presence", user, "hidden_from_peers", hidden, update_modified=False)
	else:
		frappe.get_doc({"doctype": "VM Chat Presence", "user": user, "hidden_from_peers": hidden}).insert(
			ignore_permissions=True
		)

	return {"success": True, "data": {"visible": not hidden}}
