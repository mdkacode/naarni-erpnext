"""Whitelisted API for duty rosters and check-in / check-out.

Two audiences share this module:

* **The engineer's phone** — `get_my_duty` is the one call the duty card makes.
  It returns the plan, the state, and the single action to offer next, so the
  app never has to work out whether the button should say Check In or Check Out.
* **The Depot Manager** — roster authoring and the live attendance board.

Everything the app posts is treated as a claim, not a fact: the server stamps
its own time, resolves the depot itself, and evaluates the geofence. A phone can
report where it thinks it is; it cannot decide whether that counts.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.utils import add_days, cint, flt, get_datetime, getdate, now_datetime

from vehicle_maintenance.fleet_service import roster as R

# Roles that may author a roster or see a whole depot's attendance. "Depot Admin"
# in the brief is the existing Depot Manager role — the depot-owning role this
# app already uses everywhere else.
ADMIN_ROLES = ("Depot Manager", "Central Ops", "N. Maintenance Head", "System Manager")


def _ok(data=None, message=None) -> dict:
	return {"success": True, "data": data if data is not None else {}, "message": message}


def _assert_admin() -> None:
	frappe.only_for(ADMIN_ROLES)


def _assert_depot_access(depot: str) -> None:
	"""A Depot Manager sees their own depots; Central Ops and above see all."""
	roles = set(frappe.get_roles(frappe.session.user))
	if roles & {"Central Ops", "N. Maintenance Head", "System Manager"}:
		return
	if "Depot Manager" not in roles:
		frappe.throw(_("You do not have access to this depot."), frappe.PermissionError)
	if depot and depot not in R.user_depots(frappe.session.user):
		frappe.throw(
			_("{0} is not one of your depots.").format(
				frappe.db.get_value("Depot", depot, "depot_name") or depot
			),
			frappe.PermissionError,
		)


def _shift_brief(shift: str | None) -> dict | None:
	if not shift:
		return None
	row = frappe.db.get_value(
		"Duty Shift", shift, ["shift_name", "start_time", "end_time", "color"], as_dict=True
	)
	if not row:
		return None
	return {
		"name": shift,
		"label": row.shift_name,
		"start_time": str(row.start_time) if row.start_time else None,
		"end_time": str(row.end_time) if row.end_time else None,
		"color": row.color or "Blue",
	}


# --------------------------------------------------------------- engineer: duty


@frappe.whitelist()
def get_my_duty(on_date: str | None = None) -> dict:
	"""Everything the app's duty card needs, in one call.

	Params: `on_date` (YYYY-MM-DD, defaults to today).

	Returns `{success, data: {date, can_punch, depot, depot_name, shift, planned,
	is_week_off, remarks, attendance, punches, next_action, elapsed_minutes,
	geofence}}`.

	`next_action` is the authority for the button: `check_in`, `check_out`,
	`done`, or `none`. Deriving it here means a policy change (say, allowing a
	second check-in after a break) ships without an app release.
	"""
	user = frappe.session.user
	day = getdate(on_date) if on_date else getdate()

	if not R.can_punch(user):
		return _ok(
			{
				"date": str(day),
				"can_punch": False,
				"next_action": "none",
				"reason": _("Check-in is not enabled for your role."),
			}
		)

	planned = R.get_planned_duty(user, day) or {}
	depot = planned.get("depot") or R.primary_depot(user)
	cfg = R.settings()

	attendance_name = frappe.db.get_value("Duty Attendance", {"dedup_key": f"{user}|{day}"}, "name")
	attendance = frappe.get_doc("Duty Attendance", attendance_name) if attendance_name else None
	punches = R.punches_for(attendance_name) if attendance_name else []
	# NB: never bind the throwaway to `_` here — it shadows the imported
	# translation function for the rest of the scope.
	_hours, is_open = R.paired_hours(punches)

	elapsed = 0
	if is_open:
		last_in = next((p for p in reversed(punches) if p["punch_type"] == R.PUNCH_IN), None)
		if last_in:
			elapsed = int((now_datetime() - get_datetime(last_in["punch_time"])).total_seconds() // 60)

	next_action = _next_action(planned, punches, is_open, day, cfg)

	return _ok(
		{
			"date": str(day),
			"can_punch": True,
			"depot": depot,
			"depot_name": frappe.db.get_value("Depot", depot, "depot_name") if depot else None,
			"shift": _shift_brief(planned.get("shift")),
			"planned_start": str(planned["planned_start"]) if planned.get("planned_start") else None,
			"planned_end": str(planned["planned_end"]) if planned.get("planned_end") else None,
			"is_rostered": bool(planned),
			"is_week_off": bool(planned.get("is_week_off")),
			"remarks": planned.get("remarks"),
			"is_on_duty": is_open,
			"elapsed_minutes": elapsed,
			"next_action": next_action,
			"attendance": _attendance_brief(attendance) if attendance else None,
			"punches": [_punch_brief(p) for p in punches],
			"require_photo": cint(cfg.require_photo),
			"require_location": cint(cfg.require_location),
			"geofence_mode": cfg.geofence_mode or "Off",
		}
	)


def _next_action(planned: dict, punches: list, is_open: bool, day, cfg) -> str:
	if is_open:
		return "check_out"
	if planned.get("is_week_off") and not punches:
		# A week off does not forbid working — an emergency callout is normal —
		# so the action stays available; the card just says "Week off" above it.
		return "check_in"
	if not planned and not cint(cfg.allow_punch_without_roster):
		return "none"
	if getdate(day) != getdate():
		# Past or future day: the app shows history, not a button.
		return "done" if punches else "none"
	return "check_in"


def _attendance_brief(doc) -> dict:
	return {
		"name": doc.name,
		"status": doc.status,
		"first_check_in": str(doc.first_check_in) if doc.first_check_in else None,
		"last_check_out": str(doc.last_check_out) if doc.last_check_out else None,
		"worked_hours": flt(doc.worked_hours, 2),
		"is_late": cint(doc.is_late),
		"late_by_minutes": cint(doc.late_by_minutes),
		"early_exit_minutes": cint(doc.early_exit_minutes),
		"outside_geofence": cint(doc.outside_geofence),
		"punch_count": cint(doc.punch_count),
	}


def _punch_brief(p: dict) -> dict:
	return {
		"name": p["name"],
		"punch_type": p["punch_type"],
		"punch_time": str(p["punch_time"]),
		"latitude": p["latitude"],
		"longitude": p["longitude"],
		"maps_link": p["maps_link"],
		"location_available": cint(p["location_available"]),
		"distance_from_depot_m": p["distance_from_depot_m"],
		"outside_geofence": cint(p["outside_geofence"]),
		"source": p["source"],
		"note": p["note"],
		"photo": p["photo"],
	}


@frappe.whitelist()
def punch(
	punch_type: str,
	latitude: float | None = None,
	longitude: float | None = None,
	accuracy_m: float | None = None,
	device_uuid: str | None = None,
	client_uuid: str | None = None,
	note: str | None = None,
	photo: str | None = None,
) -> dict:
	"""Record a check-in or check-out for the logged-in user.

	Params: `punch_type` ("In"/"Out"), optional coordinates and accuracy, an
	optional `client_uuid` for idempotent retries, an optional note and a photo
	file URL.

	Returns `{success, data: {duty, punch, warnings, duplicate}, message}`.
	`warnings` carries the advisory findings — no fix, outside the geofence —
	which the app shows *after* a successful punch rather than as a blocker.
	"""
	punch_type = (punch_type or "").strip().title()
	if punch_type not in (R.PUNCH_IN, R.PUNCH_OUT):
		frappe.throw(_("punch_type must be In or Out."))

	R.assert_can_punch()

	user = frappe.session.user

	# Resolve an idempotent retry BEFORE the state machine sees it. A phone that
	# timed out mid-punch retries with the same client_uuid; if the guard below
	# ran first it would answer "You are already checked in" — turning the exact
	# network flakiness this key exists for into a hard error on the operator's
	# screen. Order matters more than it looks here.
	if client_uuid:
		prior = frappe.db.get_value(
			"Duty Punch",
			{"client_uuid": client_uuid, "user": user},
			["name", "punch_type", "punch_time"],
			as_dict=True,
		)
		if prior:
			if prior.punch_type != punch_type:
				frappe.throw(_("That request was already recorded as a {0} punch.").format(prior.punch_type))
			return _ok(
				{
					"duty": get_my_duty()["data"],
					"punch": _punch_brief(frappe.get_doc("Duty Punch", prior.name).as_dict()),
					"warnings": [],
					"duplicate": True,
				},
				_("Already recorded at {0}.").format(frappe.utils.format_datetime(prior.punch_time, "HH:mm")),
			)

	is_open = bool(R.open_check_in(user))
	# The server owns the state machine. Without this, a double tap or a stale
	# screen produces In,In or Out,Out and the day's hours become fiction.
	if punch_type == R.PUNCH_IN and is_open:
		frappe.throw(_("You are already checked in. Check out first."))
	if punch_type == R.PUNCH_OUT and not is_open:
		frappe.throw(_("You are not checked in right now."))

	result = R.record_punch(
		punch_type,
		user=user,
		latitude=flt(latitude) if latitude not in (None, "") else None,
		longitude=flt(longitude) if longitude not in (None, "") else None,
		accuracy_m=flt(accuracy_m) if accuracy_m not in (None, "") else None,
		device_uuid=device_uuid,
		client_uuid=client_uuid,
		note=note,
		photo=photo,
		source="App",
	)
	frappe.db.commit()

	message = (_("Checked in at {0}.") if punch_type == R.PUNCH_IN else _("Checked out at {0}.")).format(
		frappe.utils.format_datetime(result["punch"]["punch_time"], "HH:mm")
	)

	return _ok(
		{
			"duty": get_my_duty()["data"],
			"punch": _punch_brief(result["punch"]),
			"warnings": result["warnings"],
			"duplicate": result["duplicate"],
		},
		message,
	)


@frappe.whitelist()
def check_in(
	latitude: float | None = None,
	longitude: float | None = None,
	accuracy_m: float | None = None,
	device_uuid: str | None = None,
	client_uuid: str | None = None,
	note: str | None = None,
	photo: str | None = None,
) -> dict:
	"""Check in. Same contract as `punch` with punch_type fixed to In."""
	return punch(
		punch_type=R.PUNCH_IN,
		latitude=latitude,
		longitude=longitude,
		accuracy_m=accuracy_m,
		device_uuid=device_uuid,
		client_uuid=client_uuid,
		note=note,
		photo=photo,
	)


@frappe.whitelist()
def check_out(
	latitude: float | None = None,
	longitude: float | None = None,
	accuracy_m: float | None = None,
	device_uuid: str | None = None,
	client_uuid: str | None = None,
	note: str | None = None,
	photo: str | None = None,
) -> dict:
	"""Check out. Same contract as `punch` with punch_type fixed to Out."""
	return punch(
		punch_type=R.PUNCH_OUT,
		latitude=latitude,
		longitude=longitude,
		accuracy_m=accuracy_m,
		device_uuid=device_uuid,
		client_uuid=client_uuid,
		note=note,
		photo=photo,
	)


@frappe.whitelist()
def get_my_roster(from_date: str | None = None, to_date: str | None = None) -> dict:
	"""The logged-in engineer's own published duty days.

	Defaults to today → +13 days, the fortnight view the app shows.

	Returns `{success, data: {from_date, to_date, days: [...]}}` where each day
	carries its shift, planned window, week-off flag and — for days already past
	— the attendance status that resulted.
	"""
	user = frappe.session.user
	start = getdate(from_date) if from_date else getdate()
	end = getdate(to_date) if to_date else add_days(start, 13)
	if end < start:
		frappe.throw(_("To Date cannot be before From Date."))
	if (end - start).days > 92:
		frappe.throw(_("Ask for at most 92 days at a time."))

	rows = frappe.db.sql(
		"""
		SELECT e.duty_date, e.shift, e.is_week_off, e.start_time, e.end_time, e.remarks,
		       r.depot, r.name AS roster
		FROM `tabDuty Roster Entry` e
		INNER JOIN `tabDuty Roster` r ON r.name = e.parent
		WHERE r.status = 'Published'
		  AND e.parenttype = 'Duty Roster'
		  AND e.engineer = %(user)s
		  AND e.duty_date BETWEEN %(start)s AND %(end)s
		ORDER BY e.duty_date ASC
		""",
		{"user": user, "start": start, "end": end},
		as_dict=True,
	)

	attendance = {
		str(getdate(a["attendance_date"])): a
		for a in frappe.get_all(
			"Duty Attendance",
			filters={"user": user, "attendance_date": ["between", [start, end]]},
			fields=["attendance_date", "status", "worked_hours", "is_late", "first_check_in"],
			limit_page_length=0,
		)
	}

	days = []
	for row in rows:
		day = str(getdate(row.duty_date))
		att = attendance.get(day)
		days.append(
			{
				"date": day,
				"shift": _shift_brief(row.shift),
				"is_week_off": cint(row.is_week_off),
				"remarks": row.remarks,
				"depot": row.depot,
				"depot_name": frappe.db.get_value("Depot", row.depot, "depot_name") if row.depot else None,
				"status": att["status"] if att else None,
				"worked_hours": flt(att["worked_hours"], 2) if att else None,
				"is_late": cint(att["is_late"]) if att else 0,
			}
		)

	return _ok({"from_date": str(start), "to_date": str(end), "days": days})


@frappe.whitelist()
def get_my_attendance(from_date: str | None = None, to_date: str | None = None) -> dict:
	"""The engineer's own attendance history — the 'my hours' list in the app.

	Defaults to the last 30 days. Returns `{success, data: {days, totals}}`.
	"""
	user = frappe.session.user
	end = getdate(to_date) if to_date else getdate()
	start = getdate(from_date) if from_date else add_days(end, -29)

	rows = frappe.get_all(
		"Duty Attendance",
		filters={"user": user, "attendance_date": ["between", [start, end]]},
		fields=[
			"name",
			"attendance_date",
			"status",
			"shift",
			"first_check_in",
			"last_check_out",
			"worked_hours",
			"is_late",
			"late_by_minutes",
			"outside_geofence",
		],
		order_by="attendance_date desc",
		limit_page_length=0,
	)

	days = [
		{
			"name": r["name"],
			"date": str(getdate(r["attendance_date"])),
			"status": r["status"],
			"shift": r["shift"],
			"first_check_in": str(r["first_check_in"]) if r["first_check_in"] else None,
			"last_check_out": str(r["last_check_out"]) if r["last_check_out"] else None,
			"worked_hours": flt(r["worked_hours"], 2),
			"is_late": cint(r["is_late"]),
			"late_by_minutes": cint(r["late_by_minutes"]),
			"outside_geofence": cint(r["outside_geofence"]),
		}
		for r in rows
	]

	present = [d for d in days if d["status"] in (R.STATUS_PRESENT, R.STATUS_HALF_DAY, R.STATUS_ON_DUTY)]
	return _ok(
		{
			"from_date": str(start),
			"to_date": str(end),
			"days": days,
			"totals": {
				"present_days": len(present),
				"late_days": len([d for d in days if d["is_late"]]),
				"absent_days": len([d for d in days if d["status"] == R.STATUS_ABSENT]),
				"total_hours": round(sum(d["worked_hours"] for d in days), 2),
			},
		}
	)


# ------------------------------------------------------------ admin: the board


@frappe.whitelist()
def get_attendance_board(depot: str | None = None, on_date: str | None = None) -> dict:
	"""Live picture of one depot's day — the dashboard the brief asks for.

	Params: `depot` (defaults to the manager's first depot), `on_date`.

	Returns `{success, data: {date, depot, summary, rows}}`. Each row is one
	rostered engineer with their plan, actual punches and last known location,
	including the engineers who have not shown up — the ones a depot manager is
	actually looking for.
	"""
	_assert_admin()
	depot = depot or R.primary_depot(frappe.session.user)
	if depot:
		_assert_depot_access(depot)
	day = getdate(on_date) if on_date else getdate()

	planned_rows = frappe.db.sql(
		"""
		SELECT e.engineer, e.engineer_name, e.shift, e.is_week_off, e.remarks, r.depot
		FROM `tabDuty Roster Entry` e
		INNER JOIN `tabDuty Roster` r ON r.name = e.parent
		WHERE r.status = 'Published'
		  AND e.parenttype = 'Duty Roster'
		  AND e.duty_date = %(day)s
		  {depot_filter}
		ORDER BY e.engineer_name ASC
		""".format(depot_filter="AND r.depot = %(depot)s" if depot else ""),
		{"day": day, "depot": depot},
		as_dict=True,
	)

	att_filters = {"attendance_date": day}
	if depot:
		att_filters["depot"] = depot
	attendance_rows = frappe.get_all(
		"Duty Attendance",
		filters=att_filters,
		fields=[
			"name",
			"user",
			"employee_name",
			"status",
			"shift",
			"first_check_in",
			"last_check_out",
			"worked_hours",
			"is_late",
			"late_by_minutes",
			"outside_geofence",
			"check_in_maps_link",
			"check_in_latitude",
			"check_in_longitude",
		],
		limit_page_length=0,
	)
	by_user = {a["user"]: a for a in attendance_rows}

	rows: list[dict] = []
	seen: set[str] = set()

	for p in planned_rows:
		seen.add(p.engineer)
		a = by_user.get(p.engineer)
		rows.append(_board_row(p.engineer, p.engineer_name, p.shift, cint(p.is_week_off), a, p.remarks))

	# Anyone who punched without being rostered still belongs on the board —
	# an unplanned presence is exactly what a manager wants to notice.
	for user, a in by_user.items():
		if user in seen:
			continue
		rows.append(_board_row(user, a["employee_name"], a["shift"], 0, a, None))

	summary = {
		"rostered": len([r for r in rows if r["is_rostered"] and not r["is_week_off"]]),
		"on_duty": len([r for r in rows if r["status"] == R.STATUS_ON_DUTY]),
		"checked_out": len([r for r in rows if r["status"] in (R.STATUS_PRESENT, R.STATUS_HALF_DAY)]),
		"not_started": len([r for r in rows if r["status"] == R.STATUS_NOT_STARTED and not r["is_week_off"]]),
		"late": len([r for r in rows if r["is_late"]]),
		"absent": len([r for r in rows if r["status"] == R.STATUS_ABSENT]),
		"week_off": len([r for r in rows if r["is_week_off"]]),
		"outside_geofence": len([r for r in rows if r["outside_geofence"]]),
		"total_hours": round(sum(r["worked_hours"] or 0 for r in rows), 2),
	}

	return _ok(
		{
			"date": str(day),
			"depot": depot,
			"depot_name": frappe.db.get_value("Depot", depot, "depot_name") if depot else None,
			"summary": summary,
			"rows": rows,
		}
	)


def _board_row(user: str, name: str | None, shift: str | None, is_week_off: int, a, remarks) -> dict:
	return {
		"user": user,
		"employee_name": name or frappe.db.get_value("User", user, "full_name") or user,
		"shift": _shift_brief(shift or (a["shift"] if a else None)),
		"is_rostered": True if shift or is_week_off else bool(a),
		"is_week_off": bool(is_week_off),
		"remarks": remarks,
		"attendance": a["name"] if a else None,
		"status": (a["status"] if a else (R.STATUS_WEEK_OFF if is_week_off else R.STATUS_NOT_STARTED)),
		"first_check_in": str(a["first_check_in"]) if a and a["first_check_in"] else None,
		"last_check_out": str(a["last_check_out"]) if a and a["last_check_out"] else None,
		"worked_hours": flt(a["worked_hours"], 2) if a else 0.0,
		"is_late": cint(a["is_late"]) if a else 0,
		"late_by_minutes": cint(a["late_by_minutes"]) if a else 0,
		"outside_geofence": cint(a["outside_geofence"]) if a else 0,
		"maps_link": a["check_in_maps_link"] if a else None,
		"latitude": a["check_in_latitude"] if a else None,
		"longitude": a["check_in_longitude"] if a else None,
	}


# --------------------------------------------------------- admin: roster authoring


@frappe.whitelist()
def get_depot_engineers(depot: str) -> dict:
	"""The engineers assignable at a depot — the picker behind Fill Roster.

	Returns `{success, data: {engineers: [{user, full_name, roles}]}}`.
	"""
	_assert_admin()
	_assert_depot_access(depot)

	users = [
		r["user"]
		for r in frappe.get_all(
			"Depot Engineer",
			filters={"parent": depot, "parenttype": "Depot"},
			fields=["user"],
			limit_page_length=0,
		)
	]
	if not users:
		return _ok({"engineers": []})

	rows = frappe.get_all(
		"User",
		filters={"name": ["in", users], "enabled": 1},
		fields=["name", "full_name", "mobile_no"],
		order_by="full_name asc",
		limit_page_length=0,
	)
	return _ok(
		{
			"engineers": [
				{"user": r["name"], "full_name": r["full_name"] or r["name"], "mobile_no": r["mobile_no"]}
				for r in rows
			]
		}
	)


@frappe.whitelist()
def get_depot_roster(depot: str, from_date: str, to_date: str) -> dict:
	"""Published + draft roster entries for a depot over a period.

	Returns `{success, data: {rosters, entries}}` — enough to render a grid of
	engineer by date.
	"""
	_assert_admin()
	_assert_depot_access(depot)
	start, end = getdate(from_date), getdate(to_date)

	rosters = frappe.get_all(
		"Duty Roster",
		filters={
			"depot": depot,
			"from_date": ["<=", end],
			"to_date": [">=", start],
		},
		fields=["name", "from_date", "to_date", "status", "published_at"],
		order_by="from_date asc",
		limit_page_length=0,
	)
	if not rosters:
		return _ok({"rosters": [], "entries": []})

	entries = frappe.get_all(
		"Duty Roster Entry",
		filters={
			"parent": ["in", [r["name"] for r in rosters]],
			"parenttype": "Duty Roster",
			"duty_date": ["between", [start, end]],
		},
		fields=[
			"parent",
			"engineer",
			"engineer_name",
			"duty_date",
			"shift",
			"is_week_off",
			"start_time",
			"end_time",
			"remarks",
		],
		order_by="duty_date asc, engineer_name asc",
		limit_page_length=0,
	)
	for e in entries:
		e["duty_date"] = str(getdate(e["duty_date"]))
		e["start_time"] = str(e["start_time"]) if e["start_time"] else None
		e["end_time"] = str(e["end_time"]) if e["end_time"] else None

	return _ok({"rosters": rosters, "entries": entries})


@frappe.whitelist()
def fill_roster(
	roster: str,
	engineers: str | list,
	shift: str | None = None,
	weekdays: str | list | None = None,
	week_off_days: str | list | None = None,
	replace: int = 0,
) -> dict:
	"""Generate roster entries from a pattern instead of typing every row.

	`weekdays` / `week_off_days` are Monday=0 … Sunday=6. Existing rows for an
	engineer/date are left alone unless `replace` is set.

	Returns `{success, data: {added, total}, message}`.
	"""
	_assert_admin()
	doc = frappe.get_doc("Duty Roster", roster)
	_assert_depot_access(doc.depot)

	engineers = frappe.parse_json(engineers) if isinstance(engineers, str) else engineers
	weekdays = frappe.parse_json(weekdays) if isinstance(weekdays, str) else weekdays
	week_off_days = frappe.parse_json(week_off_days) if isinstance(week_off_days, str) else week_off_days

	added = doc.fill(
		engineers=[e for e in (engineers or []) if e],
		shift=shift or None,
		weekdays=[cint(d) for d in (weekdays or [])] or None,
		week_off_days=[cint(d) for d in (week_off_days or [])] or None,
		replace=bool(cint(replace)),
	)
	doc.save()
	frappe.db.commit()

	return _ok(
		{"added": added, "total": len(doc.entries or [])},
		_("Added {0} duty day(s).").format(added),
	)


@frappe.whitelist()
def publish_roster(roster: str) -> dict:
	"""Publish a roster — the moment its shifts become visible in the app.

	Returns `{success, data: {name, status, entries}, message}`.
	"""
	_assert_admin()
	doc = frappe.get_doc("Duty Roster", roster)
	_assert_depot_access(doc.depot)
	doc.publish()
	frappe.db.commit()

	return _ok(
		{"name": doc.name, "status": doc.status, "entries": len(doc.entries or [])},
		_("Published. {0} duty day(s) are now visible to the engineers.").format(len(doc.entries or [])),
	)


@frappe.whitelist()
def get_roster_coverage(roster: str) -> dict:
	"""Headcount per date for a roster — powers the thin-day warning.

	Returns `{success, data: {coverage: [{date, on_duty}]}}`.
	"""
	_assert_admin()
	doc = frappe.get_doc("Duty Roster", roster)
	_assert_depot_access(doc.depot)
	return _ok({"coverage": doc.coverage()})


@frappe.whitelist()
def admin_punch(
	user: str,
	punch_type: str,
	punch_time: str,
	note: str | None = None,
) -> dict:
	"""Record a punch on someone's behalf — the phone-died case.

	Written with source = Desk and requires a note, so a manager-entered punch is
	always distinguishable from one the engineer made.

	Returns `{success, data: {attendance}, message}`.
	"""
	_assert_admin()
	if not (note or "").strip():
		frappe.throw(_("A punch entered on someone's behalf needs a note explaining why."))

	punch_type = (punch_type or "").strip().title()
	if punch_type not in (R.PUNCH_IN, R.PUNCH_OUT):
		frappe.throw(_("punch_type must be In or Out."))

	depot = R.primary_depot(user)
	if depot:
		_assert_depot_access(depot)

	result = R.record_punch(
		punch_type,
		user=user,
		punch_time=get_datetime(punch_time),
		source="Desk",
		note=_("Entered by {0}: {1}").format(frappe.session.user, note.strip()),
	)
	frappe.db.commit()
	return _ok({"attendance": result["attendance"]["name"]}, _("Punch recorded."))
