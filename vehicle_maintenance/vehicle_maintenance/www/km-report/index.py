"""Public, guest-accessible Monthly KM Report — served at /km-report/<token>.

Security posture (see CLAUDE §13): the lookup lives in
`fleet_service.km_report.public_report_context`, which reads ONLY the frozen
`KM Report Snapshot` (never the raw Vehicle KM Daily rows, Users, or Customers),
enforces the 7-day token expiry server-side, and returns None on any unknown/blank
token so this page renders a generic 404 (no enumeration signal). The page is marked
noindex / no-sitemap so tokens are never crawled.
"""

import frappe

from vehicle_maintenance.fleet_service.km_report import public_report_context

no_cache = 1


def get_context(context):
	context.no_cache = 1
	context.no_sitemap = 1
	frappe.local.flags.no_sitemap = 1

	ctx = public_report_context(_token())
	if ctx is None:
		raise frappe.PageDoesNotExistError

	context.update(ctx)
	return context


def _token() -> str:
	token = frappe.form_dict.get("token")
	if not token:
		# Fallback: last path segment of /km-report/<token>.
		parts = [p for p in (frappe.request.path or "").split("/") if p]
		if len(parts) >= 2 and parts[0] == "km-report":
			token = parts[1]
	return (token or "").strip()
