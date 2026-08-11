"""Seed the process engine's shared masters.

Idempotent, like every seeder in this app — it runs after every `bench migrate`
and checks existence before inserting, so it is safe to re-run.

Creates the scannable entity types for a battery pack, the report brand
profiles, and the engine defaults. Deliberately does *not* create the Battery QC
process itself; that lives in `seed_battery_qc_process` so the two can be
re-run independently.
"""

import frappe

from vehicle_maintenance.process_engine import constants as C

# Component types on a pack. `expected_count` is what drives the traceability
# completeness figure on the certificate.
#
# CELL is seeded with scanning DISABLED on purpose. A bus is 12 packs x 48 cells
# = 576 cells; at a realistic 4-6 seconds a scan that is ~50 minutes of pure
# scanning per bus before a single check is answered, and it buys a cell→pack
# link that is already derivable through the module. Flip `is_scan_enabled` and
# set the count if you ever want it — one checkbox, no code change.
ENTITY_TYPES = [
	{
		"entity_code": "CELL_MODULE",
		"label": "Cell Module",
		"label_alt": "सेल मॉड्यूल",
		"icon": "🔋",
		"expected_count": 3,
		"is_scan_enabled": 1,
		"duplicate_policy": "Warn",
		"mfg_date_format": "DDMMYY",
		# Two shapes seen on module labels; the first that matches wins, and an
		# unmatched payload is still stored rather than rejected.
		"qr_pattern": (
			"^(?P<serial>[A-Z0-9]{8,16})-(?P<mfg>\\d{6})-M(?P<module>[0-9]{1,2})$\n"
			"^(?P<serial>[A-Z0-9]{8,16})\\|(?P<mfg>\\d{6})\\|(?P<module>[0-9]{1,2})\\|(?P<batch>[A-Z0-9]+)$"
		),
	},
	{
		"entity_code": "SLAVE_BMS",
		"label": "Slave BMS",
		"label_alt": "स्लेव BMS",
		"icon": "🧠",
		"expected_count": 1,
		"is_scan_enabled": 1,
		"duplicate_policy": "Warn",
		"mfg_date_format": "DDMMYY",
		"qr_pattern": "^(?P<serial>[A-Z0-9]{8,16})-(?P<mfg>\\d{6})$",
	},
	{
		"entity_code": "COOLING_PLATE",
		"label": "Cooling Plate",
		"label_alt": "कूलिंग प्लेट",
		"icon": "❄️",
		"expected_count": 1,
		"is_scan_enabled": 1,
		"duplicate_policy": "Warn",
		"mfg_date_format": "DDMMYY",
		"qr_pattern": "^(?P<serial>[A-Z0-9]{6,16})-(?P<mfg>\\d{6})$",
	},
	{
		"entity_code": "UPPER_CASE",
		"label": "Upper Case",
		"label_alt": "ऊपरी केस",
		"icon": "📦",
		"expected_count": 1,
		"is_scan_enabled": 1,
		"duplicate_policy": "Warn",
		"mfg_date_format": "DDMMYY",
		"qr_pattern": "^(?P<serial>[A-Z0-9]{6,16})-(?P<mfg>\\d{6})$",
	},
	{
		"entity_code": "CELL",
		"label": "Cell",
		"label_alt": "सेल",
		"icon": "🔌",
		"expected_count": 48,
		"is_scan_enabled": 0,
		"duplicate_policy": "Warn",
		"mfg_date_format": "DDMMYY",
		"qr_pattern": "^(?P<serial>[A-Z0-9]{8,20})-(?P<mfg>\\d{6})$",
	},
]

BRANDS = [
	{
		"brand_code": "SKYWORTH",
		"display_name": "Skyworth",
		"certificate_title": "Battery Quality Inspection Certificate",
		"is_default": 1,
		"primary_color": "#1F2937",
	},
]

# Reusable answer vocabularies. This is the engine's whole answer to "make
# pass/fail configurable": there is no built-in verdict, only these rows. Steps
# link to a set instead of each authoring its own, so a wording or translation
# fix lands on every step at once.
OUTCOME_SETS = [
	{
		"set_code": "PASS_FAIL_NA",
		"set_name": "Pass / Fail / N-A",
		"description": "The default inspection vocabulary. Fail is critical and asks for a photo and a remark.",
		"options": [
			{
				"value": "Pass",
				"label": "Pass",
				"label_alt": "ठीक",
				"is_pass": 1,
				"color": "green",
				"icon": "✓",
				"sequence": 1,
			},
			{
				"value": "Fail",
				"label": "Fail",
				"label_alt": "खराब",
				"is_pass": 0,
				"is_critical": 1,
				"requires_remark": 1,
				"requires_photo": 1,
				"color": "red",
				"icon": "✕",
				"sequence": 2,
			},
			{
				"value": "NA",
				"label": "N/A",
				"label_alt": "लागू नहीं",
				"is_pass": 1,
				"color": "grey",
				"icon": "–",
				"sequence": 3,
			},
		],
	},
	{
		"set_code": "PASS_FAIL_NA_MINOR",
		"set_name": "Pass / Fail / N-A (non-critical)",
		"description": "Same vocabulary, but a failure means rework rather than quarantining the whole unit.",
		"options": [
			{
				"value": "Pass",
				"label": "Pass",
				"label_alt": "ठीक",
				"is_pass": 1,
				"color": "green",
				"icon": "✓",
				"sequence": 1,
			},
			{
				"value": "Fail",
				"label": "Fail",
				"label_alt": "खराब",
				"is_pass": 0,
				"is_critical": 0,
				"requires_remark": 1,
				"requires_photo": 1,
				"color": "red",
				"icon": "✕",
				"sequence": 2,
			},
			{
				"value": "NA",
				"label": "N/A",
				"label_alt": "लागू नहीं",
				"is_pass": 1,
				"color": "grey",
				"icon": "–",
				"sequence": 3,
			},
		],
	},
	{
		"set_code": "THREE_TIER",
		"set_name": "Good / Recommend / Immediate",
		"description": "The PMS service vocabulary — proof that the engine needs no code change for a different verdict scale.",
		"options": [
			{
				"value": "Good",
				"label": "Good",
				"label_alt": "ठीक",
				"is_pass": 1,
				"color": "green",
				"icon": "✓",
				"sequence": 1,
			},
			{
				"value": "Recommended",
				"label": "Repair recommended",
				"label_alt": "मरम्मत सुझाई",
				"is_pass": 0,
				"requires_remark": 1,
				"color": "amber",
				"icon": "!",
				"sequence": 2,
			},
			{
				"value": "Immediate",
				"label": "Repair immediately",
				"label_alt": "तुरंत मरम्मत",
				"is_pass": 0,
				"is_critical": 1,
				"requires_remark": 1,
				"requires_photo": 1,
				"color": "red",
				"icon": "✕",
				"sequence": 3,
			},
		],
	},
	{
		"set_code": "YES_NO",
		"set_name": "Yes / No",
		"options": [
			{
				"value": "Yes",
				"label": "Yes",
				"label_alt": "हाँ",
				"is_pass": 1,
				"color": "green",
				"icon": "✓",
				"sequence": 1,
			},
			{
				"value": "No",
				"label": "No",
				"label_alt": "नहीं",
				"is_pass": 0,
				"color": "red",
				"icon": "✕",
				"sequence": 2,
			},
		],
	},
]

# Reusable automations. Authoring "raise the NCR, tell the supervisor, quarantine"
# once means changing who gets alerted is a single edit, not twenty.
ACTION_SETS = [
	{
		"set_code": "CRITICAL_RESPONSE",
		"set_name": "Critical failure response",
		"description": "Raise a deviation, alert the QA admin in realtime, and quarantine the unit.",
		"actions": [
			{
				"trigger": C.TRIGGER_ON_CRITICAL,
				"action_type": C.ACT_RAISE_DEVIATION,
				"severity": "Critical",
				"message_template": "{{ step.label }} failed on {{ identifier }}",
			},
			{
				"trigger": C.TRIGGER_ON_CRITICAL,
				"action_type": C.ACT_NOTIFY_ROLE,
				"target": "Battery QA Admin",
				"severity": "Critical",
				"message_template": "Critical failure on {{ identifier }} — {{ step.label }}",
			},
			{
				"trigger": C.TRIGGER_ON_CRITICAL,
				"action_type": C.ACT_QUARANTINE,
				"severity": "Critical",
				"message_template": "Critical failure at {{ step.label }}",
			},
		],
	},
	{
		"set_code": "DEVIATION_ONLY",
		"set_name": "Raise a deviation only",
		"description": "Log the non-conformance for review without quarantining or alerting.",
		"actions": [
			{
				"trigger": C.TRIGGER_ON_FAIL,
				"action_type": C.ACT_RAISE_DEVIATION,
				"severity": "Minor",
				"message_template": "{{ step.label }} failed on {{ identifier }}",
			}
		],
	},
]


def _upsert(doctype: str, key_field: str, rows: list[dict]) -> None:
	"""Insert missing rows; leave existing ones alone.

	Existing records are never overwritten — an admin who has tuned a QR pattern
	on a live site must not have it reset by the next migrate.
	"""
	for row in rows:
		key = row[key_field]
		if frappe.db.exists(doctype, key):
			continue
		doc = frappe.get_doc({"doctype": doctype, **row})
		doc.insert(ignore_permissions=True)


def _seed_settings() -> None:
	settings = frappe.get_single("Process Engine Settings")
	changed = False
	if not settings.default_brand and frappe.db.exists("Report Brand Profile", "SKYWORTH"):
		settings.default_brand = "SKYWORTH"
		changed = True
	if not settings.app_step_type_capability:
		settings.app_step_type_capability = 1
		changed = True
	if changed:
		settings.save(ignore_permissions=True)


def _seed_role_composition() -> None:
	"""Give Battery QA Admin the engine roles its job needs.

	The role exists as its own name so it can be scoped to the battery process
	via that process's `author_roles`, while still carrying the platform-level
	Process Author and Process Verifier rights.
	"""
	for role in (C.ROLE_AUTHOR, C.ROLE_OPERATOR, C.ROLE_VERIFIER, C.ROLE_VIEWER, "Battery QA Admin"):
		if not frappe.db.exists("Role", role):
			frappe.get_doc({"doctype": "Role", "role_name": role, "desk_access": 1}).insert(
				ignore_permissions=True
			)


def execute() -> None:
	_seed_role_composition()
	_upsert("Process Entity Type", "entity_code", ENTITY_TYPES)
	_upsert("Report Brand Profile", "brand_code", BRANDS)
	_upsert("Process Outcome Set", "set_code", OUTCOME_SETS)
	_upsert("Process Action Set", "set_code", ACTION_SETS)
	_seed_settings()
	frappe.db.commit()
