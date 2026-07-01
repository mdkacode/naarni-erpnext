"""Job Card closure report — a NaArNi-watermarked PDF proof of service.

Generates the 'Job Card Closure Report' print format (vehicle, customer, repair/
maintenance/software jobs, health scores, parts and the multi-angle photos) as a
PDF, attaches it to the Job Card (public file so it is shareable), and exposes
share helpers for **Email** (native Frappe) and **WhatsApp** (wa.me deep link
with the public PDF URL — upgradeable to a WhatsApp Business API later).

`generate_on_close` is called from the closure notification hook so a proof PDF
exists the moment a job card is verified & closed.
"""

import urllib.parse

import frappe
from frappe import _

PRINT_FORMAT = "Job Card Closure Report"


def _pdf_bytes(job_card_name: str) -> bytes:
	return frappe.get_print("Job Card", job_card_name, print_format=PRINT_FORMAT, as_pdf=True)


def _existing_file(job_card_name: str) -> str | None:
	"""Return the URL of the latest closure PDF attached to this job card, if any."""
	rows = frappe.get_all(
		"File",
		filters={
			"attached_to_doctype": "Job Card",
			"attached_to_name": job_card_name,
			"file_name": ["like", "%-closure-report.pdf"],
		},
		fields=["file_url"],
		order_by="creation desc",
		limit_page_length=1,
	)
	return rows[0].file_url if rows else None


def _build_and_attach(job_card_name: str) -> str:
	"""(Re)generate the closure PDF, attach it (public) and return its file_url."""
	pdf = _pdf_bytes(job_card_name)
	f = frappe.get_doc(
		{
			"doctype": "File",
			"file_name": f"{job_card_name}-closure-report.pdf",
			"content": pdf,
			"attached_to_doctype": "Job Card",
			"attached_to_name": job_card_name,
			"is_private": 0,
		}
	)
	f.insert(ignore_permissions=True)
	return f.file_url


@frappe.whitelist()
def generate_closure_report(job_card_name: str, refresh: int | bool = 0) -> dict:
	"""Return a shareable URL to the job card's closure PDF (building it if needed)."""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)
	url = None if int(refresh or 0) else _existing_file(job_card_name)
	if not url:
		url = _build_and_attach(job_card_name)
	return {
		"success": True,
		"data": {"file_url": url, "absolute_url": frappe.utils.get_url() + url},
		"message": _("Closure report ready."),
	}


def _customer_contact(doc) -> dict:
	if not doc.customer:
		return {}
	return (
		frappe.db.get_value(
			"Customer", doc.customer, ["customer_name", "mobile_no", "email_id"], as_dict=True
		)
		or {}
	)


@frappe.whitelist()
def share_closure_report(job_card_name: str) -> dict:
	"""Return everything the app needs to share the proof: the absolute PDF URL,
	a prefilled WhatsApp link, and the customer's email/phone."""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)
	doc = frappe.get_doc("Job Card", job_card_name)
	url = _existing_file(job_card_name) or _build_and_attach(job_card_name)
	abs_url = frappe.utils.get_url() + url
	contact = _customer_contact(doc)

	msg = _(
		"Dear {0}, your vehicle {1} has been serviced. "
		"Here is your NaArNi service proof for job card {2}: {3}"
	).format(contact.get("customer_name") or _("Customer"), doc.vehicle_number or "", doc.name, abs_url)

	phone = (contact.get("mobile_no") or "").strip()
	digits = "".join(ch for ch in phone if ch.isdigit())
	wa = (
		f"https://wa.me/{digits}?text={urllib.parse.quote(msg)}"
		if digits
		else f"https://wa.me/?text={urllib.parse.quote(msg)}"
	)

	return {
		"success": True,
		"data": {
			"pdf_url": abs_url,
			"whatsapp_link": wa,
			"email": contact.get("email_id"),
			"phone": phone,
			"message": msg,
		},
	}


@frappe.whitelist()
def email_closure_report(job_card_name: str, recipient: str | None = None) -> dict:
	"""Email the closure PDF to the customer (or an explicit recipient)."""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)
	doc = frappe.get_doc("Job Card", job_card_name)
	contact = _customer_contact(doc)
	to = (recipient or contact.get("email_id") or "").strip()
	if not to:
		frappe.throw(_("No customer email on file. Add one on the Customer, or share via WhatsApp."))

	pdf = _pdf_bytes(job_card_name)
	frappe.sendmail(
		recipients=[to],
		subject=_("Your NaArNi Service Report — {0}").format(doc.vehicle_number or doc.name),
		message=_(
			"<p>Dear {0},</p><p>Your vehicle <b>{1}</b> has been serviced. "
			"Please find your NaArNi service proof (job card {2}) attached.</p>"
			"<p>Thank you for choosing NaArNi Fleet Service.</p>"
		).format(contact.get("customer_name") or _("Customer"), doc.vehicle_number or "", doc.name),
		attachments=[{"fname": f"{doc.name}-closure-report.pdf", "fcontent": pdf}],
		reference_doctype="Job Card",
		reference_name=doc.name,
	)
	return {"success": True, "data": {"sent_to": to}, "message": _("Report emailed to {0}.").format(to)}


def _email_aftersales(doc) -> None:
	"""Email the detailed closure report to the Aftersales Engineers (PRD p.2:
	'Detailed Report goes to the After-sales Engineer'). Best-effort."""
	recipients = frappe.get_all(
		"Has Role",
		filters={"role": "Aftersales Eng", "parenttype": "User"},
		pluck="parent",
	)
	recipients = [
		r
		for r in set(recipients)
		if r not in ("Administrator", "Guest") and frappe.db.get_value("User", r, "enabled")
	]
	# Keep to real email addresses (the app's phone-based users may not have one).
	recipients = [r for r in recipients if "@" in r and not r.endswith("@test.localhost")]
	if not recipients:
		return
	pdf = _pdf_bytes(doc.name)
	frappe.sendmail(
		recipients=recipients,
		subject=_("Job Card closed — {0} ({1})").format(doc.name, doc.vehicle_number or ""),
		message=_(
			"<p>Job Card <b>{0}</b> for vehicle <b>{1}</b> has been closed.</p>"
			"<p>The detailed service report is attached for after-sales review.</p>"
		).format(doc.name, doc.vehicle_number or ""),
		attachments=[{"fname": f"{doc.name}-closure-report.pdf", "fcontent": pdf}],
		reference_doctype="Job Card",
		reference_name=doc.name,
	)


def generate_on_close(doc) -> None:
	"""Closure hook: build + attach the proof PDF; auto-email when the SE ticked
	'Send report to customer'; always send the detailed report to Aftersales.
	Best-effort — never blocks closure."""
	try:
		_build_and_attach(doc.name)
	except Exception:
		frappe.log_error(title="closure_report_generate", message=frappe.get_traceback())
		return
	if doc.get("send_report_to_customer"):
		try:
			email_closure_report(doc.name)
		except Exception:
			frappe.log_error(title="closure_report_email", message=frappe.get_traceback())
	try:
		_email_aftersales(doc)
	except Exception:
		frappe.log_error(title="closure_report_aftersales_email", message=frappe.get_traceback())
