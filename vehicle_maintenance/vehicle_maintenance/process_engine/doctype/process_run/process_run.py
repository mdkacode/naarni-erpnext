"""Process Run — one execution, and the numbers derived from it.

The controller deliberately keeps *derivation* here and *judgement* in
`evaluation`: this class recomputes counts, score and verdict from whatever
result rows exist, so a row edited in Desk produces the same totals as one saved
through the API. There is exactly one place that decides whether a run passed.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import cint, time_diff_in_seconds

from vehicle_maintenance.process_engine import constants as C
from vehicle_maintenance.process_engine import scanning, scoring


class ProcessRun(Document):
	def validate(self):
		self._snapshot_process_name()
		self._recompute()

	def before_insert(self):
		if not self.started_at:
			self.started_at = frappe.utils.now_datetime()
		if not self.started_by:
			self.started_by = frappe.session.user

	# ------------------------------------------------------------------ derive

	def _snapshot_process_name(self):
		if self.process_definition and not self.process_name:
			definition = frappe.db.get_value(
				"Process Definition",
				self.process_definition,
				["process_name", "version", "subject_doctype"],
				as_dict=True,
			)
			if definition:
				self.process_name = definition.process_name
				if not self.definition_version:
					self.definition_version = definition.version
				if not self.subject_doctype:
					self.subject_doctype = definition.subject_doctype

	def _recompute(self):
		"""Rebuild every derived number from the result and scan rows."""
		if not self.process_definition:
			return

		definition = frappe.get_cached_doc("Process Definition", self.process_definition)
		steps_by_code = {s.step_code: s.as_dict() for s in definition.steps or []}

		results = [r.as_dict() for r in self.results or []]
		for row in self.results or []:
			step = steps_by_code.get(row.step_code) or {}
			row.weight = step.get("weight", 1)
			row.score_earned = row.weight if row.is_pass and not cint(row.is_skipped) else 0

		totals = scoring.score_run(definition.as_dict(), results, steps_by_code)
		for field, value in totals.items():
			setattr(self, field, value)

		expected = scanning.expected_counts(list(steps_by_code.values()))
		self.trace_completeness_pct = scoring.trace_completeness(
			expected, [s.as_dict() for s in self.scans or []]
		)

		self.photo_counts()

		if self.completed_at and self.started_at:
			self.duration_seconds = int(time_diff_in_seconds(self.completed_at, self.started_at))

	def photo_counts(self):
		"""Mirror the per-step photo count onto each result row for the report."""
		counts: dict[str, int] = {}
		for photo in self.photos or []:
			if photo.step_code:
				counts[photo.step_code] = counts.get(photo.step_code, 0) + 1
		for row in self.results or []:
			row.photo_count = counts.get(row.step_code, 0)

	# ------------------------------------------------------------------ guards

	def ensure_open(self):
		"""Refuse further answers on a finished run."""
		if self.status in C.TERMINAL_STATUSES:
			frappe.throw(
				_("This run is {0} and can no longer be edited.").format(self.status),
				title=_("Run closed"),
			)

	def stage_is_blocked(self, stage_code: str) -> bool:
		blocked = {s.strip() for s in (self.blocked_stages or "").splitlines() if s.strip()}
		return stage_code in blocked

	def signoff_for(self, stage_code: str, level: str):
		for row in self.signoffs or []:
			if row.stage == stage_code and row.level == level:
				return row
		return None
