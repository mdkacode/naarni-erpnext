# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Resumable chunked uploads for chat attachments.

Why this exists at all: Frappe's core `upload_file` does
``content = file.stream.read()`` (frappe/handler.py), buffering the entire upload
in the worker before anything touches disk. A 400 MB video costs 400 MB+ of
resident memory in one gunicorn worker. Frappe v15 ships no chunked or resumable
alternative, so the mobile client's 500 MB requirement has no server to talk to
without this module.

Registering the result is the second half of the same trap: `File.before_insert`
calls ``save_file(get_content())``, and both of those do a full ``f.read()`` — so
creating a normal File doc for a large file reads it into memory *twice*. We
therefore build the row and `db_insert()` it, having already computed the digests
in a single streaming pass.

Protocol — four calls:

1. ``begin_upload``  → reserves staging state, returns ``upload_id``
2. ``upload_chunk``  → appends bytes at a strict offset, streamed to disk
3. ``chunk_status``  → the authoritative resume point after process death
4. ``commit_upload`` → verifies size + digest, publishes the File and the message

Strictly sequential offsets are deliberate: it makes resume unambiguous and means
a truncated or reordered chunk is rejected instead of silently corrupting a video.
"""

import hashlib
import os

import frappe
from frappe import _
from frappe.utils import cint, get_files_path

from vehicle_maintenance.api.chat import _room_checked
from vehicle_maintenance.fleet_service.chat_notify import preview_for

# 500 MB, overridable per site. This is the app's own ceiling — nginx
# client_max_body_size only has to clear CHUNK_MAX, not this.
DEFAULT_MAX_UPLOAD_BYTES = 500 * 1024 * 1024

# Largest single chunk we will accept. Bounds per-request memory regardless of
# what the client claims, and keeps us well under any proxy body limit.
CHUNK_MAX = 8 * 1024 * 1024

# Block size for streaming copies and digests. Never the whole file.
IO_BLOCK = 1024 * 1024

# Chat's own allow-list. Core's ALLOWED_MIMETYPES (frappe/handler.py) is enforced
# for users without desk access and contains no audio/* entry at all, so voice
# notes are impossible through the core path. We bypass it, which means we owe
# our own list.
ALLOWED_CONTENT_TYPES = {
	"image/jpeg": ".jpg",
	"image/png": ".png",
	"image/webp": ".webp",
	"video/mp4": ".mp4",
	"video/quicktime": ".mov",
	"audio/mp4": ".m4a",
	"audio/aac": ".aac",
	"audio/mpeg": ".mp3",
	"audio/ogg": ".ogg",
	"audio/opus": ".opus",
	"application/pdf": ".pdf",
}

CONTENT_TYPE_KIND = {
	"image/jpeg": "image",
	"image/png": "image",
	"image/webp": "image",
	"video/mp4": "video",
	"video/quicktime": "video",
	"audio/mp4": "audio",
	"audio/aac": "audio",
	"audio/mpeg": "audio",
	"audio/ogg": "audio",
	"audio/opus": "audio",
	"application/pdf": "image",  # rendered as a document card client-side
}


def _max_upload_bytes() -> int:
	return cint(frappe.get_conf().get("chat_max_upload_bytes")) or DEFAULT_MAX_UPLOAD_BYTES


def _owned_upload(upload_id: str):
	"""Load a staging record the caller owns, or throw."""
	if not upload_id or not frappe.db.exists("VM Chat Upload", upload_id):
		frappe.throw(_("Upload not found."), frappe.DoesNotExistError)
	doc = frappe.get_doc("VM Chat Upload", upload_id)
	if doc.owner != frappe.session.user:
		frappe.throw(_("This upload belongs to another user."), frappe.PermissionError)
	return doc


# ------------------------------------------------------------------ 1. begin


@frappe.whitelist()
def begin_upload(
	room: str, file_name: str, total_size: int, content_type: str, sha256: str | None = None
) -> dict:
	"""Reserve staging space for one file and return its upload_id.

	Returns: {success, data: {upload_id, chunk_size, bytes_received}}.
	"""
	_room_checked(room)

	content_type = (content_type or "").strip().lower()
	if content_type not in ALLOWED_CONTENT_TYPES:
		frappe.throw(_("Files of type {0} cannot be sent in chat.").format(content_type or "unknown"))

	total_size = cint(total_size)
	if total_size <= 0:
		frappe.throw(_("total_size must be a positive number of bytes."))
	limit = _max_upload_bytes()
	if total_size > limit:
		frappe.throw(
			_("That file is {0} MB. The limit is {1} MB.").format(
				total_size // (1024 * 1024), limit // (1024 * 1024)
			)
		)

	doc = frappe.get_doc(
		{
			"doctype": "VM Chat Upload",
			"room": room,
			"file_name": (file_name or "attachment")[:140],
			"content_type": content_type,
			"total_size": total_size,
			"bytes_received": 0,
			"sha256": (sha256 or "").strip().lower() or None,
			"status": "Staging",
		}
	)
	doc.insert(ignore_permissions=True)

	# Create the (empty) staging file now so a later seek/append never has to
	# branch on existence.
	open(doc.staging_path, "wb").close()

	return {
		"success": True,
		"data": {"upload_id": doc.name, "chunk_size": CHUNK_MAX, "bytes_received": 0},
	}


# ------------------------------------------------------------------ 2. chunk


@frappe.whitelist()
def upload_chunk() -> dict:
	"""Append one chunk at a strict byte offset. Streamed — never fully buffered.

	Multipart form: `upload_id`, `offset`, and the `chunk` file part.

	An offset that does not equal `bytes_received` is rejected rather than
	written, so a duplicated or reordered chunk can never corrupt the file. A
	client that resumes simply asks `chunk_status` first.

	Returns: {success, data: {bytes_received, total_size, complete}}.
	"""
	upload_id = frappe.form_dict.get("upload_id")
	offset = cint(frappe.form_dict.get("offset"))
	doc = _owned_upload(upload_id)

	if doc.status != "Staging":
		frappe.throw(_("This upload is already {0}.").format(doc.status.lower()))

	part = (frappe.request.files or {}).get("chunk")
	if part is None:
		frappe.throw(_("No chunk was attached."))

	received = cint(doc.bytes_received)
	if offset != received:
		# Not an error the user can act on — the client resyncs and retries.
		frappe.throw(
			_("Chunk offset {0} does not match the expected {1}.").format(offset, received),
			title=_("Out of order"),
		)

	remaining = cint(doc.total_size) - received
	if remaining <= 0:
		frappe.throw(_("This upload has already received all of its bytes."))

	written = 0
	ceiling = min(remaining, CHUNK_MAX)
	try:
		with open(doc.staging_path, "r+b") as dest:
			dest.seek(received)
			while written < ceiling:
				block = part.stream.read(min(IO_BLOCK, ceiling - written))
				if not block:
					break
				dest.write(block)
				written += len(block)
			# Anything still in the stream means the client sent more than it was
			# allowed to. Stop rather than silently truncating a file we will then
			# happily hash and publish.
			if part.stream.read(1):
				raise ValueError(f"chunk exceeds the {ceiling} byte ceiling")
	except Exception as exc:
		doc.discard(reason=str(exc))
		frappe.log_error(title=f"Chat chunk write failed ({upload_id})", message=frappe.get_traceback())
		frappe.throw(_("The upload could not be written. Start it again."))

	if not written:
		frappe.throw(_("The chunk was empty."))

	total_now = received + written
	doc.db_set("bytes_received", total_now, update_modified=False)

	return {
		"success": True,
		"data": {
			"bytes_received": total_now,
			"total_size": cint(doc.total_size),
			"complete": total_now >= cint(doc.total_size),
		},
	}


# ----------------------------------------------------------------- 3. status


@frappe.whitelist()
def chunk_status(upload_id: str) -> dict:
	"""The authoritative resume point.

	After process death the client trusts this over its own bookkeeping, which is
	what makes a 400 MB upload survive a swipe from Recents.

	Returns: {success, data: {upload_id, bytes_received, total_size, status}}.
	"""
	doc = _owned_upload(upload_id)
	return {
		"success": True,
		"data": {
			"upload_id": doc.name,
			"bytes_received": cint(doc.bytes_received),
			"total_size": cint(doc.total_size),
			"status": doc.status,
		},
	}


# ----------------------------------------------------------------- 4. commit


def _digest_and_size(path: str) -> tuple[str, str, int]:
	"""Stream the file once, returning (md5_hex, sha256_hex, size)."""
	md5 = hashlib.md5(usedforsecurity=False)
	sha = hashlib.sha256()
	size = 0
	with open(path, "rb") as f:
		while True:
			block = f.read(IO_BLOCK)
			if not block:
				break
			md5.update(block)
			sha.update(block)
			size += len(block)
	return md5.hexdigest(), sha.hexdigest(), size


@frappe.whitelist()
def commit_upload(
	upload_id: str,
	client_id: str,
	body: str | None = None,
	reply_to: str | None = None,
	duration_ms: int | None = None,
	lat: float | None = None,
	lon: float | None = None,
	vehicle: str | None = None,
	ticket: str | None = None,
) -> dict:
	"""Verify the staged file, publish it as a private File, and post the message.

	Idempotent: committing an already-committed upload returns the message it
	produced instead of creating a second one.

	Returns: {success, data: {message: {...}, file_url, duplicate}}.
	"""
	doc = _owned_upload(upload_id)

	if doc.status == "Committed":
		existing = frappe.get_doc("VM Chat Message", doc.message)
		return {
			"success": True,
			"data": {
				"message": existing.as_payload(),
				"file_url": doc.file_doc and existing.file_url,
				"duplicate": True,
			},
		}
	if doc.status != "Staging":
		frappe.throw(_("This upload was aborted. Start it again."))

	room_doc = _room_checked(doc.room)

	md5_hex, sha_hex, actual_size = _digest_and_size(doc.staging_path)
	if actual_size != cint(doc.total_size):
		doc.discard(reason=f"size mismatch: got {actual_size}, expected {doc.total_size}")
		frappe.throw(_("The upload is incomplete. Send it again."))
	if doc.sha256 and doc.sha256 != sha_hex:
		doc.discard(reason="sha256 mismatch")
		frappe.throw(_("The upload was corrupted in transit. Send it again."))

	# Move into the site's private files. Same volume, so this is a rename, not a
	# copy — a 500 MB file must not be duplicated on disk to be published.
	ext = ALLOWED_CONTENT_TYPES.get(doc.content_type, "")
	disk_name = f"chat_{frappe.generate_hash(length=12)}{ext}"
	target = get_files_path(disk_name, is_private=True)
	try:
		os.replace(doc.staging_path, target)
	except OSError:
		frappe.log_error(title=f"Chat upload publish failed ({upload_id})", message=frappe.get_traceback())
		frappe.throw(_("The upload could not be saved. Try again."))

	file_url = f"/private/files/{disk_name}"
	file_doc = _insert_file_row(
		disk_name=disk_name,
		file_url=file_url,
		size=actual_size,
		content_hash=md5_hex,
		room=doc.room,
	)

	kind = CONTENT_TYPE_KIND.get(doc.content_type, "image")

	from vehicle_maintenance.api.chat import _advance_cursor

	# Idempotency on client_id, same contract as a text send.
	existing_name = frappe.db.get_value("VM Chat Message", {"client_id": client_id}, "name")
	if existing_name:
		msg = frappe.get_doc("VM Chat Message", existing_name)
		duplicate = True
	else:
		seq = room_doc.allocate_seq()
		msg = frappe.get_doc(
			{
				"doctype": "VM Chat Message",
				"room": doc.room,
				"seq": seq,
				"client_id": client_id,
				"author": frappe.session.user,
				"kind": kind,
				"body": (body or "").strip(),
				"reply_to": reply_to,
				"file_url": file_url,
				"file_name": doc.file_name,
				"file_size": actual_size,
				"duration_ms": cint(duration_ms) or None,
				"lat": lat,
				"lon": lon,
				"geotagged": 1 if (lat is not None and lon is not None) else 0,
				"vehicle": vehicle,
				"ticket": ticket,
			}
		)
		msg.insert(ignore_permissions=True)
		room_doc.touch_last_message(preview_for(msg), msg.creation)
		_advance_cursor(doc.room, frappe.session.user, msg.seq)
		duplicate = False

	doc.db_set({"status": "Committed", "file_doc": file_doc, "message": msg.name}, update_modified=False)

	return {
		"success": True,
		"data": {"message": msg.as_payload(), "file_url": file_url, "duplicate": duplicate},
	}


def _insert_file_row(disk_name: str, file_url: str, size: int, content_hash: str, room: str) -> str:
	"""Register an already-on-disk private file without re-reading it.

	`db_insert` bypasses File.before_insert on purpose — that path calls
	`save_file(get_content())`, and both do a full `f.read()`, so a normal insert
	would pull the entire file into memory twice for a file we have already
	written and hashed in one streaming pass.

	`attached_to_doctype/name` points at the room, which is what makes the
	attachment readable by every member: File.has_permission delegates to the
	referenced document, and VM Chat Room's permission hook is membership-based.
	Without this the uploader would be the only person able to open their own
	photo.
	"""
	doc = frappe.get_doc(
		{
			"doctype": "File",
			"file_name": disk_name,
			"file_url": file_url,
			"is_private": 1,
			"file_size": size,
			"content_hash": content_hash,
			"folder": "Home/Attachments",
			"attached_to_doctype": "VM Chat Room",
			"attached_to_name": room,
		}
	)
	doc.name = frappe.generate_hash(length=10)  # matches File.autoname for non-folders
	doc.db_insert()
	return doc.name


@frappe.whitelist()
def abort_upload(upload_id: str) -> dict:
	"""Discard a staged upload and its partial bytes."""
	doc = _owned_upload(upload_id)
	if doc.status == "Committed":
		frappe.throw(_("That upload is already committed."))
	doc.discard(reason="aborted by client")
	return {"success": True, "data": {"upload_id": doc.name, "status": "Aborted"}}


# ---------------------------------------------------------------- housekeeping


def cleanup_stale_uploads(older_than_hours: int = 48) -> None:
	"""Scheduled sweep — delete staging files for uploads nobody finished.

	Without this, every abandoned 400 MB video stays on disk forever. Called from
	`scheduler_events` daily.
	"""
	from frappe.utils import add_to_date, now_datetime

	cutoff = add_to_date(now_datetime(), hours=-abs(cint(older_than_hours)))
	stale = frappe.get_all(
		"VM Chat Upload",
		filters={"status": "Staging", "modified": ["<", cutoff]},
		fields=["name"],
		limit_page_length=500,
	)
	for row in stale:
		try:
			frappe.get_doc("VM Chat Upload", row["name"]).discard(reason="expired before commit")
		except Exception:
			frappe.log_error(
				title=f"Chat upload sweep failed ({row['name']})", message=frappe.get_traceback()
			)
	if stale:
		frappe.db.commit()
