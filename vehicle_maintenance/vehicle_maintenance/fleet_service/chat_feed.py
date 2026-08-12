# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Server-authored chat posts, and the alert feed that produces most of them.

Two jobs live here:

* :func:`post` — the one way anything other than a phone puts a message in a
  room. It allocates the seq through the room's locked allocator exactly as
  ``send_message`` does, so a machine-written message can never collide with a
  human's or land out of order.
* :func:`broadcast_alert` — fans a new Alert Event into every channel an admin
  has opted in, which is what turns a chat room into the place a depot actually
  watches instead of a dashboard nobody has open.

Alert fan-out runs in a background job. A chat room being misconfigured, or a
socket being down, must never be able to roll back the alert itself — the alert
is the record of a vehicle in trouble and is far more important than its notice.
"""

import frappe

from vehicle_maintenance.fleet_service.chat_notify import preview_for

# Severities in ascending order, so a room's minimum is a simple index compare.
SEVERITY_RANK = {"warning": 1, "critical": 2}


def post(
	room: str,
	kind: str,
	body: str,
	author: str | None = None,
	*,
	client_id: str | None = None,
	ticket: str | None = None,
	vehicle: str | None = None,
	alert_event: str | None = None,
	mentions: list[str] | None = None,
) -> "frappe.model.document.Document":
	"""Insert a message into `room` and return it.

	`author` defaults to Administrator, which is what an alert or an automated
	notice is posted as. Membership is not checked here: `system` and `alert`
	messages are explicitly exempt in the controller, and every other kind is
	only ever reached through an API that has already checked.
	"""
	room_doc = frappe.get_doc("VM Chat Room", room)
	seq = room_doc.allocate_seq()

	msg = frappe.get_doc(
		{
			"doctype": "VM Chat Message",
			"room": room,
			"seq": seq,
			"client_id": client_id or frappe.generate_hash(length=32),
			"author": author or "Administrator",
			"kind": kind,
			"body": body or "",
			"ticket": ticket,
			"vehicle": vehicle,
			"alert_event": alert_event,
			"mentions": [{"user": u} for u in (mentions or [])],
		}
	)
	msg.insert(ignore_permissions=True)
	room_doc.touch_last_message(preview_for(msg), msg.creation)
	return msg


# ------------------------------------------------------------------ alert feed


def on_alert_event(doc, method=None) -> None:
	"""`Alert Event.after_insert` hook — hand the fan-out to a worker.

	Enqueued rather than inline because an alert burst (a depot losing GPS across
	forty buses at once) would otherwise pay chat fan-out inside the ingest
	request, and slow ingest is how alerts get dropped upstream.
	"""
	if not frappe.db.exists("DocType", "VM Chat Room"):
		return
	frappe.enqueue(
		method="vehicle_maintenance.fleet_service.chat_feed.broadcast_alert",
		queue="short",
		timeout=120,
		enqueue_after_commit=True,
		job_name=f"chat-alert:{doc.name}",
		alert_name=doc.name,
	)


def broadcast_alert(alert_name: str) -> None:
	"""Post `alert_name` into every alert channel whose scope matches it."""
	alert = frappe.db.get_value(
		"Alert Event",
		alert_name,
		[
			"name",
			"title",
			"severity",
			"vehicle",
			"registration_number",
			"message",
			"maps_link",
			"value_text",
			"parameter",
			"unit",
			"value",
		],
		as_dict=True,
	)
	if not alert:
		return

	depot = frappe.db.get_value("Vehicle", alert.vehicle, "depot") if alert.vehicle else None
	body = _alert_body(alert)

	for room in _channels_for(alert, depot):
		try:
			post(
				room,
				kind="alert",
				body=body,
				vehicle=alert.vehicle,
				alert_event=alert.name,
			)
		except Exception:
			# One misconfigured channel must not stop the others hearing about it.
			frappe.log_error(
				title=f"Alert fan-out to chat room {room} failed",
				message=frappe.get_traceback(),
			)


def _channels_for(alert, depot: str | None) -> list[str]:
	"""Rooms opted in to alerts whose depot/vehicle scope admits this alert.

	An empty scope field means "everything": a channel with no depot and no
	vehicle is the whole-fleet feed, which is what a central ops room wants.
	"""
	rank = SEVERITY_RANK.get((alert.severity or "warning").lower(), 1)
	rows = frappe.get_all(
		"VM Chat Room",
		filters={"is_active": 1, "broadcast_alerts": 1},
		fields=["name", "depot", "vehicle", "alert_min_severity"],
		limit_page_length=0,
	)
	out = []
	for row in rows:
		if row.get("depot") and row["depot"] != depot:
			continue
		if row.get("vehicle") and row["vehicle"] != alert.vehicle:
			continue
		if rank < SEVERITY_RANK.get((row.get("alert_min_severity") or "warning").lower(), 1):
			continue
		out.append(row["name"])
	return out


def _alert_body(alert) -> str:
	"""A line a person can act on, not a field dump."""
	head = (alert.title or "Alert").strip()
	reg = (alert.registration_number or alert.vehicle or "").strip()
	parts = [f"{(alert.severity or 'warning').upper()} · {head}"]
	if reg:
		parts.append(reg)

	reading = (alert.value_text or "").strip()
	if not reading and alert.parameter:
		value = alert.value
		reading = f"{alert.parameter} {value}{alert.unit or ''}" if value is not None else alert.parameter
	if reading:
		parts.append(reading)

	detail = (alert.message or "").strip()
	if detail:
		parts.append(detail)
	if alert.maps_link:
		parts.append(alert.maps_link)
	return "\n".join(parts)


# --------------------------------------------------------------- ticket notices


def announce_assignment(ticket: str, assignee: str, by: str, room: str | None = None) -> None:
	"""Post "X assigned this ticket to Y" wherever the ticket is being discussed.

	Without `room` this walks every room the ticket has been shared into, so the
	notice lands in the conversation people are actually reading rather than only
	in the one the assigner happened to be looking at.
	"""
	who = frappe.db.get_value("User", assignee, "full_name") or assignee
	actor = frappe.db.get_value("User", by, "full_name") or by
	body = f"{actor} assigned ticket {ticket} to {who}"

	rooms = [room] if room else _rooms_mentioning_ticket(ticket)
	for target in rooms:
		try:
			post(target, kind="system", body=body, ticket=ticket)
		except Exception:
			frappe.log_error(
				title=f"Ticket assignment notice failed (room {target})",
				message=frappe.get_traceback(),
			)


def _rooms_mentioning_ticket(ticket: str) -> list[str]:
	rows = frappe.get_all(
		"VM Chat Message",
		filters={"ticket": ticket, "kind": "ticket"},
		fields=["distinct room as room"],
		limit_page_length=0,
	)
	return [r["room"] for r in rows]


def resolve_severity_rank(value: str | None) -> int:
	"""Exposed for tests. Anything unrecognised is treated as the low end."""
	return SEVERITY_RANK.get((value or "warning").lower(), 1)
