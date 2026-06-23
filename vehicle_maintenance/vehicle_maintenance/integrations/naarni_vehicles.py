"""Sync the Naarni vehicle directory into Frappe + fetch live per-vehicle detail.

Two halves, matching the chosen "sync catalog + live detail" design:

* `sync_vehicle_directory()` — scheduled. Pulls the ADMIN vehicle directory
  (`/v1/analytics/vehicles`) with the service token and upserts each row into the
  Frappe `Vehicle` doctype (keyed on `naarni_vehicle_id`). This keeps the app's
  vehicle dropdown fast and offline-resilient (served locally by `search_vehicles`).

* `live_detail(vehicle)` / `enrich_form_context(...)` — on-demand. When a job-card
  form opens we fetch `/v1/analytics/vehicles/{id}` for the live odometer
  ("running km"), operator, and telemetry, and fold it into the autofill payload.

Everything here is best-effort: a Naarni outage degrades to the last synced
values and never blocks job-card creation.
"""

from __future__ import annotations

import frappe
from frappe import _

from vehicle_maintenance.integrations import naarni_client


@frappe.whitelist()
def sync_now() -> dict:
	"""Admin-triggered manual run of the vehicle directory sync (Desk/Ops)."""
	frappe.only_for(["System Manager", "Ops Manager", "Central Ops"])
	return {"success": True, "data": sync_vehicle_directory()}


def _depot_name_to_link(depot_name: str | None) -> str | None:
	"""Find-or-create a Depot by display name; return its docname (None if no name)."""
	depot_name = (depot_name or "").strip()
	if not depot_name:
		return None
	if frappe.db.exists("Depot", depot_name):
		return depot_name
	existing = frappe.db.get_value("Depot", {"depot_name": depot_name}, "name")
	if existing:
		return existing
	doc = frappe.new_doc("Depot")
	doc.depot_name = depot_name
	# location_code is mandatory; derive a stable placeholder Ops can rename later.
	doc.location_code = depot_name.upper().replace(" ", "-")[:20] or "NAARNI"
	doc.flags.ignore_permissions = True
	doc.insert(ignore_permissions=True)
	return doc.name


def _make_model(item: dict) -> str | None:
	make = (item.get("make") or "").strip()
	model = (item.get("model") or "").strip()
	combined = " ".join(p for p in (make, model) if p)
	return combined or None


def _upsert_vehicle(item: dict) -> tuple[str, bool]:
	"""Upsert one directory row. Returns (vehicle_name, created)."""
	naarni_id = item.get("vehicleId")
	reg = (item.get("registrationNumber") or "").strip()
	if naarni_id is None and not reg:
		raise ValueError(f"directory row has neither vehicleId nor registrationNumber: {item}")

	name = None
	if naarni_id is not None:
		name = frappe.db.get_value("Vehicle", {"naarni_vehicle_id": str(naarni_id)}, "name")
	if not name and reg:
		name = frappe.db.get_value("Vehicle", reg, "name") or frappe.db.get_value(
			"Vehicle", {"registration_number": reg}, "name"
		)

	depot = _depot_name_to_link(item.get("depotName"))
	values = {
		"naarni_vehicle_id": str(naarni_id) if naarni_id is not None else None,
		"operator": item.get("operator"),
		"vehicle_status": item.get("status"),
		"last_synced_at": frappe.utils.now(),
	}
	make_model = _make_model(item)
	if make_model:
		values["make_model"] = make_model
	if depot:
		values["depot"] = depot

	if name:
		doc = frappe.get_doc("Vehicle", name)
		for k, v in values.items():
			if v is not None:
				doc.set(k, v)
		doc.flags.ignore_permissions = True
		doc.save(ignore_permissions=True)
		return doc.name, False

	# Create. registration_number is the autoname + mandatory; make_model mandatory.
	if not reg:
		raise ValueError(f"cannot create vehicle without registration number: {item}")
	doc = frappe.new_doc("Vehicle")
	doc.registration_number = reg
	doc.make_model = make_model or reg
	for k, v in values.items():
		if v is not None:
			doc.set(k, v)
	doc.flags.ignore_permissions = True
	doc.insert(ignore_permissions=True)
	return doc.name, True


def sync_vehicle_directory() -> dict:
	"""Pull the Naarni vehicle directory and upsert into Frappe `Vehicle`.

	Scheduled (see hooks.scheduler_events) and also callable manually. Returns a
	summary dict; logs and swallows per-row errors so one bad row can't abort the run.
	"""
	if not naarni_client.is_enabled():
		return {"skipped": "integration disabled"}

	try:
		rows = naarni_client.list_vehicles()
	except Exception:
		frappe.log_error(title="Naarni vehicle sync: list failed")
		raise

	created = updated = failed = 0
	for item in rows:
		try:
			_, was_created = _upsert_vehicle(item)
			created += int(was_created)
			updated += int(not was_created)
		except Exception:
			failed += 1
			frappe.log_error(
				title="Naarni vehicle sync: row failed",
				message=frappe.as_json(item),
			)
	frappe.db.commit()
	summary = {"fetched": len(rows), "created": created, "updated": updated, "failed": failed}
	frappe.logger("naarni").info(f"vehicle sync: {summary}")
	return summary


# ──────────────────────────── live detail ────────────────────────────


def live_detail(vehicle: str) -> dict | None:
	"""Live Naarni detail for a Frappe Vehicle, normalised to a flat dict.

	Returns None when the vehicle isn't linked to Naarni or Naarni is unreachable
	(callers must treat this as "no live data", never an error).
	"""
	if not naarni_client.is_enabled():
		return None
	naarni_id = frappe.db.get_value("Vehicle", vehicle, "naarni_vehicle_id")
	if not naarni_id:
		return None
	try:
		detail = naarni_client.get_vehicle_detail(naarni_id)
	except Exception:
		frappe.log_error(title="Naarni live detail failed")
		return None
	if not detail:
		return None

	identity = detail.get("identity") or {}
	distance = detail.get("distance") or {}
	location = detail.get("location") or {}
	status = detail.get("status") or {}
	battery = detail.get("battery") or {}

	odo = distance.get("odometerreading")
	return {
		"odometer": int(float(odo)) if odo is not None else None,
		"operator": identity.get("operator"),
		"make": identity.get("make"),
		"model": identity.get("model"),
		"route_name": identity.get("routeName"),
		"activity": identity.get("activity"),
		"connectivity_status": identity.get("connectivityStatus"),
		"latitude": location.get("latitude"),
		"longitude": location.get("longitude"),
		"battery_soc": battery.get("batSoc"),
		"telemetry_timestamp": status.get("timestamp"),
		"distance_to_empty": distance.get("distancetoempty"),
	}


def enrich_form_context(vehicle: str, data: dict) -> dict:
	"""Fold live Naarni values into a `get_job_card_form_context` payload (in place).

	Live running-km becomes the odometer estimate (more accurate than the last job
	card), operator/make/model fill gaps, and a `live` sub-dict carries telemetry
	for display. No-op when there's no live data.
	"""
	live = live_detail(vehicle)
	if not live:
		data["live_telemetry"] = None
		return data

	if live.get("odometer") is not None:
		data["odometer_estimate"] = live["odometer"]
		data["odometer_source"] = "naarni_live"
	if live.get("operator"):
		data["operator"] = live["operator"]
		# Operator is the human-facing owner label when no CRM customer is linked.
		if not data.get("customer_name"):
			data["customer_name"] = live["operator"]
	if not data.get("make_model"):
		mm = " ".join(p for p in (live.get("make"), live.get("model")) if p)
		if mm:
			data["make_model"] = mm
	data["live_telemetry"] = {
		"latitude": live.get("latitude"),
		"longitude": live.get("longitude"),
		"battery_soc": live.get("battery_soc"),
		"activity": live.get("activity"),
		"connectivity_status": live.get("connectivity_status"),
		"route_name": live.get("route_name"),
		"timestamp": live.get("telemetry_timestamp"),
	}
	return data
