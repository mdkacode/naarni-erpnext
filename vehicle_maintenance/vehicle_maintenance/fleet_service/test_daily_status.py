"""Daily status — the properties the feature would be untrustworthy without.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.fleet_service.test_daily_status

The tests worth reading are the ones covering things a manager would silently
act on if they broke: the seq window never losing or double-counting a message,
idempotency on (user, date), a hallucinated ticket id being dropped, and the day
still being recorded when ONYX is unavailable.
"""

from __future__ import annotations

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.fleet_service import chat_feed, daily_status, daily_status_digest
from vehicle_maintenance.integrations import onyx_client


class DailyStatusTestBase(FrappeTestCase):
	def setUp(self):
		frappe.set_user("Administrator")
		self.user = self._ensure_user("status-tech@test.localhost", "9990300001", ["Technician"])
		self._configure()

		# addCleanup rather than a tearDown override: the base suite's tearDown is
		# what forces the rollback that keeps these tests idempotent, and replacing
		# it would quietly take that away.
		orig_commit = frappe.db.commit
		frappe.db.commit = lambda *a, **k: None
		self.addCleanup(setattr, frappe.db, "commit", orig_commit)
		self.addCleanup(setattr, onyx_client, "complete", onyx_client.complete)
		self.addCleanup(setattr, onyx_client, "is_enabled", onyx_client.is_enabled)
		self.addCleanup(frappe.set_user, "Administrator")

	# ---------------------------------------------------------------- fixtures

	def _ensure_user(self, email, mobile, roles):
		if not frappe.db.exists("User", email):
			u = frappe.new_doc("User")
			u.email = email
			u.first_name = "Status Tech"
			u.mobile_no = mobile
			u.send_welcome_email = 0
			u.insert(ignore_permissions=True)
			for r in roles:
				u.append("roles", {"role": r})
			u.save(ignore_permissions=True)
		return email

	def _configure(self, **overrides):
		cfg = frappe.get_single("Daily Status Settings")
		cfg.enabled = 1
		cfg.use_ai = 0
		cfg.on_duty_only = 0
		cfg.day_start_hour = 4
		cfg.max_messages = 60
		cfg.digest_enabled = 1
		cfg.include_silent = 1
		cfg.set("reporting_roles", [{"role": "Technician"}])
		for key, value in overrides.items():
			setattr(cfg, key, value)
		cfg.flags.ignore_permissions = True
		cfg.save()
		frappe.clear_document_cache("Daily Status Settings", "Daily Status Settings")

	def _say(self, text: str, kind: str = "text", **kwargs):
		"""Put a message in the user's status room as the user themselves."""
		room = daily_status.ensure_room(self.user)
		return chat_feed.post(room=room, kind=kind, body=text, author=self.user, **kwargs)

	def _fresh_room(self):
		"""Drop any existing status room + rows so each test starts at seq 0."""
		# Statuses first — they link to the room, and deleting the room out from
		# under them is how this teardown starts failing for an unrelated reason.
		for name in frappe.get_all("VM Daily Status", filters={"user": self.user}, pluck="name"):
			frappe.delete_doc("VM Daily Status", name, force=True, ignore_permissions=True)
		room = daily_status.find_room(self.user)
		if room:
			frappe.db.delete("VM Chat Message", {"room": room})
			frappe.db.delete("VM Chat Member", {"parent": room})
			frappe.delete_doc("VM Chat Room", room, force=True, ignore_permissions=True)


class TestStatusRoom(DailyStatusTestBase):
	def test_room_is_created_once_and_reused(self):
		self._fresh_room()
		first = daily_status.ensure_room(self.user)
		second = daily_status.ensure_room(self.user)
		self.assertEqual(first, second)

	def test_room_has_exactly_one_member(self):
		self._fresh_room()
		room = frappe.get_doc("VM Chat Room", daily_status.ensure_room(self.user))
		self.assertEqual(room.kind, "Status")
		self.assertEqual(room.member_users(), [self.user])


class TestCollection(DailyStatusTestBase):
	def test_only_the_persons_own_messages_count(self):
		self._fresh_room()
		room = daily_status.ensure_room(self.user)
		self._say("finished the PM on 4102")
		chat_feed.post(room=room, kind="system", body="a reminder from the system")

		rows = daily_status.collect(room, self.user, 0, 99)
		self.assertEqual(len(rows), 1)
		self.assertIn("4102", rows[0]["body"])

	def test_deleted_messages_are_excluded(self):
		self._fresh_room()
		room = daily_status.ensure_room(self.user)
		msg = self._say("said something then thought better of it")
		frappe.db.set_value("VM Chat Message", msg.name, "deleted", 1)
		self.assertEqual(daily_status.collect(room, self.user, 0, 99), [])

	def test_window_is_exclusive_at_the_start_and_inclusive_at_the_end(self):
		self._fresh_room()
		room = daily_status.ensure_room(self.user)
		for i in range(4):
			self._say(f"message {i}")
		rows = daily_status.collect(room, self.user, 1, 3)
		self.assertEqual([r["seq"] for r in rows], [2, 3])


class TestGeneration(DailyStatusTestBase):
	def test_idempotent_on_user_and_date(self):
		self._fresh_room()
		self._say("changed the oil on 4102")
		first = daily_status.generate_for_user(self.user, "2026-08-13")
		second = daily_status.generate_for_user(self.user, "2026-08-13")
		self.assertEqual(first, second)
		self.assertEqual(
			frappe.db.count("VM Daily Status", {"user": self.user, "status_date": "2026-08-13"}), 1
		)

	def test_silence_still_produces_a_row(self):
		"""A day nobody reported is the day a manager most needs to see."""
		self._fresh_room()
		name = daily_status.generate_for_user(self.user, "2026-08-13")
		doc = frappe.get_doc("VM Daily Status", name)
		self.assertEqual(doc.state, "Not Reported")
		self.assertEqual(doc.message_count, 0)

	def test_a_late_message_is_carried_forward_not_lost(self):
		"""Yesterday's window closed at seq N; a message sent after it lands today."""
		self._fresh_room()
		self._say("day one work")
		yesterday = daily_status.generate_for_user(self.user, "2026-08-12")
		self.assertEqual(frappe.db.get_value("VM Daily Status", yesterday, "message_count"), 1)

		self._say("forgot to mention the brake pads")
		today = daily_status.generate_for_user(self.user, "2026-08-13")
		doc = frappe.get_doc("VM Daily Status", today)
		self.assertEqual(doc.message_count, 1)
		self.assertIn("brake pads", doc.raw_transcript)
		# And it was not counted twice.
		self.assertEqual(frappe.db.get_value("VM Daily Status", yesterday, "message_count"), 1)

	def test_regeneration_extends_the_same_window(self):
		self._fresh_room()
		self._say("first")
		name = daily_status.generate_for_user(self.user, "2026-08-13")
		from_seq = frappe.db.get_value("VM Daily Status", name, "from_seq")

		self._say("second")
		daily_status.generate_for_user(self.user, "2026-08-13")
		doc = frappe.get_doc("VM Daily Status", name)
		self.assertEqual(doc.from_seq, from_seq)
		self.assertEqual(doc.message_count, 2)

	def test_voice_and_photo_counts(self):
		self._fresh_room()
		self._say("typed")
		msg = self._say("voice", kind="text")
		frappe.db.set_value("VM Chat Message", msg.name, {"kind": "audio", "transcript": "spoken words"})
		name = daily_status.generate_for_user(self.user, "2026-08-13")
		doc = frappe.get_doc("VM Daily Status", name)
		self.assertEqual(doc.message_count, 2)
		self.assertEqual(doc.voice_count, 1)
		self.assertIn("spoken words", doc.raw_transcript)


class TestAISummary(DailyStatusTestBase):
	def _with_ai(self, answer: str):
		self._configure(use_ai=1)
		onyx_client.is_enabled = lambda: True
		onyx_client.complete = lambda prompt, **kw: answer

	def test_valid_json_becomes_items(self):
		self._fresh_room()
		self._say("finished PM on the bus, compressor still leaking")
		self._with_ai(
			'```json\n{"summary": "PM done, compressor still leaking.",'
			' "completed": [{"text": "Preventive maintenance finished"}],'
			' "in_progress": [], "blockers": [{"text": "Compressor leaking, part needed"}],'
			' "needs_attention": []}\n```'
		)
		doc = frappe.get_doc("VM Daily Status", daily_status.generate_for_user(self.user, "2026-08-13"))
		self.assertEqual(doc.generated_by, "AI")
		self.assertEqual(doc.summary_line, "PM done, compressor still leaking.")
		self.assertEqual({i.bucket for i in doc.items}, {"Completed", "Blocker"})

	def test_invented_ticket_id_is_dropped(self):
		"""Grounding: an id the person never mentioned must never reach a record."""
		self._fresh_room()
		self._say("worked on the bus")
		self._with_ai(
			'{"summary": "worked", "completed": [{"text": "fixed it", "ticket": "TKT-99999"}],'
			' "in_progress": [], "blockers": [], "needs_attention": []}'
		)
		doc = frappe.get_doc("VM Daily Status", daily_status.generate_for_user(self.user, "2026-08-13"))
		self.assertEqual(len(doc.items), 1)
		self.assertIsNone(doc.items[0].ticket)

	def test_unparseable_answer_falls_back_and_still_records_the_day(self):
		self._fresh_room()
		self._say("bus 4102 is stuck waiting for a part")
		self._with_ai("Sure! Here is a summary of the day in prose, with no JSON at all.")
		doc = frappe.get_doc("VM Daily Status", daily_status.generate_for_user(self.user, "2026-08-13"))
		self.assertEqual(doc.generated_by, "Fallback")
		self.assertEqual(doc.state, "Generated")
		self.assertIn("4102", doc.raw_transcript)

	def test_onyx_failure_falls_back_rather_than_raising(self):
		self._fresh_room()
		self._say("did the service")

		def boom(prompt, **kw):
			raise onyx_client.OnyxApiError("ONYX returned HTTP 502")

		self._configure(use_ai=1)
		onyx_client.is_enabled = lambda: True
		onyx_client.complete = boom

		doc = frappe.get_doc("VM Daily Status", daily_status.generate_for_user(self.user, "2026-08-13"))
		self.assertEqual(doc.generated_by, "Fallback")
		self.assertIn("502", doc.generation_note)
		self.assertIn("did the service", doc.raw_transcript)

	def test_fallback_flags_a_line_that_reads_like_trouble(self):
		self._fresh_room()
		self._say("bus 4102 is stuck, no part available")
		self._configure(use_ai=0)
		doc = frappe.get_doc("VM Daily Status", daily_status.generate_for_user(self.user, "2026-08-13"))
		self.assertEqual(doc.generated_by, "Fallback")
		self.assertTrue(any(i.bucket == "Needs Attention" for i in doc.items))


class TestJsonExtraction(FrappeTestCase):
	def test_bare_object(self):
		self.assertEqual(onyx_client.extract_json('{"a": 1}'), {"a": 1})

	def test_fenced_object(self):
		self.assertEqual(onyx_client.extract_json('```json\n{"a": 1}\n```'), {"a": 1})

	def test_object_buried_in_prose(self):
		self.assertEqual(onyx_client.extract_json('Here you go:\n{"a": 1}\nHope that helps!'), {"a": 1})

	def test_nothing_parseable_returns_none(self):
		self.assertIsNone(onyx_client.extract_json("no json here"))
		self.assertIsNone(onyx_client.extract_json(""))

	def test_a_json_array_is_not_accepted(self):
		self.assertIsNone(onyx_client.extract_json("[1, 2, 3]"))


class TestOnyxConfig(FrappeTestCase):
	"""site_config arrives as strings, and "0" is a truthy string."""

	def setUp(self):
		original = dict(frappe.conf)

		def restore():
			frappe.conf.clear()
			frappe.conf.update(original)

		self.addCleanup(restore)

	def test_string_zero_does_not_enable_the_integration(self):
		frappe.conf["onyx_enabled"] = "0"
		frappe.conf["onyx_api_key"] = "on_test"
		self.assertFalse(onyx_client.is_enabled())

	def test_string_one_enables_it(self):
		frappe.conf["onyx_enabled"] = "1"
		frappe.conf["onyx_api_key"] = "on_test"
		self.assertTrue(onyx_client.is_enabled())

	def test_site_config_key_wins_over_the_stored_one(self):
		"""So a site can be pinned outside the database when that is preferred."""
		frappe.conf["onyx_api_key"] = "on_from_site_config"
		self.assertEqual(onyx_client._settings()["api_key"], "on_from_site_config")

	def test_calling_without_a_key_raises_config_error_rather_than_a_request(self):
		orig = onyx_client._settings
		onyx_client._settings = lambda: {**orig(), "enabled": True, "api_key": None}
		try:
			with self.assertRaises(onyx_client.OnyxConfigError):
				onyx_client.complete("hello")
		finally:
			onyx_client._settings = orig


class TestPermissions(DailyStatusTestBase):
	def setUp(self):
		super().setUp()
		self.other = self._ensure_user("status-peer@test.localhost", "9990300002", ["Technician"])

	def test_a_peer_cannot_read_another_technicians_day(self):
		self._fresh_room()
		self._say("my day")
		name = daily_status.generate_for_user(self.user, "2026-08-13")

		frappe.set_user(self.other)
		try:
			doc = frappe.get_doc("VM Daily Status", name)
			self.assertFalse(doc.has_permission("read"))
		finally:
			frappe.set_user("Administrator")

	def test_you_can_read_your_own_day(self):
		self._fresh_room()
		self._say("my day")
		name = daily_status.generate_for_user(self.user, "2026-08-13")

		frappe.set_user(self.user)
		try:
			self.assertTrue(frappe.get_doc("VM Daily Status", name).has_permission("read"))
		finally:
			frappe.set_user("Administrator")


class TestDigest(DailyStatusTestBase):
	def test_unroutable_placeholder_addresses_are_not_mailed(self):
		self.assertFalse(daily_status_digest._is_routable("someone@test.localhost"))
		self.assertFalse(daily_status_digest._is_routable("9876543210"))
		self.assertFalse(daily_status_digest._is_routable(None))
		self.assertTrue(daily_status_digest._is_routable("ops@naarni.com"))

	def test_blockers_are_hoisted_into_the_banner(self):
		rows = [
			{
				"user": "a@naarni.com",
				"user_name": "Amit",
				"state": "Generated",
				"summary_line": "PM done",
				"message_count": 2,
				"voice_count": 0,
				"photo_count": 0,
				"generated_by": "AI",
				"raw_transcript": "",
				"items": [
					{"bucket": "Completed", "text": "PM finished", "ticket": None, "vehicle": None},
					{"bucket": "Blocker", "text": "Waiting on a compressor", "ticket": None, "vehicle": None},
				],
			}
		]
		html = daily_status_digest.render_digest(rows, [])
		self.assertIn("Blocked (1)", html)
		self.assertIn("Waiting on a compressor", html)
		# The blocker appears both in the banner and in the person's card.
		self.assertGreaterEqual(html.count("Waiting on a compressor"), 2)

	def test_silent_people_are_named(self):
		silent = [{"user": "b@naarni.com", "user_name": "Bhavna", "state": "Not Reported"}]
		html = daily_status_digest.render_digest([], silent)
		self.assertIn("No update (1)", html)
		self.assertIn("Bhavna", html)

	def test_a_fallback_day_still_shows_what_was_written(self):
		rows = [
			{
				"user": "c@naarni.com",
				"user_name": "Chandan",
				"state": "Generated",
				"summary_line": "",
				"message_count": 1,
				"voice_count": 0,
				"photo_count": 0,
				"generated_by": "Fallback",
				"raw_transcript": "[17:20] finished the wheel alignment",
				"items": [],
			}
		]
		html = daily_status_digest.render_digest(rows, [])
		self.assertIn("finished the wheel alignment", html)
		self.assertIn("not organised by AI", html)


class TestDayBoundary(DailyStatusTestBase):
	def test_a_night_shift_past_midnight_belongs_to_the_day_it_started(self):
		from datetime import datetime

		self._configure(day_start_hour=4)
		late = datetime(2026, 8, 14, 1, 30, tzinfo=daily_status.IST)
		self.assertEqual(daily_status.status_date_now(late), "2026-08-13")

		morning = datetime(2026, 8, 14, 9, 0, tzinfo=daily_status.IST)
		self.assertEqual(daily_status.status_date_now(morning), "2026-08-14")
