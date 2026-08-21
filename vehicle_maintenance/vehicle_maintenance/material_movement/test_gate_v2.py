"""The Material Gate as the operator meets it: six questions, then a check.

These drive `api.process` the way the handset does — start, answer, photograph,
submit — rather than building documents directly, because every defect this
process has had so far lived in the API layer and not in the model. The last one
was a definition that was seeded, deployed and never published: perfectly valid
in the database, invisible to every phone.
"""

from __future__ import annotations

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import process as api
from vehicle_maintenance.material_movement import constants as MC
from vehicle_maintenance.patches.v2_8 import rebuild_material_gate as gate

#: The order the gate asks in. Asserted as a whole rather than step by step: the
#: complaint that produced v2 was about the sequence, not about any one question.
EXPECTED_STEPS = ["DIRECTION", "DOC_NO", "CHASSIS", "ITEM", "PHOTO", "WEIGHT", "VERIFY"]

OPERATOR = "gate.runner@test.local"


def _tiny_png() -> str:
	"""A real one-pixel PNG. Frappe parses uploads, so invented bytes are rejected."""
	import base64

	data = base64.b64decode(
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
	)
	file = frappe.get_doc(
		{
			"doctype": "File",
			"file_name": f"gate-{frappe.generate_hash(length=8)}.png",
			"is_private": 1,
			"content": data,
			"decode": False,
		}
	).insert(ignore_permissions=True)
	return file.file_url


class GateV2TestCase(FrappeTestCase):
	@classmethod
	def setUpClass(cls):
		super().setUpClass()
		from vehicle_maintenance.patches.v2_6 import seed_material_movement
		from vehicle_maintenance.patches.v2_7 import seed_material_gate_process

		seed_material_movement.execute()
		seed_material_gate_process.execute()
		gate.execute()

		if not frappe.db.exists("User", OPERATOR):
			user = frappe.get_doc(
				{
					"doctype": "User",
					"email": OPERATOR,
					"first_name": "Gate Runner",
					"mobile_no": "9" + str(abs(hash(OPERATOR)))[:9],
					"send_welcome_email": 0,
				}
			)
			user.flags.ignore_phone_requirement = True
			user.insert(ignore_permissions=True, ignore_mandatory=True)
		user = frappe.get_doc("User", OPERATOR)
		for role in ("Material Gate Operator", "Process Operator"):
			if not any(r.role == role for r in user.roles):
				user.append("roles", {"role": role})
		user.save(ignore_permissions=True)

		# nosemgrep — FrappeTestCase rolls back once per class, not per test, so a
		# class-level fixture has to be committed or it vanishes mid-suite.
		frappe.db.commit()  # nosemgrep

	def setUp(self):
		frappe.set_user(OPERATOR)
		self.addCleanup(frappe.set_user, "Administrator")

	# ------------------------------------------------------------------ helpers

	def _start(self) -> str:
		run = api.start_run(
			process="MATERIAL_GATE",
			identifier=None,
			client_uuid=frappe.generate_hash(length=16),
		)["data"]
		return run["name"]

	def _answer_everything(self, run: str, chassis: str = "KA25AB1234") -> str:
		part = frappe.get_all("Part", filters={"part_group": "Chassis & Driveline"}, limit=1, pluck="name")[0]
		api.save_step_result(run=run, step_code="DIRECTION", response=MC.INWARD.upper())
		api.save_step_result(run=run, step_code="DOC_NO", response="DC-4471")
		api.save_step_result(run=run, step_code="CHASSIS", value=chassis)
		api.save_step_result(run=run, step_code="ITEM", response=part)
		api.attach_photo(run=run, step_code="PHOTO", file_url=_tiny_png(), caption="Part")
		api.save_step_result(run=run, step_code="WEIGHT", value="412.5")
		api.save_step_result(run=run, step_code="VERIFY", response="CONFIRMED")
		return part


class TestTheAuthoredProcess(GateV2TestCase):
	def test_the_live_version_is_v2_and_is_published(self):
		"""v1 was seeded as a Draft and never published, so no phone could see it."""
		live = frappe.get_all(
			"Process Definition",
			filters={"family": "MATERIAL_GATE", "status": "Published"},
			fields=["name", "version"],
		)
		self.assertEqual([(d.name, d.version) for d in live], [("MATERIAL_GATE-v2", 2)])

	def test_v1_is_retired_rather_than_deleted(self):
		"""Runs recorded against the old shape still need a definition to read."""
		self.assertEqual(frappe.db.get_value("Process Definition", "MATERIAL_GATE-v1", "status"), "Retired")

	def test_the_questions_come_in_the_order_of_the_work(self):
		steps = api.get_definition("MATERIAL_GATE", app_capability=3)["data"]["steps"]
		self.assertEqual([s["step_code"] for s in steps], EXPECTED_STEPS)

	def test_the_run_names_itself(self):
		"""A clerk has no number to type before the truck is open."""
		run = frappe.get_doc("Process Run", self._start())
		self.assertTrue((run.run_identifier or "").startswith("GN-"), run.run_identifier)

	def test_seeding_twice_authors_one_version(self):
		gate.execute()
		self.assertEqual(
			frappe.db.count("Process Definition", {"family": "MATERIAL_GATE"}),
			2,
		)

	def test_an_older_app_is_told_the_review_screen_is_beyond_it(self):
		"""Rather than crashing on a step type it has never heard of."""
		steps = api.get_definition("MATERIAL_GATE", app_capability=2)["data"]["steps"]
		unsupported = [s["step_code"] for s in steps if not s["supported"]]
		self.assertEqual(unsupported, ["VERIFY"])


class TestTheInterlocks(GateV2TestCase):
	def test_the_part_photo_cannot_be_skipped(self):
		"""A gate note whose part was never photographed is a list of claims."""
		run = self._start()
		part = frappe.get_all("Part", limit=1, pluck="name")[0]
		api.save_step_result(run=run, step_code="DIRECTION", response="INWARD")
		api.save_step_result(run=run, step_code="CHASSIS", value="KA01AA0001")
		api.save_step_result(run=run, step_code="ITEM", response=part)
		api.save_step_result(run=run, step_code="VERIFY", response="CONFIRMED")
		with self.assertRaises(frappe.ValidationError) as caught:
			api.submit_stage(run=run, stage="GATE")
		# Reported by display number, which is what the operator sees on screen.
		self.assertIn("5", str(caught.exception))

	def test_the_review_screen_cannot_be_skipped(self):
		run = self._start()
		part = frappe.get_all("Part", limit=1, pluck="name")[0]
		api.save_step_result(run=run, step_code="DIRECTION", response="INWARD")
		api.save_step_result(run=run, step_code="CHASSIS", value="KA01AA0001")
		api.save_step_result(run=run, step_code="ITEM", response=part)
		api.attach_photo(run=run, step_code="PHOTO", file_url=_tiny_png())
		with self.assertRaises(frappe.ValidationError) as caught:
			api.submit_stage(run=run, stage="GATE")
		self.assertIn("7", str(caught.exception))

	def test_the_paperwork_and_the_weight_are_optional(self):
		"""A truck is not always accompanied by its documents or a weighbridge."""
		run = self._start()
		part = frappe.get_all("Part", limit=1, pluck="name")[0]
		api.save_step_result(run=run, step_code="DIRECTION", response="INWARD")
		api.save_step_result(run=run, step_code="CHASSIS", value="KA01AA0001")
		api.save_step_result(run=run, step_code="ITEM", response=part)
		api.attach_photo(run=run, step_code="PHOTO", file_url=_tiny_png())
		api.save_step_result(run=run, step_code="VERIFY", response="CONFIRMED")
		self.assertTrue(api.submit_stage(run=run, stage="GATE")["success"])

	def test_a_chassis_typed_by_hand_counts_as_answered(self):
		"""Labels come off crates greasy, torn, or too small for any camera.

		The step's own help text tells the operator to type it. Judging only the
		camera made that answer read as unanswered, so the submit gate demanded a
		step that was filled in on screen.
		"""
		run = self._start()
		row = api.save_step_result(run=run, step_code="CHASSIS", value="KA25AB1234")["data"]["result"]
		self.assertEqual(row["value_text"], "KA25AB1234")
		self.assertEqual(row["response"], "KA25AB1234")

		# And it satisfies the step: nothing typed used to reach the submit gate.
		part = frappe.get_all("Part", limit=1, pluck="name")[0]
		api.save_step_result(run=run, step_code="DIRECTION", response="INWARD")
		api.save_step_result(run=run, step_code="ITEM", response=part)
		api.attach_photo(run=run, step_code="PHOTO", file_url=_tiny_png())
		api.save_step_result(run=run, step_code="VERIFY", response="CONFIRMED")
		self.assertTrue(api.submit_stage(run=run, stage="GATE")["success"])

	def test_the_chassis_question_is_asked_but_can_be_skipped_with_a_reason(self):
		"""Parts do go to general stock; a blank nobody can interpret is worse."""
		run = self._start()
		part = frappe.get_all("Part", limit=1, pluck="name")[0]
		api.save_step_result(run=run, step_code="DIRECTION", response="INWARD")
		api.save_step_result(run=run, step_code="CHASSIS", skipped=1, skip_reason="Going to general stock")
		api.save_step_result(run=run, step_code="ITEM", response=part)
		api.attach_photo(run=run, step_code="PHOTO", file_url=_tiny_png())
		api.save_step_result(run=run, step_code="VERIFY", response="CONFIRMED")
		self.assertTrue(api.submit_stage(run=run, stage="GATE")["success"])


class TestTheRegisterItProduces(GateV2TestCase):
	def _finish(self, chassis: str = "KA25AB1234"):
		run = self._start()
		part = self._answer_everything(run, chassis=chassis)
		api.submit_stage(run=run, stage="GATE")
		frappe.db.commit()  # nosemgrep — the projection runs on the run's own save
		name = frappe.db.get_value("Material Movement", {"client_uuid": f"run:{run}"})
		return run, part, frappe.get_doc("Material Movement", name) if name else None

	def test_a_finished_run_becomes_one_register_line(self):
		_, part, movement = self._finish()
		self.assertIsNotNone(movement)
		self.assertEqual(len(movement.items), 1)
		self.assertEqual(movement.items[0].item, part)
		self.assertEqual(movement.movement_type, MC.INWARD)

	def test_the_document_number_reaches_the_header(self):
		_, _, movement = self._finish()
		self.assertEqual(movement.reference_no, "DC-4471")

	def test_the_chassis_reaches_the_line(self):
		"""This is what makes “which parts went into this bus” answerable."""
		_, _, movement = self._finish(chassis="KA53XY7788")
		self.assertEqual(movement.items[0].chassis_no, "KA53XY7788")

	def test_the_challan_photo_is_filed_as_paperwork_not_as_the_part(self):
		run = self._start()
		self._answer_everything(run)
		api.attach_photo(run=run, step_code="DOC_NO", file_url=_tiny_png(), caption="Challan")
		api.submit_stage(run=run, stage="GATE")
		frappe.db.commit()  # nosemgrep — the projection runs on the run's own save
		movement = frappe.get_doc(
			"Material Movement", frappe.db.get_value("Material Movement", {"client_uuid": f"run:{run}"})
		)
		kinds = sorted((p.kind, bool(p.item_row)) for p in movement.photos)
		self.assertEqual(kinds, [("Document", False), ("Item", True)])

	def test_one_run_produces_one_movement_however_often_it_syncs(self):
		run, _, movement = self._finish()
		# A replayed offline sync saves the run again.
		frappe.get_doc("Process Run", run).save(ignore_permissions=True)
		frappe.db.commit()  # nosemgrep — as above
		self.assertEqual(frappe.db.count("Material Movement", {"client_uuid": f"run:{run}"}), 1)


class TestTheOperatorsList(GateV2TestCase):
	def test_a_finished_entry_stays_on_the_operators_list(self):
		"""It used to vanish: submitted, and gone from every chip they can see."""
		from vehicle_maintenance.api import material

		run = self._start()
		part = self._answer_everything(run)
		api.submit_stage(run=run, stage="GATE")
		frappe.db.commit()  # nosemgrep — the projection runs on the run's own save

		name = frappe.db.get_value("Material Movement", {"client_uuid": f"run:{run}"})
		self.assertIsNotNone(name)
		self.assertEqual(
			frappe.db.get_value("Material Movement", name, "status"), MC.STATUS_AWAITING_VERIFICATION
		)

		# The projection flags anything made under test so the plant's register
		# does not fill up with them; cleared here because the list query filters
		# on exactly that flag, and the thing being tested is the list query.
		frappe.db.set_value("Material Movement", name, "is_test", 0)
		rows = material.my_movements(scope="open")["data"]["movements"]
		self.addCleanup(frappe.db.set_value, "Material Movement", name, "is_test", 1)
		self.assertIn(name, {m["name"] for m in rows})

		# And it is named by what crossed the gate. The row used to lead with the
		# party, which this flow never asks for — so every entry read "Unnamed
		# party" and the register told you nothing without opening each line.
		row = next(m for m in rows if m["name"] == name)
		self.assertEqual(row["headline"], frappe.db.get_value("Part", part, "part_name"))
		self.assertEqual(row["chassis_no"], "KA25AB1234")
		self.assertEqual(row["more_items"], 0)
