"""SLA Report (Desk) — per-vehicle uptime for Ops over any date range.

Thin wrapper over `sla.fleet_sla` so Desk, the API and the public page agree on uptime.
"""

import frappe
from frappe import _

from vehicle_maintenance.fleet_service import sla


def execute(filters=None):
	filters = frappe._dict(filters or {})
	columns = _columns()
	if not filters.get("customer") or not filters.get("from_date") or not filters.get("to_date"):
		return columns, []

	result = sla.fleet_sla(filters.customer, filters.from_date, filters.to_date)
	data = [
		{
			"vehicle": r["vehicle"],
			"registration": r["registration"],
			"uptime_pct": r["uptime_pct"],
			"target": r["target"],
			"status": _("Breach")
			if r["breach"]
			else (_("No data") if r["uptime_pct"] is None else _("On target")),
			"active_days": r["active_days"],
			"observed_days": r["observed_days"],
			"service_days": r["service_days"],
			"breakdown_days": r["breakdown_days"],
		}
		for r in result["vehicles"]
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
		{"label": _("Uptime %"), "fieldname": "uptime_pct", "fieldtype": "Percent", "width": 100},
		{"label": _("Target %"), "fieldname": "target", "fieldtype": "Percent", "width": 100},
		{"label": _("Status"), "fieldname": "status", "fieldtype": "Data", "width": 110},
		{"label": _("Active Days"), "fieldname": "active_days", "fieldtype": "Int", "width": 90},
		{"label": _("Observed Days"), "fieldname": "observed_days", "fieldtype": "Int", "width": 110},
		{"label": _("Service"), "fieldname": "service_days", "fieldtype": "Int", "width": 80},
		{"label": _("Breakdown"), "fieldname": "breakdown_days", "fieldtype": "Int", "width": 90},
	]
