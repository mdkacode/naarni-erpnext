"""Smoke tests for the Lead DocType and CRM API."""

from __future__ import annotations

from datetime import datetime, timedelta

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import crm


class TestLead(FrappeTestCase):
	"""Covers Lead creation, status transitions, reminders, and conversion."""

	@classmethod
	def setUpClass(cls) -> None:
		super().setUpClass()
		# Patches should have seeded these, but tests must not depend on migration order.
		cls._ensure_status("New", stage="Open", order=10)
		cls._ensure_status("Qualified", stage="Working", order=30)
		cls._ensure_status("Won", stage="Won", order=70, terminal=1)
		cls._ensure_status("Lost", stage="Lost", order=80, terminal=1)
		cls._ensure_source("Referral")

	# ── helpers ─────────────────────────────────────────────────────────

	@staticmethod
	def _ensure_status(name: str, stage: str, order: int, terminal: int = 0) -> None:
		if frappe.db.exists("Lead Status", name):
			return
		frappe.get_doc(
			{
				"doctype": "Lead Status",
				"status_name": name,
				"stage": stage,
				"display_order": order,
				"is_terminal": terminal,
			}
		).insert(ignore_permissions=True)

	@staticmethod
	def _ensure_source(name: str) -> None:
		if frappe.db.exists("Lead Source", name):
			return
		frappe.get_doc(
			{
				"doctype": "Lead Source",
				"source_name": name,
				"is_active": 1,
			}
		).insert(ignore_permissions=True)

	def _make_lead(self, **overrides):
		payload = {
			"doctype": "Lead",
			"lead_name": "Acme Transport",
			"phone": "+91 98765 43210",
			"email": "ops@acme.test",
			"company_name": "Acme Transport Pvt Ltd",
			"lead_source": "Referral",
			"industry": "Logistics",
			"priority": "High",
			"status": "New",
		}
		payload.update(overrides)
		doc = frappe.get_doc(payload)
		doc.insert(ignore_permissions=True)
		return doc

	# ── tests ───────────────────────────────────────────────────────────

	def test_phone_normalized_to_last_ten_digits(self) -> None:
		doc = self._make_lead(phone="+91 98765-43210")
		self.assertEqual(doc.phone, "9876543210")

	def test_phone_too_short_rejected(self) -> None:
		with self.assertRaises(frappe.ValidationError):
			self._make_lead(lead_name="Too short", phone="12345")

	def test_append_activity_creates_row(self) -> None:
		doc = self._make_lead(lead_name="Activity Ltd")
		doc.append_activity(
			activity_type="Call",
			summary="Intro call",
			outcome="Interested",
		)
		doc.save(ignore_permissions=True)
		self.assertEqual(len(doc.activities), 1)
		self.assertEqual(doc.activities[0].activity_type, "Call")
		self.assertEqual(doc.activities[0].performed_by, frappe.session.user)

	def test_terminal_status_blocks_further_changes(self) -> None:
		doc = self._make_lead(lead_name="Terminal Lead")
		doc.status = "Lost"
		doc.save(ignore_permissions=True)

		doc.status = "Qualified"
		with self.assertRaises(frappe.ValidationError):
			doc.save(ignore_permissions=True)

	def test_convert_to_customer_is_idempotent(self) -> None:
		doc = self._make_lead(lead_name="Convert Co", company_name="Convert Co Pvt Ltd")
		first = crm.convert_lead_to_customer(doc.name)
		second = crm.convert_lead_to_customer(doc.name)
		self.assertEqual(first["data"]["customer"], second["data"]["customer"])
		self.assertTrue(frappe.db.exists("Customer", first["data"]["customer"]))

	def test_schedule_reminder_requires_future_datetime(self) -> None:
		doc = self._make_lead(lead_name="Reminder Lead")
		with self.assertRaises(frappe.ValidationError):
			crm.schedule_reminder(
				{
					"lead": doc.name,
					"reminder_datetime": (datetime.now() - timedelta(hours=1)).isoformat(),
					"subject": "Too late",
					"message_body": "body",
				}
			)

	def test_schedule_and_cancel_reminder(self) -> None:
		doc = self._make_lead(lead_name="Scheduled Lead")
		result = crm.schedule_reminder(
			{
				"lead": doc.name,
				"reminder_datetime": (datetime.now() + timedelta(days=1)).isoformat(),
				"subject": "Follow up",
				"message_body": "Ping",
			}
		)
		name = result["data"]["name"]
		self.assertEqual(frappe.db.get_value("Lead Reminder", name, "status"), "Scheduled")

		crm.cancel_reminder(name)
		self.assertEqual(frappe.db.get_value("Lead Reminder", name, "status"), "Cancelled")
