"""Unit tests for Job Card DocType.

Covers the original lifecycle checks plus the Milestone 1 PRD-alignment
additions: 20K/40K/80K check-sheet thresholds, breakdown remote-resolution
timer init, per-category PMS score persistence, OEM auto-fetch, and
subsystem multi-select binding.
"""

import frappe
from frappe.tests.utils import FrappeTestCase


class TestJobCard(FrappeTestCase):
    """Test suite for Job Card lifecycle and validations."""

    def setUp(self) -> None:
        self.oem = self._ensure_oem()
        self.vehicle = self._create_vehicle()
        self.customer = self._create_customer()
        self.depot = self._ensure_depot()

    # ── Fixture helpers ────────────────────────────────────────────────

    def _ensure_oem(self) -> str:
        if frappe.db.exists("OEM", "NaArNi"):
            return "NaArNi"
        return frappe.get_doc({
            "doctype": "OEM",
            "oem_name": "NaArNi",
        }).insert(ignore_permissions=True).name

    def _ensure_depot(self) -> str:
        code = "TESTDEP"
        existing = frappe.db.exists("Depot", {"location_code": code})
        if existing:
            return existing
        return frappe.get_doc({
            "doctype": "Depot",
            "depot_name": "Test Depot",
            "location_code": code,
        }).insert(ignore_permissions=True).name

    def _create_vehicle(self) -> str:
        reg = "KA01AB1234"
        existing = frappe.db.exists("Vehicle", reg)
        if existing:
            # Earlier test runs (before the OEM column existed) may have left
            # this row with oem=NULL — reconcile so OEM-dependent tests work.
            frappe.db.set_value("Vehicle", reg, "oem", self.oem)
            return existing
        return frappe.get_doc({
            "doctype": "Vehicle",
            "registration_number": reg,
            "make_model": "Tata Nexon",
            "oem": self.oem,
        }).insert(ignore_permissions=True).name

    def _create_customer(self) -> str:
        code = "TESTCUST"
        existing = frappe.db.exists("Customer", {"customer_code": code})
        if existing:
            return existing
        return frappe.get_doc({
            "doctype": "Customer",
            "customer_name": "Test VM Customer",
            "customer_code": code,
            "customer_type": "Individual",
        }).insert(ignore_permissions=True).name

    def _make_job_card(self, **kwargs) -> "frappe.Document":
        defaults = {
            "doctype": "Job Card",
            "job_card_type": "PMS + Repair",
            "vehicle": self.vehicle,
            "odometer_reading": 15_000,
            "customer": self.customer,
            "depot": self.depot,
            "priority": "Medium",
            "complaint_description": "Routine service",
            "workflow_state": "Open",
        }
        defaults.update(kwargs)
        return frappe.get_doc(defaults)

    # ── Baseline lifecycle tests ───────────────────────────────────────

    def test_create_job_card(self) -> None:
        jc = self._make_job_card()
        jc.insert(ignore_permissions=True)
        self.assertTrue(jc.name)
        self.assertEqual(jc.workflow_state, "Open")

    def test_negative_odometer_rejected(self) -> None:
        jc = self._make_job_card(odometer_reading=-100)
        self.assertRaises(frappe.ValidationError, jc.insert, ignore_permissions=True)

    def test_cost_computation(self) -> None:
        jc = self._make_job_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(bus_system="Powertrain"),
            "description": "Replace oil filter",
            "activity_type": "Spare Replacement",
            "estimated_amount": 500,
            "actual_amount": 450,
        })
        jc.append("maintenance_items", {
            "maintenance_type": "Oil/Lubricant",
            "description": "Engine oil top-up",
            "action": "Top-up",
            "estimated_amount": 1000,
            "actual_amount": 1000,
        })
        jc.insert(ignore_permissions=True)
        self.assertEqual(jc.estimated_cost, 1500)
        self.assertEqual(jc.actual_cost, 1450)

    # ── Milestone 1: check-sheet auto-selection with PRD thresholds ───

    def test_check_sheet_sheet_a_under_20k(self) -> None:
        jc = self._make_job_card(odometer_reading=15_000)
        jc.insert(ignore_permissions=True)
        self.assertEqual(jc.check_sheet, "Sheet A (0-20,000 km)")

    def test_check_sheet_sheet_b_between_20k_and_40k(self) -> None:
        jc = self._make_job_card(odometer_reading=35_000)
        jc.insert(ignore_permissions=True)
        self.assertEqual(jc.check_sheet, "Sheet B (20,001-40,000 km)")

    def test_check_sheet_sheet_c_between_40k_and_80k(self) -> None:
        jc = self._make_job_card(odometer_reading=70_000)
        jc.insert(ignore_permissions=True)
        self.assertEqual(jc.check_sheet, "Sheet C (40,001-80,000 km)")

    def test_check_sheet_sheet_d_above_80k(self) -> None:
        jc = self._make_job_card(odometer_reading=120_000)
        jc.insert(ignore_permissions=True)
        self.assertEqual(jc.check_sheet, "Sheet D (80,001+ km)")

    # ── Milestone 1: Breakdown remote-resolution timer init ────────────

    def test_breakdown_inits_remote_resolution_timer(self) -> None:
        jc = self._make_job_card(
            job_card_type="Breakdown",
            complaint_description="Engine won't start",
        )
        jc.insert(ignore_permissions=True)
        self.assertEqual(jc.remote_resolution_status, "In Progress")
        self.assertIsNotNone(jc.remote_resolution_started_at)

    def test_non_breakdown_does_not_init_remote_resolution_timer(self) -> None:
        jc = self._make_job_card()
        jc.insert(ignore_permissions=True)
        self.assertFalse(jc.remote_resolution_started_at)

    # ── Milestone 1: OEM auto-fetch from Vehicle ───────────────────────

    def test_oem_fetched_from_vehicle(self) -> None:
        jc = self._make_job_card()
        jc.insert(ignore_permissions=True)
        # fetch_from populates on save; reload to confirm the persisted value.
        jc.reload()
        self.assertEqual(jc.oem, "NaArNi")

    # ── Milestone 1: Per-category PMS score persistence ────────────────

    def test_category_scores_persisted_per_bus_system(self) -> None:
        pg_brakes = self._ensure_part_group(name="Test Brakes PG", bus_system="Brakes")
        pg_powertrain = self._ensure_part_group(name="Test Powertrain PG", bus_system="Powertrain")

        jc = self._make_job_card(workflow_state="Open")
        jc.append("repair_items", {
            "part_group": pg_brakes,
            "description": "Check brake pads",
            "activity_type": "Only Repair",
            "component_status": "Good",
        })
        jc.append("repair_items", {
            "part_group": pg_brakes,
            "description": "Check rotor",
            "activity_type": "Only Repair",
            "component_status": "Repair/Replace Recommended",
        })
        jc.append("repair_items", {
            "part_group": pg_powertrain,
            "description": "Check engine mount",
            "activity_type": "Only Repair",
            "component_status": "Repair/Replace Immediately",
        })
        jc.insert(ignore_permissions=True)
        jc.reload()

        categories = {row.category: row for row in jc.category_scores}
        self.assertIn("Brakes", categories)
        self.assertIn("Powertrain", categories)
        # Brakes: (10 + 5) / 20 * 100 = 75%
        self.assertEqual(round(categories["Brakes"].pre_pms_score, 1), 75.0)
        # Powertrain: 0 / 10 * 100 = 0%
        self.assertEqual(round(categories["Powertrain"].pre_pms_score, 1), 0.0)
        # Overall = (75 + 0) / 2 = 37.5
        self.assertEqual(round(jc.pre_pms_score, 1), 37.5)

    # ── Milestone 1: Subsystems Table MultiSelect binds ────────────────

    def test_subsystems_multiselect_binding(self) -> None:
        subsystem = self._ensure_subsystem("Brakes")
        jc = self._make_job_card(
            job_card_type="Only Repair",
            repair_subtype="Regular",
        )
        jc.append("subsystems", {"subsystem": subsystem})
        jc.insert(ignore_permissions=True)
        jc.reload()
        self.assertEqual(len(jc.subsystems), 1)
        self.assertEqual(jc.subsystems[0].subsystem, "Brakes")

    # ── Small fixture helpers used by scoring test ─────────────────────

    def _ensure_part_group(self, name: str = "Test General PG", bus_system: str = "Other") -> str:
        existing = frappe.db.exists("Part Group", name)
        if existing:
            return existing
        return frappe.get_doc({
            "doctype": "Part Group",
            "part_group_name": name,
            "bus_system": bus_system,
        }).insert(ignore_permissions=True).name

    def _ensure_subsystem(self, name: str) -> str:
        if frappe.db.exists("Subsystem", name):
            return name
        return frappe.get_doc({
            "doctype": "Subsystem",
            "subsystem_name": name,
            "category": "Mechanical",
        }).insert(ignore_permissions=True).name
