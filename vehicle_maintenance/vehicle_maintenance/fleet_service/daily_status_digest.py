# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""The evening digest: one email per depot, not one per person.

Forty separate "here is Ramesh's day" emails get filtered inside a week. One
email per depot, blockers hoisted to the top and the people who said nothing
listed at the bottom, is a thing a manager actually reads — so the unit of
delivery here is the depot, and a recipient gets one message per depot they are
responsible for.

Everything is built from **VM Daily Status** rows, which are generated first (see
`daily_status.generate_all`). This module never talks to ONYX or to chat: if the
AI was unavailable, the rows say so and the digest renders what people actually
wrote instead. A degraded digest still goes out on time.
"""

from __future__ import annotations

from html import escape

import frappe
from frappe import _
from frappe.utils import format_date, now_datetime

from vehicle_maintenance.fleet_service import daily_status
from vehicle_maintenance.fleet_service.email_templates import (
	BORDER,
	BRAND_PRIMARY,
	TEXT_MUTED,
	TEXT_PRIMARY,
	render_branded_email,
)

# Recipients holding any of these see every depot rather than only their own.
GLOBAL_RECIPIENT_ROLES = ("System Manager", "Central Ops", "N. Maintenance Head")

# Placeholder inboxes that exist because this platform authenticates by phone.
# Mailing them just fills the Email Queue with bounces.
_UNROUTABLE_SUFFIXES = (".local", ".localhost", "@example.com", "@test.com")

BUCKET_ORDER = ("Blocker", "Needs Attention", "In Progress", "Completed")
BUCKET_COLOURS = {
	"Blocker": ("#DC2626", "#FEE2E2"),
	"Needs Attention": ("#B45309", "#FEF3C7"),
	"In Progress": ("#1E40AF", "#DBEAFE"),
	"Completed": ("#047857", "#D1FAE5"),
}


# ──────────────────────────────── recipients ────────────────────────────────


def _is_routable(email: str | None) -> bool:
	if not email or "@" not in email:
		return False
	lowered = email.lower()
	return not any(lowered.endswith(suffix) or suffix in lowered for suffix in _UNROUTABLE_SUFFIXES)


def _email_of(user: str) -> str | None:
	email = frappe.db.get_value("User", user, "email") or user
	return email if _is_routable(email) else None


def recipients_by_depot(depots: list[str]) -> dict[str, list[str]]:
	"""Which email addresses get which depot's digest.

	A recipient sees the depots they are attached to via Depot.service_engineers.
	Central Ops, System Manager, N. Maintenance Head and anyone named explicitly
	under Also Send To get every depot — those are the roles whose job is the
	whole fleet.
	"""
	cfg = daily_status.settings()
	roles = [r.role for r in (cfg.recipient_roles or []) if r.role]
	named = [r.user for r in (cfg.extra_recipients or []) if r.user]

	candidates: set[str] = set(named)
	if roles:
		candidates |= {
			r["parent"]
			for r in frappe.get_all(
				"Has Role",
				filters={"role": ["in", roles], "parenttype": "User"},
				fields=["parent"],
				limit_page_length=0,
			)
		}
	candidates -= set(daily_status.NON_HUMAN_USERS)
	if not candidates:
		return {}

	enabled = {
		u["name"]
		for u in frappe.get_all(
			"User",
			filters={"name": ["in", list(candidates)], "enabled": 1},
			fields=["name"],
			limit_page_length=0,
		)
	}

	# One query for every recipient's depot attachments rather than one each.
	attachments: dict[str, set[str]] = {}
	if enabled:
		for row in frappe.get_all(
			"Depot Engineer",
			filters={"user": ["in", list(enabled)], "parenttype": "Depot"},
			fields=["user", "parent"],
			limit_page_length=0,
		):
			attachments.setdefault(row["user"], set()).add(row["parent"])

	out: dict[str, list[str]] = {depot: [] for depot in depots}
	for user in sorted(enabled):
		email = _email_of(user)
		if not email:
			continue
		sees_all = user in named or bool(set(frappe.get_roles(user)) & set(GLOBAL_RECIPIENT_ROLES))
		for depot in depots:
			if sees_all or depot in attachments.get(user, set()):
				out[depot].append(email)
	return out


# ────────────────────────────────── rendering ───────────────────────────────


def _pill(bucket: str) -> str:
	fg, bg = BUCKET_COLOURS.get(bucket, BUCKET_COLOURS["In Progress"])
	return (
		f'<span style="display:inline-block;font-size:10px;font-weight:700;'
		f"letter-spacing:0.4px;text-transform:uppercase;padding:2px 8px;border-radius:999px;"
		f'color:{fg};background:{bg};white-space:nowrap;">{escape(bucket)}</span>'
	)


def _ref(item) -> str:
	parts = [p for p in (item.get("ticket"), item.get("vehicle")) if p]
	if not parts:
		return ""
	return f'<span style="color:{TEXT_MUTED};font-size:12px;"> · {escape(" · ".join(parts))}</span>'


def _item_row(item: dict) -> str:
	"""One `<tr>` of a person's item table.

	A named builder rather than a multi-line f-string inside the `join` that calls
	it: implicitly concatenated strings in a comprehension are indistinguishable
	from a list that lost a comma, which is exactly what the correctness linter
	flags — and it is right to.
	"""
	pill_cell = f'<td style="padding:3px 8px 3px 0;vertical-align:top;white-space:nowrap;">{_pill(item["bucket"])}</td>'
	text_cell = (
		f'<td style="padding:3px 0;font-size:13px;color:{TEXT_PRIMARY};">'
		+ f"{escape(item['text'])}{_ref(item)}</td>"
	)
	return f"<tr>{pill_cell}{text_cell}</tr>"


def _person_card(row: dict) -> str:
	"""One person's day."""
	name = escape(row["user_name"] or row["user"])
	head = f'<div style="font-size:15px;font-weight:700;color:{TEXT_PRIMARY};">{name}</div>'
	meta_bits = [f'{row["message_count"]} update{"" if row["message_count"] == 1 else "s"}']
	if row["voice_count"]:
		meta_bits.append(f'{row["voice_count"]} voice')
	if row["photo_count"]:
		meta_bits.append(f'{row["photo_count"]} photo')
	if row["generated_by"] == "Fallback":
		meta_bits.append("not organised by AI")
	meta = (
		f'<div style="font-size:12px;color:{TEXT_MUTED};margin-top:2px;">'
		f'{escape(" · ".join(meta_bits))}</div>'
	)

	summary = ""
	if row.get("summary_line"):
		summary = (
			f'<div style="font-size:14px;color:{TEXT_PRIMARY};margin-top:8px;">'
			f'{escape(row["summary_line"])}</div>'
		)

	items_html = ""
	ordered = sorted(
		row.get("items") or [],
		key=lambda i: BUCKET_ORDER.index(i["bucket"]) if i["bucket"] in BUCKET_ORDER else 99,
	)
	if ordered:
		lis = "".join(_item_row(i) for i in ordered)
		items_html = f'<table style="width:100%;border-collapse:collapse;margin-top:8px;">{lis}</table>'

	# When the AI was not available there are no organised items — show what they
	# actually wrote rather than an empty card.
	raw_html = ""
	if not ordered and row.get("raw_transcript"):
		lines = escape(row["raw_transcript"]).replace("\n", "<br/>")
		raw_html = (
			f'<div style="font-size:13px;color:{TEXT_PRIMARY};margin-top:8px;'
			f'padding:8px 10px;background:#F7F9FC;border-radius:6px;line-height:1.5;">{lines}</div>'
		)

	return (
		f'<div style="padding:14px 0;border-bottom:1px solid {BORDER};">'
		f"{head}{meta}{summary}{items_html}{raw_html}</div>"
	)


def _blocker_line(row: dict, item: dict) -> str:
	"""One `<li>` of the blocked banner. Named for the same reason as `_item_row`."""
	who = escape(row["user_name"] or row["user"])
	return (
		f'<li style="margin-bottom:4px;font-size:13px;color:{TEXT_PRIMARY};">'
		+ f"<b>{who}</b> — {escape(item['text'])}{_ref(item)}</li>"
	)


def _blocker_banner(rows: list[dict]) -> str:
	blockers = [
		(row, item) for row in rows for item in (row.get("items") or []) if item["bucket"] == "Blocker"
	]
	if not blockers:
		return ""
	lis = "".join(_blocker_line(row, item) for row, item in blockers[:15])
	return (
		f'<div style="background:#FEF2F2;border-left:4px solid #DC2626;padding:12px 14px;'
		f'border-radius:6px;margin-bottom:18px;">'
		f'<div style="font-size:12px;font-weight:700;letter-spacing:0.5px;text-transform:uppercase;'
		f'color:#DC2626;margin-bottom:6px;">Blocked ({len(blockers)})</div>'
		f'<ul style="margin:0;padding-left:18px;">{lis}</ul></div>'
	)


def render_digest(rows: list[dict], silent: list[dict]) -> str:
	"""The whole email body for one depot."""
	reported = [r for r in rows if r["state"] != "Not Reported"]
	body = [_blocker_banner(reported)]

	if reported:
		body.append(
			f'<div style="font-size:12px;font-weight:700;letter-spacing:0.5px;'
			f'text-transform:uppercase;color:{BRAND_PRIMARY};margin-bottom:4px;">Reported</div>'
		)
		body.extend(_person_card(row) for row in reported)
	else:
		body.append(
			f'<div style="font-size:14px;color:{TEXT_MUTED};padding:12px 0;">'
			f"Nobody sent an update today.</div>"
		)

	if silent:
		names = ", ".join(escape(r["user_name"] or r["user"]) for r in silent)
		body.append(
			f'<div style="margin-top:18px;padding:12px 14px;background:#F7F9FC;'
			f'border-radius:6px;">'
			f'<div style="font-size:12px;font-weight:700;letter-spacing:0.5px;'
			f'text-transform:uppercase;color:{TEXT_MUTED};margin-bottom:4px;">'
			f"No update ({len(silent)})</div>"
			f'<div style="font-size:13px;color:{TEXT_PRIMARY};">{names}</div></div>'
		)

	return "".join(body)


# ─────────────────────────────────── sending ────────────────────────────────


def _rows_for(status_date: str) -> list[dict]:
	docs = frappe.get_all(
		"VM Daily Status",
		filters={"status_date": status_date},
		fields=[
			"name",
			"user",
			"employee_name",
			"depot",
			"state",
			"summary_line",
			"raw_transcript",
			"message_count",
			"voice_count",
			"photo_count",
			"generated_by",
		],
		order_by="employee_name asc",
		limit_page_length=0,
	)
	if not docs:
		return []

	# Items for every row in one query — never one query per person.
	items: dict[str, list[dict]] = {}
	for row in frappe.get_all(
		"VM Daily Status Item",
		filters={"parent": ["in", [d["name"] for d in docs]], "parenttype": "VM Daily Status"},
		fields=["parent", "bucket", "text", "ticket", "vehicle"],
		order_by="idx asc",
		limit_page_length=0,
	):
		items.setdefault(row["parent"], []).append(
			{
				"bucket": row["bucket"],
				"text": row["text"],
				"ticket": row["ticket"],
				"vehicle": row["vehicle"],
			}
		)

	for doc in docs:
		doc["user_name"] = doc.pop("employee_name", None) or doc["user"]
		doc["items"] = items.get(doc["name"], [])
	return docs


def send_digests(status_date: str | None = None) -> dict:
	"""Scheduled entry point: email every depot's rollup for `status_date`.

	Idempotent in effect: rows already marked Sent are still shown (the digest is
	a snapshot of the day, not a delta), but a depot with no recipients and no
	rows is skipped rather than sending an empty email.
	"""
	if not daily_status.is_enabled():
		return {"skipped": "daily status disabled"}
	cfg = daily_status.settings()
	if not cfg.digest_enabled:
		return {"skipped": "digest disabled"}

	status_date = status_date or daily_status.status_date_now()
	rows = _rows_for(status_date)
	if not rows:
		return {"date": status_date, "sent": 0, "note": "no status rows"}

	by_depot: dict[str, list[dict]] = {}
	for row in rows:
		by_depot.setdefault(row["depot"] or "", []).append(row)

	real_depots = [d for d in by_depot if d]
	routing = recipients_by_depot(real_depots) if real_depots else {}
	# Rows with no depot are everyone-who-sees-everything's problem, so they ride
	# along with the fleet-wide recipients rather than vanishing.
	fleet_wide = sorted({e for emails in routing.values() for e in emails}) if routing else []

	sent = 0
	for depot, depot_rows in by_depot.items():
		recipients = routing.get(depot, []) if depot else fleet_wide
		if not recipients:
			frappe.logger("naarni").info(f"daily status digest: no recipients for depot {depot or '(none)'}")
			continue
		if _send_one(depot or None, status_date, depot_rows, recipients, cfg):
			sent += 1

	frappe.logger("naarni").info(f"daily status digest: sent {sent} depot emails for {status_date}")
	return {"date": status_date, "sent": sent}


def _send_one(depot: str | None, status_date: str, rows: list[dict], recipients: list[str], cfg) -> bool:
	reported = [r for r in rows if r["state"] != "Not Reported"]
	silent = [r for r in rows if r["state"] == "Not Reported"] if cfg.include_silent else []
	blockers = sum(1 for r in reported for i in (r.get("items") or []) if i["bucket"] == "Blocker")

	pretty_date = format_date(status_date, "d MMM yyyy")
	scope = depot or _("Unassigned")
	subject = _("Daily Status — {0} — {1}").format(scope, pretty_date)
	if blockers:
		subject = _("Daily Status — {0} — {1} ({2} blocked)").format(scope, pretty_date, blockers)

	html = render_branded_email(
		heading=_("Daily Status — {0}").format(scope),
		body_html=render_digest(rows, silent),
		preheader=_("{0} of {1} reported · {2} blocked").format(len(reported), len(rows), blockers),
		priority="High" if blockers else "Medium",
		meta_rows=[
			(_("Date"), pretty_date),
			(_("Reported"), f"{len(reported)} / {len(rows)}"),
			(_("Blocked"), str(blockers)),
		],
	)

	try:
		frappe.sendmail(recipients=recipients, subject=subject, message=html)
	except Exception:
		frappe.log_error(
			title=f"Daily status digest failed (depot={depot or 'none'}, date={status_date})",
			message=frappe.get_traceback(),
		)
		return False

	stamp = now_datetime()
	for row in reported:
		frappe.db.set_value(
			"VM Daily Status",
			row["name"],
			{"email_sent_at": stamp, "state": "Sent"},
			update_modified=False,
		)
	_notify_in_app(recipients, subject, len(reported), len(rows), blockers)
	return True


def _notify_in_app(recipients: list[str], subject: str, reported: int, total: int, blockers: int) -> None:
	"""Mirror the digest to the in-app bell. Best-effort — email is the delivery."""
	body = _("{0} of {1} reported. {2} blocked.").format(reported, total, blockers)
	users = frappe.get_all(
		"User",
		filters={"email": ["in", recipients]},
		fields=["name"],
		limit_page_length=0,
	)
	for user in users:
		try:
			frappe.publish_realtime(
				event="vm_notification",
				message={"subject": subject, "body": body, "priority": "Medium"},
				user=user["name"],
			)
			log = frappe.new_doc("Notification Log")
			log.update(
				{
					"subject": subject,
					"email_content": body,
					"for_user": user["name"],
					"type": "Alert",
					"document_type": "VM Daily Status",
					"from_user": "Administrator",
				}
			)
			log.insert(ignore_permissions=True)
		except Exception:
			frappe.log_error(
				title=f"Daily status in-app notify failed (user={user['name']})",
				message=frappe.get_traceback(),
			)


# ──────────────────────────────── indexing pass ─────────────────────────────


def index_day(status_date: str | None = None) -> dict:
	"""Push the day's finished rows into ONYX search, when that is switched on."""
	if not daily_status.is_enabled():
		return {"skipped": "daily status disabled"}
	try:
		if not daily_status.settings().index_in_onyx:
			return {"skipped": "indexing disabled"}
	except Exception:
		return {"skipped": "settings unavailable"}

	status_date = status_date or daily_status.status_date_now()
	names = frappe.get_all(
		"VM Daily Status",
		filters={"status_date": status_date, "state": ["!=", "Not Reported"]},
		pluck="name",
		limit_page_length=0,
	)
	for name in names:
		frappe.enqueue(
			"vehicle_maintenance.fleet_service.daily_status.index_in_onyx",
			queue="long",
			timeout=120,
			name=name,
		)
	return {"date": status_date, "queued": len(names)}
