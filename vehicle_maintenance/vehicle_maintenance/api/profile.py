# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Who someone is, as the app needs it: a name, a face, and what they do.

Three decisions worth knowing before changing anything here:

* **A photo is a face, not evidence.** Everything else this app photographs is
  stamped with time, coordinates and the capturing user's name, because it is
  proof of work. A profile picture is the opposite — it exists so a colleague
  recognises the person in a thread — so it is uploaded plainly and stored
  **public**. Private files are permission-checked against the User they hang
  off, and a technician cannot read another technician's User doc, so a private
  avatar renders as a broken circle for everyone except its owner.

* **Completeness is reported, never enforced.** `profile_complete` tells the app
  whether to show its banner. The server does not refuse anything over a missing
  photo: a technician at a depot gate with one bar of signal still has a job to
  do. See the prompt-dismissal field for the other half of that.

* **Designations are a picklist.** The people using this app are not typists,
  and free text turns an org chart into forty spellings of "senior technician".
"""

import frappe
from frappe import _
from frappe.utils import cint, now_datetime

from vehicle_maintenance.fleet_service.doctype.vm_app_preference import vm_app_preference as app_preference

# How long a dismissed "finish your profile" banner stays away. Long enough not
# to nag, short enough that it is not a way of never doing it.
PROMPT_SNOOZE_DAYS = 7

# What the app may set a tone to without us shipping a new build. A bundled id,
# or anything prefixed `system:` — the escape hatch for a sound off their phone.
BUNDLED_TONES = ("default", "chime", "ping", "knock", "bell", "none")


def _tone_ok(value: str) -> bool:
	value = (value or "").strip()
	return value in BUNDLED_TONES or value.startswith("system:")


def _photo_of(user_doc) -> str | None:
	return (user_doc.user_image or "").strip() or None


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
		"photo": photo,
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
	if cint(row.is_private):
		frappe.throw(_("A profile picture has to be public so your colleagues can see it."))
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
