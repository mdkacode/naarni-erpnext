# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""A verdict belongs to a finished inspection, and nothing else.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_process_completion

Three separate ways a run used to be judged before it was done:

* `_recompute` runs on **every answer**, and scored whatever had been answered
  so far against the pass threshold — so a run carried a Pass or a Fail from its
  first tap onwards, and every screen reading `result` showed a decision nobody
  had made about a battery nobody had finished.
* `submit_stage` looked only at the stage in hand and the stages after it, so a
  run could be stamped Passed with an earlier stage never submitted.
* A critical failure set the status to Quarantined at check 3 of 59 — a finished,
  failed inspection with 56 checks still to do — and dropped the run out of the
  operator's resume list, with no way back to it.

The rule these lock in: keep the numbers live, defer the judgement, and never
lose the ability to carry on where you left off.
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import process
from vehicle_maintenance.process_engine import constants as C

CODE = "TEST-COMPLETION-PROC"


class CompletionTestBase(FrappeTestCase):
	def setUp(self):
		frappe.set_user("Administrator")
		self.addCleanup(frappe.db.rollback)
		self.addCleanup(frappe.set_user, "Administrator")
		self.definition = _ensure_definition()

	def _run(self, **extra):
		doc = frappe.get_doc(
			{
				"doctype": "Process Run",
				"process_definition": self.definition,
				"run_identifier": "PACK-COMPLETION",
				"status": C.STATUS_IN_PROGRESS,
				"started_by": frappe.session.user,
				"started_at": frappe.utils.now_datetime(),
				**extra,
			}
		)
		doc.insert(ignore_permissions=True)
		return doc

	def _answer(self, doc, step_code, response="Pass", is_pass=1, is_critical=0):
		doc.append(
			"results",
			{
				"step_code": step_code,
				"stage": "S1",
				"response": response,
				"is_pass": is_pass,
				"is_critical": is_critical,
				"answered_by": frappe.session.user,
				"answered_at": frappe.utils.now_datetime(),
			},
		)
		doc.save(ignore_permissions=True)
		return doc


class TestVerdictWaitsForCompletion(CompletionTestBase):
	def test_an_unfinished_run_carries_no_verdict(self):
		doc = self._run()
		self._answer(doc, "C1")

		doc.reload()
		self.assertEqual(doc.result or "", "")
		self.assertFalse(doc.completed_at)

	def test_a_failing_answer_does_not_make_it_a_failed_run(self):
		"""The complaint in one test.

		One bad check part-way through is a bad check, not a failed inspection —
		there are still 50 to do, and the operator has to be able to do them.
		"""
		doc = self._run()
		self._answer(doc, "C1", response="Fail", is_pass=0)

		doc.reload()
		self.assertEqual(doc.result or "", "")
		self.assertEqual(doc.status, C.STATUS_IN_PROGRESS)

	def test_the_numbers_stay_live_while_the_judgement_waits(self):
		# Progress is what an operator navigates by; only the verdict is held
		# back. Blanking the counts too would be a worse cure than the disease.
		doc = self._run()
		self._answer(doc, "C1")
		self._answer(doc, "C2")

		doc.reload()
		self.assertEqual(doc.answered_count, 2)
		self.assertEqual(doc.pass_count, 2)
		self.assertEqual(doc.result or "", "")

	def test_the_verdict_appears_once_it_is_finished(self):
		doc = self._run()
		self._answer(doc, "C1")
		self._answer(doc, "C2")

		doc.completed_at = frappe.utils.now_datetime()
		doc.save(ignore_permissions=True)
		doc.reload()

		self.assertTrue(doc.result)


class TestQuarantineIsNotAVerdictYet(CompletionTestBase):
	def test_a_recorded_quarantine_reason_still_decides_the_end(self):
		"""Deferring the status must not make a critical failure survivable.

		The reason is written the moment the check fails; `_finalise` honours it
		whatever the score says. Without this, a run could trip a quarantine
		early, answer everything else perfectly, and finish Passed.
		"""
		doc = self._run(quarantine_reason="Critical failure at check 3")
		self._answer(doc, "C1")
		self._answer(doc, "C2")

		definition = frappe.get_cached_doc("Process Definition", self.definition)
		process._finalise(doc, definition)
		doc.save(ignore_permissions=True)

		self.assertEqual(doc.status, C.STATUS_QUARANTINED)

	def test_a_run_with_a_quarantine_reason_is_still_open_until_finished(self):
		doc = self._run(quarantine_reason="Critical failure at check 3")

		self.assertEqual(doc.status, C.STATUS_IN_PROGRESS)
		self.assertFalse(doc.completed_at)
		# And still editable — the remaining checks have to be recordable.
		doc.ensure_open()


class TestResumeList(CompletionTestBase):
	def test_an_unfinished_run_is_offered_for_continuing(self):
		doc = self._run()

		names = [r["name"] for r in process.my_open_runs()["data"]]

		self.assertIn(doc.name, names)

	def test_a_run_that_tripped_a_critical_check_is_still_offered(self):
		"""The one an operator most needs to come back to.

		It used to disappear: the list filtered on a set of statuses, and
		Quarantined was not among them, so a run failed at check 3 could neither
		be finished nor found.
		"""
		doc = self._run(quarantine_reason="Critical failure at check 3")
		frappe.db.set_value("Process Run", doc.name, "status", C.STATUS_QUARANTINED, update_modified=False)

		names = [r["name"] for r in process.my_open_runs()["data"]]

		self.assertIn(doc.name, names)

	def test_a_finished_run_is_not_offered(self):
		doc = self._run()
		frappe.db.set_value(
			"Process Run",
			doc.name,
			{"status": C.STATUS_PASSED, "completed_at": frappe.utils.now_datetime()},
			update_modified=False,
		)

		names = [r["name"] for r in process.my_open_runs()["data"]]

		self.assertNotIn(doc.name, names)


class TestOutstandingWork(CompletionTestBase):
	def test_an_unsubmitted_stage_counts_as_outstanding(self):
		"""`submit_stage` used to look only at the stage in hand.

		A run could reach the end with an earlier stage never submitted and be
		stamped Passed on the strength of the last one.
		"""
		doc = self._run()
		definition = frappe.get_cached_doc("Process Definition", self.definition)

		outstanding = process.outstanding_work(doc, definition)

		self.assertTrue(outstanding["stages"])

	def test_an_unanswered_mandatory_check_counts_as_outstanding(self):
		doc = self._run()
		definition = frappe.get_cached_doc("Process Definition", self.definition)

		outstanding = process.outstanding_work(doc, definition)

		self.assertIn("1", [str(s) for s in outstanding["steps"]] or [""])


def _ensure_definition() -> str:
	"""A two-check, one-stage published process to hang runs off."""
	if frappe.db.exists("Process Definition", CODE):
		return CODE
	doc = frappe.get_doc(
		{
			"doctype": "Process Definition",
			"process_code": CODE,
			"process_name": "Completion Test Process",
			"version": 1,
			"status": "Published",
			"scoring_mode": "Simple",
			"pass_threshold_pct": 100,
		}
	)
	doc.name = CODE
	doc.append("stages", {"stage_code": "S1", "label": "Stage One", "sequence": 1})
	for i, code in enumerate(("C1", "C2"), start=1):
		doc.append(
			"steps",
			{
				"step_code": code,
				"stage": "S1",
				"sequence": i,
				"display_no": str(i),
				"label": f"Check {i}",
				"response_type": "Choice",
				"is_mandatory": 1,
				"is_active": 1,
				"weight": 1,
			},
		)
	doc.insert(ignore_permissions=True)
	return doc.name
