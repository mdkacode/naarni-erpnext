"""Watchdog E2E: heartbeat state -> email + recovery + throttle.

Run on a bench:
    bench --site <site> run-tests --module \
      vehicle_maintenance.fleet_service.doctype.alert_engine_heartbeat.test_alert_engine_heartbeat
"""

import frappe
from frappe.tests.utils import FrappeTestCase
from frappe.utils import add_to_date, now_datetime

from vehicle_maintenance.fleet_service import tasks


class TestAlertEngineWatchdog(FrappeTestCase):
	def setUp(self):
		self.sent = []
		self._orig_sendmail = frappe.sendmail
		frappe.sendmail = lambda **kw: self.sent.append(kw)
		# These tests exercise the email/throttle/recovery mechanics, not the
		# debounce — so require just one bad check to page (the debounce itself is
		# covered by test_brief_flap_* / test_sustained_stale_* below).
		frappe.local.conf["alert_watchdog_confirm_checks"] = 1
		# Reset the single to a known baseline.
		hb = frappe.get_single("Alert Engine Heartbeat")
		hb.down_notified = 0
		hb.consecutive_bad = 0
		hb.last_notified_at = None
		hb.rules_loaded = 12
		hb.teams_enabled = 1
		hb.last_errors = 0
		hb.save(ignore_permissions=True)

	def tearDown(self):
		frappe.sendmail = self._orig_sendmail
		frappe.local.conf.pop("alert_watchdog_confirm_checks", None)

	def _set_last_seen(self, seconds_ago):
		hb = frappe.get_single("Alert Engine Heartbeat")
		hb.last_seen = add_to_date(now_datetime(), seconds=-seconds_ago) if seconds_ago is not None else None
		hb.save(ignore_permissions=True)

	def test_never_seen_is_unarmed_no_email(self):
		# Before the engine ever posts a heartbeat, the switch is unarmed — no email,
		# so Frappe can be migrated before engine >= 0.9.5 is deployed.
		self._set_last_seen(None)
		tasks.monitor_alert_engine()
		self.assertEqual(self.sent, [])
		hb = frappe.get_single("Alert Engine Heartbeat")
		self.assertEqual(hb.down_notified, 0)
		self.assertIn("Unarmed", hb.last_status or "")

	def test_fresh_heartbeat_sends_nothing(self):
		self._set_last_seen(30)
		tasks.monitor_alert_engine()
		self.assertEqual(self.sent, [])
		self.assertEqual(frappe.get_single("Alert Engine Heartbeat").down_notified, 0)

	def test_stale_heartbeat_emails_and_marks_down(self):
		self._set_last_seen(600)  # 10 min old, threshold 300
		tasks.monitor_alert_engine()
		self.assertEqual(len(self.sent), 1)
		self.assertIn("DOWN", self.sent[0]["subject"])
		# recipient defaults to the ops address
		self.assertIn("mayank.dwivedi@naarni.com", self.sent[0]["recipients"])
		self.assertEqual(frappe.get_single("Alert Engine Heartbeat").down_notified, 1)

	def test_throttle_no_duplicate_email_while_down(self):
		self._set_last_seen(600)
		tasks.monitor_alert_engine()  # 1st email
		tasks.monitor_alert_engine()  # still down, just after -> throttled
		self.assertEqual(len(self.sent), 1)

	def test_recovery_email_when_back(self):
		self._set_last_seen(600)
		tasks.monitor_alert_engine()  # down email
		self.sent.clear()
		self._set_last_seen(20)  # fresh again
		tasks.monitor_alert_engine()  # recovery
		self.assertEqual(len(self.sent), 1)
		self.assertIn("recovered", self.sent[0]["subject"].lower())
		self.assertEqual(frappe.get_single("Alert Engine Heartbeat").down_notified, 0)

	def test_zero_rules_flagged_even_when_fresh(self):
		self._set_last_seen(20)
		hb = frappe.get_single("Alert Engine Heartbeat")
		hb.rules_loaded = 0
		hb.save(ignore_permissions=True)
		tasks.monitor_alert_engine()
		self.assertEqual(len(self.sent), 1)

	def test_brief_flap_suppressed_by_debounce(self):
		# Default debounce (2 checks): a single stale check must NOT page — this is
		# the fix for the every-minute-ingest heartbeat flapping on a brief gap.
		frappe.local.conf["alert_watchdog_confirm_checks"] = 2
		self._set_last_seen(600)  # stale
		tasks.monitor_alert_engine()  # 1st bad check -> pending, no email
		self.assertEqual(self.sent, [])
		hb = frappe.get_single("Alert Engine Heartbeat")
		self.assertEqual(hb.consecutive_bad, 1)
		self.assertEqual(hb.down_notified, 0)
		self.assertIn("pending 1/2", hb.last_status or "")
		# A beat arrives before the next check -> streak clears, still no email.
		self._set_last_seen(20)
		tasks.monitor_alert_engine()
		self.assertEqual(self.sent, [])
		self.assertEqual(frappe.get_single("Alert Engine Heartbeat").consecutive_bad, 0)

	def test_sustained_stale_pages_after_confirm(self):
		# Two consecutive stale checks (a real sustained outage) DOES page.
		frappe.local.conf["alert_watchdog_confirm_checks"] = 2
		self._set_last_seen(600)
		tasks.monitor_alert_engine()  # 1/2 -> pending
		self.assertEqual(self.sent, [])
		self._set_last_seen(720)  # still stale on the next run
		tasks.monitor_alert_engine()  # 2/2 -> page
		self.assertEqual(len(self.sent), 1)
		self.assertIn("DOWN", self.sent[0]["subject"])
		self.assertEqual(frappe.get_single("Alert Engine Heartbeat").down_notified, 1)
