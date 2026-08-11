"""Duty Shift — a reusable named duty window.

Kept deliberately small: a shift owns its times and its own lateness/half-day
policy, so "Night shift grace is 30 minutes" is a field on one record rather
than a branch in code.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import flt


class DutyShift(Document):
	def validate(self) -> None:
		self._validate_window()
		self._validate_thresholds()

	def _validate_window(self) -> None:
		if not self.start_time or not self.end_time:
			return
		# A same-day shift that ends at or before it starts is almost always a
		# missed "Ends Next Day" tick — catching it here beats a month of
		# zero-hour attendance nobody notices until payroll.
		if not self.crosses_midnight and str(self.end_time) <= str(self.start_time):
			frappe.throw(
				_(
					"{0} ends at or before it starts. Tick <b>Ends Next Day</b> if this is a night shift."
				).format(self.shift_name)
			)

	def _validate_thresholds(self) -> None:
		if flt(self.half_day_hours) > flt(self.full_day_hours):
			frappe.throw(_("Half Day hours cannot be greater than Full Day hours."))
