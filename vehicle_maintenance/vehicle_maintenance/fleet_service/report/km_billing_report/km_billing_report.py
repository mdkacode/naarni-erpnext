"""KM Billing Report (Desk) — per-vehicle monthly distance for Ops.

Thin wrapper over `km_report.month_vehicle_rollup` so the Desk report and the public
page / email always agree on the numbers (single aggregation source).
"""

import frappe
from frappe import _

from vehicle_maintenance.fleet_service import km_report


def execute(filters=None):
	filters = frappe._dict(filters or {})
	columns = _columns()
	if not filters.get("customer") or not filters.get("month"):
		return columns, []

	rows = km_report.month_vehicle_rollup(filters.customer, filters.month)
	data = [
		{
			"vehicle": r["vehicle"],
			"registration": r["registration"],
			"start_km": r["start_km"],
			"end_km": r["end_km"],
			"distance_km": r["distance_km"],
			"billable_km": r["billable_km"],
			"active_days": r["active_days"],
			"excluded_days": r["excluded_days"],
			"service_days": r["service_days"],
			"breakdown_days": r["breakdown_days"],
		}
		for r in rows
	]
	return columns, data


def _columns():
	return [
		{
			"label": _("Vehicle"),
			"fieldname": "vehicle",
			"fieldtype": "Link",
			"options": "Vehicle",
			"width": 130,
		},
		{"label": _("Registration"), "fieldname": "registration", "fieldtype": "Data", "width": 130},
		{
			"label": _("Start Odo"),
			"fieldname": "start_km",
			"fieldtype": "Float",
			"precision": 1,
			"width": 100,
		},
		{"label": _("End Odo"), "fieldname": "end_km", "fieldtype": "Float", "precision": 1, "width": 100},
		{
			"label": _("Distance (km)"),
			"fieldname": "distance_km",
			"fieldtype": "Float",
			"precision": 1,
			"width": 110,
		},
		{
			"label": _("Billable KM"),
			"fieldname": "billable_km",
			"fieldtype": "Float",
			"precision": 1,
			"width": 110,
		},
		{"label": _("Active Days"), "fieldname": "active_days", "fieldtype": "Int", "width": 90},
		{"label": _("Excluded"), "fieldname": "excluded_days", "fieldtype": "Int", "width": 80},
		{"label": _("Service"), "fieldname": "service_days", "fieldtype": "Int", "width": 80},
		{"label": _("Breakdown"), "fieldname": "breakdown_days", "fieldtype": "Int", "width": 90},
	]
