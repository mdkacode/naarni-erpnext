"""Duty roster & attendance domain logic.

The shape of the module, and why:

* **Punches are the truth; attendance is derived.** `Duty Punch` rows are the
  append-only event log. `Duty Attendance` is a rollup that `recompute()` can
  rebuild from scratch at any time, so correcting a bad punch corrects the day
  without anyone hand-editing totals.

* **Every gate is advisory by default.** No GPS fix, outside the geofence, no
  published roster — each is recorded and surfaced, not refused. An engineer
  blocked at the gate does not go home; they start work and the record is simply
  lost, which is strictly worse than an accepted punch with a flag on it.
  `Roster Settings` lets an admin tighten any of these once the ground reality
  supports it.

* **Times are naive site-local**, like everything else in this app. A shift that
  ends "next day" is the one case that needs saying out loud, so it is a field
  on `Duty Shift` rather than an inference from end < start.
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta

import frappe
from frappe import _
from frappe.utils import add_days, cint, flt, get_datetime, get_time, getdate, now_datetime

PUNCH_IN = "In"
PUNCH_OUT = "Out"

STATUS_NOT_STARTED = "Not Started"
STATUS_ON_DUTY = "On Duty"
STATUS_PRESENT = "Present"
STATUS_HALF_DAY = "Half Day"
STATUS_ABSENT = "Absent"
STATUS_WEEK_OFF = "Week Off"

# Roles seeded into Roster Settings on first migrate. Editable from Desk after
# that — this list is only ever a starting point.
DEFAULT_PUNCH_ROLES = ("Service Engineer", "Technician", "Depot Manager")

EARTH_RADIUS_M = 6_371_000.0


# --------------------------------------------------------------------- settings


def settings():
	"""The Roster Settings Single. Cheap — Frappe caches Singles."""
	return frappe.get_cached_doc("Roster Settings")


def punch_roles() -> set[str]:
	cfg = settings()
	roles = {r.role for r in cfg.punch_roles or [] if r.role}
	return roles or set(DEFAULT_PUNCH_ROLES)


def can_punch(user: str | None = None) -> bool:
	user = user or frappe.session.user
	roles = set(frappe.get_roles(user))
	if "System Manager" in roles or user == "Administrator":
		return True
	return bool(roles & punch_roles())


def assert_can_punch(user: str | None = None) -> None:
	if not can_punch(user):
		frappe.throw(
			_("Your role is not enabled for check-in. Ask your Depot Manager to add it in Roster Settings."),
			frappe.PermissionError,
		)


# --------------------------------------------------------------------- geo


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
	"""Great-circle distance in metres."""
	p1, p2 = math.radians(lat1), math.radians(lat2)
	dp = math.radians(lat2 - lat1)
	dl = math.radians(lon2 - lon1)
	a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
	return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def maps_link(latitude: float | None, longitude: float | None) -> str | None:
	if latitude is None or longitude is None:
		return None
	return f"https://www.google.com/maps?q={flt(latitude, 6)},{flt(longitude, 6)}"


def evaluate_geofence(depot: str | None, latitude: float | None, longitude: float | None) -> dict:
	"""Distance from the depot and whether that is outside its radius.

	Returns `{"distance_m": float|None, "outside": bool, "radius_m": int, "evaluated": bool}`.
	`evaluated` is False whenever we could not judge — geofencing off, depot has
	no coordinates, or the phone had no fix — and an unevaluated punch is never
	treated as a violation.
	"""
	cfg = settings()
	blank = {"distance_m": None, "outside": False, "radius_m": 0, "evaluated": False}

	if (cfg.geofence_mode or "Off") == "Off":
		return blank
	if not depot or latitude is None or longitude is None:
		return blank

	row = frappe.db.get_value("Depot", depot, ["latitude", "longitude", "geofence_radius_m"], as_dict=True)
	if not row or not row.latitude or not row.longitude:
		return blank

	radius = cint(row.geofence_radius_m) or cint(cfg.default_radius_m) or 300
	distance = haversine_m(flt(latitude), flt(longitude), flt(row.latitude), flt(row.longitude))
	return {
		"distance_m": round(distance, 1),
		"outside": distance > radius,
		"radius_m": radius,
		"evaluated": True,
	}


# --------------------------------------------------------------------- depot


def user_depots(user: str) -> list[str]:
	"""Depots this user is assigned to (via Depot.service_engineers)."""
	rows = frappe.get_all(
		"Depot Engineer",
		filters={"user": user, "parenttype": "Depot"},
		fields=["parent"],
		limit_page_length=0,
	)
	return [r["parent"] for r in rows]


def primary_depot(user: str) -> str | None:
	depots = user_depots(user)
	return depots[0] if depots else None


# --------------------------------------------------------------------- roster


def get_planned_duty(user: str, on_date) -> dict | None:
	"""The engineer's rostered duty for a date, from Published rosters only.

	A Draft roster is invisible here on purpose: a half-built plan must never
	reach a phone. Returns None when nothing is rostered.
	"""
	rows = frappe.db.sql(
		"""
		SELECT e.name AS entry, e.duty_date, e.shift, e.is_week_off,
		       e.has_custom_time, e.start_time, e.end_time, e.remarks,
		       r.name AS roster, r.depot
		FROM `tabDuty Roster Entry` e
		INNER JOIN `tabDuty Roster` r ON r.name = e.parent
		WHERE r.status = 'Published'
		  AND e.parenttype = 'Duty Roster'
		  AND e.engineer = %(user)s
		  AND e.duty_date = %(on_date)s
		ORDER BY r.modified DESC
		LIMIT 1
		""",
		{"user": user, "on_date": getdate(on_date)},
		as_dict=True,
	)
	if not rows:
		return None

	row = rows[0]
	shift = _shift_doc(row.shift)
	# `has_custom_time`, not the emptiness of the Time fields, decides whether
	# this row overrides the shift — see the note in duty_roster_entry.py.
	overrides = (row.start_time, row.end_time) if cint(row.has_custom_time) else (None, None)
	planned_start, planned_end = shift_window(row.duty_date, shift, *overrides)
	return {
		"roster": row.roster,
		"entry": row.entry,
		"depot": row.depot,
		"shift": row.shift,
		"shift_label": (shift.get("shift_name") if shift else None) or row.shift,
		"color": (shift or {}).get("color") or "Blue",
		"is_week_off": cint(row.is_week_off),
		"planned_start": planned_start,
		"planned_end": planned_end,
		"remarks": row.remarks,
		"grace_minutes": cint((shift or {}).get("grace_minutes")) or cint(settings().default_grace_minutes),
		"early_window_minutes": cint((shift or {}).get("early_window_minutes")) or 90,
		"full_day_hours": flt((shift or {}).get("full_day_hours")) or 8.0,
		"half_day_hours": flt((shift or {}).get("half_day_hours")) or 4.0,
	}


def _shift_doc(shift: str | None) -> dict | None:
	if not shift:
		return None
	return frappe.db.get_value(
		"Duty Shift",
		shift,
		[
			"shift_name",
			"start_time",
			"end_time",
			"crosses_midnight",
			"grace_minutes",
			"early_window_minutes",
			"full_day_hours",
			"half_day_hours",
			"color",
		],
		as_dict=True,
	)


def shift_window(
	on_date, shift: dict | None, start_override=None, end_override=None
) -> tuple[datetime | None, datetime | None]:
	"""Absolute start/end datetimes for a duty day.

	Row-level overrides beat the shift; a night shift's end lands on the next
	calendar day.
	"""
	day = getdate(on_date)
	start_t = start_override or (shift or {}).get("start_time")
	end_t = end_override or (shift or {}).get("end_time")
	if not start_t or not end_t:
		return None, None

	start = datetime.combine(day, get_time(start_t))
	end = datetime.combine(day, get_time(end_t))
	# Decide the wrap from the resolved times, not the shift's flag: a row-level
	# override can turn a day shift into one that crosses midnight, and the flag
	# on Duty Shift would not know. The flag's job is to make the admin say it
	# out loud at authoring time (see DutyShift.validate), not to drive the maths.
	if end <= start:
		end = datetime.combine(add_days(day, 1), get_time(end_t))
	return start, end


# --------------------------------------------------------------------- attendance


def _dedup_key(user: str, on_date) -> str:
	return f"{user}|{getdate(on_date)}"


def get_or_create_attendance(user: str, on_date, depot: str | None = None):
	"""The Duty Attendance row for (user, date), created if missing.

	Uniqueness is a DB constraint on `dedup_key`, not a read-then-write check, so
	two punches racing from a double tap cannot produce two day rows.
	"""
	key = _dedup_key(user, on_date)
	name = frappe.db.get_value("Duty Attendance", {"dedup_key": key}, "name")
	if name:
		return frappe.get_doc("Duty Attendance", name)

	planned = get_planned_duty(user, on_date) or {}
	doc = frappe.get_doc(
		{
			"doctype": "Duty Attendance",
			"user": user,
			"employee_name": frappe.db.get_value("User", user, "full_name") or user,
			"attendance_date": getdate(on_date),
			"depot": depot or planned.get("depot") or primary_depot(user),
			"roster": planned.get("roster"),
			"shift": planned.get("shift"),
			"planned_start": planned.get("planned_start"),
			"planned_end": planned.get("planned_end"),
			"status": STATUS_WEEK_OFF if planned.get("is_week_off") else STATUS_NOT_STARTED,
			"dedup_key": key,
		}
	)
	# Savepoint rather than a bare rollback: losing the race must not discard
	# anything else this request has already written.
	save_point = "duty_attendance_create"
	frappe.db.savepoint(save_point)
	try:
		doc.insert(ignore_permissions=True)
	except frappe.DuplicateEntryError:
		frappe.db.rollback(save_point=save_point)
		name = frappe.db.get_value("Duty Attendance", {"dedup_key": key}, "name")
		return frappe.get_doc("Duty Attendance", name)
	return doc


def punches_for(attendance: str) -> list[dict]:
	return frappe.get_all(
		"Duty Punch",
		filters={"attendance": attendance},
		fields=[
			"name",
			"punch_type",
			"punch_time",
			"latitude",
			"longitude",
			"accuracy_m",
			"maps_link",
			"location_available",
			"distance_from_depot_m",
			"outside_geofence",
			"source",
			"note",
			"photo",
		],
		order_by="punch_time asc",
		limit_page_length=0,
	)


def paired_hours(punches: list[dict]) -> tuple[float, bool]:
	"""Worked hours from closed In→Out pairs, and whether a punch is still open.

	Only closed pairs count. An open check-in contributes nothing to the stored
	total — the elapsed time of an in-progress shift is a display concern, and
	writing it into the record would mean the number changes every time anyone
	looks at the page.
	"""
	total = timedelta()
	open_at: datetime | None = None
	for p in punches:
		t = get_datetime(p["punch_time"])
		if p["punch_type"] == PUNCH_IN:
			if open_at is None:
				open_at = t
		elif open_at is not None:
			total += t - open_at
			open_at = None
	return round(total.total_seconds() / 3600.0, 2), open_at is not None


def recompute(attendance) -> None:
	"""Rebuild every derived field on a Duty Attendance from its punches."""
	if isinstance(attendance, str):
		attendance = frappe.get_doc("Duty Attendance", attendance)

	punches = punches_for(attendance.name)
	ins = [p for p in punches if p["punch_type"] == PUNCH_IN]
	outs = [p for p in punches if p["punch_type"] == PUNCH_OUT]

	worked, is_open = paired_hours(punches)
	planned = get_planned_duty(attendance.user, attendance.attendance_date) or {}

	# Keep the plan in step: a roster published or swapped after the first punch
	# should still describe the day correctly.
	if planned:
		attendance.roster = planned.get("roster")
		attendance.shift = planned.get("shift")
		attendance.planned_start = planned.get("planned_start")
		attendance.planned_end = planned.get("planned_end")
		if not attendance.depot:
			attendance.depot = planned.get("depot")

	attendance.punch_count = len(punches)
	attendance.first_check_in = get_datetime(ins[0]["punch_time"]) if ins else None
	attendance.last_check_out = get_datetime(outs[-1]["punch_time"]) if outs else None
	attendance.worked_hours = worked
	attendance.outside_geofence = 1 if any(cint(p["outside_geofence"]) for p in punches) else 0

	if ins:
		first = ins[0]
		attendance.check_in_latitude = first["latitude"]
		attendance.check_in_longitude = first["longitude"]
		attendance.check_in_maps_link = first["maps_link"]
	if outs:
		last = outs[-1]
		attendance.check_out_latitude = last["latitude"]
		attendance.check_out_longitude = last["longitude"]
		attendance.check_out_maps_link = last["maps_link"]

	# Lateness
	attendance.is_late = 0
	attendance.late_by_minutes = 0
	attendance.early_exit_minutes = 0
	planned_start = planned.get("planned_start")
	planned_end = planned.get("planned_end")
	grace = cint(planned.get("grace_minutes")) or cint(settings().default_grace_minutes)

	if planned_start and attendance.first_check_in:
		late = (get_datetime(attendance.first_check_in) - planned_start).total_seconds() / 60.0
		if late > grace:
			attendance.is_late = 1
			attendance.late_by_minutes = int(round(late))
	if planned_end and attendance.last_check_out and not is_open:
		early = (planned_end - get_datetime(attendance.last_check_out)).total_seconds() / 60.0
		if early > grace:
			attendance.early_exit_minutes = int(round(early))

	attendance.status = _derive_status(attendance, planned, punches, is_open, worked)
	attendance.save(ignore_permissions=True)


def _derive_status(attendance, planned: dict, punches: list, is_open: bool, worked: float) -> str:
	"""Coarse label for the day. `worked_hours` always carries the detail.

	Note that a day with *any* punch never becomes Absent automatically. Someone
	who worked two hours is a conversation for their manager, not a record the
	system silently marks absent — that is the kind of thing people dispute, and
	rightly.
	"""
	if attendance.manual_status:
		return attendance.manual_status
	if planned.get("is_week_off"):
		return STATUS_WEEK_OFF
	if not punches:
		if _should_auto_absent(attendance, planned):
			return STATUS_ABSENT
		return STATUS_NOT_STARTED
	if is_open:
		return STATUS_ON_DUTY

	full = flt(planned.get("full_day_hours")) or 8.0
	half = flt(planned.get("half_day_hours")) or 4.0
	if worked >= full:
		return STATUS_PRESENT
	if worked >= half:
		return STATUS_HALF_DAY
	return STATUS_HALF_DAY


def _should_auto_absent(attendance, planned: dict) -> bool:
	if not cint(settings().auto_absent_enabled):
		return False
	if not planned or planned.get("is_week_off"):
		return False
	# Only once the duty window has actually closed.
	end = planned.get("planned_end")
	if end and now_datetime() < end:
		return False
	return getdate(attendance.attendance_date) < getdate()


# --------------------------------------------------------------------- punching


def record_punch(
	punch_type: str,
	user: str | None = None,
	punch_time: datetime | None = None,
	latitude: float | None = None,
	longitude: float | None = None,
	accuracy_m: float | None = None,
	device_uuid: str | None = None,
	client_uuid: str | None = None,
	note: str | None = None,
	photo: str | None = None,
	source: str = "App",
	depot: str | None = None,
) -> dict:
	"""Write one punch and refresh the day. Returns `{punch, attendance, warnings}`.

	Idempotent on `client_uuid`: a retry after a timeout resolves to the punch
	already recorded rather than a duplicate.
	"""
	user = user or frappe.session.user
	punch_time = get_datetime(punch_time) if punch_time else now_datetime()
	on_date = _duty_date_for(user, punch_time)

	if client_uuid:
		existing = frappe.db.get_value(
			"Duty Punch", {"client_uuid": client_uuid}, ["name", "attendance"], as_dict=True
		)
		if existing:
			return {
				"punch": frappe.get_doc("Duty Punch", existing.name).as_dict(),
				"attendance": frappe.get_doc("Duty Attendance", existing.attendance).as_dict(),
				"warnings": [],
				"duplicate": True,
			}

	attendance = get_or_create_attendance(user, on_date, depot=depot)
	depot = attendance.depot
	cfg = settings()
	warnings: list[str] = []

	has_fix = latitude is not None and longitude is not None
	if not has_fix:
		if cint(cfg.require_location):
			frappe.throw(
				_("Location is required to check in. Turn on GPS and allow location access, then try again.")
			)
		warnings.append(_("Location was not captured for this punch."))

	geo = evaluate_geofence(depot, latitude, longitude)
	if geo["outside"]:
		depot_label = frappe.db.get_value("Depot", depot, "depot_name") or depot
		message = _("You are {0} m from {1} — outside its {2} m radius.").format(
			int(geo["distance_m"]), depot_label, geo["radius_m"]
		)
		if (cfg.geofence_mode or "Off") == "Block":
			frappe.throw(message)
		warnings.append(message)

	punch = frappe.get_doc(
		{
			"doctype": "Duty Punch",
			"punch_type": punch_type,
			"punch_time": punch_time,
			"user": user,
			"employee_name": frappe.db.get_value("User", user, "full_name") or user,
			"depot": depot,
			"attendance": attendance.name,
			"source": source,
			"device_uuid": device_uuid,
			"client_uuid": client_uuid,
			"location_available": 1 if has_fix else 0,
			"latitude": latitude,
			"longitude": longitude,
			"accuracy_m": accuracy_m,
			"maps_link": maps_link(latitude, longitude),
			"distance_from_depot_m": geo["distance_m"],
			"outside_geofence": 1 if geo["outside"] else 0,
			"note": note,
			"photo": photo,
		}
	)
	# Duty Punch.on_update re-derives the day so a Desk edit stays consistent.
	# On this path we suppress it and recompute explicitly, so a rollup failure
	# surfaces as an error here instead of being logged and swallowed.
	frappe.flags.in_roster_recompute = True
	try:
		punch.insert(ignore_permissions=True)
	finally:
		frappe.flags.in_roster_recompute = False

	recompute(attendance)

	return {
		"punch": punch.as_dict(),
		"attendance": frappe.get_doc("Duty Attendance", attendance.name).as_dict(),
		"warnings": warnings,
		"duplicate": False,
	}


def _duty_date_for(user: str, at: datetime):
	"""Which duty day a punch belongs to.

	A night shift that started yesterday at 22:00 and is checked out of at 06:00
	must land on yesterday's row, otherwise the day shows a check-out with no
	check-in and tomorrow shows the reverse. We look back one day for an open
	check-in whose shift window still covers `at`.
	"""
	today = getdate(at)
	yesterday = add_days(today, -1)

	prev_key = _dedup_key(user, yesterday)
	prev = frappe.db.get_value(
		"Duty Attendance", {"dedup_key": prev_key}, ["name", "planned_end"], as_dict=True
	)
	if not prev:
		return today

	punches = punches_for(prev.name)
	_hours, is_open = paired_hours(punches)
	if not is_open:
		return today

	# Open check-in from yesterday: it belongs to yesterday if we are still inside
	# that shift's window (plus the auto-checkout grace), else the engineer simply
	# forgot and today is a new day.
	limit = None
	if prev.planned_end:
		limit = get_datetime(prev.planned_end) + timedelta(hours=2)
	else:
		first_in = next((p for p in punches if p["punch_type"] == PUNCH_IN), None)
		if first_in:
			limit = get_datetime(first_in["punch_time"]) + timedelta(
				hours=flt(settings().auto_checkout_after_hours) or 14
			)
	return yesterday if limit and at <= limit else today


def open_check_in(user: str, on_date=None) -> dict | None:
	"""The engineer's currently open check-in, if any."""
	on_date = on_date or getdate()
	name = frappe.db.get_value("Duty Attendance", {"dedup_key": _dedup_key(user, on_date)}, "name")
	if not name:
		return None
	punches = punches_for(name)
	_hours, is_open = paired_hours(punches)
	if not is_open:
		return None
	return next((p for p in reversed(punches) if p["punch_type"] == PUNCH_IN), None)


# --------------------------------------------------------------------- scheduled


def close_forgotten_punches() -> int:
	"""Auto check-out anyone still open past the configured window.

	Written as source = Auto with an explicit note, so nobody mistakes it for the
	engineer's own punch. Returns the number closed.
	"""
	cfg = settings()
	if not cint(cfg.auto_checkout_enabled):
		return 0

	cutoff_hours = flt(cfg.auto_checkout_after_hours) or 14.0
	now = now_datetime()

	candidates = frappe.get_all(
		"Duty Attendance",
		filters={
			"status": STATUS_ON_DUTY,
			"attendance_date": [">=", add_days(getdate(), -7)],
		},
		fields=["name", "user", "first_check_in", "planned_end"],
		limit_page_length=0,
	)

	closed = 0
	for row in candidates:
		punches = punches_for(row.name)
		_hours, is_open = paired_hours(punches)
		if not is_open:
			continue
		last_in = next((p for p in reversed(punches) if p["punch_type"] == PUNCH_IN), None)
		if not last_in:
			continue
		opened_at = get_datetime(last_in["punch_time"])
		deadline = opened_at + timedelta(hours=cutoff_hours)
		if now < deadline:
			continue

		# Close at the planned end when we know it — that is the honest estimate —
		# else at the cutoff.
		close_at = deadline
		if row.planned_end and get_datetime(row.planned_end) > opened_at:
			close_at = min(get_datetime(row.planned_end), deadline)

		try:
			record_punch(
				PUNCH_OUT,
				user=row.user,
				punch_time=close_at,
				source="Auto",
				note=_("Automatic check-out — no check-out was recorded within {0} hours.").format(
					int(cutoff_hours)
				),
			)
			closed += 1
		except Exception:
			frappe.log_error(
				title="roster.close_forgotten_punches",
				message=f"{row.name}: {frappe.get_traceback()}",
			)
	if closed:
		frappe.db.commit()
	return closed


def mark_absentees(on_date=None) -> int:
	"""Mark yesterday's rostered-but-unpunched days Absent, when enabled."""
	if not cint(settings().auto_absent_enabled):
		return 0

	on_date = getdate(on_date or add_days(getdate(), -1))
	rows = frappe.db.sql(
		"""
		SELECT e.engineer, r.depot
		FROM `tabDuty Roster Entry` e
		INNER JOIN `tabDuty Roster` r ON r.name = e.parent
		WHERE r.status = 'Published'
		  AND e.parenttype = 'Duty Roster'
		  AND e.duty_date = %(on_date)s
		  AND IFNULL(e.is_week_off, 0) = 0
		""",
		{"on_date": on_date},
		as_dict=True,
	)

	marked = 0
	for row in rows:
		attendance = get_or_create_attendance(row.engineer, on_date, depot=row.depot)
		if cint(attendance.punch_count) or attendance.manual_status:
			continue
		recompute(attendance)
		if frappe.db.get_value("Duty Attendance", attendance.name, "status") == STATUS_ABSENT:
			marked += 1
	if marked:
		frappe.db.commit()
	return marked
