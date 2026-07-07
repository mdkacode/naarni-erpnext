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


def _user_vehicle_names(user: str) -> list[str]:
	"""Vehicle names at the SE's depots (empty list when they have no depot)."""
	depots = _user_depots(user)
	if not depots:
		return []
	return [
		v["name"]
		for v in frappe.get_all(
			"Vehicle", filters={"depot": ["in", depots]}, fields=["name"], limit_page_length=0
		)
	]


@frappe.whitelist()
def get_my_alert_groups(
	search: str = "",
	severity: str = "",
	status: str = "",
	sort: str = "latest",
	limit: int = 50,
	offset: int = 0,
) -> dict:
	"""Modernized Alerts feed: one row per (bus, issue) group (dedup_key).

	Returns the LATEST occurrence per group plus an occurrence count and the
	current open ticket. Latest is computed with a window function keyed on the
	reliable clock COALESCE(triggered_at, occurred_at, creation) so a stale
	status/severity can't leak in (greatest-N-per-group). Depot-scoped.
	"""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)
	vehicles = _user_vehicle_names(user)
	if not vehicles:
		return {"success": True, "data": []}

	veh_ph = ", ".join(["%s"] * len(vehicles))
	params: list = list(vehicles)
	extra = ""
	if severity:
		extra += " AND ae.severity = %s"
		params.append(severity)
	if status:
		extra += " AND ae.status = %s"
		params.append(status)
	if search:
		extra += " AND (ae.registration_number LIKE %s OR ae.title LIKE %s OR ae.message LIKE %s)"
		like = f"%{search.strip()}%"
		params += [like, like, like]

	order = "occurrence_count DESC, latest_time DESC" if sort == "frequent" else "latest_time DESC"

	sql = f"""
		SELECT dedup_key, vehicle, registration_number, alert_type,
		       alert_name, latest_severity, latest_status, latest_time,
		       latest_alert_event, occurrence_count
		FROM (
			SELECT
				ae.dedup_key, ae.vehicle, ae.registration_number, ae.alert_type,
				ae.title AS alert_name, ae.severity AS latest_severity, ae.status AS latest_status,
				COALESCE(ae.triggered_at, ae.occurred_at, ae.creation) AS latest_time,
				ae.name AS latest_alert_event,
				ROW_NUMBER() OVER (
					PARTITION BY ae.dedup_key
					ORDER BY COALESCE(ae.triggered_at, ae.occurred_at, ae.creation) DESC, ae.name DESC
				) AS rn,
				COUNT(*) OVER (PARTITION BY ae.dedup_key) AS occurrence_count
			FROM `tabAlert Event` ae
			WHERE ae.vehicle IN ({veh_ph})
			  AND ae.dedup_key IS NOT NULL AND ae.dedup_key != ''
			  {extra}
		) g
		WHERE g.rn = 1
		ORDER BY {order}
		LIMIT %s OFFSET %s
	"""
	params += [min(int(limit), 100), int(offset or 0)]
	rows = frappe.db.sql(sql, params, as_dict=True)

	# Attach the current open (non-resolved) ticket per group.
	keys = [r["dedup_key"] for r in rows]
	open_by_key: dict = {}
	if keys:
		for t in frappe.get_all(
			"Service Ticket",
			filters={"dedup_key": ["in", keys], "status": ["!=", "Resolved"]},
			fields=["name", "dedup_key", "status"],
		):
			open_by_key.setdefault(t["dedup_key"], t)
	for r in rows:
		ot = open_by_key.get(r["dedup_key"])
		r["open_ticket"] = ot["name"] if ot else None
		r["open_ticket_status"] = ot["status"] if ot else None
		r["latest_time"] = str(r["latest_time"]) if r.get("latest_time") else None

	return {"success": True, "data": rows}


@frappe.whitelist()
def get_alert_group(
	dedup_key: str | None = None,
	vehicle: str | None = None,
	alert_type: str | None = None,
	alert_event: str | None = None,
	occurrence_limit: int = 100,
) -> dict:
	"""Detail for one (bus, issue) group: latest reading + every occurrence +
	ticket episodes (who resolved, with what response). Depot-scoped.

	Accepts a `dedup_key` directly, or resolves one from `alert_event` (used by
	notification deep links) or from `vehicle` (+ optional `alert_type`).
	"""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)

	key = dedup_key
	if not key and alert_event:
		key = frappe.db.get_value("Alert Event", alert_event, "dedup_key")
	if not key and vehicle:
		filt = {"vehicle": vehicle}
		if alert_type:
			filt["alert_type"] = alert_type
		latest = frappe.get_all(
			"Alert Event",
			filters=filt,
			fields=["dedup_key"],
			order_by="triggered_at desc",
			limit_page_length=1,
		)
		key = latest[0]["dedup_key"] if latest else None
	if not key:
		frappe.throw(_("Alert group not found."))

	events = frappe.get_all(
		"Alert Event",
		filters={"dedup_key": key},
		fields=[
			"name",
			"title",
			"alert_type",
			"severity",
			"status",
			"registration_number",
			"vehicle",
			"parameter",
			"value",
			"value_text",
			"value_meaning",
			"unit",
			"threshold",
			"match_value",
			"message",
			"latitude",
			"longitude",
			"maps_link",
			"occurred_at",
			"triggered_at",
			"creation",
		],
		order_by="triggered_at desc",
		limit_page_length=min(int(occurrence_limit), 200),
	)
	if not events:
		frappe.throw(_("Alert group not found."))

	# Depot authorization — the group's bus must be at one of the caller's depots.
	group_vehicle = events[0].get("vehicle")
	depots = _user_depots(user)
	if depots and group_vehicle and "System Manager" not in frappe.get_roles(user):
		veh_depot = frappe.db.get_value("Vehicle", group_vehicle, "depot")
		if veh_depot and veh_depot not in depots:
			frappe.throw(_("Not permitted."), frappe.PermissionError)

	latest = events[0]
	latest_reading = {
		k: latest.get(k)
		for k in (
			"parameter",
			"value",
			"value_text",
			"value_meaning",
			"unit",
			"threshold",
			"match_value",
			"message",
			"latitude",
			"longitude",
			"maps_link",
			"occurred_at",
			"triggered_at",
		)
	}
	occurrences = [
		{
			"name": e["name"],
			"status": e.get("status"),
			"severity": e.get("severity"),
			"time": str(e.get("triggered_at") or e.get("occurred_at") or e.get("creation") or ""),
			"message": e.get("message"),
			"value": e.get("value"),
			"value_text": e.get("value_text"),
			"unit": e.get("unit"),
		}
		for e in events
	]

	# Ticket episodes for this group (the truthful "who resolved + what response").
	episodes = frappe.get_all(
		"Service Ticket",
		filters={"dedup_key": key},
		fields=[
			"name",
			"status",
			"severity",
			"creation",
			"acknowledged_at",
			"resolved_at",
			"resolved_by",
			"resolved_by_name",
			"resolution_response",
			"resolution_reason",
		],
		order_by="creation desc",
	)
	resp_names = [e["resolution_response"] for e in episodes if e.get("resolution_response")]
	resp_text: dict = {}
	if resp_names:
		resp_text = {
			r["name"]: r["response_text"]
			for r in frappe.get_all(
				"Alert Response", filters={"name": ["in", resp_names]}, fields=["name", "response_text"]
			)
		}
	open_ticket = None
	for e in episodes:
		e["ticket"] = e["name"]
		e["resolution_response_text"] = resp_text.get(e.get("resolution_response"))
		e["creation"] = str(e.get("creation") or "")
		if e.get("status") != "Resolved" and not open_ticket:
			open_ticket = e["name"]

	return {
		"success": True,
		"data": {
			"dedup_key": key,
			"vehicle": group_vehicle,
			"registration_number": latest.get("registration_number"),
			"alert_type": latest.get("alert_type"),
			"alert_name": latest.get("title"),
			"latest_severity": latest.get("severity"),
			"latest_status": latest.get("status"),
			"occurrence_count": len(occurrences),
			"latest_reading": latest_reading,
			"occurrences": occurrences,
			"episodes": episodes,
			"open_ticket": open_ticket,
		},
	}


@frappe.whitelist()
def get_alert_responses(alert_type: str = "", limit: int = 12) -> dict:
	"""Ranked canned resolutions for the quick-response chips.

	Alert-type-specific responses first, then generic (no alert_type), each block
	by usage_count desc. Responses tied to a DIFFERENT alert type are excluded.
	"""
	if frappe.session.user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)
	rows = frappe.get_all(
		"Alert Response",
		filters={"is_active": 1},
		fields=["name", "response_text", "alert_type", "usage_count"],
		order_by="usage_count desc, modified desc",
		limit_page_length=0,
	)
	specific = [r for r in rows if alert_type and r.get("alert_type") == alert_type]
	generic = [r for r in rows if not r.get("alert_type")]
	out = (specific + generic)[: int(limit)]
	return {
		"success": True,
		"data": [
			{"name": r["name"], "response_text": r["response_text"], "usage_count": r["usage_count"]}
			for r in out
		],
	}


@frappe.whitelist()
def acknowledge_ticket(name: str) -> dict:
	frappe.has_permission("Service Ticket", "write", doc=name, throw=True)
	doc = frappe.get_doc("Service Ticket", name)
	if doc.status == "Open":
		doc.status = "Acknowledged"
		doc.acknowledged_at = now_datetime()
		doc.save(ignore_permissions=True)
	return {"success": True, "data": {"status": doc.status}}


def _normalize_response(text: str) -> str:
	"""Case/space-insensitive key for de-duping canned responses."""
	return " ".join((text or "").strip().split()).lower()


def _promote_response(reason: str, alert_type: str | None) -> str | None:
	"""Find-or-create an Alert Response for a free-text resolution (source=Learned).

	De-dupes on normalized text against ALL active responses (so a typed answer
	matching a seeded one reuses+reinforces it, and typos like 'brakes ok' /
	'Brakes OK' collapse to one row).
	"""
	text = (reason or "").strip()
	if not text:
		return None
	norm = _normalize_response(text)
	for r in frappe.get_all(
		"Alert Response", filters={"is_active": 1}, fields=["name", "response_text"], limit_page_length=0
	):
		if _normalize_response(r["response_text"]) == norm:
			return r["name"]
	doc = frappe.get_doc(
		{
			"doctype": "Alert Response",
			"response_text": text[:140],
			"alert_type": alert_type,
			"is_active": 1,
			"usage_count": 0,
			"source": "Learned",
		}
	).insert(ignore_permissions=True)
	return doc.name


@frappe.whitelist()
def resolve_ticket(name: str, response: str | None = None, reason: str | None = None) -> dict:
	"""Resolve a ticket via a tapped quick response and/or a free-text reason.

	Captures who resolved it, links the chosen Alert Response, and bumps that
	response's usage_count atomically — but only on the real Open/Acked→Resolved
	transition (idempotent re-resolves don't double-count). A free-text reason
	with no matching response is promoted into the catalog (auto-learning).
	Back-compatible: legacy callers passing only `reason` still work.
	"""
	frappe.has_permission("Service Ticket", "write", doc=name, throw=True)
	doc = frappe.get_doc("Service Ticket", name)
	already_resolved = doc.status == "Resolved"

	doc.status = "Resolved"
	if not doc.resolved_at:
		doc.resolved_at = now_datetime()
	if not doc.resolved_by:
		doc.resolved_by = frappe.session.user
		doc.resolved_by_name = frappe.utils.get_fullname(frappe.session.user)

	# Alert type of the source alert (scopes a promoted response).
	alert_type = (
		frappe.db.get_value("Alert Event", doc.alert_event, "alert_type") if doc.alert_event else None
	)

	resp_name = None
	if response and frappe.db.get_value("Alert Response", response, "is_active"):
		resp_name = response
		doc.resolution_response = response
		doc.resolution_reason = reason or frappe.db.get_value("Alert Response", response, "response_text")
	elif reason and reason.strip():
		resp_name = _promote_response(reason, alert_type)
		doc.resolution_response = resp_name
		doc.resolution_reason = reason

	doc.save(ignore_permissions=True)

	# Bump usage atomically, only on a real transition (never a read-modify-write).
	if resp_name and not already_resolved:
		frappe.db.sql(
			"UPDATE `tabAlert Response` SET usage_count = usage_count + 1 WHERE name = %s", resp_name
		)
	frappe.db.commit()

	return {
		"success": True,
		"data": {
			"status": doc.status,
			"resolved_by_name": doc.resolved_by_name,
			"resolution_response": doc.resolution_response,
			"resolution_response_text": (
				frappe.db.get_value("Alert Response", doc.resolution_response, "response_text")
				if doc.resolution_response
				else None
			),
		},
	}


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
