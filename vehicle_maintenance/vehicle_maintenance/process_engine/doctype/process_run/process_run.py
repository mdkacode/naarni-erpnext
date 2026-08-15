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


# ------------------------------------------------------------------ visibility

#: Roles that may see and act on *anyone's* inspection.
#:
#: Everything outside this set — in practice a plain `Process Operator` — sees
#: only the runs it started. That is the whole isolation rule, and it lives here
#: rather than in each API method so no endpoint can forget it.
SUPERVISOR_ROLES = frozenset(
	{
		"System Manager",
		"Administrator",
		"Process Author",
		"Process Verifier",
		"Process Viewer",
		"Central Ops",
		"Depot Manager",
	}
)


def is_process_supervisor(user: str | None = None) -> bool:
	user = user or frappe.session.user
	if user == "Administrator":
		return True
	return bool(SUPERVISOR_ROLES & set(frappe.get_roles(user)))


def get_permission_query_conditions(user: str | None = None) -> str:
	"""SQL predicate limiting run *lists* to the operator's own work.

	The DocType grants `Process Operator` read and write on Process Run with
	`if_owner = 0`, which by itself lets any operator open — and answer into —
	somebody else's inspection. Narrowing it here rather than tightening the role
	is deliberate: a supervisor genuinely needs to read every run to verify one,
	so the distinction is per-user, not per-row-owner, and `if_owner` cannot
	express it.

	Scoped on `started_by` rather than `owner`. They are the same person today,
	but `owner` is a Frappe bookkeeping field that a data import or a support
	fix can change, and who *performed* an inspection is a fact about the plant.
	"""
	user = user or frappe.session.user
	if is_process_supervisor(user):
		return ""
	return f"`tabProcess Run`.started_by = {frappe.db.escape(user)}"


def has_permission(doc, ptype: str = "read", user: str | None = None) -> bool:
	"""Document-level twin of the query condition.

	Frappe consults this only after the role check has already passed, so it can
	narrow access but never widen it — which is the property that makes it safe
	to put the whole rule in one place.
	"""
	user = user or frappe.session.user
	if is_process_supervisor(user):
		return True
	return (doc.started_by or doc.owner) == user
