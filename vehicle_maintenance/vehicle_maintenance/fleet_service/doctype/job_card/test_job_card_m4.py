"""Milestone 4 tests — type-specific flows.

Covers:
  • Breakdown auto-fills Last PMS fields from prior closed PMS card
  • Non-Breakdown types do not autofill
  • RCA timestamp stamps on first population of rca_notes
  • `save_subsystems` API roundtrip
"""

import frappe
from frappe.tests.utils import FrappeTestCase


class TestJobCardM4(FrappeTestCase):

    def setUp(self) -> None:
        self.oem = self._ensure_named(
            "OEM", "NaArNi", {"doctype": "OEM", "oem_name": "NaArNi"},
        )
        self.customer = self._ensure_named(
            "Customer", "M4 Test Customer",
            {"doctype": "Customer", "customer_name": "M4 Test Customer",
             "customer_code": "M4CUST", "customer_type": "Individual"},
        )
        self.depot = self._ensure_depot()
        self.vehicle = self._ensure_named(
            "Vehicle", "M401TEST",
            {"doctype": "Vehicle", "registration_number": "M401TEST",
             "make_model": "Tata Nexon", "oem": self.oem,
             "customer": self.customer},
        )
        frappe.db.set_value("Vehicle", self.vehicle, {
            "oem": self.oem,
            "customer": self.customer,
        })

    def _ensure_named(self, doctype, name, payload):
        if frappe.db.exists(doctype, name):
            return name
        return frappe.get_doc(payload).insert(ignore_permissions=True).name

    def _ensure_depot(self):
        existing = frappe.get_all(
            "Depot", filters={"location_code": "M4DEP"}, pluck="name", limit=1,
        )
        if existing:
            return existing[0]
        return frappe.get_doc({
            "doctype": "Depot", "depot_name": "M4 Test Depot",
            "location_code": "M4DEP",
        }).insert(ignore_permissions=True).name

    def _ensure_part_group(self):
        name = "M4 Brakes PG"
        if frappe.db.exists("Part Group", name):
            return name
        return frappe.get_doc({
            "doctype": "Part Group", "part_group_name": name,
            "bus_system": "Brakes",
        }).insert(ignore_permissions=True).name

    def _make_card(self, **kwargs):
        defaults = {
            "doctype": "Job Card",
            "job_card_type": "Breakdown",
            "vehicle": self.vehicle,
            "odometer_reading": 75_000,
            "customer": self.customer,
            "depot": self.depot,
            "priority": "Urgent",
            "complaint_description": "M4 breakdown test",
            "workflow_state": "Open",
        }
        defaults.update(kwargs)
        return frappe.get_doc(defaults)

    # ── Breakdown autofill ──

    def test_breakdown_autofills_last_pms_when_prior_exists(self):
        # Seed a prior closed PMS + Repair card
        prior = self._make_card(
            job_card_type="PMS + Repair",
            odometer_reading=50_000,
            priority="Medium",
            pms_tolerance_level="Tier-1",
            complaint_description="Prior PMS for M4",
        )
        prior.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "Rotor check",
            "activity_type": "Only Repair",
            "component_status": "Good",
        })
        prior.insert(ignore_permissions=True)
        prior.workflow_state = "Closed"
        prior.save(ignore_permissions=True)

        # Now a new Breakdown should autofill from the prior
        bd = self._make_card()
        bd.insert(ignore_permissions=True)
        bd.reload()

        self.assertEqual(bd.last_pms_odometer, 50_000)
        self.assertEqual(bd.last_service_tolerance_level, "Tier-1")
        # last_pms_date lands as a Datetime string; just check it's populated.
        self.assertTrue(bd.last_pms_date)

    def test_non_breakdown_does_not_autofill_last_pms(self):
        jc = self._make_card(job_card_type="Only Repair", repair_subtype="Regular")
        jc.insert(ignore_permissions=True)
        self.assertFalse(jc.last_pms_odometer)
        self.assertFalse(jc.last_pms_date)

    def test_breakdown_without_prior_pms_stays_blank(self):
        # Use a fresh vehicle with no PMS history of its own
        fresh_veh = self._ensure_named(
            "Vehicle", "M4FRESH",
            {"doctype": "Vehicle", "registration_number": "M4FRESH",
             "make_model": "Tata Nexon", "oem": self.oem,
             "customer": self.customer},
        )
        frappe.db.set_value("Vehicle", fresh_veh, {
            "oem": self.oem, "customer": self.customer,
        })
        bd = self._make_card(vehicle=fresh_veh, odometer_reading=5_000)
        bd.insert(ignore_permissions=True)
        self.assertFalse(bd.last_pms_odometer)
        self.assertFalse(bd.last_pms_date)

    # ── RCA timestamp stamping ──

    def test_rca_received_at_stamped_on_first_rca_notes_write(self):
        bd = self._make_card()
        bd.insert(ignore_permissions=True)
        self.assertFalse(bd.rca_received_at)

        bd.rca_notes = "Root cause: worn wheel bearing; replaced with OEM part."
        bd.save(ignore_permissions=True)
        bd.reload()
        self.assertTrue(bd.rca_received_at, "rca_received_at must be stamped")

        first_stamp = bd.rca_received_at
        bd.rca_notes = (
            "Root cause: worn wheel bearing; replaced with OEM part. "
            "Follow-up inspection scheduled."
        )
        bd.save(ignore_permissions=True)
        bd.reload()
        self.assertEqual(
            bd.rca_received_at, first_stamp,
            "rca_received_at must not be overwritten on subsequent edits",
        )

    def test_rca_not_stamped_for_non_breakdown(self):
        jc = self._make_card(job_card_type="Only Repair", repair_subtype="Regular")
        jc.insert(ignore_permissions=True)
        # Even if rca_notes is populated (unusual), the hook is type-gated.
        frappe.db.set_value("Job Card", jc.name, "rca_notes", "ignored")
        jc.reload()
        jc.save(ignore_permissions=True)
        jc.reload()
        self.assertFalse(jc.rca_received_at)

    # ── Subsystems roundtrip via API ──

    def test_save_subsystems_api_replaces_table(self):
        self._ensure_named(
            "Subsystem", "Brakes",
            {"doctype": "Subsystem", "subsystem_name": "Brakes",
             "category": "Mechanical"},
        )
        self._ensure_named(
            "Subsystem", "HVAC",
            {"doctype": "Subsystem", "subsystem_name": "HVAC",
             "category": "HVAC"},
        )

        jc = self._make_card(job_card_type="Only Repair", repair_subtype="Regular")
        jc.insert(ignore_permissions=True)

        from vehicle_maintenance.api.job_card import save_subsystems
        save_subsystems(jc.name, ["Brakes", "HVAC"])
        jc.reload()
        names = {row.subsystem for row in jc.subsystems}
        self.assertEqual(names, {"Brakes", "HVAC"})

        save_subsystems(jc.name, ["Brakes"])
        jc.reload()
        names = {row.subsystem for row in jc.subsystems}
        self.assertEqual(names, {"Brakes"})
