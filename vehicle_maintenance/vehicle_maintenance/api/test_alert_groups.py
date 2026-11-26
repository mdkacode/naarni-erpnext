"""Alerts (new) feed filters: time range, alert type and vehicle.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_alert_groups

A group is one bus x one issue (`dedup_key`). With a time range, a group's count and
latest occurrence come only from the alerts inside that range, so "last 7 days" means
exactly that. Every filter stays inside the Service Engineer's depots.
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import tickets


class TestAlertGroupFilters(FrappeTestCase):
	def setUp(self):
		frappe.set_user("Administrator")
		self._orig_commit = frappe.db.commit
		frappe.db.commit = lambda *a, **k: None
		# Inserting an Alert Event queues its chat broadcast; the feed does not need it,
		# and a test bench may have no queue running.
		self._orig_enqueue = frappe.enqueue
		frappe.enqueue = lambda *a, **k: None
		self.se = self._ensure_user("alert-filter-se@test.localhost", "9990287341")
		self.depot = self._ensure_depot("Alert Filter Depot", "ALFDEP", self.se)
		self.other_depot = self._ensure_depot("Alert Filter Other Depot", "ALFOTH", None)
		self.bus1 = self._ensure_vehicle("ALF-TEST-01", self.depot)
		self.bus2 = self._ensure_vehicle("ALF-TEST-02", self.depot)
		self.outsider = self._ensure_vehicle("ALF-TEST-99", self.other_depot)
		self.hot = self._ensure_alert_type("ALF Battery temperature high")
		self.low = self._ensure_alert_type("ALF Brake air low")
		frappe.db.delete("Alert Event", {"vehicle": ["in", [self.bus1, self.bus2, self.outsider]]})
		# bus1 x hot: two alerts in November, one in October.
		self._alert(self.bus1, self.hot, "2026-11-20 09:00:00")
		self._alert(self.bus1, self.hot, "2026-11-22 18:30:00")
		self._alert(self.bus1, self.hot, "2026-10-05 10:00:00")
		# bus2 x low: one alert in November.
		self._alert(self.bus2, self.low, "2026-11-21 07:15:00")
		# A bus outside the SE's depots: never visible.
		self._alert(self.outsider, self.hot, "2026-11-21 08:00:00")

	def tearDown(self):
		frappe.set_user("Administrator")
		frappe.db.commit = self._orig_commit
		frappe.enqueue = self._orig_enqueue

	# -- fixtures ---------------------------------------------------------------

	def _ensure_user(self, email, mobile):
		if not frappe.db.exists("User", email):
			u = frappe.new_doc("User")
			u.email, u.first_name, u.mobile_no = email, "Alert Filter SE", mobile
			u.send_welcome_email = 0
			u.insert(ignore_permissions=True)
			u.append("roles", {"role": "Service Engineer"})
			u.save(ignore_permissions=True)
		return email

	def _ensure_depot(self, name, code, engineer):
		if not frappe.db.exists("Depot", name):
			doc = {"doctype": "Depot", "depot_name": name, "location_code": code}
			if engineer:
				doc["service_engineers"] = [{"user": engineer}]
			frappe.get_doc(doc).insert(ignore_permissions=True)
		return name

	def _ensure_vehicle(self, reg, depot):
		if not frappe.db.exists("Vehicle", reg):
			frappe.get_doc(
				{"doctype": "Vehicle", "registration_number": reg, "make_model": "Test Bus", "depot": depot}
			).insert(ignore_permissions=True)
		return reg

	def _ensure_alert_type(self, label):
		existing = frappe.db.get_value("Alert Type", {"alert_name": label}, "name")
		if existing:
			return existing
		doc = frappe.get_doc({"doctype": "Alert Type", "alert_name": label, "severity": "critical"})
		# The feed only needs the type's name and label, not a working rule (which
		# would need Telemetry Parameter fixtures), so the rule checks are skipped.
		doc.flags.ignore_validate = True
		return doc.insert(ignore_permissions=True).name

	def _alert(self, vehicle, alert_type, at):
		label = frappe.db.get_value("Alert Type", alert_type, "alert_name")
		return frappe.get_doc(
			{
				"doctype": "Alert Event",
				"title": label,
				"alert_type": alert_type,
				"severity": "critical",
				"status": "Open",
				"vehicle": vehicle,
				"registration_number": vehicle,
				"dedup_key": f"{vehicle}:{alert_type}",
				"triggered_at": at,
				"message": label,
			}
		).insert(ignore_permissions=True)

	def _groups(self, **filters):
		frappe.set_user(self.se)
		try:
			return {g["dedup_key"]: g for g in tickets.get_my_alert_groups(**filters)["data"]}
		finally:
			frappe.set_user("Administrator")

	# -- the feed -----------------------------------------------------------------

	def test_without_filters_every_group_at_the_depot_counts_all_time(self):
		groups = self._groups()
		self.assertEqual(set(groups), {f"{self.bus1}:{self.hot}", f"{self.bus2}:{self.low}"})
		self.assertEqual(groups[f"{self.bus1}:{self.hot}"]["occurrence_count"], 3)

	def test_time_range_counts_and_dates_only_alerts_inside_it(self):
		groups = self._groups(from_date="2026-11-20", to_date="2026-11-21")
		bus1 = groups[f"{self.bus1}:{self.hot}"]
		# 20 Nov counts; 22 Nov and 5 Oct are outside the range.
		self.assertEqual(bus1["occurrence_count"], 1)
		self.assertTrue(bus1["latest_time"].startswith("2026-11-20 09:00"))
		self.assertIn(f"{self.bus2}:{self.low}", groups)

	def test_the_to_date_includes_its_whole_day(self):
		groups = self._groups(from_date="2026-11-22", to_date="2026-11-22")
		self.assertEqual(set(groups), {f"{self.bus1}:{self.hot}"})
		self.assertTrue(groups[f"{self.bus1}:{self.hot}"]["latest_time"].startswith("2026-11-22 18:30"))

	def test_a_range_with_no_alerts_returns_no_groups(self):
		self.assertEqual(self._groups(from_date="2026-09-01", to_date="2026-09-30"), {})

	def test_alert_type_keeps_only_that_issue(self):
		groups = self._groups(alert_type=self.low)
		self.assertEqual(set(groups), {f"{self.bus2}:{self.low}"})

	def test_vehicle_keeps_only_that_bus(self):
		groups = self._groups(vehicle=self.bus1)
		self.assertEqual(set(groups), {f"{self.bus1}:{self.hot}"})

	def test_a_vehicle_outside_the_engineers_depots_shows_nothing(self):
		self.assertEqual(self._groups(vehicle=self.outsider), {})

	def test_filters_combine(self):
		groups = self._groups(
			from_date="2026-11-01", to_date="2026-11-30", alert_type=self.hot, vehicle=self.bus1
		)
		self.assertEqual(groups[f"{self.bus1}:{self.hot}"]["occurrence_count"], 2)

	def test_bad_dates_are_refused(self):
		frappe.set_user(self.se)
		try:
			with self.assertRaises(frappe.ValidationError):
				tickets.get_my_alert_groups(from_date="21-11-2026")
			with self.assertRaises(frappe.ValidationError):
				tickets.get_my_alert_groups(from_date="2026-11-22", to_date="2026-11-21")
		finally:
			frappe.set_user("Administrator")

	# -- the filter choices ---------------------------------------------------------

	def test_choices_are_the_engineers_vehicles_and_the_types_raised_on_them(self):
		frappe.set_user(self.se)
		try:
			data = tickets.get_my_alert_filter_options()["data"]
		finally:
			frappe.set_user("Administrator")
		vehicles = [v["value"] for v in data["vehicles"]]
		self.assertIn(self.bus1, vehicles)
		self.assertIn(self.bus2, vehicles)
		self.assertNotIn(self.outsider, vehicles)
		self.assertEqual(vehicles, sorted(vehicles))
		types = {t["value"]: t["label"] for t in data["alert_types"]}
		self.assertEqual(types.get(self.hot), "ALF Battery temperature high")
		self.assertEqual(types.get(self.low), "ALF Brake air low")

	def test_choices_for_an_engineer_without_a_depot_are_empty(self):
		lone = self._ensure_user("alert-filter-lone@test.localhost", "9990287342")
		frappe.set_user(lone)
		try:
			data = tickets.get_my_alert_filter_options()["data"]
		finally:
			frappe.set_user("Administrator")
		self.assertEqual(data, {"vehicles": [], "alert_types": []})
