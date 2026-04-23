"""Phase 2 tests — PRD items deferred past the initial 5 milestones.

Covers:
  Gap 20  — Closure Snapshot on entering Closed; reopen stamps the record
  Gap 19  — Part-level + JC-level customer rejection feedback
  Gap 9   — Customer approval token generated on 'Awaiting Customer Approval'
  TAT Adherence reporting shape
  Repeated-issue detection helper
  Depot broadcast on high occurrence-risk Breakdown closure
"""

import frappe
import json as _json
from frappe.tests.utils import FrappeTestCase


class TestPhase2(FrappeTestCase):

    def setUp(self) -> None:
        self.oem = self._ensure_named(
            "OEM", "NaArNi", {"doctype": "OEM", "oem_name": "NaArNi"},
        )
        self.customer = self._ensure_named(
            "Customer", "P2 Test Customer",
            {"doctype": "Customer", "customer_name": "P2 Test Customer",
             "customer_code": "P2CUST", "customer_type": "Individual"},
        )
        self.depot = self._ensure_depot()
        self.vehicle = self._ensure_named(
            "Vehicle", "P201TEST",
            {"doctype": "Vehicle", "registration_number": "P201TEST",
             "make_model": "Tata Nexon", "oem": self.oem,
             "customer": self.customer},
        )
        frappe.db.set_value("Vehicle", self.vehicle, {
            "oem": self.oem, "customer": self.customer,
        })

    def _ensure_named(self, doctype, name, payload):
        if frappe.db.exists(doctype, name):
            return name
        return frappe.get_doc(payload).insert(ignore_permissions=True).name

    def _ensure_depot(self):
        existing = frappe.get_all(
            "Depot", filters={"location_code": "P2DEP"}, pluck="name", limit=1,
        )
        if existing:
            return existing[0]
        return frappe.get_doc({
            "doctype": "Depot", "depot_name": "P2 Test Depot",
            "location_code": "P2DEP",
        }).insert(ignore_permissions=True).name

    def _ensure_part_group(self, name="P2 Brakes PG", bus_system="Brakes"):
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
            "priority": "Medium", "complaint_description": "P2 test",
            "workflow_state": "Open",
        }
        defaults.update(kwargs)
        return frappe.get_doc(defaults)

    # ── Gap 20: Closure Snapshot on Reopen ────────────────────────────

    def test_closure_record_created_on_entering_closed(self):
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "rotor", "activity_type": "Only Repair",
            "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)

        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        rows = frappe.get_all(
            "Job Card Closure Record",
            filters={"job_card": jc.name},
            fields=["name", "sequence", "workflow_state_at_close",
                    "snapshot_json", "reopened_at"],
        )
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["sequence"], 1)
        self.assertEqual(rows[0]["workflow_state_at_close"], "Closed")
        self.assertFalse(rows[0]["reopened_at"])
        snap = _json.loads(rows[0]["snapshot_json"])
        self.assertEqual(snap["workflow_state"], "Closed")
        self.assertEqual(len(snap["repair_items"]), 1)

    def test_reopen_stamps_closure_record(self):
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "rotor", "activity_type": "Only Repair",
            "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        # Move out of Closed (use Reopened transition)
        jc.workflow_state = "Reopened"
        jc.closed_edit_reason = "Reopen: rattle noise still present"
        jc.save(ignore_permissions=True)

        row = frappe.get_all(
            "Job Card Closure Record",
            filters={"job_card": jc.name},
            fields=["reopened_at", "reopen_reason"],
        )[0]
        self.assertTrue(row["reopened_at"])
        self.assertIn("rattle noise", row["reopen_reason"])

    def test_second_closure_creates_record_with_sequence_2(self):
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "rotor", "activity_type": "Only Repair",
            "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)
        jc.workflow_state = "Reopened"
        jc.closed_edit_reason = "Reopen: second pass"
        jc.save(ignore_permissions=True)
        jc.workflow_state = "WIP"
        jc.save(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        seqs = sorted(frappe.get_all(
            "Job Card Closure Record",
            filters={"job_card": jc.name},
            pluck="sequence",
        ))
        self.assertEqual(seqs, [1, 2])

    # ── Gap 19: Part-level rejection feedback ─────────────────────────

    def test_record_customer_approval_decision_accept_sets_all_items(self):
        from vehicle_maintenance.api.job_card import record_customer_approval_decision
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "Pads", "activity_type": "Spare Replacement",
            "qty": 1, "rate": 500, "component_status": "Good",
        })
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "Rotor", "activity_type": "Spare Replacement",
            "qty": 1, "rate": 2000, "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)

        record_customer_approval_decision(jc.name, approved=1)
        jc.reload()
        for item in jc.repair_items:
            self.assertEqual(item.customer_approved, 1)
            self.assertIsNotNone(item.customer_decision_at)
        self.assertIsNotNone(jc.customer_approval_received_at)

    def test_record_customer_approval_decision_reject_with_part_feedback(self):
        from vehicle_maintenance.api.job_card import record_customer_approval_decision
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "Rotor", "activity_type": "Spare Replacement",
            "qty": 1, "rate": 2000, "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)

        record_customer_approval_decision(
            jc.name, approved=0,
            rejection_feedback="Budget constraints",
            per_part_feedback=[{
                "part_group": self._ensure_part_group(),
                "description": "Rotor",
                "feedback": "Will defer to next cycle",
            }],
        )
        jc.reload()
        self.assertEqual(jc.customer_rejection_feedback, "Budget constraints")
        item = jc.repair_items[0]
        self.assertEqual(item.customer_approved, 0)
        self.assertEqual(item.item_status, "Customer Rejected")
        self.assertIn("defer", item.customer_rejection_feedback or "")

    def test_record_customer_rejection_requires_some_feedback(self):
        from vehicle_maintenance.api.job_card import record_customer_approval_decision
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "Rotor", "activity_type": "Spare Replacement",
            "qty": 1, "rate": 2000, "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)
        with self.assertRaises(frappe.ValidationError):
            record_customer_approval_decision(jc.name, approved=0)

    # ── Gap 9: Approval token ─────────────────────────────────────────

    def test_approval_token_generated_on_awaiting_state(self):
        jc = self._make_card()
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "rotor", "activity_type": "Only Repair",
            "component_status": "Good",
        })
        jc.insert(ignore_permissions=True)

        jc.workflow_state = "WIP"
        jc.save(ignore_permissions=True)
        jc.workflow_state = "Awaiting Customer Approval"
        jc.save(ignore_permissions=True)
        jc.reload()

        self.assertTrue(jc.customer_approval_token)
        self.assertGreaterEqual(len(jc.customer_approval_token or ""), 32)

    # ── TAT Adherence Report ──────────────────────────────────────────

    def test_tat_adherence_report_returns_envelope(self):
        from vehicle_maintenance.api.job_card import tat_adherence_report
        result = tat_adherence_report()
        self.assertTrue(result["success"])
        self.assertIn("by_type", result["data"])
        self.assertIn("overall", result["data"])
        self.assertIn("total", result["data"]["overall"])

    # ── Repeated-issue detection ──────────────────────────────────────

    def test_repeated_issues_flags_two_plus_occurrences(self):
        from vehicle_maintenance.api.job_card import repeated_issues_for_vehicle
        pg = self._ensure_part_group("P2 Brakes PG", "Brakes")
        # Seed two closed PMS cards both touching "Brakes"
        for i in range(2):
            jc = self._make_card(odometer_reading=20_000 + i * 1000)
            jc.append("repair_items", {
                "part_group": pg, "description": f"pad {i}",
                "activity_type": "Spare Replacement",
                "component_status": "Repair/Replace Recommended",
            })
            jc.insert(ignore_permissions=True)
            jc.workflow_state = "Closed"
            jc.save(ignore_permissions=True)

        result = repeated_issues_for_vehicle(self.vehicle, window_days=365)
        systems = {r["bus_system"] for r in result["data"]["repeated_issues"]}
        self.assertIn("Brakes", systems)

    # ── Depot broadcast on fleet-wide occurrence risk ─────────────────

    def test_breakdown_high_occurrence_risk_triggers_broadcast(self):
        # Seed a DM user for notification
        dm_user = self._ensure_named(
            "User", "p2-dm@example.com",
            {"doctype": "User", "email": "p2-dm@example.com",
             "first_name": "P2 DM", "enabled": 1,
             "send_welcome_email": 0, "user_type": "System User"},
        )
        if not frappe.db.exists("Has Role", {"parent": dm_user, "role": "Depot Manager"}):
            frappe.get_doc({
                "doctype": "Has Role", "parent": dm_user,
                "parenttype": "User", "parentfield": "roles",
                "role": "Depot Manager",
            }).insert(ignore_permissions=True)

        bd = self._make_card(job_card_type="Breakdown",
                             complaint_description="P2 BD broadcast",
                             priority="Urgent")
        bd.insert(ignore_permissions=True)
        bd.occurrence_risk = "High"
        bd.workflow_state = "Closed"
        bd.save(ignore_permissions=True)
        bd.reload()

        self.assertTrue(bd.fleet_broadcast_sent, "Fleet broadcast flag must be set")
        # At least one Notification Log entry tagged fleet-wide alert for this BD
        count = frappe.db.count(
            "Notification Log",
            filters={"document_name": bd.name, "for_user": dm_user,
                     "subject": ["like", "%Fleet-wide alert%"]},
        )
        self.assertGreater(count, 0)
