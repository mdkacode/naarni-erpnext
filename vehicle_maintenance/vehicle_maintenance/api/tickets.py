"""Service Ticket endpoints + the SE-scoped alert feed.

Tickets are auto-raised from Alert Events (see `create_ticket_from_alert`, called
by `ingest_alert_event`) for buses at a Service Engineer's depot, and can be
acknowledged, resolved, or turned into a Job Card. The App's Alerts and Tickets
tabs consume these.
"""

import frappe
from frappe import _
from frappe.utils import now_datetime

# Alert Event severity (warning/critical) → Ticket severity.
SEVERITY_FROM_ALERT = {"critical": "Critical", "warning": "Medium"}


def _user_depots(user: str) -> list[str]:
	"""Depots where `user` is listed as a service engineer."""
	rows = frappe.get_all(
		"Depot Engineer",
		filters={"user": user, "parenttype": "Depot"},
		fields=["parent"],
		limit_page_length=0,
	)
	return [r["parent"] for r in rows]


def _depot_engineers(depot: str) -> list[str]:
	return [
		r["user"]
		for r in frappe.get_all(
			"Depot Engineer",
			filters={"parent": depot, "parenttype": "Depot"},
			fields=["user"],
			limit_page_length=0,
		)
	]


@frappe.whitelist()
def get_my_depots(user: str | None = None) -> dict:
	"""Return the depots a user is assigned to as a Service Engineer.

	Defaults to the current user. A Depot Manager / Central Ops may inspect
	another user's depots by passing ``user``.

	Returns: {success, data: {user, depots: [{name, depot_name, city, state}]}}.
	"""
	target = (user or frappe.session.user).strip()
	if target != frappe.session.user:
		frappe.only_for(["Depot Manager", "Central Ops", "System Manager"])

	names = _user_depots(target)
	depots = (
		frappe.get_all(
			"Depot",
			filters={"name": ["in", names]},
			fields=["name", "depot_name", "city", "state"],
			order_by="depot_name asc",
			limit_page_length=0,
		)
		if names
		else []
	)
	return {"success": True, "data": {"user": target, "depots": depots}}


@frappe.whitelist()
def set_my_depot(depot: str, user: str | None = None) -> dict:
	"""Assign a Service Engineer to ``depot`` (their service depot), replacing any
	prior depot assignment so the Alerts/Tickets feeds re-scope immediately.

	Self-service for the SE/Technician; a Depot Manager / Central Ops may set it
	for another user via ``user``. Idempotent.

	Returns: {success, data: {user, depot}, message}.
	"""
	target = (user or frappe.session.user).strip()
	if target != frappe.session.user:
		frappe.only_for(["Depot Manager", "Central Ops", "System Manager"])
	else:
		frappe.only_for(["Service Engineer", "Technician", "Depot Manager", "Central Ops"])

	if not depot or not frappe.db.exists("Depot", depot):
		frappe.throw(_("Depot not found."))

	# Drop any existing Depot Engineer rows for this user (across depots), then add
	# them to the chosen depot. We operate on the child table directly rather than
	# re-saving the parent Depot: it is O(rows) (no full-fleet doc loads — scalable)
	# AND it won't fail if some other depot carries legacy/invalid header data.
	frappe.db.delete("Depot Engineer", {"user": target, "parenttype": "Depot"})

	if not frappe.db.exists(
		"Depot Engineer",
		{"user": target, "parent": depot, "parenttype": "Depot"},
	):
		next_idx = (
			frappe.db.sql(
				"""SELECT COALESCE(MAX(idx), 0) + 1 FROM `tabDepot Engineer`
				   WHERE parent = %s AND parenttype = 'Depot'""",
				depot,
			)[0][0]
			or 1
		)
		frappe.get_doc(
			{
				"doctype": "Depot Engineer",
				"parent": depot,
				"parenttype": "Depot",
				"parentfield": "service_engineers",
				"user": target,
				"idx": next_idx,
			}
		).insert(ignore_permissions=True)
	frappe.db.commit()

	return {
		"success": True,
		"data": {"user": target, "depot": depot},
		"message": _("Depot updated to {0}.").format(
			frappe.db.get_value("Depot", depot, "depot_name") or depot
		),
	}


def create_ticket_from_alert(alert_event_name: str) -> str | None:
	"""Create (idempotently) a Service Ticket from an Alert Event + notify the
	depot's engineers. Returns the ticket name, or None when the bus has no depot.

	Called from `ingest_alert_event`. Dedupes on the alert's `dedup_key` so a
	repeating alert doesn't spawn duplicate open tickets.
	"""
	ae = frappe.db.get_value(
		"Alert Event",
		alert_event_name,
		[
			"vehicle",
			"registration_number",
			"severity",
			"title",
			"message",
			"latitude",
			"longitude",
			"maps_link",
			"dedup_key",
		],
		as_dict=True,
	)
	if not ae or not ae.get("vehicle"):
		return None

	depot = frappe.db.get_value("Vehicle", ae["vehicle"], "depot")
	if not depot:
		return None

	dedup = ae.get("dedup_key") or alert_event_name
	existing = frappe.db.exists("Service Ticket", {"dedup_key": dedup, "status": ["!=", "Resolved"]})
	if existing:
		return existing

	engineers = _depot_engineers(depot)
	ticket = frappe.get_doc(
		{
			"doctype": "Service Ticket",
			"title": ae.get("title") or _("Alert"),
			"status": "Open",
			"severity": SEVERITY_FROM_ALERT.get((ae.get("severity") or "").lower(), "Medium"),
			"source": "Alert",
			"vehicle": ae["vehicle"],
			"registration_number": ae.get("registration_number"),
			"depot": depot,
			"assigned_to": engineers[0] if engineers else None,
			"message": ae.get("message"),
			"alert_event": alert_event_name,
			"latitude": ae.get("latitude"),
			"longitude": ae.get("longitude"),
			"maps_link": ae.get("maps_link"),
			"dedup_key": dedup,
		}
	).insert(ignore_permissions=True)
	ticket.db_set("deeplink", f"naarni://ticket/{ticket.name}", update_modified=False)

	_populate_vehicle_snapshot(ticket)
	_notify_ticket(ticket, engineers)
	return ticket.name


def _populate_vehicle_snapshot(ticket) -> None:
	"""Stamp the ticket with the bus's live telemetry (IST) at raise time.

	Pulls the full `/v1/analytics/vehicles/{id}` detail so the engineer sees the
	vehicle's complete state on the ticket. Best-effort — never blocks ticket raise.
	"""
	try:
		from vehicle_maintenance.integrations import naarni_vehicles

		live = naarni_vehicles.live_detail(ticket.vehicle)
		if not live:
			return
		mm = " ".join(p for p in (live.get("make"), live.get("model")) if p) or None
		values = {
			"operator": live.get("operator"),
			"make_model": mm,
			"route_name": live.get("route_name"),
			"odometer": live.get("odometer_exact"),
			"activity": live.get("activity"),
			"connectivity_status": live.get("connectivity_status"),
			"battery_soc": live.get("battery_soc"),
			"telemetry_at": live.get("telemetry_at_ist"),
			"vehicle_snapshot": frappe.as_json(live),
		}
		# Fall back to live GPS if the alert carried no coordinates.
		if live.get("latitude") is not None and not ticket.latitude:
			values["latitude"] = live.get("latitude")
			values["longitude"] = live.get("longitude")
			values["maps_link"] = live.get("maps_link")
		frappe.db.set_value("Service Ticket", ticket.name, values, update_modified=False)
	except Exception:
		frappe.log_error(title="Ticket vehicle snapshot failed")


def _notify_ticket(ticket, engineers: list[str]) -> None:
	"""Instant realtime + durable in-app/push to the depot's engineers."""
	# Lead with the VEHICLE NUMBER so the engineer knows which bus at a glance.
	reg = ticket.registration_number or ticket.vehicle or _("Vehicle")
	subject = _("🚨 {0} — {1}").format(reg, ticket.title or _("Alert"))
	body = ticket.message or _("Tap to see the alert details.")
	# Tapping the notification opens the detailed ALERT page (which links through to
	# this ticket); fall back to the ticket route when there's no source alert.
	deeplink = f"naarni://alert/{ticket.alert_event}" if ticket.alert_event else ticket.deeplink
	for user in engineers:
		try:
			frappe.publish_realtime(
				"vm_notification",
				message={
					"subject": subject,
					"body": body,
					"priority": ticket.severity,
					"type": "alert",
					"id": ticket.name,
					"registration_number": reg,
					"deeplink": deeplink,
				},
				user=user,
			)
		except Exception:
			frappe.log_error(title="Ticket realtime failed", message=frappe.get_traceback())
	try:
		from vehicle_maintenance.fleet_service import notifications as notif

		push_on = frappe.get_conf().get("notifications_push_enabled")
		for user in engineers:
			notif._dispatch_in_app(user, subject, body, ticket.name)
			if push_on:
				notif._dispatch_push(user, subject, body, ticket.name, ticket.severity, deeplink=deeplink)
	except Exception:
		frappe.log_error(title="Ticket notify failed", message=frappe.get_traceback())


@frappe.whitelist()
def get_my_tickets(status: str = "", limit: int = 50, offset: int = 0) -> dict:
	"""Tickets for the current SE — assigned to them OR at one of their depots."""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)
	depots = _user_depots(user)
	or_filters = [["assigned_to", "=", user]]
	if depots:
		or_filters.append(["depot", "in", depots])
	filters = {"status": status} if status else {}
	rows = frappe.get_all(
		"Service Ticket",
		filters=filters,
		or_filters=or_filters,
		fields=[
			"name",
			"title",
			"status",
			"severity",
			"registration_number",
			"depot",
			"vehicle",
			"job_card",
			"creation",
			"deeplink",
		],
		order_by="creation desc",
		limit_page_length=min(int(limit), 100),
		limit_start=int(offset or 0),
	)
	return {"success": True, "data": rows}


@frappe.whitelist()
def get_ticket(name: str) -> dict:
	frappe.has_permission("Service Ticket", doc=name, throw=True)
	return {"success": True, "data": frappe.get_doc("Service Ticket", name).as_dict()}


@frappe.whitelist()
def get_alert_event(name: str) -> dict:
	"""Full Alert Event detail + the linked Service Ticket name.

	Powers the app's detailed Alert page; the `ticket` field lets it link through
	to the ticket screen. Login-gated (the feed is already depot-scoped, and the
	push that carries this id was sent only to the depot's engineers).
	"""
	if frappe.session.user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)
	data = frappe.get_doc("Alert Event", name).as_dict()
	data["ticket"] = frappe.db.get_value("Service Ticket", {"alert_event": name}, "name")
	return {"success": True, "data": data}


@frappe.whitelist()
def get_my_alert_events(severity: str = "", status: str = "", limit: int = 50, offset: int = 0) -> dict:
	"""Alert Events for buses at the current SE's depots (the Alerts tab feed)."""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)
	depots = _user_depots(user)
	if not depots:
		return {"success": True, "data": []}
	vehicles = [
		v["name"]
		for v in frappe.get_all(
			"Vehicle", filters={"depot": ["in", depots]}, fields=["name"], limit_page_length=0
		)
	]
	if not vehicles:
		return {"success": True, "data": []}
	filters = [["vehicle", "in", vehicles]]
	if severity:
		filters.append(["severity", "=", severity])
	if status:
		filters.append(["status", "=", status])
	rows = frappe.get_all(
		"Alert Event",
		filters=filters,
		fields=[
			"name",
			"title",
			"severity",
			"status",
			"registration_number",
			"vehicle",
			"parameter",
			"value",
			"unit",
			"threshold",
			"message",
			"latitude",
			"longitude",
			"maps_link",
			"occurred_at",
			"job_card",
		],
		order_by="occurred_at desc",
		limit_page_length=min(int(limit), 100),
		limit_start=int(offset or 0),
	)
	return {"success": True, "data": rows}


@frappe.whitelist()
def acknowledge_ticket(name: str) -> dict:
	frappe.has_permission("Service Ticket", "write", doc=name, throw=True)
	doc = frappe.get_doc("Service Ticket", name)
	if doc.status == "Open":
		doc.status = "Acknowledged"
		doc.acknowledged_at = now_datetime()
		doc.save(ignore_permissions=True)
	return {"success": True, "data": {"status": doc.status}}


@frappe.whitelist()
def resolve_ticket(name: str, reason: str = "") -> dict:
	frappe.has_permission("Service Ticket", "write", doc=name, throw=True)
	doc = frappe.get_doc("Service Ticket", name)
	doc.status = "Resolved"
	doc.resolved_at = now_datetime()
	doc.resolution_reason = reason
	doc.save(ignore_permissions=True)
	return {"success": True, "data": {"status": doc.status}}


@frappe.whitelist()
def acknowledge_alert_event(name: str) -> dict:
	frappe.has_permission("Alert Event", "write", doc=name, throw=True)
	frappe.db.set_value("Alert Event", name, "status", "Acknowledged")
	return {"success": True}


@frappe.whitelist()
def create_job_card_from_ticket(name: str, job_card_type: str = "Breakdown") -> dict:
	"""Create a Job Card pre-filled from a ticket and link it back."""
	frappe.has_permission("Service Ticket", "write", doc=name, throw=True)
	frappe.has_permission("Job Card", "create", throw=True)
	t = frappe.get_doc("Service Ticket", name)
	if t.job_card:
		return {"success": True, "data": {"job_card": t.job_card}, "message": _("Already linked.")}
	if not t.vehicle:
		frappe.throw(_("Ticket has no vehicle."))

	customer = frappe.db.get_value("Vehicle", t.vehicle, "customer")
	last = frappe.get_all(
		"Job Card",
		filters={"vehicle": t.vehicle},
		fields=["odometer_reading"],
		order_by="creation desc",
		limit_page_length=1,
	)
	odometer = (last[0]["odometer_reading"] if last else 0) or 1

	jc = frappe.get_doc(
		{
			"doctype": "Job Card",
			"job_card_type": job_card_type,
			"vehicle": t.vehicle,
			"customer": customer,
			"depot": t.depot,
			"odometer_reading": odometer,
			"priority": "Urgent" if (t.severity or "") == "Critical" else "Medium",
			"complaint_description": t.message or t.title or _("Raised from ticket {0}").format(t.name),
		}
	).insert(ignore_permissions=True)
	t.db_set("job_card", jc.name, update_modified=False)
	return {"success": True, "data": {"job_card": jc.name}}
