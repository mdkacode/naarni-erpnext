"""Monthly KM Report: freeze a snapshot, send a white-labelled email with a public
7-day link.

`generate_snapshot` builds the PII-safe view model (see km_report.build_report_payload)
and freezes it onto a `KM Report Snapshot` with a fresh random token. `send_report_email`
co-brands the shared email template with the customer's logo/name and mails the
stakeholders via Brevo. The scheduled fan-out lives in `fleet_service.tasks`.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.utils import get_url, now

from vehicle_maintenance.fleet_service import brevo_client, km_report
from vehicle_maintenance.fleet_service.email_templates import render_branded_email, to_plain_text


def _public_logo_url(logo: str | None) -> str | None:
	"""Absolute URL for the customer logo (Attach Image stores a site-relative path)."""
	if not logo:
		return None
	return logo if logo.startswith("http") else get_url() + logo


def generate_snapshot(customer: str, year_month: str) -> str:
	"""Build (or refresh) the KM Report Snapshot for one customer/month.

	Freezes the PII-safe payload and (re)issues a fresh 7-day public token. Returns
	the snapshot docname. Idempotent per (customer, month) — regenerating replaces the
	payload and resets the link.
	"""
	from vehicle_maintenance.fleet_service import sla

	payload = km_report.build_report_payload(customer, year_month, generated_at=now())
	payload["sla"] = sla.month_sla_section(customer, year_month)
	cfg = km_report.report_config(customer) or {}

	existing = frappe.db.get_value(
		"KM Report Snapshot", {"customer": customer, "report_month": year_month}, "name"
	)
	if existing:
		snap = frappe.get_doc("KM Report Snapshot", existing)
		snap.issue_token()  # a regenerated report gets a fresh 7-day link
	else:
		snap = frappe.new_doc("KM Report Snapshot")
		snap.customer = customer
		snap.report_month = year_month

	snap.payload_json = frappe.as_json(payload)
	snap.logo_url = _public_logo_url(cfg.get("logo"))
	snap.display_name = payload["customer_display"]
	snap.status = "Draft"
	snap.flags.ignore_permissions = True
	snap.save(ignore_permissions=True)
	return snap.name


def send_report_email(snapshot_name: str) -> dict:
	"""Email the white-labelled monthly report to the customer's stakeholders."""
	snap = frappe.get_doc("KM Report Snapshot", snapshot_name)
	cfg_doc = (
		frappe.get_doc("Fleet Report Config", snap.customer)
		if frappe.db.exists("Fleet Report Config", snap.customer)
		else None
	)
	recipients = cfg_doc.recipient_emails() if cfg_doc else []
	if not recipients:
		return {"sent": False, "reason": "no recipients"}

	payload = frappe.parse_json(snap.payload_json)
	totals = payload.get("totals", {})
	public_url = get_url(f"/km-report/{snap.public_token}")
	heading = _("Monthly KM Report — {0}").format(payload.get("month_label"))
	body = _(
		"<p>Here is the distance report for your fleet for <b>{0}</b>. "
		"Open the link below to view the full month &gt; week &gt; day breakdown per vehicle.</p>"
		"<p style='color:#6B7280;font-size:13px;'>This link stays active for 7 days.</p>"
	).format(payload.get("month_label"))
	meta_rows = [
		(_("Month"), payload.get("month_label")),
		(_("Vehicles"), totals.get("vehicles")),
		(_("Total Billable KM"), f"{totals.get('billable_km', 0):,.1f}"),
	]

	html = render_branded_email(
		heading=heading,
		body_html=body,
		preheader=_("Your {0} fleet KM report is ready.").format(payload.get("month_label")),
		cta_label=_("View KM Report"),
		cta_url=public_url,
		priority="Low",
		meta_rows=meta_rows,
		customer_logo_url=snap.logo_url,
		customer_name=snap.display_name,
	)
	text = to_plain_text(
		heading,
		_("Your {0} fleet KM report is ready.").format(payload.get("month_label")),
		cta_label=_("View KM Report"),
		cta_url=public_url,
		customer_name=snap.display_name,
	)

	result = brevo_client.send_mail(
		to=recipients,
		subject=heading,
		html_body=html,
		text_body=text,
		tags=["km-report", payload.get("report_month") or ""],
	)
	snap.db_set("email_message_id", result.get("message_id"))
	snap.db_set("status", "Sent")
	return result


def run_for_customer(customer: str, year_month: str) -> dict:
	"""Generate the snapshot and email it — best-effort (email failure never loses the snapshot)."""
	name = generate_snapshot(customer, year_month)
	sent = None
	try:
		sent = send_report_email(name)
	except Exception:
		frappe.log_error(title="KM report email failed", message=frappe.get_traceback())
	frappe.db.commit()
	return {"snapshot": name, "email": sent}
