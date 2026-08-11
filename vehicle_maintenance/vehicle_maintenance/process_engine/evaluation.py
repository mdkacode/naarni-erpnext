"""Turning a raw answer into a judged result.

One function does the work: given a step definition and whatever the operator
entered, decide whether it passed, what the numeric value was, and how to
describe the specification in words for the certificate.

Two decisions worth knowing about:

**`pass_condition` is the authority on which bounds apply.** Frappe Float fields
are non-nullable and default to ``0``, so ``min_value = 0`` is indistinguishable
from "not set". Rather than guess, the step declares its intent — *Greater Than
Min* uses only the lower bound, *Within Range* uses both — and the operands are
read accordingly. `Number with Tolerance` short-circuits this and always uses
``nominal ± tolerance``.

**Outcomes stay data.** A Choice step's verdict is read off the matching
`Process Step Option` row's ``is_pass`` / ``is_critical``, never from a hardcoded
"Pass"/"Fail" string. Yes/No steps may also carry options; when they do not,
``Yes`` passes.
"""

from __future__ import annotations

from frappe.utils import cint, flt

from vehicle_maintenance.process_engine import constants as C


def resolve_band(step) -> tuple[float | None, float | None]:
	"""Return the effective ``(low, high)`` bounds for a numeric step.

	Either end may be ``None``, meaning unbounded in that direction. The step's
	`pass_condition` decides which of `min_value` / `max_value` / `nominal` are
	operands, because a zero bound is otherwise indistinguishable from an unset
	one (see module docstring).
	"""
	rtype = step.get("response_type")
	if rtype == C.NUMBER_WITH_TOLERANCE:
		nominal = flt(step.get("nominal_value"))
		tol = abs(flt(step.get("tolerance")))
		return nominal - tol, nominal + tol

	cond = step.get("pass_condition") or C.WITHIN_RANGE
	if cond == C.ANY_VALUE:
		return None, None
	if cond == C.GREATER_THAN_MIN:
		return flt(step.get("min_value")), None
	if cond == C.LESS_THAN_MAX:
		return None, flt(step.get("max_value"))
	if cond == C.EQUALS_NOMINAL:
		nominal = flt(step.get("nominal_value"))
		tol = abs(flt(step.get("tolerance")))
		return nominal - tol, nominal + tol
	return flt(step.get("min_value")), flt(step.get("max_value"))


def spec_summary(step) -> str:
	"""Human-readable specification, snapshotted onto the result for the report.

	Written at answer time so a certificate printed years later shows the band
	that actually applied, not whatever the template says today.
	"""
	rtype = step.get("response_type")
	if rtype not in C.NUMERIC_TYPES:
		return ""

	unit = (step.get("unit") or "").strip()
	suffix = f" {unit}" if unit else ""
	dec = cint(step.get("decimals")) or 2

	def fmt(v: float) -> str:
		return f"{v:.{dec}f}".rstrip("0").rstrip(".") or "0"

	if rtype == C.NUMBER_WITH_TOLERANCE or step.get("pass_condition") == C.EQUALS_NOMINAL:
		nominal = flt(step.get("nominal_value"))
		tol = abs(flt(step.get("tolerance")))
		if tol:
			return f"{fmt(nominal)} ± {fmt(tol)}{suffix} ({fmt(nominal - tol)}–{fmt(nominal + tol)})"
		return f"{fmt(nominal)}{suffix}"

	lo, hi = resolve_band(step)
	if lo is not None and hi is not None:
		return f"{fmt(lo)}–{fmt(hi)}{suffix}"
	if lo is not None:
		return f"≥ {fmt(lo)}{suffix}"
	if hi is not None:
		return f"≤ {fmt(hi)}{suffix}"
	return f"Any value{suffix}"


def _option_for(step, response: str) -> dict | None:
	"""Find the authored option row matching `response`, by value then by label."""
	options = step.get("options") or []
	target = (response or "").strip()
	if not target:
		return None
	for opt in options:
		if (opt.get("value") or "").strip() == target:
			return opt
	for opt in options:
		if (opt.get("label") or "").strip() == target:
			return opt
	return None


def evaluate(
	step: dict,
	response: str | None = None,
	value: float | str | None = None,
	skipped: bool = False,
	photo_count: int = 0,
	scan_count: int = 0,
) -> dict:
	"""Judge one answer against one step definition.

	Args:
	    step: The `Process Step` row as a plain dict (options included).
	    response: The chosen option value, free text, or date string.
	    value: Numeric entry, for the numeric response types.
	    skipped: The operator explicitly skipped this step.
	    photo_count: Photos already attached to this step on the run.
	    scan_count: Component scans already recorded against this step.

	Returns a dict shaped for a `Process Run Result` row:
	``{is_pass, is_critical, is_deviation, value_numeric, value_text, response,
	spec_summary, is_answered}``.

	`is_critical` here means "this answer *is* the critical outcome", which is
	the step's criticality AND a failing verdict — not merely that the step is
	marked critical.
	"""
	rtype = step.get("response_type") or C.CHOICE
	out = {
		"response": (response or "").strip() if isinstance(response, str) else response,
		"value_numeric": None,
		"value_text": None,
		"spec_summary": spec_summary(step),
		"is_pass": False,
		"is_critical": False,
		"is_deviation": False,
		"is_answered": False,
	}

	if skipped:
		# A skip is not a verdict. Scoring decides whether it counts against the
		# run, per the process's `skipped_steps_count_as`.
		return out

	if rtype == C.SECTION_NOTE:
		out["is_pass"] = True
		out["is_answered"] = True
		return out

	# ------------------------------------------------------------ numeric types
	if rtype in C.NUMERIC_TYPES:
		if value in (None, ""):
			return out
		try:
			num = flt(value)
		except (TypeError, ValueError):
			return out
		out["value_numeric"] = num
		out["is_answered"] = True
		lo, hi = resolve_band(step)
		in_band = True
		if lo is not None and num < lo:
			in_band = False
		if hi is not None and num > hi:
			in_band = False
		out["is_pass"] = in_band
		out["is_deviation"] = not in_band

	# ------------------------------------------------------------ choice types
	elif rtype in (C.CHOICE, C.CHOICE_MULTI, C.YES_NO):
		raw = out["response"]
		if not raw:
			return out
		out["is_answered"] = True

		if rtype == C.CHOICE_MULTI:
			# Every chosen option must pass; any critical one is critical.
			picks = [p.strip() for p in str(raw).split(",") if p.strip()]
			matched = [_option_for(step, p) for p in picks]
			known = [m for m in matched if m]
			if not known:
				out["is_pass"] = True
			else:
				out["is_pass"] = all(cint(m.get("is_pass")) for m in known)
				out["is_deviation"] = not out["is_pass"]
				out["is_critical"] = any(cint(m.get("is_critical")) for m in known)
			return out

		opt = _option_for(step, raw)
		if opt:
			out["is_pass"] = bool(cint(opt.get("is_pass")))
			out["is_critical"] = bool(cint(opt.get("is_critical")))
		elif rtype == C.YES_NO:
			# No authored vocabulary — the sensible default is that Yes passes.
			out["is_pass"] = str(raw).strip().lower() in ("yes", "1", "true")
		else:
			# An answer the template no longer offers: record it, do not credit it.
			out["is_pass"] = False
		out["is_deviation"] = not out["is_pass"]

	# ------------------------------------------------------- evidence-only types
	elif rtype == C.PHOTO_ONLY:
		out["is_answered"] = photo_count > 0
		out["is_pass"] = photo_count >= max(1, cint(step.get("min_photos")) or 1)

	elif rtype == C.SCAN:
		expected = max(1, cint(step.get("scan_count")) or 1)
		out["is_answered"] = scan_count > 0
		# A scan is never mandatory, so a partial capture still passes the step —
		# shortfall shows up as traceability completeness on the run, not a fail.
		out["is_pass"] = scan_count > 0
		out["value_numeric"] = scan_count
		out["spec_summary"] = f"{scan_count} of {expected} captured"

	# ---------------------------------------------------------- free-form types
	else:  # TEXT_SHORT, TEXT_LONG, DATE, DATETIME, SIGNATURE, LINK
		raw = out["response"]
		if raw in (None, ""):
			return out
		out["value_text"] = str(raw)
		out["is_answered"] = True
		out["is_pass"] = True

	# A step only becomes *critically* failed if it is marked critical and failed.
	if not out["is_pass"] and cint(step.get("is_critical")):
		out["is_critical"] = True
	elif out["is_pass"]:
		out["is_critical"] = False

	return out


def photo_required(step: dict, is_pass: bool, chosen_option: dict | None = None) -> bool:
	"""Whether this answer should prompt for a photo.

	Never a hard block — the caller surfaces it as a prompt, and skipping records
	a reason that appears on the report. An option row can demand a photo on its
	own, which is how "Fail" asks for evidence while "Pass" does not.
	"""
	if chosen_option and cint(chosen_option.get("requires_photo")):
		return True
	if not cint(step.get("requires_photo")):
		return False
	policy = step.get("photo_policy") or "On Fail"
	if policy == "Never":
		return False
	if policy == "Always":
		return True
	if policy == "On Fail":
		return not is_pass
	if policy == "On Pass":
		return is_pass
	return False
