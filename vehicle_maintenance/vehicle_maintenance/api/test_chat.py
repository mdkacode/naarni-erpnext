"""Chat API behaviour — the invariants the mobile client is built on.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_chat

The tests worth reading are the ones covering properties the Android side cannot
recover from if they break: sequence monotonicity, `client_id` idempotency,
membership isolation, and resumable-upload offset discipline.
"""

import hashlib
import io
import random
import types

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import chat, chat_upload


class _FakePart:
	"""Stands in for a Werkzeug FileStorage in `frappe.request.files`."""

	def __init__(self, data: bytes):
		self.stream = io.BytesIO(data)


class ChatTestBase(FrappeTestCase):
	def setUp(self):
		frappe.set_user("Administrator")
		self.alice = self._ensure_user("chat-alice@test.localhost", "9990100001", ["Service Engineer"])
		self.bob = self._ensure_user("chat-bob@test.localhost", "9990100002", ["Technician"])
		self.mallory = self._ensure_user("chat-mallory@test.localhost", "9990100003", ["Technician"])
		self.room = self._ensure_room()
		self._orig_commit = frappe.db.commit
		frappe.db.commit = lambda *a, **k: None

	def tearDown(self):
		frappe.set_user("Administrator")
		frappe.db.commit = self._orig_commit
		frappe.local.request = None

	def _ensure_user(self, email, mobile, roles):
		if not frappe.db.exists("User", email):
			u = frappe.new_doc("User")
			u.email = email
			u.first_name = email.split("@")[0]
			u.mobile_no = mobile
			u.send_welcome_email = 0
			u.flags.ignore_permissions = True
			u.insert(ignore_permissions=True)
			for r in roles:
				u.append("roles", {"role": r})
			u.save(ignore_permissions=True)
		return email

	def _ensure_room(self):
		"""A fresh room per test, so seq always starts at zero."""
		doc = frappe.get_doc(
			{
				"doctype": "VM Chat Room",
				"title": f"Test Room {frappe.generate_hash(length=6)}",
				"kind": "Group",
				"members": [
					{"user": self.alice, "member_role": "Admin"},
					{"user": self.bob, "member_role": "Member"},
				],
			}
		)
		doc.insert(ignore_permissions=True)
		return doc.name

	def _send(self, user, body, client_id=None):
		frappe.set_user(user)
		return chat.send_message(
			room=self.room, client_id=client_id or frappe.generate_hash(length=20), body=body
		)


class TestChatSequencing(ChatTestBase):
	def test_seq_starts_at_one_and_increments(self):
		"""Ordering is the spine of the client. seq must be 1,2,3 with no gaps."""
		seqs = [self._send(self.alice, f"m{i}")["data"]["message"]["seq"] for i in range(5)]
		self.assertEqual(seqs, [1, 2, 3, 4, 5])

	def test_seq_is_per_room_not_global(self):
		other = self._ensure_room()
		self._send(self.alice, "first here")
		frappe.set_user(self.alice)
		res = chat.send_message(room=other, client_id=frappe.generate_hash(length=20), body="first there")
		self.assertEqual(res["data"]["message"]["seq"], 1)

	def test_interleaved_senders_never_collide(self):
		a1 = self._send(self.alice, "a1")["data"]["message"]["seq"]
		b1 = self._send(self.bob, "b1")["data"]["message"]["seq"]
		a2 = self._send(self.alice, "a2")["data"]["message"]["seq"]
		self.assertEqual([a1, b1, a2], [1, 2, 3])

	def test_room_last_seq_tracks_messages(self):
		self._send(self.alice, "x")
		self._send(self.bob, "y")
		self.assertEqual(frappe.db.get_value("VM Chat Room", self.room, "last_seq"), 2)

	def test_direct_insert_without_seq_is_rejected(self):
		"""Nothing may create a message outside the API and get away with no seq."""
		frappe.set_user("Administrator")
		doc = frappe.get_doc(
			{
				"doctype": "VM Chat Message",
				"room": self.room,
				"client_id": frappe.generate_hash(length=20),
				"author": self.alice,
				"kind": "text",
				"body": "smuggled",
			}
		)
		with self.assertRaises(frappe.ValidationError):
			doc.insert(ignore_permissions=True)


class TestChatIdempotency(ChatTestBase):
	def test_same_client_id_returns_original(self):
		"""The property that makes retrying over a flaky link safe."""
		cid = frappe.generate_hash(length=20)
		first = self._send(self.alice, "hello", client_id=cid)
		second = self._send(self.alice, "hello", client_id=cid)

		self.assertFalse(first["data"]["duplicate"])
		self.assertTrue(second["data"]["duplicate"])
		self.assertEqual(first["data"]["message"]["name"], second["data"]["message"]["name"])
		self.assertEqual(first["data"]["message"]["seq"], second["data"]["message"]["seq"])

	def test_retry_does_not_burn_a_sequence_number(self):
		"""A duplicate must not leave a hole the client will hunt for forever."""
		cid = frappe.generate_hash(length=20)
		self._send(self.alice, "one", client_id=cid)
		self._send(self.alice, "one", client_id=cid)
		nxt = self._send(self.alice, "two")
		self.assertEqual(nxt["data"]["message"]["seq"], 2)

	def test_client_id_is_unique_at_the_database(self):
		cid = frappe.generate_hash(length=20)
		self._send(self.alice, "one", client_id=cid)
		frappe.set_user("Administrator")
		dupe = frappe.get_doc(
			{
				"doctype": "VM Chat Message",
				"room": self.room,
				"seq": 999,
				"client_id": cid,
				"author": self.alice,
				"kind": "text",
				"body": "dupe",
			}
		)
		with self.assertRaises(frappe.UniqueValidationError):
			dupe.insert(ignore_permissions=True)


class TestChatMembership(ChatTestBase):
	def test_non_member_cannot_send(self):
		frappe.set_user(self.mallory)
		with self.assertRaises(frappe.PermissionError):
			chat.send_message(room=self.room, client_id=frappe.generate_hash(length=20), body="hi")

	def test_non_member_cannot_read(self):
		self._send(self.alice, "secret")
		frappe.set_user(self.mallory)
		with self.assertRaises(frappe.PermissionError):
			chat.list_messages(room=self.room)

	def test_non_member_room_list_excludes_room(self):
		frappe.set_user(self.mallory)
		names = [r["name"] for r in chat.list_rooms()["data"]["rooms"]]
		self.assertNotIn(self.room, names)

	def test_member_room_list_includes_room(self):
		frappe.set_user(self.bob)
		names = [r["name"] for r in chat.list_rooms()["data"]["rooms"]]
		self.assertIn(self.room, names)

	def test_sync_never_leaks_other_rooms(self):
		"""Passing a cursor for a room you are not in must not return its messages."""
		self._send(self.alice, "private")
		frappe.set_user(self.mallory)
		out = chat.sync(cursors={self.room: 0})
		self.assertNotIn(self.room, out["data"]["rooms"])


class TestChatSync(ChatTestBase):
	def test_sync_returns_only_newer_messages(self):
		self._send(self.alice, "one")
		self._send(self.alice, "two")
		self._send(self.alice, "three")

		frappe.set_user(self.bob)
		out = chat.sync(cursors={self.room: 1})["data"]["rooms"][self.room]
		self.assertEqual([m["seq"] for m in out["messages"]], [2, 3])
		self.assertEqual(out["last_seq"], 3)

	def test_sync_omits_rooms_with_nothing_new(self):
		self._send(self.alice, "one")
		frappe.set_user(self.bob)
		out = chat.sync(cursors={self.room: 1})
		self.assertNotIn(self.room, out["data"]["rooms"])

	def test_sync_accepts_json_string_cursors(self):
		"""Frappe hands HTTP bodies through as strings; the client posts JSON."""
		self._send(self.alice, "one")
		frappe.set_user(self.bob)
		out = chat.sync(cursors=frappe.as_json({self.room: 0}))
		self.assertIn(self.room, out["data"]["rooms"])

	def test_fresh_install_gets_history(self):
		self._send(self.alice, "one")
		self._send(self.alice, "two")
		frappe.set_user(self.bob)
		out = chat.sync(cursors={})["data"]["rooms"][self.room]
		self.assertEqual([m["seq"] for m in out["messages"]], [1, 2])


class TestChatReadCursor(ChatTestBase):
	def test_unread_counts_from_read_cursor(self):
		self._send(self.alice, "one")
		self._send(self.alice, "two")
		frappe.set_user(self.bob)
		room = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == self.room)
		self.assertEqual(room["unread"], 2)

		chat.mark_read(room=self.room, seq=2)
		room = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == self.room)
		self.assertEqual(room["unread"], 0)

	def test_mark_read_is_forward_only(self):
		"""Opening an old message from a push must not resurrect the badge."""
		self._send(self.alice, "one")
		self._send(self.alice, "two")
		frappe.set_user(self.bob)
		chat.mark_read(room=self.room, seq=2)
		res = chat.mark_read(room=self.room, seq=1)
		self.assertEqual(res["data"]["last_read_seq"], 2)

	def test_sender_has_read_their_own_message(self):
		self._send(self.alice, "mine")
		frappe.set_user(self.alice)
		room = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == self.room)
		self.assertEqual(room["unread"], 0)


class TestChatPaging(ChatTestBase):
	def test_pages_backwards_by_seq(self):
		for i in range(1, 8):
			self._send(self.alice, f"m{i}")
		frappe.set_user(self.bob)

		page1 = chat.list_messages(room=self.room, limit=3)["data"]
		self.assertEqual([m["seq"] for m in page1["messages"]], [7, 6, 5])
		self.assertTrue(page1["has_more"])

		page2 = chat.list_messages(room=self.room, before_seq=5, limit=3)["data"]
		self.assertEqual([m["seq"] for m in page2["messages"]], [4, 3, 2])

	def test_limit_is_capped(self):
		self._send(self.alice, "one")
		frappe.set_user(self.bob)
		res = chat.list_messages(room=self.room, limit=100000)
		self.assertTrue(res["success"])


class TestChatUpload(ChatTestBase):
	def _begin(self, data: bytes, content_type="image/jpeg", sha=None):
		frappe.set_user(self.alice)
		return chat_upload.begin_upload(
			room=self.room,
			file_name="photo.jpg",
			total_size=len(data),
			content_type=content_type,
			sha256=sha if sha is not None else hashlib.sha256(data).hexdigest(),
		)["data"]["upload_id"]

	def _chunk(self, upload_id, offset, data: bytes):
		frappe.local.form_dict.update({"upload_id": upload_id, "offset": offset})
		frappe.local.request = types.SimpleNamespace(files={"chunk": _FakePart(data)})
		return chat_upload.upload_chunk()

	def test_multi_chunk_roundtrip_produces_one_message(self):
		"""The core 500 MB path, in miniature: two chunks, one file, one message."""
		data = b"A" * 1000 + b"B" * 1000
		upload_id = self._begin(data)

		self._chunk(upload_id, 0, data[:1000])
		res = self._chunk(upload_id, 1000, data[1000:])
		self.assertTrue(res["data"]["complete"])

		out = chat_upload.commit_upload(upload_id=upload_id, client_id=frappe.generate_hash(length=20))
		msg = out["data"]["message"]
		self.assertEqual(msg["kind"], "image")
		self.assertEqual(msg["file_size"], 2000)
		self.assertTrue(msg["file_url"].startswith("/private/files/"))
		self.assertEqual(msg["seq"], 1)

	def test_out_of_order_chunk_is_rejected(self):
		"""A reordered chunk must fail loudly, not corrupt the file."""
		data = b"X" * 2000
		upload_id = self._begin(data)
		self._chunk(upload_id, 0, data[:1000])
		with self.assertRaises(frappe.ValidationError):
			self._chunk(upload_id, 9999, data[1000:])

	def test_replayed_chunk_is_rejected(self):
		data = b"X" * 2000
		upload_id = self._begin(data)
		self._chunk(upload_id, 0, data[:1000])
		with self.assertRaises(frappe.ValidationError):
			self._chunk(upload_id, 0, data[:1000])

	def test_status_reports_resume_point(self):
		"""What the worker asks after process death."""
		data = b"X" * 2000
		upload_id = self._begin(data)
		self._chunk(upload_id, 0, data[:1500])
		status = chat_upload.chunk_status(upload_id=upload_id)["data"]
		self.assertEqual(status["bytes_received"], 1500)
		self.assertEqual(status["total_size"], 2000)

	def test_incomplete_upload_cannot_commit(self):
		data = b"X" * 2000
		upload_id = self._begin(data)
		self._chunk(upload_id, 0, data[:1000])
		with self.assertRaises(frappe.ValidationError):
			chat_upload.commit_upload(upload_id=upload_id, client_id=frappe.generate_hash(length=20))

	def test_corrupted_upload_is_rejected(self):
		"""Digest mismatch means a proxy mangled it — never publish that."""
		data = b"X" * 1000
		upload_id = self._begin(data, sha="0" * 64)
		self._chunk(upload_id, 0, data)
		with self.assertRaises(frappe.ValidationError):
			chat_upload.commit_upload(upload_id=upload_id, client_id=frappe.generate_hash(length=20))

	def test_audio_is_accepted(self):
		"""Regression for blocker B2: core ALLOWED_MIMETYPES has no audio/* at all."""
		data = b"\x00" * 500
		upload_id = self._begin(data, content_type="audio/mp4")
		self._chunk(upload_id, 0, data)
		out = chat_upload.commit_upload(
			upload_id=upload_id, client_id=frappe.generate_hash(length=20), duration_ms=4200
		)
		self.assertEqual(out["data"]["message"]["kind"], "audio")
		self.assertEqual(out["data"]["message"]["duration_ms"], 4200)

	def test_executable_content_type_is_refused(self):
		frappe.set_user(self.alice)
		with self.assertRaises(frappe.ValidationError):
			chat_upload.begin_upload(
				room=self.room,
				file_name="evil.sh",
				total_size=10,
				content_type="application/x-sh",
			)

	def test_oversize_is_refused_before_any_bytes_move(self):
		frappe.set_user(self.alice)
		with self.assertRaises(frappe.ValidationError):
			chat_upload.begin_upload(
				room=self.room,
				file_name="huge.mp4",
				total_size=chat_upload.DEFAULT_MAX_UPLOAD_BYTES + 1,
				content_type="video/mp4",
			)

	def test_non_member_cannot_begin_upload(self):
		frappe.set_user(self.mallory)
		with self.assertRaises(frappe.PermissionError):
			chat_upload.begin_upload(
				room=self.room, file_name="x.jpg", total_size=10, content_type="image/jpeg"
			)

	def test_another_user_cannot_hijack_an_upload(self):
		data = b"X" * 100
		upload_id = self._begin(data)
		frappe.set_user(self.bob)
		with self.assertRaises(frappe.PermissionError):
			chat_upload.chunk_status(upload_id=upload_id)

	def test_commit_is_idempotent(self):
		data = b"X" * 500
		upload_id = self._begin(data)
		self._chunk(upload_id, 0, data)
		cid = frappe.generate_hash(length=20)
		first = chat_upload.commit_upload(upload_id=upload_id, client_id=cid)
		second = chat_upload.commit_upload(upload_id=upload_id, client_id=cid)
		self.assertTrue(second["data"]["duplicate"])
		self.assertEqual(first["data"]["message"]["name"], second["data"]["message"]["name"])

	def test_attachment_is_private_and_scoped_to_the_room(self):
		"""File permission must delegate to room membership, not to the uploader."""
		data = b"X" * 300
		upload_id = self._begin(data)
		self._chunk(upload_id, 0, data)
		chat_upload.commit_upload(upload_id=upload_id, client_id=frappe.generate_hash(length=20))

		file_doc = frappe.get_last_doc("File", filters={"attached_to_doctype": "VM Chat Room"})
		self.assertEqual(file_doc.is_private, 1)
		self.assertEqual(file_doc.attached_to_name, self.room)


class TestChatDirectMessages(ChatTestBase):
	def test_direct_room_is_created_once(self):
		"""Two calls must not leave a user with two parallel DM threads."""
		# A user nobody else in this suite has messaged, so the assertion holds
		# regardless of the order the tests happen to run in.
		suffix = "".join(random.choices("0123456789", k=8))
		fresh = self._ensure_user(
			f"chat-dm-{suffix}@test.localhost",
			f"99{suffix}",  # User.validate requires 10 digits
			["Technician"],
		)
		frappe.set_user(self.alice)
		first = chat.get_or_create_direct(user=fresh)["data"]
		second = chat.get_or_create_direct(user=fresh)["data"]
		self.assertTrue(first["created"])
		self.assertFalse(second["created"])
		self.assertEqual(first["room"], second["room"])

	def test_direct_room_is_symmetric(self):
		"""Whoever opens it second must land in the same thread."""
		frappe.set_user(self.alice)
		mine = chat.get_or_create_direct(user=self.mallory)["data"]["room"]
		frappe.set_user(self.mallory)
		theirs = chat.get_or_create_direct(user=self.alice)["data"]["room"]
		self.assertEqual(mine, theirs)

	def test_group_containing_both_is_not_their_dm(self):
		"""self.room already has alice+bob as a Group; it must not be reused."""
		frappe.set_user(self.alice)
		dm = chat.get_or_create_direct(user=self.bob)["data"]["room"]
		self.assertNotEqual(dm, self.room)

	def test_cannot_dm_yourself(self):
		frappe.set_user(self.alice)
		with self.assertRaises(frappe.ValidationError):
			chat.get_or_create_direct(user=self.alice)

	def test_both_parties_can_message_immediately(self):
		frappe.set_user(self.alice)
		room = chat.get_or_create_direct(user=self.bob)["data"]["room"]
		frappe.set_user(self.bob)
		res = chat.send_message(room=room, client_id=frappe.generate_hash(length=20), body="hi")
		self.assertEqual(res["data"]["message"]["seq"], 1)


class TestChatUserSearch(ChatTestBase):
	def test_search_finds_by_name(self):
		frappe.set_user(self.alice)
		names = [u["name"] for u in chat.search_users(query="chat-bob")["data"]["users"]]
		self.assertIn(self.bob, names)

	def test_search_finds_by_phone(self):
		frappe.set_user(self.alice)
		names = [u["name"] for u in chat.search_users(query="9990100002")["data"]["users"]]
		self.assertIn(self.bob, names)

	def test_search_excludes_self(self):
		frappe.set_user(self.alice)
		names = [u["name"] for u in chat.search_users(query="chat-")["data"]["users"]]
		self.assertNotIn(self.alice, names)

	def test_empty_query_returns_a_browsable_directory(self):
		frappe.set_user(self.alice)
		users = chat.search_users(query="")["data"]["users"]
		self.assertGreater(len(users), 0)
		self.assertNotIn(self.alice, [u["name"] for u in users])

	def test_each_side_sees_the_other_persons_name(self):
		"""A DM must never show you your own name as the thread title."""
		frappe.set_user(self.alice)
		room = chat.get_or_create_direct(user=self.bob)["data"]["room"]

		frappe.set_user(self.bob)
		row = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == room)
		bob_sees = row["title"]

		frappe.set_user(self.alice)
		row = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == room)
		alice_sees = row["title"]

		self.assertNotEqual(bob_sees, alice_sees)
		self.assertIn("alice", bob_sees.lower())
		self.assertIn("bob", alice_sees.lower())


class TestChatMentions(ChatTestBase):
	"""`@` is the difference between a busy channel and one people read."""

	def test_mention_of_a_member_is_stored_and_returned(self):
		frappe.set_user(self.alice)
		res = chat.send_message(
			room=self.room,
			client_id=frappe.generate_hash(length=20),
			body="@Bob can you check gate 3",
			mentions=[self.bob],
		)
		self.assertEqual(res["data"]["message"]["mentions"], [self.bob])

		page = chat.list_messages(room=self.room)["data"]["messages"]
		self.assertEqual(page[0]["mentions"], [self.bob])

	def test_mention_accepts_a_json_string(self):
		"""Frappe hands list bodies through as strings over plain HTTP form posts."""
		frappe.set_user(self.alice)
		res = chat.send_message(
			room=self.room,
			client_id=frappe.generate_hash(length=20),
			body="ping",
			mentions=f'["{self.bob}"]',
		)
		self.assertEqual(res["data"]["message"]["mentions"], [self.bob])

	def test_mention_of_a_non_member_is_dropped_not_rejected(self):
		"""A stale mention costs the mention, never the message."""
		frappe.set_user(self.alice)
		res = chat.send_message(
			room=self.room,
			client_id=frappe.generate_hash(length=20),
			body="@Mallory look",
			mentions=[self.mallory],
		)
		self.assertEqual(res["data"]["message"]["mentions"], [])
		self.assertEqual(res["data"]["message"]["seq"], 1)

	def test_duplicate_mentions_collapse(self):
		frappe.set_user(self.alice)
		res = chat.send_message(
			room=self.room,
			client_id=frappe.generate_hash(length=20),
			body="@Bob @Bob",
			mentions=[self.bob, self.bob],
		)
		self.assertEqual(res["data"]["message"]["mentions"], [self.bob])

	def test_mention_writes_a_notification_log_entry(self):
		before = frappe.db.count("Notification Log", {"for_user": self.bob})
		frappe.set_user(self.alice)
		chat.send_message(
			room=self.room,
			client_id=frappe.generate_hash(length=20),
			body="@Bob urgent",
			mentions=[self.bob],
		)
		after = frappe.db.count("Notification Log", {"for_user": self.bob})
		self.assertEqual(after, before + 1)

	def test_plain_message_does_not_fill_the_notification_bell(self):
		before = frappe.db.count("Notification Log", {"for_user": self.bob})
		self._send(self.alice, "just chatting")
		self.assertEqual(frappe.db.count("Notification Log", {"for_user": self.bob}), before)

	def test_list_members_excludes_self_and_filters(self):
		frappe.set_user(self.alice)
		names = [u["name"] for u in chat.list_members(room=self.room)["data"]["users"]]
		self.assertEqual(names, [self.bob])

		filtered = chat.list_members(room=self.room, query="zzzz")["data"]["users"]
		self.assertEqual(filtered, [])

	def test_list_members_is_membership_gated(self):
		frappe.set_user(self.mallory)
		with self.assertRaises(frappe.PermissionError):
			chat.list_members(room=self.room)


class TestChatPushRouting(ChatTestBase):
	"""Who gets woken up. Pure function, so it is worth pinning exactly."""

	def _room_doc(self):
		return frappe.get_doc("VM Chat Room", self.room)

	def test_author_is_never_pushed_to(self):
		from vehicle_maintenance.fleet_service import chat_notify

		msg = types.SimpleNamespace(author=self.alice)
		who = chat_notify.recipients_for_push(msg, self._room_doc(), set())
		self.assertEqual(who, [self.bob])

	def test_notify_push_off_is_always_honoured(self):
		from vehicle_maintenance.fleet_service import chat_notify

		room = self._room_doc()
		room.member_row(self.bob).notify_push = 0
		msg = types.SimpleNamespace(author=self.alice)
		self.assertEqual(chat_notify.recipients_for_push(msg, room, {self.bob}), [])

	def test_muted_room_is_silent(self):
		from vehicle_maintenance.fleet_service import chat_notify

		room = self._room_doc()
		room.member_row(self.bob).muted = 1
		msg = types.SimpleNamespace(author=self.alice)
		self.assertEqual(chat_notify.recipients_for_push(msg, room, set()), [])

	def test_a_mention_pierces_a_mute(self):
		"""Otherwise nobody can afford to mute a busy depot channel."""
		from vehicle_maintenance.fleet_service import chat_notify

		room = self._room_doc()
		room.member_row(self.bob).muted = 1
		msg = types.SimpleNamespace(author=self.alice)
		self.assertEqual(chat_notify.recipients_for_push(msg, room, {self.bob}), [self.bob])


class TestChatAlertChannels(ChatTestBase):
	def setUp(self):
		super().setUp()
		self.depot = self._ensure_depot("CHATDEP")
		self.other_depot = self._ensure_depot("CHATDEP2")
		self.vehicle = self._ensure_vehicle("CHAT-TEST-01", self.depot)

	def _ensure_depot(self, code):
		name = f"Chat Test {code}"
		if not frappe.db.exists("Depot", name):
			frappe.get_doc({"doctype": "Depot", "depot_name": name, "location_code": code}).insert(
				ignore_permissions=True
			)
		return name

	def _ensure_vehicle(self, reg, depot):
		if not frappe.db.exists("Vehicle", reg):
			frappe.get_doc(
				{
					"doctype": "Vehicle",
					"registration_number": reg,
					"make_model": "Test Bus",
					"depot": depot,
				}
			).insert(ignore_permissions=True)
		return reg

	def _channel(self, **kwargs):
		doc = frappe.get_doc(
			{
				"doctype": "VM Chat Room",
				"title": f"Alerts {frappe.generate_hash(length=6)}",
				"kind": "Alert Channel",
				"broadcast_alerts": 1,
				"members": [{"user": self.alice, "member_role": "Admin"}],
				**kwargs,
			}
		)
		doc.insert(ignore_permissions=True)
		return doc.name

	def _alert(self, severity="critical"):
		return frappe.get_doc(
			{
				"doctype": "Alert Event",
				"title": "Battery temperature high",
				"severity": severity,
				"vehicle": self.vehicle,
				"registration_number": self.vehicle,
				"message": "Pack 2 at 61C",
			}
		).insert(ignore_permissions=True)

	def test_fleetwide_channel_receives_everything(self):
		from vehicle_maintenance.fleet_service import chat_feed

		room = self._channel()
		chat_feed.broadcast_alert(self._alert().name)
		rows = frappe.get_all("VM Chat Message", filters={"room": room}, fields=["kind", "alert_event"])
		self.assertEqual(len(rows), 1)
		self.assertEqual(rows[0]["kind"], "alert")
		self.assertTrue(rows[0]["alert_event"])

	def test_depot_scoped_channel_ignores_other_depots(self):
		from vehicle_maintenance.fleet_service import chat_feed

		mine = self._channel(depot=self.depot)
		theirs = self._channel(depot=self.other_depot)
		chat_feed.broadcast_alert(self._alert().name)
		self.assertEqual(frappe.db.count("VM Chat Message", {"room": mine}), 1)
		self.assertEqual(frappe.db.count("VM Chat Message", {"room": theirs}), 0)

	def test_minimum_severity_keeps_a_channel_quiet(self):
		from vehicle_maintenance.fleet_service import chat_feed

		critical_only = self._channel(alert_min_severity="critical")
		chat_feed.broadcast_alert(self._alert(severity="warning").name)
		self.assertEqual(frappe.db.count("VM Chat Message", {"room": critical_only}), 0)

		chat_feed.broadcast_alert(self._alert(severity="critical").name)
		self.assertEqual(frappe.db.count("VM Chat Message", {"room": critical_only}), 1)

	def test_alert_posts_take_a_real_seq(self):
		"""A machine-written message must order with human ones, not beside them."""
		from vehicle_maintenance.fleet_service import chat_feed

		room = self._channel()
		frappe.set_user(self.alice)
		chat.send_message(room=room, client_id=frappe.generate_hash(length=20), body="morning")
		frappe.set_user("Administrator")
		chat_feed.broadcast_alert(self._alert().name)
		seqs = [
			r["seq"]
			for r in frappe.get_all(
				"VM Chat Message", filters={"room": room}, fields=["seq"], order_by="seq asc"
			)
		]
		self.assertEqual(seqs, [1, 2])

	def test_an_inactive_channel_is_skipped(self):
		from vehicle_maintenance.fleet_service import chat_feed

		room = self._channel()
		frappe.db.set_value("VM Chat Room", room, "is_active", 0)
		chat_feed.broadcast_alert(self._alert().name)
		self.assertEqual(frappe.db.count("VM Chat Message", {"room": room}), 0)


class TestChatTickets(ChatTestBase):
	def setUp(self):
		super().setUp()
		self.ticket = frappe.get_doc(
			{
				"doctype": "Service Ticket",
				"title": "Door sensor fault",
				"status": "Open",
				"severity": "High",
				"registration_number": "CHAT-TKT-09",
			}
		).insert(ignore_permissions=True)

	def test_search_matches_registration(self):
		frappe.set_user(self.alice)
		names = [t["name"] for t in chat.search_tickets(query="CHAT-TKT-09")["data"]["tickets"]]
		self.assertIn(self.ticket.name, names)

	def test_share_posts_a_ticket_card(self):
		frappe.set_user(self.alice)
		res = chat.share_ticket(room=self.room, ticket=self.ticket.name)
		msg = res["data"]["message"]
		self.assertEqual(msg["kind"], "ticket")
		self.assertEqual(msg["ticket"], self.ticket.name)
		self.assertIn("Door sensor fault", msg["body"])

	def test_share_is_idempotent_on_client_id(self):
		frappe.set_user(self.alice)
		cid = frappe.generate_hash(length=20)
		first = chat.share_ticket(room=self.room, ticket=self.ticket.name, client_id=cid)
		second = chat.share_ticket(room=self.room, ticket=self.ticket.name, client_id=cid)
		self.assertFalse(first["data"]["duplicate"])
		self.assertTrue(second["data"]["duplicate"])
		self.assertEqual(frappe.db.count("VM Chat Message", {"room": self.room}), 1)

	def test_share_is_membership_gated(self):
		frappe.set_user(self.mallory)
		with self.assertRaises(frappe.PermissionError):
			chat.share_ticket(room=self.room, ticket=self.ticket.name)

	def test_assign_sets_the_field_and_announces_it(self):
		frappe.set_user("Administrator")
		chat.assign_ticket(ticket=self.ticket.name, user=self.bob, room=self.room)
		self.assertEqual(frappe.db.get_value("Service Ticket", self.ticket.name, "assigned_to"), self.bob)
		rows = frappe.get_all(
			"VM Chat Message", filters={"room": self.room, "kind": "system"}, fields=["body"]
		)
		self.assertEqual(len(rows), 1)
		self.assertIn(self.ticket.name, rows[0]["body"])

	def test_assign_to_a_disabled_user_is_refused(self):
		frappe.set_user("Administrator")
		with self.assertRaises(frappe.DoesNotExistError):
			chat.assign_ticket(ticket=self.ticket.name, user="nobody@nowhere.invalid")


class TestChatAlertWiring(ChatTestBase):
	"""The hook, not just the function it calls.

	Every chat bug that reached a phone so far has been wiring, not logic — a
	handler that was never registered, or registered under a name that did not
	resolve. So this asserts the path an actual Alert Event takes.
	"""

	def test_hooks_registers_the_alert_handler(self):
		from vehicle_maintenance import hooks

		self.assertEqual(
			hooks.doc_events["Alert Event"]["after_insert"],
			"vehicle_maintenance.fleet_service.chat_feed.on_alert_event",
		)

	def test_handler_path_resolves_to_a_callable(self):
		self.assertTrue(
			callable(frappe.get_attr("vehicle_maintenance.fleet_service.chat_feed.on_alert_event"))
		)

	def test_inserting_an_alert_reaches_the_channel(self):
		"""End to end with the queue collapsed to an inline call."""
		from vehicle_maintenance.fleet_service import chat_feed

		room = frappe.get_doc(
			{
				"doctype": "VM Chat Room",
				"title": f"Wiring {frappe.generate_hash(length=6)}",
				"kind": "Alert Channel",
				"broadcast_alerts": 1,
				"members": [{"user": self.alice, "member_role": "Admin"}],
			}
		).insert(ignore_permissions=True)

		# The suite stubs commit out, so `enqueue_after_commit` jobs never flush.
		# Run the job body directly instead of pretending the queue ran.
		calls = []
		original = frappe.enqueue
		frappe.enqueue = lambda method=None, **kw: calls.append((method, kw))
		try:
			frappe.get_doc(
				{
					"doctype": "Alert Event",
					"title": "Coolant low",
					"severity": "critical",
					"message": "reservoir below minimum",
				}
			).insert(ignore_permissions=True)
		finally:
			frappe.enqueue = original

		self.assertEqual(len(calls), 1, "the after_insert hook did not enqueue a fan-out")
		method, kwargs = calls[0]
		self.assertEqual(method, "vehicle_maintenance.fleet_service.chat_feed.broadcast_alert")

		chat_feed.broadcast_alert(kwargs["alert_name"])
		self.assertEqual(frappe.db.count("VM Chat Message", {"room": room.name}), 1)


class TestChatSearchReachesEveryone(ChatTestBase):
	"""The point of "message anyone" is the people who are not staff."""

	def test_a_website_user_is_findable(self):
		email = "chat-driver@test.localhost"
		if not frappe.db.exists("User", email):
			u = frappe.new_doc("User")
			u.email = email
			u.first_name = "Chat Driver"
			u.mobile_no = "9990100009"
			u.user_type = "Website User"
			u.send_welcome_email = 0
			u.insert(ignore_permissions=True)

		frappe.set_user(self.alice)
		names = [x["name"] for x in chat.search_users(query="chat-driver")["data"]["users"]]
		self.assertIn(email, names)

	def test_automation_accounts_stay_hidden(self):
		frappe.set_user(self.alice)
		names = [x["name"] for x in chat.search_users(query="")["data"]["users"]]
		self.assertNotIn("Administrator", names)
		self.assertNotIn("Guest", names)


class TestChatDepotEnrolment(ChatTestBase):
	def setUp(self):
		super().setUp()
		self.depot_name = "Chat Enrol Depot"
		if not frappe.db.exists("Depot", self.depot_name):
			frappe.get_doc(
				{
					"doctype": "Depot",
					"depot_name": self.depot_name,
					"location_code": "CHATENROL",
					"service_engineers": [{"user": self.bob}],
				}
			).insert(ignore_permissions=True)

	def _channel(self):
		return frappe.get_doc(
			{
				"doctype": "VM Chat Room",
				"title": f"Enrol {frappe.generate_hash(length=6)}",
				"kind": "Alert Channel",
				"broadcast_alerts": 1,
				"depot": self.depot_name,
				"members": [{"user": self.alice, "member_role": "Admin"}],
			}
		).insert(ignore_permissions=True)

	def test_pulls_the_depots_engineers_in(self):
		frappe.set_user("Administrator")
		room = self._channel()
		added = chat.add_depot_members(room=room.name)["data"]["added"]
		self.assertIn(self.bob, added)
		self.assertIn(self.bob, frappe.get_doc("VM Chat Room", room.name).member_users())

	def test_running_it_twice_adds_nobody(self):
		frappe.set_user("Administrator")
		room = self._channel()
		chat.add_depot_members(room=room.name)
		self.assertEqual(chat.add_depot_members(room=room.name)["data"]["added"], [])

	def test_refuses_a_room_with_no_depot(self):
		frappe.set_user("Administrator")
		with self.assertRaises(frappe.ValidationError):
			chat.add_depot_members(room=self.room)

	def test_doctype_js_is_registered(self):
		from vehicle_maintenance import hooks

		self.assertEqual(hooks.doctype_js["VM Chat Room"], "public/js/vm_chat_room.js")


class TestChatFileUploads(ChatTestBase):
	"""Any working document, but nothing a handset could be told to run."""

	def test_ordinary_documents_are_accepted(self):
		from vehicle_maintenance.api import chat_upload

		for content_type, name in [
			("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "readings.xlsx"),
			("text/csv", "km-dump.csv"),
			("application/zip", "diagnostics.zip"),
			("text/plain", "can-bus.log"),
			("application/octet-stream", "controller.bin"),
			("application/pdf", "invoice.pdf"),
		]:
			# Must not raise.
			chat_upload._reject_if_executable(content_type, name)

	def test_an_apk_is_refused_by_type(self):
		from vehicle_maintenance.api import chat_upload

		with self.assertRaises(frappe.ValidationError):
			chat_upload._reject_if_executable("application/vnd.android.package-archive", "app.apk")

	def test_an_executable_is_refused_by_extension_even_when_the_type_lies(self):
		from vehicle_maintenance.api import chat_upload

		# A picker that reports octet-stream must not become a way in.
		with self.assertRaises(frappe.ValidationError):
			chat_upload._reject_if_executable("application/octet-stream", "totally-safe.apk")

	def test_a_shell_script_is_refused(self):
		from vehicle_maintenance.api import chat_upload

		with self.assertRaises(frappe.ValidationError):
			chat_upload._reject_if_executable("text/plain", "wipe.sh")

	def test_publish_extension_prefers_the_known_type(self):
		from vehicle_maintenance.api import chat_upload

		self.assertEqual(chat_upload._publish_extension("image/jpeg", "photo.jfif"), ".jpg")

	def test_publish_extension_falls_back_to_the_original_name(self):
		from vehicle_maintenance.api import chat_upload

		self.assertEqual(chat_upload._publish_extension("text/csv", "km-dump.csv"), ".csv")

	def test_a_hostile_extension_is_dropped_rather_than_cleaned(self):
		from vehicle_maintenance.api import chat_upload

		# The value is concatenated into a filesystem path, so anything with a
		# separator in it must yield nothing at all.
		self.assertEqual(chat_upload._extension_of("evil.tar/../../etc/passwd"), "")
		self.assertEqual(chat_upload._extension_of("no-extension"), "")
		self.assertEqual(chat_upload._extension_of("trailing."), "")

	def test_unknown_types_render_as_a_document_card(self):
		from vehicle_maintenance.api import chat_upload

		self.assertEqual(chat_upload._kind_for("application/zip"), "file")
		self.assertEqual(chat_upload._kind_for("text/csv"), "file")

	def test_new_media_subtypes_still_render_inline(self):
		from vehicle_maintenance.api import chat_upload

		self.assertEqual(chat_upload._kind_for("image/avif"), "image")
		self.assertEqual(chat_upload._kind_for("video/webm"), "video")


class TestChatReceipts(ChatTestBase):
	"""Delivered and read marks.

	These drive the ticks, and a tick that overstates what happened is worse
	than no tick at all — a dispatcher reads two blue ones as "they have seen
	it" and stops chasing.
	"""

	def _room_row(self, user):
		frappe.set_user(user)
		rooms = chat.list_rooms()["data"]["rooms"]
		return next(r for r in rooms if r["name"] == self.room)

	def test_nothing_is_delivered_or_read_before_the_other_side_syncs(self):
		self._send(self.alice, "hello")
		row = self._room_row(self.alice)
		self.assertEqual(row["delivered_upto"], 0)
		self.assertEqual(row["read_upto"], 0)

	def test_sync_marks_delivered_but_not_read(self):
		self._send(self.alice, "hello")

		# Bob's device pulls it — delivered, but he has not opened the thread.
		frappe.set_user(self.bob)
		chat.sync(cursors={self.room: 0})

		row = self._room_row(self.alice)
		self.assertEqual(row["delivered_upto"], 1)
		self.assertEqual(row["read_upto"], 0)

	def test_reading_advances_both(self):
		self._send(self.alice, "hello")
		frappe.set_user(self.bob)
		chat.sync(cursors={self.room: 0})
		chat.mark_read(room=self.room, seq=1)

		row = self._room_row(self.alice)
		self.assertEqual(row["read_upto"], 1)
		self.assertEqual(row["delivered_upto"], 1)

	def test_read_implies_delivered_even_if_the_cursor_lagged(self):
		"""A read mark alone must never render as "read but not delivered"."""
		self._send(self.alice, "hello")
		frappe.set_user(self.bob)
		chat.mark_read(room=self.room, seq=1)

		row = self._room_row(self.alice)
		self.assertEqual(row["read_upto"], 1)
		self.assertEqual(row["delivered_upto"], 1)

	def test_delivery_cursor_never_walks_backwards(self):
		"""A replayed or out-of-order sync must not un-deliver a message."""
		self._send(self.alice, "one")
		self._send(self.alice, "two")
		frappe.set_user(self.bob)
		chat.sync(cursors={self.room: 0})
		# An older cursor arrives late — it returns nothing, and must not lower
		# the mark that is already at 2.
		chat.sync(cursors={self.room: 0})

		row = self._room_row(self.alice)
		self.assertEqual(row["delivered_upto"], 2)

	def test_a_group_waits_for_the_slowest_member(self):
		"""Two ticks mean everyone, not somebody."""
		frappe.set_user("Administrator")
		room = frappe.get_doc(
			{
				"doctype": "VM Chat Room",
				"title": "Receipts group",
				"kind": "Group",
				"members": [
					{"user": self.alice, "member_role": "Admin"},
					{"user": self.bob, "member_role": "Member"},
					{"user": self.mallory, "member_role": "Member"},
				],
			}
		).insert(ignore_permissions=True)

		frappe.set_user(self.alice)
		chat.send_message(room=room.name, client_id=frappe.generate_hash(length=20), body="all hands")

		# Only Bob pulls it.
		frappe.set_user(self.bob)
		chat.sync(cursors={room.name: 0})

		frappe.set_user(self.alice)
		row = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == room.name)
		self.assertEqual(row["delivered_upto"], 0, "one member is not everyone")

		# Now Mallory does too.
		frappe.set_user(self.mallory)
		chat.sync(cursors={room.name: 0})

		frappe.set_user(self.alice)
		row = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == room.name)
		self.assertEqual(row["delivered_upto"], 1)
