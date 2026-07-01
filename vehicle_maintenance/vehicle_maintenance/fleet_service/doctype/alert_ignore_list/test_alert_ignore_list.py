"""Tests for the central Alert Ignore List.

Run on a bench:
    bench --site <site> run-tests --module \
      vehicle_maintenance.fleet_service.doctype.alert_ignore_list.test_alert_ignore_list
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.fleet_service.doctype.alert_ignore_list.alert_ignore_list import (
	ignored_vehicles,
	muted_device_ids,
)

TEST_VEHICLES = {
	"_TEST_IGN_BUS_A": "9000001",
	"_TEST_IGN_BUS_B": "9000002",
	"_TEST_IGN_BUS_NODEV": "",  # no device_id -> excluded from muted_devices
}


def _ensure_vehicle(name: str, device_id: str) -> str:
	"""Create a minimal Vehicle for the test, tolerating whatever mandatory fields
	the site defines (only name + device_id matter here)."""
	if frappe.db.exists("Vehicle", name):
		doc = frappe.get_doc("Vehicle", name)
		doc.device_id = device_id or None
		doc.save(ignore_permissions=True)
		return name
	doc = frappe.new_doc("Vehicle")
	# Vehicle is likely autonamed by registration_number; force our test name.
	doc.registration_number = name
	if device_id:
		doc.device_id = device_id
	doc.flags.name_set = True
	doc.name = name
	doc.insert(ignore_permissions=True, ignore_mandatory=True)
	return doc.name


class TestAlertIgnoreList(FrappeTestCase):
	@classmethod
	def setUpClass(cls):
		super().setUpClass()
		cls._names = [_ensure_vehicle(n, d) for n, d in TEST_VEHICLES.items()]

	def _set_list(self, vehicle_names):
		doc = frappe.get_single("Alert Ignore List")
		doc.vehicles = []
		for vn in vehicle_names:
			doc.append("vehicles", {"vehicle": vn})
		doc.save(ignore_permissions=True)
		return doc

	def tearDown(self):
		# Reset the single doc between tests so cases don't leak into each other.
		self._set_list([])

	def test_empty_list_mutes_nothing(self):
		self._set_list([])
		self.assertEqual(ignored_vehicles(), [])
		self.assertEqual(muted_device_ids(), [])

	def test_selected_buses_resolve_to_device_ids(self):
		self._set_list(["_TEST_IGN_BUS_A", "_TEST_IGN_BUS_B"])
		self.assertEqual(set(ignored_vehicles()), {"_TEST_IGN_BUS_A", "_TEST_IGN_BUS_B"})
		self.assertEqual(set(muted_device_ids()), {"9000001", "9000002"})

	def test_bus_without_device_id_is_skipped(self):
		# On the ignore list but has no telemetry device -> not in muted_devices.
		self._set_list(["_TEST_IGN_BUS_NODEV"])
		self.assertEqual(ignored_vehicles(), ["_TEST_IGN_BUS_NODEV"])
		self.assertEqual(muted_device_ids(), [])

	def test_duplicate_bus_is_rejected(self):
		doc = frappe.get_single("Alert Ignore List")
		doc.vehicles = []
		doc.append("vehicles", {"vehicle": "_TEST_IGN_BUS_A"})
		doc.append("vehicles", {"vehicle": "_TEST_IGN_BUS_A"})
		with self.assertRaises(frappe.ValidationError):
			doc.save(ignore_permissions=True)

	def test_muted_device_ids_are_deduped(self):
		# Two vehicles sharing a device_id should collapse to one muted id.
		_ensure_vehicle("_TEST_IGN_BUS_DUP", "9000001")
		self.addCleanup(frappe.delete_doc, "Vehicle", "_TEST_IGN_BUS_DUP", force=True)
		self._set_list(["_TEST_IGN_BUS_A", "_TEST_IGN_BUS_DUP"])
		self.assertEqual(muted_device_ids(), ["9000001"])

	def test_engine_config_includes_muted_devices(self):
		# End-to-end: the compiled engine config carries the ignored device_ids.
		from vehicle_maintenance.api import alerts

		self._set_list(["_TEST_IGN_BUS_A"])
		# Ensure a service key is present so the whitelisted call authorises.
		key = frappe.conf.get("alert_engine_service_key")
		restore = key is None
		if restore:
			key = "_test_engine_key"
			frappe.conf["alert_engine_service_key"] = key
		try:
			result = alerts.get_engine_config(service_key=key)
		finally:
			if restore:
				frappe.conf.pop("alert_engine_service_key", None)
		payload = result.get("data", result) if isinstance(result, dict) else {}
		self.assertIn("9000001", payload.get("muted_devices", []))
