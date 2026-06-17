"""Seed the Alert Type catalog.

Idempotent: each alert type is created only if missing. Safe to re-run via
`bench migrate`. Mirrors the engine's original rules.yaml so the catalog and the
engine agree on parameter/operator/severity out of the box. Customers then tune
threshold/channels/vehicles per Alert Subscription.
"""

import frappe

from vehicle_maintenance.fleet_service.doctype.alert_type.alert_type import SYMBOL_TO_LABEL

# (id, alert_name, parameter, op, default_threshold, unit, severity, title, description)
# `id` is the engine rule id (preset doc name). `op` uses operator symbols here and
# is converted to the friendly Operator label the form stores.
ALERT_TYPES = [
	(
		"pack_overheat",
		"Battery pack overheating",
		"pack1_cellmax_temperature",
		">",
		55,
		"°C",
		"critical",
		"Battery pack overheating",
		"Maximum battery cell temperature has exceeded the safe operating limit.",
	),
	(
		"overspeed",
		"Vehicle overspeeding",
		"vehicle_speed_vcu",
		">",
		80,
		"km/h",
		"warning",
		"Vehicle overspeeding",
		"Vehicle speed has crossed the configured limit.",
	),
	(
		"low_soc",
		"Low state of charge",
		"bat_soc",
		"<",
		15,
		"%",
		"warning",
		"Low state of charge",
		"Battery state of charge has dropped below the configured minimum.",
	),
	(
		"pack_undervoltage",
		"Battery pack undervoltage",
		"bat_voltage",
		"<",
		420,
		"V",
		"critical",
		"Battery pack undervoltage",
		"Battery pack voltage has fallen below the safe minimum.",
	),
]


def execute() -> None:
	for (
		alert_id,
		alert_name,
		parameter,
		op,
		default_threshold,
		unit,
		severity,
		title,
		description,
	) in ALERT_TYPES:
		if frappe.db.exists("Alert Type", alert_id):
			continue
		frappe.get_doc(
			{
				"doctype": "Alert Type",
				"name": alert_id,  # preset doc name = stable engine rule id
				"alert_name": alert_name,
				"parameter": parameter,
				"op": SYMBOL_TO_LABEL.get(op, op),  # store the friendly Operator label
				"default_threshold": default_threshold,
				"unit": unit,
				"severity": severity,
				"title": title,
				"description": description,
				"enabled": 1,
			}
		).insert(ignore_permissions=True)
