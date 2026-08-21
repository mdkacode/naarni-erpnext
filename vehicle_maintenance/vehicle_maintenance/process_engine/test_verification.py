"""The verification queue: who sees which packs, and what signing off does.

The queue is the only part of the engine where being *too generous* is the
dangerous failure. An empty list is a nuisance; a list containing another
plant's packs is an engineer signing off work they have never been near. So
most of what follows is about what the queue does *not* show.
"""

from __future__ import annotations

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import process as api
from vehicle_maintenance.patches.v2_9.seed_battery_verification import ROLE, USER_PLANT_FIELD
from vehicle_maintenance.process_engine import constants as C

PROCESS = "TEST-VERIFY-PROC"
STAGE = "BUILD"

NARSAPURA = "NARSAPURA"
HUBLI = "HUBLI"

OPERATOR = "verify.operator@test.local"
ENGINEER = "verify.engineer@test.local"
OTHER_PLANT = "verify.hubli@test.local"
NO_PLANT = "verify.nowhere@test.local"
BYSTANDER = "verify.bystander@test.local"


def _user(email: str, roles: list[str], plant: str | None) -> str:
	if not frappe.db.exists("User", email):
		doc = frappe.get_doc(
			{
				"doctype": "User",
				"email": email,
				"first_name": email.split("@")[0],
				"mobile_no": "9" + str(abs(hash(email)))[:9],
				"send_welcome_email": 0,
			}
		)
		doc.flags.ignore_phone_requirement = True
		doc.insert(ignore_permissions=True, ignore_mandatory=True)
	doc = frappe.get_doc("User", email)
	for role in roles:
		if not any(r.role == role for r in doc.roles):
			doc.append("roles", {"role": role})
	doc.set(USER_PLANT_FIELD, plant)
	doc.save(ignore_permissions=True)
	return email


class VerificationTestCase(FrappeTestCase):
	@classmethod
	def setUpClass(cls):
		super().setUpClass()
		from vehicle_maintenance.patches.v2_6 import seed_material_movement
		from vehicle_maintenance.patches.v2_9 import seed_battery_verification

		# The plants come from the material seeder — they are the same masters.
		seed_material_movement.execute()
		seed_battery_verification.execute()

		_user(OPERATOR, [C.ROLE_OPERATOR], NARSAPURA)
		_user(ENGINEER, [ROLE, C.ROLE_OPERATOR], NARSAPURA)
		_user(OTHER_PLANT, [ROLE, C.ROLE_OPERATOR], HUBLI)
		_user(NO_PLANT, [ROLE, C.ROLE_OPERATOR], None)
		_user(BYSTANDER, [C.ROLE_OPERATOR], NARSAPURA)

		if not frappe.db.exists("Process Definition", PROCESS):
			frappe.get_doc(
				{
					"doctype": "Process Definition",
					"process_code": PROCESS,
					"process_name": "Verification Test Process",
					"family": "TEST_VERIFY",
					"version": 1,
					"status": C.DEF_DRAFT,
					"subject_label": "Pack",
					"identifier_mode": "Auto Generate",
					"identifier_pattern": "VT-.#####",
					"scoring_enabled": 0,
					"allow_offline": 1,
					"allowed_roles": [{"role": C.ROLE_OPERATOR}],
					"stages": [
						{
							"stage_code": STAGE,
							"label": "Build",
							"sequence": 10,
							"screen_grouping": "One Per Screen",
							# The whole point of the fixture.
							"requires_second_signoff": 1,
							"second_signoff_role": ROLE,
						}
					],
					"steps": [
						{
							"step_code": "Q1",
							"stage": STAGE,
							"display_no": "1",
							"sequence": 10,
							"label": "Did it build?",
							"response_type": C.TEXT_SHORT,
							"is_mandatory": 1,
							"is_active": 1,
							"weight": 1,
						}
					],
				}
			).insert(ignore_permissions=True).publish()

		# nosemgrep — FrappeTestCase rolls back once per class, not per test, so a
		# class-level fixture has to be committed or it vanishes mid-suite.
		frappe.db.commit()  # nosemgrep

	def setUp(self):
		self.addCleanup(frappe.set_user, "Administrator")

	# ------------------------------------------------------------------ helpers

	def _awaiting_run(self, operator: str = OPERATOR) -> str:
		"""One run, answered and submitted, sitting in the verification queue."""
		frappe.set_user(operator)
		run = api.start_run(process=PROCESS, client_uuid=frappe.generate_hash(length=16))["data"]["name"]
		api.save_step_result(run=run, step_code="Q1", response="yes")
		api.submit_stage(run=run, stage=STAGE)
		frappe.db.commit()  # nosemgrep — submit_stage commits; this matches it
		self.addCleanup(self._drop, run)
		return run

	def _drop(self, run: str) -> None:
		frappe.set_user("Administrator")
		if frappe.db.exists("Process Run", run):
			frappe.delete_doc("Process Run", run, force=True, ignore_permissions=True)
			frappe.db.commit()  # nosemgrep — undoing a committed fixture

	def _queue(self, user: str) -> dict:
		frappe.set_user(user)
		return api.verification_queue()["data"]


class TestThePlantIsRecorded(VerificationTestCase):
	def test_a_run_is_stamped_with_the_operators_plant(self):
		"""Nobody is asked. An operator knows where they are standing."""
		run = self._awaiting_run()
		self.assertEqual(frappe.db.get_value("Process Run", run, "plant"), NARSAPURA)

	def test_an_explicit_plant_wins_over_the_profile(self):
		frappe.set_user(OPERATOR)
		run = api.start_run(process=PROCESS, client_uuid=frappe.generate_hash(length=16), plant=HUBLI)[
			"data"
		]["name"]
		self.addCleanup(self._drop, run)
		self.assertEqual(frappe.db.get_value("Process Run", run, "plant"), HUBLI)


class TestWhoSeesWhat(VerificationTestCase):
	def test_the_engineer_at_that_plant_sees_the_pack(self):
		run = self._awaiting_run()
		queue = self._queue(ENGINEER)
		self.assertIn(run, {r["name"] for r in queue["runs"]})
		self.assertEqual(queue["plant"], NARSAPURA)

	def test_the_queue_says_which_stage_is_waiting(self):
		"""It is what the verify call needs next, so the list has to carry it."""
		run = self._awaiting_run()
		row = next(r for r in self._queue(ENGINEER)["runs"] if r["name"] == run)
		self.assertEqual(row["stage"], STAGE)
		self.assertEqual(row["stage_label"], "Build")

	def test_an_engineer_at_another_plant_does_not(self):
		"""The failure that matters: signing off work you have never been near."""
		run = self._awaiting_run()
		self.assertNotIn(run, {r["name"] for r in self._queue(OTHER_PLANT)["runs"]})

	def test_an_engineer_with_no_plant_sees_nothing_and_is_told_why(self):
		"""Fails closed. An unset profile must not mean every site's packs."""
		self._awaiting_run()
		queue = self._queue(NO_PLANT)
		self.assertEqual(queue["runs"], [])
		self.assertIsNone(queue["plant"])

	def test_somebody_without_the_role_sees_an_empty_queue_with_a_reason(self):
		"""'Nothing to do' and 'you cannot do anything' look alike and are not."""
		self._awaiting_run()
		queue = self._queue(BYSTANDER)
		self.assertEqual(queue["runs"], [])
		self.assertEqual(queue["reason"], "no_role")

	def test_a_run_still_being_worked_on_is_not_in_the_queue(self):
		frappe.set_user(OPERATOR)
		run = api.start_run(process=PROCESS, client_uuid=frappe.generate_hash(length=16))["data"]["name"]
		self.addCleanup(self._drop, run)
		frappe.db.commit()  # nosemgrep — the queue reads committed rows
		self.assertNotIn(run, {r["name"] for r in self._queue(ENGINEER)["runs"]})


class TestSigningOff(VerificationTestCase):
	def test_approving_records_who_and_finishes_the_run(self):
		run = self._awaiting_run()
		frappe.set_user(ENGINEER)
		api.verify_stage(run=run, stage=STAGE, decision="Approved", remarks="Checked the busbars")

		doc = frappe.get_doc("Process Run", run)
		signoff = next(s for s in doc.signoffs if s.level != "Operator")
		self.assertEqual(signoff.decision, "Approved")
		self.assertEqual(signoff.user, ENGINEER)
		self.assertEqual(signoff.role, ROLE)
		self.assertNotEqual(doc.status, C.STATUS_AWAITING_VERIFICATION)

	def test_an_approved_pack_leaves_the_queue(self):
		run = self._awaiting_run()
		frappe.set_user(ENGINEER)
		api.verify_stage(run=run, stage=STAGE, decision="Approved")
		frappe.db.commit()  # nosemgrep — as above
		self.assertNotIn(run, {r["name"] for r in self._queue(ENGINEER)["runs"]})

	def test_sending_back_records_the_reason(self):
		run = self._awaiting_run()
		frappe.set_user(ENGINEER)
		api.verify_stage(run=run, stage=STAGE, decision="Rejected", remarks="Torque mark missing on B4")

		doc = frappe.get_doc("Process Run", run)
		signoff = next(s for s in doc.signoffs if s.level != "Operator")
		self.assertEqual(signoff.decision, "Rejected")
		self.assertIn("B4", signoff.remarks or "")

	def test_somebody_without_the_role_cannot_sign_off(self):
		run = self._awaiting_run()
		frappe.set_user(BYSTANDER)
		with self.assertRaises(frappe.PermissionError):
			api.verify_stage(run=run, stage=STAGE, decision="Approved")

	def test_the_operator_cannot_sign_off_their_own_work(self):
		"""The entire point of a second signoff.

		Run as the *engineer* deliberately. Asserting this as the plain operator
		would pass without the rule existing at all — they lack the role, so the
		check above refuses them first and the four-eyes rule is never reached.
		The engineer holds the role and carried the work out, which is the only
		combination that tests the thing this is named after.
		"""
		run = self._awaiting_run(operator=ENGINEER)
		frappe.set_user(ENGINEER)
		with self.assertRaises(frappe.PermissionError) as caught:
			api.verify_stage(run=run, stage=STAGE, decision="Approved")
		self.assertIn("somebody other than", str(caught.exception))

	def test_a_colleague_with_the_role_can_sign_off_that_same_work(self):
		"""The rule is about who did it, not about the run being untouchable."""
		run = self._awaiting_run(operator=ENGINEER)
		_user(f"second.{ENGINEER}", [ROLE], NARSAPURA)
		frappe.set_user(f"second.{ENGINEER}")
		self.assertTrue(api.verify_stage(run=run, stage=STAGE, decision="Approved")["success"])


class TestTheBatteryLineItself(VerificationTestCase):
	def test_the_installation_stage_names_the_new_role(self):
		"""Configured since it was published, with nobody able to answer it."""
		live = frappe.db.get_value(
			"Process Definition", {"family": "BATTERY_QC", "status": C.DEF_PUBLISHED}, "name"
		)
		if not live:
			self.skipTest("Battery QC is not published on this site")
		row = frappe.db.get_value(
			"Process Stage",
			{"parent": live, "stage_code": "INSTALLATION"},
			["requires_second_signoff", "second_signoff_role"],
			as_dict=True,
		)
		self.assertTrue(row.requires_second_signoff)
		self.assertEqual(row.second_signoff_role, ROLE)
