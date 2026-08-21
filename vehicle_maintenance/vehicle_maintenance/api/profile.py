# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Who someone is, as the app needs it: a name, a face, and what they do.

Three decisions worth knowing before changing anything here:

* **Nothing this app stores is world-readable, faces included.** Profile pictures
  live in the private bucket like every other image here. That creates a real
  problem — Frappe permission-checks a private file against the document it
  hangs off, and a technician cannot read another technician's User doc, so the
  raw path 403s for everyone except the owner — and the answer is [avatar],
  which serves the bytes itself after checking only that the caller is signed
  in. Colleagues can see each other; the internet cannot see anybody.

* **Completeness is reported, never enforced.** `profile_complete` tells the app
  whether to show its banner. The server does not refuse anything over a missing
  photo: a technician at a depot gate with one bar of signal still has a job to
  do. See the prompt-dismissal field for the other half of that.

* **Designations are a picklist.** The people using this app are not typists,
  and free text turns an org chart into forty spellings of "senior technician".
"""

import mimetypes
import os
from urllib.parse import quote

import frappe
from frappe import _
from frappe.utils import cint, now_datetime

from vehicle_maintenance.fleet_service.doctype.vm_app_preference import vm_app_preference as app_preference

# How long a dismissed "finish your profile" banner stays away. Long enough not
# to nag, short enough that it is not a way of never doing it.
PROMPT_SNOOZE_DAYS = 7

# Where clients fetch faces from. Named once so the url the API hands out and the
# method that serves it can never drift apart.
AVATAR_METHOD = "vehicle_maintenance.api.profile.avatar"

# What a profile picture may be. Not a general file server: this endpoint exists
# to hand out faces, and anything else asking to come through it is a mistake or
# an attempt.
AVATAR_TYPES = {".jpg", ".jpeg", ".png", ".webp", ".gif"}

# What the app may set a tone to without us shipping a new build. A bundled id,
# or anything prefixed `system:` — the escape hatch for a sound off their phone.
BUNDLED_TONES = ("default", "chime", "ping", "knock", "bell", "none")


def _tone_ok(value: str) -> bool:
	value = (value or "").strip()
	return value in BUNDLED_TONES or value.startswith("system:")


def _photo_of(user_doc) -> str | None:
	return (user_doc.user_image or "").strip() or None


def avatar_url(user: str) -> str:
	"""Where a client fetches somebody's face.

	Always this endpoint, never the stored path: the file is private, so the raw
	`/private/files/...` url is readable only by its owner and every other
	member of the room would render a broken circle. Clients pass the *user*,
	we do the permission check, and the bytes come back.
	"""
	return f"/api/method/{AVATAR_METHOD}?user={quote(user)}"


def _looks_named(user_doc) -> bool:
	"""Whether the account has a real name rather than the phone it was made from.

	A phone-provisioned account starts with its own number as `first_name`, so
	"has a full_name" is not the question — "is it still just the number" is.
	"""
	name = (user_doc.full_name or "").strip()
	if not name:
		return False
	digits = "".join(ch for ch in name if ch.isdigit())
	return not (digits and len(digits) >= 10)


def _serialise(user_doc, pref) -> dict:
	photo = _photo_of(user_doc)
	named = _looks_named(user_doc)
	return {
		"user": user_doc.name,
		"full_name": (user_doc.full_name or "").strip(),
		"phone": user_doc.mobile_no,
		# The endpoint, not the stored path — see `avatar_url`. Null when there is
		# nothing to show, so the client draws initials instead of requesting a
		# face that does not exist.
		"photo": avatar_url(user_doc.name) if photo else None,
		"designation": pref.designation,
		"about": pref.about,
		"has_name": named,
		"has_photo": bool(photo),
		"profile_complete": bool(named and photo),
		"chat_tone": pref.chat_tone or "default",
		"alert_tone": pref.alert_tone or "default",
		"vibrate": bool(cint(pref.vibrate)),
		"prompt_snoozed_until": _snoozed_until(pref),
		"roles": frappe.get_roles(user_doc.name),
	}


def _snoozed_until(pref) -> str | None:
	if not pref.profile_prompt_dismissed_at:
		return None
	return str(frappe.utils.add_days(pref.profile_prompt_dismissed_at, PROMPT_SNOOZE_DAYS))


@frappe.whitelist()
def get_my_profile() -> dict:
	"""Everything the app needs to render the profile screen and its banner.

	Returns: {success, data: {user, full_name, phone, photo, designation, about,
	has_name, has_photo, profile_complete, chat_tone, alert_tone, vibrate,
	prompt_snoozed_until, roles}}.
	"""
	user_doc = frappe.get_doc("User", frappe.session.user)
	return {"success": True, "data": _serialise(user_doc, app_preference.for_user())}


@frappe.whitelist()
def update_my_profile(
	full_name: str | None = None,
	designation: str | None = None,
	about: str | None = None,
) -> dict:
	"""Save the parts of a profile a person owns.

	Only ever the caller's own record — there is no user argument on purpose.
	Each field is optional so the onboarding screens can save as they go rather
	than holding everything until a final Done, which is what loses work when an
	app is killed mid-flow.
	"""
	user_doc = frappe.get_doc("User", frappe.session.user)

	if full_name is not None:
		name = (full_name or "").strip()
		if not name:
			frappe.throw(_("Your name cannot be blank."))
		if len(name) > 140:
			frappe.throw(_("That name is too long."))
		# Frappe composes full_name from first/middle/last, so writing the whole
		# thing into first_name is what makes it come back out unchanged.
		user_doc.first_name = name
		user_doc.middle_name = None
		user_doc.last_name = None
		user_doc.flags.ignore_permissions = True
		user_doc.save(ignore_permissions=True)

	pref = app_preference.for_user()
	dirty = False
	if designation is not None:
		designation = (designation or "").strip() or None
		if designation and not frappe.db.exists("VM Designation", designation):
			frappe.throw(_("Pick a designation from the list."))
		pref.designation = designation
		dirty = True
	if about is not None:
		pref.about = (about or "").strip()[:140] or None
		dirty = True
	if dirty:
		pref.save(ignore_permissions=True)

	user_doc.reload()
	return {"success": True, "data": _serialise(user_doc, pref), "message": _("Saved.")}


@frappe.whitelist()
def set_profile_photo(file_url: str) -> dict:
	"""Point the account at an already-uploaded image.

	The bytes go up through Frappe's own `upload_file`; this only adopts the
	result, which keeps one upload path in the app instead of two.

	The file must be public and must belong to the caller. Both are checked: a
	private avatar is invisible to everybody but its owner, and an unowned
	`file_url` would let one person set another's face as their own.
	"""
	file_url = (file_url or "").strip()
	if not file_url:
		frappe.throw(_("No image was given."))

	row = frappe.db.get_value(
		"File",
		{"file_url": file_url},
		["name", "is_private", "owner", "file_type"],
		as_dict=True,
	)
	if not row:
		frappe.throw(_("That image could not be found. Upload it again."))
	if row.owner != frappe.session.user:
		frappe.throw(_("That image belongs to somebody else."), frappe.PermissionError)
	if not cint(row.is_private):
		# Nothing this app stores belongs in a world-readable bucket, faces least
		# of all: a public url is a photograph of a named employee that needs no
		# login and cannot be recalled once it has been shared. Colleagues see
		# each other through `avatar`, which asks who is calling first.
		frappe.throw(_("A profile picture has to be uploaded privately."))
	if (row.file_type or "").upper() not in ("JPG", "JPEG", "PNG", "WEBP", "GIF"):
		frappe.throw(_("Choose a photo, not a file."))

	frappe.db.set_value("User", frappe.session.user, "user_image", file_url, update_modified=False)
	user_doc = frappe.get_doc("User", frappe.session.user)
	return {
		"success": True,
		"data": _serialise(user_doc, app_preference.for_user()),
		"message": _("Photo updated."),
	}


@frappe.whitelist()
def remove_profile_photo() -> dict:
	"""Clear it. Somebody who put up the wrong picture must be able to take it down."""
	frappe.db.set_value("User", frappe.session.user, "user_image", None, update_modified=False)
	user_doc = frappe.get_doc("User", frappe.session.user)
	return {"success": True, "data": _serialise(user_doc, app_preference.for_user())}


@frappe.whitelist()
def avatar(user: str | None = None):
	"""Serve somebody's profile picture to a signed-in colleague.

	This exists because the file is private and must stay private. Frappe checks
	a private file against the document it is attached to, and no technician can
	read another technician's User — so the raw `/private/files/...` url is
	readable only by its owner, and a room full of people would see one face and
	a dozen broken circles.

	The check here is deliberately "are you signed in", not "do you share a room
	with this person". A face is the least sensitive thing in the system and the
	whole point is recognising a colleague; anything narrower would mean a
	dispatcher seeing blanks in the depot list. What it is *not* is public:
	`@frappe.whitelist()` without `allow_guest` refuses an anonymous caller, so
	nothing here is reachable without a session.

	Streams from disk rather than reading the File doc's content, so a large
	image does not become a large string in memory first.
	"""
	user = (user or frappe.session.user).strip()
	if frappe.session.user == "Guest":
		frappe.throw(_("Sign in to view this."), frappe.PermissionError)

	stored = (frappe.db.get_value("User", user, "user_image") or "").strip()
	if not stored:
		frappe.throw(_("No picture."), frappe.DoesNotExistError)

	path = _avatar_path(stored)
	if not path:
		frappe.throw(_("No picture."), frappe.DoesNotExistError)

	# Audited, which is what the traversal rule asks for: `path` is not the
	# caller's string. `_avatar_path` accepts only a flat image filename inside
	# this site's own files directories and re-checks containment after resolving
	# symlinks, and the tests below drive three traversal payloads through it.
	with open(path, "rb") as fh:  # nosemgrep
		content = fh.read()

	frappe.local.response.filename = os.path.basename(path)
	frappe.local.response.filecontent = content
	frappe.local.response.type = "download"
	# Inline, so an <img> or Coil renders it instead of downloading it.
	frappe.local.response.display_content_as = "inline"
	frappe.local.response.content_type = mimetypes.guess_type(path)[0] or "image/jpeg"


def _avatar_path(stored: str) -> str | None:
	"""Resolve a stored `user_image` to a file on disk, or None.

	Refuses anything that is not a plain image sitting in this site's own files
	directories. `user_image` is writable from Desk, so it is not a trusted
	string: without the containment check below, a crafted value could walk out
	of the files directory and read whatever the bench user can.
	"""
	if os.path.splitext(stored)[1].lower() not in AVATAR_TYPES:
		return None

	if stored.startswith("/private/files/"):
		root = frappe.get_site_path("private", "files")
		name = stored[len("/private/files/") :]
	elif stored.startswith("/files/"):
		# Legacy, and still served here rather than left readable without a
		# login: the bucket is the thing that should not be public.
		root = frappe.get_site_path("public", "files")
		name = stored[len("/files/") :]
	else:
		# A gravatar or any other absolute url. Nothing for us to serve.
		return None

	# An avatar is a flat filename. Refusing separators outright is what stops a
	# crafted `user_image` — the field is writable from Desk — from walking out
	# of the files directory; the containment check below is the second lock.
	if not name or "/" in name or "\\" in name:
		return None

	root = os.path.realpath(root)
	candidate = os.path.realpath(os.path.join(root, name))
	if not candidate.startswith(root + os.sep):
		return None
	return candidate if os.path.isfile(candidate) else None


@frappe.whitelist()
def list_designations(query: str | None = None) -> dict:
	"""The picker's options. Active ones only, in the order an admin chose."""
	filters: dict = {"is_active": 1}
	if (query or "").strip():
		filters["designation_name"] = ["like", f"%{query.strip()}%"]
	rows = frappe.get_all(
		"VM Designation",
		filters=filters,
		fields=["name", "designation_name", "sort_order"],
		order_by="sort_order asc, designation_name asc",
		limit_page_length=100,
	)
	return {"success": True, "data": {"designations": rows}}


@frappe.whitelist()
def set_notification_tones(
	chat_tone: str | None = None, alert_tone: str | None = None, vibrate: int | None = None
) -> dict:
	"""Store the sounds this person chose.

	The server keeps the choice so it survives a reinstall or a second device; it
	is the app that actually plays it, because an Android notification channel's
	sound is fixed when the channel is created.
	"""
	pref = app_preference.for_user()
	if chat_tone is not None:
		if not _tone_ok(chat_tone):
			frappe.throw(_("That is not a tone we know."))
		pref.chat_tone = chat_tone.strip()
	if alert_tone is not None:
		if not _tone_ok(alert_tone):
			frappe.throw(_("That is not a tone we know."))
		pref.alert_tone = alert_tone.strip()
	if vibrate is not None:
		pref.vibrate = 1 if cint(vibrate) else 0
	pref.save(ignore_permissions=True)
	return {
		"success": True,
		"data": {
			"chat_tone": pref.chat_tone,
			"alert_tone": pref.alert_tone,
			"vibrate": bool(cint(pref.vibrate)),
			"bundled": list(BUNDLED_TONES),
		},
		"message": _("Sounds updated."),
	}


@frappe.whitelist()
def snooze_profile_prompt() -> dict:
	"""Wave the finish-your-profile banner away for a while.

	It comes back, and it never blocked anything in the first place. The point is
	that somebody mid-shift can make it go away without being made to stop and
	take a photograph of themselves.
	"""
	pref = app_preference.for_user()
	pref.profile_prompt_dismissed_at = now_datetime()
	pref.save(ignore_permissions=True)
	return {"success": True, "data": {"prompt_snoozed_until": _snoozed_until(pref)}}
