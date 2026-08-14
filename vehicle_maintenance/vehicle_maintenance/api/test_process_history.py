"""The operator's own inspection record — `my_history` and `run_report`.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_process_history

These two endpoints are the only place in the engine where an operator reads
their *own* work back, so the properties worth pinning are the ones that would
quietly mislead someone about what they did: whose runs are counted, whether a
quarantined inspection still shows up as work done, and whether a photo stays
attached to the step it was taken at.
"""

import random

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import process
from vehicle_maintenance.process_engine import constants as C

CODE = "TEST-HIST-PROC"


class ProcessHistoryTestBase(FrappeTestCase):
	def setUp(self):
		# Cleanup is registered rather than done in a `tearDown` override: the
		# override is what CI's semgrep rule blocks, and `addCleanup` composes
		# with whatever the base class already does instead of replacing it.
		#
		# The rollback is not optional here. These tests insert Process Runs and
		# then assert on counts, so without it the second test sees the first
		# test's runs and every tally is wrong — which is exactly what happened
		# when it was dropped (8 failures, "13 != 1").
		#
		# LIFO order: the user is restored first, then the transaction unwinds.
		self.addCleanup(frappe.db.rollback)
		self.addCleanup(frappe.set_user, "Administrator")
		self.operator = _ensure_user("hist-op@test.localhost", "Hist Operator")
		self.other = _ensure_user("hist-other@test.localhost", "Other Operator")
		self.definition = _ensure_definition()
		frappe.set_user(self.operator)

	def _run(self, *, status=C.STATUS_PASSED, user=None, identifier="PACK-1", **extra):
		"""A Process Run owned by `user`, inserted straight rather than via the API.

		`start_run` would drag in the role gate and the published-definition
		check, neither of which is under test here — what matters is what the
		history endpoints do with rows that already exist.
		"""
		doc = frappe.get_doc(
			{
				"doctype": "Process Run",
				"process_definition": self.definition,
				"run_identifier": identifier,
				"status": status,
				"started_by": user or self.operator,
				"started_at": frappe.utils.now_datetime(),
				"completed_at": frappe.utils.now_datetime()
				if status in (C.STATUS_PASSED, C.STATUS_QUARANTINED)
				else None,
				**extra,
			}
		)
		doc.insert(ignore_permissions=True)
		return doc


class TestMyHistory(ProcessHistoryTestBase):
	def test_only_my_own_runs_are_listed(self):
		self._run(identifier="MINE")
		self._run(identifier="THEIRS", user=self.other)

		rows = process.my_history()["data"]["runs"]

		self.assertEqual([r["run_identifier"] for r in rows], ["MINE"])

	def test_a_quarantined_run_still_counts_as_work_done(self):
		# The operator did the inspection; the pack failed. Hiding it would
		# under-report exactly the inspections that matter most.
		self._run(status=C.STATUS_QUARANTINED, identifier="BAD-PACK")

		data = process.my_history()["data"]

		self.assertEqual([r["run_identifier"] for r in data["runs"]], ["BAD-PACK"])
		self.assertEqual(data["stats"]["total"], 1)
		self.assertEqual(data["stats"]["quarantined"], 1)

	def test_a_quarantined_run_counts_towards_today(self):
		# It has no `completed_at` — the engine never sets one on a run it holds.
		# Dating the tally on completion alone reported "0 today" to an operator
		# staring at the three inspections they had just done.
		doc = self._run(status=C.STATUS_QUARANTINED, identifier="HELD-TODAY")
		frappe.db.set_value("Process Run", doc.name, "completed_at", None, update_modified=False)

		stats = process.my_history()["data"]["stats"]

		self.assertEqual(stats["today"], 1)
		self.assertEqual(stats["week"], 1)

	def test_work_from_last_month_is_in_the_total_but_not_this_week(self):
		doc = self._run(identifier="OLD")
		old = frappe.utils.add_days(frappe.utils.today(), -30)
		frappe.db.set_value("Process Run", doc.name, "completed_at", old, update_modified=False)
		frappe.db.set_value("Process Run", doc.name, "started_at", old, update_modified=False)

		stats = process.my_history()["data"]["stats"]

		self.assertEqual(stats["total"], 1)
		self.assertEqual(stats["today"], 0)
		self.assertEqual(stats["week"], 0)

	def test_an_unfinished_run_is_not_in_the_finished_list(self):
		self._run(status=C.STATUS_IN_PROGRESS, identifier="HALF-DONE")

		data = process.my_history()["data"]

		self.assertEqual(data["runs"], [])
		self.assertEqual(data["stats"]["total"], 0)
		self.assertEqual(data["stats"]["open"], 1)

	def test_open_scope_returns_the_unfinished_ones(self):
		self._run(status=C.STATUS_IN_PROGRESS, identifier="HALF-DONE")
		self._run(status=C.STATUS_PASSED, identifier="DONE")

		rows = process.my_history(scope="open")["data"]["runs"]

		self.assertEqual([r["run_identifier"] for r in rows], ["HALF-DONE"])

	def test_test_runs_never_appear(self):
		# A trial run during authoring is not an inspection anyone performed.
		self._run(identifier="REHEARSAL", is_test_run=1)

		data = process.my_history()["data"]

		self.assertEqual(data["runs"], [])
		self.assertEqual(data["stats"]["total"], 0)

	def test_the_first_photo_becomes_the_row_thumbnail(self):
		doc = self._run(identifier="WITH-PHOTOS")
		doc.append("photos", {"step_code": "S1", "file_url": "/private/files/first.jpg"})
		doc.append("photos", {"step_code": "S2", "file_url": "/private/files/second.jpg"})
		doc.save(ignore_permissions=True)

		row = process.my_history()["data"]["runs"][0]

		self.assertEqual(row["photo_count"], 2)
		self.assertEqual(row["thumb"], "/private/files/first.jpg")

	def test_a_run_without_photos_reports_zero_rather_than_omitting_the_key(self):
		# The app reads these unconditionally; a missing key would deserialise
		# as a default and hide the difference between "none" and "not loaded".
		self._run(identifier="NO-PHOTOS")

		row = process.my_history()["data"]["runs"][0]

		self.assertEqual(row["photo_count"], 0)
		self.assertIsNone(row["thumb"])

	def test_photo_counts_do_not_bleed_between_runs(self):
		with_photo = self._run(identifier="A")
		with_photo.append("photos", {"step_code": "S1", "file_url": "/private/files/a.jpg"})
		with_photo.save(ignore_permissions=True)
		self._run(identifier="B")

		by_id = {r["run_identifier"]: r for r in process.my_history()["data"]["runs"]}

		self.assertEqual(by_id["A"]["photo_count"], 1)
		self.assertEqual(by_id["B"]["photo_count"], 0)

	def test_limit_is_clamped_rather_than_trusted(self):
		self._run(identifier="ONE")

		# A client asking for everything must not be able to ask for everything.
		self.assertLessEqual(len(process.my_history(limit=10_000)["data"]["runs"]), 100)
		self.assertEqual(len(process.my_history(limit=0)["data"]["runs"]), 1)

	def test_offset_pages_without_repeating(self):
		for i in range(3):
			self._run(identifier=f"P{i}")

		first = process.my_history(limit=2)["data"]["runs"]
		second = process.my_history(limit=2, offset=2)["data"]["runs"]

		self.assertEqual(len(first), 2)
		self.assertEqual(len(second), 1)
		self.assertFalse({r["name"] for r in first} & {r["name"] for r in second})


class TestRunReport(ProcessHistoryTestBase):
	def _answered_run(self):
		doc = self._run(identifier="PACK-9")
		doc.append(
			"results",
			{
				"step_code": "BEF_01",
				"stage": "BEFORE",
				"section": "Welding",
				"label": "Check cooling plate welding quality",
				"response_type": "Choice",
				"response": "Pass",
				"is_pass": 1,
			},
		)
		doc.append(
			"results",
			{
				"step_code": "BEF_02",
				"stage": "BEFORE",
				"section": "Torque",
				"label": "Terminal torque",
				"response_type": "Number with Tolerance",
				"value_numeric": 10.5,
				"unit": "Nm",
				"spec_summary": "10 ± 1 Nm",
				"is_pass": 1,
			},
		)
		doc.save(ignore_permissions=True)
		return doc

	def test_answers_come_back_with_the_label_the_operator_read(self):
		# Read from the run's own rows, not the live definition — re-publishing a
		# process must not rewrite the history of a finished inspection.
		self._answered_run()
		name = process.my_history()["data"]["runs"][0]["name"]

		report = process.run_report(name)["data"]
		steps = report["stages"][0]["steps"]

		self.assertEqual(report["run_identifier"], "PACK-9")
		self.assertEqual(steps[0]["label"], "Check cooling plate welding quality")
		self.assertEqual(steps[0]["response"], "Pass")
		self.assertEqual(steps[1]["value_numeric"], 10.5)
		self.assertEqual(steps[1]["spec_summary"], "10 ± 1 Nm")

	def test_a_photo_stays_on_the_step_it_was_taken_at(self):
		doc = self._answered_run()
		doc.append(
			"photos",
			{
				"step_code": "BEF_02",
				"file_url": "/private/files/torque.jpg",
				"latitude": 28.61,
				"longitude": 77.20,
				"location_source": "GPS",
			},
		)
		doc.save(ignore_permissions=True)

		steps = process.run_report(doc.name)["data"]["stages"][0]["steps"]
		by_code = {s["step_code"]: s for s in steps}

		self.assertEqual(by_code["BEF_01"]["photos"], [])
		self.assertEqual(len(by_code["BEF_02"]["photos"]), 1)
		self.assertEqual(by_code["BEF_02"]["photos"][0]["latitude"], 28.61)

	def test_a_photo_on_an_unanswered_step_is_not_dropped(self):
		# Evidence someone looked, even though nothing was recorded.
		doc = self._answered_run()
		doc.append("photos", {"step_code": "GHOST", "file_url": "/private/files/ghost.jpg"})
		doc.save(ignore_permissions=True)

		report = process.run_report(doc.name)["data"]

		self.assertEqual([g["step_code"] for g in report["unmatched_photos"]], ["GHOST"])

	def test_the_header_carries_who_did_it(self):
		doc = self._answered_run()

		report = process.run_report(doc.name)["data"]

		self.assertEqual(report["started_by"], self.operator)
		self.assertEqual(report["started_by_name"], "Hist Operator")

	def test_stages_group_their_own_steps(self):
		doc = self._run(identifier="TWO-STAGE")
		doc.append("results", {"step_code": "A1", "stage": "BEFORE", "label": "A", "response": "Pass"})
		doc.append("results", {"step_code": "B1", "stage": "AFTER", "label": "B", "response": "Pass"})
		doc.save(ignore_permissions=True)

		stages = process.run_report(doc.name)["data"]["stages"]

		self.assertEqual([s["stage"] for s in stages], ["BEFORE", "AFTER"])
		self.assertEqual([len(s["steps"]) for s in stages], [1, 1])


# ------------------------------------------------------------------- fixtures


def _free_mobile_no() -> str:
	"""A mobile number no other User holds.

	`User.mobile_no` is unique site-wide, so a hardcoded fixture number breaks
	the moment anyone — a colleague, an earlier test — has already used it.
	"""
	for _attempt in range(20):
		candidate = "9" + "".join(random.choice("0123456789") for _ in range(9))
		if not frappe.db.exists("User", {"mobile_no": candidate}):
			return candidate
	raise RuntimeError("Could not allocate a free test mobile number.")


def _ensure_user(email: str, full_name: str) -> str:
	if not frappe.db.exists("User", email):
		first, _sep, last = full_name.partition(" ")
		user = frappe.get_doc(
			{
				"doctype": "User",
				"email": email,
				"first_name": first,
				"last_name": last or None,
				# The site makes mobile_no mandatory for real people *and* unique;
				# a test fixture is not a real person, so both gates have to be
				# opened and the number picked so it cannot collide with a
				# genuine one already on the site.
				"mobile_no": _free_mobile_no(),
				"send_welcome_email": 0,
				"roles": [{"role": "Process Operator"}],
			}
		)
		user.flags.ignore_phone_requirement = True
		user.insert(ignore_permissions=True, ignore_mandatory=True)
	return email


def _ensure_definition() -> str:
	if frappe.db.exists("Process Definition", CODE):
		return CODE
	doc = frappe.get_doc(
		{
			"doctype": "Process Definition",
			"process_code": CODE,
			"process_name": "History Test Process",
			"stages": [
				{"stage_code": "BEFORE", "label": "Before Installation", "sequence": 1},
				{"stage_code": "AFTER", "label": "After Installation", "sequence": 2},
			],
		}
	)
	doc.insert(ignore_permissions=True)
	return doc.name
