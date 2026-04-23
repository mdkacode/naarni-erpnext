"""Phase B tests — PRD alignment fixes after the initial 5 milestones.

Covers:
  Gap 8  — Critical force close auto-creates a 24h follow-up Job Card
  Gap 10 — Force Override / Process Override fields accepted via API
  Gap 11 — Groups Impacted Table MultiSelect roundtrip
  Gap 13 — send_report_to_customer flag defaults + persistence
  Gap 15 — Aftersales Eng is NOT in notify_remote_resolution_failed recipients
  Gap 16 — Breakdown SLA switches target when arrival is stamped
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.fleet_service.doctype.job_card import job_card as jc_module


class TestPhaseB(FrappeTestCase):

    def setUp(self) -> None:
        self.oem = self._ensure_named(
            "OEM", "NaArNi", {"doctype": "OEM", "oem_name": "NaArNi"},
        )
        self.customer = self._ensure_named(
            "Customer", "PB Test Customer",
            {"doctype": "Customer", "customer_name": "PB Test Customer",
             "customer_code": "PBCUST", "customer_type": "Individual"},
        )
        self.depot = self._ensure_depot()
        self.vehicle = self._ensure_named(
            "Vehicle", "PB01TEST",
            {"doctype": "Vehicle", "registration_number": "PB01TEST",
             "make_model": "Tata Nexon", "oem": self.oem,
             "customer": self.customer},
        )
        frappe.db.set_value("Vehicle", self.vehicle, {
            "oem": self.oem, "customer": self.customer,
        })

    # ── Fixture helpers ────────────────────────────────────────────────

    def _ensure_named(self, doctype, name, payload):
        if frappe.db.exists(doctype, name):
            return name
        return frappe.get_doc(payload).insert(ignore_permissions=True).name

    def _ensure_depot(self):
        existing = frappe.get_all(
            "Depot", filters={"location_code": "PBDEP"}, pluck="name", limit=1,
        )
        if existing:
            return existing[0]
        return frappe.get_doc({
            "doctype": "Depot", "depot_name": "PB Test Depot",
            "location_code": "PBDEP",
        }).insert(ignore_permissions=True).name

    def _ensure_part_group(self, name="PB Brakes PG", bus_system="Brakes"):
        if frappe.db.exists("Part Group", name):
            return name
        return frappe.get_doc({
            "doctype": "Part Group", "part_group_name": name,
            "bus_system": bus_system,
        }).insert(ignore_permissions=True).name

    def _make_card(self, **kwargs):
        defaults = {
            "doctype": "Job Card", "job_card_type": "PMS + Repair",
            "vehicle": self.vehicle, "customer": self.customer,
            "depot": self.depot, "odometer_reading": 20_000,
            "priority": "Medium", "complaint_description": "PB test",
            "workflow_state": "Open",
        }
        defaults.update(kwargs)
        return frappe.get_doc(defaults)

    # ── Gap 8: Critical auto-follow-up ────────────────────────────────

    def test_critical_force_close_auto_creates_followup(self):
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "rotor", "activity_type": "Only Repair",
            "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)

        jc.force_closed = 1
        jc.force_close_severity = "Critical"
        jc.force_close_reason = "Safety risk; vehicle non-operational."
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)
        jc.reload()

        self.assertTrue(jc.followup_job_card,
                        "Critical force-close must spawn a follow-up JC")

        followup = frappe.get_doc("Job Card", jc.followup_job_card)
        self.assertEqual(followup.source_force_close_job_card, jc.name)
        self.assertEqual(followup.priority, "Urgent")
        self.assertEqual(followup.workflow_state, "Open")

    def test_minor_force_close_does_not_create_followup(self):
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "scratch", "activity_type": "Only Repair",
            "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)

        jc.force_closed = 1
        jc.force_close_severity = "Minor"
        jc.force_close_reason = "Cosmetic only; defer to next PMS."
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)
        jc.reload()
        self.assertFalse(jc.followup_job_card)

    def test_critical_followup_is_idempotent(self):
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "rotor", "activity_type": "Only Repair",
            "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)
        jc.force_closed = 1
        jc.force_close_severity = "Critical"
        jc.force_close_reason = "Non-operational"
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)
        jc.reload()
        first = jc.followup_job_card

        # Touch the record again — must not spawn a second follow-up
        jc.closed_edit_reason = "Minor annotation on force-close."
        jc.save(ignore_permissions=True)
        jc.reload()
        self.assertEqual(jc.followup_job_card, first)

        count = frappe.db.count(
            "Job Card", filters={"source_force_close_job_card": jc.name},
        )
        self.assertEqual(count, 1)

    # ── Gap 10: Force Override + Process Override fields ──────────────

    def test_update_breakdown_diagnosis_accepts_overrides(self):
        from vehicle_maintenance.api.job_card import update_breakdown_diagnosis
        bd = self._make_card(job_card_type="Breakdown", complaint_description="PB breakdown")
        bd.insert(ignore_permissions=True)

        update_breakdown_diagnosis(bd.name, {
            "force_override": 1,
            "force_override_reason": "No documented SOP for this subsystem.",
            "process_override": 1,
            "process_override_steps": "Bypassed step 3; jumper on ECU pin 4.",
        })
        bd.reload()
        self.assertTrue(bd.force_override)
        self.assertIn("No documented", bd.force_override_reason)
        self.assertTrue(bd.process_override)
        self.assertIn("Bypassed", bd.process_override_steps)

    # ── Gap 11: Groups Impacted ───────────────────────────────────────

    def test_save_groups_impacted_roundtrips(self):
        from vehicle_maintenance.api.job_card import save_groups_impacted
        pg1 = self._ensure_part_group("PB Brakes PG", "Brakes")
        pg2 = self._ensure_part_group("PB Powertrain PG", "Powertrain")

        bd = self._make_card(job_card_type="Breakdown", complaint_description="PB groups")
        bd.insert(ignore_permissions=True)
        save_groups_impacted(bd.name, [pg1, pg2])
        bd.reload()
        groups = {row.part_group for row in bd.groups_impacted}
        self.assertEqual(groups, {pg1, pg2})

    def test_groups_impacted_rejected_for_non_breakdown(self):
        from vehicle_maintenance.api.job_card import save_groups_impacted
        jc = self._make_card(job_card_type="Only Repair", repair_subtype="Regular")
        jc.insert(ignore_permissions=True)
        with self.assertRaises(frappe.ValidationError):
            save_groups_impacted(jc.name, [self._ensure_part_group()])

    # ── Gap 13: send_report_to_customer default for SW Update ─────────

    def test_software_update_send_report_flag_defaults_on(self):
        sw = self._make_card(job_card_type="Software Update", complaint_description="PB sw")
        sw.insert(ignore_permissions=True)
        sw.reload()
        self.assertEqual(sw.send_report_to_customer, 1)

    def test_send_report_flag_can_be_disabled(self):
        sw = self._make_card(job_card_type="Software Update",
                             complaint_description="PB sw",
                             send_report_to_customer=0)
        sw.insert(ignore_permissions=True)
        sw.reload()
        self.assertEqual(sw.send_report_to_customer, 0)

    # ── Gap 15: Notification #11 recipients ───────────────────────────

    def test_remote_resolution_recipients_exclude_aftersales_eng(self):
        from vehicle_maintenance.fleet_service import notifications
        self.assertNotIn(
            "Aftersales Eng",
            notifications.RECIPIENTS_REMOTE_RESOLUTION_FAILED,
            "PRD p.2 row 11: Aftersales Eng is NOT a recipient of the remote-"
            "resolution-failed notification (they appear in the TAT escalation "
            "path separately).",
        )
        self.assertIn("Service Engineer",
                      notifications.RECIPIENTS_REMOTE_RESOLUTION_FAILED)
        self.assertIn("Central Ops",
                      notifications.RECIPIENTS_REMOTE_RESOLUTION_FAILED)
        self.assertIn("N. Maintenance Head",
                      notifications.RECIPIENTS_REMOTE_RESOLUTION_FAILED)

    # ── Gap 16: Breakdown SLA switches on arrival ─────────────────────

    def test_breakdown_sla_uses_remote_target_before_arrival(self):
        bd = self._make_card(
            job_card_type="Breakdown", complaint_description="PB BD remote",
            priority="Urgent",
        )
        bd.insert(ignore_permissions=True)
        bd.reload()
        # 0.5h = 1800 seconds; but SLA field is in hours.
        self.assertAlmostEqual(bd.sla_target_hours, 0.5, places=2)

    def test_breakdown_sla_switches_to_onsite_on_arrival(self):
        from frappe.utils import now_datetime
        bd = self._make_card(
            job_card_type="Breakdown", complaint_description="PB BD onsite",
            priority="Urgent",
        )
        bd.insert(ignore_permissions=True)
        first_deadline = bd.sla_deadline

        # Simulate SE arriving at the breakdown location
        bd.travel_started_at = now_datetime()
        bd.arrived_at_location = now_datetime()
        bd.save(ignore_permissions=True)
        bd.reload()

        # Target must flip to the on-site value (default 4h)
        self.assertAlmostEqual(bd.sla_target_hours, 4.0, places=2)
        # Deadline should be recomputed (from arrival anchor)
        self.assertNotEqual(bd.sla_deadline, first_deadline)
        # Breach flags reset so the on-site phase is monitored fresh
        self.assertFalse(bd.sla_breach_sent)
        self.assertFalse(bd.sla_warning_sent)
