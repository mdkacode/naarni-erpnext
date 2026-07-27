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
		# Reset the single to a known baseline. The heartbeat is a Single, so state
		# leaks between tests unless every field the watchdog reads is cleared here.
		hb = frappe.get_single("Alert Engine Heartbeat")
		hb.down_notified = 0
		hb.consecutive_bad = 0
		hb.last_notified_at = None
		hb.rules_loaded = 12
		hb.teams_enabled = 1
		hb.last_errors = 0
		hb.last_ingest_at = None
		hb.last_timer_at = None
		hb.last_beat_source = None
		hb.last_error_at = None
		hb.last_error_context = None
		hb.last_error_trace = None
		hb.save(ignore_permissions=True)

	def tearDown(self):
		frappe.sendmail = self._orig_sendmail
		frappe.local.conf.pop("alert_watchdog_confirm_checks", None)

	def _set_last_seen(self, seconds_ago):
		hb = frappe.get_single("Alert Engine Heartbeat")
		hb.last_seen = add_to_date(now_datetime(), seconds=-seconds_ago) if seconds_ago is not None else None
		hb.save(ignore_permissions=True)

	def _set_clocks(self, *, beat_secs_ago, ingest_secs_ago, self_timed=True):
		"""Drive the two clocks independently: `beat` is the newest beat of any kind
		(engine liveness), `ingest` the newest telemetry cycle."""
		hb = frappe.get_single("Alert Engine Heartbeat")
		now = now_datetime()
		hb.last_seen = add_to_date(now, seconds=-beat_secs_ago)
		hb.last_ingest_at = (
			add_to_date(now, seconds=-ingest_secs_ago) if ingest_secs_ago is not None else None
		)
		hb.last_timer_at = add_to_date(now, seconds=-beat_secs_ago) if self_timed else None
		hb.last_beat_source = "timer" if self_timed else "ingest"
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

	# ---------------------------------------------------------------- two clocks
	# The point of the split: "engine down" and "telemetry stopped" are different
	# outages with different owners, and the page has to say which one it is.

	def test_telemetry_stall_blames_the_dag_not_the_engine(self):
		# Engine self-timer is fresh, but no ingest cycle for 15 min: the engine is
		# healthy and idle — this is an Airflow problem, and the mail must say so.
		self._set_clocks(beat_secs_ago=30, ingest_secs_ago=900)
		tasks.monitor_alert_engine()
		self.assertEqual(len(self.sent), 1)
		# The subject alone has to route this to the data team, not to infra.
		self.assertIn("telemetry ingest STOPPED", self.sent[0]["subject"])
		body = self.sent[0]["message"]
		self.assertIn("TELEMETRY INGEST HAS STOPPED", body)
		self.assertIn("Check the DAG", body)
		self.assertNotIn("ALERT ENGINE IS DOWN", body)

	def test_engine_down_named_when_the_self_timer_is_silent_too(self):
		# Nothing at all for 10 min, self-timer included — the process itself is gone.
		self._set_clocks(beat_secs_ago=600, ingest_secs_ago=600)
		tasks.monitor_alert_engine()
		self.assertEqual(len(self.sent), 1)
		body = self.sent[0]["message"]
		self.assertIn("ALERT ENGINE IS DOWN", body)
		self.assertIn("self-timer", body)
		self.assertNotIn("TELEMETRY INGEST HAS STOPPED", body)

	def test_pre_0_9_6_engine_keeps_the_old_combined_wording(self):
		# No self-timed beat has ever arrived, so the two clocks are the same number
		# and the mail must not claim to know which half failed.
		self._set_clocks(beat_secs_ago=600, ingest_secs_ago=600, self_timed=False)
		tasks.monitor_alert_engine()
		self.assertEqual(len(self.sent), 1)
		body = self.sent[0]["message"]
		self.assertIn("Heartbeat is STALE", body)
		self.assertNotIn("ALERT ENGINE IS DOWN", body)

	def test_slow_ingest_within_threshold_is_not_a_page(self):
		# Ingest at 7 min is late but under the 10-min telemetry threshold — a slow
		# DAG run is normal and must not page.
		self._set_clocks(beat_secs_ago=30, ingest_secs_ago=420)
		tasks.monitor_alert_engine()
		self.assertEqual(self.sent, [])

	def test_page_carries_the_engine_traceback_and_both_clocks(self):
		hb = frappe.get_single("Alert Engine Heartbeat")
		hb.last_error_context = "novu trigger failed for 16:overspeed"
		hb.last_error_trace = "Traceback (most recent call last):\n  RuntimeError: novu 502 bad gateway"
		hb.save(ignore_permissions=True)
		self._set_clocks(beat_secs_ago=600, ingest_secs_ago=600)
		tasks.monitor_alert_engine()
		body = self.sent[0]["message"]
		self.assertIn("novu trigger failed for 16:overspeed", body)
		self.assertIn("RuntimeError: novu 502 bad gateway", body)
		# Both clocks are in the state table, so the reader can see which one stopped.
		self.assertIn("Telemetry ingest cycle", body)
		self.assertIn("Engine self-timer", body)

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


class TestEngineHealthProblems(FrappeTestCase):
	"""The decision itself, with no DB and no email — every wording branch pinned."""

	def _problems(self, **kw):
		base = dict(
			has_heartbeat=True,
			age_secs=10,
			rules_loaded=12,
			teams_enabled=True,
			last_errors=0,
			stale_secs=300,
			ingest_age_secs=10,
			ingest_stale_secs=600,
			self_timed=True,
		)
		return tasks.engine_health_problems(**{**base, **kw})

	def test_healthy_is_empty(self):
		self.assertEqual(self._problems(), [])

	def test_engine_stale_with_self_timer_is_engine_down(self):
		(msg,) = self._problems(age_secs=600, ingest_age_secs=600)
		self.assertIn("ALERT ENGINE IS DOWN", msg)

	def test_engine_stale_without_self_timer_stays_ambiguous(self):
		(msg,) = self._problems(age_secs=600, ingest_age_secs=600, self_timed=False)
		self.assertIn("Heartbeat is STALE", msg)

	def test_fresh_engine_stale_ingest_is_telemetry(self):
		(msg,) = self._problems(age_secs=10, ingest_age_secs=900)
		self.assertIn("TELEMETRY INGEST HAS STOPPED", msg)

	def test_engine_down_wins_over_telemetry(self):
		# Both clocks stale: report the engine, not the symptom. A dead engine also
		# stops ingest, so listing both would just add noise to the page.
		problems = self._problems(age_secs=900, ingest_age_secs=900)
		self.assertEqual(len(problems), 1)
		self.assertIn("ALERT ENGINE IS DOWN", problems[0])

	def test_unknown_ingest_clock_never_pages(self):
		# No ingest beat has ever landed (fresh install, engine timer beat first) —
		# we don't know when telemetry last worked, so we must not page on a guess.
		self.assertEqual(self._problems(ingest_age_secs=None), [])

	def test_other_problems_still_reported_alongside(self):
		problems = self._problems(rules_loaded=0, teams_enabled=False, last_errors=3)
		self.assertEqual(len(problems), 3)
