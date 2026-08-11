"""Computed steps — a derived value, with no expression language to abuse.

`Process Step.computed_expression` accepts one function call over earlier step
codes and literals::

    DIFF(CELL_V_MAX, CELL_V_MIN)
    AVG(MOD1_V, MOD2_V, MOD3_V)
    SUM(A, B, C)          MAX(...)   MIN(...)   COUNT_FAILED(...)

That is deliberately the whole grammar. A general expression language would
need parsing or `eval()`, and it would put process authoring back in the hands
of programmers — which is the exact thing the engine exists to avoid. One call
covers what shop-floor sheets actually ask for (a max, a min, and the spread
between them) and stays readable to whoever maintains the process.

Anything unparseable returns ``None`` rather than raising: a broken expression
must not cost an operator their run.
"""

from __future__ import annotations

import re

from frappe.utils import flt

#: FUNC(arg, arg, ...) — nothing nested, nothing infix.
_CALL = re.compile(r"^\s*(?P<func>[A-Z_]+)\s*\(\s*(?P<args>[^()]*?)\s*\)\s*$")


def _resolve(token: str, answers: dict) -> float | None:
	"""A token is either a numeric literal or an earlier step's numeric answer."""
	token = token.strip()
	if not token:
		return None
	try:
		return float(token)
	except ValueError:
		pass
	answer = answers.get(token)
	if not answer:
		return None
	value = answer.get("value_numeric")
	return flt(value) if value is not None else None


def evaluate_expression(expression: str, answers: dict) -> float | None:
	"""Compute a step's derived value from the answers gathered so far.

	Args:
	    expression: The authored `computed_expression`.
	    answers: ``step_code`` → result dict (as built by the API).

	Returns the value, or ``None`` when the expression is malformed or its
	inputs have not been answered yet — in which case the step simply stays
	unanswered and the operator sees a dash.
	"""
	if not expression:
		return None

	match = _CALL.match(expression)
	if not match:
		return None

	func = match.group("func").upper()
	raw_args = [a for a in match.group("args").split(",") if a.strip()]
	if not raw_args:
		return None

	if func == "COUNT_FAILED":
		# Operates on outcomes rather than values, so it resolves separately.
		return float(
			sum(
				1
				for token in raw_args
				if (answers.get(token.strip()) or {}).get("is_answered")
				and not (answers.get(token.strip()) or {}).get("is_pass")
			)
		)

	values = [_resolve(a, answers) for a in raw_args]
	present = [v for v in values if v is not None]
	if not present:
		return None

	if func == "DIFF":
		# The spread across the given inputs — max minus min. With the usual two
		# arguments this is exactly the "Difference:" line on the paper sheet.
		return round(max(present) - min(present), 6)
	if func == "SUM":
		return round(sum(present), 6)
	if func == "AVG":
		return round(sum(present) / len(present), 6)
	if func == "MAX":
		return round(max(present), 6)
	if func == "MIN":
		return round(min(present), 6)
	return None


def referenced_steps(expression: str) -> list[str]:
	"""Step codes an expression depends on — used by the publish linter."""
	match = _CALL.match(expression or "")
	if not match:
		return []
	out = []
	for token in match.group("args").split(","):
		token = token.strip()
		if not token:
			continue
		try:
			float(token)
		except ValueError:
			out.append(token)
	return out
