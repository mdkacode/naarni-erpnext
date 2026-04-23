"""Tests for Vehicle Health Card auto-generation (Milestone 2)."""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.fleet_service.doctype.vehicle_health_card import (
    vehicle_health_card as health_card_module,
)


class TestVehicleHealthCard(FrappeTestCase):

    def setUp(self) -> None:
        self.oem = self._ensure_named("OEM", "NaArNi",
                                      {"doctype": "OEM", "oem_name": "NaArNi"})
        self.customer = self._ensure_named(
            "Customer", "HC Test Customer",
            {"doctype": "Customer", "customer_name": "HC Test Customer",
             "customer_code": "HCCUST", "customer_type": "Individual"},
        )
        self.depot = self._ensure_depot()
        self.vehicle = self._ensure_named(
            "Vehicle", "HC01TEST",
            {"doctype": "Vehicle", "registration_number": "HC01TEST",
             "make_model": "Tata Nexon", "oem": self.oem,
             "customer": self.customer},
        )
        # Reconcile vehicle attrs if the row predates new columns. Both matter:
        # `customer` because Job Card fetches it via fetch_from (NULL would
        # overwrite the JC's own customer); `oem` for the OEM-fetched tests.
        frappe.db.set_value("Vehicle", self.vehicle, {
            "oem": self.oem,
            "customer": self.customer,
        })

    # ── Fixture helpers ───────────────────────────────────────────────

    def _ensure_named(self, doctype: str, name: str, payload: dict) -> str:
        if frappe.db.exists(doctype, name):
            return name
        return frappe.get_doc(payload).insert(ignore_permissions=True).name

    def _ensure_depot(self) -> str:
        existing = frappe.get_all(
            "Depot", filters={"location_code": "HCDEP"}, pluck="name", limit=1,
        )
        if existing:
            return existing[0]
        return frappe.get_doc({
            "doctype": "Depot",
            "depot_name": "HC Test Depot",
            "location_code": "HCDEP",
        }).insert(ignore_permissions=True).name

    def _ensure_part_group(self, name: str, bus_system: str = "Powertrain") -> str:
        if frappe.db.exists("Part Group", name):
            return name
        return frappe.get_doc({
            "doctype": "Part Group",
            "part_group_name": name,
            "bus_system": bus_system,
        }).insert(ignore_permissions=True).name

    def _make_pms_card(self, **kwargs):
        pg = self._ensure_part_group("HC Brakes PG", bus_system="Brakes")
        defaults = {
            "doctype": "Job Card",
            "job_card_type": "PMS + Repair",
            "vehicle": self.vehicle,
            "odometer_reading": 22_000,
            "customer": self.customer,
            "depot": self.depot,
            "priority": "Medium",
            "complaint_description": "Periodic maintenance",
            "workflow_state": "Open",
        }
        defaults.update(kwargs)
        jc = frappe.get_doc(defaults)
        jc.append("repair_items", {
            "part_group": pg,
            "description": "Brake pad inspection",
            "activity_type": "Only Repair",
            "component_status": "Good",
        })
        return jc

    # ── Tests ─────────────────────────────────────────────────────────

    def test_health_card_not_created_before_closure(self) -> None:
        jc = self._make_pms_card()
        jc.insert(ignore_permissions=True)
        self.assertFalse(jc.health_card)
        self.assertFalse(
            frappe.db.exists("Vehicle Health Card", {"job_card": jc.name})
        )

    def test_health_card_generated_on_se_closure(self) -> None:
        jc = self._make_pms_card()
        jc.insert(ignore_permissions=True)

        # Simulate the SE-closure transition.
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        jc.reload()
        self.assertTrue(jc.health_card, "Job Card should link to a Health Card")
        hc = frappe.get_doc("Vehicle Health Card", jc.health_card)
        self.assertEqual(hc.vehicle, self.vehicle)
        self.assertEqual(hc.job_card, jc.name)
        self.assertEqual(hc.odometer_reading, 22_000)
        self.assertTrue(hc.category_scores,
                        "Category scores must be copied to the Health Card")

    def test_health_card_generation_is_idempotent(self) -> None:
        jc = self._make_pms_card()
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)
        first_hc = frappe.db.get_value(
            "Vehicle Health Card", {"job_card": jc.name}, "name"
        )

        # Re-save the Closed card (e.g., closed-state edit by Depot Manager).
        jc.reload()
        jc.closed_edit_reason = "Updated notes"
        jc.save(ignore_permissions=True)

        count = frappe.db.count(
            "Vehicle Health Card", filters={"job_card": jc.name}
        )
        self.assertEqual(count, 1, "Health Card must not duplicate on re-save")
        self.assertEqual(
            frappe.db.get_value(
                "Vehicle Health Card", {"job_card": jc.name}, "name"
            ),
            first_hc,
        )

    def test_health_card_skipped_for_non_pms_types(self) -> None:
        jc = self._make_pms_card(
            job_card_type="Only Repair",
            repair_subtype="Regular",
            complaint_description="Brake pad replacement only",
        )
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        self.assertFalse(
            frappe.db.exists("Vehicle Health Card", {"job_card": jc.name}),
            "Only-Repair cards must not trigger Health Card generation",
        )

    def test_api_returns_none_when_no_card(self) -> None:
        jc = self._make_pms_card()
        jc.insert(ignore_permissions=True)
        result = health_card_module.get_for_job_card(jc.name)
        self.assertTrue(result["success"])
        self.assertIsNone(result["data"])

    def test_api_returns_payload_after_generation(self) -> None:
        jc = self._make_pms_card()
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        result = health_card_module.get_for_job_card(jc.name)
        self.assertTrue(result["success"])
        data = result["data"]
        self.assertIsNotNone(data)
        self.assertEqual(data["vehicle"], self.vehicle)
        self.assertEqual(data["job_card"], jc.name)
        self.assertIn("category_scores", data)
        self.assertIn("overall_pre_score", data)
