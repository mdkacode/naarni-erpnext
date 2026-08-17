# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""One pack, one record, several pairs of hands.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_process_collaboration

A battery is fifty-nine checks across several modules and a shift puts more than
one person on it. The engine used to model an inspection as one operator's
private work, which failed in two directions at once: the second person to scan
a pack label opened a *second* run of the same physical battery, and neither
operator could see the other's half — so one pack ended the shift with two
records, two part-scores and two verdicts.

What is pinned here:

* scanning a label that is already being inspected **joins** that inspection;
* a finished pack still starts a fresh one, because rework is a new inspection;
* the record says who has worked it, and the board says where they got to.
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import process
from vehicle_maintenance.process_engine import collaboration
from vehicle_maintenance.process_engine import constants as C

CODE = "TEST-COLLAB-PROC"


class CollaborationTestBase(FrappeTestCase):
	def setUp(self):
		self.addCleanup(frappe.db.rollback)
		self.addCleanup(frappe.set_user, "Administrator")
		frappe.set_user("Administrator")
		self.definition = _ensure_definition()
		self.mate = _ensure_user("collab-mate@test.localhost", "Collab Mate")
		# `start_run` commits — it has to, so a dropped response never costs an
		# operator a run — which means the usual rollback cleanup cannot reach the
		# rows these tests create. A label unique to each test is what keeps them
		# from joining *each other's* packs, which is precisely the behaviour
		# under test and would otherwise make some of these pass by accident.
		self.pack = f"PACK-{frappe.generate_hash(length=8).upper()}"
		self.addCleanup(self._delete_runs)

	def _delete_runs(self):
		frappe.db.rollback()
		for row in frappe.get_all(
			"Process Run", filters={"run_identifier": ["like", f"{self.pack}%"]}, fields=["name"]
		):
			frappe.delete_doc("Process Run", row["name"], force=True, ignore_permissions=True)
		frappe.db.commit()  # nosemgrep — test teardown of rows a committing endpoint created

	def _start(self, identifier=None, **kwargs):
		return process.start_run(process=CODE, identifier=identifier or self.pack, **kwargs)["data"]

	def _answer(self, run_name, step_code, user, stage="S1"):
		doc = frappe.get_doc("Process Run", run_name)
		doc.append(
			"results",
			{
				"step_code": step_code,
				"stage": stage,
				"response": "Pass",
				"is_pass": 1,
				"answered_by": user,
				"answered_at": frappe.utils.now_datetime(),
			},
		)
		doc.save(ignore_permissions=True)
		return doc


class TestOnePackOneRun(CollaborationTestBase):
	def test_scanning_the_same_pack_joins_the_open_inspection(self):
		"""The whole point. Two operators, two phones, one battery.

		Different `client_uuid`s — these are genuinely two different devices
		starting work, not one device retrying.
		"""
		first = self._start(client_uuid=frappe.generate_hash(length=18))
		second = self._start(client_uuid=frappe.generate_hash(length=18))

		self.assertEqual(second["name"], first["name"])
		self.assertEqual(frappe.db.count("Process Run", {"run_identifier": self.pack}), 1)

	def test_a_scan_and_a_typed_entry_are_the_same_pack(self):
		# A scanner returns upper case with no padding; a person types whatever
		# they type. That difference alone must not open a second record.
		first = self._start()
		second = self._start(identifier=f"  {self.pack.lower()} ")

		self.assertEqual(second["name"], first["name"])

	def test_a_different_pack_gets_its_own_record(self):
		first = self._start()
		second = self._start(identifier=f"{self.pack}-OTHER")

		self.assertNotEqual(second["name"], first["name"])

	def test_a_finished_pack_starts_a_fresh_inspection(self):
		"""Rework is a new inspection, not an edit of the old one.

		Joining here would silently reopen a closed record and overwrite the
		history of what the pack looked like the first time round.
		"""
		first = self._start()
		frappe.db.set_value(
			"Process Run",
			first["name"],
			{"status": C.STATUS_PASSED, "completed_at": frappe.utils.now_datetime()},
			update_modified=False,
		)

		second = self._start()

		self.assertNotEqual(second["name"], first["name"])

	def test_a_quarantined_pack_is_still_joinable(self):
		# A run that tripped a critical check is unfinished work, and it is the
		# one a colleague most needs to pick up rather than duplicate.
		first = self._start()
		frappe.db.set_value(
			"Process Run", first["name"], "status", C.STATUS_QUARANTINED, update_modified=False
		)

		second = self._start()

		self.assertEqual(second["name"], first["name"])

	def test_a_run_with_no_label_never_joins_anything(self):
		"""Two unlabelled runs are two different packs, not one shared one.

		Blank must never match blank — that would collapse every anonymous
		inspection on the site into a single record.
		"""
		first = process.start_run(process=CODE)["data"]
		second = process.start_run(process=CODE)["data"]

		self.assertNotEqual(second["name"], first["name"])

	def test_the_client_uuid_still_wins_for_a_retry(self):
		uuid = frappe.generate_hash(length=18)
		first = self._start(client_uuid=uuid)
		second = self._start(client_uuid=uuid)

		self.assertEqual(second["name"], first["name"])


class TestFindOpenRun(CollaborationTestBase):
	def test_a_scanned_label_reports_the_inspection_in_progress(self):
		started = self._start()
		self._answer(started["name"], "C1", self.mate)

		found = process.find_open_run(process=CODE, identifier=self.pack)["data"]

		self.assertTrue(found["found"])
		self.assertEqual(found["run"], started["name"])
		self.assertTrue(found["stages"])

	def test_an_unknown_label_is_an_answer_not_an_error(self):
		found = process.find_open_run(process=CODE, identifier=f"{self.pack}-NOBODY")["data"]

		self.assertFalse(found["found"])

	def test_it_names_who_is_on_the_pack(self):
		started = self._start()
		self._answer(started["name"], "C1", self.mate)

		found = process.find_open_run(process=CODE, identifier=self.pack)["data"]

		self.assertIn(self.mate, [p["user"] for p in found["participants"]])


class TestParticipants(CollaborationTestBase):
	def test_the_person_who_started_it_counts_even_with_no_answers(self):
		# They identified the pack, which is work. A run showing "0 people" while
		# somebody is plainly standing at it reads as broken.
		started = self._start()
		doc = frappe.get_doc("Process Run", started["name"])

		people = collaboration.participants(doc)

		self.assertEqual([p["user"] for p in people], ["Administrator"])

	def test_a_colleagues_answers_put_them_on_the_record(self):
		started = self._start()
		doc = self._answer(started["name"], "C1", self.mate)

		people = collaboration.participants(doc)

		self.assertEqual(len(people), 2)
		mate = next(p for p in people if p["user"] == self.mate)
		self.assertEqual(mate["answers"], 1)
		self.assertTrue(mate["full_name"])

	def test_the_run_carries_the_list_so_one_request_answers_the_question(self):
		started = self._start()
		self._answer(started["name"], "C1", self.mate)

		data = process.get_run(started["name"])["data"]

		self.assertIn(self.mate, [p["user"] for p in data["participants"]])


class TestBoard(CollaborationTestBase):
	def test_every_module_is_listed_with_its_progress(self):
		started = self._start()
		self._answer(started["name"], "C1", self.mate)

		board = process.run_board(started["name"])["data"]

		stage = next(s for s in board["stages"] if s["stage_code"] == "S1")
		self.assertEqual(stage["total"], 2)
		self.assertEqual(stage["answered"], 1)
		self.assertEqual(stage["outstanding"], 1)

	def test_a_module_says_who_was_last_in_it(self):
		"""The question the second operator actually has at the bench."""
		started = self._start()
		self._answer(started["name"], "C1", self.mate)

		board = process.run_board(started["name"])["data"]

		stage = next(s for s in board["stages"] if s["stage_code"] == "S1")
		self.assertEqual(stage["last_user"], self.mate)
		self.assertTrue(stage["last_by"])
		self.assertTrue(stage["active_now"])

	def test_an_untouched_module_claims_nobody(self):
		started = self._start()

		board = process.run_board(started["name"])["data"]

		stage = next(s for s in board["stages"] if s["stage_code"] == "S2")
		self.assertIsNone(stage["last_user"])
		self.assertFalse(stage["active_now"])

	def test_the_board_uses_the_word_the_plant_uses(self):
		# "Module" for battery QC. A board headed "Stage" is a board about the
		# software rather than about the battery.
		started = self._start()

		board = process.run_board(started["name"])["data"]

		self.assertEqual(board["stage_label"], "Module")


class TestResumeListIsShared(CollaborationTestBase):
	def test_a_run_i_answered_into_is_offered_back_to_me(self):
		"""Started by a colleague, worked by me — it is my open work too.

		Keyed on `started_by` alone, this vanished from the phone that did it.
		"""
		started = self._start()
		self._answer(started["name"], "C1", self.mate)

		frappe.set_user(self.mate)
		names = [r["name"] for r in process.my_open_runs()["data"]]

		self.assertIn(started["name"], names)

	def test_the_list_says_how_many_people_are_on_each_pack(self):
		started = self._start()
		self._answer(started["name"], "C1", self.mate)

		row = next(r for r in process.my_open_runs()["data"] if r["name"] == started["name"])

		self.assertEqual(row["participant_count"], 2)


def _ensure_user(email: str, full_name: str) -> str:
	if frappe.db.exists("User", email):
		return email
	user = frappe.get_doc(
		{
			"doctype": "User",
			"email": email,
			"first_name": full_name,
			"send_welcome_email": 0,
			"enabled": 1,
			# Service-style test accounts need both gates open: the app makes
			# `mobile_no` mandatory, and the field is not one this test supplies.
			"roles": [{"role": C.ROLE_OPERATOR}],
		}
	)
	user.flags.ignore_phone_requirement = True
	user.insert(ignore_permissions=True, ignore_mandatory=True)
	return user.name


def _ensure_definition() -> str:
	"""A published two-module, two-check process to hang runs off."""
	if frappe.db.exists("Process Definition", CODE):
		return CODE
	doc = frappe.get_doc(
		{
			"doctype": "Process Definition",
			"process_code": CODE,
			"process_name": "Collaboration Test Process",
			"family": "TEST_COLLAB",
			"version": 1,
			"status": C.DEF_PUBLISHED,
			"stage_label": "Module",
			"subject_label": "Battery Pack",
			"scoring_mode": "Simple",
			"pass_threshold_pct": 100,
		}
	)
	doc.name = CODE
	doc.append("stages", {"stage_code": "S1", "label": "Module One", "sequence": 1})
	doc.append("stages", {"stage_code": "S2", "label": "Module Two", "sequence": 2})
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
