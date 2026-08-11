"""Step visibility — branching without an expression language.

A step's `visibility_conditions` are structured rows, not a formula. That is a
deliberate constraint: a formula field would need parsing or `eval()`, and it
would quietly re-introduce the "ask a developer" problem the engine exists to
remove. Rows stay editable by whoever authors the process.

Clauses fold **left to right with no operator precedence** — ``A AND B OR C``
reads as ``(A AND B) OR C``. That is predictable and matches how a
non-programmer reads a list top to bottom; anyone needing real precedence should
split the logic across two steps.
"""

from __future__ import annotations

from frappe.utils import cint, flt

EQUALS = "Equals"
NOT_EQUALS = "Not Equals"
GREATER_THAN = "Greater Than"
LESS_THAN = "Less Than"
IS_ANSWERED = "Is Answered"
IS_NOT_ANSWERED = "Is Not Answered"
IS_PASS = "Is Pass"
IS_FAILED = "Is Failed"
IS_OUT_OF_RANGE = "Is Out Of Range"
IS_SKIPPED = "Is Skipped"


def _clause(cond: dict, answers: dict) -> bool:
	"""Evaluate one condition row against the answers collected so far.

	`answers` maps ``step_code`` → the result dict written by
	`evaluation.evaluate` (plus ``is_skipped``). A step that has not been reached
	yet is simply absent, which is why the unanswered operators are explicit
	rather than inferred from a falsy value.
	"""
	code = (cond.get("when_step") or "").strip()
	op = cond.get("operator") or EQUALS
	target = cond.get("value")
	ans = answers.get(code)

	if op == IS_ANSWERED:
		return bool(ans and ans.get("is_answered"))
	if op == IS_NOT_ANSWERED:
		return not (ans and ans.get("is_answered"))
	if op == IS_SKIPPED:
		return bool(ans and ans.get("is_skipped"))

	if not ans:
		# Nothing to compare against yet. Treat as unsatisfied rather than
		# throwing — a half-finished run must still render.
		return False

	if op == IS_PASS:
		return bool(ans.get("is_pass"))
	if op == IS_FAILED:
		return ans.get("is_answered") and not ans.get("is_pass")
	if op == IS_OUT_OF_RANGE:
		return bool(ans.get("is_deviation"))

	if op in (GREATER_THAN, LESS_THAN):
		left = ans.get("value_numeric")
		if left is None:
			return False
		try:
			right = flt(target)
		except (TypeError, ValueError):
			return False
		return left > right if op == GREATER_THAN else left < right

	# Equals / Not Equals compare the stored response, case-insensitively —
	# option values are authored by hand and casing drifts.
	left_s = str(ans.get("response") or "").strip().lower()
	right_s = str(target or "").strip().lower()
	if op == EQUALS:
		return left_s == right_s
	if op == NOT_EQUALS:
		return left_s != right_s
	return False


def is_visible(step: dict, answers: dict) -> bool:
	"""Whether `step` should be shown, given the answers gathered so far.

	No conditions means always visible. Rows fold left to right using each row's
	own `join` (the first row's join is ignored).
	"""
	conds = step.get("visibility_conditions") or []
	if not conds:
		return True

	result = None
	for cond in conds:
		value = _clause(cond, answers)
		if result is None:
			result = value
			continue
		if (cond.get("join") or "AND").upper() == "OR":
			result = result or value
		else:
			result = result and value
	return bool(result)


def visible_steps(steps: list[dict], answers: dict) -> list[dict]:
	"""Filter a step list to those currently visible and active."""
	return [s for s in steps if cint(s.get("is_active", 1)) and is_visible(s, answers)]


def unreachable_steps(steps: list[dict]) -> list[str]:
	"""Step codes whose conditions can never be satisfied — the publish linter.

	Catches the two mistakes that authoring UIs produce constantly: pointing a
	condition at a step code that does not exist, and pointing it at a step that
	comes *later* in the process, which can never have been answered in time.
	An unreachable step is the no-code equivalent of dead code, and only a linter
	will ever find it.
	"""
	order = {}
	for idx, s in enumerate(steps):
		order[(s.get("step_code") or "").strip()] = idx

	bad = []
	for idx, step in enumerate(steps):
		code = (step.get("step_code") or "").strip()
		for cond in step.get("visibility_conditions") or []:
			ref = (cond.get("when_step") or "").strip()
			if ref not in order:
				bad.append(f"{code}: refers to unknown step '{ref}'")
			elif order[ref] >= idx:
				bad.append(f"{code}: refers to '{ref}', which is not answered before it")
	return bad
