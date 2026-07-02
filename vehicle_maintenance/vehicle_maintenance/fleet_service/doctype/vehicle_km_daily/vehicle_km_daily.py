"""Vehicle KM Daily — one telematics odometer row per vehicle per calendar day.

Raw fields (`start_km`, `end_km`, `distance_km`, `is_inactive`) are synced read-only
from the Naarni analytics-service. The correction section is operator-editable so a
day the vehicle was legitimately off the road (service / breakdown) can be excluded,
or its distance overridden, without the next sync clobbering the edit.

`effective_distance` is the single source of truth every rollup (KM billing + SLA)
must use — it folds corrections and exclusions into one number.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import flt, getdate, now_datetime

# Exclusion reasons that count as *planned* downtime — removed from the SLA
# denominator so known off-road days don't penalise uptime. Kept as a module
# constant so the policy is one line to change (see fleet_service/sla.py).
PLANNED_DOWNTIME_REASONS = ("Service", "Breakdown")

_CORRECTION_FIELDS = (
	"is_excluded",
	"exclusion_reason",
	"override_distance",
	"corrected_distance",
	"correction_notes",
)


class VehicleKMDaily(Document):
	def autoname(self):
		# Deterministic identity → the daily sync can upsert idempotently.
		self.row_key = self._make_key()
		self.name = self.row_key

	def _make_key(self) -> str:
		# '/' would break the docname-as-URL; registration numbers never legitimately
		# contain one, but normalise defensively.
		return f"{self.vehicle}::{getdate(self.date)}".replace("/", "-")

	def validate(self):
		if not self.row_key:
			self.row_key = self._make_key()
		self._validate_correction()

	def _validate_correction(self):
		if self.is_excluded and not self.exclusion_reason:
			frappe.throw(_("Select a reason (Service / Breakdown / Other) when excluding a day."))

		has_correction = bool(self.is_excluded) or bool(self.override_distance) or bool(self.correction_notes)
		if has_correction:
			changed = self.is_new() or any(self.has_value_changed(f) for f in _CORRECTION_FIELDS)
			if changed:
				self.corrected_by = frappe.session.user
				self.corrected_on = now_datetime()
		else:
			# A cleared correction should not keep a stale attribution.
			self.corrected_by = None
			self.corrected_on = None

	@property
	def effective_distance(self) -> float:
		"""Billable/counted distance for this day, honouring corrections.

		Precedence: an excluded day always contributes 0; otherwise an explicit
		distance override wins; otherwise the raw distance, clamped to >= 0 (so an
		odometer reset can never produce negative billable KM).
		"""
		if self.is_excluded:
			return 0.0
		if self.override_distance:
			return max(flt(self.corrected_distance), 0.0)
		return max(flt(self.distance_km), 0.0)

	@property
	def is_planned_downtime(self) -> bool:
		return bool(self.is_excluded) and (self.exclusion_reason in PLANNED_DOWNTIME_REASONS)
