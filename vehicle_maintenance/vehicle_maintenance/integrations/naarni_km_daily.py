"""Sync per-vehicle per-day odometer facts from the Naarni analytics-service into
the `Vehicle KM Daily` doctype — the source of truth for the Monthly KM Report and
SLA Management.

Design mirrors `naarni_vehicles.sync_vehicle_directory`: a scheduled, idempotent,
best-effort upsert. The crucial rule is that a re-sync updates ONLY the raw
telematics fields (`start_km`, `end_km`, `distance_km`, `is_inactive`, `date_basis`,
`synced_at`) and NEVER touches an operator's correction (`is_excluded`,
`corrected_distance`, ...). We do that by writing raw fields on existing rows with
`frappe.db.set_value` (which bypasses the controller), and only running the full
document insert for brand-new days.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import frappe
from frappe.utils import add_days, getdate, now

from vehicle_maintenance.integrations import naarni_client

IST = timezone(timedelta(hours=5, minutes=30))


def _ist_today():
	"""Today's calendar date in IST (independent of the server timezone)."""
	return datetime.now(timezone.utc).astimezone(IST).date()


def _num(v) -> float | None:
	try:
		return float(v) if v is not None else None
	except (TypeError, ValueError):
		return None


def _vehicle_for(naarni_id, cache: dict) -> str | None:
	"""Map a Naarni vehicleId to a Frappe Vehicle docname (cached per run)."""
	if naarni_id is None:
		return None
	key = str(naarni_id)
	if key not in cache:
		cache[key] = frappe.db.get_value("Vehicle", {"naarni_vehicle_id": key}, "name")
	return cache[key]


def _upsert_km_daily(row: dict, date_basis: str, stamp: str, cache: dict) -> str:
	"""Upsert one daily row. Returns 'created' | 'updated' | 'skipped'."""
	vehicle = _vehicle_for(row.get("vehicleId"), cache)
	date = row.get("date")
	if not vehicle or not date:
		return "skipped"

	raw = {
		"start_km": _num(row.get("startOdo")),
		"end_km": _num(row.get("endOdo")),
		"distance_km": _num(row.get("distTravelledKm")),
		"is_inactive": 1 if row.get("isInactive") else 0,
		"date_basis": date_basis,
		"source": "Telematics",
		"synced_at": stamp,
	}

	existing = frappe.db.get_value("Vehicle KM Daily", {"vehicle": vehicle, "date": getdate(date)}, "name")
	if existing:
		# Raw fields only — never clobber operator corrections; skip controller/validate.
		frappe.db.set_value("Vehicle KM Daily", existing, raw)
		return "updated"

	doc = frappe.new_doc("Vehicle KM Daily")
	doc.vehicle = vehicle
	doc.date = getdate(date)
	for k, v in raw.items():
		doc.set(k, v)
	doc.flags.ignore_permissions = True
	doc.insert(ignore_permissions=True)
	return "created"


def _run_sync(start: str, end: str, vehicle_ids: list[int] | None) -> dict:
	"""Fetch [start, end] (IST dates) and upsert. Per-row errors are logged, not fatal."""
	try:
		body = naarni_client.fetch_km_daily(vehicle_ids, start, end)
	except Exception:
		frappe.log_error(title="KM daily sync: fetch failed")
		raise

	rows = body.get("rows") or []
	date_basis = body.get("dateBasis") or "IST"
	if date_basis == "UTC":
		# The endpoint passes the upstream day-bucket through; a UTC basis means the
		# IST day boundary is wrong by 5.5h and must be fixed in the upstream ETL.
		frappe.log_error(
			title="KM daily sync: UTC date basis",
			message="energy_mileage_daily is UTC-bucketed; IST day boundaries are off by 5.5h. Fix upstream ETL.",
		)

	stamp = now()
	cache: dict = {}
	created = updated = skipped = failed = 0
	for row in rows:
		try:
			result = _upsert_km_daily(row, date_basis, stamp, cache)
			created += result == "created"
			updated += result == "updated"
			skipped += result == "skipped"
		except Exception:
			failed += 1
			frappe.log_error(title="KM daily sync: row failed", message=frappe.as_json(row))
	frappe.db.commit()

	summary = {
		"window": {"start": start, "end": end},
		"date_basis": date_basis,
		"fetched": len(rows),
		"created": created,
		"updated": updated,
		"skipped": skipped,
		"failed": failed,
	}
	frappe.logger("naarni").info(f"km daily sync: {summary}")
	return summary


def sync_km_daily(lookback_days: int = 3, vehicle_ids: list[int] | None = None) -> dict:
	"""Scheduled daily sync of a short rolling window (back-fills late telematics rows).

	Window is `[today - lookback_days, today]` in IST. No-op when the integration is
	disabled. Idempotent — safe to run repeatedly.
	"""
	if not naarni_client.is_enabled():
		return {"skipped": "integration disabled"}
	end = _ist_today()
	start = add_days(end, -int(lookback_days))
	return _run_sync(str(getdate(start)), str(end), vehicle_ids)


@frappe.whitelist()
def sync_km_daily_backfill(start: str, end: str, vehicle: str | None = None) -> dict:
	"""Ops-triggered one-time historical load for [start, end] (YYYY-MM-DD, IST).

	Optionally scoped to a single Vehicle. Requires System Manager / Central Ops.
	"""
	frappe.only_for(["System Manager", "Central Ops"])
	if not naarni_client.is_enabled():
		frappe.throw(frappe._("Naarni integration is disabled (enable_naarni_integration)."))

	vehicle_ids: list[int] | None = None
	if vehicle:
		naarni_id = frappe.db.get_value("Vehicle", vehicle, "naarni_vehicle_id")
		vehicle_ids = [int(naarni_id)] if naarni_id else []
	return {"success": True, "data": _run_sync(str(getdate(start)), str(getdate(end)), vehicle_ids)}
