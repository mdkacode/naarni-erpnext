"""Milestone 5 tests — Inventory, Reopen, Feedback, Closed-edit notification."""

import frappe
from frappe.tests.utils import FrappeTestCase


class TestJobCardM5(FrappeTestCase):

    def setUp(self) -> None:
        self.oem = self._ensure_named(
            "OEM", "NaArNi", {"doctype": "OEM", "oem_name": "NaArNi"},
        )
        self.customer = self._ensure_named(
            "Customer", "M5 Test Customer",
            {"doctype": "Customer", "customer_name": "M5 Test Customer",
             "customer_code": "M5CUST", "customer_type": "Individual"},
        )
        self.depot = self._ensure_depot()
        self.vehicle = self._ensure_named(
            "Vehicle", "M501TEST",
            {"doctype": "Vehicle", "registration_number": "M501TEST",
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
            "Depot", filters={"location_code": "M5DEP"}, pluck="name", limit=1,
        )
        if existing:
            return existing[0]
        return frappe.get_doc({
            "doctype": "Depot", "depot_name": "M5 Test Depot",
            "location_code": "M5DEP",
        }).insert(ignore_permissions=True).name

    def _ensure_part_group(self):
        if frappe.db.exists("Part Group", "M5 Brakes PG"):
            return "M5 Brakes PG"
        return frappe.get_doc({
            "doctype": "Part Group", "part_group_name": "M5 Brakes PG",
            "bus_system": "Brakes",
        }).insert(ignore_permissions=True).name

    def _make_card(self, **kwargs):
        defaults = {
            "doctype": "Job Card", "job_card_type": "PMS + Repair",
            "vehicle": self.vehicle, "customer": self.customer,
            "depot": self.depot, "odometer_reading": 22_000,
            "priority": "Medium", "complaint_description": "M5 test",
            "workflow_state": "Open",
        }
        defaults.update(kwargs)
        jc = frappe.get_doc(defaults)
        jc.append("repair_items", {
            "part_group": self._ensure_part_group(),
            "description": "M5 check", "activity_type": "Only Repair",
            "component_status": "Good",
        })
        return jc

    # ── Reopen flow ───────────────────────────────────────────────────

    def test_reopen_api_moves_closed_to_reopened(self):
        from vehicle_maintenance.api.job_card import reopen_job_card
        jc = self._make_card()
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        reopen_job_card(jc.name, "Rattle noise still present after closure.")
        jc.reload()
        self.assertEqual(jc.workflow_state, "Reopened")
        self.assertIn("Reopen:", jc.closed_edit_reason or "")

    def test_reopen_rejects_non_closed_cards(self):
        from vehicle_maintenance.api.job_card import reopen_job_card
        jc = self._make_card()
        jc.insert(ignore_permissions=True)
        # Card is Open, not Closed
        with self.assertRaises(frappe.ValidationError):
            reopen_job_card(jc.name, "try")

    def test_reopen_requires_reason(self):
        from vehicle_maintenance.api.job_card import reopen_job_card
        jc = self._make_card()
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        with self.assertRaises(frappe.ValidationError):
            reopen_job_card(jc.name, "")

    # ── Feedback flow ─────────────────────────────────────────────────

    def test_feedback_only_on_closed_cards(self):
        jc = self._make_card()
        jc.insert(ignore_permissions=True)
        # Not closed yet
        fb = frappe.get_doc({
            "doctype": "Customer Feedback",
            "job_card": jc.name,
            "rating": 5,
        })
        with self.assertRaises(frappe.ValidationError):
            fb.insert(ignore_permissions=True)

    def test_feedback_rating_range_enforced(self):
        jc = self._make_card()
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        fb = frappe.get_doc({
            "doctype": "Customer Feedback",
            "job_card": jc.name,
            "rating": 7,
        })
        with self.assertRaises(frappe.ValidationError):
            fb.insert(ignore_permissions=True)

    def test_feedback_submit_api_is_idempotent(self):
        from vehicle_maintenance.api.job_card import (
            submit_customer_feedback, get_customer_feedback,
        )
        jc = self._make_card()
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        submit_customer_feedback(jc.name, rating=4, nps_score=8,
                                 comments="Nice!", would_recommend="Yes")
        first = frappe.db.count("Customer Feedback", {"job_card": jc.name})
        self.assertEqual(first, 1)

        # Resubmit — updates the existing row, doesn't create a duplicate
        submit_customer_feedback(jc.name, rating=5, nps_score=10,
                                 comments="Even nicer!", would_recommend="Yes")
        self.assertEqual(frappe.db.count("Customer Feedback", {"job_card": jc.name}), 1)

        result = get_customer_feedback(jc.name)
        self.assertEqual(result["data"]["rating"], 5)
        self.assertEqual(result["data"]["nps_score"], 10)

    def test_feedback_nps_range_enforced(self):
        jc = self._make_card()
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)
        fb = frappe.get_doc({
            "doctype": "Customer Feedback",
            "job_card": jc.name,
            "rating": 4,
            "nps_score": 12,
        })
        with self.assertRaises(frappe.ValidationError):
            fb.insert(ignore_permissions=True)

    # ── Central Ops closed-edit notification ──────────────────────────

    def test_central_ops_notification_on_closed_edit(self):
        # Seed a Central Ops user so the notification has a recipient
        co_user = self._ensure_named(
            "User", "m5-ops@example.com",
            {"doctype": "User", "email": "m5-ops@example.com",
             "first_name": "M5 Ops", "enabled": 1,
             "send_welcome_email": 0, "user_type": "System User"},
        )
        for role_name in ["Central Ops"]:
            if not frappe.db.exists("Has Role",
                                    {"parent": co_user, "role": role_name}):
                frappe.get_doc({
                    "doctype": "Has Role",
                    "parent": co_user, "parenttype": "User",
                    "parentfield": "roles", "role": role_name,
                }).insert(ignore_permissions=True)

        jc = self._make_card()
        jc.insert(ignore_permissions=True)
        jc.workflow_state = "Closed"
        jc.save(ignore_permissions=True)

        # Capture Notification Log count before the closed edit
        pre_count = frappe.db.count(
            "Notification Log",
            filters={"document_name": jc.name, "for_user": co_user},
        )

        # Edit the closed card — notification should fire to Central Ops
        jc.reload()
        jc.closed_edit_reason = "Fixed a typo in the complaint narrative."
        jc.complaint_description = "M5 test (minor narrative tweak)"
        jc.save(ignore_permissions=True)

        post_count = frappe.db.count(
            "Notification Log",
            filters={"document_name": jc.name, "for_user": co_user},
        )
        self.assertGreater(
            post_count, pre_count,
            "Central Ops must receive a notification on closed-state edits",
        )
