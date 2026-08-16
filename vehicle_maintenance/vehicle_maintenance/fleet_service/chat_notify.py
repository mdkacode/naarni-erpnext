# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Fan-out for a newly inserted VM Chat Message.

Three channels, each with a different job:

* **Doc room** (`doc:VM Chat Room/{name}`) carries the full message body. Only
  clients with the thread open are joined, so this is the low-latency path for
  the screen the user is actually looking at.
* **User rooms** (`user:{user}`) carry a lightweight envelope — room, seq,
  preview. Sockets join their own user room automatically on connect, so this
  needs no subscribe round-trip and survives every reconnect. It is what keeps
  the unread badge correct.
* **FCM push**, data-only, for members who are not muted. Data-only matters:
  a payload with a `notification` block is handled by the system tray and
  `onMessageReceived` never runs while the app is backgrounded, which is exactly
  when a Doze wake-up needs the app's own code to run.

Every channel is best-effort. A realtime or push failure must never roll back the
message that was already committed.
"""

import frappe

from vehicle_maintenance.fleet_service.doctype.vm_chat_room.vm_chat_room import VMChatRoom

# Longest preview we put on the wire / in a notification body.
PREVIEW_CHARS = 140

# What a non-text message reads as in a list row or a notification.
KIND_PREVIEW = {
	"image": "\U0001f4f7 Photo",
	"video": "\U0001f3a5 Video",
	"audio": "\U0001f3a4 Voice note",
	"file": "\U0001f4ce Document",
	"system": "",
	"ticket": "\U0001f3ab",
	"alert": "⚠️",
}

# Kinds whose whole point is the body text, not an attachment.
TEXTUAL_KINDS = ("text", "system")


def preview_for(msg) -> str:
	"""One-line summary of a message for list rows and notification bodies."""
	if msg.kind in TEXTUAL_KINDS:
		return (msg.body or "")[:PREVIEW_CHARS]
	if msg.kind in ("ticket", "alert"):
		# The body already reads as a sentence; the glyph just makes the row
		# scannable in a list of thirty conversations.
		first_line = (msg.body or "").split("\n", 1)[0]
		return f"{KIND_PREVIEW[msg.kind]} {first_line}"[:PREVIEW_CHARS]
	label = KIND_PREVIEW.get(msg.kind, "Attachment")
	caption = (msg.body or "").strip()
	return f"{label} · {caption}"[:PREVIEW_CHARS] if caption else label


def dispatch(msg) -> None:
	"""Publish `msg` to the doc room, every member's user room, and push."""
	try:
		room: VMChatRoom = frappe.get_cached_doc("VM Chat Room", msg.room)
	except Exception:
		frappe.log_error(title=f"Chat fan-out: room {msg.room} missing", message=frappe.get_traceback())
		return

	preview = preview_for(msg)
	author_name = frappe.db.get_value("User", msg.author, "full_name") or msg.author
	mentioned = {m.user for m in (msg.mentions or [])}

	_publish_doc_room(msg)
	_publish_user_rooms(msg, room, preview, author_name, mentioned)
	_enqueue_push(msg, room, preview, author_name, mentioned)
	_log_mentions(msg, room, preview, author_name, mentioned)


def _publish_doc_room(msg) -> None:
	"""Full message body to whoever has the thread open."""
	try:
		frappe.publish_realtime(
			event="vm_chat_message",
			message=msg.as_payload(),
			doctype="VM Chat Room",
			docname=msg.room,
			after_commit=True,
		)
	except Exception:
		frappe.log_error(title=f"Chat doc-room publish failed ({msg.room})", message=frappe.get_traceback())


def _publish_user_rooms(msg, room: VMChatRoom, preview: str, author_name: str, mentioned: set[str]) -> None:
	"""Lightweight envelope to each member, so badges stay right without a subscribe."""
	envelope = {
		"room": msg.room,
		"room_title": room.title,
		"seq": msg.seq,
		"client_id": msg.client_id,
		"author": msg.author,
		"author_name": author_name,
		"kind": msg.kind,
		"preview": preview,
	}
	for member in room.members:
		# The author's own devices still get this — a second phone needs its
		# badge and read cursor moved just like anyone else's.
		try:
			frappe.publish_realtime(
				event="vm_chat_envelope",
				# Per-recipient, so the phone can chime differently for a mention
				# without re-parsing the body it has not downloaded yet.
				message={**envelope, "mentioned": member.user in mentioned},
				user=member.user,
				after_commit=True,
			)
		except Exception:
			frappe.log_error(
				title=f"Chat envelope publish failed (user={member.user})",
				message=frappe.get_traceback(),
			)


def recipients_for_push(msg, room: VMChatRoom, mentioned: set[str]) -> list[str]:
	"""Who should be woken up by this message.

	Three rules, in order:

	* never the author — their other devices sync silently over the envelope;
	* `notify_push` off is an explicit opt-out and is always honoured;
	* `muted` silences the room, **except** when the message names you. Muting a
	  busy depot channel is how people cope with volume, and if a mute also
	  swallowed "@ravi the bus at gate 3 won't start" then nobody could ever
	  afford to mute anything.
	"""
	out = []
	for m in room.members:
		if m.user == msg.author or not m.notify_push:
			continue
		if m.muted and m.user not in mentioned:
			continue
		out.append(m.user)
	return out


def _enqueue_push(msg, room: VMChatRoom, preview: str, author_name: str, mentioned: set[str]) -> None:
	"""Data-only FCM to every member who should hear about this."""
	if not frappe.get_conf().get("notifications_push_enabled"):
		return

	recipients = recipients_for_push(msg, room, mentioned)
	if not recipients:
		return

	tokens = frappe.get_all(
		"Push Token",
		filters={"user": ["in", recipients], "is_active": 1},
		fields=["device_token", "user"],
		limit_page_length=0,
	)
	for row in tokens:
		is_mention = row["user"] in mentioned
		frappe.enqueue(
			method="vehicle_maintenance.fleet_service.chat_notify.push_job",
			queue="short",
			timeout=30,
			enqueue_after_commit=True,
			job_name=f"chat-push:{msg.room}:{msg.seq}:{row['user']}",
			device_token=row["device_token"],
			room=msg.room,
			room_title=room.title,
			seq=msg.seq,
			author_name=author_name,
			preview=preview,
			mention=is_mention,
		)


def _log_mentions(msg, room: VMChatRoom, preview: str, author_name: str, mentioned: set[str]) -> None:
	"""A mention also lands in the notification bell.

	Push is best-effort and disappears when it is swiped away; a mention is a
	request directed at one person and needs somewhere durable to sit until they
	deal with it. Ordinary messages deliberately do not get one — thirty depot
	messages an hour would make the bell useless.
	"""
	for user in mentioned:
		if user == msg.author:
			continue
		try:
			frappe.get_doc(
				{
					"doctype": "Notification Log",
					"for_user": user,
					"type": "Mention",
					"subject": f"{author_name} mentioned you in {room.title}",
					"email_content": preview,
					"document_type": "VM Chat Room",
					"document_name": msg.room,
					"from_user": msg.author,
				}
			).insert(ignore_permissions=True)
		except Exception:
			frappe.log_error(
				title=f"Chat mention log failed (user={user})",
				message=frappe.get_traceback(),
			)


def push_job(
	device_token: str,
	room: str,
	room_title: str,
	seq: int,
	author_name: str,
	preview: str,
	mention: bool = False,
) -> None:
	"""Background worker — one data-only FCM HTTP v1 message.

	No `notification` block, deliberately. With one present Android renders the
	alert itself and skips `onMessageReceived` while the app is backgrounded; the
	app would then be unable to write the message into Room, group the alert, or
	honour a per-room mute. Data-only plus `priority: HIGH` gets our own code a
	brief wake-up even in Doze, which is what TC01 depends on.

	No-ops when FCM is unconfigured, so this is safe to ship before credentials.
	"""
	from vehicle_maintenance.fleet_service.notifications import _fcm_access_token

	creds = _fcm_access_token()
	if not creds:
		frappe.logger().info(f"[chat-push] FCM not configured; skipped room={room} seq={seq}")
		return
	access_token, project_id = creds

	try:
		import requests

		resp = requests.post(
			f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send",
			headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"},
			json={
				"message": {
					"token": device_token,
					"data": {
						"type": "chat",
						"room": room,
						"room_title": room_title or "",
						"seq": str(seq),
						"mention": "1" if mention else "0",
						"title": room_title or "New message",
						"body": (
							f"{author_name} mentioned you: {preview}"
							if mention
							else (f"{author_name}: {preview}" if preview else author_name)
						),
						"deeplink": f"naarni://chat/{room}?msg={seq}",
					},
					"android": {
						"priority": "HIGH",
						# Collapse a burst in one room into the latest message
						# rather than stacking twenty trays on the lock screen.
						# A mention is exempt: it is addressed to one person, and
						# being swallowed by the next chatty message in the room is
						# exactly the failure that makes people stop trusting it.
						**({} if mention else {"collapse_key": f"chat-{room}"}),
					},
				}
			},
			timeout=15,
		)
		if resp.status_code in (400, 404) and (
			"UNREGISTERED" in resp.text or "registration-token-not-registered" in resp.text
		):
			frappe.db.set_value(
				"Push Token", {"device_token": device_token}, "is_active", 0, update_modified=False
			)
		elif resp.status_code >= 300:
			frappe.log_error(title="Chat FCM non-2xx", message=resp.text[:500])
	except Exception:
		frappe.log_error(title="Chat FCM send failed", message=frappe.get_traceback())
