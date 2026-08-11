"""Duty Attendance controller — one engineer's day.

Everything read-only on this form is derived from Duty Punch by
`roster.recompute`. The one field a human owns is `manual_status`: the escape
hatch for the day the punches cannot describe (dead phone, direct-to-site visit).
It survives recomputation, and it demands a reason, so an adjusted day always
carries who adjusted it and why.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import getdate


class DutyAttendance(Document):
	def before_insert(self) -> None:
		if not self.dedup_key:
			self.dedup_key = f"{self.user}|{getdate(self.attendance_date)}"
		if not self.employee_name and self.user:
			self.employee_name = frappe.db.get_value("User", self.user, "full_name") or self.user

	def validate(self) -> None:
		if self.manual_status and not (self.override_reason or "").strip():
			frappe.throw(_("An overridden status needs a reason."))

	def on_update(self) -> None:
		# An override entered by hand in Desk has to take effect immediately rather
		# than waiting for the next punch. Only re-derive when the override itself
		# changed, so this never loops with `roster.recompute`'s own save.
		if frappe.flags.in_roster_recompute:
			return
		# `get_doc_before_save()` rather than `has_value_changed()`: the latter
		# reports True when there is no prior version, i.e. on every insert, and
		# recomputing mid-insert leaves the caller holding a stale doc that then
		# fails to save with a timestamp mismatch.
		before = self.get_doc_before_save()
		if not before or before.manual_status == self.manual_status:
			return
		try:
			from vehicle_maintenance.fleet_service import roster

			frappe.flags.in_roster_recompute = True
			roster.recompute(self.name)
		except Exception:
			frappe.log_error(
				title="Duty Attendance recompute", message=f"{self.name}: {frappe.get_traceback()}"
			)
		finally:
			frappe.flags.in_roster_recompute = False
