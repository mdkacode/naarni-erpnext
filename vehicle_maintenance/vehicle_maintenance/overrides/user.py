import re

import frappe
from frappe import _

STAFF_EMAIL_DOMAIN = "staff.naarni.com"


def _normalize_phone(raw: str) -> str:
	digits = re.sub(r"\D", "", raw or "")
	if not digits:
		frappe.throw(_("Phone number must contain digits."))
	if len(digits) < 10:
		frappe.throw(_("Phone number must be at least 10 digits."))
	return digits


def validate_user(doc, method=None):
	if doc.name == "Administrator" or getattr(doc, "user_type", "") == "Website User":
		return

	if not (doc.mobile_no or "").strip():
		frappe.throw(_("Phone number is mandatory for staff users."))

	normalized = _normalize_phone(doc.mobile_no)
	doc.mobile_no = normalized

	if not (doc.email or "").strip():
		doc.email = f"{normalized}@{STAFF_EMAIL_DOMAIN}"

	existing = frappe.db.get_all(
		"User",
		filters={"mobile_no": normalized, "name": ["!=", doc.name or ""]},
		fields=["name"],
		limit=1,
	)
	if existing:
		frappe.throw(
			_("Phone number {0} is already registered to user {1}.").format(normalized, existing[0].name)
		)
