import frappe
import frappe.sessions


@frappe.whitelist()
def get_csrf_token() -> str:
	"""Return the current session's CSRF token for the SPA to attach to POST requests."""
	return frappe.sessions.get_csrf_token()
