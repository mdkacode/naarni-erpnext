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


# ──────────────────────────── live detail (full, IST) ────────────────────────────

IST_OFFSET_HOURS = 5
IST_OFFSET_MINUTES = 30


def _to_ist(ts_utc: str | None) -> str | None:
	"""Convert a Naarni UTC timestamp to an IST string.

	Naarni sends e.g. "2026-06-24 09:13:27.475000 UTC". Per the requirement, every
	time we surface is IST → "2026-06-24 14:43:27 IST". Returns the input unchanged
	if it can't be parsed.
	"""
	if not ts_utc:
		return None
	from datetime import datetime, timedelta, timezone

	raw = str(ts_utc).replace(" UTC", "").replace("Z", "").strip()
	for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
		try:
			dt = datetime.strptime(raw, fmt).replace(tzinfo=timezone.utc)
			ist = dt.astimezone(timezone(timedelta(hours=IST_OFFSET_HOURS, minutes=IST_OFFSET_MINUTES)))
			return ist.strftime("%Y-%m-%d %H:%M:%S IST")
		except ValueError:
			continue
	return ts_utc


def _num(v) -> float | None:
	try:
		return float(v) if v is not None else None
	except (TypeError, ValueError):
		return None


def live_detail(vehicle: str) -> dict | None:
	"""Full live Naarni detail for a Frappe Vehicle, normalised + IST-stamped.

	Returns every group from `/v1/analytics/vehicles/{id}` (identity, location,
	battery, motor, charging, distance, status) flattened for easy consumption by
	tickets, job cards, and the app. None when the vehicle isn't linked or Naarni is
	unreachable (callers treat that as "no live data", never an error).
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
	location = detail.get("location") or {}
	battery = detail.get("battery") or {}
	motor = detail.get("motor") or {}
	charging = detail.get("charging") or {}
	distance = detail.get("distance") or {}
	status = detail.get("status") or {}

	odo = _num(distance.get("odometerreading"))
	lat, lng = _num(location.get("latitude")), _num(location.get("longitude"))
	return {
		# identity
		"registration_number": identity.get("registrationNumber"),
		"operator": identity.get("operator"),
		"make": identity.get("make"),
		"model": identity.get("model"),
		"route_name": identity.get("routeName"),
		"activity": identity.get("activity"),
		"connectivity_status": identity.get("connectivityStatus"),
		"is_registered": identity.get("isRegistered"),
		# distance
		"odometer": int(odo) if odo is not None else None,
		"odometer_exact": odo,
		"distance_to_empty": _num(distance.get("distancetoempty")),
		# location
		"latitude": lat,
		"longitude": lng,
		"altitude": _num(location.get("altitude")),
		"ground_speed_kmph": _num(location.get("groundSpeedKmph")),
		"maps_link": f"https://maps.google.com/?q={lat},{lng}"
		if lat is not None and lng is not None
		else None,
		# battery
		"battery_soc": _num(battery.get("batSoc")),
		"battery_soh": _num(battery.get("soh")),
		"battery_voltage": _num(battery.get("batVoltage")),
		"battery_current": _num(battery.get("totalBatteryCurrent")),
		"cell_max_c": _num(battery.get("cellmaxC")),
		"battery_coolant_temp": _num(battery.get("batterycoolanttemperature")),
		# motor
		"motor_rpm": _num(motor.get("motorRpm")),
		"motor_torque": _num(motor.get("motorTorque")),
		"motor_temp": _num(motor.get("motorTemperature")),
		"igbt_temp": _num(motor.get("igbtTemperature")),
		# charging
		"gun_connection_status": charging.get("gunConnectionStatus"),
		"charger_current": _num(charging.get("chargerCurrent")),
		"charger_voltage": _num(charging.get("chargerVoltage")),
		"gun_thermal_status": charging.get("gunThermalStatus"),
		"pack_thermal_status": charging.get("packThermalStatus"),
		# status (timestamp in IST)
		"ac_status": status.get("acStatus"),
		"ignition_status": status.get("ignitionstatus"),
		"vehicle_operation_mode": status.get("vehicleOperationMode"),
		"last_msg_interval_mins": _num(status.get("lastMsgIntervalMins")),
		"telemetry_at_ist": _to_ist(status.get("timestamp")),
	}


def enrich_form_context(vehicle: str, data: dict) -> dict:
	"""Fold full live Naarni detail into a `get_job_card_form_context` payload.

	Live running-km becomes the odometer estimate, operator/make/model fill gaps,
	and the complete telemetry snapshot rides along under `live_telemetry` so the
	job-card create flow can show all of the vehicle's information.
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
		if not data.get("customer_name"):
			data["customer_name"] = live["operator"]
	if not data.get("make_model"):
		mm = " ".join(p for p in (live.get("make"), live.get("model")) if p)
		if mm:
			data["make_model"] = mm
	data["live_telemetry"] = live
	return data


# ──────────────────────────── app: fleet list ────────────────────────────


@frappe.whitelist()
def list_fleet(txt: str = "", limit: int = 200, offset: int = 0) -> dict:
	"""All synced vehicles, for the Service Engineer's in-app fleet list.

	Browseable + searchable by registration / model / operator. Served from the
	locally-synced Vehicle doctype (fast, offline-resilient).
	"""
	frappe.has_permission("Vehicle", "read", throw=True)
	params: dict = {"limit": min(int(limit), 500), "offset": int(offset)}
	cond = ""
	if (txt or "").strip():
		cond = "WHERE registration_number LIKE %(t)s OR make_model LIKE %(t)s OR operator LIKE %(t)s"
		params["t"] = f"%{txt.strip()}%"
	rows = frappe.db.sql(
		f"""
		SELECT name, registration_number, make_model, operator, depot, vehicle_status,
		       naarni_vehicle_id, last_synced_at
		FROM tabVehicle
		{cond}
		ORDER BY registration_number ASC
		LIMIT %(limit)s OFFSET %(offset)s
		""",
		params,
		as_dict=True,
	)
	total = frappe.db.count("Vehicle")
	return {"success": True, "data": {"vehicles": rows, "total": total}}


@frappe.whitelist()
def get_vehicle_live(vehicle: str) -> dict:
	"""Full live telemetry for one vehicle (IST timestamps), for the app detail view."""
	frappe.has_permission("Vehicle", "read", throw=True)
	return {"success": True, "data": live_detail(vehicle)}


# ──────────────────────────── one-time bootstrap ────────────────────────────


@frappe.whitelist(allow_guest=True)
def connect_naarni(access_token: str) -> dict:
	"""Bootstrap the vehicle-sync service account from an admin Naarni access token.

	The vehicle directory is ADMIN-gated; this stores an admin token as the service
	credential, enables the integration, and runs an immediate sync. The token IS
	the credential — we only store it after confirming it can read the directory
	(proving admin), so exposing this as a guest endpoint is safe.
	"""
	access_token = (access_token or "").strip()
	if not access_token:
		frappe.throw(_("access_token is required."))

	try:
		vehicles = naarni_client.list_vehicles_with_token(access_token)
	except Exception as exc:
		frappe.throw(
			_("That token cannot read the vehicle directory (admin required): {0}").format(str(exc)[:200])
		)

	from frappe.installer import update_site_config

	update_site_config("naarni_service_token", access_token)
	update_site_config("enable_naarni_integration", 1)
	# update_site_config mutates frappe.conf in-process, but be explicit so the
	# immediate sync below sees the new values.
	frappe.conf["naarni_service_token"] = access_token
	frappe.conf["enable_naarni_integration"] = 1
	frappe.cache().delete_value("naarni_service_access_token")

	summary = sync_vehicle_directory()
	return {"success": True, "data": {"vehicles_visible": len(vehicles), "sync": summary}}
