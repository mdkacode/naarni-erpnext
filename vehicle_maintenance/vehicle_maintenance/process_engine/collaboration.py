# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""One pack, one record, several pairs of hands.

A battery pack is 59 checks across several modules, and a shift puts more than
one person on it. Until now the engine modelled an inspection as one operator's
private piece of work: two people scanning the same pack label started two runs
of the same physical battery, each holding half the answers, and neither could
see the other's.

This module is the other half of that fix. `run_key` and `open_run_for` make a
scanned label resolve to the run that already exists, and `board` answers the
question the second person actually has when they arrive at the bench: *what is
left, and where did the last person get to?*

Nothing here writes. Attribution comes from rows the engine already keeps —
`answered_by` on every result, `user` on every sign-off — so the board is a
reading of the record rather than a second place where the truth is stored.
"""

from __future__ import annotations

import frappe
from frappe.utils import cint, time_diff_in_seconds

from vehicle_maintenance.process_engine import conditions


def run_key(identifier: str | None) -> str:
	"""Normalise a scanned or typed label so one pack has one key.

	Case and surrounding whitespace differ between a scan and a typed entry of
	the same serial, and that difference alone would be enough to open a second
	inspection of one battery — which is the exact failure this exists to stop.
	"""
	return (identifier or "").strip().upper()


def open_run_for(definition_names: list[str], identifier: str | None) -> str | None:
	"""The unfinished run for this pack, if somebody already started one.

	Matched on `completed_at is null` rather than on a set of statuses: a run
	that tripped a critical check is still open work, and it is precisely the one
	a colleague must join rather than start again.

	Scoped to every version of the process, not just the current one — a
	definition republished mid-shift must not orphan the pack that is on the
	bench.
	"""
	key = run_key(identifier)
	if not key or not definition_names:
		return None
	rows = frappe.get_all(
		"Process Run",
		filters={
			"process_definition": ["in", definition_names],
			"run_identifier": key,
			"completed_at": ["is", "not set"],
			"status": ["not in", ["Cancelled"]],
			"is_test_run": 0,
		},
		fields=["name"],
		order_by="creation asc",
		limit_page_length=1,
	)
	return rows[0]["name"] if rows else None


def _full_name(user: str | None, cache: dict) -> str:
	if not user:
		return ""
	if user not in cache:
		cache[user] = frappe.utils.get_fullname(user) or user
	return cache[user]


def participants(doc, names: dict | None = None) -> list[dict]:
	"""Everyone who has actually done something on this run, most recent first.

	Deliberately *not* "everyone with access". The number an operator wants on
	the pack card is how many people are working this battery, and someone who
	opened the record and answered nothing has not worked it.

	The person who started the run is always included, even with no answers to
	their name — they identified the pack, which is work, and a run showing
	"0 people" while somebody is plainly standing at it reads as broken.
	"""
	cache = names if names is not None else {}
	seen: dict[str, dict] = {}

	def touch(user: str | None, when, stage: str | None) -> None:
		if not user or user in ("Guest",):
			return
		row = seen.setdefault(
			user,
			{
				"user": user,
				"full_name": _full_name(user, cache),
				"answers": 0,
				"last_at": None,
				"last_stage": None,
			},
		)
		if when and (row["last_at"] is None or str(when) > str(row["last_at"])):
			row["last_at"] = when
			row["last_stage"] = stage

	touch(doc.started_by, doc.started_at, doc.current_stage)
	for result in doc.results or []:
		touch(result.answered_by, result.answered_at, result.stage)
		if result.answered_by in seen:
			seen[result.answered_by]["answers"] += 1
	for signoff in doc.signoffs or []:
		touch(signoff.user, signoff.signed_at, signoff.stage)

	ordered = sorted(seen.values(), key=lambda r: str(r["last_at"] or ""), reverse=True)
	for row in ordered:
		row["last_at"] = str(row["last_at"]) if row["last_at"] else None
	return ordered


def board(doc, definition) -> list[dict]:
	"""Every module of this run, with its progress and its last pair of hands.

	This is what the app draws before it draws a single question. Showing the
	modules first is what makes parallel work possible to *organise*: two people
	at one pack need to see which module the other is in before choosing their
	own, and a stepper that opens straight onto question one cannot tell them.

	`answered` counts every visible active step, mandatory or not, because that
	is what "how far through is it" means to somebody looking at the bench.
	`outstanding` counts only what actually holds the run open, which is what the
	submit gate enforces — the two numbers answer different questions and
	collapsing them into one made the board lie in both directions.
	"""
	answers = {}
	last_by_stage: dict[str, dict] = {}
	names: dict = {}

	for result in doc.results or []:
		answers[result.step_code] = {
			"is_answered": bool(
				result.response or result.value_numeric is not None or cint(result.is_skipped)
			),
			"is_pass": cint(result.is_pass),
			"is_deviation": cint(result.is_deviation),
			"is_skipped": cint(result.is_skipped),
			"response": result.response,
			"value_numeric": result.value_numeric,
		}
		if not result.answered_at or not result.stage:
			continue
		current = last_by_stage.get(result.stage)
		if current is None or str(result.answered_at) > str(current["at"]):
			last_by_stage[result.stage] = {
				"at": result.answered_at,
				"user": result.answered_by,
			}

	steps_by_stage: dict[str, list[dict]] = {}
	for step in definition.expanded_steps():
		if not cint(step.get("is_active", 1)):
			continue
		steps_by_stage.setdefault(step.get("stage") or "", []).append(step)

	out = []
	for stage in sorted(definition.stages or [], key=lambda s: cint(s.sequence)):
		steps = steps_by_stage.get(stage.stage_code, [])
		visible = [s for s in steps if conditions.is_visible(s, answers)]
		answered = [s for s in visible if s["step_code"] in answers]
		outstanding = [s for s in visible if cint(s.get("is_mandatory")) and s["step_code"] not in answers]

		signoff = doc.signoff_for(stage.stage_code, "Operator")
		last = last_by_stage.get(stage.stage_code)

		out.append(
			{
				"stage_code": stage.stage_code,
				"label": stage.label or stage.stage_code,
				"sequence": cint(stage.sequence),
				"total": len(visible),
				"answered": len(answered),
				"outstanding": len(outstanding),
				"blocked": doc.stage_is_blocked(stage.stage_code),
				"submitted": bool(signoff),
				"submitted_by": _full_name(signoff.user, names) if signoff else None,
				"submitted_at": str(signoff.signed_at) if signoff and signoff.signed_at else None,
				"last_user": last["user"] if last else None,
				"last_by": _full_name(last["user"], names) if last else None,
				"last_at": str(last["at"]) if last else None,
				# Someone is on this module *now*, as far as anyone can tell from
				# the record. Used to mark a module rather than to lock it: two
				# people in one module is allowed, and being told is enough.
				"active_now": bool(last and _within(last["at"], ACTIVE_WINDOW_SECONDS)),
			}
		)
	return out


#: How recently an answer must have landed for a module to read as "in progress
#: now". Ten minutes is a guess at the pace of a plant floor rather than a
#: measurement — long enough to cover a check that needs a torque wrench fetched,
#: short enough that a module abandoned before lunch stops claiming somebody.
ACTIVE_WINDOW_SECONDS = 600


def _within(when, seconds: int) -> bool:
	if not when:
		return False
	try:
		return time_diff_in_seconds(frappe.utils.now_datetime(), when) <= seconds
	except Exception:
		return False
