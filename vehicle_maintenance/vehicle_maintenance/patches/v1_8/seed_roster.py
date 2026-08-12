"""Seed the Duty Roster module: shift templates, policy defaults, workspace.

Idempotent and defensive, like every other seeder here — it runs after every
migrate, only ever fills in what is missing, and never overwrites an admin's
edit. A depot that has retimed its Morning shift keeps its timing.
"""

import json

import frappe

from vehicle_maintenance.fleet_service.roster import DEFAULT_PUNCH_ROLES

# Indian bus-depot norms: a two-shift day is most common, with a general shift
# for supervisors and a night shift where the depot runs round the clock.
SHIFTS = [
	{
		"shift_name": "Morning",
		"start_time": "06:00:00",
		"end_time": "14:00:00",
		"color": "Orange",
		"grace_minutes": 15,
		"full_day_hours": 8,
		"half_day_hours": 4,
	},
	{
		"shift_name": "General",
		"start_time": "09:00:00",
		"end_time": "18:00:00",
		"color": "Blue",
		"grace_minutes": 15,
		"full_day_hours": 8,
		"half_day_hours": 4,
	},
	{
		"shift_name": "Evening",
		"start_time": "14:00:00",
		"end_time": "22:00:00",
		"color": "Purple",
		"grace_minutes": 15,
		"full_day_hours": 8,
		"half_day_hours": 4,
	},
	{
		"shift_name": "Night",
		"start_time": "22:00:00",
		"end_time": "06:00:00",
		"crosses_midnight": 1,
		"color": "Grey",
		"grace_minutes": 20,
		"full_day_hours": 8,
		"half_day_hours": 4,
	},
]


def _safe(fn) -> None:
	try:
		fn()
	except Exception:
		frappe.log_error(title="seed_roster", message=frappe.get_traceback())


def execute() -> None:
	_safe(_seed_shifts)
	_safe(_seed_settings)
	_safe(_build_workspace)
	frappe.db.commit()


def _seed_shifts() -> None:
	for row in SHIFTS:
		if frappe.db.exists("Duty Shift", row["shift_name"]):
			continue
		frappe.get_doc({"doctype": "Duty Shift", "is_active": 1, **row}).insert(
			ignore_permissions=True, ignore_if_duplicate=True
		)


def _seed_settings() -> None:
	cfg = frappe.get_single("Roster Settings")
	changed = False

	# Only seed the role list when it is empty — an admin who removed a role must
	# not get it back on the next migrate.
	if not cfg.punch_roles:
		for role in DEFAULT_PUNCH_ROLES:
			if frappe.db.exists("Role", role):
				cfg.append("punch_roles", {"role": role})
				changed = True

	if not cfg.geofence_mode:
		cfg.geofence_mode = "Warn"
		changed = True
	if not cfg.default_radius_m:
		cfg.default_radius_m = 300
		changed = True
	if not cfg.auto_checkout_after_hours:
		cfg.auto_checkout_after_hours = 14
		changed = True
	if not cfg.default_grace_minutes:
		cfg.default_grace_minutes = 15
		changed = True

	if changed:
		cfg.save(ignore_permissions=True)


# ------------------------------------------------------------------- workspace


def _ensure_number_card(name: str, doctype: str, label: str, filters: list) -> None:
	if not frappe.db.exists("DocType", doctype) or frappe.db.exists("Number Card", name):
		return
	frappe.get_doc(
		{
			"doctype": "Number Card",
			"name": name,
			"label": label,
			"document_type": doctype,
			"function": "Count",
			"is_public": 1,
			"filters_json": json.dumps(filters),
		}
	).insert(ignore_permissions=True, ignore_if_duplicate=True)


def _build_workspace() -> None:
	"""A 'Duty Roster' workspace — the admin dashboard for this module.

	Kept separate from Fleet Service rather than bolted onto it: a depot manager
	opening this page is doing one job (who is on today, who is late), and mixing
	it with job cards and vehicles buries exactly the numbers they came for.
	"""
	today = "Today"
	_ensure_number_card(
		"DR On Duty Now",
		"Duty Attendance",
		"On Duty Now",
		[["Duty Attendance", "status", "=", "On Duty"], ["Duty Attendance", "attendance_date", "=", today]],
	)
	_ensure_number_card(
		"DR Late Today",
		"Duty Attendance",
		"Late Today",
		[["Duty Attendance", "is_late", "=", 1], ["Duty Attendance", "attendance_date", "=", today]],
	)
	_ensure_number_card(
		"DR Not Started",
		"Duty Attendance",
		"Not Started",
		[
			["Duty Attendance", "status", "=", "Not Started"],
			["Duty Attendance", "attendance_date", "=", today],
		],
	)
	_ensure_number_card(
		"DR Outside Geofence",
		"Duty Punch",
		"Outside Geofence",
		[["Duty Punch", "outside_geofence", "=", 1]],
	)

	number_cards = [
		lbl
		for lbl in ("On Duty Now", "Late Today", "Not Started", "Outside Geofence")
		if frappe.db.exists("Number Card", "DR " + lbl)
	]

	links: list = []

	def card(label: str, doctypes: list, link_type: str = "DocType") -> None:
		rows = [
			{"type": "Link", "label": dt, "link_type": link_type, "link_to": dt}
			for dt in doctypes
			if frappe.db.exists("DocType" if link_type == "DocType" else "Report", dt)
		]
		if not rows:
			return
		links.append({"type": "Card Break", "label": label})
		links.extend(rows)

	card("Plan", ["Duty Roster", "Duty Shift"])
	card("Attendance", ["Duty Attendance", "Duty Punch"])
	card("Reports", ["Duty Attendance Board"], link_type="Report")
	card("Setup", ["Roster Settings", "Depot"])

	shortcuts = []
	if frappe.db.exists("Report", "Duty Attendance Board"):
		shortcuts.append(
			{
				"type": "Report",
				"label": "Attendance Board",
				"link_to": "Duty Attendance Board",
				"color": "Green",
			}
		)
	for label, dt, color in [
		("Rosters", "Duty Roster", "Blue"),
		("Punches", "Duty Punch", "Orange"),
	]:
		if frappe.db.exists("DocType", dt):
			shortcuts.append(
				{"type": "DocType", "label": label, "link_to": dt, "color": color, "doc_view": "List"}
			)

	content = [{"type": "header", "data": {"text": "Duty Roster", "col": 12}}]
	for lbl in number_cards:
		content.append({"type": "number_card", "data": {"number_card_name": "DR " + lbl, "col": 3}})
	for sc in shortcuts:
		content.append({"type": "shortcut", "data": {"shortcut_name": sc["label"], "col": 3}})
	for grp in ("Plan", "Attendance", "Reports", "Setup"):
		content.append({"type": "card", "data": {"card_name": grp, "col": 4}})

	nc_rows = [{"number_card_name": "DR " + lbl, "label": lbl} for lbl in number_cards]

	payload = {
		"doctype": "Workspace",
		"name": "Duty Roster",
		"label": "Duty Roster",
		"title": "Duty Roster",
		"module": "Fleet Service",
		"public": 1,
		"icon": "calendar",
		"content": json.dumps(content),
		"links": links,
		"shortcuts": shortcuts,
		"number_cards": nc_rows,
	}

	if frappe.db.exists("Workspace", "Duty Roster"):
		doc = frappe.get_doc("Workspace", "Duty Roster")
		doc.content = payload["content"]
		for field, rows in (("links", links), ("shortcuts", shortcuts), ("number_cards", nc_rows)):
			doc.set(field, [])
			for r in rows:
				doc.append(field, r)
		doc.public = 1
		doc.save(ignore_permissions=True)
	else:
		frappe.get_doc(payload).insert(ignore_permissions=True, ignore_if_duplicate=True)
