# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""VM Chat Room controller.

The room owns the per-room sequence counter that every ordering decision in the
mobile client depends on — see :meth:`VMChatRoom.allocate_seq`. It also owns
membership, which is the real access-control boundary for chat: DocType-level
role permissions grant the *ability* to use chat at all, while the permission
hooks at the bottom of this file restrict each user to their own rooms.
"""

import frappe
from frappe import _
from frappe.model.document import Document

# Roles that may see and administer any room regardless of membership. Kept
# narrow on purpose: a Service Engineer is not on this list, so they only ever
# see rooms they were actually added to.
CHAT_SUPERVISOR_ROLES = ("System Manager", "Central Ops", "Depot Manager")

# Room kinds that are meaningless without the thing they are about.
REQUIRED_SCOPE_FIELD = {
	"Vehicle": "vehicle",
	"Ticket": "ticket",
	"Job Card": "job_card",
	"Depot": "depot",
}


def is_chat_supervisor(user: str) -> bool:
	"""True when `user` holds a role that bypasses room membership."""
	return bool(set(frappe.get_roles(user)) & set(CHAT_SUPERVISOR_ROLES))


class VMChatRoom(Document):
	def validate(self) -> None:
		self._validate_members()
		self._validate_scope()

	def _validate_members(self) -> None:
		seen: set[str] = set()
		for m in self.members:
			if m.user in seen:
				frappe.throw(_("{0} is listed twice in members.").format(m.user))
			seen.add(m.user)
		if not seen:
			frappe.throw(_("A chat room needs at least one member."))

	def _validate_scope(self) -> None:
		"""A tagged room must actually carry the tag its kind promises."""
		field = REQUIRED_SCOPE_FIELD.get(self.kind)
		if field and not self.get(field):
			frappe.throw(_("A {0} room must be linked to a {0}.").format(self.kind))

	# ------------------------------------------------------------------ members

	def is_member(self, user: str) -> bool:
		return any(m.user == user for m in self.members)

	def member_row(self, user: str):
		"""The VM Chat Member child row for `user`, or None."""
		for m in self.members:
			if m.user == user:
				return m
		return None

	def member_users(self) -> list[str]:
		return [m.user for m in self.members]

	# ------------------------------------------------------------------ sequence

	def allocate_seq(self) -> int:
		"""Reserve the next per-room sequence number under a row lock.

		Returns a strictly increasing integer, unique within the room. ``FOR UPDATE``
		holds the lock until the surrounding transaction commits, which serialises
		concurrent senders: two phones posting in the same millisecond get 7 and 8,
		never 7 twice.

		This is deliberately not ``creation``/``modified``. Those collide inside a
		single millisecond and skew across app servers, and the mobile client relies
		on strict monotonicity to detect gaps after a dropped socket.
		"""
		current = frappe.db.get_value("VM Chat Room", self.name, "last_seq", for_update=True) or 0
		nxt = int(current) + 1
		frappe.db.set_value("VM Chat Room", self.name, "last_seq", nxt, update_modified=False)
		return nxt

	def allocate_delete_seq(self) -> int:
		"""Reserve the next tombstone number for this room, under the same row lock.

		A **second** counter rather than a number off `allocate_seq`, and the
		reason is unread. A member's unread count is `last_seq` minus their read
		cursor, and a cursor only ever moves to a seq that some message carries —
		so a deletion drawing from that counter would leave every member of the
		room with one unread message that does not exist, that they cannot open,
		and that nothing can ever mark read. The badge would simply stay lit.

		Deletions still need a number of their own, because a soft delete changes
		a row the delta sync has already handed out and would otherwise never
		mention again: `sync` returns messages *above* the client's cursor, and a
		message deleted long after it was sent is below it. The client therefore
		carries two cursors, and this counter is what the second one tracks.
		"""
		current = frappe.db.get_value("VM Chat Room", self.name, "last_delete_seq", for_update=True) or 0
		nxt = int(current) + 1
		frappe.db.set_value("VM Chat Room", self.name, "last_delete_seq", nxt, update_modified=False)
		return nxt

	def touch_last_message(self, preview: str, when) -> None:
		"""Denormalise the newest message onto the room for cheap list rendering."""
		frappe.db.set_value(
			"VM Chat Room",
			self.name,
			{"last_message_preview": (preview or "")[:280], "last_message_at": when},
			update_modified=False,
		)


# ---------------------------------------------------------------- permissions
# Wired in hooks.py. These are what stop a Technician from reading another
# depot's rooms through the generic /api/resource endpoints.


def get_permission_query_conditions(user: str | None = None) -> str:
	"""SQL predicate limiting room lists to rooms `user` is a member of."""
	user = user or frappe.session.user
	if is_chat_supervisor(user):
		return ""
	escaped = frappe.db.escape(user)
	return f"""`tabVM Chat Room`.name in (
		select `parent` from `tabVM Chat Member`
		where `parenttype` = 'VM Chat Room' and `user` = {escaped}
	)"""


def has_permission(doc, ptype: str = "read", user: str | None = None) -> bool:
	user = user or frappe.session.user
	if is_chat_supervisor(user):
		return True
	return any(m.user == user for m in (doc.members or []))
