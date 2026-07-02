"""KM daily sync: idempotent upsert that never clobbers operator corrections.

Run:
    bench --site <site> run-tests --module \
      vehicle_maintenance.integrations.test_naarni_km_daily
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.integrations import naarni_client, naarni_km_daily


class TestKmDailySync(FrappeTestCase):
	def setUp(self):
		# Pretend the integration is on and stub the HTTP client — no network.
		self._orig_enabled = naarni_client.is_enabled
		self._orig_fetch = naarni_client.fetch_km_daily
		naarni_client.is_enabled = lambda: True
		# Neutralise the sync's commit so test rows roll back (FrappeTestCase does not
		# disable commit; a real commit here would pollute the dev site).
		self._orig_commit = frappe.db.commit
		frappe.db.commit = lambda *a, **k: None
		self.vehicle = self._ensure_vehicle("KMDAILY-T1", "990001")

	def tearDown(self):
		naarni_client.is_enabled = self._orig_enabled
		naarni_client.fetch_km_daily = self._orig_fetch
		frappe.db.commit = self._orig_commit

	def _ensure_vehicle(self, reg, naarni_id):
		name = frappe.db.get_value("Vehicle", {"naarni_vehicle_id": naarni_id}, "name")
		if name:
			return name
		v = frappe.new_doc("Vehicle")
		v.registration_number = reg
		v.make_model = "Test EV"
		v.naarni_vehicle_id = naarni_id
		v.flags.ignore_permissions = True
		v.insert(ignore_permissions=True)
		return v.name

	def _stub(self, rows, date_basis="IST"):
		naarni_client.fetch_km_daily = lambda vehicle_ids, start, end: {
			"rows": rows,
			"dateBasis": date_basis,
		}

	def _row(self, name, date):
		return frappe.db.get_value("Vehicle KM Daily", {"vehicle": self.vehicle, "date": date}, name)

	def test_creates_then_updates_idempotently(self):
		self._stub([_tel("990001", "2026-06-01", 1000, 1050, 50)])
		s1 = naarni_km_daily.sync_km_daily(lookback_days=3)
		self.assertEqual(s1["created"], 1)
		name = self._row("name", "2026-06-01")
		self.assertTrue(name)

		# Re-sync the same day with a new distance → update, not a duplicate.
		self._stub([_tel("990001", "2026-06-01", 1000, 1060, 60)])
		s2 = naarni_km_daily.sync_km_daily(lookback_days=3)
		self.assertEqual(s2["created"], 0)
		self.assertEqual(s2["updated"], 1)
		self.assertEqual(frappe.db.get_value("Vehicle KM Daily", name, "distance_km"), 60)

	def test_resync_preserves_corrections(self):
		self._stub([_tel("990001", "2026-06-02", 2000, 2100, 100)])
		naarni_km_daily.sync_km_daily()
		name = self._row("name", "2026-06-02")

		doc = frappe.get_doc("Vehicle KM Daily", name)
		doc.is_excluded = 1
		doc.exclusion_reason = "Service"
		doc.corrected_distance = 0
		doc.save(ignore_permissions=True)

		# A later re-sync must refresh raw fields but leave the correction intact.
		self._stub([_tel("990001", "2026-06-02", 2000, 2200, 200)])
		naarni_km_daily.sync_km_daily()
		doc.reload()
		self.assertEqual(doc.is_excluded, 1)
		self.assertEqual(doc.exclusion_reason, "Service")
		self.assertEqual(doc.corrected_distance, 0)
		self.assertEqual(doc.distance_km, 200)  # raw refreshed
		self.assertEqual(doc.effective_distance, 0.0)  # correction wins
		self.assertTrue(doc.corrected_by)  # attribution stamped

	def test_unmapped_vehicle_skipped(self):
		self._stub([_tel("does-not-exist", "2026-06-03", 1, 11, 10)])
		s = naarni_km_daily.sync_km_daily()
		self.assertEqual(s["skipped"], 1)
		self.assertEqual(s["created"], 0)

	def test_disabled_is_noop(self):
		naarni_client.is_enabled = lambda: False
		s = naarni_km_daily.sync_km_daily()
		self.assertIn("skipped", s)
		self.assertNotIn("created", s)

	def test_utc_basis_recorded_on_row(self):
		self._stub([_tel("990001", "2026-06-04", 1, 2, 1)], date_basis="UTC")
		naarni_km_daily.sync_km_daily()
		name = self._row("name", "2026-06-04")
		self.assertEqual(frappe.db.get_value("Vehicle KM Daily", name, "date_basis"), "UTC")


def _tel(vehicle_id, date, start, end, dist, inactive=0):
	return {
		"vehicleId": vehicle_id,
		"date": date,
		"startOdo": start,
		"endOdo": end,
		"distTravelledKm": dist,
		"isInactive": inactive,
	}
