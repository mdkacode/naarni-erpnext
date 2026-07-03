"""SLA uptime math: planned-downtime exclusion, breach, targets, edge cases.

Run:
    bench --site <site> run-tests --module vehicle_maintenance.fleet_service.test_sla
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.fleet_service import sla


class TestSla(FrappeTestCase):
	def setUp(self):
		self.customer = self._ensure_customer("SLA Test Customer", "SLAT")
		self.v1 = self._ensure_vehicle("SLA-V1", self.customer)
		self.v2 = self._ensure_vehicle("SLA-V2", self.customer)
		frappe.db.delete("Vehicle KM Daily", {"vehicle": ["in", [self.v1, self.v2]]})

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

	def _day(self, vehicle, date, inactive=0, excluded=0, reason=None):
		doc = frappe.new_doc("Vehicle KM Daily")
		doc.vehicle, doc.date = vehicle, date
		doc.start_km = doc.end_km = doc.distance_km = 10  # value irrelevant to uptime
		doc.is_inactive = inactive
		if excluded:
			doc.is_excluded = 1
			doc.exclusion_reason = reason or "Service"
		doc.flags.ignore_permissions = True
		doc.insert(ignore_permissions=True)

	def _fill(self, vehicle, dates, **kw):
		for d in dates:
			self._day(vehicle, d, **kw)

	def test_uptime_active_over_observed(self):
		# 8 active + 2 inactive (reported but idle) → observed 10, uptime 8/10 = 80%.
		self._fill(self.v1, [f"2026-06-{d:02d}" for d in range(1, 9)])
		self._fill(self.v1, ["2026-06-09", "2026-06-10"], inactive=1)
		m = sla.vehicle_uptime(self.v1, "2026-06-01", "2026-06-10")
		self.assertEqual(m["active_days"], 8)
		self.assertEqual(m["inactive_days"], 2)
		self.assertEqual(m["observed_days"], 10)
		self.assertEqual(m["uptime_pct"], 80.0)

	def test_no_data_days_ignored(self):
		# 8 active days, remaining days of the month have NO telematics row → ignored,
		# not counted as downtime → uptime 8/8 = 100%.
		self._fill(self.v1, [f"2026-06-{d:02d}" for d in range(1, 9)])
		m = sla.vehicle_uptime(self.v1, "2026-06-01", "2026-06-30")
		self.assertEqual(m["observed_days"], 8)
		self.assertEqual(m["uptime_pct"], 100.0)

	def test_excluded_removed_from_observed(self):
		# 6 active + 2 inactive + 2 excluded (any reason) → observed 8 (excluded gone),
		# uptime 6/8 = 75%.
		self._fill(self.v1, [f"2026-06-{d:02d}" for d in range(1, 7)])
		self._fill(self.v1, ["2026-06-07", "2026-06-08"], inactive=1)
		self._fill(self.v1, ["2026-06-09", "2026-06-10"], excluded=1, reason="Service")
		m = sla.vehicle_uptime(self.v1, "2026-06-01", "2026-06-10")
		self.assertEqual(m["active_days"], 6)
		self.assertEqual(m["inactive_days"], 2)
		self.assertEqual(m["excluded_days"], 2)
		self.assertEqual(m["observed_days"], 8)
		self.assertEqual(m["uptime_pct"], 75.0)

	def test_inactive_day_not_active(self):
		self._fill(self.v1, ["2026-06-01", "2026-06-02"])  # active
		self._day(self.v1, "2026-06-03", inactive=1)  # reported but inactive
		m = sla.vehicle_uptime(self.v1, "2026-06-01", "2026-06-03")
		self.assertEqual(m["active_days"], 2)
		self.assertEqual(m["observed_days"], 3)
		self.assertEqual(m["uptime_pct"], round(200 / 3, 1))

	def test_all_excluded_returns_none(self):
		# every reported day excluded → 0 observed → uptime undefined (None).
		self._fill(self.v1, ["2026-06-01", "2026-06-02", "2026-06-03"], excluded=1, reason="Breakdown")
		m = sla.vehicle_uptime(self.v1, "2026-06-01", "2026-06-03")
		self.assertEqual(m["observed_days"], 0)
		self.assertIsNone(m["uptime_pct"])

	def test_no_rows_returns_none(self):
		# no telematics at all → nothing observed → None (not 0%).
		m = sla.vehicle_uptime(self.v1, "2026-06-01", "2026-06-05")
		self.assertEqual(m["observed_days"], 0)
		self.assertIsNone(m["uptime_pct"])

	def test_fleet_sla_breach_target_and_color(self):
		# v1 = 8 active + 4 inactive → 66.7% (breach vs 95); v2 = 10 active → 100%.
		self._fill(self.v1, [f"2026-06-{d:02d}" for d in range(1, 9)])
		self._fill(self.v1, [f"2026-06-{d:02d}" for d in range(9, 13)], inactive=1)
		self._fill(self.v2, [f"2026-06-{d:02d}" for d in range(1, 11)])
		result = sla.fleet_sla(self.customer, "2026-06-01", "2026-06-30")
		by_reg = {r["registration"]: r for r in result["vehicles"]}
		self.assertTrue(by_reg["SLA-V1"]["breach"])
		self.assertEqual(by_reg["SLA-V1"]["color"], sla.BAD_COLOR)
		self.assertEqual(by_reg["SLA-V1"]["uptime_pct"], round(800 / 12, 1))  # 66.7
		self.assertFalse(by_reg["SLA-V2"]["breach"])
		self.assertEqual(by_reg["SLA-V2"]["uptime_pct"], 100.0)
		self.assertEqual(result["summary"]["breaches"], 1)

	def test_per_vehicle_target_override(self):
		# 8 active + 4 inactive → 66.7%; a 60% override clears the breach.
		self._fill(self.v1, [f"2026-06-{d:02d}" for d in range(1, 9)])
		self._fill(self.v1, [f"2026-06-{d:02d}" for d in range(9, 13)], inactive=1)
		frappe.db.set_value("Vehicle", self.v1, "uptime_target_override", 60)
		result = sla.fleet_sla(self.customer, "2026-06-01", "2026-06-30")
		v1 = next(r for r in result["vehicles"] if r["registration"] == "SLA-V1")
		self.assertEqual(v1["target"], 60.0)
		self.assertFalse(v1["breach"])
