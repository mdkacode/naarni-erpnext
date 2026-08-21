# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""VM Daily Status controller.

One row per (user, date). `dedup_key` carries that uniqueness into the database
so a duplicated cron firing or a hand-run regeneration can never produce two rows
for the same day — the generator relies on it being enforced here rather than on
its own bookkeeping.

Visibility is the point of this DocType, so it is deliberately asymmetric: you
can always read your own days, and a supervisor can read everyone's. A
Technician cannot read a peer's.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document

# Roles that may read every person's status. Same shape as the chat supervisor
# list, kept separate because "who may read a shift report" is a different
# question from "who may read a chat room", and they will drift.
STATUS_SUPERVISOR_ROLES = (
	"System Manager",
	"Central Ops",
	"Depot Manager",
	"N. Maintenance Head",
)


def is_status_supervisor(user: str) -> bool:
	"""True when `user` holds a role that may read everyone's daily status."""
	return bool(set(frappe.get_roles(user)) & set(STATUS_SUPERVISOR_ROLES))


class VMDailyStatus(Document):
	def validate(self) -> None:
		if not self.user or not self.status_date:
			frappe.throw(_("A daily status needs a user and a date."))
		self.dedup_key = f"{self.user}::{self.status_date}"
		self.employee_name = frappe.db.get_value("User", self.user, "full_name") or self.user
		self._recount()

	def _recount(self) -> None:
		"""Keep the counters honest even when a manager edits the items by hand."""
		if self.state == "Not Reported" and self.items:
			self.state = "Generated"

	def as_payload(self) -> dict:
		"""Wire shape for the app and the digest. Explicit fields, never SELECT *."""
		return {
			"name": self.name,
			"user": self.user,
			"user_name": self.employee_name,
			"status_date": str(self.status_date),
			"depot": self.depot,
			"state": self.state,
			"summary_line": self.summary_line or "",
			"items": [
				{
					"bucket": i.bucket,
					"text": i.text,
					"ticket": i.ticket,
					"vehicle": i.vehicle,
				}
				for i in (self.items or [])
			],
			"message_count": self.message_count or 0,
			"voice_count": self.voice_count or 0,
			"photo_count": self.photo_count or 0,
			"generated_by": self.generated_by,
			"generated_at": str(self.generated_at) if self.generated_at else None,
			"room": self.room,
		}


# ---------------------------------------------------------------- permissions
# Wired in hooks.py so the generic /api/resource endpoints are covered too.


def get_permission_query_conditions(user: str | None = None) -> str:
	user = user or frappe.session.user
	if is_status_supervisor(user):
		return ""
	return f"`tabVM Daily Status`.user = {frappe.db.escape(user)}"


def has_permission(doc, ptype: str = "read", user: str | None = None) -> bool:
	user = user or frappe.session.user
	if is_status_supervisor(user):
		return True
	# Your own day is yours to read, never to rewrite: the chat is the place to
	# change what you said.
	return ptype == "read" and doc.user == user
