"""Duty Punch controller — one check-in or check-out.

The append-only event log behind Duty Attendance. Editing or deleting a punch
from Desk re-derives the day, so a correction never leaves the totals stale.
"""

from __future__ import annotations

from datetime import timedelta

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import get_datetime, now_datetime

# How far ahead of the server clock a punch may be before we call it a bad device
# clock rather than a real punch. Generous enough to absorb ordinary phone drift.
FUTURE_TOLERANCE = timedelta(minutes=10)


class DutyPunch(Document):
	def before_insert(self) -> None:
		# `client_uuid` carries a UNIQUE index. Frappe writes an unset Data field
		# as NULL (many NULLs are fine) but an empty string is a real value, and
		# the second Desk-created punch would collide with the first.
		if not (self.client_uuid or "").strip():
			self.client_uuid = None
		if not self.punch_time:
			self.punch_time = now_datetime()
		if not self.employee_name and self.user:
			self.employee_name = frappe.db.get_value("User", self.user, "full_name") or self.user

	def validate(self) -> None:
		# A punch far in the future is a device with a wrong clock, and it would
		# quietly poison hours and lateness for that day.
		if self.punch_time and get_datetime(self.punch_time) > now_datetime() + FUTURE_TOLERANCE:
			frappe.throw(_("Punch time is in the future. Check the device clock."))

	def on_update(self) -> None:
		self._refresh_day()

	def on_trash(self) -> None:
		self._refresh_day()

	def _refresh_day(self) -> None:
		"""Re-derive the parent day. Guarded: a punch must never be lost because
		the rollup hit a problem."""
		if not self.attendance or not frappe.db.exists("Duty Attendance", self.attendance):
			return
		if frappe.flags.in_roster_recompute:
			return
		try:
			from vehicle_maintenance.fleet_service import roster

			frappe.flags.in_roster_recompute = True
			roster.recompute(self.attendance)
		except Exception:
			frappe.log_error(title="Duty Punch recompute", message=f"{self.name}: {frappe.get_traceback()}")
		finally:
			frappe.flags.in_roster_recompute = False
