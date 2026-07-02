"""SLA Management — vehicle uptime over the same Vehicle KM Daily data.

Uptime definition (locked decision):

    uptime% = active_days / (calendar_days - planned_downtime_days)

where a day is *active* if it has a telematics row that is neither inactive nor
excluded, and *planned downtime* is an excluded day whose reason is Service or
Breakdown (removed from the denominator so known off-road days don't penalise the
vehicle). Days with no telematics row at all stay in the denominator and therefore
count against uptime — an honest "we didn't hear from the bus" signal — unless an
operator explicitly excludes them.

A vehicle breaches when its uptime is below the effective contract target (per-vehicle
override → customer config → global default).
"""

from __future__ import annotations

import frappe
from frappe.utils import flt, getdate

from vehicle_maintenance.fleet_service import km_report
from vehicle_maintenance.fleet_service.doctype.vehicle_km_daily.vehicle_km_daily import (
	PLANNED_DOWNTIME_REASONS,
)

GOOD_COLOR = "#10B981"
BAD_COLOR = "#EF4444"

_SLA_FIELDS = ["date", "is_inactive", "is_excluded", "exclusion_reason"]


def _calendar_days(start, end) -> int:
	return (getdate(end) - getdate(start)).days + 1


def _rows(vehicle: str, start, end) -> list[dict]:
	return frappe.get_all(
		"Vehicle KM Daily",
		filters={"vehicle": vehicle, "date": ["between", [str(getdate(start)), str(getdate(end))]]},
		fields=_SLA_FIELDS,
		limit_page_length=0,
	)


def vehicle_uptime(vehicle: str, start, end) -> dict:
	"""Uptime metrics for one vehicle over [start, end] (inclusive)."""
	rows = _rows(vehicle, start, end)
	calendar_days = _calendar_days(start, end)
	service_days = sum(1 for r in rows if r.get("is_excluded") and r.get("exclusion_reason") == "Service")
	breakdown_days = sum(1 for r in rows if r.get("is_excluded") and r.get("exclusion_reason") == "Breakdown")
	planned_days = sum(
		1 for r in rows if r.get("is_excluded") and r.get("exclusion_reason") in PLANNED_DOWNTIME_REASONS
	)
	active_days = sum(1 for r in rows if not r.get("is_inactive") and not r.get("is_excluded"))
	denom = calendar_days - planned_days
	uptime = round(100.0 * active_days / denom, 1) if denom > 0 else None
	return {
		"calendar_days": calendar_days,
		"active_days": active_days,
		"planned_days": planned_days,
		"service_days": service_days,
		"breakdown_days": breakdown_days,
		"denominator_days": denom,
		"uptime_pct": uptime,
	}


def effective_target(vehicle_row: dict, config_target: float) -> float:
	override = vehicle_row.get("uptime_target_override")
	return flt(override) if override else flt(config_target)


def _decorate(uptime_pct, target) -> dict:
	breach = uptime_pct is not None and uptime_pct < target
	color = GOOD_COLOR if (uptime_pct is not None and uptime_pct >= target) else BAD_COLOR
	return {"target": round(flt(target), 1), "breach": bool(breach), "color": color}


def fleet_sla(customer: str, start, end) -> dict:
	"""Per-vehicle uptime + fleet summary for a customer over [start, end]."""
	cfg = km_report.report_config(customer) or {}
	target_default = cfg.get("contract_uptime_target", km_report.DEFAULT_UPTIME_TARGET)
	vehicles = km_report.customer_vehicles(customer)

	rows: list[dict] = []
	measured: list[float] = []
	breaches = 0
	for v in vehicles:
		m = vehicle_uptime(v["name"], start, end)
		target = effective_target(v, target_default)
		deco = _decorate(m["uptime_pct"], target)
		if m["uptime_pct"] is not None:
			measured.append(m["uptime_pct"])
		breaches += int(deco["breach"])
		rows.append(
			{
				"vehicle": v["name"],
				"registration": v.get("registration_number") or v["name"],
				"uptime_pct": m["uptime_pct"],
				"active_days": m["active_days"],
				"calendar_days": m["calendar_days"],
				"service_days": m["service_days"],
				"breakdown_days": m["breakdown_days"],
				**deco,
			}
		)

	avg = round(sum(measured) / len(measured), 1) if measured else None
	return {
		"start": str(getdate(start)),
		"end": str(getdate(end)),
		"target": round(flt(target_default), 1),
		"summary": {
			"vehicles": len(rows),
			"avg_uptime": avg,
			"breaches": breaches,
			"target": round(flt(target_default), 1),
		},
		"vehicles": rows,
	}


def month_sla_section(customer: str, year_month: str) -> dict:
	"""SLA section for the monthly report/public page (uptime for the report month)."""
	start, end = km_report.month_bounds(year_month)
	return fleet_sla(customer, start, end)
