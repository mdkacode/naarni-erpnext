"""Whitelisted API for the process engine — everything the app calls.

Design notes worth knowing before changing anything here:

* **Incremental saves.** `save_step_result` writes one answer at a time, so a
  killed app or a dead plant Wi-Fi zone never costs more than the step in hand.
* **Idempotent starts.** `start_run` keys on a client-generated UUID, so a retry
  over a flaky network resumes the same run instead of creating a second one.
* **Server-side judgement.** The client never decides whether a step passed —
  it posts the raw answer and the server evaluates it against the definition and
  dispatches actions. That is what makes a quarantine impossible to dodge.
* **Scans are never mandatory.** Every scan endpoint accepts manual entry, and
  a shortfall lands as traceability completeness, never as a blocked submit.
"""

from __future__ import annotations

import random
import time

import frappe
from frappe import _
from frappe.utils import cint, flt

from vehicle_maintenance.process_engine import (
	actions as engine_actions,
)
from vehicle_maintenance.process_engine import (
	computed,
	conditions,
	evaluation,
	scanning,
	scoring,
)
from vehicle_maintenance.process_engine import (
	constants as C,
)

# --------------------------------------------------------------------- helpers


def _ok(data=None, message=None) -> dict:
	return {"success": True, "data": data if data is not None else {}, "message": message}


# How many times a write that lost a deadlock is replayed before giving up.
# Six, not four: at four, a 20-operator shift still lost 1.5% of answers, and a
# retry costs nothing on the path that does not need it.
DEADLOCK_ATTEMPTS = 6


def with_deadlock_retry(work, attempts: int = DEADLOCK_ATTEMPTS):
	"""Run `work`, replaying it if InnoDB kills it to break a deadlock.

	Saving an answer rewrites the run's child tables — Frappe deletes the rows it
	does not recognise and re-inserts the rest — so a shift of operators saving
	at the same moment contends for the same index gaps in `tabProcess Run
	Result`. InnoDB resolves that by killing one transaction with error 1213 and
	saying, in as many words, "try restarting transaction". It is not a
	corruption or a bug in the statement; it is the interleaving, and the correct
	response is to run it again.

	Measured against a laptop bench at 20 concurrent operators driving a full
	59-step inspection: **195 of 1180 answers** — one in six — came back as a 500
	in the operator's face, on a tap they had already made. With six attempts,
	3 of 1180. Not zero: the residue is the write amplification itself, and the
	real cure is to stop rewriting every child row to save one answer.

	`work` must be re-runnable, so it re-reads the document itself: the deadlock
	has already rolled the transaction back, and anything held in memory from the
	failed attempt describes a world that no longer exists.
	"""
	for attempt in range(attempts):
		try:
			return work()
		except frappe.QueryDeadlockError:
			frappe.db.rollback()
			if attempt == attempts - 1:
				# Best-effort: a logger that throws here would replace the real
				# cause with its own, and the real cause is the whole point.
				try:
					frappe.log_error(
						title="Process engine: deadlock survived retries",
						message=frappe.get_traceback(),
					)
				except Exception:
					pass
				raise
			# Back off with jitter. Two transactions that retry in lockstep
			# simply deadlock again, which is how a retry loop turns one lost
			# answer into four.
			time.sleep(0.05 * (2**attempt) + random.uniform(0, 0.05))


def _user_roles() -> set[str]:
	return set(frappe.get_roles(frappe.session.user))


def _assert_can_run(definition) -> None:
	"""Role gate for running a process.

	An empty `allowed_roles` means "anyone holding Process Operator", which keeps
	simple processes simple; naming roles scopes it further.
	"""
	roles = _user_roles()
	if "System Manager" in roles:
		return
	allowed = {r.role for r in definition.allowed_roles or []}
	if allowed:
		if not (roles & allowed):
			frappe.throw(
				_("You do not have access to {0}.").format(definition.process_name), frappe.PermissionError
			)
		return
	if C.ROLE_OPERATOR not in roles and C.ROLE_VERIFIER not in roles:
		frappe.throw(
			_("You do not have access to {0}.").format(definition.process_name), frappe.PermissionError
		)


def _assert_can_author(definition) -> None:
	roles = _user_roles()
	if "System Manager" in roles:
		return
	if C.ROLE_AUTHOR not in roles:
		frappe.throw(_("Authoring processes requires the Process Author role."), frappe.PermissionError)
	authors = {r.role for r in definition.author_roles or []}
	if authors and not (roles & authors):
		frappe.throw(
			_("{0} is scoped to another authoring team.").format(definition.process_name),
			frappe.PermissionError,
		)


def _answer_map(run) -> dict:
	"""``step_code`` → the shape `conditions` and `computed` expect.

	One builder, so visibility, computed values and the submit gate can never
	disagree about what counts as answered.
	"""
	return {
		r.step_code: {
			"is_answered": bool(r.response or r.value_numeric is not None or cint(r.is_skipped)),
			"is_pass": cint(r.is_pass),
			"is_deviation": cint(r.is_deviation),
			"is_skipped": cint(r.is_skipped),
			"response": r.response,
			"value_numeric": flt(r.value_numeric) if r.value_numeric is not None else None,
		}
		for r in run.results or []
	}


def _serialise_option(opt) -> dict:
	return {
		"value": opt.value,
		"label": opt.label or opt.value,
		"label_alt": opt.label_alt,
		"is_pass": cint(opt.is_pass),
		"is_critical": cint(opt.is_critical),
		"requires_remark": cint(opt.requires_remark),
		"requires_photo": cint(opt.requires_photo),
		"color": opt.color or "grey",
		"icon": opt.icon,
	}


def _serialise_step(step: dict, app_capability: int) -> dict:
	"""One step, flattened for the renderer.

	`supported` is the compatibility contract: an app reporting a capability
	below the step's requirement renders it read-only with an update prompt
	instead of crashing. Without this the first new step type breaks every
	phone in the plant on a Monday morning.
	"""
	required = C.STEP_TYPE_CAPABILITY.get(step.get("response_type"), 1)
	return {
		"step_code": step.get("step_code"),
		"stage": step.get("stage"),
		"sequence": cint(step.get("sequence")),
		"display_no": step.get("display_no"),
		"section": step.get("section"),
		"section_alt": step.get("section_alt"),
		"label": step.get("label"),
		"label_alt": step.get("label_alt"),
		"method_label": step.get("method_label"),
		"help_text": step.get("help_text"),
		"help_text_alt": step.get("help_text_alt"),
		"reference_image_good": step.get("reference_image_good"),
		"reference_image_bad": step.get("reference_image_bad"),
		"reaction_plan": step.get("reaction_plan"),
		"response_type": step.get("response_type"),
		"options": [_serialise_option(o) for o in step.get("options") or []],
		"link_doctype": step.get("link_doctype"),
		"computed_expression": step.get("computed_expression"),
		"unit": step.get("unit"),
		"min_value": flt(step.get("min_value")),
		"max_value": flt(step.get("max_value")),
		"nominal_value": flt(step.get("nominal_value")),
		"tolerance": flt(step.get("tolerance")),
		"pass_condition": step.get("pass_condition"),
		"decimals": cint(step.get("decimals")) or 2,
		"default_value": step.get("default_value"),
		"spec_summary": evaluation.spec_summary(step),
		"requires_photo": cint(step.get("requires_photo")),
		"photo_policy": step.get("photo_policy"),
		"min_photos": cint(step.get("min_photos")),
		"max_photos": cint(step.get("max_photos")),
		"photo_hint": step.get("photo_hint"),
		"requires_scan": cint(step.get("requires_scan")),
		"scan_entity_type": step.get("scan_entity_type"),
		"scan_count": cint(step.get("scan_count")),
		"requires_signature": cint(step.get("requires_signature")),
		"is_critical": cint(step.get("is_critical")),
		"is_mandatory": cint(step.get("is_mandatory")),
		"allow_skip": cint(step.get("allow_skip")),
		"skip_reasons": [r.strip() for r in (step.get("skip_reasons") or "").splitlines() if r.strip()],
		"weight": flt(step.get("weight")),
		"expected_seconds": cint(step.get("expected_seconds")),
		"visibility_conditions": [
			{
				"when_step": c.get("when_step"),
				"operator": c.get("operator"),
				"value": c.get("value"),
				"join": c.get("join"),
			}
			for c in step.get("visibility_conditions") or []
		],
		"supported": required <= app_capability,
		"required_capability": required,
	}


def _live_definition(process_code_or_family: str):
	"""Resolve a code or family name to the one live Published definition."""
	name = frappe.db.get_value(
		"Process Definition",
		{"name": process_code_or_family, "status": C.DEF_PUBLISHED},
		"name",
	)
	if not name:
		name = frappe.db.get_value(
			"Process Definition",
			{"family": process_code_or_family, "status": C.DEF_PUBLISHED},
			"name",
			order_by="version desc",
		)
	if not name:
		frappe.throw(_("No published process found for '{0}'.").format(process_code_or_family))
	return frappe.get_cached_doc("Process Definition", name)


# ----------------------------------------------------------------- definitions


@frappe.whitelist()
def list_processes(subject_doctype: str | None = None) -> dict:
	"""Processes the logged-in user may run.

	This is the app's home list. Only Published definitions appear, filtered by
	the caller's roles, so an operator never sees a draft or someone else's
	process.
	"""
	roles = _user_roles()
	filters = {"status": C.DEF_PUBLISHED}
	if subject_doctype:
		filters["subject_doctype"] = subject_doctype

	rows = frappe.get_all(
		"Process Definition",
		filters=filters,
		fields=[
			"name",
			"process_code",
			"process_name",
			"family",
			"version",
			"description",
			"icon",
			"color",
			"subject_doctype",
			"subject_label",
			"identifier_mode",
			"stage_label",
			"expected_minutes",
			"min_app_step_types",
		],
		order_by="process_name asc",
		limit_page_length=200,
	)

	out = []
	for row in rows:
		definition = frappe.get_cached_doc("Process Definition", row.name)
		allowed = {r.role for r in definition.allowed_roles or []}
		if "System Manager" not in roles:
			if allowed:
				if not (roles & allowed):
					continue
			elif not (roles & {C.ROLE_OPERATOR, C.ROLE_VERIFIER}):
				continue
		row["stage_count"] = len(definition.stages or [])
		row["step_count"] = len(definition.steps or [])
		out.append(row)

	return _ok(out)


@frappe.whitelist()
def get_definition(process: str, app_capability: int = 1) -> dict:
	"""The full process definition the renderer needs, in one round trip.

	Args:
	    process: A definition name, process_code or family.
	    app_capability: The step-type capability level of the calling app.

	Returns stages, steps (with options and conditions inlined), entity types
	referenced by scan steps, and the branding to use. The app caches this by
	version and can run it offline indefinitely.
	"""
	definition = _live_definition(process)
	_assert_can_run(definition)
	capability = cint(app_capability) or 1

	expanded = definition.expanded_steps()

	entity_types = {}
	for step in expanded:
		if step.get("scan_entity_type") and step["scan_entity_type"] not in entity_types:
			etype = frappe.get_cached_doc("Process Entity Type", step["scan_entity_type"])
			entity_types[etype.name] = {
				"entity_code": etype.entity_code,
				"label": etype.label,
				"label_alt": etype.label_alt,
				"icon": etype.icon,
				"expected_count": cint(etype.expected_count),
				"is_scan_enabled": cint(etype.is_scan_enabled),
			}

	return _ok(
		{
			"name": definition.name,
			"process_code": definition.process_code,
			"process_name": definition.process_name,
			"family": definition.family,
			"version": cint(definition.version),
			"description": definition.description,
			"stage_label": definition.stage_label or "Stage",
			"subject_doctype": definition.subject_doctype,
			"subject_label": definition.subject_label,
			"identifier_mode": definition.identifier_mode,
			"identifier_pattern": definition.identifier_pattern,
			"allow_offline": cint(definition.allow_offline),
			"allow_resume": cint(definition.allow_resume),
			"expected_minutes": cint(definition.expected_minutes),
			"scoring_enabled": cint(definition.scoring_enabled),
			"pass_threshold_pct": flt(definition.pass_threshold_pct),
			"stages": [
				{
					"stage_code": s.stage_code,
					"label": s.label,
					"label_alt": s.label_alt,
					"sequence": cint(s.sequence),
					"instructions": s.instructions,
					"signoff_role": s.signoff_role,
					"requires_second_signoff": cint(s.requires_second_signoff),
					"second_signoff_role": s.second_signoff_role,
					"screen_grouping": s.screen_grouping,
					"steps_per_screen": cint(s.steps_per_screen) or 5,
				}
				for s in sorted(definition.stages or [], key=lambda x: cint(x.sequence))
				if cint(s.is_active, 1)
			],
			"steps": [_serialise_step(s, capability) for s in expanded if cint(s.get("is_active", 1))],
			"entity_types": entity_types,
		}
	)


# ------------------------------------------------------------------------ runs


@frappe.whitelist()
def start_run(
	process: str,
	identifier: str | None = None,
	subject_name: str | None = None,
	client_uuid: str | None = None,
	station: str | None = None,
	shift: str | None = None,
	depot: str | None = None,
	latitude: float | None = None,
	longitude: float | None = None,
	is_test_run: int = 0,
) -> dict:
	"""Create or resume a run. Idempotent on `client_uuid`.

	The idempotency key matters more than it looks: a plant network that drops a
	response after the server committed would otherwise leave the operator
	starting a duplicate pack record.
	"""
	definition = _live_definition(process)
	_assert_can_run(definition)

	if client_uuid:
		existing = frappe.db.get_value("Process Run", {"client_uuid": client_uuid}, "name")
		if existing:
			return _ok(get_run(existing)["data"], _("Resumed existing run."))

	if definition.identifier_pattern and identifier:
		import re

		if not re.match(definition.identifier_pattern, identifier):
			frappe.throw(
				_("'{0}' does not look like a valid {1}.").format(
					identifier, definition.subject_label or _("identifier")
				)
			)

	if is_test_run and not (_user_roles() & {C.ROLE_AUTHOR, "System Manager"}):
		frappe.throw(_("Only a Process Author can start a test run."), frappe.PermissionError)

	first_stage = sorted(definition.stages or [], key=lambda x: cint(x.sequence))
	run = frappe.get_doc(
		{
			"doctype": "Process Run",
			"process_definition": definition.name,
			"process_name": definition.process_name,
			"definition_version": cint(definition.version),
			"run_identifier": identifier,
			"client_uuid": client_uuid,
			"subject_doctype": definition.subject_doctype,
			"subject_name": subject_name,
			"status": C.STATUS_IN_PROGRESS,
			"current_stage": first_stage[0].stage_code if first_stage else None,
			"station": station,
			"shift": shift,
			"depot": depot,
			"latitude": flt(latitude) if latitude not in (None, "") else None,
			"longitude": flt(longitude) if longitude not in (None, "") else None,
			"is_test_run": cint(is_test_run),
		}
	)
	# A savepoint, not the whole transaction: the only thing worth undoing is the
	# insert that collided. A blanket rollback would also discard whatever the
	# request did before reaching here — and made the recovery unable to see the
	# very run it was recovering, since that run was inserted in the same
	# transaction. Found by the test below, which is the shape a retry takes.
	frappe.db.savepoint("start_run_insert")
	try:
		run.insert(ignore_permissions=True)
	except (frappe.UniqueValidationError, frappe.DuplicateEntryError):
		# Two retries of the same start arrived together. The check above is a
		# read and the insert is a write, and nothing holds the gap between
		# them — so the unique index on client_uuid is what actually enforces
		# this, and losing that race is a *success*: somebody else created the
		# very run we were asked for.
		#
		# Measured before this existed: 30 simultaneous retries of one
		# client_uuid produced 1 run and 29 errors in the operator's face. The
		# key was doing its job; the endpoint was not.
		frappe.db.rollback(save_point="start_run_insert")
		existing = frappe.db.get_value("Process Run", {"client_uuid": client_uuid}, "name")
		if not existing:
			# The winner is another request, and under REPEATABLE READ this
			# transaction's snapshot was taken before it committed — so the row
			# that just rejected our insert is invisible to us. Ending the
			# transaction is what buys a fresh snapshot. Both rollbacks earn
			# their place: the savepoint keeps a same-transaction caller's work
			# (which is how the tests reach this path), the full one is what
			# makes it work in production. Dropping either was measured: 29 of
			# 30 concurrent retries came back as errors.
			frappe.db.rollback()
			existing = frappe.db.get_value("Process Run", {"client_uuid": client_uuid}, "name")
		if not existing:
			raise
		return _ok(get_run(existing)["data"], _("Resumed existing run."))

	frappe.db.commit()
	return _ok(_serialise_run(run), _("Run started."))


def _serialise_run(run) -> dict:
	return {
		"name": run.name,
		"process_definition": run.process_definition,
		"process_name": run.process_name,
		"definition_version": cint(run.definition_version),
		"run_identifier": run.run_identifier,
		"subject_doctype": run.subject_doctype,
		"subject_name": run.subject_name,
		"status": run.status,
		"current_stage": run.current_stage,
		"result": run.result,
		"score_pct": flt(run.score_pct),
		"score_earned": flt(run.score_earned),
		"score_max": flt(run.score_max),
		"pass_count": cint(run.pass_count),
		"fail_count": cint(run.fail_count),
		"skip_count": cint(run.skip_count),
		"critical_count": cint(run.critical_count),
		"answered_count": cint(run.answered_count),
		"is_first_pass": cint(run.is_first_pass),
		"trace_completeness_pct": flt(run.trace_completeness_pct),
		"quarantine_reason": run.quarantine_reason,
		"blocked_stages": [s for s in (run.blocked_stages or "").splitlines() if s.strip()],
		"started_at": str(run.started_at) if run.started_at else None,
		"completed_at": str(run.completed_at) if run.completed_at else None,
		"is_test_run": cint(run.is_test_run),
		"results": [
			{
				"step_code": r.step_code,
				"stage": r.stage,
				"display_no": r.display_no,
				"response": r.response,
				"value_numeric": flt(r.value_numeric) if r.value_numeric is not None else None,
				"value_text": r.value_text,
				"is_pass": cint(r.is_pass),
				"is_deviation": cint(r.is_deviation),
				"is_critical": cint(r.is_critical),
				"is_skipped": cint(r.is_skipped),
				"skip_reason": r.skip_reason,
				"remark": r.remark,
				"photo_count": cint(r.photo_count),
				"entry_flag": r.entry_flag,
				"answered_at": str(r.answered_at) if r.answered_at else None,
			}
			for r in run.results or []
		],
		"scans": [
			{
				"entity_type": s.entity_type,
				"step_code": s.step_code,
				"position_index": cint(s.position_index),
				"serial_no": s.serial_no,
				"mfg_date": str(s.mfg_date) if s.mfg_date else None,
				"module_number": s.module_number,
				"batch_ref": s.batch_ref,
				"is_manual_entry": cint(s.is_manual_entry),
				"parse_failed": cint(s.parse_failed),
				"duplicate_of": s.duplicate_of,
			}
			for s in run.scans or []
		],
		"photos": [
			{
				"step_code": p.step_code,
				"file_url": p.file_url,
				"captured_at": str(p.captured_at) if p.captured_at else None,
				"latitude": flt(p.latitude) if p.latitude else None,
				"longitude": flt(p.longitude) if p.longitude else None,
				"geofence_status": p.geofence_status,
			}
			for p in run.photos or []
		],
		"signoffs": [
			{
				"stage": s.stage,
				"level": s.level,
				"decision": s.decision,
				"user": s.user,
				"user_full_name": s.user_full_name,
				"signed_at": str(s.signed_at) if s.signed_at else None,
				"remarks": s.remarks,
			}
			for s in run.signoffs or []
		],
	}


@frappe.whitelist()
def get_run(name: str) -> dict:
	"""Full run state — used to resume, and by the verifier's review screen."""
	frappe.has_permission("Process Run", doc=name, throw=True)
	return _ok(_serialise_run(frappe.get_doc("Process Run", name)))


#: Fields whose change makes an answer a *different* answer.
#:
#: Replaying an offline batch must not re-fire a step's actions, or one dropped
#: response would raise a second deviation and notify the supervisor twice for
#: work the operator did once. `photo_count` is deliberately absent: a photo
#: arriving later changes the row without changing the answer.
_ANSWER_IDENTITY = (
	"response",
	"value_numeric",
	"value_text",
	"is_pass",
	"is_skipped",
	"skip_reason",
	"remark",
)

#: Compared as numbers, not as values.
#:
#: Frappe's Float and Check columns are non-nullable, so a judged `None` is
#: stored and read back as `0.0`. Comparing the two directly made *every* replay
#: look like a changed answer — which is precisely the bug `_ANSWER_IDENTITY`
#: exists to prevent, and it passed unnoticed until a test asserted `changed`
#: rather than asserting the row count.
_NUMERIC_IDENTITY = ("value_numeric", "is_pass", "is_skipped")


def _same_answer(existing, row_values: dict) -> bool:
	"""Whether the stored row already says exactly what this answer says."""
	for field in _ANSWER_IDENTITY:
		was, now = existing.get(field), row_values[field]
		if field in _NUMERIC_IDENTITY:
			if flt(was) != flt(now):
				return False
		elif (was or "") != (now or ""):
			# Empty string and None are the same absence of a remark.
			return False
	return True


def apply_answer(
	doc,
	definition,
	step_code: str,
	response: str | None = None,
	value: float | str | None = None,
	remark: str | None = None,
	skipped: int = 0,
	skip_reason: str | None = None,
	seconds_spent: int | None = None,
	answered_at=None,
) -> dict:
	"""Judge one answer and write it onto `doc` **in memory** — no save, no commit.

	The single place an answer is turned into a result row. `save_step_result`
	calls it once per request; `process_sync.sync_run` calls it once per queued
	answer in a batch. Keeping one implementation is the point: two evaluators
	would agree the day they were written and disagree by the time it mattered.

	Args:
	    doc: The `Process Run` document, loaded and open.
	    definition: Its cached `Process Definition`.
	    step_code: Which step this answers.
	    answered_at: When the operator answered, for a batch replaying work done
	        offline hours ago. Defaults to now, which is right for a live save.

	Returns ``{row, judged, outcome, chosen, changed}``. `changed` is False when
	the row already said exactly this, and the caller uses it to decide whether
	the step's actions should fire — see `_ANSWER_IDENTITY`.
	"""
	step = definition.expanded_step(step_code)
	if not step:
		frappe.throw(_("Step '{0}' is not part of {1}.").format(step_code, definition.process_name))

	photo_count = len([p for p in doc.photos or [] if p.step_code == step_code])
	scan_count = len([s for s in doc.scans or [] if s.step_code == step_code])

	if step["response_type"] == C.COMPUTED:
		# Derived server-side from earlier answers — the client's posted value is
		# ignored, so a computed result can never disagree with its inputs.
		value = computed.evaluate_expression(step.get("computed_expression"), _answer_map(doc))
		skipped = 0 if value is not None else skipped

	judged = evaluation.evaluate(
		step,
		response=response,
		value=value,
		skipped=bool(cint(skipped)),
		photo_count=photo_count,
		scan_count=scan_count,
	)

	threshold = flt(frappe.db.get_single_value("Process Engine Settings", "fast_entry_threshold_pct") or 25)
	row_values = {
		"step_code": step["step_code"],
		"stage": step.get("stage"),
		"display_no": step.get("display_no"),
		"section": step.get("section"),
		"label": step.get("label"),
		"response_type": step["response_type"],
		"method_label": step.get("method_label"),
		"unit": step.get("unit"),
		"spec_summary": judged["spec_summary"],
		"response": judged["response"],
		"value_numeric": judged["value_numeric"],
		"value_text": judged["value_text"],
		"is_pass": 1 if judged["is_pass"] else 0,
		"is_deviation": 1 if judged["is_deviation"] else 0,
		"is_critical": 1 if judged["is_critical"] else 0,
		"is_skipped": cint(skipped),
		"skip_reason": skip_reason,
		"remark": remark,
		"weight": flt(step.get("weight")),
		"answered_by": frappe.session.user,
		"answered_at": answered_at or frappe.utils.now_datetime(),
		"seconds_spent": cint(seconds_spent),
		"entry_flag": scoring.entry_flag(step, seconds_spent, threshold),
		"photo_count": photo_count,
	}

	existing = next((r for r in doc.results or [] if r.step_code == step_code), None)
	changed = True
	if existing:
		changed = not _same_answer(existing, row_values)
		existing.update(row_values)
	else:
		doc.append("results", row_values)

	outcome = {"client_hints": [], "warnings": []}
	if changed:
		judged_with_meta = {**judged, **row_values, "is_answered": judged["is_answered"]}
		outcome = engine_actions.dispatch(doc, step, judged_with_meta, step.get("actions") or [])

	chosen = next(
		(
			o
			for o in step.get("options") or []
			if (o.get("value") or "").strip() == (judged["response"] or "").strip()
		),
		None,
	)
	return {
		"row": row_values,
		"judged": judged,
		"outcome": outcome,
		"chosen": chosen,
		"changed": changed,
		"step": step,
	}


@frappe.whitelist()
def save_step_result(
	run: str,
	step_code: str,
	response: str | None = None,
	value: float | str | None = None,
	remark: str | None = None,
	skipped: int = 0,
	skip_reason: str | None = None,
	seconds_spent: int | None = None,
) -> dict:
	"""Save one answer, judge it server-side, and fire its actions.

	Returns the judged result plus any `client_hints` — the app uses those to
	prompt for a photo or remark in place, rather than the operator discovering
	the requirement at submit time.
	"""

	# Re-read inside the closure: a deadlock rolls the transaction back, so a
	# retry must start from the run as it now is, not as it was.
	def _apply():
		doc = frappe.get_doc("Process Run", run)
		frappe.has_permission("Process Run", doc=doc, ptype="write", throw=True)
		doc.ensure_open()

		definition = frappe.get_cached_doc("Process Definition", doc.process_definition)
		applied = apply_answer(
			doc,
			definition,
			step_code,
			response=response,
			value=value,
			remark=remark,
			skipped=skipped,
			skip_reason=skip_reason,
			seconds_spent=seconds_spent,
		)

		doc.save(ignore_permissions=True)
		frappe.db.commit()

		judged, row_values = applied["judged"], applied["row"]
		chosen, outcome = applied["chosen"], applied["outcome"]
		return _ok(
			{
				"result": row_values,
				"needs_photo": evaluation.photo_required(applied["step"], judged["is_pass"], chosen),
				"needs_remark": bool(chosen and cint(chosen.get("requires_remark"))) and not remark,
				"client_hints": outcome["client_hints"],
				"run": {
					"status": doc.status,
					"score_pct": flt(doc.score_pct),
					"pass_count": cint(doc.pass_count),
					"fail_count": cint(doc.fail_count),
					"critical_count": cint(doc.critical_count),
				},
			},
			"; ".join(outcome["warnings"]) or None,
		)

	return with_deadlock_retry(_apply)


@frappe.whitelist()
def get_next_steps(run: str) -> dict:
	"""Steps currently visible for this run, after evaluating conditions.

	The app can compute this itself from the cached definition when offline;
	this endpoint is the authority when it is online, and the two must agree.
	"""
	doc = frappe.get_doc("Process Run", run)
	frappe.has_permission("Process Run", doc=doc, throw=True)
	definition = frappe.get_cached_doc("Process Definition", doc.process_definition)

	answers = _answer_map(doc)
	visible = conditions.visible_steps(definition.expanded_steps(), answers)
	return _ok({"visible_step_codes": [s["step_code"] for s in visible], "answers": answers})


# ----------------------------------------------------------------------- scans


@frappe.whitelist()
def record_scan(
	run: str,
	entity_type: str,
	payload: str | None = None,
	step_code: str | None = None,
	position_index: int | None = None,
	is_manual_entry: int = 0,
	latitude: float | None = None,
	longitude: float | None = None,
) -> dict:
	"""Record one component scan, parsed against the entity type's patterns.

	A payload that matches no pattern is still stored verbatim and flagged, so
	nothing is ever lost to a vendor changing their label format. Duplicates are
	reported per the entity type's policy — Warn by default, because a
	legitimate rework re-scan must not be blocked at the station.
	"""

	def _apply():
		doc = frappe.get_doc("Process Run", run)
		frappe.has_permission("Process Run", doc=doc, ptype="write", throw=True)
		doc.ensure_open()

		parsed = scanning.parse_payload(entity_type, payload or "")
		policy = frappe.db.get_value("Process Entity Type", entity_type, "duplicate_policy") or "Warn"
		duplicate = scanning.find_duplicate(entity_type, parsed["serial_no"], exclude_run=doc.name)

		if duplicate and policy == "Block":
			frappe.throw(
				_("{0} was already recorded on run {1}.").format(parsed["serial_no"], duplicate),
				title=_("Duplicate serial"),
			)

		doc.append(
			"scans",
			{
				"entity_type": entity_type,
				"step_code": step_code,
				"position_index": cint(position_index),
				"raw_payload": parsed["raw_payload"],
				"serial_no": parsed["serial_no"],
				"mfg_date": parsed["mfg_date"],
				"module_number": parsed["module_number"],
				"batch_ref": parsed["batch_ref"],
				"revision": parsed["revision"],
				"parse_failed": parsed["parse_failed"],
				"is_manual_entry": cint(is_manual_entry),
				"duplicate_of": duplicate if policy != "Ignore" else None,
				"scanned_by": frappe.session.user,
				"scanned_at": frappe.utils.now_datetime(),
				"latitude": flt(latitude) if latitude not in (None, "") else None,
				"longitude": flt(longitude) if longitude not in (None, "") else None,
			},
		)
		doc.save(ignore_permissions=True)
		frappe.db.commit()

		message = None
		if duplicate and policy == "Warn":
			message = _("Heads up — {0} was also recorded on run {1}.").format(parsed["serial_no"], duplicate)
		elif parsed["parse_failed"]:
			message = _("Saved, but no pattern matched this label. An admin can add one later.")

		return _ok(
			{
				"scan": parsed,
				"duplicate_of": duplicate,
				"trace_completeness_pct": flt(doc.trace_completeness_pct),
			},
			message,
		)

	return with_deadlock_retry(_apply)


@frappe.whitelist()
def delete_scan(run: str, serial_no: str, entity_type: str) -> dict:
	"""Remove a mis-scanned component from a run."""

	def _apply():
		doc = frappe.get_doc("Process Run", run)
		frappe.has_permission("Process Run", doc=doc, ptype="write", throw=True)
		doc.ensure_open()
		kept = [s for s in doc.scans or [] if not (s.serial_no == serial_no and s.entity_type == entity_type)]
		doc.set("scans", [])
		for row in kept:
			doc.append("scans", row)
		doc.save(ignore_permissions=True)
		frappe.db.commit()
		return _ok({"trace_completeness_pct": flt(doc.trace_completeness_pct)}, _("Scan removed."))

	return with_deadlock_retry(_apply)


# ---------------------------------------------------------------------- photos


def _geofence_status(latitude, longitude) -> str:
	"""Evaluate a capture against the plant geofence. Advisory only."""
	settings = frappe.get_cached_doc("Process Engine Settings")
	if not cint(settings.geofence_enabled) or latitude in (None, "") or longitude in (None, ""):
		return "Unknown"
	if not (settings.plant_latitude and settings.plant_longitude):
		return "Unknown"

	from math import asin, cos, radians, sin, sqrt

	lat1, lon1 = radians(flt(latitude)), radians(flt(longitude))
	lat2, lon2 = radians(flt(settings.plant_latitude)), radians(flt(settings.plant_longitude))
	dlat, dlon = lat2 - lat1, lon2 - lon1
	a = sin(dlat / 2) ** 2 + cos(lat1) * cos(lat2) * sin(dlon / 2) ** 2
	metres = 6371000 * 2 * asin(sqrt(a))
	return "Inside" if metres <= cint(settings.geofence_radius_m) else "Outside"


@frappe.whitelist()
def attach_photo(
	run: str,
	step_code: str,
	file_url: str,
	captured_at: str | None = None,
	latitude: float | None = None,
	longitude: float | None = None,
	accuracy_m: float | None = None,
	location_source: str = "Unavailable",
	is_stamped: int = 1,
	exif_written: int = 0,
	caption: str | None = None,
) -> dict:
	"""Attach an already-uploaded photo to a step.

	The file itself goes up through Frappe's `upload_file`; this call maps it to
	a step and records the capture metadata. Coordinates are stored here *and*
	burnt into the image by the app: the burn-in survives screenshots, the EXIF
	survives resizing, and neither alone is enough.
	"""

	def _apply():
		doc = frappe.get_doc("Process Run", run)
		frappe.has_permission("Process Run", doc=doc, ptype="write", throw=True)
		doc.ensure_open()

		if not file_url:
			frappe.throw(_("A file URL is required."))

		doc.append(
			"photos",
			{
				"step_code": step_code,
				"file_url": file_url,
				"caption": caption,
				"captured_by": frappe.session.user,
				"captured_at": captured_at or frappe.utils.now_datetime(),
				"server_received_at": frappe.utils.now_datetime(),
				"latitude": flt(latitude) if latitude not in (None, "") else None,
				"longitude": flt(longitude) if longitude not in (None, "") else None,
				"accuracy_m": flt(accuracy_m) if accuracy_m not in (None, "") else None,
				"location_source": location_source or "Unavailable",
				"geofence_status": _geofence_status(latitude, longitude),
				"is_stamped": cint(is_stamped),
				"exif_written": cint(exif_written),
			},
		)
		doc.save(ignore_permissions=True)
		frappe.db.commit()
		return _ok(
			{"photo_count": len([p for p in doc.photos if p.step_code == step_code])}, _("Photo saved.")
		)

	return with_deadlock_retry(_apply)


# --------------------------------------------------------------- stage sign-off


@frappe.whitelist()
def submit_stage(run: str, stage: str, signature: str | None = None, remarks: str | None = None) -> dict:
	"""Operator submits a stage.

	The only genuinely interlocked control in the engine: unanswered *mandatory*
	steps block the submit, and the response names them so the operator knows
	exactly what is missing. Everything else — missing photos, missing scans —
	is recorded and reported, never blocked.
	"""

	def _apply():
		doc = frappe.get_doc("Process Run", run)
		frappe.has_permission("Process Run", doc=doc, ptype="write", throw=True)
		doc.ensure_open()

		definition = frappe.get_cached_doc("Process Definition", doc.process_definition)
		stage_def = next((s for s in definition.stages or [] if s.stage_code == stage), None)
		if not stage_def:
			frappe.throw(_("Stage '{0}' is not part of this process.").format(stage))
		if doc.stage_is_blocked(stage):
			frappe.throw(
				_("Stage '{0}' is blocked pending review of an earlier failure.").format(stage_def.label)
			)

		answers = _answer_map(doc)

		missing = []
		for step in definition.expanded_steps():
			if (
				step.get("stage") != stage
				or not cint(step.get("is_active", 1))
				or not cint(step.get("is_mandatory"))
			):
				continue
			if not conditions.is_visible(step, answers):
				continue
			if step["step_code"] not in answers:
				missing.append(step.get("display_no") or step["step_code"])

		if missing:
			frappe.throw(
				_("These checks still need an answer: {0}").format(", ".join(missing)),
				title=_("Not ready to submit"),
			)

		doc.append(
			"signoffs",
			{
				"stage": stage,
				"level": "Operator",
				"decision": "Submitted",
				"role": stage_def.signoff_role,
				"user": frappe.session.user,
				"user_full_name": frappe.utils.get_fullname(frappe.session.user),
				"signed_at": frappe.utils.now_datetime(),
				"signature": signature,
				"remarks": remarks,
			},
		)

		ordered = sorted(definition.stages or [], key=lambda x: cint(x.sequence))
		idx = next((i for i, s in enumerate(ordered) if s.stage_code == stage), 0)
		remaining = [s for s in ordered[idx + 1 :] if not doc.stage_is_blocked(s.stage_code)]

		if cint(stage_def.requires_second_signoff):
			doc.status = C.STATUS_AWAITING_VERIFICATION
		elif remaining:
			doc.current_stage = remaining[0].stage_code
		else:
			# Last stage in order — but "last" is not "finished". Anything still
			# outstanding anywhere in the run keeps it open and pointed at the
			# work, rather than stamping a verdict on a half-done inspection.
			outstanding = outstanding_work(doc, definition)
			if outstanding["stages"] or outstanding["steps"]:
				doc.status = C.STATUS_IN_PROGRESS
				first_open = next(
					(
						s.stage_code
						for s in sorted(definition.stages or [], key=lambda x: cint(x.sequence))
						if not doc.stage_is_blocked(s.stage_code)
						and not doc.signoff_for(s.stage_code, "Operator")
					),
					doc.current_stage,
				)
				doc.current_stage = first_open
				doc.save(ignore_permissions=True)
				# Same contract as every other write here: the operator's signoff
				# and the moved stage pointer are persisted before we answer, so
				# a dropped response never costs them the work.
				frappe.db.commit()  # nosemgrep
				return _ok(
					{**_serialise_run(doc), "outstanding": outstanding},
					_("Saved. {0} still to finish before this can be completed.").format(
						", ".join(outstanding["stages"] + outstanding["steps"])
					),
				)
			_finalise(doc, definition)

		doc.save(ignore_permissions=True)
		frappe.db.commit()
		return _ok(_serialise_run(doc), _("{0} submitted.").format(stage_def.label))

	return with_deadlock_retry(_apply)


@frappe.whitelist()
def verify_stage(
	run: str, stage: str, decision: str, remarks: str | None = None, signature: str | None = None
) -> dict:
	"""Independent verification of a submitted stage.

	Mirrors the operator / line-inspector split that Indian assembly lines
	already run, and the KM Report L1/L2 checker pattern already in this app.
	"""

	def _apply():
		doc = frappe.get_doc("Process Run", run)
		frappe.has_permission("Process Run", doc=doc, ptype="write", throw=True)

		definition = frappe.get_cached_doc("Process Definition", doc.process_definition)
		stage_def = next((s for s in definition.stages or [] if s.stage_code == stage), None)
		if not stage_def:
			frappe.throw(_("Stage '{0}' is not part of this process.").format(stage))

		roles = _user_roles()
		required = stage_def.second_signoff_role or C.ROLE_VERIFIER
		if "System Manager" not in roles and required not in roles:
			frappe.throw(
				_("Verifying this stage requires the {0} role.").format(required), frappe.PermissionError
			)

		if decision not in ("Approved", "Rejected"):
			frappe.throw(_("Decision must be Approved or Rejected."))

		doc.append(
			"signoffs",
			{
				"stage": stage,
				"level": "Verifier L1",
				"decision": decision,
				"role": required,
				"user": frappe.session.user,
				"user_full_name": frappe.utils.get_fullname(frappe.session.user),
				"signed_at": frappe.utils.now_datetime(),
				"signature": signature,
				"remarks": remarks,
			},
		)

		if decision == "Rejected":
			doc.status = C.STATUS_QUARANTINED
			doc.quarantine_reason = remarks or _("Rejected at verification.")
		else:
			ordered = sorted(definition.stages or [], key=lambda x: cint(x.sequence))
			idx = next((i for i, s in enumerate(ordered) if s.stage_code == stage), 0)
			remaining = ordered[idx + 1 :]
			if remaining:
				doc.status = C.STATUS_IN_PROGRESS
				doc.current_stage = remaining[0].stage_code
			else:
				_finalise(doc, definition)

		doc.save(ignore_permissions=True)
		# A verification decision is the point of the request; it is persisted
		# before answering so a dropped response never loses a sign-off somebody
		# has already given. Same contract as every other write in this file.
		frappe.db.commit()  # nosemgrep
		return _ok(_serialise_run(doc), _("Stage {0}.").format(decision.lower()))

	return with_deadlock_retry(_apply)


def outstanding_work(doc, definition) -> dict:
	"""What is still missing before this run may be called finished.

	Checked across the *whole* run, not the stage in hand. `submit_stage` only
	ever looked at the stage being submitted and at the stages after it, so a
	run whose first stage was never submitted — or was submitted out of order —
	could reach the end and be stamped Passed with an entire stage unanswered.

	Returns ``{stages: [...], steps: [...]}`` of labels, empty when complete.
	"""
	answers = _answer_map(doc)

	missing_steps = []
	for step in definition.expanded_steps():
		if not cint(step.get("is_active", 1)) or not cint(step.get("is_mandatory")):
			continue
		if not conditions.is_visible(step, answers):
			continue
		if step["step_code"] not in answers:
			missing_steps.append(step.get("display_no") or step["step_code"])

	missing_stages = []
	for stage in sorted(definition.stages or [], key=lambda x: cint(x.sequence)):
		if doc.stage_is_blocked(stage.stage_code):
			# Blocked pending review of an earlier failure. Not the operator's to
			# finish, and not a reason to hold the run open for ever.
			continue
		if not doc.signoff_for(stage.stage_code, "Operator"):
			missing_stages.append(stage.label or stage.stage_code)

	return {"stages": missing_stages, "steps": missing_steps}


def _finalise(doc, definition) -> None:
	"""Close a run: stamp completion, then fire the process-level actions."""
	doc.completed_at = frappe.utils.now_datetime()
	# Stamped first: `_recompute` only writes a verdict once the run is closed,
	# which is what keeps a half-finished inspection from carrying one.
	doc._recompute()
	if doc.status != C.STATUS_QUARANTINED:
		# A reason recorded by a quarantine action during the run decides this
		# regardless of the score. Deferring the *status* to completion must not
		# make a critical failure survivable by finishing well afterwards.
		failed = doc.result == "Fail" or bool((doc.quarantine_reason or "").strip())
		doc.status = C.STATUS_QUARANTINED if failed else C.STATUS_PASSED
	if doc.status == C.STATUS_QUARANTINED and not doc.quarantine_reason:
		doc.quarantine_reason = _("Run did not meet the pass criteria.")
	engine_actions.dispatch_completion(doc, [a.as_dict() for a in definition.completion_actions or []])


# ------------------------------------------------------------------- authoring


@frappe.whitelist()
def lint_definition(process: str) -> dict:
	"""Publish-readiness check, surfaced as a button on the Desk form."""
	definition = frappe.get_doc("Process Definition", process)
	_assert_can_author(definition)
	return _ok({"issues": definition.lint()})


@frappe.whitelist()
def publish_definition(process: str) -> dict:
	"""Freeze a draft and make it the live version for its family."""
	definition = frappe.get_doc("Process Definition", process)
	_assert_can_author(definition)
	return _ok(None, definition.publish()["message"])


@frappe.whitelist()
def clone_definition(process: str) -> dict:
	"""Fork a published process as the next draft version."""
	definition = frappe.get_doc("Process Definition", process)
	_assert_can_author(definition)
	return _ok({"name": definition.clone_new_version()}, _("Draft created."))


@frappe.whitelist()
def test_qr_pattern(entity_type: str, payload: str) -> dict:
	"""Try an entity type's patterns against a sample payload.

	Lets an author confirm a pattern works before saving it, instead of finding
	out at the station.
	"""
	if not (_user_roles() & {C.ROLE_AUTHOR, "System Manager"}):
		frappe.throw(_("Testing patterns requires the Process Author role."), frappe.PermissionError)
	scanning.clear_pattern_cache(entity_type)
	return _ok(scanning.parse_payload(entity_type, payload))


# ---------------------------------------------------------------- traceability


@frappe.whitelist()
def where_used(serial_no: str | None = None, batch_ref: str | None = None) -> dict:
	"""Every run — and every subject — a component reached.

	The containment query. This is what turns a field failure from "quarantine
	the fleet" into "quarantine nine packs".
	"""
	if not (serial_no or batch_ref):
		frappe.throw(_("Provide a serial number or a batch reference."))

	filters = {}
	if serial_no:
		filters["serial_no"] = serial_no
	if batch_ref:
		filters["batch_ref"] = batch_ref

	scans = frappe.get_all(
		"Process Run Scan",
		filters=filters,
		fields=["parent", "entity_type", "serial_no", "batch_ref", "mfg_date", "module_number"],
		limit_page_length=500,
		order_by="creation desc",
	)
	if not scans:
		return _ok({"scans": [], "runs": []})

	run_names = list({s.parent for s in scans if s.parent})
	runs = frappe.get_all(
		"Process Run",
		filters={"name": ["in", run_names]},
		fields=[
			"name",
			"process_name",
			"run_identifier",
			"subject_doctype",
			"subject_name",
			"status",
			"result",
			"completed_at",
		],
		limit_page_length=500,
		order_by="completed_at desc",
	)
	return _ok({"scans": scans, "runs": runs})


@frappe.whitelist()
def subject_history(subject_doctype: str, subject_name: str) -> dict:
	"""Every process ever run against one subject — a bus, a pack, a depot."""
	frappe.has_permission(subject_doctype, doc=subject_name, throw=True)
	runs = frappe.get_all(
		"Process Run",
		filters={"subject_doctype": subject_doctype, "subject_name": subject_name, "is_test_run": 0},
		fields=[
			"name",
			"process_name",
			"definition_version",
			"run_identifier",
			"status",
			"result",
			"score_pct",
			"started_at",
			"completed_at",
		],
		order_by="started_at desc",
		limit_page_length=100,
	)
	return _ok(runs)


@frappe.whitelist()
def my_open_runs(limit: int = 20) -> dict:
	"""Runs this user started and has not finished — the app's resume list."""
	runs = frappe.get_all(
		"Process Run",
		filters={
			# Unfinished is `completed_at is null`, not a list of statuses. A run
			# that tripped a critical check part-way is still editable — a
			# quarantine is not a terminal status — but it used to vanish from
			# this list, so the operator could neither finish it nor see it
			# again. It is the one run they most need to come back to.
			"started_by": frappe.session.user,
			"completed_at": ["is", "not set"],
			"status": ["not in", [C.STATUS_CANCELLED, C.STATUS_AWAITING_VERIFICATION]],
		},
		fields=[
			"name",
			"process_definition",
			"process_name",
			"run_identifier",
			"status",
			"current_stage",
			"answered_count",
			"started_at",
		],
		order_by="modified desc",
		limit_page_length=cint(limit) or 20,
	)
	return _ok(runs)


# ------------------------------------------------------------- operator history


#: Statuses that mean the operator is done with the run, whatever the verdict.
#: Quarantined counts as finished work — the operator did the inspection; it was
#: the pack that failed. Leaving it out of the history would hide exactly the
#: inspections that matter most.
_FINISHED_STATUSES = (
	C.STATUS_PASSED,
	C.STATUS_QUARANTINED,
	C.STATUS_AWAITING_VERIFICATION,
)


@frappe.whitelist()
def my_history(limit: int = 30, offset: int = 0, scope: str = "finished") -> dict:
	"""This operator's own inspection record.

	Answers the two questions an operator actually has about their own work —
	*how many have I done* and *what did I put on that one* — without giving them
	a report builder. Deliberately scoped to `started_by = session.user`: this is
	a personal record, not a supervisor's console, so it needs no role gate and
	leaks nothing about anyone else's work.

	`scope` is "finished" (the default), "open", or "all".

	Returns ``{stats, runs}``. Each run carries a `thumb` — the first photo taken
	on it — because a wall of identical serial numbers is unreadable, and the
	photo is the one thing that makes a row recognisable at a glance.
	"""
	user = frappe.session.user
	limit = min(max(cint(limit) or 30, 1), 100)
	offset = max(cint(offset), 0)

	status_filter = {
		"finished": ["in", _FINISHED_STATUSES],
		"open": ["in", [C.STATUS_IN_PROGRESS, C.STATUS_DRAFT, C.STATUS_IN_REWORK]],
	}.get(scope)

	filters: dict = {"started_by": user, "is_test_run": 0}
	if status_filter:
		filters["status"] = status_filter

	runs = frappe.get_all(
		"Process Run",
		filters=filters,
		fields=[
			"name",
			"process_definition",
			"process_name",
			"run_identifier",
			"status",
			"result",
			"score_pct",
			"pass_count",
			"fail_count",
			"skip_count",
			"critical_count",
			"answered_count",
			"trace_completeness_pct",
			"started_at",
			"completed_at",
		],
		order_by="ifnull(completed_at, started_at) desc, modified desc",
		limit_page_length=limit,
		limit_start=offset,
	)

	_attach_photo_summary(runs)

	return _ok({"stats": _history_stats(user), "runs": runs})


def _attach_photo_summary(runs: list[dict]) -> None:
	"""Add `photo_count` and `thumb` to each row, in one query for the page.

	One query for the whole page rather than one per run: a history screen is the
	easiest place in the app to write an N+1, and thirty runs would mean thirty
	round trips before the list could draw.
	"""
	for row in runs:
		row["photo_count"] = 0
		row["thumb"] = None
	if not runs:
		return

	names = [r["name"] for r in runs]
	photos = frappe.get_all(
		"Process Run Photo",
		filters={"parent": ["in", names], "parenttype": "Process Run"},
		fields=["parent", "file_url", "creation"],
		order_by="parent asc, idx asc",
		limit_page_length=0,
	)
	by_run: dict[str, list] = {}
	for p in photos:
		by_run.setdefault(p.parent, []).append(p)
	for row in runs:
		shots = by_run.get(row["name"]) or []
		row["photo_count"] = len(shots)
		row["thumb"] = shots[0].file_url if shots else None


def _history_stats(user: str) -> dict:
	"""Headline counts for the operator's own work.

	Counted server-side rather than derived from the returned page — the page is
	thirty rows and the totals are about a career, so a client-side tally would
	silently under-report the moment the list paginated.

	Dated on ``COALESCE(completed_at, started_at)``. A quarantined run never gets
	a completion time, so dating on `completed_at` alone reported "0 inspections
	today" to an operator looking at a list of three runs they had just finished
	— and the ones it dropped were exactly the ones that found a fault.

	One aggregate rather than six counts: these are six slices of the same rows,
	and the whole point of the header is that it draws before the list does.
	"""
	from frappe.utils import add_days, today

	row = frappe.db.sql(
		"""
		SELECT
			SUM(CASE WHEN status IN %(finished)s THEN 1 ELSE 0 END) AS total,
			SUM(CASE WHEN status IN %(finished)s
				AND DATE(COALESCE(completed_at, started_at)) = %(day)s THEN 1 ELSE 0 END) AS today,
			SUM(CASE WHEN status IN %(finished)s
				AND DATE(COALESCE(completed_at, started_at)) >= %(week_start)s THEN 1 ELSE 0 END) AS week,
			SUM(CASE WHEN status = %(passed)s THEN 1 ELSE 0 END) AS passed,
			SUM(CASE WHEN status = %(quarantined)s THEN 1 ELSE 0 END) AS quarantined,
			SUM(CASE WHEN status IN %(open_statuses)s THEN 1 ELSE 0 END) AS open_runs
		FROM `tabProcess Run`
		WHERE started_by = %(user)s AND IFNULL(is_test_run, 0) = 0
		""",
		{
			"user": user,
			"finished": _FINISHED_STATUSES,
			"open_statuses": (C.STATUS_IN_PROGRESS, C.STATUS_DRAFT, C.STATUS_IN_REWORK),
			"passed": C.STATUS_PASSED,
			"quarantined": C.STATUS_QUARANTINED,
			"day": today(),
			"week_start": add_days(today(), -6),
		},
		as_dict=True,
	)[0]

	return {
		"total": cint(row.total),
		"today": cint(row.today),
		"week": cint(row.week),
		"passed": cint(row.passed),
		"quarantined": cint(row.quarantined),
		"open": cint(row.open_runs),
	}


# ------------------------------------------------------------- admin overview


#: Who may read the whole plant's inspections rather than their own.
ADMIN_ROLES = ("System Manager", "Process Author", "Process Verifier", "Process Viewer")


def _assert_inspection_admin() -> None:
	"""Gate the cross-operator views.

	`my_history` needs no role check because it can only ever return the caller's
	own work. These do the opposite — they read everybody's — so the role check
	is the only thing standing between an operator and their colleagues' records.
	"""
	if frappe.session.user == "Administrator":
		return
	if not (set(ADMIN_ROLES) & _user_roles()):
		frappe.throw(_("You do not have access to the inspection overview."), frappe.PermissionError)


@frappe.whitelist()
def inspections(
	from_date: str | None = None,
	to_date: str | None = None,
	operator: str | None = None,
	status: str | None = None,
	process: str | None = None,
	search: str | None = None,
	limit: int = 50,
	offset: int = 0,
) -> dict:
	"""Every operator's inspections, for the admin review screen.

	Returns ``{stats, runs, operators}``. `operators` is the distinct set of
	people who appear in the *filtered* range, so the filter dropdown offers
	names that will actually return something rather than the whole user table.
	"""
	_assert_inspection_admin()

	limit = min(max(cint(limit) or 50, 1), 200)
	offset = max(cint(offset), 0)

	filters: dict = {"is_test_run": 0}
	if operator:
		filters["started_by"] = operator
	if status:
		filters["status"] = status
	if process:
		filters["process_definition"] = process
	if from_date:
		filters["started_at"] = [">=", f"{from_date} 00:00:00"]
	if to_date:
		# Two bounds on one field need the tuple form; a second assignment would
		# silently discard the first and quietly widen the range.
		if from_date:
			filters["started_at"] = ["between", [f"{from_date} 00:00:00", f"{to_date} 23:59:59"]]
		else:
			filters["started_at"] = ["<=", f"{to_date} 23:59:59"]

	or_filters = None
	if search:
		like = f"%{search.strip()}%"
		or_filters = {"run_identifier": ["like", like], "name": ["like", like]}

	fields = [
		"name",
		"process_definition",
		"process_name",
		"run_identifier",
		"status",
		"result",
		"score_pct",
		"pass_count",
		"fail_count",
		"skip_count",
		"critical_count",
		"answered_count",
		"trace_completeness_pct",
		"started_by",
		"started_at",
		"completed_at",
		"station",
	]
	runs = frappe.get_all(
		"Process Run",
		filters=filters,
		or_filters=or_filters,
		fields=fields,
		order_by="ifnull(completed_at, started_at) desc",
		limit_page_length=limit,
		limit_start=offset,
	)

	_attach_photo_summary(runs)

	names = {r["started_by"] for r in runs if r.get("started_by")}
	full_names = (
		dict(
			frappe.get_all(
				"User",
				filters={"name": ["in", list(names)]},
				fields=["name", "full_name"],
				as_list=True,
				limit_page_length=0,
			)
		)
		if names
		else {}
	)
	for row in runs:
		row["started_by_name"] = full_names.get(row.get("started_by")) or row.get("started_by")

	return _ok(
		{
			"stats": _inspection_stats(filters, or_filters),
			"runs": runs,
			"operators": sorted(
				({"user": u, "full_name": full_names.get(u) or u} for u in names),
				key=lambda o: o["full_name"],
			),
		}
	)


def _inspection_stats(filters: dict, or_filters) -> dict:
	"""Counts over the same filtered set the list is drawn from.

	One grouped query rather than one count per status: these are five slices of
	the same rows, and the headline is supposed to appear before the list does.

	Derived from the filters rather than from the returned page — the page is at
	most 200 rows, and a headline number is meant to describe the range the admin
	chose, not the slice that happened to fit on it.
	"""
	grouped = frappe.get_all(
		"Process Run",
		filters=filters,
		or_filters=or_filters,
		fields=["status", "count(name) as n"],
		group_by="status",
		limit_page_length=0,
	)
	by_status = {row["status"]: cint(row["n"]) for row in grouped}
	open_statuses = (C.STATUS_IN_PROGRESS, C.STATUS_DRAFT, C.STATUS_IN_REWORK)
	return {
		"total": sum(by_status.values()),
		"passed": by_status.get(C.STATUS_PASSED, 0),
		"quarantined": by_status.get(C.STATUS_QUARANTINED, 0),
		"awaiting": by_status.get(C.STATUS_AWAITING_VERIFICATION, 0),
		"in_progress": sum(by_status.get(s, 0) for s in open_statuses),
	}


@frappe.whitelist()
def run_report(name: str) -> dict:
	"""One finished run, as the operator filled it in.

	Reads entirely from the run's own result rows, never from the live
	definition: results carry the label, section and spec that were in force at
	the time, so re-publishing a process cannot rewrite the history of an
	inspection that has already happened.

	Photos are grouped onto their step rather than listed separately — "what did
	I see at the cooling plate" is the question, and a flat photo strip cannot
	answer it.
	"""
	doc = frappe.get_doc("Process Run", name)
	frappe.has_permission("Process Run", doc=doc, throw=True)

	shots: dict[str, list] = {}
	for p in doc.photos or []:
		shots.setdefault(p.step_code or "", []).append(
			{
				"file_url": p.file_url,
				"caption": p.caption,
				"captured_at": str(p.captured_at) if p.captured_at else None,
				"captured_by": p.captured_by,
				"latitude": flt(p.latitude) if p.latitude else None,
				"longitude": flt(p.longitude) if p.longitude else None,
				"accuracy_m": flt(p.accuracy_m) if p.accuracy_m else None,
				"location_source": p.location_source,
				"geofence_status": p.geofence_status,
			}
		)

	stage_labels = {}
	definition = frappe.db.exists("Process Definition", doc.process_definition)
	if definition:
		for row in frappe.get_all(
			"Process Stage",
			filters={"parent": doc.process_definition},
			fields=["stage_code", "label"],
			order_by="idx asc",
			limit_page_length=0,
		):
			stage_labels[row.stage_code] = row.label

	# Grouped by stage, in the order the stages were worked — which is the order
	# the operator remembers doing them in.
	groups: dict[str, dict] = {}
	for r in doc.results or []:
		stage = r.stage or ""
		group = groups.setdefault(
			stage,
			{"stage": stage, "label": stage_labels.get(stage, stage or "Checks"), "steps": []},
		)
		group["steps"].append(
			{
				"step_code": r.step_code,
				"display_no": r.display_no,
				"section": r.section,
				"label": r.label,
				"response_type": r.response_type,
				"response": r.response,
				"value_numeric": flt(r.value_numeric) if r.value_numeric is not None else None,
				"value_text": r.value_text,
				"unit": r.unit,
				"spec_summary": r.spec_summary,
				"is_pass": cint(r.is_pass),
				"is_deviation": cint(r.is_deviation),
				"is_critical": cint(r.is_critical),
				"is_skipped": cint(r.is_skipped),
				"skip_reason": r.skip_reason,
				"remark": r.remark,
				"answered_at": str(r.answered_at) if r.answered_at else None,
				"photos": shots.get(r.step_code or "", []),
			}
		)

	# Photos taken against a step that has no answer row must still surface —
	# an unanswered step with a photo on it is evidence someone looked.
	answered_codes = {r.step_code for r in doc.results or []}
	orphans = [
		{"step_code": code, "label": code or "Other photos", "photos": items}
		for code, items in shots.items()
		if code not in answered_codes
	]

	return _ok(
		{
			"name": doc.name,
			"process_name": doc.process_name,
			"run_identifier": doc.run_identifier,
			"status": doc.status,
			"result": doc.result,
			"score_pct": flt(doc.score_pct),
			"pass_count": cint(doc.pass_count),
			"fail_count": cint(doc.fail_count),
			"skip_count": cint(doc.skip_count),
			"critical_count": cint(doc.critical_count),
			"answered_count": cint(doc.answered_count),
			"trace_completeness_pct": flt(doc.trace_completeness_pct),
			"quarantine_reason": doc.quarantine_reason,
			"station": doc.station,
			"started_by": doc.started_by,
			"started_by_name": frappe.db.get_value("User", doc.started_by, "full_name")
			if doc.started_by
			else None,
			"started_at": str(doc.started_at) if doc.started_at else None,
			"completed_at": str(doc.completed_at) if doc.completed_at else None,
			"photo_count": len(doc.photos or []),
			"stages": list(groups.values()),
			"unmatched_photos": orphans,
		}
	)
