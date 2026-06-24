"""Whitelisted API methods for Job Card operations.

These methods are designed to be consumed by the Vue 3 frontend (Frappe UI)
and future React Native mobile app via token-based auth.
"""

import json as _json

import frappe
from frappe import _

# Reuse the controller's derivation tables so the App, the web SPA, and the
# server all compute identical auto-fill defaults from one source of truth.
from vehicle_maintenance.fleet_service.doctype.job_card.job_card import (
	HEALTH_SCORE_MAP,
	SERVICE_TYPE_BY_JOB_CARD_TYPE,
)

# Priority defaults per job card type (PRD: Breakdown is highest priority,
# Software Update is low). Mirrors the create wizard; centralised here so every
# client derives the same default.
PRIORITY_BY_JOB_CARD_TYPE: dict[str, str] = {
	"PMS + Repair": "Medium",
	"Only Repair": "Medium",
	"Software Update": "Low",
	"Breakdown": "Urgent",
}


@frappe.whitelist()
@frappe.validate_and_sanitize_search_inputs
def get_users_by_role(doctype, txt, searchfield, start, page_len, filters):
	"""Return users filtered by a specific role. Used by set_query on Link fields."""
	role = filters.get("role", "")
	return frappe.db.sql(
		"""
        SELECT u.name, u.full_name
        FROM `tabUser` u
        INNER JOIN `tabHas Role` r ON r.parent = u.name
        WHERE r.role = %(role)s
          AND u.enabled = 1
          AND u.name NOT IN ('Administrator', 'Guest')
          AND (u.name LIKE %(txt)s OR u.full_name LIKE %(txt)s)
        ORDER BY u.full_name
        LIMIT %(page_len)s OFFSET %(start)s
        """,
		{"role": role, "txt": f"%{txt}%", "page_len": page_len, "start": start},
	)


@frappe.whitelist()
def list_users_by_role(role: str, txt: str = "", limit: int = 50) -> dict:
	"""Return active users holding the given role — for Vue SPA assignment dropdowns.

	Designed for the JobCardDetail 'Assigned SE / Technician' pickers. Unlike
	`get_users_by_role` (which matches Frappe's Link-query signature), this
	endpoint returns a Vue-friendly `{success, data: [{user, full_name}]}`
	envelope and filters by the logged-in user's read permission on User.

	Args:
	    role:  Role name (e.g., "Service Engineer", "Technician").
	    txt:   Optional substring to match against user id or full name.
	    limit: Max rows, capped at 100.

	Returns:
	    Envelope with list of `{user, full_name}` objects sorted by full_name.
	"""
	frappe.only_for(
		[
			"Depot Manager",
			"Service Engineer",
			"Central Ops",
			"N. Maintenance Head",
			"Aftersales Eng",
		]
	)
	if not role:
		frappe.throw(_("Role is required."))

	limit = max(1, min(int(limit), 100))
	txt = (txt or "").strip()

	rows = frappe.db.sql(
		"""
        SELECT u.name AS user, u.full_name
        FROM `tabUser` u
        INNER JOIN `tabHas Role` r ON r.parent = u.name
        WHERE r.role = %(role)s
          AND u.enabled = 1
          AND u.user_type = 'System User'
          AND u.name NOT IN ('Administrator', 'Guest')
          AND (%(txt)s = '' OR u.name LIKE %(pattern)s OR u.full_name LIKE %(pattern)s)
        ORDER BY u.full_name ASC
        LIMIT %(limit)s
        """,
		{
			"role": role,
			"txt": txt,
			"pattern": f"%{txt}%",
			"limit": limit,
		},
		as_dict=True,
	)
	return {"success": True, "data": rows}


@frappe.whitelist()
def search_vehicles(txt: str = "", limit: int = 20) -> dict:
	"""Vehicles for the create-job-card dropdown (PRD: filtered, searchable list).

	With no query it returns the first `limit` vehicles so the dropdown is populated
	the moment it opens — the user never has to type to see options. Typing filters by
	registration / model / operator.
	"""
	txt = (txt or "").strip()
	limit = min(int(limit), 50)
	fields = "name, registration_number, make_model, customer, fuel_type, color, operator, depot, naarni_vehicle_id"

	if txt:
		vehicles = frappe.db.sql(
			f"""
			SELECT {fields}
			FROM tabVehicle
			WHERE registration_number LIKE %(txt)s
			   OR make_model LIKE %(txt)s
			   OR operator LIKE %(txt)s
			ORDER BY registration_number ASC
			LIMIT %(limit)s
			""",
			{"txt": f"%{txt}%", "limit": limit},
			as_dict=True,
		)
	else:
		vehicles = frappe.db.sql(
			f"""
			SELECT {fields}
			FROM tabVehicle
			ORDER BY registration_number ASC
			LIMIT %(limit)s
			""",
			{"limit": limit},
			as_dict=True,
		)

	return {"success": True, "data": vehicles}


@frappe.whitelist()
def search_depots(txt: str = "", limit: int = 10) -> dict:
	"""Search depots by name or city. For portal depot dropdown."""
	txt = (txt or "").strip()
	if len(txt) < 1:
		return {"success": True, "data": []}

	depots = frappe.db.sql(
		"""
        SELECT name, depot_name, city, state
        FROM tabDepot
        WHERE depot_name LIKE %(txt)s
           OR city LIKE %(txt)s
        ORDER BY depot_name ASC
        LIMIT %(limit)s
    """,
		{"txt": f"%{txt}%", "limit": int(limit)},
		as_dict=True,
	)

	return {"success": True, "data": depots}


@frappe.whitelist()
def get_customer_name(customer: str) -> dict:
	"""Get customer name for display."""
	name = frappe.db.get_value("Customer", customer, "customer_name")
	return {"success": True, "data": {"customer_name": name or customer}}


def _select_check_sheet(odometer: int | None) -> str:
	"""Mirror JobCard._auto_select_check_sheet for pre-create display (PRD thresholds)."""
	odo = int(odometer or 0)
	if odo <= 20_000:
		return "Sheet A (0-20,000 km)"
	if odo <= 40_000:
		return "Sheet B (20,001-40,000 km)"
	if odo <= 80_000:
		return "Sheet C (40,001-80,000 km)"
	return "Sheet D (80,001+ km)"


def _active_service_contract(customer: str | None) -> str | None:
	"""The customer's currently-active Service Contract, if any.

	Active = today within [start_date, end_date]; an unset end_date is treated as
	open-ended. Newest start wins when several overlap.
	"""
	if not customer:
		return None
	today = frappe.utils.today()
	rows = frappe.get_all(
		"Service Contract",
		filters=[["customer", "=", customer], ["start_date", "<=", today]],
		or_filters=[["end_date", ">=", today], ["end_date", "is", "not set"]],
		fields=["name"],
		order_by="start_date desc",
		limit_page_length=1,
	)
	return rows[0]["name"] if rows else None


def _suggest_subsystems(vehicle: str, limit: int = 6) -> list[dict]:
	"""Most-recently-touched subsystems for this vehicle — surfaced first in the
	subsystem SmartSelect so the common choice is one tap (full list via
	`list_subsystems`). Empty for a vehicle with no history.
	"""
	rows = frappe.db.sql(
		"""
        SELECT s.subsystem AS value, COUNT(*) AS uses
        FROM `tabJob Card Subsystem` s
        INNER JOIN `tabJob Card` jc ON jc.name = s.parent
        WHERE jc.vehicle = %(vehicle)s AND s.subsystem IS NOT NULL
        GROUP BY s.subsystem
        ORDER BY uses DESC
        LIMIT %(limit)s
        """,
		{"vehicle": vehicle, "limit": int(limit)},
		as_dict=True,
	)
	return [{"value": r["value"], "label": r["value"], "recent": True} for r in rows]


def _suggest_complaints(vehicle: str, limit: int = 6) -> list[dict]:
	"""Suggested complaints for the complaint SmartSelect.

	Prefers the Complaint Catalog master when it exists; always blends in this
	vehicle's recent distinct complaints (history) so the common report is one
	tap. Degrades to [] gracefully before the master is built.
	"""
	suggestions: list[dict] = []
	seen: set[str] = set()

	if frappe.db.exists("DocType", "Complaint Catalog"):
		for r in frappe.get_all(
			"Complaint Catalog",
			filters={"is_active": 1},
			fields=["name", "complaint_text", "subsystem"],
			order_by="usage_count desc",
			limit_page_length=int(limit),
		):
			text = r.get("complaint_text") or r.get("name")
			if text and text not in seen:
				seen.add(text)
				suggestions.append({"value": r["name"], "label": text, "sublabel": r.get("subsystem")})

	for r in frappe.get_all(
		"Job Card",
		filters=[["vehicle", "=", vehicle], ["complaint_description", "is", "set"]],
		fields=["complaint_description"],
		order_by="creation desc",
		limit_page_length=int(limit),
	):
		text = (r.get("complaint_description") or "").strip()
		if text and text not in seen:
			seen.add(text)
			suggestions.append({"value": text, "label": text, "recent": True})

	return suggestions[: int(limit)]


@frappe.whitelist()
def get_job_card_form_context(vehicle: str, job_card_type: str, odometer: int | None = None) -> dict:
	"""Return EVERY auto-fillable value for the Job Card create form in one call.

	The App (and web SPA) call this the instant the user picks a vehicle + job
	card type, so the rest of the form is pre-filled and the user only taps —
	nothing to type. Consolidates what used to be several round-trips
	(get_customer_name / get_last_pms_info / check-sheet logic) into one, keeping
	the non-tech-savvy create flow free of manual entry.

	Args:
	    vehicle: Vehicle name (registration number).
	    job_card_type: PMS + Repair / Only Repair / Software Update / Breakdown.
	    odometer: Optional current reading; when given, the PMS check sheet is
	        resolved too (else left blank for the UI to fill after odometer capture).

	Returns:
	    {success, data: {... all autofill fields + suggestion lists ...}}.
	"""
	if not vehicle:
		frappe.throw(_("Vehicle is required."))
	frappe.has_permission("Job Card", "create", throw=True)

	veh = frappe.db.get_value(
		"Vehicle",
		vehicle,
		["registration_number", "make_model", "oem", "customer"],
		as_dict=True,
	)
	if not veh:
		frappe.throw(_("Vehicle {0} not found.").format(vehicle))

	customer = veh.get("customer")
	cust = (
		frappe.db.get_value("Customer", customer, ["customer_name", "mobile_no"], as_dict=True)
		if customer
		else {}
	) or {}

	# Default depot = the depot most recently used for this vehicle (else blank →
	# user picks via SmartSelect). Same row gives a last-known odometer estimate.
	last_card = frappe.get_all(
		"Job Card",
		filters={"vehicle": vehicle},
		fields=["depot", "odometer_reading"],
		order_by="creation desc",
		limit_page_length=1,
	)
	default_depot = last_card[0]["depot"] if last_card else None
	depot_name = frappe.db.get_value("Depot", default_depot, "depot_name") if default_depot else None
	odo_estimate = last_card[0]["odometer_reading"] if last_card else None

	data = {
		"vehicle": vehicle,
		"vehicle_number": veh.get("registration_number"),
		"make_model": veh.get("make_model"),
		"oem": veh.get("oem"),
		"customer": customer,
		"customer_name": cust.get("customer_name"),
		"customer_phone": cust.get("mobile_no"),
		"depot": default_depot,
		"depot_name": depot_name,
		"service_contract": _active_service_contract(customer),
		"service_type": SERVICE_TYPE_BY_JOB_CARD_TYPE.get(job_card_type, "Other"),
		"priority": PRIORITY_BY_JOB_CARD_TYPE.get(job_card_type, "Medium"),
		"odometer_estimate": odo_estimate,
		"check_sheet": (
			_select_check_sheet(odometer) if job_card_type == "PMS + Repair" and odometer else None
		),
		"pms_tolerance_level": None,
		"last_pms_date": None,
		"last_pms_odometer": None,
		"last_serviced_by": None,
		"last_service_tolerance_level": None,
		"suggested_complaints": _suggest_complaints(vehicle),
		"suggested_subsystems": _suggest_subsystems(vehicle),
	}

	# Breakdown cards autofill the Last-PMS block (PRD p.15).
	if job_card_type == "Breakdown":
		last = get_last_pms_info(vehicle)
		if last.get("data"):
			d = last["data"]
			data["last_pms_date"] = d.get("last_pms_date")
			data["last_pms_odometer"] = d.get("last_pms_odometer")
			data["last_serviced_by"] = d.get("last_serviced_by")
			data["last_service_tolerance_level"] = d.get("last_service_tolerance_level")

	# Fold in live Naarni telemetry (running-km odometer, operator, location) when
	# the vehicle is linked. Best-effort — never blocks the form if Naarni is down.
	from vehicle_maintenance.integrations import naarni_vehicles

	naarni_vehicles.enrich_form_context(vehicle, data)

	return {"success": True, "data": data}


@frappe.whitelist()
def get_job_card_summary(job_card_name: str) -> dict:
	"""Return a role-appropriate summary of a single job card.

	Args:
	    job_card_name: The name (ID) of the Job Card document.

	Returns:
	    dict with success, data, and message keys.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)

	doc = frappe.get_doc("Job Card", job_card_name)
	user_roles = set(frappe.get_roles(frappe.session.user))

	# Surface the current user's roles so the SPA can gate controls (e.g., only
	# Depot Manager sees the Assigned SE / Technician dropdowns as editable).
	viewer_roles = sorted(user_roles)

	data = {
		"name": doc.name,
		"viewer_roles": viewer_roles,
		"viewer_is_depot_manager": bool(
			user_roles & {"Depot Manager", "N. Maintenance Head", "Administrator"}
		),
		"job_card_type": doc.job_card_type,
		"repair_subtype": doc.repair_subtype,
		"vehicle": doc.vehicle,
		"vehicle_number": doc.vehicle_number,
		"vehicle_make_model": doc.vehicle_make_model,
		"customer_name": doc.customer_name,
		"service_type": doc.service_type,
		"priority": doc.priority,
		"workflow_state": doc.workflow_state,
		"complaint_description": doc.complaint_description,
		"se_observations": doc.se_observations,
		"estimated_cost": float(doc.estimated_cost or 0),
		"actual_cost": float(doc.actual_cost or 0),
		"opened_at": str(doc.opened_at) if doc.opened_at else None,
		"closed_at": str(doc.closed_at) if doc.closed_at else None,
		"depot": doc.depot,
		"pre_pms_score": float(doc.pre_pms_score or 0),
		"post_pms_score": float(doc.post_pms_score or 0),
		"score_improvement": float(doc.score_improvement or 0),
		"health_card": doc.health_card,
		"force_closed": int(doc.force_closed or 0),
		"force_close_severity": doc.force_close_severity,
		"force_close_reason": doc.force_close_reason,
		"sla_breached": int(doc.sla_breached or 0),
		"requires_customer_approval": int(doc.requires_customer_approval or 0),
		"subsystems": [row.subsystem for row in doc.get("subsystems", [])],
		"followup_job_card": doc.followup_job_card,
		"source_force_close_job_card": doc.source_force_close_job_card,
		"send_report_to_customer": int(doc.send_report_to_customer or 0),
		"odometer_reading": doc.odometer_reading,
	}

	# Workflow actions available to THIS user in the current state — drives the
	# app's "progress the job card" buttons.
	try:
		data["available_actions"] = [t.action for t in doc.get_transitions()]
	except Exception:
		data["available_actions"] = []

	# Type-specific blocks — included only when relevant.
	if doc.job_card_type == "Breakdown":
		data["breakdown"] = {
			"incident_place": doc.incident_place,
			"fault_code_1": doc.fault_code_1,
			"fault_code_2": doc.fault_code_2,
			"fault_code_3": doc.fault_code_3,
			"remote_resolution_status": doc.remote_resolution_status,
			"remote_resolution_started_at": str(doc.remote_resolution_started_at)
			if doc.remote_resolution_started_at
			else None,
			"remote_resolution_failed_at": str(doc.remote_resolution_failed_at)
			if doc.remote_resolution_failed_at
			else None,
			"travel_started_at": str(doc.travel_started_at) if doc.travel_started_at else None,
			"arrived_at_location": str(doc.arrived_at_location) if doc.arrived_at_location else None,
			"travel_duration_minutes": float(doc.travel_duration_minutes or 0),
			"fix_type": doc.fix_type,
			"next_level_engineer": doc.next_level_engineer,
			"recurrence_risk": doc.recurrence_risk,
			"occurrence_risk": doc.occurrence_risk,
			"trial_trip_started_at": str(doc.trial_trip_started_at) if doc.trial_trip_started_at else None,
			"trial_trip_start_km": doc.trial_trip_start_km,
			"trial_trip_ended_at": str(doc.trial_trip_ended_at) if doc.trial_trip_ended_at else None,
			"trial_trip_end_km": doc.trial_trip_end_km,
			"trial_trip_distance_km": doc.trial_trip_distance_km,
			"trial_trip_duration_minutes": float(doc.trial_trip_duration_minutes or 0),
			"vehicle_handover_at": str(doc.vehicle_handover_at) if doc.vehicle_handover_at else None,
			"total_downtime_minutes": float(doc.total_downtime_minutes or 0),
			"last_pms_date": str(doc.last_pms_date) if doc.last_pms_date else None,
			"last_pms_odometer": doc.last_pms_odometer,
			"last_service_tolerance_level": doc.last_service_tolerance_level,
			"last_serviced_by": doc.last_serviced_by,
			"rca_notes": doc.rca_notes
			if "N. Maintenance Head" in user_roles
			or "Aftersales Eng" in user_roles
			or "Depot Manager" in user_roles
			or "Central Ops" in user_roles
			else None,
			"rca_received_at": str(doc.rca_received_at) if doc.rca_received_at else None,
			"force_override": int(doc.force_override or 0),
			"force_override_reason": doc.force_override_reason,
			"process_override": int(doc.process_override or 0),
			"process_override_steps": doc.process_override_steps,
			"groups_impacted": [row.part_group for row in doc.get("groups_impacted", [])],
		}

	# Internal roles see assignment + item details
	if user_roles & {"Depot Manager", "Service Engineer", "Technician", "Central Ops"}:
		data["assigned_service_engineer"] = doc.assigned_service_engineer
		data["assigned_technician"] = doc.assigned_technician
		data["repair_items"] = [
			{
				"part_group": item.part_group,
				"bus_system": item.bus_system,
				"description": item.description,
				"activity_type": item.activity_type,
				"component_status": item.component_status,
				"qty": float(item.qty or 0),
				"estimated_amount": float(item.estimated_amount or 0),
				"actual_amount": float(item.actual_amount or 0),
				"item_status": item.item_status,
			}
			for item in doc.get("repair_items", [])
		]
		data["maintenance_items"] = [
			{
				"maintenance_type": item.maintenance_type,
				"description": item.description,
				"action": item.action,
				"qty": float(item.qty or 0),
				"unit": item.unit,
				"estimated_amount": float(item.estimated_amount or 0),
				"actual_amount": float(item.actual_amount or 0),
				"item_status": item.item_status,
			}
			for item in doc.get("maintenance_items", [])
		]
		data["category_scores"] = [
			{
				"category": row.category,
				"component_count": row.component_count,
				"pre_pms_score": float(row.pre_pms_score or 0),
				"post_pms_score": float(row.post_pms_score or 0),
				"improvement": float(row.improvement or 0),
			}
			for row in doc.get("category_scores", [])
		]
		data["software_components"] = [
			{
				"component": row.component,
				"reason": row.reason,
				"status": row.status,
				"retry_count": row.retry_count,
				"pre_version": row.pre_version,
				"post_version": row.post_version,
				"pre_version_photo": row.pre_version_photo,
				"post_version_photo": row.post_version_photo,
				"calibration_values": row.calibration_values,
				"failure_notes": row.failure_notes,
			}
			for row in doc.get("software_components", [])
		]

	return {"success": True, "data": data, "message": ""}


@frappe.whitelist()
def get_my_job_cards(
	status: str | None = None,
	limit: int = 20,
	offset: int = 0,
) -> dict:
	"""Return job cards relevant to the current user and their role.

	Args:
	    status: Optional workflow_state filter.
	    limit: Page size (max 100).
	    offset: Pagination offset.

	Returns:
	    dict with success and data (list of job card summaries).
	"""
	limit = min(int(limit), 100)
	offset = max(int(offset), 0)
	user = frappe.session.user
	user_roles = set(frappe.get_roles(user))

	filters: dict = {}

	# Role-based scoping
	if "Customer" in user_roles and not user_roles & {"Depot Manager", "Central Ops"}:
		# Customers see only their own job cards
		customer_name = frappe.db.get_value("Customer", {"user": user}, "name")
		if not customer_name:
			return {"success": True, "data": [], "message": _("No linked customer account.")}
		filters["customer"] = customer_name
	elif "Technician" in user_roles and not user_roles & {"Depot Manager", "Central Ops"}:
		filters["assigned_technician"] = user
	elif "Service Engineer" in user_roles and not user_roles & {"Depot Manager", "Central Ops"}:
		filters["assigned_service_engineer"] = user

	if status:
		filters["workflow_state"] = status

	job_cards = frappe.get_list(
		"Job Card",
		filters=filters,
		fields=[
			"name",
			"vehicle_number",
			"customer_name",
			"service_type",
			"priority",
			"workflow_state",
			"modified",
		],
		order_by="modified desc",
		limit_page_length=limit,
		limit_start=offset,
	)

	return {"success": True, "data": job_cards, "message": ""}


@frappe.whitelist()
def transition_job_card(job_card_name: str, action: str) -> dict:
	"""Apply a workflow action to a job card.

	Args:
	    job_card_name: The Job Card document name.
	    action: The workflow action string (e.g., "Start Work", "Close Job Card").

	Returns:
	    dict with the new workflow_state after transition.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	from frappe.model.workflow import apply_workflow

	doc = frappe.get_doc("Job Card", job_card_name)
	apply_workflow(doc, action)
	doc.save()

	return {
		"success": True,
		"data": {"name": doc.name, "workflow_state": doc.workflow_state},
		"message": _("Job Card moved to {0}.").format(doc.workflow_state),
	}


@frappe.whitelist()
def update_job_card(job_card_name: str, updates: str | dict) -> dict:
	"""Update editable fields on a Job Card.

	Args:
	    job_card_name: The Job Card document name.
	    updates: JSON string or dict of field:value pairs to update.
	        Allowed fields: priority, complaint_description, assigned_technician,
	        assigned_service_engineer, depot, service_type.

	Per PRD p.3, only the Depot Manager (or N. Maintenance Head for governance)
	can change assignment fields. These fields are `permlevel: 1` in the DocType
	— Frappe's permlevel validator silently reverts them when a lower-privilege
	user saves, which manifested as "assignment doesn't persist". We gate the
	role check here and then save with `ignore_permissions=True` so the
	validator doesn't undo the DM's own write. All non-assignment edits keep
	the normal permission path.

	Returns:
	    dict with updated job card data and which fields were actually written.
	"""
	frappe.only_for(["Service Engineer", "Depot Manager", "N. Maintenance Head", "Administrator"])
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if isinstance(updates, str):
		updates = _json.loads(updates)

	ALLOWED_FIELDS = {
		"priority",
		"complaint_description",
		"assigned_technician",
		"assigned_service_engineer",
		"depot",
		"service_type",
	}
	# permlevel:1 fields — PRD restricts assignment to Depot Manager.
	DM_ONLY_FIELDS = {"assigned_service_engineer", "assigned_technician"}

	user_roles = set(frappe.get_roles(frappe.session.user))
	is_dm = bool(user_roles & {"Depot Manager", "N. Maintenance Head", "Administrator"})

	restricted_attempts: list[str] = []
	for field in list(updates.keys()):
		if field in DM_ONLY_FIELDS and not is_dm:
			restricted_attempts.append(field)

	if restricted_attempts:
		frappe.throw(
			_("Only the Depot Manager can change: {0}.").format(", ".join(restricted_attempts)),
			frappe.PermissionError,
		)

	doc = frappe.get_doc("Job Card", job_card_name)
	changed: list[str] = []
	touched_assignment = False
	for field, value in updates.items():
		if field not in ALLOWED_FIELDS:
			continue
		# Normalize empty string → None for Link fields so the DB stores NULL,
		# not an empty string that violates the User Link's FK lookup.
		if field in DM_ONLY_FIELDS and value == "":
			value = None
		doc.set(field, value)
		changed.append(field)
		if field in DM_ONLY_FIELDS:
			touched_assignment = True

	if not changed:
		frappe.throw(_("No valid fields to update."))

	# When the DM changes permlevel-1 fields, save with ignore_permissions so
	# Frappe's permlevel validator doesn't silently revert them. The role check
	# above is the authorization.
	doc.save(ignore_permissions=touched_assignment)

	return {
		"success": True,
		"data": {
			"name": doc.name,
			"updated_fields": changed,
			"assigned_service_engineer": doc.assigned_service_engineer,
			"assigned_technician": doc.assigned_technician,
		},
		"message": _("Job Card updated."),
	}


@frappe.whitelist()
def force_close_job_card(
	job_card_name: str,
	severity: str,
	reason: str,
) -> dict:
	"""Force-close a Job Card per PRD p.6 severity matrix.

	Args:
	    job_card_name: The Job Card document name.
	    severity: One of 'Minor', 'Major', 'Critical'.
	    reason: Human-readable reason (mandatory).

	The Job Card controller re-validates that the logged-in user's roles
	satisfy the severity's authority, throwing if not.

	Returns:
	    Envelope with new workflow_state and severity.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)
	if severity not in {"Minor", "Major", "Critical"}:
		frappe.throw(_("Invalid severity: {0}").format(severity))
	if not (reason or "").strip():
		frappe.throw(_("A reason is mandatory for force close."))

	doc = frappe.get_doc("Job Card", job_card_name)
	doc.force_closed = 1
	doc.force_close_severity = severity
	doc.force_close_reason = reason.strip()
	doc.workflow_state = "Closed"
	doc.save()

	return {
		"success": True,
		"data": {
			"name": doc.name,
			"workflow_state": doc.workflow_state,
			"force_close_severity": doc.force_close_severity,
		},
		"message": _("Job Card force-closed ({0}).").format(severity),
	}


@frappe.whitelist()
def save_repair_items(job_card_name: str, rows: str | list) -> dict:
	"""Replace the Job Card's `repair_items` child table with the supplied rows.

	Whitelisted fields on each row:
	    part_group, description, activity_type, qty, rate, component_status,
	    pre_repair_photo (File URL), post_repair_photo (File URL).

	Photos are expected as already-uploaded File URLs. The caller (Vue UI) is
	responsible for uploading the file first via `/api/method/upload_file`.
	"""
	frappe.only_for(["Service Engineer", "Technician", "Depot Manager"])
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if isinstance(rows, str):
		rows = _json.loads(rows)

	ALLOWED = {
		"part_group",
		"description",
		"activity_type",
		"qty",
		"rate",
		"component_status",
		"pre_repair_photo",
		"post_repair_photo",
		"item_status",
		"estimated_amount",
	}

	doc = frappe.get_doc("Job Card", job_card_name)
	doc.set("repair_items", [])
	for row in rows or []:
		clean = {k: v for k, v in row.items() if k in ALLOWED}
		# Fall back: compute estimated_amount if caller didn't.
		if "estimated_amount" not in clean:
			clean["estimated_amount"] = float(clean.get("qty", 0) or 0) * float(clean.get("rate", 0) or 0)
		doc.append("repair_items", clean)
	doc.save()

	return {
		"success": True,
		"data": {"name": doc.name, "count": len(doc.get("repair_items", []))},
		"message": _("Repair jobs saved."),
	}


@frappe.whitelist()
def save_maintenance_items(job_card_name: str, rows: str | list) -> dict:
	"""Replace the Job Card's `maintenance_items` child table with the supplied rows."""
	frappe.only_for(["Service Engineer", "Technician", "Depot Manager"])
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if isinstance(rows, str):
		rows = _json.loads(rows)

	ALLOWED = {
		"maintenance_type",
		"description",
		"action",
		"qty",
		"unit",
		"rate",
		"estimated_amount",
		"pre_photo",
		"post_photo",
		"item_status",
	}

	doc = frappe.get_doc("Job Card", job_card_name)
	doc.set("maintenance_items", [])
	for row in rows or []:
		clean = {k: v for k, v in row.items() if k in ALLOWED}
		if "estimated_amount" not in clean:
			clean["estimated_amount"] = float(clean.get("qty", 0) or 0) * float(clean.get("rate", 0) or 0)
		doc.append("maintenance_items", clean)
	doc.save()

	return {
		"success": True,
		"data": {"name": doc.name, "count": len(doc.get("maintenance_items", []))},
		"message": _("Maintenance jobs saved."),
	}


@frappe.whitelist()
def tat_adherence_report(
	from_date: str | None = None,
	to_date: str | None = None,
	depot: str | None = None,
) -> dict:
	"""Phase-2 PRD item: TAT Adherence reporting (PRD p.8 item 8).

	Aggregates closed Job Cards in the date window and returns per-type TAT
	compliance stats. Useful for the Central Ops analytics dashboard.

	Args:
	    from_date: ISO date string (inclusive). Defaults to 30 days ago.
	    to_date:   ISO date string (inclusive). Defaults to today.
	    depot:     Optional Depot filter.

	Returns:
	    { by_type: [ { job_card_type, total, on_time, breached, avg_hours,
	                   compliance_pct } ], overall: { ... } }
	"""
	frappe.only_for(
		[
			"Depot Manager",
			"Central Ops",
			"N. Maintenance Head",
			"Service Engineer",
			"Aftersales Eng",
		]
	)

	from frappe.utils import add_days, getdate, today

	from_date = getdate(from_date) if from_date else getdate(add_days(today(), -30))
	to_date = getdate(to_date) if to_date else getdate(today())

	filters = {
		"workflow_state": "Closed",
		"closed_at": ["between", [str(from_date), str(to_date)]],
	}
	if depot:
		filters["depot"] = depot

	rows = frappe.get_all(
		"Job Card",
		filters=filters,
		fields=[
			"name",
			"job_card_type",
			"repair_subtype",
			"opened_at",
			"closed_at",
			"sla_deadline",
			"sla_target_hours",
			"sla_breached",
		],
		limit_page_length=0,
	)

	from collections import defaultdict

	buckets: dict[str, dict] = defaultdict(
		lambda: {"total": 0, "on_time": 0, "breached": 0, "total_hours": 0.0}
	)
	for r in rows:
		key = r["job_card_type"] or "Unknown"
		if r["job_card_type"] == "Only Repair" and r["repair_subtype"] == "Accidental Major":
			key = "Only Repair (Accidental Major)"
		bucket = buckets[key]
		bucket["total"] += 1
		if r["sla_breached"]:
			bucket["breached"] += 1
		else:
			bucket["on_time"] += 1
		if r["opened_at"] and r["closed_at"]:
			delta = frappe.utils.time_diff_in_hours(r["closed_at"], r["opened_at"])
			bucket["total_hours"] += float(delta or 0)

	def summarise(b):
		total = b["total"] or 1
		return {
			"total": b["total"],
			"on_time": b["on_time"],
			"breached": b["breached"],
			"avg_hours": round(b["total_hours"] / total, 2) if b["total"] else 0,
			"compliance_pct": round((b["on_time"] / total) * 100, 1) if b["total"] else 0,
		}

	by_type = [{"job_card_type": k, **summarise(v)} for k, v in sorted(buckets.items())]
	overall_total = sum(b["total"] for b in buckets.values())
	overall_on_time = sum(b["on_time"] for b in buckets.values())
	overall_breached = sum(b["breached"] for b in buckets.values())
	overall_hours = sum(b["total_hours"] for b in buckets.values())

	return {
		"success": True,
		"data": {
			"from_date": str(from_date),
			"to_date": str(to_date),
			"depot": depot,
			"by_type": by_type,
			"overall": {
				"total": overall_total,
				"on_time": overall_on_time,
				"breached": overall_breached,
				"avg_hours": round(overall_hours / overall_total, 2) if overall_total else 0,
				"compliance_pct": round((overall_on_time / overall_total) * 100, 1) if overall_total else 0,
			},
		},
	}


@frappe.whitelist()
def repeated_issues_for_vehicle(
	vehicle: str,
	window_days: int = 180,
) -> dict:
	"""Phase-2 PRD item: "Any issues that were repeated in previous activities
	as well for the same bus" (PRD p.9 item 9 — Better Solutioning).

	Groups closed Job Cards for the vehicle within `window_days` by bus_system
	(repair items) and returns any group with >= 2 occurrences, newest first.
	"""
	frappe.has_permission("Vehicle", doc=vehicle, throw=True)

	from collections import defaultdict

	from frappe.utils import add_days, today

	since = add_days(today(), -abs(int(window_days)))

	repair_rows = frappe.db.sql(
		"""
        SELECT
            jc.name as job_card,
            jc.job_card_date,
            jc.closed_at,
            jc.job_card_type,
            ri.bus_system,
            ri.description
        FROM `tabJob Card` jc
        INNER JOIN `tabJob Card Repair Item` ri ON ri.parent = jc.name
        WHERE jc.vehicle = %(vehicle)s
          AND jc.workflow_state = 'Closed'
          AND jc.closed_at >= %(since)s
          AND ri.bus_system IS NOT NULL
          AND ri.bus_system != ''
        ORDER BY jc.closed_at DESC
        """,
		{"vehicle": vehicle, "since": since},
		as_dict=True,
	)

	grouped: dict[str, list[dict]] = defaultdict(list)
	for r in repair_rows:
		grouped[r["bus_system"]].append(
			{
				"job_card": r["job_card"],
				"closed_at": str(r["closed_at"]) if r["closed_at"] else None,
				"job_card_type": r["job_card_type"],
				"description": r["description"],
			}
		)

	repeats = [
		{"bus_system": system, "occurrences": len(items), "incidents": items[:10]}
		for system, items in grouped.items()
		if len(items) >= 2
	]
	repeats.sort(key=lambda x: x["occurrences"], reverse=True)

	return {
		"success": True,
		"data": {
			"vehicle": vehicle,
			"window_days": abs(int(window_days)),
			"repeated_issues": repeats,
		},
	}


@frappe.whitelist()
def list_part_groups() -> dict:
	"""Return the Part Group master for use in the Repair Jobs dropdown."""
	rows = frappe.get_all(
		"Part Group",
		fields=["name", "part_group_name", "bus_system"],
		order_by="part_group_name asc",
		limit_page_length=0,
	)
	return {"success": True, "data": rows}


@frappe.whitelist()
def list_subsystems() -> dict:
	"""Return the Subsystem master for the multi-select used by Only Repair /
	Software Update / Breakdown job cards.
	"""
	rows = frappe.get_all(
		"Subsystem",
		fields=["name", "subsystem_name", "category"],
		order_by="subsystem_name asc",
		limit_page_length=0,
	)
	return {"success": True, "data": rows}


@frappe.whitelist()
def list_complaints(subsystem: str = "", txt: str = "", limit: int = 20) -> dict:
	"""Searchable Complaint Catalog for the complaint SmartSelect.

	Opens with the most-used complaints (no typing needed); narrows by `txt` and,
	when given, by `subsystem`. Returns SmartSelect-shaped rows.
	"""
	filters = [["is_active", "=", 1]]
	if subsystem:
		filters.append(["subsystem", "=", subsystem])
	if (txt or "").strip():
		filters.append(["complaint_text", "like", f"%{txt.strip()}%"])
	rows = frappe.get_all(
		"Complaint Catalog",
		filters=filters,
		fields=["name", "complaint_text", "subsystem", "usage_count"],
		order_by="usage_count desc, complaint_text asc",
		limit_page_length=int(limit),
	)
	data = [{"value": r["name"], "label": r["complaint_text"], "sublabel": r.get("subsystem")} for r in rows]
	return {"success": True, "data": data}


@frappe.whitelist()
def list_fault_codes(part_group: str = "", subsystem: str = "", txt: str = "", limit: int = 20) -> dict:
	"""Searchable Fault Code master for the Breakdown fault-code SmartSelect."""
	filters = [["is_active", "=", 1]]
	if part_group:
		filters.append(["part_group", "=", part_group])
	if subsystem:
		filters.append(["subsystem", "=", subsystem])
	if (txt or "").strip():
		t = txt.strip()
		rows = frappe.get_all(
			"Fault Code",
			or_filters=[["fault_code", "like", f"%{t}%"], ["description", "like", f"%{t}%"]],
			filters=filters,
			fields=["name", "fault_code", "description", "severity", "subsystem"],
			order_by="fault_code asc",
			limit_page_length=int(limit),
		)
	else:
		rows = frappe.get_all(
			"Fault Code",
			filters=filters,
			fields=["name", "fault_code", "description", "severity", "subsystem"],
			order_by="fault_code asc",
			limit_page_length=int(limit),
		)
	data = [
		{
			"value": r["name"],
			"label": r["fault_code"],
			"sublabel": r.get("description"),
			"badge": r.get("severity"),
		}
		for r in rows
	]
	return {"success": True, "data": data}


@frappe.whitelist()
def list_observation_templates(subsystem: str = "", txt: str = "", limit: int = 20) -> dict:
	"""Searchable Observation Template master for the SE/technician notes SmartSelect."""
	filters = [["is_active", "=", 1]]
	if subsystem:
		filters.append(["subsystem", "=", subsystem])
	if (txt or "").strip():
		filters.append(["observation_text", "like", f"%{txt.strip()}%"])
	rows = frappe.get_all(
		"Observation Template",
		filters=filters,
		fields=["name", "observation_text", "subsystem", "usage_count"],
		order_by="usage_count desc, observation_text asc",
		limit_page_length=int(limit),
	)
	data = [
		{"value": r["name"], "label": r["observation_text"], "sublabel": r.get("subsystem")} for r in rows
	]
	return {"success": True, "data": data}


@frappe.whitelist()
def get_last_pms_info(vehicle: str) -> dict:
	"""Return Last PMS metadata for Breakdown-card autofill (PRD p.15).

	The same fields the `before_insert` hook fills — exposed as an API so the
	Vue UI can show 'Last PMS: 12 May, 43,210 km' hints before the JC is
	actually created.
	"""
	if not vehicle:
		frappe.throw(_("Vehicle is required."))
	frappe.has_permission("Vehicle", doc=vehicle, throw=True)

	rows = frappe.get_all(
		"Job Card",
		filters={
			"vehicle": vehicle,
			"job_card_type": "PMS + Repair",
			"workflow_state": "Closed",
			"force_closed": 0,
		},
		fields=[
			"name",
			"closed_at",
			"job_card_date",
			"odometer_reading",
			"pms_tolerance_level",
			"assigned_service_engineer",
			"assigned_technician",
		],
		order_by="closed_at desc",
		limit_page_length=1,
	)
	if not rows:
		return {"success": True, "data": None, "message": _("No prior PMS on record.")}

	r = rows[0]
	serviced_by = " / ".join(
		n for n in [r.get("assigned_service_engineer"), r.get("assigned_technician")] if n
	)
	return {
		"success": True,
		"data": {
			"last_pms_job_card": r["name"],
			"last_pms_date": str(r.get("closed_at") or r.get("job_card_date")),
			"last_pms_odometer": r.get("odometer_reading"),
			"last_service_tolerance_level": r.get("pms_tolerance_level"),
			"last_serviced_by": serviced_by,
		},
	}


@frappe.whitelist()
def update_breakdown_diagnosis(
	job_card_name: str,
	updates: str | dict,
) -> dict:
	"""Save breakdown-specific diagnosis fields (PRD p.15-16).

	Allowed fields: incident_place, fault_code_1/2/3, remote_resolution_status,
	remote_resolution_started_at, travel_started_at, arrived_at_location,
	fix_type, next_level_engineer, recurrence_risk, occurrence_risk,
	trial_trip_*, vehicle_handover_at, rca_notes.

	The controller's `before_save` auto-computes travel_duration, trial_trip
	distance/duration, and total_downtime from these inputs.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if isinstance(updates, str):
		updates = _json.loads(updates)

	ALLOWED = {
		"incident_place",
		"fault_code_1",
		"fault_code_2",
		"fault_code_3",
		"remote_resolution_status",
		"remote_resolution_started_at",
		"travel_started_at",
		"arrived_at_location",
		"fix_type",
		"next_level_engineer",
		"recurrence_risk",
		"occurrence_risk",
		"trial_trip_started_at",
		"trial_trip_start_km",
		"trial_trip_ended_at",
		"trial_trip_end_km",
		"vehicle_handover_at",
		"rca_notes",
		# Phase B additions
		"force_override",
		"force_override_reason",
		"process_override",
		"process_override_steps",
	}

	doc = frappe.get_doc("Job Card", job_card_name)
	if doc.job_card_type != "Breakdown":
		frappe.throw(_("This endpoint only applies to Breakdown job cards."))

	changed = []
	for field, value in updates.items():
		if field in ALLOWED:
			doc.set(field, value)
			changed.append(field)

	if not changed:
		frappe.throw(_("No valid fields to update."))

	doc.save()
	return {
		"success": True,
		"data": {"name": doc.name, "updated_fields": changed},
		"message": _("Breakdown diagnosis saved."),
	}


@frappe.whitelist()
def save_software_components(job_card_name: str, rows: str | list) -> dict:
	"""Replace the Job Card's `software_components` child table (PRD p.14).

	Whitelisted fields per row: component, reason, status, retry_count,
	pre_version, pre_version_photo, post_version, post_version_photo,
	calibration_values, calibration_photo, failure_notes.
	"""
	frappe.only_for(["Service Engineer", "Technician", "Depot Manager"])
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if isinstance(rows, str):
		rows = _json.loads(rows)

	ALLOWED = {
		"component",
		"reason",
		"status",
		"retry_count",
		"pre_version",
		"pre_version_photo",
		"post_version",
		"post_version_photo",
		"calibration_values",
		"calibration_photo",
		"failure_notes",
	}

	doc = frappe.get_doc("Job Card", job_card_name)
	if doc.job_card_type != "Software Update":
		frappe.throw(_("This endpoint only applies to Software Update job cards."))

	doc.set("software_components", [])
	for row in rows or []:
		clean = {k: v for k, v in row.items() if k in ALLOWED}
		doc.append("software_components", clean)
	doc.save()

	return {
		"success": True,
		"data": {
			"name": doc.name,
			"count": len(doc.get("software_components", [])),
		},
		"message": _("Software components saved."),
	}


@frappe.whitelist()
def create_inventory_request(
	job_card_name: str,
	part: str,
	quantity: float,
	urgency_level: str = "Medium",
	notes: str | None = None,
) -> dict:
	"""Raise an Inventory Request against a Job Card (PRD p.3).

	Whitelisted convenience wrapper so the Vue UI doesn't need to hit
	`frappe.client.insert` directly. The logged-in user becomes the requester.
	"""
	frappe.only_for(["Service Engineer", "Technician", "Depot Manager"])
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)

	if not part:
		frappe.throw(_("Part is required."))
	qty = float(quantity or 0)
	if qty <= 0:
		frappe.throw(_("Quantity must be positive."))

	inv = frappe.get_doc(
		{
			"doctype": "Inventory Request",
			"job_card_ref": job_card_name,
			"part": part,
			"quantity": qty,
			"urgency_level": urgency_level or "Medium",
			"notes": notes or "",
		}
	)
	inv.insert(ignore_permissions=True)

	return {
		"success": True,
		"data": {"name": inv.name, "status": inv.status},
		"message": _("Inventory request raised."),
	}


@frappe.whitelist()
def advance_inventory_status(
	inventory_request_name: str,
	next_status: str,
) -> dict:
	"""Move an Inventory Request to the next status (PRD Inventory Flow).

	Transitions (role-gated by the Inventory Request controller):
	    Requested       → Parts Allocated    (Depot Manager / Central Ops)
	    Parts Allocated → Parts Issued       (Depot Manager / Central Ops)
	    Parts Issued    → Received           (Technician / Service Engineer)
	"""
	doc = frappe.get_doc("Inventory Request", inventory_request_name)
	jc_ref = doc.job_card_ref
	frappe.has_permission("Job Card", doc=jc_ref, ptype="write", throw=True)

	if next_status not in {"Parts Allocated", "Parts Issued", "Received"}:
		frappe.throw(_("Invalid target status: {0}").format(next_status))

	doc.status = next_status
	doc.save()

	return {
		"success": True,
		"data": {"name": doc.name, "status": doc.status},
		"message": _("Inventory request moved to {0}.").format(next_status),
	}


@frappe.whitelist()
def reopen_job_card(
	job_card_name: str,
	reason: str,
) -> dict:
	"""Reopen a Closed Job Card (PRD p.3 — Customer & Central Ops only).

	Per the Job Card VALID_TRANSITIONS table, Closed → Reopened is permitted
	for these roles. The controller validates the actor's role, so this API
	just routes the transition and stamps the reason into the audit log.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)
	if not (reason or "").strip():
		frappe.throw(_("A reason is required to reopen a Job Card."))

	doc = frappe.get_doc("Job Card", job_card_name)
	if doc.workflow_state != "Closed":
		frappe.throw(_("Only Closed Job Cards can be reopened (current: {0}).").format(doc.workflow_state))

	doc.workflow_state = "Reopened"
	# Store the reason in the closed-edit-reason field so it shows in the audit log.
	doc.closed_edit_reason = f"Reopen: {reason.strip()}"
	doc.save()

	return {
		"success": True,
		"data": {"name": doc.name, "workflow_state": doc.workflow_state},
		"message": _("Job Card reopened."),
	}


@frappe.whitelist()
def submit_customer_feedback(
	job_card_name: str,
	rating: int,
	nps_score: int | None = None,
	comments: str | None = None,
	would_recommend: str | None = None,
) -> dict:
	"""Record post-closure feedback (PRD p.2 "Feedback request post-closure").

	Idempotent per job card: creates on first call, updates existing on later
	calls. Only allowed when the Job Card is Closed (the DocType validator
	enforces this).
	"""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)

	existing = frappe.db.exists("Customer Feedback", {"job_card": job_card_name})
	if existing:
		doc = frappe.get_doc("Customer Feedback", existing)
	else:
		doc = frappe.new_doc("Customer Feedback")
		doc.job_card = job_card_name

	doc.rating = int(rating)
	if nps_score is not None and nps_score != "":
		doc.nps_score = int(nps_score)
	if comments is not None:
		doc.comments = comments
	if would_recommend is not None:
		doc.would_recommend = would_recommend

	doc.save(ignore_permissions=True)

	return {
		"success": True,
		"data": {"name": doc.name, "rating": doc.rating, "nps_score": doc.nps_score},
		"message": _("Thanks for your feedback!"),
	}


@frappe.whitelist()
def get_customer_feedback(job_card_name: str) -> dict:
	"""Return any feedback stored for a given Job Card, or `data: None`."""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)

	name = frappe.db.get_value("Customer Feedback", {"job_card": job_card_name})
	if not name:
		return {"success": True, "data": None}

	doc = frappe.get_doc("Customer Feedback", name)
	return {
		"success": True,
		"data": {
			"name": doc.name,
			"rating": doc.rating,
			"nps_score": doc.nps_score,
			"comments": doc.comments,
			"would_recommend": doc.would_recommend,
			"submitted_at": str(doc.submitted_at) if doc.submitted_at else None,
			"submitted_by": doc.submitted_by,
		},
	}


@frappe.whitelist(allow_guest=True)
def get_approval_link_payload(job_card_name: str, token: str) -> dict:
	"""Token-authenticated read of a JC for the customer approval link.

	Exposed to guests so a customer can review the estimate via the external
	link sent by notification. No-op (404-style) if the token doesn't match
	the JC's current approval token. Only a curated subset of fields is
	returned — cost, parts list, and vehicle identity.
	"""
	if not (job_card_name and token):
		frappe.throw(_("Job card and token are required."), frappe.PermissionError)

	stored_token, state = frappe.db.get_value(
		"Job Card",
		job_card_name,
		["customer_approval_token", "workflow_state"],
	) or (None, None)

	if not stored_token or stored_token != token:
		frappe.throw(_("Invalid or expired approval link."), frappe.PermissionError)
	if state != "Awaiting Customer Approval":
		frappe.throw(_("This Job Card is no longer awaiting approval."))

	doc = frappe.get_doc("Job Card", job_card_name)
	return {
		"success": True,
		"data": {
			"name": doc.name,
			"vehicle_number": doc.vehicle_number,
			"vehicle_make_model": doc.vehicle_make_model,
			"customer_name": doc.customer_name,
			"depot": doc.depot,
			"complaint_description": doc.complaint_description,
			"estimated_cost": float(doc.estimated_cost or 0),
			"requires_customer_approval": int(doc.requires_customer_approval or 0),
			"repair_items": [
				{
					"part_group": item.part_group,
					"description": item.description,
					"activity_type": item.activity_type,
					"qty": float(item.qty or 0),
					"estimated_amount": float(item.estimated_amount or 0),
					"pre_repair_photo": item.pre_repair_photo,
				}
				for item in doc.get("repair_items", [])
				if item.activity_type in ("Spare Replacement", "Both")
			],
		},
	}


@frappe.whitelist(allow_guest=True)
def submit_approval_via_link(
	job_card_name: str,
	token: str,
	approved: bool | int,
	rejection_feedback: str | None = None,
	per_part_feedback: str | list | None = None,
) -> dict:
	"""Token-authenticated accept/reject for the external customer link.

	Validates the token, then delegates to the role-gated
	`record_customer_approval_decision` using system permissions. The token is
	invalidated after use so links can't be replayed.
	"""
	stored_token = frappe.db.get_value("Job Card", job_card_name, "customer_approval_token")
	if not stored_token or stored_token != token:
		frappe.throw(_("Invalid or expired approval link."), frappe.PermissionError)

	# Short-circuit: invalidate the token first so it can't be replayed in
	# parallel requests even if the subsequent save fails partway.
	frappe.db.set_value(
		"Job Card",
		job_card_name,
		"customer_approval_token",
		None,
		update_modified=False,
	)

	# Run the approval logic with elevated permissions; the token itself is
	# the authorization. Temporarily swap to Administrator so has_permission
	# passes even for a Guest session.
	original_user = frappe.session.user
	try:
		frappe.set_user("Administrator")
		return record_customer_approval_decision(
			job_card_name=job_card_name,
			approved=approved,
			rejection_feedback=rejection_feedback,
			per_part_feedback=per_part_feedback,
		)
	finally:
		frappe.set_user(original_user)


@frappe.whitelist()
def record_customer_approval_decision(
	job_card_name: str,
	approved: bool | int,
	rejection_feedback: str | None = None,
	per_part_feedback: str | list | None = None,
) -> dict:
	"""Record the customer's accept/reject decision on a pending approval.

	Per PRD p.11 Only Repair step 10:
	  • Customer ticks approve → all repair items marked customer_approved=1
	  • Customer rejects → per-part feedback on the rejected items, plus
	    optional JC-level `customer_rejection_feedback`.

	`per_part_feedback` shape:
	  [{"part_group": "Brakes", "description": "rotor", "feedback": "too expensive"}, …]
	Matching is by (part_group, description) tuple — if duplicates exist the
	first match wins.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)

	approved_bool = bool(int(approved)) if str(approved).isdigit() else bool(approved)

	if isinstance(per_part_feedback, str):
		per_part_feedback = _json.loads(per_part_feedback) if per_part_feedback else []
	per_part_feedback = per_part_feedback or []

	doc = frappe.get_doc("Job Card", job_card_name)
	now = frappe.utils.now_datetime()

	if approved_bool:
		# Accept everything on the card
		for item in doc.repair_items:
			item.customer_approved = 1
			item.customer_decision_at = now
		doc.customer_approval_received_at = now
		doc.customer_rejection_feedback = None
	else:
		# Reject: require at least a JC-level reason OR some per-part feedback
		jc_reason = (rejection_feedback or "").strip()
		if not jc_reason and not per_part_feedback:
			frappe.throw(_("Please provide a rejection reason (either JC-level or per-part)."))

		doc.customer_rejection_feedback = jc_reason or None
		doc.customer_approval_received_at = now

		# Index per-part feedback for quick lookup
		fb_by_key: dict[tuple[str, str], str] = {}
		for fb in per_part_feedback:
			key = (fb.get("part_group") or "", fb.get("description") or "")
			fb_by_key[key] = fb.get("feedback") or ""

		for item in doc.repair_items:
			key = (item.part_group or "", item.description or "")
			feedback = fb_by_key.get(key)
			if feedback:
				item.customer_approved = 0
				item.customer_rejection_feedback = feedback
				item.customer_decision_at = now
				item.item_status = "Customer Rejected"

	doc.save(ignore_permissions=True)

	return {
		"success": True,
		"data": {
			"name": doc.name,
			"approved": approved_bool,
			"workflow_state": doc.workflow_state,
		},
		"message": _("Approval recorded.") if approved_bool else _("Rejection recorded."),
	}


@frappe.whitelist()
def save_groups_impacted(job_card_name: str, part_groups: str | list) -> dict:
	"""Replace the Breakdown Job Card's `groups_impacted` table (PRD p.15 step 4)."""
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if isinstance(part_groups, str):
		part_groups = _json.loads(part_groups)

	doc = frappe.get_doc("Job Card", job_card_name)
	if doc.job_card_type != "Breakdown":
		frappe.throw(_("groups_impacted applies only to Breakdown job cards."))

	doc.set("groups_impacted", [])
	for pg in part_groups or []:
		val = pg.get("part_group") if isinstance(pg, dict) else pg
		if val:
			doc.append("groups_impacted", {"part_group": val})
	doc.save()

	return {
		"success": True,
		"data": {"name": doc.name, "count": len(doc.get("groups_impacted", []))},
		"message": _("Groups Impacted saved."),
	}


@frappe.whitelist()
def save_subsystems(job_card_name: str, subsystems: str | list) -> dict:
	"""Replace the Job Card's `subsystems` Table MultiSelect with the provided
	list of Subsystem names. Used by Only Repair / Software Update / Breakdown.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if isinstance(subsystems, str):
		subsystems = _json.loads(subsystems)

	doc = frappe.get_doc("Job Card", job_card_name)
	doc.set("subsystems", [])
	for sub in subsystems or []:
		# Accept both bare strings and {"subsystem": "..."} shapes.
		val = sub.get("subsystem") if isinstance(sub, dict) else sub
		if val:
			doc.append("subsystems", {"subsystem": val})
	doc.save()

	return {
		"success": True,
		"data": {"name": doc.name, "count": len(doc.get("subsystems", []))},
		"message": _("Subsystems saved."),
	}


@frappe.whitelist()
def get_available_actions(job_card_name: str) -> dict:
	"""Return the workflow actions available for the current user on this job card.

	Args:
	    job_card_name: The Job Card document name.

	Returns:
	    dict with list of available action strings.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)

	from frappe.model.workflow import get_transitions

	doc = frappe.get_doc("Job Card", job_card_name)
	transitions = get_transitions(doc)

	actions = [t.get("action") for t in transitions if t.get("action")]

	return {
		"success": True,
		"data": {
			"workflow_state": doc.workflow_state,
			"actions": actions,
		},
		"message": "",
	}


def _inspection_rows_from_results(results: dict) -> list[dict]:
	"""Convert the wizard's inspection-results JSON into Job Card Inspection Item rows.

	Maps each check to a structured row. Three-tier values are normalised to a
	`component_status` the score engine understands (accepts both the
	Good/Recommended/Immediate scheme and a legacy pass/fail scheme); measurement
	values are captured numerically with their range bounds.
	"""
	pass_fail = {"pass": "Good", "fail": "Repair/Replace Immediately"}
	rows: list[dict] = []
	for check_id, check in (results or {}).items():
		if not isinstance(check, dict):
			continue
		input_type = check.get("inputType") or "three_tier"
		value = check.get("value")
		row = {
			"check_id": check_id,
			"label": check.get("label"),
			"category": check.get("category"),
			"input_type": input_type,
			"phase": "Pre",
			"value": None if value is None else str(value),
		}
		if input_type == "three_tier":
			if value in HEALTH_SCORE_MAP:
				row["component_status"] = value
			elif value in pass_fail:
				row["component_status"] = pass_fail[value]
		elif input_type == "measurement":
			try:
				row["measurement_value"] = float(value)
			except (TypeError, ValueError):
				pass
			if check.get("min") is not None:
				row["min_value"] = check.get("min")
			if check.get("max") is not None:
				row["max_value"] = check.get("max")
			if check.get("outOfRange") or check.get("out_of_range"):
				row["out_of_range"] = 1
		rows.append(row)
	return rows


@frappe.whitelist()
def create_job_card_with_inspection(
	vehicle_number: str,
	odometer_reading: int,
	service_type: str,
	complaint_description: str = "",
	technician_notes: str = "",
	inspection_sheet_id: str = "",
	inspection_results: str = "{}",
	job_card_type: str = "PMS + Repair",
	depot: str = "",
) -> dict:
	"""Create a Job Card with structured inspection data (Technician flow).

	Used by the Technician Inspection Wizard / App. Creates the Job Card,
	auto-assigns the current user as technician, and persists the inspection
	checklist as **structured `inspection_items` rows** (not just a comment) so
	the health-score engine reads them: each three-tier row maps Good/
	Recommended/Immediate -> 10/5/0 into the Pre-PMS category scores. A
	human-readable summary comment is still added for audit.

	Args:
	    vehicle_number: Vehicle registration (used to look up the Vehicle doc).
	    odometer_reading: Current odometer in km.
	    service_type: Display service type (overridden by job_card_type derivation).
	    complaint_description: Optional complaint text.
	    technician_notes: Optional technician observations.
	    inspection_sheet_id: "A"/"B"/"C"/"D" — auto-selected by the client.
	    inspection_results: JSON string of
	        {check_id: {value, label, category, inputType, min?, max?}}.
	    job_card_type: Defaults to "PMS + Repair" so scoring runs.
	    depot: Optional; falls back to this vehicle's most recently used depot.

	Returns:
	    dict with the created Job Card name and workflow state.
	"""
	frappe.only_for(["Technician", "Service Engineer", "Depot Manager"])

	# Resolve vehicle
	vehicle_name = frappe.db.get_value("Vehicle", {"registration_number": vehicle_number}, "name")
	if not vehicle_name:
		frappe.throw(_("Vehicle with registration {0} not found.").format(vehicle_number))

	# Resolve customer from vehicle
	customer = frappe.db.get_value("Vehicle", vehicle_name, "customer")

	# Resolve depot: explicit arg, else the vehicle's most recently used depot.
	# Depot is mandatory for Job Card naming, so fail clearly if none is known.
	if not depot:
		prior = frappe.get_all(
			"Job Card",
			filters={"vehicle": vehicle_name},
			fields=["depot"],
			order_by="creation desc",
			limit_page_length=1,
		)
		depot = prior[0]["depot"] if prior else None
	if not depot:
		frappe.throw(_("No depot could be determined for this vehicle. Please pass a depot."))

	odometer_reading = int(odometer_reading)
	if odometer_reading <= 0:
		frappe.throw(_("Odometer reading must be positive."))

	results = _json.loads(inspection_results) if isinstance(inspection_results, str) else inspection_results

	# Assign the creator so the card shows up in THEIR "My Job Cards" list. The list
	# scopes a Technician by assigned_technician and a Service Engineer by
	# assigned_service_engineer, so set whichever role(s) the creator holds — an SE
	# creating from the app must see their own card.
	creator = frappe.session.user
	creator_roles = set(frappe.get_roles(creator))
	job_card_data = {
		"doctype": "Job Card",
		"job_card_type": job_card_type,
		"vehicle": vehicle_name,
		"odometer_reading": odometer_reading,
		"customer": customer,
		"depot": depot,
		"priority": "Medium",
		"complaint_description": complaint_description,
		"se_observations": technician_notes,
		"assigned_technician": creator,
		"inspection_items": _inspection_rows_from_results(results or {}),
	}
	if "Service Engineer" in creator_roles:
		job_card_data["assigned_service_engineer"] = creator

	# Create the Job Card with structured inspection rows so before_save scores them.
	doc = frappe.get_doc(job_card_data)
	doc.insert()

	if results:
		# Build a human-readable summary
		passes = sum(1 for v in results.values() if v.get("value") == "pass")
		fails = sum(1 for v in results.values() if v.get("value") == "fail")
		measurements = sum(1 for v in results.values() if v.get("inputType") == "measurement")
		total = len(results)

		summary_lines = [
			_("**Inspection Sheet {0}** — {1} checks completed").format(inspection_sheet_id, total),
			_("Passed: {0} | Failed: {1} | Measurements: {2}").format(passes, fails, measurements),
		]

		# List failures explicitly
		for check_id, check in results.items():
			if check.get("value") == "fail":
				summary_lines.append(
					_("- FAIL: {0} ({1})").format(check.get("label", check_id), check.get("category", ""))
				)

		# List out-of-range measurements
		for check_id, check in results.items():
			if check.get("inputType") == "measurement" and check.get("value"):
				summary_lines.append(_("- {0}: {1}").format(check.get("label", check_id), check.get("value")))

		if technician_notes:
			summary_lines.append(_("\n**Technician Notes:** {0}").format(technician_notes))

		doc.add_comment("Comment", "\n".join(summary_lines))

		# Also store the raw JSON for programmatic access
		doc.add_comment(
			"Comment",
			_("Inspection raw data (Sheet {0}): ```{1}```").format(
				inspection_sheet_id, _json.dumps(results, indent=2)
			),
		)

	return {
		"success": True,
		"data": {"name": doc.name, "workflow_state": doc.workflow_state},
		"message": _("Job Card {0} created with inspection data.").format(doc.name),
	}
