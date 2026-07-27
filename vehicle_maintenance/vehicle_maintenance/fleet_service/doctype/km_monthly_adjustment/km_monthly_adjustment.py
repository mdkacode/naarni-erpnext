"""KM Monthly Adjustment — one month-level Dead KM lump per vehicle.

Subtracted from the vehicle's monthly billable KM in km_report.month_vehicle_rollup,
on top of any per-day Dead KM logged on Vehicle KM Daily. Deterministic name so it
upserts one row per (vehicle, month).
"""

import frappe
from frappe.model.document import Document
from frappe.utils import flt, now_datetime


class KMMonthlyAdjustment(Document):
	def autoname(self):
		self.row_key = self._make_key()
		self.name = self.row_key

	def _make_key(self) -> str:
		return f"{self.vehicle}::{self.year_month}".replace("/", "-")

	def validate(self):
		if not self.row_key:
			self.row_key = self._make_key()
		# A dead-KM deduction can never be negative.
		self.dead_km = max(flt(self.dead_km), 0.0)
		self.adjusted_by = frappe.session.user
		self.adjusted_on = now_datetime()
