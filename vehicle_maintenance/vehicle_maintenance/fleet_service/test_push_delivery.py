# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Tests for the guaranteed-delivery push pipeline.

The point of these is the *failure* paths. A push that works is easy; what broke
in the field was a transient FCM error silently discarding an alert, a worker
dying mid-send, and a payload shape that stopped the handset's own code from
ever running. Each of those has a test here.

The manual commits carry `# nosemgrep` throughout. The code under test commits
its own state transitions and then reads them back — that durability *is* the
behaviour being tested — so a fixture left in the test's uncommitted transaction
would be invisible to it. These are commits in a test, not in a request path.
"""

from unittest.mock import patch

import frappe
from frappe.tests.utils import FrappeTestCase
from frappe.utils import add_to_date, now_datetime

from vehicle_maintenance.fleet_service import push_delivery


class _Resp:
	"""Minimal stand-in for a `requests` response."""

	def __init__(self, status_code=200, text="", payload=None):
		self.status_code = status_code
		self.text = text
		self._payload = payload if payload is not None else {"name": "projects/x/messages/1"}

	def json(self):
		return self._payload


class TestPushDelivery(FrappeTestCase):
	def setUp(self):
		self.user = "push-test@example.com"
		if not frappe.db.exists("User", self.user):
			frappe.get_doc(
				{
					"doctype": "User",
					"email": self.user,
					"first_name": "Push",
					"mobile_no": "9990000911",
					"send_welcome_email": 0,
				}
			).insert(ignore_permissions=True, ignore_mandatory=True)

		frappe.db.delete("Push Token", {"user": self.user})
		frappe.db.delete("Push Delivery", {"user": self.user})
		frappe.get_doc(
			{
				"doctype": "Push Token",
				"user": self.user,
				"device_token": "TOKEN-A",
				"platform": "android",
				"is_active": 1,
			}
		).insert(ignore_permissions=True)
		frappe.db.commit()  # nosemgrep

	def tearDown(self):
		frappe.db.delete("Push Delivery", {"user": self.user})
		frappe.db.delete("Push Token", {"user": self.user})
		frappe.db.commit()  # nosemgrep

	# ── queueing ──

	def test_queue_is_a_noop_when_push_is_disabled(self):
		with patch.object(push_delivery, "is_enabled", return_value=False):
			self.assertEqual(push_delivery.queue_push(self.user, "s", "b"), [])

	def test_ledger_row_is_written_before_any_network_call(self):
		"""The durability guarantee: the row exists even if the enqueue fails."""
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue", side_effect=Exception("redis down")),
		):
			names = push_delivery.queue_push(
				self.user, "Breakdown", "bus 1", reference_name="TKT-1", priority="Critical"
			)
		self.assertEqual(len(names), 1)
		row = frappe.get_doc("Push Delivery", names[0])
		self.assertEqual(row.status, "Queued")
		self.assertEqual(row.urgent, 1)

	def test_warning_severity_is_still_urgent(self):
		"""A warning alert arrives as 'Medium'. It must not be downgraded."""
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue"),
		):
			names = push_delivery.queue_push(self.user, "Warn", "b", priority="Medium")
		self.assertEqual(frappe.db.get_value("Push Delivery", names[0], "urgent"), 1)

		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue"),
		):
			low = push_delivery.queue_push(self.user, "Chatter", "b", priority="Low")
		self.assertEqual(frappe.db.get_value("Push Delivery", low[0], "urgent"), 0)

	def test_duplicate_tokens_produce_one_delivery(self):
		frappe.get_doc(
			{
				"doctype": "Push Token",
				"user": self.user,
				"device_token": "TOKEN-A",
				"platform": "android",
				"is_active": 1,
			}
		).insert(ignore_permissions=True)
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue"),
		):
			names = push_delivery.queue_push(self.user, "s", "b")
		self.assertEqual(len(names), 1)

	# ── delivery ──

	def _queue_one(self, priority="Critical"):
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue"),
		):
			return push_delivery.queue_push(
				self.user, "Breakdown", "bus 1", reference_name="TKT-1", priority=priority
			)[0]

	def test_successful_send_marks_sent_once(self):
		name = self._queue_one()
		with patch.object(push_delivery, "_send", return_value=(True, False, "msg-1")):
			push_delivery.deliver(name)
		row = frappe.get_doc("Push Delivery", name)
		self.assertEqual(row.status, "Sent")
		self.assertEqual(row.attempts, 1)
		self.assertTrue(row.sent_at)

	def test_a_claimed_row_cannot_be_sent_twice(self):
		"""The sweeper and the original enqueue both fire; only one may send."""
		name = self._queue_one()
		with patch.object(push_delivery, "_send", return_value=(True, False, "")) as send:
			push_delivery.deliver(name)
			push_delivery.deliver(name)
		self.assertEqual(send.call_count, 1)

	def test_transient_failure_is_retried_not_dropped(self):
		"""The old path logged a 503 and lost the alert. This one reschedules."""
		name = self._queue_one()
		with patch.object(push_delivery, "_send", return_value=(False, False, "503: busy")):
			push_delivery.deliver(name)
		row = frappe.get_doc("Push Delivery", name)
		self.assertEqual(row.status, "Retrying")
		self.assertEqual(row.attempts, 1)
		self.assertTrue(row.next_attempt_at)

	def test_retries_are_exhausted_into_dead_not_looped_forever(self):
		name = self._queue_one()
		with patch.object(push_delivery, "_send", return_value=(False, False, "503")):
			for _ in range(len(push_delivery.BACKOFF_URGENT) + 2):
				frappe.db.set_value(
					"Push Delivery", name, "next_attempt_at", now_datetime(), update_modified=False
				)
				push_delivery.deliver(name)
		self.assertEqual(frappe.db.get_value("Push Delivery", name, "status"), "Dead")

	def test_dead_token_is_deactivated_and_not_retried(self):
		name = self._queue_one()
		with patch.object(push_delivery, "_send", return_value=(False, True, "404: UNREGISTERED")):
			push_delivery.deliver(name)
		self.assertEqual(frappe.db.get_value("Push Delivery", name, "status"), "Dead")
		self.assertEqual(frappe.db.get_value("Push Token", {"device_token": "TOKEN-A"}, "is_active"), 0)

	def test_send_raising_does_not_lose_the_row(self):
		"""An exception out of the sender must land as a retry, not a lost job."""
		name = self._queue_one()
		with patch.object(push_delivery, "_send", side_effect=RuntimeError("boom")):
			push_delivery.deliver(name)
		self.assertEqual(frappe.db.get_value("Push Delivery", name, "status"), "Retrying")

	# ── payload shape ──

	def test_payload_is_data_only(self):
		"""No `notification` block, or `onMessageReceived` never runs in background."""
		name = self._queue_one()
		row = frappe.db.get_value("Push Delivery", name, "*", as_dict=True)
		captured = {}

		def fake_post(url, **kwargs):
			captured.update(kwargs.get("json") or {})
			return _Resp()

		with (
			patch(
				"vehicle_maintenance.fleet_service.notifications._fcm_access_token",
				return_value=("tok", "proj"),
			),
			patch("requests.post", side_effect=fake_post),
		):
			ok, permanent, _detail = push_delivery._send(row)

		self.assertTrue(ok)
		self.assertFalse(permanent)
		msg = captured["message"]
		self.assertNotIn("notification", msg)
		self.assertEqual(msg["android"]["priority"], "HIGH")
		self.assertEqual(msg["data"]["urgent"], "1")
		self.assertTrue(msg["data"]["deeplink"])

	def test_routine_priority_is_normal(self):
		name = self._queue_one(priority="Low")
		row = frappe.db.get_value("Push Delivery", name, "*", as_dict=True)
		captured = {}

		with (
			patch(
				"vehicle_maintenance.fleet_service.notifications._fcm_access_token",
				return_value=("tok", "proj"),
			),
			patch(
				"requests.post",
				side_effect=lambda url, **kw: (captured.update(kw.get("json") or {}), _Resp())[1],
			),
		):
			push_delivery._send(row)
		self.assertEqual(captured["message"]["android"]["priority"], "NORMAL")

	def test_unregistered_token_is_a_permanent_failure(self):
		name = self._queue_one()
		row = frappe.db.get_value("Push Delivery", name, "*", as_dict=True)
		with (
			patch(
				"vehicle_maintenance.fleet_service.notifications._fcm_access_token",
				return_value=("tok", "proj"),
			),
			patch("requests.post", return_value=_Resp(404, '{"error":{"status":"UNREGISTERED"}}')),
		):
			ok, permanent, _ = push_delivery._send(row)
		self.assertFalse(ok)
		self.assertTrue(permanent)

	def test_server_error_is_retryable_not_permanent(self):
		name = self._queue_one()
		row = frappe.db.get_value("Push Delivery", name, "*", as_dict=True)
		with (
			patch(
				"vehicle_maintenance.fleet_service.notifications._fcm_access_token",
				return_value=("tok", "proj"),
			),
			patch("requests.post", return_value=_Resp(503, "backend unavailable")),
		):
			ok, permanent, _ = push_delivery._send(row)
		self.assertFalse(ok)
		self.assertFalse(permanent)

	def test_transport_error_is_retryable(self):
		name = self._queue_one()
		row = frappe.db.get_value("Push Delivery", name, "*", as_dict=True)
		with (
			patch(
				"vehicle_maintenance.fleet_service.notifications._fcm_access_token",
				return_value=("tok", "proj"),
			),
			patch("requests.post", side_effect=OSError("connection reset")),
		):
			ok, permanent, detail = push_delivery._send(row)
		self.assertFalse(ok)
		self.assertFalse(permanent)
		self.assertIn("transport", detail)

	def test_unconfigured_fcm_does_not_kill_the_row(self):
		name = self._queue_one()
		with patch("vehicle_maintenance.fleet_service.notifications._fcm_access_token", return_value=None):
			push_delivery.deliver(name)
		self.assertEqual(frappe.db.get_value("Push Delivery", name, "status"), "Retrying")

	# ── the sweeper ──

	def test_sweeper_reclaims_a_row_orphaned_by_a_dead_worker(self):
		name = self._queue_one()
		frappe.db.sql(
			"""UPDATE `tabPush Delivery` SET status='Sending', modified=%(old)s WHERE name=%(n)s""",
			{"n": name, "old": add_to_date(now_datetime(), seconds=-(push_delivery.STUCK_SENDING_SEC + 60))},
		)
		frappe.db.commit()  # nosemgrep
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(push_delivery, "_try_enqueue") as enq,
			patch.object(push_delivery, "_escalate_undelivered"),
		):
			push_delivery.sweep_pending()
		self.assertEqual(frappe.db.get_value("Push Delivery", name, "status"), "Retrying")
		self.assertIn(name, [c.args[0] for c in enq.call_args_list])

	def test_sweeper_re_enqueues_a_row_whose_enqueue_never_reached_redis(self):
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue", side_effect=Exception("redis down")),
		):
			name = push_delivery.queue_push(self.user, "Breakdown", "b", priority="Critical")[0]
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(push_delivery, "_try_enqueue") as enq,
			patch.object(push_delivery, "_escalate_undelivered"),
		):
			push_delivery.sweep_pending()
		self.assertIn(name, [c.args[0] for c in enq.call_args_list])

	def test_sweeper_leaves_sent_rows_alone(self):
		name = self._queue_one()
		with patch.object(push_delivery, "_send", return_value=(True, False, "")):
			push_delivery.deliver(name)
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(push_delivery, "_try_enqueue") as enq,
			patch.object(push_delivery, "_escalate_undelivered"),
		):
			push_delivery.sweep_pending()
		self.assertNotIn(name, [c.args[0] for c in enq.call_args_list])

	# ── SMS escalation ──

	def test_critical_undelivered_escalates_to_sms_once(self):
		name = self._queue_one(priority="Critical")
		frappe.db.set_value(
			"Push Delivery",
			name,
			"creation",
			add_to_date(now_datetime(), seconds=-(push_delivery.SMS_FALLBACK_AFTER_SEC + 60)),
			update_modified=False,
		)
		frappe.db.commit()  # nosemgrep
		with patch("vehicle_maintenance.fleet_service.notifications._dispatch_sms") as sms:
			push_delivery._escalate_undelivered(now_datetime())
			push_delivery._escalate_undelivered(now_datetime())
		self.assertEqual(sms.call_count, 1)
		self.assertEqual(frappe.db.get_value("Push Delivery", name, "fallback_sent"), 1)

	def test_a_warning_does_not_burn_an_sms(self):
		name = self._queue_one(priority="Medium")
		frappe.db.set_value(
			"Push Delivery",
			name,
			"creation",
			add_to_date(now_datetime(), seconds=-(push_delivery.SMS_FALLBACK_AFTER_SEC + 60)),
			update_modified=False,
		)
		frappe.db.commit()  # nosemgrep
		with patch("vehicle_maintenance.fleet_service.notifications._dispatch_sms") as sms:
			push_delivery._escalate_undelivered(now_datetime())
		sms.assert_not_called()

	def test_no_sms_when_another_device_already_took_it(self):
		"""Two handsets, one delivered — the person's phone already buzzed."""
		frappe.get_doc(
			{
				"doctype": "Push Token",
				"user": self.user,
				"device_token": "TOKEN-B",
				"platform": "android",
				"is_active": 1,
			}
		).insert(ignore_permissions=True)
		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue"),
		):
			names = push_delivery.queue_push(
				self.user, "Breakdown", "b", reference_name="TKT-9", priority="Critical"
			)
		self.assertEqual(len(names), 2)
		with patch.object(push_delivery, "_send", return_value=(True, False, "")):
			push_delivery.deliver(names[0])
		for n in names:
			frappe.db.set_value(
				"Push Delivery",
				n,
				"creation",
				add_to_date(now_datetime(), seconds=-(push_delivery.SMS_FALLBACK_AFTER_SEC + 60)),
				update_modified=False,
			)
		frappe.db.commit()  # nosemgrep
		with patch("vehicle_maintenance.fleet_service.notifications._dispatch_sms") as sms:
			push_delivery._escalate_undelivered(now_datetime())
		sms.assert_not_called()

	# ── housekeeping ──

	def test_prune_never_removes_an_undelivered_row(self):
		name = self._queue_one()
		frappe.db.set_value(
			"Push Delivery",
			name,
			"creation",
			add_to_date(now_datetime(), days=-(push_delivery.LEDGER_RETENTION_DAYS + 5)),
			update_modified=False,
		)
		frappe.db.commit()  # nosemgrep
		push_delivery.prune_ledger()
		self.assertTrue(frappe.db.exists("Push Delivery", name))


class TestAlertNotificationWiring(FrappeTestCase):
	"""The join between the alert/ticket path and the delivery ledger.

	These are the wires that were actually crossed in production: a ticket
	notification that pointed the bell at a non-existent Job Card, and a
	*warning* alert being quietly downgraded to a push Doze could sit on.
	"""

	def setUp(self):
		self.user = "wiring-test@example.com"
		if not frappe.db.exists("User", self.user):
			frappe.get_doc(
				{
					"doctype": "User",
					"email": self.user,
					"first_name": "Wiring",
					"mobile_no": "9990000912",
					"send_welcome_email": 0,
				}
			).insert(ignore_permissions=True, ignore_mandatory=True)
		frappe.db.delete("Push Token", {"user": self.user})
		frappe.db.delete("Push Delivery", {"user": self.user})
		frappe.db.delete("Notification Log", {"for_user": self.user})
		frappe.get_doc(
			{
				"doctype": "Push Token",
				"user": self.user,
				"device_token": "WIRE-1",
				"platform": "android",
				"is_active": 1,
			}
		).insert(ignore_permissions=True)
		frappe.db.commit()  # nosemgrep

	def tearDown(self):
		frappe.db.delete("Push Delivery", {"user": self.user})
		frappe.db.delete("Push Token", {"user": self.user})
		frappe.db.delete("Notification Log", {"for_user": self.user})
		frappe.db.commit()  # nosemgrep

	def test_ticket_bell_row_points_at_the_ticket_not_a_job_card(self):
		from vehicle_maintenance.fleet_service import notifications as notif

		notif._dispatch_in_app(self.user, "🚨 KA01 — Alert", "body", "TKT-77", document_type="Service Ticket")
		row = frappe.get_all(
			"Notification Log",
			filters={"for_user": self.user},
			fields=["document_type", "document_name"],
		)[0]
		self.assertEqual(row["document_type"], "Service Ticket")
		self.assertEqual(row["document_name"], "TKT-77")

	def test_alert_deeplink_routes_the_push_to_the_alert_page(self):
		from vehicle_maintenance.fleet_service import notifications as notif

		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue"),
		):
			notif._dispatch_push(self.user, "s", "b", "TKT-77", "Critical", deeplink="naarni://alert/AE-9")
		row = frappe.get_all(
			"Push Delivery",
			filters={"user": self.user},
			fields=["deeplink", "reference_doctype", "urgent"],
		)[0]
		self.assertEqual(row["deeplink"], "naarni://alert/AE-9")
		self.assertEqual(row["reference_doctype"], "Service Ticket")
		self.assertEqual(row["urgent"], 1)

	def test_job_card_push_defaults_to_the_job_card_route(self):
		from vehicle_maintenance.fleet_service import notifications as notif

		with (
			patch.object(push_delivery, "is_enabled", return_value=True),
			patch.object(frappe, "enqueue"),
		):
			notif._dispatch_push(self.user, "s", "b", "JC-0001", "Medium")
		row = frappe.get_all("Push Delivery", filters={"user": self.user}, fields=["deeplink", "urgent"])[0]
		self.assertEqual(row["deeplink"], "naarni://jobcard/JC-0001")
		self.assertEqual(row["urgent"], 1)

	def test_disabled_push_leaves_the_in_app_bell_working(self):
		"""Turning push off must never take the durable notification with it."""
		from vehicle_maintenance.fleet_service import notifications as notif

		with patch.object(push_delivery, "is_enabled", return_value=False):
			notif._dispatch_in_app(self.user, "subject", "body", "JC-1")
			notif._dispatch_push(self.user, "subject", "body", "JC-1", "High")
		self.assertEqual(frappe.db.count("Notification Log", {"for_user": self.user}), 1)
		self.assertEqual(frappe.db.count("Push Delivery", {"user": self.user}), 0)


class TestSmsKillSwitch(FrappeTestCase):
	"""`notifications_sms_enabled` has to hold for every caller, not just `_dispatch`.

	The push escalation path calls `_dispatch_sms` directly, so a toggle that
	only the fan-out honoured would let an operator turn SMS off and keep being
	billed for it.
	"""

	def test_disabled_toggle_blocks_a_direct_call(self):
		from vehicle_maintenance.fleet_service import notifications as notif

		conf = dict(frappe.get_conf())
		conf["notifications_sms_enabled"] = 0
		with (
			patch.object(frappe, "get_conf", return_value=conf),
			patch.object(frappe, "enqueue") as enq,
		):
			notif._dispatch_sms("someone@example.com", "s", "b", "JC-1", "High")
		enq.assert_not_called()

	def test_routine_priority_never_sends_sms(self):
		from vehicle_maintenance.fleet_service import notifications as notif

		conf = dict(frappe.get_conf())
		conf["notifications_sms_enabled"] = 1
		with (
			patch.object(frappe, "get_conf", return_value=conf),
			patch.object(frappe, "enqueue") as enq,
		):
			notif._dispatch_sms("someone@example.com", "s", "b", "JC-1", "Medium")
		enq.assert_not_called()
