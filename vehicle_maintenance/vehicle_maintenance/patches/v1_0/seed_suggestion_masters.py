"""Seed starter Complaint Catalog, Fault Code, and Observation Template entries.

These back the searchable dropdowns on the Job Card create form so non-tech-savvy
field staff tap a known value instead of typing free text (the product's
non-negotiable UX rule). Idempotent: each row is created only if missing, so this
is safe to re-run on every `bench migrate`. A `subsystem` link is set only when
that Subsystem master actually exists, so seeding never fails on a fresh site.
"""

import frappe

# (complaint_text, subsystem)
COMPLAINTS = [
	("Engine not starting", "Engine"),
	("Engine overheating", "Engine"),
	("Excessive smoke from exhaust", "Engine"),
	("Unusual engine noise", "Engine"),
	("Loss of power / poor pickup", "Powertrain"),
	("Gear shifting hard / slipping", "Powertrain"),
	("Brakes weak / not stopping", "Brakes"),
	("Brake noise / grinding", "Brakes"),
	("Steering heavy / vibrating", "Suspension"),
	("Suspension noise over bumps", "Suspension"),
	("AC not cooling", "HVAC"),
	("Battery not charging", "Electrical"),
	("Headlights / indicators not working", "Electrical"),
	("Dashboard warning light on", "Electrical"),
	("Door not opening / closing", "Body"),
	("Tyre worn / puncture", "Other"),
	("Coolant / oil leak", "Engine"),
	("Software / firmware error", "Software"),
]

# (fault_code, description, subsystem, severity)
FAULT_CODES = [
	("P0217", "Engine over-temperature condition", "Engine", "High"),
	("P0606", "ECU / PCM processor fault", "Software", "Critical"),
	("P0A80", "Replace hybrid/EV battery pack", "Electrical", "Critical"),
	("P0562", "System voltage low", "Electrical", "High"),
	("C0035", "Wheel speed sensor fault", "Brakes", "Medium"),
	("U0100", "Lost communication with ECM/PCM", "Software", "High"),
	("B1000", "ECU internal fault", "Software", "Medium"),
	("P0700", "Transmission control system malfunction", "Powertrain", "High"),
]

# (observation_text, subsystem)
OBSERVATIONS = [
	("Oil level low, top-up required", "Engine"),
	("Coolant below minimum mark", "Engine"),
	("Brake pad thickness near limit", "Brakes"),
	("Tyre tread below safe limit", "Other"),
	("Battery terminals corroded", "Electrical"),
	("Air filter clogged", "Engine"),
	("Belt cracked / worn", "Engine"),
	("Minor body dent, cosmetic only", "Body"),
	("Suspension bush worn", "Suspension"),
	("AC gas pressure low", "HVAC"),
]


def _subsystem_or_none(name: str) -> str | None:
	return name if name and frappe.db.exists("Subsystem", name) else None


def execute() -> None:
	for text, subsystem in COMPLAINTS:
		if frappe.db.exists("Complaint Catalog", {"complaint_text": text}):
			continue
		frappe.get_doc(
			{
				"doctype": "Complaint Catalog",
				"complaint_text": text,
				"subsystem": _subsystem_or_none(subsystem),
				"is_active": 1,
			}
		).insert(ignore_permissions=True)

	for code, description, subsystem, severity in FAULT_CODES:
		if frappe.db.exists("Fault Code", code):
			continue
		frappe.get_doc(
			{
				"doctype": "Fault Code",
				"fault_code": code,
				"description": description,
				"subsystem": _subsystem_or_none(subsystem),
				"severity": severity,
				"is_active": 1,
			}
		).insert(ignore_permissions=True)

	for text, subsystem in OBSERVATIONS:
		if frappe.db.exists("Observation Template", {"observation_text": text}):
			continue
		frappe.get_doc(
			{
				"doctype": "Observation Template",
				"observation_text": text,
				"subsystem": _subsystem_or_none(subsystem),
				"is_active": 1,
			}
		).insert(ignore_permissions=True)

	frappe.db.commit()
