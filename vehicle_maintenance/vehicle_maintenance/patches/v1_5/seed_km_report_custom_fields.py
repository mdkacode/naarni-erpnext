"""Idempotent seeder: per-vehicle overrides for KM & SLA reporting.

Added as Custom Fields on the app's own Vehicle doctype (via create_custom_fields, so
they are reproducible on every `bench migrate`). Both are optional overrides — the
effective value falls back to Fleet Report Config, then a global default.
"""

import frappe
from frappe.custom.doctype.custom_field.custom_field import create_custom_fields


def execute() -> None:
	create_custom_fields(
		{
			"Vehicle": [
				{
					"fieldname": "reporting_section",
					"label": "Reporting Overrides",
					"fieldtype": "Section Break",
					"insert_after": "last_synced_at",
					"collapsible": 1,
				},
				{
					"fieldname": "min_km_override",
					"label": "Min KM per Month (override)",
					"fieldtype": "Float",
					"precision": "1",
					"insert_after": "reporting_section",
					"description": "Overrides the customer's Min KM per Vehicle for this vehicle only.",
				},
				{
					"fieldname": "uptime_target_override",
					"label": "Uptime Target % (override)",
					"fieldtype": "Percent",
					"insert_after": "min_km_override",
					"description": "Overrides the customer's Contract Uptime Target for this vehicle only.",
				},
			]
		},
		ignore_validate=True,
	)
	frappe.db.commit()
