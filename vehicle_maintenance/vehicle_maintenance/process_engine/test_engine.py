"""Unit tests for the engine's pure logic.

These deliberately test the modules that decide outcomes — evaluation, scoring,
conditions, computed values, scan parsing — without touching the database. That
is where the bugs that matter live: a wrong band, a miscounted score or a
mis-parsed serial is invisible in the UI and permanent in the record.

`test_scanning` exercises only `parse_mfg_date`, which is pure; the pattern
matching itself needs a saved Process Entity Type and is covered by the
integration tests in `test_process_api.py`.
"""

import unittest

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

PASS_FAIL = [
	{"value": "Pass", "label": "Pass", "is_pass": 1, "is_critical": 0},
	{"value": "Fail", "label": "Fail", "is_pass": 0, "is_critical": 1, "requires_photo": 1},
	{"value": "NA", "label": "N/A", "is_pass": 1, "is_critical": 0},
]


def choice_step(**kw) -> dict:
	base = {
		"step_code": "S1",
		"response_type": C.CHOICE,
		"options": PASS_FAIL,
		"is_critical": 1,
		"weight": 1,
		"requires_photo": 1,
		"photo_policy": "On Fail",
	}
	base.update(kw)
	return base


def torque_step(**kw) -> dict:
	base = {
		"step_code": "T1",
		"response_type": C.NUMBER_WITH_TOLERANCE,
		"nominal_value": 10.0,
		"tolerance": 1.0,
		"unit": "Nm",
		"decimals": 1,
		"is_critical": 1,
		"weight": 3,
	}
	base.update(kw)
	return base


class TestEvaluation(unittest.TestCase):
	def test_choice_reads_verdict_from_the_authored_option(self):
		"""Pass/Fail is data — the verdict comes off the option row, not a literal."""
		out = evaluation.evaluate(choice_step(), response="Pass")
		self.assertTrue(out["is_pass"])
		self.assertFalse(out["is_critical"])

		out = evaluation.evaluate(choice_step(), response="Fail")
		self.assertFalse(out["is_pass"])
		self.assertTrue(out["is_critical"])
		self.assertTrue(out["is_deviation"])

	def test_na_counts_as_pass_when_authored_that_way(self):
		out = evaluation.evaluate(choice_step(), response="NA")
		self.assertTrue(out["is_pass"])
		self.assertFalse(out["is_critical"])

	def test_three_tier_vocabulary_needs_no_code(self):
		"""An entirely different outcome set works with no engine change."""
		step = choice_step(
			options=[
				{"value": "Good", "is_pass": 1},
				{"value": "Recommend", "is_pass": 0, "is_critical": 0},
				{"value": "Immediate", "is_pass": 0, "is_critical": 1},
			],
			is_critical=0,
		)
		self.assertTrue(evaluation.evaluate(step, response="Good")["is_pass"])
		recommend = evaluation.evaluate(step, response="Recommend")
		self.assertFalse(recommend["is_pass"])
		self.assertFalse(recommend["is_critical"])
		self.assertTrue(evaluation.evaluate(step, response="Immediate")["is_critical"])

	def test_unknown_option_is_recorded_but_not_credited(self):
		out = evaluation.evaluate(choice_step(), response="Maybe")
		self.assertTrue(out["is_answered"])
		self.assertFalse(out["is_pass"])

	def test_torque_band_is_nominal_plus_minus_tolerance(self):
		self.assertTrue(evaluation.evaluate(torque_step(), value=10.4)["is_pass"])
		self.assertTrue(evaluation.evaluate(torque_step(), value=9.0)["is_pass"])
		self.assertTrue(evaluation.evaluate(torque_step(), value=11.0)["is_pass"])
		self.assertFalse(evaluation.evaluate(torque_step(), value=11.1)["is_pass"])
		self.assertFalse(evaluation.evaluate(torque_step(), value=8.9)["is_pass"])

	def test_greater_than_min_ignores_a_zero_max(self):
		"""A Float max of 0 must not be read as an upper bound.

		Frappe Floats are non-nullable, so `pass_condition` is the authority on
		which bounds apply. Without this the >52 V module check would fail every
		reading, because 52 > 0.
		"""
		step = {
			"step_code": "V1",
			"response_type": C.NUMBER_IN_RANGE,
			"min_value": 52.0,
			"max_value": 0.0,
			"pass_condition": C.GREATER_THAN_MIN,
			"unit": "V",
			"weight": 3,
		}
		self.assertTrue(evaluation.evaluate(step, value=53.2)["is_pass"])
		self.assertFalse(evaluation.evaluate(step, value=51.2)["is_pass"])

	def test_less_than_max_ignores_a_zero_min(self):
		step = {
			"step_code": "D1",
			"response_type": C.NUMBER_IN_RANGE,
			"min_value": 0.0,
			"max_value": 0.05,
			"pass_condition": C.LESS_THAN_MAX,
			"unit": "V",
		}
		self.assertTrue(evaluation.evaluate(step, value=0.03)["is_pass"])
		self.assertFalse(evaluation.evaluate(step, value=0.06)["is_pass"])

	def test_skip_is_not_a_verdict(self):
		out = evaluation.evaluate(choice_step(), skipped=True)
		self.assertFalse(out["is_pass"])
		self.assertFalse(out["is_answered"])
		self.assertFalse(out["is_deviation"])

	def test_yes_no_defaults_to_yes_passing(self):
		step = {"step_code": "Y1", "response_type": C.YES_NO, "options": []}
		self.assertTrue(evaluation.evaluate(step, response="Yes")["is_pass"])
		self.assertFalse(evaluation.evaluate(step, response="No")["is_pass"])

	def test_yes_no_vocabulary_can_be_inverted_by_config(self):
		"""'Is there damage?' — Yes must be the failure, with no code change."""
		step = {
			"step_code": "Y2",
			"response_type": C.YES_NO,
			"options": [{"value": "Yes", "is_pass": 0, "is_critical": 1}, {"value": "No", "is_pass": 1}],
		}
		self.assertFalse(evaluation.evaluate(step, response="Yes")["is_pass"])
		self.assertTrue(evaluation.evaluate(step, response="No")["is_pass"])

	def test_scan_step_passes_on_partial_capture(self):
		"""A scan is never mandatory; a shortfall is completeness, not a failure."""
		step = {"step_code": "SC1", "response_type": C.SCAN, "scan_count": 3}
		out = evaluation.evaluate(step, scan_count=1)
		self.assertTrue(out["is_pass"])
		self.assertIn("1 of 3", out["spec_summary"])

	def test_a_scan_typed_by_hand_counts_as_answered(self):
		"""Every scan step offers a box to type into, and its help text says so.

		Labels come off crates greasy, torn or printed too small for any camera.
		Judging only the camera made a keyed-in serial read as unanswered, so the
		submit gate demanded a step that was filled in on screen.
		"""
		step = {"step_code": "SC1", "response_type": C.SCAN, "scan_count": 1}
		for field, kwargs in (("response", {"response": "KA25AB1234"}), ("value", {"value": "KA25AB1234"})):
			with self.subTest(field=field):
				out = evaluation.evaluate(step, **kwargs)
				self.assertTrue(out["is_answered"])
				self.assertTrue(out["is_pass"])
				self.assertEqual(out["value_text"], "KA25AB1234")

	def test_a_scan_step_nobody_has_touched_is_still_unanswered(self):
		step = {"step_code": "SC1", "response_type": C.SCAN, "scan_count": 1}
		self.assertFalse(evaluation.evaluate(step)["is_answered"])

	def test_spec_summary_reads_like_the_paper_sheet(self):
		self.assertEqual(evaluation.spec_summary(torque_step()), "10 ± 1 Nm (9–11)")
		self.assertEqual(
			evaluation.spec_summary(
				{
					"response_type": C.NUMBER_IN_RANGE,
					"min_value": 52,
					"pass_condition": C.GREATER_THAN_MIN,
					"unit": "V",
				}
			),
			"≥ 52 V",
		)

	def test_photo_policy(self):
		step = choice_step(photo_policy="On Fail")
		self.assertFalse(evaluation.photo_required(step, is_pass=True))
		self.assertTrue(evaluation.photo_required(step, is_pass=False))

		always = choice_step(photo_policy="Always")
		self.assertTrue(evaluation.photo_required(always, is_pass=True))

		off = choice_step(requires_photo=0)
		self.assertFalse(evaluation.photo_required(off, is_pass=False))

	def test_option_can_demand_a_photo_on_its_own(self):
		step = choice_step(requires_photo=0)
		self.assertTrue(evaluation.photo_required(step, is_pass=False, chosen_option=PASS_FAIL[1]))


class TestScoring(unittest.TestCase):
	def _definition(self, **kw) -> dict:
		base = {
			"scoring_enabled": 1,
			"scoring_mode": "Weighted",
			"pass_threshold_pct": 90,
			"critical_fails_allowed": 0,
			"skipped_steps_count_as": "Excluded",
		}
		base.update(kw)
		return base

	def test_weighted_score(self):
		steps = {"A": {"weight": 3, "response_type": C.CHOICE}, "B": {"weight": 1, "response_type": C.CHOICE}}
		results = [
			{
				"step_code": "A",
				"response_type": C.CHOICE,
				"is_pass": 1,
				"is_answered": True,
				"response": "Pass",
			},
			{
				"step_code": "B",
				"response_type": C.CHOICE,
				"is_pass": 0,
				"is_answered": True,
				"response": "Fail",
			},
		]
		out = scoring.score_run(self._definition(), results, steps)
		self.assertEqual(out["score_max"], 4)
		self.assertEqual(out["score_earned"], 3)
		self.assertEqual(out["score_pct"], 75.0)
		self.assertEqual(out["result"], "Fail")

	def test_a_single_critical_fail_fails_a_high_score(self):
		"""The critical budget is separate from the threshold, and defaults to zero."""
		steps = {f"S{i}": {"weight": 1, "response_type": C.CHOICE} for i in range(20)}
		results = [
			{
				"step_code": f"S{i}",
				"response_type": C.CHOICE,
				"is_pass": 1 if i else 0,
				"is_critical": 0 if i else 1,
				"is_answered": True,
				"response": "Pass" if i else "Fail",
			}
			for i in range(20)
		]
		out = scoring.score_run(self._definition(), results, steps)
		self.assertEqual(out["score_pct"], 95.0)
		self.assertEqual(out["critical_count"], 1)
		self.assertEqual(out["result"], "Fail")

	def test_skipped_steps_are_excluded_from_both_sides(self):
		steps = {"A": {"weight": 1, "response_type": C.CHOICE}, "B": {"weight": 1, "response_type": C.CHOICE}}
		results = [
			{
				"step_code": "A",
				"response_type": C.CHOICE,
				"is_pass": 1,
				"is_answered": True,
				"response": "Pass",
			},
			{"step_code": "B", "response_type": C.CHOICE, "is_skipped": 1},
		]
		out = scoring.score_run(self._definition(), results, steps)
		self.assertEqual(out["score_max"], 1)
		self.assertEqual(out["score_pct"], 100.0)
		self.assertEqual(out["skip_count"], 1)

	def test_skipped_can_be_configured_to_fail(self):
		steps = {"A": {"weight": 1, "response_type": C.CHOICE}, "B": {"weight": 1, "response_type": C.CHOICE}}
		results = [
			{
				"step_code": "A",
				"response_type": C.CHOICE,
				"is_pass": 1,
				"is_answered": True,
				"response": "Pass",
			},
			{"step_code": "B", "response_type": C.CHOICE, "is_skipped": 1},
		]
		out = scoring.score_run(self._definition(skipped_steps_count_as="Fail"), results, steps)
		self.assertEqual(out["score_max"], 2)
		self.assertEqual(out["score_pct"], 50.0)

	def test_evidence_steps_do_not_inflate_the_score(self):
		"""A photo is evidence, not a verdict — counting it would flatter every run."""
		steps = {
			"P": {"weight": 5, "response_type": C.PHOTO_ONLY},
			"A": {"weight": 1, "response_type": C.CHOICE},
		}
		results = [
			{"step_code": "P", "response_type": C.PHOTO_ONLY, "is_pass": 1, "is_answered": True},
			{
				"step_code": "A",
				"response_type": C.CHOICE,
				"is_pass": 0,
				"is_answered": True,
				"response": "Fail",
			},
		]
		out = scoring.score_run(self._definition(), results, steps)
		self.assertEqual(out["score_max"], 1)
		self.assertEqual(out["score_pct"], 0.0)

	def test_first_pass_requires_a_clean_sheet(self):
		steps = {"A": {"weight": 1, "response_type": C.CHOICE}}
		clean = [
			{
				"step_code": "A",
				"response_type": C.CHOICE,
				"is_pass": 1,
				"is_answered": True,
				"response": "Pass",
			}
		]
		self.assertEqual(scoring.score_run(self._definition(), clean, steps)["is_first_pass"], 1)

		dirty = [
			{
				"step_code": "A",
				"response_type": C.CHOICE,
				"is_pass": 0,
				"is_answered": True,
				"response": "Fail",
			}
		]
		self.assertEqual(scoring.score_run(self._definition(), dirty, steps)["is_first_pass"], 0)

	def test_scoring_can_be_switched_off_entirely(self):
		steps = {"A": {"weight": 1, "response_type": C.CHOICE}}
		results = [
			{
				"step_code": "A",
				"response_type": C.CHOICE,
				"is_pass": 0,
				"is_answered": True,
				"response": "Fail",
			}
		]
		out = scoring.score_run(self._definition(scoring_enabled=0), results, steps)
		self.assertEqual(out["result"], "")

	def test_trace_completeness(self):
		expected = {"CELL_MODULE": 3, "SLAVE_BMS": 1}
		scans = [
			{"entity_type": "CELL_MODULE", "serial_no": "A1"},
			{"entity_type": "CELL_MODULE", "serial_no": "A2"},
			{"entity_type": "SLAVE_BMS", "serial_no": "B1"},
		]
		self.assertEqual(scoring.trace_completeness(expected, scans), 75.0)
		self.assertEqual(scoring.trace_completeness(expected, []), 0.0)
		self.assertEqual(scoring.trace_completeness({}, []), 100.0)

	def test_fast_entry_is_advisory(self):
		step = {"expected_seconds": 40}
		self.assertEqual(scoring.entry_flag(step, 5), "Fast Entry")
		self.assertEqual(scoring.entry_flag(step, 30), "Normal")
		self.assertEqual(scoring.entry_flag({"expected_seconds": 0}, 1), "Normal")


class TestConditions(unittest.TestCase):
	def test_no_conditions_means_always_visible(self):
		self.assertTrue(conditions.is_visible({"step_code": "A"}, {}))

	def test_equals_and_is_failed(self):
		answers = {"A": {"is_answered": True, "is_pass": 0, "response": "Fail"}}
		step = {
			"step_code": "B",
			"visibility_conditions": [{"when_step": "A", "operator": "Is Failed"}],
		}
		self.assertTrue(conditions.is_visible(step, answers))

		answers["A"] = {"is_answered": True, "is_pass": 1, "response": "Pass"}
		self.assertFalse(conditions.is_visible(step, answers))

	def test_unanswered_dependency_hides_the_step(self):
		step = {
			"step_code": "B",
			"visibility_conditions": [{"when_step": "A", "operator": "Equals", "value": "Fail"}],
		}
		self.assertFalse(conditions.is_visible(step, {}))

	def test_clauses_fold_left_to_right(self):
		answers = {
			"A": {"is_answered": True, "is_pass": 1, "response": "Pass"},
			"B": {"is_answered": True, "is_pass": 0, "response": "Fail"},
		}
		step = {
			"step_code": "C",
			"visibility_conditions": [
				{"when_step": "A", "operator": "Equals", "value": "Pass"},
				{"when_step": "B", "operator": "Equals", "value": "Fail", "join": "AND"},
			],
		}
		self.assertTrue(conditions.is_visible(step, answers))

	def test_numeric_comparison(self):
		answers = {"V": {"is_answered": True, "value_numeric": 51.2, "is_pass": 0}}
		step = {
			"step_code": "X",
			"visibility_conditions": [{"when_step": "V", "operator": "Less Than", "value": "52"}],
		}
		self.assertTrue(conditions.is_visible(step, answers))

	def test_linter_catches_forward_and_unknown_references(self):
		steps = [
			{"step_code": "A", "visibility_conditions": [{"when_step": "B", "operator": "Is Pass"}]},
			{"step_code": "B", "visibility_conditions": [{"when_step": "GHOST", "operator": "Is Pass"}]},
		]
		issues = conditions.unreachable_steps(steps)
		self.assertEqual(len(issues), 2)
		self.assertIn("not answered before it", issues[0])
		self.assertIn("unknown step", issues[1])


class TestComputed(unittest.TestCase):
	def test_diff_is_the_spread(self):
		answers = {"MAX": {"value_numeric": 4.12}, "MIN": {"value_numeric": 4.09}}
		self.assertAlmostEqual(computed.evaluate_expression("DIFF(MAX, MIN)", answers), 0.03, places=6)

	def test_avg_sum_max_min(self):
		answers = {"A": {"value_numeric": 52.0}, "B": {"value_numeric": 54.0}, "C": {"value_numeric": 56.0}}
		self.assertEqual(computed.evaluate_expression("AVG(A, B, C)", answers), 54.0)
		self.assertEqual(computed.evaluate_expression("SUM(A, B, C)", answers), 162.0)
		self.assertEqual(computed.evaluate_expression("MAX(A, B, C)", answers), 56.0)
		self.assertEqual(computed.evaluate_expression("MIN(A, B, C)", answers), 52.0)

	def test_literals_mix_with_step_codes(self):
		self.assertEqual(computed.evaluate_expression("DIFF(A, 10)", {"A": {"value_numeric": 12.0}}), 2.0)

	def test_unanswered_inputs_yield_nothing(self):
		self.assertIsNone(computed.evaluate_expression("DIFF(MAX, MIN)", {}))

	def test_malformed_expressions_return_none_rather_than_raising(self):
		"""A broken expression must never cost an operator their run."""
		for bad in ("", "MAX", "__import__('os')", "DIFF(A", "A + B", "EVAL(A)", "DIFF(NESTED(A), B)"):
			self.assertIsNone(computed.evaluate_expression(bad, {"A": {"value_numeric": 1.0}}))

	def test_referenced_steps_skips_literals(self):
		self.assertEqual(computed.referenced_steps("DIFF(MAX, 10)"), ["MAX"])


class TestScanning(unittest.TestCase):
	def test_ddmmyy_is_the_industry_convention(self):
		self.assertEqual(scanning.parse_mfg_date("120826", "DDMMYY"), "2026-08-12")

	def test_other_formats(self):
		self.assertEqual(scanning.parse_mfg_date("12082026", "DDMMYYYY"), "2026-08-12")
		self.assertEqual(scanning.parse_mfg_date("260812", "YYMMDD"), "2026-08-12")
		self.assertEqual(scanning.parse_mfg_date("20260812", "YYYYMMDD"), "2026-08-12")

	def test_nonsense_dates_do_not_raise(self):
		"""A bad date must never lose the scan it came from."""
		for bad in ("", "99", "999999", "321326", "abcdef"):
			self.assertIsNone(scanning.parse_mfg_date(bad, "DDMMYY"))


class TestFreeTextLandsFromEitherField(unittest.TestCase):
	"""A typed serial must be recorded whichever field the app put it in.

	Found on a real handset. The app has two renderers for a text step and they
	disagree: the list card posts the typed answer as `response`, the full-screen
	runner as `value`. `evaluate` read only `response`, so every serial, batch number
	and date typed on the runner — the screen operators actually use — came back
	`is_answered = False`. The module showed "0 of 26" with answers on the screen,
	the submit gate demanded checks that had been filled in, and the text never
	reached the record at all.
	"""

	def test_text_in_the_response_field_is_answered(self):
		step = {"step_code": "T", "response_type": "Text Short"}

		out = evaluation.evaluate(step, response="SN-9931", value=None)

		self.assertTrue(out["is_answered"])
		self.assertEqual(out["value_text"], "SN-9931")

	def test_text_in_the_value_field_is_answered_too(self):
		step = {"step_code": "T", "response_type": "Text Short"}

		out = evaluation.evaluate(step, response=None, value="SN-9931")

		self.assertTrue(out["is_answered"])
		self.assertEqual(out["value_text"], "SN-9931")
		# Mirrored onto `response` so the report and the replay check agree with
		# every other answer in the run.
		self.assertEqual(out["response"], "SN-9931")

	def test_a_blank_text_answer_is_still_unanswered(self):
		step = {"step_code": "T", "response_type": "Text Short"}

		out = evaluation.evaluate(step, response="", value="   ")

		self.assertFalse(out["is_answered"])

	def test_a_date_typed_into_value_lands(self):
		step = {"step_code": "D", "response_type": "Date"}

		out = evaluation.evaluate(step, response=None, value="2026-08-17")

		self.assertTrue(out["is_answered"])
		self.assertEqual(out["value_text"], "2026-08-17")
