"""Tests for the material gate.

Covers the properties the spec promises and nothing decorative: the catalogue
transcription, seeder idempotency, the lifecycle including every illegal
transition, four-eyes verification, idempotent writes, the inline item-create
duplicate guard, the rollups, and permissions.
"""

from __future__ import annotations

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import material
from vehicle_maintenance.material_movement import catalogue
from vehicle_maintenance.material_movement import constants as C

OPERATOR = "gate.operator@test.local"
OPERATOR_2 = "gate.operator2@test.local"
SUPERVISOR = "gate.supervisor@test.local"
VIEWER = "gate.viewer@test.local"
#: Holds both roles. Exists so the four-eyes tests never have to mutate a
#: shared user: FrappeTestCase rolls the database back once per *class*, but
#: `frappe.get_roles` is cached in Redis and the rollback does not reach it —
#: so a role granted mid-test leaks into every later class in the same run.
OPERATOR_SUPERVISOR = "gate.both@test.local"

TEST_LOCATION = "TESTPLANT"


def _make_user(email: str, roles: list[str]) -> str:
	if not frappe.db.exists("User", email):
		user = frappe.get_doc(
			{
				"doctype": "User",
				"email": email,
				"first_name": email.split("@")[0],
				# `User.mobile_no` is mandatory on this site via a custom validation;
				# both flags are needed, and the phone must be unique per user.
				"mobile_no": "9" + str(abs(hash(email)))[:9],
				"send_welcome_email": 0,
			}
		)
		user.flags.ignore_phone_requirement = True
		user.insert(ignore_permissions=True, ignore_mandatory=True)
	user = frappe.get_doc("User", email)
	for role in roles:
		if not any(r.role == role for r in user.roles):
			user.append("roles", {"role": role})
	user.save(ignore_permissions=True)
	return email


class TestMaterialCatalogue(FrappeTestCase):
	"""The transcription itself — no database involved."""

	def test_every_sheet_row_is_transcribed_exactly_once(self):
		catalogue.verify()

	def test_counts_match_the_specification(self):
		self.assertEqual(len(catalogue.ITEMS), 151)
		self.assertEqual(len(catalogue.GROUPS), 11)

	def test_merged_lighting_rows_keep_both_sheet_references(self):
		"""The sheet lists three lamps twice; both line numbers must survive."""
		merged = {row[0]: row[6] for row in catalogue.ITEMS if len(row[6]) > 1}
		self.assertEqual(merged, {"AGG-005": (48, 140), "AGG-006": (49, 139), "AGG-009": (52, 142)})

	def test_gate_prefix_cannot_collide_with_a_catalogue_prefix(self):
		prefixes = {row[0].split("-")[0] for row in catalogue.ITEMS}
		self.assertNotIn(C.GATE_ITEM_PREFIX, prefixes)


class MaterialGateTestCase(FrappeTestCase):
	"""Shared fixtures for everything that touches the database."""

	@classmethod
	def setUpClass(cls):
		super().setUpClass()
		_make_user(OPERATOR, [C.ROLE_OPERATOR])
		_make_user(OPERATOR_2, [C.ROLE_OPERATOR])
		_make_user(SUPERVISOR, [C.ROLE_SUPERVISOR])
		_make_user(VIEWER, [C.ROLE_VIEWER])
		_make_user(OPERATOR_SUPERVISOR, [C.ROLE_OPERATOR, C.ROLE_SUPERVISOR])

		if not frappe.db.exists("Material Location", TEST_LOCATION):
			frappe.get_doc(
				{
					"doctype": "Material Location",
					"location_code": TEST_LOCATION,
					"location_name": "Test Plant",
					"plant_type": "Assembly",
					"is_active": 1,
				}
			).insert(ignore_permissions=True)

		# The seeder owns the catalogue; the tests lean on it rather than making
		# their own items, so a broken seeder fails here too.
		from vehicle_maintenance.patches.v2_6 import seed_material_movement

		seed_material_movement.execute()
		# nosemgrep — FrappeTestCase rolls back once per class, not per test, so a
		# class-level fixture has to be committed or it vanishes mid-suite.
		frappe.db.commit()  # nosemgrep

	def setUp(self):
		frappe.set_user(OPERATOR)
		self.addCleanup(frappe.set_user, "Administrator")

	def _start(self, uuid: str | None = None, movement_type: str = C.INWARD) -> dict:
		return material.start_movement(
			movement_type=movement_type,
			location=TEST_LOCATION,
			client_uuid=uuid or frappe.generate_hash(length=16),
			party_name="Test Supplier",
			party_type="Supplier",
		)["data"]

	def _add_photographed_item(self, movement: str, item: str = "AGG-001", qty: float = 1, **kw) -> str:
		"""Add an item and photograph it — the shape a submittable movement needs."""
		row = self._add_item(movement, item, qty, **kw)["item"]["row_uuid"]
		material.attach_photo(movement=movement, file_url=f"/files/{row}.jpg", item_row=row)
		return row

	def _add_item(self, movement: str, item: str = "AGG-001", qty: float = 1, **kw) -> dict:
		return material.save_item(
			movement=movement,
			row_uuid=kw.pop("row_uuid", frappe.generate_hash(length=16)),
			item=item,
			qty=qty,
			**kw,
		)["data"]


class TestSeeder(MaterialGateTestCase):
	def test_seeds_both_plants(self):
		for code in ("HUBLI", "NARSAPURA"):
			self.assertTrue(frappe.db.exists("Material Location", code), code)

	def test_seeds_every_catalogue_item(self):
		codes = [row[0] for row in catalogue.ITEMS]
		found = frappe.get_all("Part", filters={"name": ["in", codes]}, pluck="name")
		self.assertCountEqual(found, codes)

	def test_is_idempotent_and_does_not_clobber_admin_edits(self):
		"""Re-running must create nothing and must not revert a human's change."""
		frappe.set_user("Administrator")
		frappe.db.set_value("Part", "AGG-001", "stock_uom", "Set")
		frappe.db.set_value("Part", "AGG-001", "spec", "Edited by an admin")
		before = frappe.db.count("Part")

		from vehicle_maintenance.patches.v2_6 import seed_material_movement

		seed_material_movement.execute()

		self.assertEqual(frappe.db.count("Part"), before)
		self.assertEqual(frappe.db.get_value("Part", "AGG-001", "stock_uom"), "Set")
		self.assertEqual(frappe.db.get_value("Part", "AGG-001", "spec"), "Edited by an admin")

	def test_qr_flag_is_seeded_for_traceable_aggregates(self):
		self.assertEqual(frappe.db.get_value("Part", "CHS-005", "has_qr"), 1)
		self.assertEqual(frappe.db.get_value("Part", "FAS-006", "has_qr"), 0)


class TestPhotoRequirement(MaterialGateTestCase):
	"""Every item needs a photograph, or a stated reason it has none."""

	def test_submit_is_refused_while_an_item_has_no_photo(self):
		movement = self._start()["name"]
		self._add_item(movement, "AGG-001", 1)
		with self.assertRaises(frappe.ValidationError):
			material.submit_movement(movement)

	def test_a_photo_satisfies_it(self):
		movement = self._start()["name"]
		row = self._add_item(movement, "AGG-001", 1)["item"]["row_uuid"]
		material.attach_photo(movement=movement, file_url="/files/x.jpg", item_row=row)
		self.assertEqual(material.submit_movement(movement)["data"]["status"], C.STATUS_AWAITING_VERIFICATION)

	def test_a_stated_reason_also_satisfies_it(self):
		"""A sealed crate is a real thing — but it has to be said, not assumed."""
		movement = self._start()["name"]
		self._add_item(movement, "AGG-001", 1, no_photo_reason="Sealed packaging")
		self.assertEqual(material.submit_movement(movement)["data"]["status"], C.STATUS_AWAITING_VERIFICATION)

	def test_the_refusal_names_the_items_so_they_can_be_fixed(self):
		movement = self._start()["name"]
		self._add_item(movement, "AGG-001", 1)
		self._add_item(movement, "AGG-002", 1)
		with self.assertRaises(frappe.ValidationError) as caught:
			material.submit_movement(movement)
		message = str(caught.exception)
		self.assertIn("HVAC Unit", message)

	def test_a_document_photo_does_not_count_as_an_item_photo(self):
		"""A challan photographed is not the pallet photographed."""
		movement = self._start()["name"]
		self._add_item(movement, "AGG-001", 1)
		material.attach_photo(movement=movement, file_url="/files/challan.jpg", kind="Document")
		with self.assertRaises(frappe.ValidationError):
			material.submit_movement(movement)


class TestLifecycle(MaterialGateTestCase):
	def test_happy_path(self):
		movement = self._start()["name"]
		self.assertEqual(frappe.db.get_value("Material Movement", movement, "status"), C.STATUS_DRAFT)

		self._add_photographed_item(movement, "AGG-001", 2)
		self.assertEqual(frappe.db.get_value("Material Movement", movement, "status"), C.STATUS_IN_PROGRESS)

		material.submit_movement(movement)
		self.assertEqual(
			frappe.db.get_value("Material Movement", movement, "status"),
			C.STATUS_AWAITING_VERIFICATION,
		)

		frappe.set_user(SUPERVISOR)
		result = material.verify_movement(movement)
		self.assertEqual(result["data"]["status"], C.STATUS_COMPLETED)
		self.assertEqual(result["data"]["verified_by"], SUPERVISOR)

	def test_cannot_submit_an_empty_movement(self):
		movement = self._start()["name"]
		with self.assertRaises(frappe.ValidationError):
			material.submit_movement(movement)

	def test_a_completed_movement_is_closed_to_writes(self):
		movement = self._start()["name"]
		self._add_photographed_item(movement)
		material.submit_movement(movement)
		frappe.set_user(SUPERVISOR)
		material.verify_movement(movement)

		frappe.set_user(OPERATOR)
		with self.assertRaises(frappe.ValidationError):
			self._add_item(movement, "AGG-002")

	def test_verify_refuses_anything_but_a_submitted_movement(self):
		movement = self._start()["name"]
		self._add_item(movement)
		frappe.set_user(SUPERVISOR)
		with self.assertRaises(frappe.ValidationError):
			material.verify_movement(movement)

	def test_rejection_reopens_and_needs_a_reason(self):
		movement = self._start()["name"]
		self._add_photographed_item(movement)
		material.submit_movement(movement)

		frappe.set_user(SUPERVISOR)
		with self.assertRaises(frappe.ValidationError):
			material.reject_movement(movement, reason="  ")

		result = material.reject_movement(movement, reason="Challan number missing")
		self.assertEqual(result["data"]["status"], C.STATUS_IN_PROGRESS)
		self.assertEqual(result["data"]["rejection_reason"], "Challan number missing")
		# The operator has to be able to fix it, which means submitted_by is cleared.
		self.assertIsNone(result["data"]["submitted_by"])

	def test_cancel_is_terminal_and_needs_a_reason(self):
		movement = self._start()["name"]
		with self.assertRaises(frappe.ValidationError):
			material.cancel_movement(movement, reason="")
		material.cancel_movement(movement, reason="Truck turned back")
		with self.assertRaises(frappe.ValidationError):
			self._add_item(movement)

	def test_an_outward_purpose_is_refused_on_an_inward_note(self):
		with self.assertRaises(frappe.ValidationError):
			material.start_movement(
				movement_type=C.INWARD,
				location=TEST_LOCATION,
				client_uuid=frappe.generate_hash(length=16),
				purpose="Return to Supplier",
			)


class TestFourEyes(MaterialGateTestCase):
	def test_the_recorder_cannot_verify_their_own_movement(self):
		frappe.set_user(OPERATOR_SUPERVISOR)
		movement = self._start()["name"]
		self._add_photographed_item(movement)
		material.submit_movement(movement)

		with self.assertRaises(frappe.ValidationError):
			material.verify_movement(movement)

		# …but somebody else can.
		frappe.set_user(SUPERVISOR)
		self.assertEqual(material.verify_movement(movement)["data"]["status"], C.STATUS_COMPLETED)

	def test_self_verification_is_allowed_when_the_site_opts_in(self):
		frappe.set_user(OPERATOR_SUPERVISOR)
		movement = self._start()["name"]
		self._add_photographed_item(movement)
		material.submit_movement(movement)

		frappe.conf[C.CONF_ALLOW_SELF_VERIFY] = 1
		self.addCleanup(frappe.conf.pop, C.CONF_ALLOW_SELF_VERIFY, None)
		self.assertEqual(material.verify_movement(movement)["data"]["status"], C.STATUS_COMPLETED)


class TestIdempotency(MaterialGateTestCase):
	def test_repeating_start_with_one_uuid_yields_one_movement(self):
		uuid = frappe.generate_hash(length=16)
		first = self._start(uuid)
		second = self._start(uuid)
		self.assertEqual(first["name"], second["name"])
		self.assertEqual(frappe.db.count("Material Movement", {"client_uuid": uuid}), 1)

	def test_repeating_save_item_with_one_row_uuid_yields_one_row(self):
		movement = self._start()["name"]
		row_uuid = frappe.generate_hash(length=16)
		self._add_item(movement, "AGG-001", 1, row_uuid=row_uuid)
		self._add_item(movement, "AGG-001", 5, row_uuid=row_uuid)

		doc = frappe.get_doc("Material Movement", movement)
		self.assertEqual(len(doc.items), 1)
		self.assertEqual(doc.items[0].qty, 5)

	def test_repeating_attach_photo_with_one_uuid_yields_one_photo(self):
		movement = self._start()["name"]
		row = self._add_item(movement)["item"]
		uuid = frappe.generate_hash(length=16)
		for _ in range(2):
			material.attach_photo(
				movement=movement,
				file_url="/files/gate-test.jpg",
				item_row=row["row_uuid"],
				client_uuid=uuid,
			)
		self.assertEqual(frappe.db.get_value("Material Movement", movement, "photo_count"), 1)


class TestInlineItemCreation(MaterialGateTestCase):
	def test_a_new_name_creates_one_reviewable_part(self):
		result = material.create_item(
			item_name="Gate Test Widget Alpha", item_group="Aggregates & Fitments", uom="Nos"
		)["data"]
		self.assertEqual(result["created"], 1)
		self.assertTrue(result["value"].startswith(C.GATE_ITEM_PREFIX))
		self.assertEqual(frappe.db.get_value("Part", result["value"], "is_gate_created"), 1)

	def test_case_and_punctuation_variants_return_the_existing_item(self):
		material.create_item(item_name="Gate Test Widget Beta", uom="Nos")
		for variant in ("gate test widget beta", "GateTestWidgetBeta", "Gate-Test  Widget/Beta"):
			result = material.create_item(item_name=variant)["data"]
			self.assertEqual(result["created"], 0, variant)
			self.assertEqual(result["label"], "Gate Test Widget Beta", variant)

	def test_an_existing_catalogue_item_is_never_duplicated(self):
		result = material.create_item(item_name="hvac unit")["data"]
		self.assertEqual(result["created"], 0)
		self.assertEqual(result["value"], "AGG-001")

	def test_a_name_too_short_to_find_later_is_refused(self):
		with self.assertRaises(frappe.ValidationError):
			material.create_item(item_name="ab")

	def test_generated_codes_do_not_collide_after_a_deletion(self):
		frappe.set_user("Administrator")
		first = material.create_item(item_name="Gate Test Widget Gamma")["data"]["value"]
		second = material.create_item(item_name="Gate Test Widget Delta")["data"]["value"]
		frappe.delete_doc("Part", second, force=True, ignore_permissions=True)
		third = material.create_item(item_name="Gate Test Widget Epsilon")["data"]["value"]
		self.assertNotIn(third, {first, second})


class TestRollups(MaterialGateTestCase):
	def test_counts_track_writes_and_deletions(self):
		movement = self._start()["name"]
		row_a = self._add_item(movement, "AGG-001", 2)["item"]["row_uuid"]
		row_b = self._add_item(movement, "CHS-005", 12, qr_code="PACK-0001", qr_source=C.QR_SCANNED)["item"]
		row_b = row_b["row_uuid"]
		self._add_item(movement, "FAS-006", 100, condition="Damaged")

		material.attach_photo(movement=movement, file_url="/files/a.jpg", item_row=row_a)

		doc = frappe.get_doc("Material Movement", movement)
		self.assertEqual(doc.total_items, 3)
		self.assertEqual(doc.total_qty, 114)
		self.assertEqual(doc.photo_count, 1)
		self.assertEqual(doc.qr_count, 1)
		self.assertEqual(doc.damaged_count, 1)
		self.assertAlmostEqual(doc.evidence_pct, 33.33, places=1)

		# Deleting a row takes its photos with it, or photo_count outgrows the
		# movement and evidence_pct becomes a number nobody can trust.
		material.delete_item(movement, row_a)
		doc.reload()
		self.assertEqual(doc.total_items, 2)
		self.assertEqual(doc.photo_count, 0)
		self.assertEqual(doc.evidence_pct, 0)

	def test_expected_qty_is_prefilled_from_the_sheet(self):
		movement = self._start()["name"]
		row = self._add_item(movement, "CHS-005", 4)["item"]
		self.assertEqual(row["expected_qty"], 12)

	def test_a_quantity_of_zero_is_refused(self):
		movement = self._start()["name"]
		with self.assertRaises(frappe.ValidationError):
			self._add_item(movement, "AGG-001", 0)


class TestWarnings(MaterialGateTestCase):
	def test_a_duplicate_serial_warns_but_does_not_block(self):
		first = self._start()["name"]
		self._add_item(first, "CHS-005", 1, qr_code="DUP-SERIAL-1", qr_source=C.QR_SCANNED)

		second = self._start()["name"]
		result = self._add_item(second, "CHS-005", 1, qr_code="DUP-SERIAL-1", qr_source=C.QR_SCANNED)

		codes = {w["code"] for w in result["warnings"]}
		self.assertIn("duplicate_serial", codes)
		self.assertEqual(result["item"]["qr_code"], "DUP-SERIAL-1")

	def test_a_qr_item_with_no_serial_warns(self):
		movement = self._start()["name"]
		result = self._add_item(movement, "CHS-005", 1)
		self.assertIn("missing_serial", {w["code"] for w in result["warnings"]})

	def test_a_quantity_differing_from_the_sheet_warns(self):
		movement = self._start()["name"]
		result = self._add_item(movement, "CHS-005", 11)
		self.assertIn("qty_differs", {w["code"] for w in result["warnings"]})

	def test_an_unparseable_serial_is_stored_verbatim(self):
		movement = self._start()["name"]
		junk = "???not-a-pattern???"
		result = self._add_item(movement, "CHS-005", 1, qr_code=junk, qr_source=C.QR_SCANNED)
		self.assertEqual(result["item"]["qr_code"], junk)

	def test_where_used_finds_every_movement_a_serial_passed_through(self):
		movement = self._start()["name"]
		self._add_item(movement, "CHS-005", 1, qr_code="TRACE-9", qr_source=C.QR_SCANNED)
		found = material.where_used("TRACE-9")["data"]["movements"]
		self.assertIn(movement, [m["movement"] for m in found])


class TestPermissions(MaterialGateTestCase):
	def test_an_operator_cannot_verify(self):
		movement = self._start()["name"]
		self._add_photographed_item(movement)
		material.submit_movement(movement)
		with self.assertRaises(frappe.PermissionError):
			material.verify_movement(movement)

	def test_a_viewer_cannot_write(self):
		frappe.set_user(VIEWER)
		with self.assertRaises(frappe.PermissionError):
			material.start_movement(
				movement_type=C.INWARD, location=TEST_LOCATION, client_uuid=frappe.generate_hash(length=16)
			)

	def test_an_operator_cannot_touch_another_operators_movement(self):
		movement = self._start()["name"]
		frappe.set_user(OPERATOR_2)
		with self.assertRaises(frappe.PermissionError):
			self._add_item(movement)

	def test_a_supervisor_sees_every_operators_movement(self):
		movement = self._start()["name"]
		self._add_item(movement)
		frappe.set_user(SUPERVISOR)
		self.assertEqual(material.get_movement(movement)["data"]["name"], movement)

	def test_the_item_picker_opens_populated_before_a_keystroke(self):
		results = material.search_items()["data"]
		self.assertGreater(len(results), 0)

	def test_a_typed_search_is_not_padded_with_unrelated_items(self):
		"""A search returns matches only — never matches topped up from the catalogue.

		Padding a blank query is the point: the sheet opens usable before a
		keystroke. Padding a *typed* one buries the answer, which is what made
		"8.7" come back as two windshields followed by thirty-eight unrelated
		items.
		"""
		hits = material.search_items(txt="8.7")["data"]
		self.assertEqual({r["value"] for r in hits}, {"EXT-012", "EXT-013"})

		# …while a blank query still opens populated.
		self.assertGreater(len(material.search_items()["data"]), len(hits))

	def test_a_search_that_matches_nothing_returns_nothing(self):
		self.assertEqual(material.search_items(txt="zzz-no-such-item-zzz")["data"], [])

	def test_the_item_picker_searches_code_name_and_spec_together(self):
		by_name = {r["value"] for r in material.search_items(txt="Windshield")["data"]}
		self.assertIn("EXT-012", by_name)
		by_spec = {r["value"] for r in material.search_items(txt="8.7")["data"]}
		self.assertIn("EXT-012", by_spec)
		by_code = {r["value"] for r in material.search_items(txt="CHS-005")["data"]}
		self.assertIn("CHS-005", by_code)


class TestGateContext(MaterialGateTestCase):
	def test_one_call_returns_everything_the_new_movement_screen_needs(self):
		data = material.get_gate_context()["data"]
		for key in (
			"locations",
			"default_location",
			"movement_types",
			"purposes",
			"party_types",
			"reference_types",
			"conditions",
			"uoms",
			"item_groups",
			"can_verify",
		):
			self.assertIn(key, data, key)
		self.assertEqual(set(data["purposes"]), {C.INWARD, C.OUTWARD})
		self.assertFalse(data["can_verify"])

	def test_a_supervisor_is_told_they_can_verify(self):
		frappe.set_user(SUPERVISOR)
		self.assertTrue(material.get_gate_context()["data"]["can_verify"])
