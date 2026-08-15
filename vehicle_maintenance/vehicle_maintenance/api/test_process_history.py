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
from vehicle_maintenance.process_engine.doctype.process_run import process_run as run_perms

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


def _ensure_user(email: str, full_name: str, role: str = "Process Operator") -> str:
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
				"roles": [{"role": role}],
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


class TestRunIsolation(ProcessHistoryTestBase):
	"""One operator must not be able to read or edit another's inspection.

	The DocType grants `Process Operator` read *and write* on Process Run with
	`if_owner = 0`, so before the permission hooks existed any operator could
	open — and answer into — a colleague's run. These tests pin the hooks rather
	than the role table, because the role genuinely has to stay broad: a
	supervisor needs to read every run in order to verify one.
	"""

	def test_an_operator_cannot_read_another_operators_run(self):
		theirs = self._run(user=self.other, identifier="THEIRS")

		self.assertFalse(
			frappe.has_permission("Process Run", doc=theirs, user=self.operator)
		)

	def test_an_operator_cannot_write_to_another_operators_run(self):
		# The one that matters most: reading someone's inspection is a privacy
		# problem, writing to it corrupts the plant's record of a battery.
		theirs = self._run(user=self.other, identifier="THEIRS")

		self.assertFalse(
			frappe.has_permission("Process Run", doc=theirs, ptype="write", user=self.operator)
		)

	def test_an_operator_can_still_work_on_their_own_run(self):
		mine = self._run(user=self.operator, identifier="MINE")

		self.assertTrue(
			frappe.has_permission("Process Run", doc=mine, ptype="write", user=self.operator)
		)

	def test_a_supervisor_reads_everyones_runs(self):
		theirs = self._run(user=self.other, identifier="THEIRS")
		verifier = _ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier")

		self.assertTrue(frappe.has_permission("Process Run", doc=theirs, user=verifier))

	def test_the_list_query_is_scoped_to_the_operator(self):
		condition = run_perms.get_permission_query_conditions(self.operator)

		self.assertIn("started_by", condition)
		self.assertIn(self.operator, condition)

	def test_a_supervisor_gets_no_list_restriction(self):
		verifier = _ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier")

		self.assertEqual(run_perms.get_permission_query_conditions(verifier), "")

	def test_the_query_condition_escapes_the_user(self):
		# `started_by` is an email and emails are not SQL-safe by nature; the
		# predicate is concatenated, so the escaping is the only thing between a
		# username and the query.
		condition = run_perms.get_permission_query_conditions("a'; DROP TABLE x; --@test.localhost")

		# The payload survives *inside* the quoted literal — that is fine and
		# expected. What must not survive is an unescaped quote closing it early.
		self.assertIn("\\'", condition)
		self.assertNotIn("= 'a'; DROP", condition)

	def test_scoping_is_on_who_performed_it_not_the_owner_field(self):
		# `owner` is Frappe bookkeeping a data import can rewrite; who performed
		# an inspection is a fact about the plant.
		theirs = self._run(user=self.other, identifier="THEIRS")
		frappe.db.set_value("Process Run", theirs.name, "owner", self.operator, update_modified=False)
		theirs.reload()

		self.assertFalse(
			frappe.has_permission("Process Run", doc=theirs, user=self.operator)
		)


class TestInspectionAdminView(ProcessHistoryTestBase):
	def test_an_operator_is_refused_the_cross_operator_view(self):
		with self.assertRaises(frappe.PermissionError):
			process.inspections()

	def test_a_supervisor_sees_every_operators_runs(self):
		self._run(user=self.operator, identifier="MINE")
		self._run(user=self.other, identifier="THEIRS")
		verifier = _ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier")
		frappe.set_user(verifier)

		data = process.inspections(process=self.definition)["data"]

		self.assertEqual(
			{r["run_identifier"] for r in data["runs"]}, {"MINE", "THEIRS"}
		)
		self.assertEqual(data["stats"]["total"], 2)

	def test_the_operator_filter_narrows_to_one_person(self):
		self._run(user=self.operator, identifier="MINE")
		self._run(user=self.other, identifier="THEIRS")
		frappe.set_user(_ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier"))

		data = process.inspections(operator=self.other, process=self.definition)["data"]

		self.assertEqual([r["run_identifier"] for r in data["runs"]], ["THEIRS"])
		self.assertEqual(data["stats"]["total"], 1)

	def test_rows_carry_the_operators_display_name(self):
		self._run(user=self.other, identifier="THEIRS")
		frappe.set_user(_ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier"))

		row = process.inspections(process=self.definition)["data"]["runs"][0]

		self.assertEqual(row["started_by_name"], "Other Operator")

	def test_the_operator_list_covers_the_filtered_range(self):
		self._run(user=self.operator, identifier="MINE")
		self._run(user=self.other, identifier="THEIRS")
		frappe.set_user(_ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier"))

		data = process.inspections(process=self.definition)["data"]

		self.assertEqual(
			{o["user"] for o in data["operators"]}, {self.operator, self.other}
		)

	def test_search_matches_the_run_identifier(self):
		self._run(user=self.operator, identifier="PACK-ALPHA")
		self._run(user=self.other, identifier="PACK-BETA")
		frappe.set_user(_ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier"))

		data = process.inspections(search="ALPHA", process=self.definition)["data"]

		self.assertEqual([r["run_identifier"] for r in data["runs"]], ["PACK-ALPHA"])

	def test_a_date_range_bounds_both_ends(self):
		# Both bounds on one field need the `between` form — assigning twice
		# would silently drop the lower bound and widen the range.
		old = self._run(user=self.operator, identifier="OLD")
		frappe.db.set_value(
			"Process Run", old.name, "started_at", "2020-01-01 09:00:00", update_modified=False
		)
		self._run(user=self.operator, identifier="RECENT")
		frappe.set_user(_ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier"))

		today = frappe.utils.today()
		data = process.inspections(from_date=today, to_date=today, process=self.definition)["data"]

		self.assertEqual([r["run_identifier"] for r in data["runs"]], ["RECENT"])

	def test_test_runs_stay_out_of_the_admin_view_too(self):
		self._run(user=self.operator, identifier="REHEARSAL", is_test_run=1)
		frappe.set_user(_ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier"))

		self.assertEqual(process.inspections(process=self.definition)["data"]["runs"], [])

	def test_limit_is_clamped(self):
		self._run(user=self.operator, identifier="ONE")
		frappe.set_user(_ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier"))

		self.assertLessEqual(len(process.inspections(limit=10_000, process=self.definition)["data"]["runs"]), 200)


class TestInspectionReviewPage(ProcessHistoryTestBase):
	"""The Desk page is the admin's door; it needs its own lock.

	The page's `roles` gate is separate from `_assert_inspection_admin`, and both
	matter: the role list stops the page appearing in the awesomebar and being
	opened at all, the API check stops someone calling the endpoint the page
	happens to use.
	"""

	def _page(self, user: str):
		from frappe.desk.desk_page import get

		frappe.set_user(user)
		return get("inspection-review")

	def test_the_page_exists_and_is_owned_by_the_engine(self):
		doc = frappe.get_doc("Page", "inspection-review")

		self.assertEqual(doc.module, "Process Engine")
		self.assertEqual(doc.title, "Inspection Review")

	def test_an_operator_cannot_open_the_page(self):
		with self.assertRaises(frappe.PermissionError):
			self._page(self.operator)

	def test_a_verifier_can_open_the_page(self):
		verifier = _ensure_user("hist-verifier@test.localhost", "Hist Verifier", "Process Verifier")

		doc = self._page(verifier)

		self.assertEqual(doc.get("title"), "Inspection Review")

	def test_the_page_ships_its_script(self):
		# A standard Page with no script renders an empty shell and looks broken
		# rather than erroring, so the asset loading is worth pinning.
		doc = self._page("Administrator")

		self.assertIn("class InspectionReview", doc.get("script") or "")
		self.assertIn("vehicle_maintenance.api.process.inspections", doc.get("script") or "")
