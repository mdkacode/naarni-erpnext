import re

import frappe
import frappe.sessions
from frappe import _
from frappe.auth import LoginManager


@frappe.whitelist()
def get_csrf_token() -> str:
	"""Return the current session's CSRF token for the SPA to attach to POST requests."""
	return frappe.sessions.get_csrf_token()


@frappe.whitelist(allow_guest=True)
def login_with_phone(phone: str, password: str) -> dict:
	"""Authenticate a staff user by phone number + password."""
	digits = re.sub(r"\D", "", phone or "")
	if not digits:
		frappe.throw(_("Phone number is required."), frappe.AuthenticationError)
	if not password:
		frappe.throw(_("Password is required."), frappe.AuthenticationError)

	candidates = frappe.db.get_all(
		"User",
		filters={"mobile_no": digits, "enabled": 1},
		fields=["name", "full_name", "user_type"],
		limit=2,
	)
	if not candidates:
		frappe.throw(_("No account found for that phone number."), frappe.AuthenticationError)
	if len(candidates) > 1:
		frappe.throw(
			_("Multiple accounts share this phone number. Contact an administrator."),
			frappe.AuthenticationError,
		)

	user = candidates[0]
	lm = LoginManager()
	lm.authenticate(user=user.name, pwd=password)
	lm.post_login()

	roles = frappe.get_roles(user.name)
	return {
		"success": True,
		"data": {
			"user": user.name,
			"full_name": user.full_name,
			"user_type": user.user_type,
			"roles": roles,
		},
		"message": _("Logged in."),
	}
