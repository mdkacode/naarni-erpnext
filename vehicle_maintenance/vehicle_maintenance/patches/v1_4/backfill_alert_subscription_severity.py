"""Backfill Alert Subscription.severity from the linked Alert Type.

`severity` is a new fetch_from field, so existing subscriptions have it blank
until re-saved. Populate it in one pass so the list view / indicator shows the
criticality straight away. Idempotent: only fills rows still missing a value.
"""

import frappe


def execute() -> None:
	if not frappe.db.has_column("Alert Subscription", "severity"):
		return

	rows = frappe.get_all(
		"Alert Subscription",
		filters={"severity": ["in", ["", None]]},
		fields=["name", "alert_type"],
	)
	if not rows:
		return

	# Cache severity per Alert Type to avoid N+1 lookups.
	severity_by_type: dict[str, str] = {}
	for row in rows:
		alert_type = row.alert_type
		if not alert_type:
			continue
		if alert_type not in severity_by_type:
			severity_by_type[alert_type] = frappe.db.get_value("Alert Type", alert_type, "severity") or ""
		severity = severity_by_type[alert_type]
		if severity:
			frappe.db.set_value(
				"Alert Subscription",
				row.name,
				"severity",
				severity,
				update_modified=False,
			)
