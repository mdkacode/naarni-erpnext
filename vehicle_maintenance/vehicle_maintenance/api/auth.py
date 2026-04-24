import frappe
import frappe.sessions
from frappe import _
from frappe.auth import LoginManager

from vehicle_maintenance.overrides.user import normalize_phone


@frappe.whitelist()
def get_csrf_token() -> str:
	"""Return the current session's CSRF token for the SPA to attach to POST requests."""
	return frappe.sessions.get_csrf_token()


@frappe.whitelist(allow_guest=True)
def login_with_phone(phone: str, password: str) -> dict:
	"""Authenticate a staff user by phone number + password."""
	if not password:
		frappe.throw(_("Password is required."), frappe.AuthenticationError)

	normalized = normalize_phone(phone)

	candidates = frappe.db.get_all(
		"User",
		filters={"mobile_no": normalized, "enabled": 1},
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
	# post_login sets cookies via frappe.local.response; guard for non-HTTP callers
	# (tests, console) where that context doesn't exist.
	if getattr(frappe.local, "request", None) is not None:
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
