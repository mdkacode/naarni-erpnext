"""Seed the Alert Response catalog — canned resolutions for the tap-to-pick
"How did you fix it?" quick responses on alert tickets.

Generic (alert_type = None) so they're offered on any alert; the app ranks them
by usage and promotes new typed answers into this catalog (source=Learned).
Idempotent: each row is created only if a same-text generic row is missing.
"""

import frappe

# Ordered roughly by how common they are in EV-bus field triage. All generic
# (not tied to a specific Alert Type) — the learning loop adds type-specific ones.
RESPONSES = [
	"Allowed to cool down, resumed normally",
	"Reset BMS / cleared the fault",
	"Coolant topped up",
	"Loose connector re-seated",
	"Battery terminals cleaned / tightened",
	"Fuse replaced",
	"Sensor recalibrated",
	"False trigger — no action needed",
	"Spoke to driver, cautioned on driving",
	"Speed limiter / geofence checked",
	"Tyre pressure corrected",
	"Tyre replaced",
	"Brakes inspected — found OK",
	"Brake pads replaced",
	"Device power-cycled",
	"SIM / antenna re-seated",
	"Software / firmware updated",
	"Charger / charging point checked",
	"Escalated to OEM",
	"Sent to workshop for repair",
	"Job card raised for repair",
]


def execute() -> None:
	if not frappe.db.exists("DocType", "Alert Response"):
		return
	for text in RESPONSES:
		# Match on the generic (no alert_type) row for this text.
		exists = frappe.db.exists("Alert Response", {"response_text": text, "alert_type": ["is", "not set"]})
		if exists:
			continue
		frappe.get_doc(
			{
				"doctype": "Alert Response",
				"response_text": text,
				"is_active": 1,
				"usage_count": 0,
				"source": "Seeded",
			}
		).insert(ignore_permissions=True)
