"""CRM (Lead) whitelisted API.

Consumed by the Vue 3 SPA and any future mobile client. Every method:
- Checks permissions up-front (role allow-list + per-doc `has_permission`).
- Returns the project-standard `{success, data, message}` envelope.
- Wraps user-facing strings in `_()` for translation.
"""

from __future__ import annotations

import re
from datetime import datetime
from typing import Any

import frappe
from frappe import _
from frappe.utils import get_datetime, now_datetime

ROLES_FULL = ["Sales Executive", "Central Ops", "System Manager", "Administrator"]
ROLES_READ = [*ROLES_FULL, "Service Engineer"]

PHONE_DIGITS = re.compile(r"\D+")
MAX_LIST_PAGE_SIZE = 100
ALLOWED_ATTACHMENT_MIME = {"image/jpeg", "image/png", "image/webp", "application/pdf"}
MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024  # 5 MB


# --------------------------------------------------------------------- helpers


def _ok(data: Any, message: str = "") -> dict:
	return {"success": True, "data": data, "message": message}


def _require_any_role(roles: list[str]) -> None:
	frappe.only_for(roles)


def _normalize_phone(phone: str | None) -> str:
	if not phone:
		return ""
	digits = PHONE_DIGITS.sub("", phone)
	return digits[-10:] if len(digits) >= 10 else digits


def _coerce_payload(payload: Any) -> dict:
	if isinstance(payload, str):
		return frappe.parse_json(payload) or {}
	return dict(payload or {})


# --------------------------------------------------------------------- reads


@frappe.whitelist()
def get_lead_dropdowns() -> dict:
	"""Return all dropdown values needed by the Lead SPA in a single call.

	Returns: {sources, statuses, sales_users, depots, email_templates}.
	"""
	_require_any_role(ROLES_READ)

	sources = frappe.get_all(
		"Lead Source",
		filters={"is_active": 1},
		fields=["name", "source_name"],
		order_by="source_name asc",
		limit_page_length=500,
	)
	statuses = frappe.get_all(
		"Lead Status",
		fields=["name", "status_name", "stage", "display_order", "is_terminal"],
		order_by="display_order asc, status_name asc",
		limit_page_length=500,
	)
	sales_users = frappe.db.sql(
		"""
        SELECT u.name, u.full_name, u.email
        FROM `tabUser` u
        INNER JOIN `tabHas Role` r ON r.parent = u.name
        WHERE u.enabled = 1 AND r.role IN ('Sales Executive', 'Central Ops')
        GROUP BY u.name
        ORDER BY u.full_name ASC
        LIMIT 500
        """,
		as_dict=True,
	)
	depots = frappe.get_all(
		"Depot",
		fields=["name", "depot_name"],
		order_by="depot_name asc",
		limit_page_length=500,
	)
	templates = frappe.get_all(
		"Email Template",
		fields=["name", "subject"],
		order_by="name asc",
		limit_page_length=500,
	)

	return _ok(
		{
			"sources": sources,
			"statuses": statuses,
			"sales_users": sales_users,
			"depots": depots,
			"email_templates": templates,
		}
	)


@frappe.whitelist()
def get_lead_list(
	filters: Any = None,
	page: int = 1,
	page_size: int = 20,
	search: str = "",
) -> dict:
	"""Paginated Lead list for the table view.

	`filters`: dict with any of {status, lead_source, assigned_to, priority,
	depot, stage}.
	"""
	_require_any_role(ROLES_READ)

	filters = _coerce_payload(filters)
	page = max(1, int(page))
	page_size = min(MAX_LIST_PAGE_SIZE, max(1, int(page_size)))

	where: dict[str, Any] = {}
	for key in ("status", "lead_source", "assigned_to", "priority", "depot"):
		if filters.get(key):
			where[key] = filters[key]

	or_filters: list[list] = []
	if search:
		like = f"%{search.strip()}%"
		or_filters = [
			["lead_name", "like", like],
			["phone", "like", like],
			["company_name", "like", like],
		]

	total = frappe.db.count("Lead", filters=where or None)
	rows = frappe.get_list(
		"Lead",
		filters=where or None,
		or_filters=or_filters or None,
		fields=[
			"name",
			"lead_name",
			"phone",
			"email",
			"company_name",
			"lead_source",
			"status",
			"priority",
			"estimated_value",
			"expected_close_date",
			"assigned_to",
			"depot",
			"modified",
		],
		order_by="modified desc",
		limit_start=(page - 1) * page_size,
		limit_page_length=page_size,
	)

	return _ok(
		{
			"rows": rows,
			"total": total,
			"page": page,
			"page_size": page_size,
		}
	)


@frappe.whitelist()
def get_lead(name: str) -> dict:
	"""Full Lead detail incl. activities, attachments, and pending reminders."""
	_require_any_role(ROLES_READ)
	frappe.has_permission("Lead", doc=name, throw=True)

	doc = frappe.get_doc("Lead", name)
	reminders = frappe.get_all(
		"Lead Reminder",
		filters={"lead": name},
		fields=[
			"name",
			"reminder_datetime",
			"channel",
			"status",
			"subject",
			"sent_at",
			"failure_reason",
			"recipient_email",
		],
		order_by="reminder_datetime asc",
		limit_page_length=100,
	)
	return _ok(
		{
			"lead": doc.as_dict(),
			"reminders": reminders,
		}
	)


# --------------------------------------------------------------------- writes


@frappe.whitelist()
def create_lead(payload: Any) -> dict:
	"""Create a Lead from the SPA wizard payload."""
	_require_any_role(ROLES_FULL)

	payload = _coerce_payload(payload)
	if not payload.get("lead_name"):
		frappe.throw(_("Lead Name is required."))
	phone = _normalize_phone(payload.get("phone"))
	if len(phone) != 10:
		frappe.throw(_("Phone must contain at least 10 digits."))

	allowed_fields = {
		"lead_name",
		"phone",
		"email",
		"company_name",
		"lead_source",
		"industry",
		"fleet_size_bucket",
		"interested_in",
		"status",
		"priority",
		"estimated_value",
		"expected_close_date",
		"assigned_to",
		"depot",
		"state",
		"city",
		"address_line",
		"notes",
	}
	data = {k: v for k, v in payload.items() if k in allowed_fields and v not in (None, "")}
	data["phone"] = phone
	data["doctype"] = "Lead"

	doc = frappe.get_doc(data)
	doc.insert()

	doc.append_activity(
		activity_type="Other",
		summary=_("Lead created via SPA"),
	)
	doc.save()

	return _ok({"name": doc.name}, _("Lead created."))


@frappe.whitelist()
def update_lead_status(name: str, new_status: str, note: str = "") -> dict:
	"""Advance the Lead status; appends a Lead Activity row automatically.

	Transitions to a status with stage == 'Won' auto-convert to Customer.
	"""
	_require_any_role(ROLES_FULL)
	frappe.has_permission("Lead", doc=name, ptype="write", throw=True)

	if not frappe.db.exists("Lead Status", new_status):
		frappe.throw(_("Unknown status: {0}").format(new_status))

	doc = frappe.get_doc("Lead", name)
	old_status = doc.status
	doc.status = new_status
	doc.append_activity(
		activity_type="Other",
		summary=note or _("Status changed: {0} → {1}").format(old_status or "-", new_status),
		outcome="Converted" if frappe.db.get_value("Lead Status", new_status, "stage") == "Won" else None,
	)
	doc.save()

	stage = frappe.db.get_value("Lead Status", new_status, "stage")
	if stage == "Won" and not doc.converted_to_customer:
		return convert_lead_to_customer(name)

	return _ok({"name": doc.name, "status": doc.status})


@frappe.whitelist()
def add_activity(lead: str, payload: Any) -> dict:
	"""Append an activity row (call/visit/note) to a Lead."""
	_require_any_role(ROLES_FULL)
	frappe.has_permission("Lead", doc=lead, ptype="write", throw=True)

	payload = _coerce_payload(payload)
	if not payload.get("activity_type"):
		frappe.throw(_("Activity type is required."))

	doc = frappe.get_doc("Lead", lead)
	doc.append_activity(
		activity_type=payload["activity_type"],
		summary=payload.get("summary"),
		outcome=payload.get("outcome"),
		next_action=payload.get("next_action"),
	)
	doc.save()
	return _ok({"name": doc.name}, _("Activity logged."))


@frappe.whitelist()
def upload_lead_attachment(lead: str, file_url: str, caption: str = "") -> dict:
	"""Attach an already-uploaded File to a Lead.

	File upload itself goes through Frappe's /api/method/upload_file; this
	endpoint just links the File into the Lead's child table and validates
	the MIME type / size.
	"""
	_require_any_role(ROLES_FULL)
	frappe.has_permission("Lead", doc=lead, ptype="write", throw=True)

	if not file_url:
		frappe.throw(_("file_url is required."))

	file_row = frappe.db.get_value(
		"File",
		{"file_url": file_url},
		["name", "file_size", "file_type", "is_private"],
		as_dict=True,
	)
	if not file_row:
		frappe.throw(_("Uploaded File not found for URL: {0}").format(file_url))

	if file_row.file_size and file_row.file_size > MAX_ATTACHMENT_BYTES:
		frappe.throw(_("Attachment exceeds 5 MB limit."))

	mime = (file_row.file_type or "").lower()
	if mime and mime not in ALLOWED_ATTACHMENT_MIME:
		# Frappe sometimes stores type as extension (e.g. 'jpg'); be lenient.
		ext_ok = mime in {"jpg", "jpeg", "png", "webp", "pdf"}
		if not ext_ok:
			frappe.throw(_("Unsupported file type: {0}").format(mime))

	doc = frappe.get_doc("Lead", lead)
	doc.append(
		"attachments",
		{
			"file_url": file_url,
			"caption": caption,
			"uploaded_by": frappe.session.user,
			"uploaded_on": now_datetime(),
		},
	)
	doc.save()
	return _ok({"name": doc.name}, _("Attachment linked."))


@frappe.whitelist()
def convert_lead_to_customer(name: str) -> dict:
	"""Create a Customer record from the Lead and mark it converted.

	Idempotent: returns the existing customer if already converted.
	"""
	_require_any_role(ROLES_FULL)
	frappe.has_permission("Lead", doc=name, ptype="write", throw=True)

	doc = frappe.get_doc("Lead", name)
	if doc.converted_to_customer:
		return _ok(
			{"lead": doc.name, "customer": doc.converted_to_customer},
			_("Lead already converted."),
		)

	customer_name = doc.company_name or doc.lead_name
	if frappe.db.exists("Customer", {"customer_name": customer_name}):
		customer = frappe.get_doc("Customer", {"customer_name": customer_name})
	else:
		# Derive a short customer_code from the name: uppercase alphanumeric, 2-10 chars
		base = re.sub(r"[^A-Z0-9]", "", customer_name.upper())[:10] or "LEAD"
		code = base
		counter = 1
		while frappe.db.exists("Customer", {"customer_code": code}):
			suffix = str(counter)
			code = base[: 10 - len(suffix)] + suffix
			counter += 1

		customer = frappe.get_doc(
			{
				"doctype": "Customer",
				"customer_name": customer_name,
				"customer_code": code,
				"customer_type": "Company" if doc.company_name else "Individual",
				"mobile_no": doc.phone,
				"email_id": doc.email,
				"address": doc.address_line,
				"city": doc.city,
				"state": doc.state,
			}
		)
		customer.insert()

	doc.converted_to_customer = customer.name
	doc.converted_on = now_datetime()
	doc.append_activity(
		activity_type="Other",
		summary=_("Converted to Customer {0}").format(customer.name),
		outcome="Converted",
	)
	doc.save()

	return _ok(
		{"lead": doc.name, "customer": customer.name},
		_("Lead converted to customer."),
	)


# --------------------------------------------------------------------- reminders


@frappe.whitelist()
def schedule_reminder(payload: Any) -> dict:
	"""Create a Lead Reminder row scheduled for a future datetime."""
	_require_any_role(ROLES_FULL)

	payload = _coerce_payload(payload)
	lead = payload.get("lead")
	if not lead:
		frappe.throw(_("Lead is required."))
	frappe.has_permission("Lead", doc=lead, ptype="write", throw=True)

	when = get_datetime(payload.get("reminder_datetime"))
	if not when:
		frappe.throw(_("reminder_datetime is required."))
	if when <= now_datetime():
		frappe.throw(_("Reminder time must be in the future."))

	if not (payload.get("message_template") or payload.get("message_body")):
		frappe.throw(_("Provide either an Email Template or a Message body."))

	reminder = frappe.get_doc(
		{
			"doctype": "Lead Reminder",
			"lead": lead,
			"reminder_datetime": when,
			"channel": payload.get("channel") or "Email",
			"status": "Scheduled",
			"subject": payload.get("subject") or _("Follow-up reminder"),
			"message_template": payload.get("message_template"),
			"message_body": payload.get("message_body"),
			"recipient_email": payload.get("recipient_email"),
		}
	)
	reminder.insert()

	return _ok({"name": reminder.name}, _("Reminder scheduled."))


@frappe.whitelist()
def cancel_reminder(name: str) -> dict:
	"""Cancel a pending (Scheduled) Lead Reminder."""
	_require_any_role(ROLES_FULL)
	frappe.has_permission("Lead Reminder", doc=name, ptype="write", throw=True)

	doc = frappe.get_doc("Lead Reminder", name)
	if doc.status != "Scheduled":
		frappe.throw(_("Only Scheduled reminders can be cancelled."))
	doc.status = "Cancelled"
	doc.save()
	return _ok({"name": doc.name}, _("Reminder cancelled."))


def dispatch_due_reminders() -> None:
	"""Scheduler job: send all Lead Reminders whose time has come.

	Registered via hooks.py `scheduler_events`. Intentionally *not*
	whitelisted — only the scheduler should call this.
	"""
	now = now_datetime()
	due = frappe.get_all(
		"Lead Reminder",
		filters={"status": "Scheduled", "reminder_datetime": ["<=", now]},
		fields=["name"],
		limit_page_length=100,
	)
	for row in due:
		try:
			_send_reminder(row.name)
		except Exception:
			frappe.log_error(
				title=f"Lead Reminder dispatch failed: {row.name}",
				message=frappe.get_traceback(),
			)


def _send_reminder(name: str) -> None:
	reminder = frappe.get_doc("Lead Reminder", name)
	if reminder.status != "Scheduled":
		return

	lead = frappe.get_doc("Lead", reminder.lead)
	recipient = reminder.recipient_email or lead.email
	if not recipient:
		reminder.status = "Failed"
		reminder.failure_reason = _("No recipient email on reminder or lead.")
		reminder.save(ignore_permissions=True)
		return

	subject = reminder.subject
	body = reminder.message_body or ""

	if reminder.message_template:
		template = frappe.get_doc("Email Template", reminder.message_template)
		context = {
			"lead_name": lead.lead_name,
			"company_name": lead.company_name or "",
			"assigned_to": lead.assigned_to or "",
			"estimated_value": lead.estimated_value or 0,
			"expected_close_date": lead.expected_close_date or "",
			"phone": lead.phone,
		}
		subject = frappe.render_template(template.subject or subject, context)
		body = frappe.render_template(template.response or body, context)

	try:
		frappe.sendmail(
			recipients=[recipient],
			subject=subject,
			message=body,
			reference_doctype="Lead",
			reference_name=lead.name,
		)
	except Exception as exc:
		reminder.status = "Failed"
		reminder.failure_reason = str(exc)[:500]
		reminder.save(ignore_permissions=True)
		raise

	reminder.status = "Sent"
	reminder.sent_at = datetime.now()
	reminder.save(ignore_permissions=True)
