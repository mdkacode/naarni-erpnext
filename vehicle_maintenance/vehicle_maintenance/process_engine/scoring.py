"""Run scoring and the pass/fail verdict.

Two things decide whether a run passed, and both are per-process configuration:

1. **Score against a threshold** — weighted (each step contributes its `weight`)
   or simple (every judged step counts once).
2. **A critical-fail budget** — a run above the threshold still fails if it
   exceeds `critical_fails_allowed`. Default zero: any critical fail fails the
   run, however good the rest of the sheet looks.

Only *judged* steps score. A photo, a signature or a free-text note is evidence,
not a verdict, so counting them would quietly inflate every score. Skipped steps
are excluded from both numerator and denominator by default — a step nobody
could perform should not silently read as a failure.
"""

from __future__ import annotations

from frappe.utils import cint, flt

from vehicle_maintenance.process_engine import constants as C


def is_scoreable(step: dict, result: dict, skipped_policy: str = "Excluded") -> bool:
	"""Whether this answered step contributes to the score at all."""
	rtype = result.get("response_type") or step.get("response_type")
	if rtype in C.UNSCORED_TYPES:
		return False
	if rtype not in C.JUDGED_TYPES:
		return False
	if flt(step.get("weight", 1)) <= 0:
		return False
	if cint(result.get("is_skipped")):
		return skipped_policy == "Fail"
	return bool(
		result.get("is_answered") or result.get("response") or result.get("value_numeric") is not None
	)


def score_run(definition: dict, results: list[dict], steps_by_code: dict) -> dict:
	"""Compute the score and verdict for a run.

	Args:
	    definition: The `Process Definition` as a dict — reads `scoring_enabled`,
	        `scoring_mode`, `pass_threshold_pct`, `critical_fails_allowed`,
	        `skipped_steps_count_as`.
	    results: The run's `Process Run Result` rows as dicts.
	    steps_by_code: ``step_code`` → step definition dict, for weights.

	Returns ``{score_earned, score_max, score_pct, result, pass_count,
	fail_count, skip_count, critical_count, answered_count, is_first_pass}``.

	`result` is left blank when scoring is disabled — a process can legitimately
	be a data-capture sheet with no verdict at all.
	"""
	mode = definition.get("scoring_mode") or "Weighted"
	skipped_policy = definition.get("skipped_steps_count_as") or "Excluded"
	scoring_on = cint(definition.get("scoring_enabled", 1))

	earned = 0.0
	maximum = 0.0
	passed = failed = skipped = critical = answered = 0

	for res in results:
		code = res.get("step_code")
		step = steps_by_code.get(code) or {}

		if cint(res.get("is_skipped")):
			skipped += 1
		elif res.get("is_answered") or res.get("response") or res.get("value_numeric") is not None:
			answered += 1
			if res.get("is_pass"):
				passed += 1
			else:
				failed += 1
		if cint(res.get("is_critical")) and not res.get("is_pass"):
			critical += 1

		if not is_scoreable(step, res, skipped_policy):
			continue

		weight = flt(step.get("weight", 1)) if mode == "Weighted" else 1.0
		maximum += weight
		if res.get("is_pass") and not cint(res.get("is_skipped")):
			earned += weight

	pct = (earned / maximum * 100.0) if maximum else 0.0

	verdict = ""
	if scoring_on:
		threshold = flt(definition.get("pass_threshold_pct") or 0)
		allowed = cint(definition.get("critical_fails_allowed"))
		verdict = "Pass" if (pct >= threshold and critical <= allowed) else "Fail"

	return {
		"score_earned": round(earned, 2),
		"score_max": round(maximum, 2),
		"score_pct": round(pct, 2),
		"result": verdict,
		"pass_count": passed,
		"fail_count": failed,
		"skip_count": skipped,
		"critical_count": critical,
		"answered_count": answered,
		# First pass = clean the first time through: passed, nothing failed,
		# nothing critical. Reworked runs are deliberately excluded, because
		# first-pass yield is the metric that justifies the early QC gate.
		"is_first_pass": 1 if (verdict == "Pass" and failed == 0 and critical == 0) else 0,
	}


def trace_completeness(expected_by_entity: dict, scans: list[dict]) -> float:
	"""Share of expected component scans actually captured, as a percentage.

	Reported on the run and the certificate; never enforced. A pack with zero
	scans still completes and still earns a certificate — it simply shows a
	lower completeness figure. Visibility, not enforcement.
	"""
	total_expected = sum(max(0, cint(v)) for v in expected_by_entity.values())
	if not total_expected:
		return 100.0

	captured: dict[str, set] = {}
	for scan in scans:
		et = scan.get("entity_type")
		if et not in expected_by_entity:
			continue
		# Identify by serial where we have one, else by row position, so an
		# unparsed payload still counts as evidence of a scan.
		key = (
			scan.get("serial_no") or ""
		).strip() or f"#{scan.get('position_index') or len(captured.get(et, ()))}"
		captured.setdefault(et, set()).add(key)

	total_captured = sum(min(len(v), cint(expected_by_entity[k])) for k, v in captured.items())
	return round(total_captured / total_expected * 100.0, 2)


def entry_flag(step: dict, seconds_spent: int | None, threshold_pct: float = 25.0) -> str:
	"""Advisory flag for implausibly fast entry.

	Returns ``"Fast Entry"`` when a step was answered in less than
	`threshold_pct` of its `expected_seconds`, else ``"Normal"``. This is
	surfaced on the report and never blocks anything: pencil whipping is
	documented as usually a response to workload pressure rather than fraud, so
	the design goal is that the truth is easy to record and shortcuts are easy
	to see — not that the operator is policed at the point of work.
	"""
	expected = cint(step.get("expected_seconds"))
	if not expected or seconds_spent is None:
		return "Normal"
	if cint(seconds_spent) < expected * (flt(threshold_pct) / 100.0):
		return "Fast Entry"
	return "Normal"
