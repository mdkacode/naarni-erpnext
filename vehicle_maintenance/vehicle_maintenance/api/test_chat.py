"""Chat API behaviour — the invariants the mobile client is built on.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_chat

The tests worth reading are the ones covering properties the Android side cannot
recover from if they break: sequence monotonicity, `client_id` idempotency,
membership isolation, and resumable-upload offset discipline.
"""

import hashlib
import io
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
