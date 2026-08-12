# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""VM Chat Message controller.

Identity is `client_id` (a UUID minted on the device before the row is inserted),
which is what makes sending idempotent across retries. Ordering is `seq`, a
per-room monotonic integer allocated by VMChatRoom.allocate_seq().

Messages are expected to be created through `vehicle_maintenance.api.chat`, which
allocates the seq. Creating one directly without a seq is rejected rather than
silently ordered wrong.
"""

import frappe
from frappe import _
from frappe.model.document import Document

from vehicle_maintenance.fleet_service.doctype.vm_chat_room.vm_chat_room import is_chat_supervisor


class VMChatMessage(Document):
	def validate(self) -> None:
		if not self.seq:
			frappe.throw(_("Chat messages must be created through the chat API."))
		self._validate_membership()
		self._validate_attachment()

	def _validate_membership(self) -> None:
		"""Defence in depth — the API checks too, but this closes the direct-REST path."""
		# Server-authored notices (an alert landing in a channel, "X assigned this
		# ticket to Y") have no human author to be a member. The generic REST path
		# is still shut: the has_permission hook below rejects a non-member for
		# every ptype, and send_message refuses these kinds outright.
		if self.kind in ("system", "alert"):
			return
		if is_chat_supervisor(self.author):
			return
		is_member = frappe.db.exists(
			"VM Chat Member",
			{"parent": self.room, "parenttype": "VM Chat Room", "user": self.author},
		)
		if not is_member:
			frappe.throw(_("You are not a member of this room."), frappe.PermissionError)

	def _validate_attachment(self) -> None:
		if self.kind in ("text", "system", "ticket", "alert"):
			return
		if not self.file_url:
			frappe.throw(_("A {0} message needs an attachment.").format(self.kind))
		# Chat attachments carry EXIF GPS. A public file_url would leak a
		# technician's coordinates to anyone who guesses the path.
		if not self.file_url.startswith("/private/files/"):
			frappe.throw(_("Chat attachments must be private files."))

	def after_insert(self) -> None:
		from vehicle_maintenance.fleet_service import chat_notify

		chat_notify.dispatch(self)

	def as_payload(self) -> dict:
		"""Wire shape consumed by the mobile client. Explicit field list, never SELECT *."""
		return {
			"name": self.name,
			"room": self.room,
			"seq": self.seq,
			"client_id": self.client_id,
			"author": self.author,
			"author_name": frappe.db.get_value("User", self.author, "full_name") or self.author,
			"kind": self.kind,
			"body": "" if self.deleted else (self.body or ""),
			"file_url": None if self.deleted else self.file_url,
			"file_name": self.file_name,
			"file_size": self.file_size,
			"duration_ms": self.duration_ms,
			"transcript": self.transcript,
			"reply_to": self.reply_to,
			"vehicle": self.vehicle,
			"ticket": self.ticket,
			"alert_event": self.alert_event,
			"mentions": [m.user for m in (self.mentions or [])],
			"geotagged": bool(self.geotagged),
			"lat": self.lat,
			"lon": self.lon,
			"deleted": bool(self.deleted),
			"created_at": str(self.creation),
		}


def on_doctype_update():
	"""Composite unique index on (room, seq).

	Enforces the ordering invariant at the storage layer, so a bug in the allocator
	surfaces as an integrity error instead of two messages silently sharing a
	position. Also serves the client's primary read query.
	"""
	frappe.db.add_unique("VM Chat Message", ["room", "seq"], constraint_name="unique_room_seq")


# ---------------------------------------------------------------- permissions


def get_permission_query_conditions(user: str | None = None) -> str:
	"""Limit message reads to rooms the user belongs to."""
	user = user or frappe.session.user
	if is_chat_supervisor(user):
		return ""
	escaped = frappe.db.escape(user)
	return f"""`tabVM Chat Message`.room in (
		select `parent` from `tabVM Chat Member`
		where `parenttype` = 'VM Chat Room' and `user` = {escaped}
	)"""


def has_permission(doc, ptype: str = "read", user: str | None = None) -> bool:
	user = user or frappe.session.user
	if is_chat_supervisor(user):
		return True
	return bool(
		frappe.db.exists(
			"VM Chat Member",
			{"parent": doc.room, "parenttype": "VM Chat Room", "user": user},
		)
	)
