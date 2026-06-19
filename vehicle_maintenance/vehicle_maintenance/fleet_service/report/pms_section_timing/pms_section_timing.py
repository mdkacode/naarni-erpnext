"""PMS Section Timing — per-technician per-section duration report.

Each row is a single section a technician completed on one Job Card. Variance
is the difference between captured duration and the template's std time.
"""

from __future__ import annotations

import frappe
from frappe import _


def execute(filters: dict | None = None):
	filters = filters or {}
	columns = _columns()
	data = _rows(filters)
	return columns, data


def _columns() -> list[dict]:
	return [
		{
			"label": _("Job Card"),
			"fieldname": "job_card",
			"fieldtype": "Link",
			"options": "Job Card",
			"width": 140,
		},
		{
			"label": _("Vehicle"),
			"fieldname": "vehicle",
			"fieldtype": "Link",
			"options": "Vehicle",
			"width": 110,
		},
		{"label": _("Depot"), "fieldname": "depot", "fieldtype": "Link", "options": "Depot", "width": 110},
		{
			"label": _("Technician"),
			"fieldname": "technician",
			"fieldtype": "Link",
			"options": "User",
			"width": 180,
		},
		{"label": _("Section"), "fieldname": "section_code", "fieldtype": "Data", "width": 80},
		{"label": _("Section Title"), "fieldname": "section_title", "fieldtype": "Data", "width": 220},
		{"label": _("Duration (min)"), "fieldname": "duration_minutes", "fieldtype": "Float", "width": 110},
		{"label": _("Std Time (min)"), "fieldname": "std_time_minutes", "fieldtype": "Float", "width": 110},
		{"label": _("Variance (min)"), "fieldname": "variance_minutes", "fieldtype": "Float", "width": 110},
		{"label": _("Date"), "fieldname": "started_at", "fieldtype": "Datetime", "width": 150},
	]


def _rows(filters: dict) -> list[dict]:
	conds = ["st.ended_at IS NOT NULL"]
	params: dict = {}
	if filters.get("from_date"):
		conds.append("st.started_at >= %(from_date)s")
		params["from_date"] = filters["from_date"]
	if filters.get("to_date"):
		conds.append("st.started_at <= %(to_date)s")
		params["to_date"] = filters["to_date"]
	if filters.get("technician"):
		conds.append("st.technician = %(technician)s")
		params["technician"] = filters["technician"]
	if filters.get("depot"):
		conds.append("jc.depot = %(depot)s")
		params["depot"] = filters["depot"]
	if filters.get("template_code"):
		conds.append("jc.check_sheet_template = %(template_code)s")
		params["template_code"] = filters["template_code"]
	if filters.get("section_code"):
		conds.append("st.section_code = %(section_code)s")
		params["section_code"] = filters["section_code"]

	where = " AND ".join(conds)

	rows = frappe.db.sql(
		f"""
        SELECT
            jc.name              AS job_card,
            jc.vehicle           AS vehicle,
            jc.depot             AS depot,
            st.technician        AS technician,
            st.section_code      AS section_code,
            st.section_title     AS section_title,
            ROUND(st.duration_seconds / 60.0, 2)                       AS duration_minutes,
            st.std_time_minutes                                        AS std_time_minutes,
            ROUND((st.duration_seconds / 60.0) - COALESCE(st.std_time_minutes, 0), 2) AS variance_minutes,
            st.started_at        AS started_at
        FROM `tabJob Card Section Timing` st
        INNER JOIN `tabJob Card` jc ON jc.name = st.parent
        WHERE {where}
        ORDER BY st.started_at DESC
        """,
		params,
		as_dict=True,
	)
	return rows
