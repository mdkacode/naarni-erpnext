"""Monthly KM Report aggregation math: rollups, exclusions, corrections, boundaries.

Run:
    bench --site <site> run-tests --module vehicle_maintenance.fleet_service.test_km_report
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.fleet_service import km_report


class TestKmReport(FrappeTestCase):
	def setUp(self):
		self.customer = self._ensure_customer("KMR Test Customer", "KMRT")
		self.v1 = self._ensure_vehicle("KMR-V1", self.customer)
		self.v2 = self._ensure_vehicle("KMR-V2", self.customer)
		# FrappeTestCase only rolls back at class end, so clear this class's daily
		# rows before each test for a clean, deterministic slate.
		frappe.db.delete("Vehicle KM Daily", {"vehicle": ["in", [self.v1, self.v2]]})

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

	def _day(
		self, vehicle, date, start, end, dist, inactive=0, excluded=0, reason=None, corrected=None, dead=None
	):
		doc = frappe.new_doc("Vehicle KM Daily")
		doc.vehicle = vehicle
		doc.date = date
		doc.start_km = start
		doc.end_km = end
		doc.distance_km = dist
		doc.is_inactive = inactive
		if excluded:
			doc.is_excluded = 1
			doc.exclusion_reason = reason or "Service"
		if corrected is not None:
			doc.override_distance = 1
			doc.corrected_distance = corrected
		if dead is not None:
			doc.dead_km = dead
		doc.flags.ignore_permissions = True
		doc.insert(ignore_permissions=True)
		return doc.name

	# ── rollup math ──

	def test_month_rollup_basic(self):
		self._day(self.v1, "2026-06-01", 1000, 1010, 10)
		self._day(self.v1, "2026-06-02", 1010, 1030, 20)
		self._day(self.v1, "2026-06-03", 1030, 1060, 30)
		rollup = {r["vehicle"]: r for r in km_report.month_vehicle_rollup(self.customer, "2026-06")}
		r = rollup[self.v1]
		self.assertEqual(r["distance_km"], 60)
		self.assertEqual(r["start_km"], 1000)  # first day's start
		self.assertEqual(r["end_km"], 1060)  # last day's end
		self.assertEqual(r["active_days"], 3)

	def test_excluded_day_subtracts(self):
		self._day(self.v1, "2026-06-01", 0, 10, 10)
		self._day(self.v1, "2026-06-02", 10, 30, 20, excluded=1, reason="Service")
		self._day(self.v1, "2026-06-03", 30, 60, 30)
		r = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}[self.v1]
		self.assertEqual(r["distance_km"], 40)  # billable: 10 + 0 + 30
		self.assertEqual(r["raw_distance_km"], 60)  # total distance incl. excluded day
		self.assertEqual(r["excluded_km"], 20)  # the excluded day's KM, surfaced
		self.assertEqual(r["excluded_days"], 1)
		self.assertEqual(r["service_days"], 1)
		self.assertEqual(r["active_days"], 2)

	def test_payload_surfaces_excluded_km(self):
		self._day(self.v1, "2026-06-01", 0, 100, 100)
		self._day(self.v1, "2026-06-02", 100, 300, 200, excluded=1, reason="Breakdown")
		payload = km_report.build_report_payload(self.customer, "2026-06")
		self.assertEqual(payload["totals"]["total_distance_km"], 300)
		self.assertEqual(payload["totals"]["excluded_km"], 200)
		self.assertEqual(payload["totals"]["billable_km"], 100)
		veh = {v["registration"]: v for v in payload["vehicles"]}[self.v1]
		self.assertEqual(veh["excluded_km"], 200)
		self.assertEqual(veh["billable_km"], 100)

	def test_corrected_distance_overrides(self):
		self._day(self.v1, "2026-06-01", 0, 100, 100, corrected=25)
		r = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}[self.v1]
		self.assertEqual(r["distance_km"], 25)

	def test_dead_km_subtracts(self):
		# Manual dead KM is deducted from that day's billable and surfaced as a total.
		self._day(self.v1, "2026-06-01", 0, 100, 100, dead=30)
		self._day(self.v1, "2026-06-02", 100, 250, 150)
		r = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}[self.v1]
		self.assertEqual(r["distance_km"], 220)  # (100-30) + 150
		self.assertEqual(r["raw_distance_km"], 250)  # unchanged raw total
		self.assertEqual(r["dead_km"], 30)

	def test_dead_km_clamps_non_negative(self):
		# Dead KM larger than the day's distance can never push billable below 0.
		self._day(self.v1, "2026-06-01", 0, 40, 40, dead=100)
		r = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}[self.v1]
		self.assertEqual(r["distance_km"], 0)
		self.assertEqual(r["dead_km"], 100)

	def test_dead_km_on_override(self):
		# Dead KM subtracts from the override distance, not the raw telematics value.
		self._day(self.v1, "2026-06-01", 0, 500, 500, corrected=200, dead=50)
		r = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}[self.v1]
		self.assertEqual(r["distance_km"], 150)  # 200 override - 50 dead

	def test_payload_surfaces_dead_km(self):
		self._day(self.v1, "2026-06-01", 0, 100, 100, dead=40)
		payload = km_report.build_report_payload(self.customer, "2026-06")
		self.assertEqual(payload["totals"]["dead_km"], 40)
		self.assertEqual(payload["totals"]["billable_km"], 60)
		veh = {v["registration"]: v for v in payload["vehicles"]}[self.v1]
		self.assertEqual(veh["dead_km"], 40)
		self.assertEqual(veh["billable_km"], 60)

	def test_odometer_reset_non_negative(self):
		# Device swap → raw distance negative; billable must clamp to 0, never negative.
		self._day(self.v1, "2026-06-01", 90000, 5, -50)
		r = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}[self.v1]
		self.assertEqual(r["distance_km"], 0)

	def test_ist_month_boundary(self):
		# A row dated 30 June belongs to June; 1 July belongs to July.
		self._day(self.v1, "2026-06-30", 0, 15, 15)
		self._day(self.v1, "2026-07-01", 15, 114, 99)
		june = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}[self.v1]
		july = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-07")}[self.v1]
		self.assertEqual(june["distance_km"], 15)
		self.assertEqual(july["distance_km"], 99)

	def test_partial_month_no_crash(self):
		self._day(self.v1, "2026-06-15", 500, 540, 40)
		r = {x["vehicle"]: x for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}[self.v1]
		self.assertEqual(r["distance_km"], 40)
		self.assertEqual(r["days_with_data"], 1)
		# vehicle with no data still returns a zero row (not missing)
		self.assertIn(
			self.v2, {x["vehicle"] for x in km_report.month_vehicle_rollup(self.customer, "2026-06")}
		)

	def test_week_and_day_breakdown(self):
		self._day(self.v1, "2026-06-01", 0, 10, 10)
		self._day(self.v1, "2026-06-02", 10, 25, 15)
		days = km_report.day_breakdown(self.v1, "2026-06")
		self.assertEqual(len(days), 2)
		self.assertEqual(days[0]["distance_km"], 10)
		weeks = km_report.week_breakdown(self.v1, "2026-06")
		self.assertTrue(weeks)
		self.assertEqual(sum(w["distance_km"] for w in weeks), 25)

	def test_month_on_month_trend(self):
		self._day(self.v1, "2026-06-10", 0, 100, 100)
		self._day(self.v1, "2026-05-10", 0, 50, 50)
		trend = km_report.month_on_month(self.customer, "2026-06", n=6)
		self.assertEqual(len(trend), 6)
		self.assertEqual(trend[-1]["month"], "2026-06")
		by_month = {t["month"]: t["billable_km"] for t in trend}
		self.assertEqual(by_month["2026-06"], 100)
		self.assertEqual(by_month["2026-05"], 50)

	# ── PII-safe payload ──

	def test_payload_is_pii_safe(self):
		self._day(self.v1, "2026-06-01", 0, 42, 42)
		payload = km_report.build_report_payload(self.customer, "2026-06", generated_at="2026-07-01 10:00:00")
		self.assertEqual(payload["report_month"], "2026-06")
		self.assertEqual(payload["month_label"], "June 2026")
		self.assertEqual(payload["totals"]["billable_km"], 42)
		veh = payload["vehicles"][0]
		self.assertIn("registration", veh)
		# leakage guard: no operator / user / device / customer-PK / correction-author fields
		serialized = frappe.as_json(payload).lower()
		for leak in (
			"operator",
			"device_id",
			"device-id",
			"corrected_by",
			"naarni_vehicle_id",
			"mobile_no",
			"email",
		):
			self.assertNotIn(leak, serialized, msg=f"payload leaks '{leak}'")
