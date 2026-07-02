"""Whitelisted API for the Monthly KM Report & operator corrections.

Every method checks permissions before touching data (CLAUDE §6/§13). Customer-facing
viewing goes through the public token page (`/km-report/<token>`), NOT these methods —
these are for the Desk/SPA of internal roles (Ops, Depot Manager, Service Engineer) and
Ops corrections. Responses use the standard `{success, data, message}` envelope.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.utils import cint, flt

from vehicle_maintenance.fleet_service import km_report


@frappe.whitelist()
def get_km_report(customer: str, year_month: str) -> dict:
	"""Month summary (per-vehicle rollup + totals + trend) for a customer.

	`year_month` is 'YYYY-MM'. Requires read access to Vehicle KM Daily (internal
	roles only; Customer has none and uses the public link instead).
	"""
	frappe.has_permission("Vehicle KM Daily", "read", throw=True)
	if not customer or not year_month:
		frappe.throw(_("customer and year_month are required."))
	return {"success": True, "data": km_report.build_report_payload(customer, year_month)}


@frappe.whitelist()
def get_vehicle_km_breakdown(vehicle: str, year_month: str) -> dict:
	"""Week + day drill-down for one vehicle in a month (month > week > day)."""
	frappe.has_permission("Vehicle KM Daily", "read", throw=True)
	if not vehicle or not year_month:
		frappe.throw(_("vehicle and year_month are required."))
	return {
		"success": True,
		"data": {
			"weeks": km_report.week_breakdown(vehicle, year_month),
			"days": km_report.day_breakdown(vehicle, year_month),
		},
	}


@frappe.whitelist()
def get_sla_dashboard(customer: str, start: str, end: str) -> dict:
	"""Per-vehicle uptime + fleet SLA summary for a customer over [start, end].

	`start`/`end` are 'YYYY-MM-DD'. Any range works — pass a day, an ISO week, or a
	full month for day/week/month views. Internal roles only.
	"""
	frappe.has_permission("Vehicle KM Daily", "read", throw=True)
	if not customer or not start or not end:
		frappe.throw(_("customer, start and end are required."))
	from vehicle_maintenance.fleet_service import sla

	return {"success": True, "data": sla.fleet_sla(customer, start, end)}


@frappe.whitelist()
def correct_km_day(
	row_name: str,
	is_excluded: int | bool = 0,
	exclusion_reason: str | None = None,
	override_distance: int | bool = 0,
	corrected_distance: float | None = None,
	notes: str | None = None,
) -> dict:
	"""Apply an operator correction to one Vehicle KM Daily row.

	Requires WRITE permission on that specific row. Saved through the document so
	server-side validation runs and the corrector is stamped.
	"""
	frappe.has_permission("Vehicle KM Daily", "write", doc=row_name, throw=True)
	doc = frappe.get_doc("Vehicle KM Daily", row_name)
	doc.is_excluded = 1 if cint(is_excluded) else 0
	doc.exclusion_reason = exclusion_reason if doc.is_excluded else None
	doc.override_distance = 1 if cint(override_distance) else 0
	doc.corrected_distance = flt(corrected_distance) if doc.override_distance else None
	doc.correction_notes = notes
	doc.save()
	return {
		"success": True,
		"data": {"effective_distance": doc.effective_distance, "is_excluded": doc.is_excluded},
		"message": _("Correction saved."),
	}


@frappe.whitelist()
def regenerate_snapshot(customer: str, year_month: str) -> dict:
	"""Rebuild the KM Report Snapshot for a customer/month (fresh 7-day link). Ops only."""
	frappe.only_for(["System Manager", "Central Ops"])
	from vehicle_maintenance.fleet_service import monthly_km_report

	name = monthly_km_report.generate_snapshot(customer, year_month)
	token = frappe.db.get_value("KM Report Snapshot", name, "public_token")
	return {
		"success": True,
		"data": {"snapshot": name, "url": frappe.utils.get_url(f"/km-report/{token}")},
		"message": _("Report regenerated."),
	}
