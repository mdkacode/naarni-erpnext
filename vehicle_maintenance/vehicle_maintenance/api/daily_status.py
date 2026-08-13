# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Daily status endpoints for the mobile app and the stakeholder board.

There is deliberately no "submit your status" endpoint. A status is written by
talking in a chat room, which the app already knows how to do — so the app-facing
surface here is one call, :func:`my_room`, that hands back which room to open.
Everything else is the reading side: the per-depot board a manager scans, one
person's day, and the two ops levers (regenerate, send now).

Every method returns the app's standard `{success, data, message}` envelope.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.utils import cint

from vehicle_maintenance.fleet_service import daily_status, daily_status_digest
from vehicle_maintenance.fleet_service.doctype.vm_daily_status.vm_daily_status import (
	is_status_supervisor,
)

MAX_PAGE = 200
DEFAULT_PAGE = 60


# ─────────────────────────────────── writing ────────────────────────────────


@frappe.whitelist()
def my_room() -> dict:
	"""The caller's own status room, created on first call.

	The app opens this like any other chat thread — no separate status UI. Returns
	the nudge text too, so the app can show what the room is for when it is empty.

	Returns: {success, data: {room, title, reported_today, status_date, prompt}}.
	"""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Please sign in."), frappe.PermissionError)

	room = daily_status.ensure_room(user)
	status_date = daily_status.status_date_now()
	said_something = daily_status.has_reported(user, status_date)

	try:
		prompt = (daily_status.settings().nudge_text or "").strip()
	except Exception:
		prompt = ""

	return {
		"success": True,
		"data": {
			"room": room,
			"title": frappe.db.get_value("VM Chat Room", room, "title"),
			"status_date": status_date,
			"reported_today": said_something,
			"prompt": prompt,
		},
	}


@frappe.whitelist()
def open_for(user: str) -> dict:
	"""A supervisor's way into one person's status thread.

	Status rooms have a single member by design — a manager reads them one at a
	time from the board rather than being enrolled in forty threads that would
	bury their chat list.

	Returns: {success, data: {room, user, user_name}}.
	"""
	if not is_status_supervisor(frappe.session.user):
		frappe.throw(_("You are not allowed to read another person's status."), frappe.PermissionError)
	if not frappe.db.exists("User", user):
		frappe.throw(_("No such user."), frappe.DoesNotExistError)

	room = daily_status.ensure_room(user)
	return {
		"success": True,
		"data": {
			"room": room,
			"user": user,
			"user_name": frappe.db.get_value("User", user, "full_name") or user,
		},
	}


# ─────────────────────────────────── reading ────────────────────────────────


@frappe.whitelist()
def board(
	status_date: str | None = None,
	depot: str | None = None,
	limit: int = DEFAULT_PAGE,
) -> dict:
	"""The day's board: who reported, what they said, who did not.

	Permission-filtered rather than role-gated: a supervisor sees everyone, and
	anyone else sees only their own row, which makes this safe to call from every
	screen without branching in the client.

	Returns: {success, data: {status_date, depot, reported: [...], silent: [...],
	counts: {reported, silent, blocked}}}.
	"""
	status_date = status_date or daily_status.status_date_now()
	limit = min(cint(limit) or DEFAULT_PAGE, MAX_PAGE)

	filters: dict = {"status_date": status_date}
	if depot:
		filters["depot"] = depot
	if not is_status_supervisor(frappe.session.user):
		filters["user"] = frappe.session.user

	docs = frappe.get_all(
		"VM Daily Status",
		filters=filters,
		fields=[
			"name",
			"user",
			"employee_name",
			"depot",
			"state",
			"summary_line",
			"message_count",
			"voice_count",
			"photo_count",
			"generated_by",
			"room",
		],
		order_by="employee_name asc",
		limit_page_length=limit,
	)

	items: dict[str, list[dict]] = {}
	if docs:
		for row in frappe.get_all(
			"VM Daily Status Item",
			filters={"parent": ["in", [d["name"] for d in docs]], "parenttype": "VM Daily Status"},
			fields=["parent", "bucket", "text", "ticket", "vehicle"],
			order_by="idx asc",
			limit_page_length=0,
		):
			items.setdefault(row.pop("parent"), []).append(row)

	reported, silent = [], []
	blocked = 0
	for doc in docs:
		doc["user_name"] = doc.pop("employee_name", None) or doc["user"]
		doc["items"] = items.get(doc["name"], [])
		blocked += sum(1 for i in doc["items"] if i["bucket"] == "Blocker")
		(silent if doc["state"] == "Not Reported" else reported).append(doc)

	return {
		"success": True,
		"data": {
			"status_date": status_date,
			"depot": depot,
			"reported": reported,
			"silent": silent,
			"counts": {"reported": len(reported), "silent": len(silent), "blocked": blocked},
		},
	}


@frappe.whitelist()
def get(name: str) -> dict:
	"""One person's day in full, including what they actually wrote.

	Returns: {success, data: {status: {...}, raw_transcript}}.
	"""
	doc = frappe.get_doc("VM Daily Status", name)
	if not doc.has_permission("read"):
		frappe.throw(_("Not permitted."), frappe.PermissionError)
	payload = doc.as_payload()
	payload["raw_transcript"] = doc.raw_transcript or ""
	payload["generation_note"] = doc.generation_note
	return {"success": True, "data": {"status": payload}}


@frappe.whitelist()
def history(user: str | None = None, days: int = 14) -> dict:
	"""The last `days` of one person's summaries, newest first.

	Defaults to the caller. Reading someone else's history needs a supervisor
	role — the same boundary the board uses.

	Returns: {success, data: {user, days: [...]}}.
	"""
	target = user or frappe.session.user
	if target != frappe.session.user and not is_status_supervisor(frappe.session.user):
		frappe.throw(_("You are not allowed to read another person's status."), frappe.PermissionError)

	days = max(1, min(cint(days) or 14, 90))
	rows = frappe.get_all(
		"VM Daily Status",
		filters={"user": target},
		fields=["name", "status_date", "state", "summary_line", "message_count", "generated_by"],
		order_by="status_date desc",
		limit_page_length=days,
	)
	return {"success": True, "data": {"user": target, "days": rows}}


# ────────────────────────────────── ops levers ──────────────────────────────


@frappe.whitelist()
def regenerate(user: str, status_date: str | None = None) -> dict:
	"""Rebuild one person's day — after a late message, or once ONYX is back.

	Keeps the original starting sequence, so this extends the day rather than
	restating it from somewhere else.

	Returns: {success, data: {status}}.
	"""
	frappe.only_for(["System Manager", "Central Ops", "Depot Manager"])
	name = daily_status.generate_for_user(user, status_date)
	if not name:
		frappe.throw(_("Nothing to generate for {0}.").format(user))
	return {
		"success": True,
		"data": {"status": frappe.get_doc("VM Daily Status", name).as_payload()},
		"message": _("Rebuilt."),
	}


@frappe.whitelist()
def run_now(status_date: str | None = None, send_email: int = 0) -> dict:
	"""Generate everyone's day now, and optionally email the digest.

	The manual twin of the evening schedule — for a first run, or after fixing a
	misconfiguration. Generation is enqueued per person, so `send_email` waits for
	nothing and is meant for a second call once generation has settled.

	Returns: {success, data: {generated, digest}}.
	"""
	frappe.only_for(["System Manager", "Central Ops"])
	status_date = status_date or daily_status.status_date_now()
	generated = daily_status.generate_all(status_date)
	digest = (
		daily_status_digest.send_digests(status_date) if cint(send_email) else {"skipped": "not requested"}
	)
	return {"success": True, "data": {"generated": generated, "digest": digest}}
