"""Dynamic dropdown options for the app, sourced from Frappe metadata.

Every option list the app renders that isn't a master/import (Priority, Job Type,
repair/maintenance/software selects, urgency, severity, breakdown, photo angles)
is a `Select` field on a doctype. Admins edit these from **Customize Form** in
Desk (Setup → Customize Form → pick the doctype → edit the field's Options),
which Frappe persists as Property Setters. This endpoint reads the *current*
options from metadata so the app reflects those edits with no code change.

The app keeps the same lists hardcoded as an offline fallback; this endpoint just
overrides them when reachable.
"""

import frappe

# app option key -> (doctype, fieldname)
_FIELD_MAP = {
	"job_card_type": ("Job Card", "job_card_type"),
	"priority": ("Job Card", "priority"),
	"repair_subtype": ("Job Card", "repair_subtype"),
	"service_type": ("Job Card", "service_type"),
	"incident_place": ("Job Card", "incident_place"),
	"remote_status": ("Job Card", "remote_resolution_status"),
	"fix_type": ("Job Card", "fix_type"),
	"risk": ("Job Card", "recurrence_risk"),
	"severity": ("Job Card", "force_close_severity"),
	"activity_type": ("Job Card Repair Item", "activity_type"),
	"component_status": ("Job Card Repair Item", "component_status"),
	"repair_item_status": ("Job Card Repair Item", "item_status"),
	"maintenance_type": ("Job Card Maintenance Item", "maintenance_type"),
	"maintenance_action": ("Job Card Maintenance Item", "action"),
	"maintenance_unit": ("Job Card Maintenance Item", "unit"),
	"maintenance_item_status": ("Job Card Maintenance Item", "item_status"),
	"software_reason": ("Job Card Software Component", "reason"),
	"software_status": ("Job Card Software Component", "status"),
	"urgency": ("Inventory Request", "urgency_level"),
	"angle": ("Vehicle Image", "angle"),
}


def _field_options(doctype: str, fieldname: str) -> list[str]:
	"""Return the non-blank Select options for a field, honouring Customize Form."""
	try:
		field = frappe.get_meta(doctype).get_field(fieldname)
	except Exception:
		return []
	if not field or not field.options:
		return []
	return [line.strip() for line in field.options.split("\n") if line.strip()]


@frappe.whitelist()
def get_app_field_options() -> dict:
	"""Return {option_key: [choices]} for every app dropdown sourced from a Select.

	Read live from metadata, so editing a field's Options via Customize Form in
	Desk immediately changes what the app offers.
	"""
	data = {}
	for key, (doctype, fieldname) in _FIELD_MAP.items():
		opts = _field_options(doctype, fieldname)
		if opts:
			data[key] = opts
	return {"success": True, "data": data}
