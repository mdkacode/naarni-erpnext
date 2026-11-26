"""Deleting a message for everyone — without deleting it from the database.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_chat_delete

Two promises are being tested at once, and they pull in opposite directions:

* **the conversation forgets** — every member's client, including one that was
  offline when it happened, ends up holding a tombstone rather than the message;
* **the database remembers** — the row, the body and the attachment are all
  still there afterwards.

The delta-sync cases are the ones worth reading. A soft delete mutates a row the
client was handed long ago, and `sync` only ever returns rows *above* the seq
cursor — so without a second counter the deletion is simply never mentioned
again, and the message stays on every phone that already had it.
"""

import frappe
from frappe.utils import add_to_date, now_datetime

from vehicle_maintenance.api import chat
from vehicle_maintenance.api.test_chat import ChatTestBase


class ChatDeleteBase(ChatTestBase):
	def _delete(self, user, message):
		frappe.set_user(user)
		return chat.delete_message(message=message)

	def _sent(self, user=None, body="hello"):
		"""Send one message and hand back its payload."""
		return self._send(user or self.alice, body)["data"]["message"]


class TestDeletePermission(ChatDeleteBase):
	def test_author_can_delete_their_own(self):
		msg = self._sent(self.alice)
		out = self._delete(self.alice, msg["name"])["data"]["message"]
		self.assertTrue(out["deleted"])
		self.assertEqual(out["deleted_by"], self.alice)

	def test_another_member_cannot_delete_it(self):
		"""Reading a message is not a claim on it."""
		msg = self._sent(self.alice)
		with self.assertRaises(frappe.PermissionError):
			self._delete(self.bob, msg["name"])
		self.assertFalse(frappe.db.get_value("VM Chat Message", msg["name"], "deleted"))

	def test_a_non_member_cannot_delete_it(self):
		"""Mallory is in no room here, so this must fail on membership first."""
		msg = self._sent(self.alice)
		with self.assertRaises(frappe.PermissionError):
			self._delete(self.mallory, msg["name"])

	def test_a_supervisor_can_delete_somebody_elses(self):
		"""Someone has to be able to pull a photo posted to the wrong room."""
		msg = self._sent(self.alice)
		out = self._delete("Administrator", msg["name"])["data"]["message"]
		self.assertTrue(out["deleted"])
		self.assertEqual(out["deleted_by"], "Administrator")

	def test_deleting_twice_is_idempotent(self):
		"""A retry after a lost response must not allocate a second tombstone."""
		msg = self._sent(self.alice)
		first = self._delete(self.alice, msg["name"])
		second = self._delete(self.alice, msg["name"])
		self.assertFalse(first["data"]["duplicate"])
		self.assertTrue(second["data"]["duplicate"])
		self.assertEqual(
			first["data"]["message"]["delete_seq"],
			second["data"]["message"]["delete_seq"],
		)

	def test_deleting_something_that_never_existed(self):
		with self.assertRaises(frappe.DoesNotExistError):
			self._delete(self.alice, "VMCM-nope-0000")


class TestDeleteWindow(ChatDeleteBase):
	"""A message can be taken back for a while, then it is part of the record."""

	def _age(self, message, minutes):
		"""Backdate a message, so the window can be tested without waiting."""
		frappe.db.set_value(
			"VM Chat Message",
			message,
			"creation",
			add_to_date(now_datetime(), minutes=-minutes),
			update_modified=False,
		)

	def test_a_fresh_message_can_be_deleted(self):
		msg = self._sent(self.alice)
		self._age(msg["name"], 5)
		self.assertTrue(self._delete(self.alice, msg["name"])["data"]["message"]["deleted"])

	def test_an_old_message_cannot(self):
		msg = self._sent(self.alice)
		self._age(msg["name"], chat.DELETE_WINDOW_SECONDS // 60 + 1)
		with self.assertRaises(frappe.ValidationError):
			self._delete(self.alice, msg["name"])
		self.assertFalse(frappe.db.get_value("VM Chat Message", msg["name"], "deleted"))

	def test_the_refusal_never_quotes_the_window(self):
		"""No countdown anywhere a user can see. It only invites racing it."""
		msg = self._sent(self.alice)
		self._age(msg["name"], 120)
		with self.assertRaises(frappe.ValidationError) as caught:
			self._delete(self.alice, msg["name"])
		text = str(caught.exception)
		self.assertNotIn("30", text)
		self.assertNotIn("minute", text.lower())

	def test_a_supervisor_is_held_to_the_same_clock(self):
		"""One answer to 'can this still be taken back', not two."""
		msg = self._sent(self.alice)
		self._age(msg["name"], 120)
		with self.assertRaises(frappe.ValidationError):
			self._delete("Administrator", msg["name"])

	def test_a_late_retry_of_a_delete_that_worked_is_not_an_error(self):
		"""The window is checked after idempotency, so a lost response is safe."""
		msg = self._sent(self.alice)
		self._delete(self.alice, msg["name"])
		self._age(msg["name"], 120)
		again = self._delete(self.alice, msg["name"])
		self.assertTrue(again["data"]["duplicate"])
		self.assertTrue(again["data"]["message"]["deleted"])


class TestDeleteHidesTheContent(ChatDeleteBase):
	def test_the_body_stops_being_served(self):
		msg = self._sent(self.alice, "the thing I should not have said")
		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		page = chat.list_messages(room=self.room)["data"]["messages"]
		row = next(m for m in page if m["name"] == msg["name"])
		self.assertEqual(row["body"], "")
		self.assertTrue(row["deleted"])

	def test_the_row_and_the_body_are_still_in_the_database(self):
		"""The point of the whole design: withdrawn from the room, kept on record."""
		msg = self._sent(self.alice, "the thing I should not have said")
		self._delete(self.alice, msg["name"])

		stored = frappe.db.get_value(
			"VM Chat Message", msg["name"], ["body", "deleted", "deleted_by"], as_dict=True
		)
		self.assertEqual(stored["body"], "the thing I should not have said")
		self.assertEqual(stored["deleted"], 1)
		self.assertEqual(stored["deleted_by"], self.alice)

	def test_an_attachment_stops_being_reachable_through_chat(self):
		frappe.set_user(self.alice)
		msg = chat.send_message(
			room=self.room,
			client_id=frappe.generate_hash(length=20),
			kind="image",
			body="",
			file_url="/private/files/chat-delete-test.jpg",
			file_name="chat-delete-test.jpg",
		)["data"]["message"]
		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		page = chat.list_messages(room=self.room)["data"]["messages"]
		row = next(m for m in page if m["name"] == msg["name"])
		self.assertIsNone(row["file_url"])
		self.assertIsNone(row["file_name"])
		# Still on the record, still on disk.
		self.assertEqual(
			frappe.db.get_value("VM Chat Message", msg["name"], "file_url"),
			"/private/files/chat-delete-test.jpg",
		)

	def test_mentions_and_reactions_go_with_it(self):
		"""A withdrawn message must not keep naming somebody or carrying chips."""
		frappe.set_user(self.alice)
		msg = chat.send_message(
			room=self.room,
			client_id=frappe.generate_hash(length=20),
			body="@bob look at this",
			mentions=[self.bob],
		)["data"]["message"]
		frappe.set_user(self.bob)
		chat.toggle_reaction(message=msg["name"], reaction="like")

		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		row = next(
			m for m in chat.list_messages(room=self.room)["data"]["messages"] if m["name"] == msg["name"]
		)
		self.assertEqual(row["mentions"], [])
		self.assertEqual(row["reactions"], [])

	def test_you_cannot_react_to_a_deleted_message(self):
		msg = self._sent(self.alice)
		self._delete(self.alice, msg["name"])
		frappe.set_user(self.bob)
		with self.assertRaises(frappe.ValidationError):
			chat.toggle_reaction(message=msg["name"], reaction="like")


class TestDeleteReachesEveryone(ChatDeleteBase):
	"""The delta-sync half — how a device that was not listening finds out."""

	def test_a_client_holding_the_message_is_told_to_drop_it(self):
		msg = self._sent(self.alice, "withdraw me")

		# Bob syncs, so his cursor is now above the message — the state in which
		# an ordinary delta sync would never mention it again.
		frappe.set_user(self.bob)
		first = chat.sync(cursors={self.room: {"seq": 0, "del": 0}})["data"]["rooms"][self.room]
		self.assertEqual(len(first["messages"]), 1)
		cursor = {"seq": first["last_seq"], "del": first["last_delete_seq"]}

		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		second = chat.sync(cursors={self.room: cursor})["data"]["rooms"][self.room]
		names = [m["name"] for m in second["messages"]]
		self.assertIn(msg["name"], names)
		tomb = next(m for m in second["messages"] if m["name"] == msg["name"])
		self.assertTrue(tomb["deleted"])
		self.assertEqual(tomb["body"], "")

	def test_the_tombstone_is_handed_over_once_not_on_every_sync(self):
		"""Otherwise a quiet room re-sends its whole history of deletions forever."""
		msg = self._sent(self.alice)
		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		first = chat.sync(cursors={self.room: {"seq": 0, "del": 0}})["data"]["rooms"][self.room]
		cursor = {"seq": first["last_seq"], "del": first["last_delete_seq"]}
		second = chat.sync(cursors={self.room: cursor})["data"]["rooms"]
		self.assertNotIn(self.room, second)

	def test_deleting_does_not_move_the_unread_mark(self):
		"""A tombstone drawn from last_seq would leave a badge nobody can clear.

		This is why deletions have a counter of their own: unread is `last_seq`
		minus the read cursor, and a cursor can only reach a seq that some
		message carries.
		"""
		msg = self._sent(self.alice, "one")
		frappe.set_user(self.bob)
		chat.mark_read(room=self.room, seq=msg["seq"])

		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		room = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == self.room)
		self.assertEqual(room["unread"], 0)
		self.assertEqual(room["last_seq"], msg["seq"])

	def test_an_older_client_sending_a_bare_cursor_still_learns_of_it(self):
		"""Builds that predate deletion send `{room: 7}`, not `{room: {...}}`."""
		msg = self._sent(self.alice)
		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		delta = chat.sync(cursors={self.room: msg["seq"]})["data"]["rooms"][self.room]
		tomb = next(m for m in delta["messages"] if m["name"] == msg["name"])
		self.assertTrue(tomb["deleted"])

	def test_a_message_that_is_both_new_and_deleted_appears_once(self):
		"""Sent and withdrawn between two syncs — it must not arrive twice."""
		frappe.set_user(self.bob)
		base = chat.sync(cursors={self.room: {"seq": 0, "del": 0}})["data"]["rooms"].get(self.room)
		cursor = {"seq": base["last_seq"], "del": base["last_delete_seq"]} if base else {"seq": 0, "del": 0}

		msg = self._sent(self.alice, "brief")
		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		delta = chat.sync(cursors={self.room: cursor})["data"]["rooms"][self.room]
		hits = [m for m in delta["messages"] if m["name"] == msg["name"]]
		self.assertEqual(len(hits), 1)
		self.assertTrue(hits[0]["deleted"])

	def test_the_room_list_line_stops_quoting_the_message(self):
		self._sent(self.alice, "older one")
		msg = self._sent(self.alice, "secret plan")

		frappe.set_user(self.bob)
		before = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == self.room)
		self.assertEqual(before["last_message_preview"], "secret plan")

		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		after = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == self.room)
		self.assertNotIn("secret plan", after["last_message_preview"] or "")

	def test_deleting_from_the_middle_leaves_the_room_line_alone(self):
		msg = self._sent(self.alice, "middle")
		self._sent(self.alice, "newest")

		self._delete(self.alice, msg["name"])

		frappe.set_user(self.bob)
		room = next(r for r in chat.list_rooms()["data"]["rooms"] if r["name"] == self.room)
		self.assertEqual(room["last_message_preview"], "newest")

	def test_delete_seq_is_per_room_and_monotonic(self):
		other_room = self._ensure_room()
		a = self._sent(self.alice, "a")
		b = self._sent(self.alice, "b")

		frappe.set_user(self.alice)
		elsewhere = chat.send_message(room=other_room, client_id=frappe.generate_hash(length=20), body="c")[
			"data"
		]["message"]

		self.assertEqual(self._delete(self.alice, a["name"])["data"]["message"]["delete_seq"], 1)
		self.assertEqual(self._delete(self.alice, b["name"])["data"]["message"]["delete_seq"], 2)
		# A different room counts from one again.
		self.assertEqual(self._delete(self.alice, elsewhere["name"])["data"]["message"]["delete_seq"], 1)


class TestDeleteIsAnnounced(ChatDeleteBase):
	def setUp(self):
		super().setUp()
		self.published = []
		self._orig_publish = frappe.publish_realtime
		frappe.publish_realtime = lambda *a, **kw: self.published.append(kw)
		self.addCleanup(setattr, frappe, "publish_realtime", self._orig_publish)

	def test_the_open_thread_and_every_member_are_told(self):
		msg = self._sent(self.alice)
		self.published.clear()
		self._delete(self.alice, msg["name"])

		frames = [p for p in self.published if p.get("event") == "vm_chat_deleted"]
		self.assertTrue(frames, "no vm_chat_deleted frame was published")
		# One to the thread, one to each member's own room.
		self.assertTrue(any(f.get("docname") == self.room for f in frames))
		told = {f.get("user") for f in frames if f.get("user")}
		self.assertEqual(told, {self.alice, self.bob})

	def test_the_frame_carries_what_a_client_needs_to_find_the_row(self):
		msg = self._sent(self.alice)
		self.published.clear()
		self._delete(self.alice, msg["name"])

		frame = next(p for p in self.published if p.get("event") == "vm_chat_deleted")["message"]
		self.assertEqual(frame["message"], msg["name"])
		self.assertEqual(frame["client_id"], msg["client_id"])
		self.assertEqual(frame["room"], self.room)
		self.assertGreater(frame["delete_seq"], 0)

	def test_a_broken_socket_does_not_undo_the_deletion(self):
		msg = self._sent(self.alice)

		# Only our own event fails. Anything wider takes `log_error` down with it
		# — it publishes a frame of its own from inside the except block — and the
		# test would then be measuring its own stub rather than the endpoint.
		def boom(*a, **kw):
			if kw.get("event") == "vm_chat_deleted":
				raise RuntimeError("socket is down")
			return self._orig_publish(*a, **kw)

		frappe.publish_realtime = boom
		self._delete(self.alice, msg["name"])
		self.assertEqual(frappe.db.get_value("VM Chat Message", msg["name"], "deleted"), 1)
