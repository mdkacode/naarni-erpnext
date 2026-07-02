"""Monthly report generation: snapshot token/expiry, white-label email, public context.

Run:
    bench --site <site> run-tests --module vehicle_maintenance.fleet_service.test_monthly_km_report
"""

import frappe
from frappe.tests.utils import FrappeTestCase
from frappe.utils import add_days, now_datetime

from vehicle_maintenance.fleet_service import brevo_client, km_report, monthly_km_report, tasks


class TestMonthlyKmReport(FrappeTestCase):
	def setUp(self):
		self.customer = self._ensure_customer("MKR Test Customer", "MKRT")
		self.v1 = self._ensure_vehicle("MKR-V1", self.customer)
		# clean slate (FrappeTestCase rolls back only at class end)
		frappe.db.delete("Vehicle KM Daily", {"vehicle": self.v1})
		frappe.db.delete("KM Report Snapshot", {"customer": self.customer})
		# neutralise commits done by generate/run/sweeper so rows roll back
		self._orig_commit = frappe.db.commit
		frappe.db.commit = lambda *a, **k: None
		# capture Brevo sends
		self.sent = []
		self._orig_send = brevo_client.send_mail
		brevo_client.send_mail = lambda **kw: (
			self.sent.append(kw) or {"sent": True, "message_id": "brevo-xyz"}
		)
		# a day of data so the payload is non-trivial
		self._day(self.v1, "2026-06-01", 0, 42, 42)

	def tearDown(self):
		frappe.db.commit = self._orig_commit
		brevo_client.send_mail = self._orig_send

	def _ensure_customer(self, name, code):
		if frappe.db.exists("Customer", name):
			return name
		c = frappe.new_doc("Customer")
		c.customer_name = name
		c.customer_code = code
		c.flags.ignore_permissions = True
		c.insert(ignore_permissions=True)
		return c.name

	def _ensure_vehicle(self, reg, customer):
		if frappe.db.exists("Vehicle", reg):
			return reg
		v = frappe.new_doc("Vehicle")
		v.registration_number = reg
		v.make_model = "Test EV"
		v.customer = customer
		v.flags.ignore_permissions = True
		v.insert(ignore_permissions=True)
		return v.name

	def _day(self, vehicle, date, start, end, dist):
		doc = frappe.new_doc("Vehicle KM Daily")
		doc.vehicle = vehicle
		doc.date = date
		doc.start_km = start
		doc.end_km = end
		doc.distance_km = dist
		doc.flags.ignore_permissions = True
		doc.insert(ignore_permissions=True)

	def _config(self, logo=None, name="Acme Transit", emails=("ops@acme.test",)):
		cfg = frappe.new_doc("Fleet Report Config")
		cfg.customer = self.customer
		cfg.display_name = name
		cfg.logo = logo
		cfg.enabled = 1
		for e in emails:
			cfg.append("stakeholders", {"email": e, "full_name": "Ops"})
		cfg.flags.ignore_permissions = True
		cfg.insert(ignore_permissions=True)
		return cfg

	# ── snapshot / token ──

	def test_generate_snapshot_issues_token(self):
		name = monthly_km_report.generate_snapshot(self.customer, "2026-06")
		snap = frappe.get_doc("KM Report Snapshot", name)
		self.assertTrue(snap.public_token)
		self.assertGreaterEqual(len(snap.public_token), 32)
		self.assertEqual(snap.status, "Draft")
		# expiry is ~7 days out
		self.assertEqual(str(snap.token_expires_on)[:10], str(add_days(snap.generated_at, 7))[:10])
		# payload has the month total
		payload = frappe.parse_json(snap.payload_json)
		self.assertEqual(payload["totals"]["billable_km"], 42)

	def test_regenerate_is_idempotent_and_refreshes_token(self):
		n1 = monthly_km_report.generate_snapshot(self.customer, "2026-06")
		tok1 = frappe.db.get_value("KM Report Snapshot", n1, "public_token")
		n2 = monthly_km_report.generate_snapshot(self.customer, "2026-06")
		self.assertEqual(n1, n2)  # same (customer, month) row
		tok2 = frappe.db.get_value("KM Report Snapshot", n2, "public_token")
		self.assertNotEqual(tok1, tok2)  # fresh link on regeneration

	# ── email ──

	def test_run_for_customer_sends_white_label_email(self):
		self._config(name="Acme Transit", emails=("ops@acme.test", "cfo@acme.test"))
		out = monthly_km_report.run_for_customer(self.customer, "2026-06")
		self.assertEqual(len(self.sent), 1)
		kw = self.sent[0]
		self.assertIn("ops@acme.test", list(kw["to"]))
		self.assertIn("Acme Transit", kw["html_body"])  # co-branded
		self.assertIn("powered by", kw["html_body"])
		snap = frappe.get_doc("KM Report Snapshot", out["snapshot"])
		self.assertIn(f"/km-report/{snap.public_token}", kw["html_body"])  # CTA link
		self.assertEqual(snap.status, "Sent")
		self.assertEqual(snap.email_message_id, "brevo-xyz")

	def test_run_for_customer_no_recipients_keeps_draft(self):
		# no Fleet Report Config → snapshot built, no email sent, still Draft
		out = monthly_km_report.run_for_customer(self.customer, "2026-06")
		self.assertEqual(self.sent, [])
		snap = frappe.get_doc("KM Report Snapshot", out["snapshot"])
		self.assertEqual(snap.status, "Draft")

	# ── public page context ──

	def test_public_context_valid_expired_and_unknown(self):
		name = monthly_km_report.generate_snapshot(self.customer, "2026-06")
		token = frappe.db.get_value("KM Report Snapshot", name, "public_token")

		ctx = km_report.public_report_context(token)
		self.assertFalse(ctx["expired"])
		self.assertIsNotNone(ctx["report"])
		self.assertEqual(ctx["report"]["totals"]["billable_km"], 42)

		# unknown / blank token → None (generic 404)
		self.assertIsNone(km_report.public_report_context("nope-not-a-token"))
		self.assertIsNone(km_report.public_report_context(""))

		# past expiry → expired, no payload leaked
		frappe.db.set_value("KM Report Snapshot", name, "token_expires_on", add_days(now_datetime(), -1))
		ctx2 = km_report.public_report_context(token)
		self.assertTrue(ctx2["expired"])
		self.assertIsNone(ctx2["report"])

	def test_payload_pii_safe_on_snapshot(self):
		name = monthly_km_report.generate_snapshot(self.customer, "2026-06")
		raw = frappe.db.get_value("KM Report Snapshot", name, "payload_json").lower()
		for leak in ("operator", "device_id", "corrected_by", "naarni_vehicle_id", "mobile_no"):
			self.assertNotIn(leak, raw, msg=f"snapshot payload leaks '{leak}'")

	# ── sweeper ──

	def test_expire_sweeper_flips_past_tokens(self):
		name = monthly_km_report.generate_snapshot(self.customer, "2026-06")
		frappe.db.set_value("KM Report Snapshot", name, "token_expires_on", add_days(now_datetime(), -1))
		tasks.expire_km_report_snapshots()
		self.assertEqual(frappe.db.get_value("KM Report Snapshot", name, "status"), "Expired")
