"""Permission + correction behaviour for the KM Report API.

Run:
    bench --site <site> run-tests --module vehicle_maintenance.api.test_km_reports
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import km_reports


class TestKmReportsApi(FrappeTestCase):
	def setUp(self):
		self.customer = self._ensure_customer("API KMR Customer", "APIKMR")
		self.v1 = self._ensure_vehicle("APIKMR-V1", self.customer)
		frappe.db.delete("Vehicle KM Daily", {"vehicle": self.v1})
		self.row = self._day(self.v1, "2026-06-01", 0, 50, 50)
		self.customer_user = self._ensure_user("kmr-cust@test.localhost", "9990000001", ["Customer"])
		self._orig_commit = frappe.db.commit
		frappe.db.commit = lambda *a, **k: None

	def tearDown(self):
		frappe.set_user("Administrator")
		frappe.db.commit = self._orig_commit

	def _ensure_customer(self, name, code):
		if frappe.db.exists("Customer", name):
			return name
		c = frappe.new_doc("Customer")
		c.customer_name, c.customer_code = name, code
		c.flags.ignore_permissions = True
		c.insert(ignore_permissions=True)
		return c.name

	def _ensure_vehicle(self, reg, customer):
		if frappe.db.exists("Vehicle", reg):
			return reg
		v = frappe.new_doc("Vehicle")
		v.registration_number, v.make_model, v.customer = reg, "Test EV", customer
		v.flags.ignore_permissions = True
		v.insert(ignore_permissions=True)
		return v.name

	def _day(self, vehicle, date, start, end, dist):
		doc = frappe.new_doc("Vehicle KM Daily")
		doc.vehicle, doc.date, doc.start_km, doc.end_km, doc.distance_km = vehicle, date, start, end, dist
		doc.flags.ignore_permissions = True
		doc.insert(ignore_permissions=True)
		return doc.name

	def _ensure_user(self, email, mobile, roles):
		if not frappe.db.exists("User", email):
			u = frappe.new_doc("User")
			u.email = email
			u.first_name = "Test"
			u.mobile_no = mobile  # app's User.validate requires a phone
			u.flags.ignore_permissions = True
			u.insert(ignore_permissions=True)
			u.add_roles(*roles)
		return email

	# ── positive (Administrator has all perms) ──

	def test_get_km_report_ok_for_admin(self):
		out = km_reports.get_km_report(self.customer, "2026-06")
		self.assertTrue(out["success"])
		self.assertEqual(out["data"]["totals"]["billable_km"], 50)

	def test_correct_km_day_applies(self):
		out = km_reports.correct_km_day(self.row, is_excluded=1, exclusion_reason="Service")
		self.assertTrue(out["success"])
		self.assertEqual(out["data"]["effective_distance"], 0.0)
		# override path
		out2 = km_reports.correct_km_day(self.row, override_distance=1, corrected_distance=12)
		self.assertEqual(out2["data"]["effective_distance"], 12.0)

	# ── negative (Customer role must be blocked) ──

	def test_get_km_report_blocked_for_customer(self):
		frappe.set_user(self.customer_user)
		with self.assertRaises(frappe.PermissionError):
			km_reports.get_km_report(self.customer, "2026-06")

	def test_correct_km_day_blocked_for_customer(self):
		frappe.set_user(self.customer_user)
		with self.assertRaises(frappe.PermissionError):
			km_reports.correct_km_day(self.row, is_excluded=1, exclusion_reason="Service")

	def test_regenerate_blocked_for_customer(self):
		frappe.set_user(self.customer_user)
		# frappe.only_for() is a no-op under flags.in_test, so exercise it with the
		# flag off to confirm the role gate actually blocks a Customer.
		frappe.flags.in_test = False
		try:
			with self.assertRaises(frappe.PermissionError):
				km_reports.regenerate_snapshot(self.customer, "2026-06")
		finally:
			frappe.flags.in_test = True
