"""Duty Attendance Board — the Desk view of who worked when, and where.

Reads Duty Attendance rather than recomputing from punches: the rollup is
already authoritative, and a report that did its own arithmetic would be a
second opinion nobody asked for.

A Depot Manager is silently scoped to their own depots; Central Ops and above
see everything.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.utils import cint, flt, getdate

from vehicle_maintenance.fleet_service import roster as R


def execute(filters: dict | None = None):
	filters = frappe._dict(filters or {})
	_validate(filters)
	rows = _fetch(filters)
	return _columns(), rows, None, None, _report_summary(rows)


def _validate(filters) -> None:
	if not filters.from_date or not filters.to_date:
		frappe.throw(_("Pick a date range."))
	if getdate(filters.to_date) < getdate(filters.from_date):
		frappe.throw(_("To Date cannot be before From Date."))
	if (getdate(filters.to_date) - getdate(filters.from_date)).days > 366:
		frappe.throw(_("Pick a range of one year or less."))


def _scoped_depots(filters) -> list[str] | None:
	"""None means unrestricted."""
	roles = set(frappe.get_roles(frappe.session.user))
	if roles & {"Central Ops", "N. Maintenance Head", "System Manager", "Administrator"}:
		return [filters.depot] if filters.depot else None

	mine = R.user_depots(frappe.session.user)
	if filters.depot:
		if filters.depot not in mine:
			frappe.throw(_("You do not have access to that depot."), frappe.PermissionError)
		return [filters.depot]
	# A manager with no depot assignment sees nothing rather than everything.
	return mine or ["\x00none\x00"]


def _fetch(filters) -> list[dict]:
	conditions = ["a.attendance_date BETWEEN %(from_date)s AND %(to_date)s"]
	params = {"from_date": getdate(filters.from_date), "to_date": getdate(filters.to_date)}

	depots = _scoped_depots(filters)
	if depots is not None:
		conditions.append("a.depot IN %(depots)s")
		params["depots"] = tuple(depots)
	if filters.engineer:
		conditions.append("a.user = %(engineer)s")
		params["engineer"] = filters.engineer
	if filters.status:
		conditions.append("a.status = %(status)s")
		params["status"] = filters.status
	if cint(filters.only_exceptions):
		conditions.append(
			"(a.is_late = 1 OR a.outside_geofence = 1 OR a.status IN ('Absent', 'Not Started'))"
		)

	rows = frappe.db.sql(
		"""
		SELECT a.attendance_date, a.user, a.employee_name, a.depot, a.shift, a.status,
		       a.planned_start, a.planned_end,
		       a.first_check_in, a.last_check_out, a.worked_hours,
		       a.is_late, a.late_by_minutes, a.early_exit_minutes,
		       a.outside_geofence, a.punch_count,
		       a.check_in_maps_link, a.manual_status, a.name
		FROM `tabDuty Attendance` a
		WHERE {conditions}
		ORDER BY a.attendance_date DESC, a.employee_name ASC
		""".format(conditions=" AND ".join(conditions)),
		params,
		as_dict=True,
	)

	for r in rows:
		r["attendance"] = r.pop("name")
		r["map"] = (
			f"""<a href="{r['check_in_maps_link']}" target="_blank">{_("Open")}</a>"""
			if r.get("check_in_maps_link")
			else ""
		)
		r["adjusted"] = 1 if r.get("manual_status") else 0
	return rows


def _columns() -> list[dict]:
	return [
		{"fieldname": "attendance_date", "label": _("Date"), "fieldtype": "Date", "width": 100},
		{
			"fieldname": "employee_name",
			"label": _("Engineer"),
			"fieldtype": "Data",
			"width": 170,
		},
		{"fieldname": "depot", "label": _("Depot"), "fieldtype": "Link", "options": "Depot", "width": 130},
		{
			"fieldname": "shift",
			"label": _("Shift"),
			"fieldtype": "Link",
			"options": "Duty Shift",
			"width": 100,
		},
		{"fieldname": "status", "label": _("Status"), "fieldtype": "Data", "width": 110},
		{
			"fieldname": "first_check_in",
			"label": _("Check In"),
			"fieldtype": "Datetime",
			"width": 160,
		},
		{
			"fieldname": "last_check_out",
			"label": _("Check Out"),
			"fieldtype": "Datetime",
			"width": 160,
		},
		{"fieldname": "worked_hours", "label": _("Hours"), "fieldtype": "Float", "precision": 2, "width": 80},
		{"fieldname": "late_by_minutes", "label": _("Late By"), "fieldtype": "Int", "width": 90},
		{"fieldname": "early_exit_minutes", "label": _("Early Exit (min)"), "fieldtype": "Int", "width": 120},
		{"fieldname": "outside_geofence", "label": _("Geofence"), "fieldtype": "Data", "width": 100},
		{"fieldname": "punch_count", "label": _("Punches"), "fieldtype": "Int", "width": 80},
		{"fieldname": "map", "label": _("Map"), "fieldtype": "HTML", "width": 70},
		{"fieldname": "adjusted", "label": _("Adjusted"), "fieldtype": "Check", "width": 90},
		{
			"fieldname": "attendance",
			"label": _("Record"),
			"fieldtype": "Link",
			"options": "Duty Attendance",
			"width": 130,
		},
	]


def _report_summary(rows: list[dict]) -> list[dict]:
	"""The four numbers a depot manager scans before reading any row."""
	if not rows:
		return []
	on_duty = len([r for r in rows if r["status"] == R.STATUS_ON_DUTY])
	late = len([r for r in rows if cint(r["is_late"])])
	absent = len([r for r in rows if r["status"] in (R.STATUS_ABSENT, R.STATUS_NOT_STARTED)])
	hours = round(sum(flt(r["worked_hours"]) for r in rows), 1)
	return [
		{"label": _("On Duty"), "value": on_duty, "indicator": "Blue", "datatype": "Int"},
		{"label": _("Late"), "value": late, "indicator": "Red" if late else "Green", "datatype": "Int"},
		{
			"label": _("Absent / No-show"),
			"value": absent,
			"indicator": "Red" if absent else "Green",
			"datatype": "Int",
		},
		{"label": _("Total Hours"), "value": hours, "indicator": "Green", "datatype": "Float"},
	]
