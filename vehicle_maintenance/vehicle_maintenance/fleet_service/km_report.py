"""Monthly KM Report aggregation — month > week > day rollups over Vehicle KM Daily.

Every number here honours operator corrections through a single `effective_distance`
rule (mirrors `VehicleKMDaily.effective_distance`): a corrected distance overrides the
raw value, an excluded day contributes 0, and raw distance is clamped to >= 0 so an
odometer reset can never produce negative billable KM.

`build_report_payload` produces the frozen, PII-safe view model rendered on the public
`/km-report/<token>` page and emailed to stakeholders — it contains only registration
numbers, odometer/distance figures and month totals (no operators, users or device ids).
"""

from __future__ import annotations

import frappe
from frappe.utils import flt, get_datetime, get_first_day, get_last_day, getdate, now_datetime

from vehicle_maintenance.fleet_service.doctype.vehicle_km_daily.vehicle_km_daily import (
	PLANNED_DOWNTIME_REASONS,
)

DEFAULT_UPTIME_TARGET = 95.0

_DAILY_FIELDS = [
	"vehicle",
	"date",
	"start_km",
	"end_km",
	"distance_km",
	"is_inactive",
	"is_excluded",
	"exclusion_reason",
	"override_distance",
	"corrected_distance",
]


# ──────────────────────────── month helpers ────────────────────────────


def month_bounds(year_month: str) -> tuple:
	"""('2026-06') -> (date(2026,6,1), date(2026,6,30))."""
	first = get_first_day(getdate(f"{year_month}-01"))
	return first, get_last_day(first)


def month_label(year_month: str) -> str:
	"""('2026-06') -> 'June 2026'."""
	return get_first_day(getdate(f"{year_month}-01")).strftime("%B %Y")


def prev_month(year_month: str) -> str:
	first = get_first_day(getdate(f"{year_month}-01"))
	prev_last = getdate(first).replace(day=1)
	# step back one day into the previous month, then normalise
	from frappe.utils import add_days

	p = get_first_day(add_days(prev_last, -1))
	return p.strftime("%Y-%m")


# ──────────────────────────── effective distance ────────────────────────────


def _get(row, field):
	return row.get(field) if isinstance(row, dict) else getattr(row, field, None)


def effective_distance(row) -> float:
	"""Billable/counted distance for one daily row (dict or Document).

	Precedence mirrors ``VehicleKMDaily.effective_distance``: excluded → 0; else an
	explicit override wins; else raw distance clamped to >= 0.
	"""
	if _get(row, "is_excluded"):
		return 0.0
	if _get(row, "override_distance"):
		return max(flt(_get(row, "corrected_distance")), 0.0)
	return max(flt(_get(row, "distance_km")), 0.0)


# ──────────────────────────── customer fleet ────────────────────────────


def customer_vehicles(customer: str) -> list[dict]:
	"""Vehicles belonging to a customer, with per-vehicle reporting overrides."""
	return frappe.get_all(
		"Vehicle",
		filters={"customer": customer},
		fields=[
			"name",
			"registration_number",
			"min_km_override",
			"uptime_target_override",
		],
		order_by="registration_number asc",
		limit_page_length=0,
	)


def _daily_rows(vehicle_names: list[str], start, end) -> list[dict]:
	if not vehicle_names:
		return []
	return frappe.get_all(
		"Vehicle KM Daily",
		filters={"vehicle": ["in", vehicle_names], "date": ["between", [str(start), str(end)]]},
		fields=_DAILY_FIELDS,
		order_by="vehicle asc, date asc",
		limit_page_length=0,
	)


# ──────────────────────────── rollups ────────────────────────────


def _rollup_vehicle_days(rows: list[dict]) -> dict:
	"""Aggregate one vehicle's ordered daily rows into a single summary dict."""
	first, last = rows[0], rows[-1]
	distance = sum(effective_distance(r) for r in rows)
	excluded = [r for r in rows if r.get("is_excluded")]
	service_days = sum(1 for r in excluded if r.get("exclusion_reason") == "Service")
	breakdown_days = sum(1 for r in excluded if r.get("exclusion_reason") == "Breakdown")
	active_days = sum(1 for r in rows if not r.get("is_inactive") and not r.get("is_excluded"))
	return {
		"start_km": flt(first.get("start_km")),
		"end_km": flt(last.get("end_km")),
		"distance_km": round(distance, 1),
		"days_with_data": len(rows),
		"active_days": active_days,
		"excluded_days": len(excluded),
		"service_days": service_days,
		"breakdown_days": breakdown_days,
	}


def month_vehicle_rollup(customer: str, year_month: str) -> list[dict]:
	"""Per-vehicle KM summary for a customer for one month.

	Returns one dict per vehicle that has data (plus zero-rows for vehicles with none),
	each with start/end odometer, billable distance and exclusion counts.
	"""
	start, end = month_bounds(year_month)
	vehicles = customer_vehicles(customer)
	by_name = {v["name"]: v for v in vehicles}
	rows = _daily_rows(list(by_name), start, end)

	grouped: dict[str, list[dict]] = {}
	for r in rows:
		grouped.setdefault(r["vehicle"], []).append(r)

	out: list[dict] = []
	for v in vehicles:
		vrows = grouped.get(v["name"], [])
		summary = (
			_rollup_vehicle_days(vrows)
			if vrows
			else {
				"start_km": 0.0,
				"end_km": 0.0,
				"distance_km": 0.0,
				"days_with_data": 0,
				"active_days": 0,
				"excluded_days": 0,
				"service_days": 0,
				"breakdown_days": 0,
			}
		)
		summary["vehicle"] = v["name"]
		summary["registration"] = v.get("registration_number") or v["name"]
		summary["billable_km"] = summary["distance_km"]
		out.append(summary)
	return out


def day_breakdown(vehicle: str, year_month: str) -> list[dict]:
	"""Per-day rows for one vehicle in a month (the deepest drill-down level)."""
	start, end = month_bounds(year_month)
	rows = _daily_rows([vehicle], start, end)
	return [
		{
			"date": str(r["date"]),
			"start_km": flt(r.get("start_km")),
			"end_km": flt(r.get("end_km")),
			"distance_km": round(effective_distance(r), 1),
			"raw_distance_km": round(flt(r.get("distance_km")), 1),
			"is_inactive": bool(r.get("is_inactive")),
			"is_excluded": bool(r.get("is_excluded")),
			"exclusion_reason": r.get("exclusion_reason"),
		}
		for r in rows
	]


def week_breakdown(vehicle: str, year_month: str) -> list[dict]:
	"""Per-ISO-week aggregation for one vehicle in a month (month > week level)."""
	start, end = month_bounds(year_month)
	rows = _daily_rows([vehicle], start, end)
	weeks: dict[tuple, list[dict]] = {}
	for r in rows:
		iso = getdate(r["date"]).isocalendar()
		weeks.setdefault((iso[0], iso[1]), []).append(r)

	out: list[dict] = []
	for (_iso_year, iso_week), wrows in sorted(weeks.items()):
		dates = [getdate(r["date"]) for r in wrows]
		out.append(
			{
				"iso_week": iso_week,
				"week_start": str(min(dates)),
				"week_end": str(max(dates)),
				"label": f"{min(dates).strftime('%d %b')} – {max(dates).strftime('%d %b')}",
				"distance_km": round(sum(effective_distance(r) for r in wrows), 1),
				"active_days": sum(1 for r in wrows if not r.get("is_inactive") and not r.get("is_excluded")),
				"excluded_days": sum(1 for r in wrows if r.get("is_excluded")),
			}
		)
	return out


def month_on_month(customer: str, year_month: str, n: int = 6) -> list[dict]:
	"""Total billable KM for the customer across the last `n` months (oldest first)."""
	months: list[str] = []
	ym = year_month
	for _ in range(n):
		months.append(ym)
		ym = prev_month(ym)
	months.reverse()

	trend: list[dict] = []
	for m in months:
		rollup = month_vehicle_rollup(customer, m)
		total = round(sum(v["billable_km"] for v in rollup), 1)
		trend.append({"month": m, "label": month_label(m), "billable_km": total})
	return trend


# ──────────────────────────── config / thresholds ────────────────────────────


def report_config(customer: str) -> dict | None:
	name = frappe.db.exists("Fleet Report Config", customer)
	if not name:
		return None
	doc = frappe.get_doc("Fleet Report Config", name)
	return {
		"display_name": doc.display_name or customer,
		"logo": doc.logo,
		"min_km_per_vehicle": flt(doc.min_km_per_vehicle),
		"contract_uptime_target": flt(doc.contract_uptime_target) or DEFAULT_UPTIME_TARGET,
		"enabled": bool(doc.enabled),
	}


def effective_min_km(vehicle_row: dict, config_min: float) -> float:
	override = vehicle_row.get("min_km_override")
	return flt(override) if override else flt(config_min)


# ──────────────────────────── PII-safe payload ────────────────────────────


def build_report_payload(customer: str, year_month: str, generated_at: str | None = None) -> dict:
	"""Frozen, PII-safe view model for the public page + email.

	Contains ONLY report-safe fields: registration numbers, odometer/distance figures,
	month totals and the trend. No operators, users, device ids or the customer PK.
	"""
	cfg = report_config(customer) or {
		"display_name": frappe.db.get_value("Customer", customer, "customer_name") or customer,
		"min_km_per_vehicle": 0.0,
		"contract_uptime_target": DEFAULT_UPTIME_TARGET,
	}
	vehicles_meta = {v["name"]: v for v in customer_vehicles(customer)}
	rollup = month_vehicle_rollup(customer, year_month)

	vehicle_rows: list[dict] = []
	for v in rollup:
		min_km = effective_min_km(vehicles_meta.get(v["vehicle"], {}), cfg.get("min_km_per_vehicle", 0.0))
		vehicle_rows.append(
			{
				"registration": v["registration"],
				"start_km": v["start_km"],
				"end_km": v["end_km"],
				"distance_km": v["distance_km"],
				"billable_km": v["billable_km"],
				"active_days": v["active_days"],
				"excluded_days": v["excluded_days"],
				"service_days": v["service_days"],
				"breakdown_days": v["breakdown_days"],
				"min_km": round(min_km, 1),
				"meets_min": (v["billable_km"] >= min_km) if min_km else None,
			}
		)

	totals = {
		"billable_km": round(sum(v["billable_km"] for v in vehicle_rows), 1),
		"distance_km": round(sum(v["distance_km"] for v in vehicle_rows), 1),
		"vehicles": len(vehicle_rows),
		"excluded_days": sum(v["excluded_days"] for v in vehicle_rows),
	}

	return {
		"customer_display": cfg["display_name"],
		"report_month": year_month,
		"month_label": month_label(year_month),
		"generated_at": generated_at,
		"min_km_per_vehicle": round(flt(cfg.get("min_km_per_vehicle")), 1),
		"totals": totals,
		"vehicles": vehicle_rows,
		"trend": month_on_month(customer, year_month, n=6),
	}


# ──────────────────────────── public token page ────────────────────────────


def public_report_context(token: str) -> dict | None:
	"""Resolve a public KM report by its share token, for the guest page.

	Reads ONLY the frozen `KM Report Snapshot` (no raw rows / users / customers).
	Returns None for an unknown/blank token (caller renders a generic 404); otherwise
	`{brand, logo_url, expired, report}` where `report` is the frozen PII-safe payload
	(None when the 7-day link has expired).
	"""
	token = (token or "").strip()
	if not token:
		return None
	snap = frappe.db.get_value(
		"KM Report Snapshot",
		{"public_token": token},
		["payload_json", "token_expires_on", "logo_url", "display_name", "status"],
		as_dict=True,
	)
	if not snap:
		return None
	expired = snap.status == "Expired" or (
		snap.token_expires_on and now_datetime() > get_datetime(snap.token_expires_on)
	)
	return {
		"brand": snap.display_name,
		"logo_url": snap.logo_url,
		"expired": bool(expired),
		"report": None if expired else frappe.parse_json(snap.payload_json),
	}
