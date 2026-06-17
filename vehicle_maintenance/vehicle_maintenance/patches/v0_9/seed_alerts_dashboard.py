"""Seed a 'Naarni Alerts' dashboard with charts + number cards over Alert Event.

Gives instant graphs once alerts start logging. Fully defensive: every creation is
guarded and exceptions are swallowed so a charting-schema hiccup on any Frappe
version can never abort `bench migrate`. Re-runnable; existing docs are skipped.
"""

import json

import frappe

DT = "Alert Event"


def _exists(doctype: str, name: str) -> bool:
	try:
		return bool(frappe.db.exists(doctype, name))
	except Exception:
		return True  # if we can't tell, don't try to recreate


def _insert(doc: dict) -> None:
	try:
		if _exists(doc["doctype"], doc.get("name") or doc.get("chart_name") or doc.get("label") or ""):
			return
		frappe.get_doc(doc).insert(ignore_permissions=True, ignore_if_duplicate=True)
	except Exception:
		frappe.log_error(title="seed_alerts_dashboard", message=frappe.get_traceback())


def execute() -> None:
	if not _exists("DocType", DT):
		return

	charts = [
		{
			"doctype": "Dashboard Chart",
			"name": "Alerts Over Time",
			"chart_name": "Alerts Over Time",
			"chart_type": "Count",
			"document_type": DT,
			"based_on": "triggered_at",
			"timeseries": 1,
			"time_interval": "Daily",
			"timespan": "Last Month",
			"type": "Line",
			"is_public": 1,
			"filters_json": "[]",
		},
		{
			"doctype": "Dashboard Chart",
			"name": "Alerts by Severity",
			"chart_name": "Alerts by Severity",
			"chart_type": "Group By",
			"document_type": DT,
			"group_by_type": "Count",
			"group_by_based_on": "severity",
			"type": "Donut",
			"is_public": 1,
			"filters_json": "[]",
		},
		{
			"doctype": "Dashboard Chart",
			"name": "Alerts by Type",
			"chart_name": "Alerts by Type",
			"chart_type": "Group By",
			"document_type": DT,
			"group_by_type": "Count",
			"group_by_based_on": "alert_type",
			"type": "Bar",
			"is_public": 1,
			"filters_json": "[]",
		},
		{
			"doctype": "Dashboard Chart",
			"name": "Alerts by Vehicle",
			"chart_name": "Alerts by Vehicle",
			"chart_type": "Group By",
			"document_type": DT,
			"group_by_type": "Count",
			"group_by_based_on": "registration_number",
			"type": "Bar",
			"is_public": 1,
			"filters_json": "[]",
		},
	]
	for c in charts:
		_insert(c)

	cards = [
		{
			"doctype": "Number Card",
			"name": "Total Alerts",
			"label": "Total Alerts",
			"document_type": DT,
			"function": "Count",
			"is_public": 1,
			"show_percentage_stats": 1,
			"stats_time_interval": "Daily",
			"filters_json": "[]",
		},
		{
			"doctype": "Number Card",
			"name": "Critical Alerts",
			"label": "Critical Alerts",
			"document_type": DT,
			"function": "Count",
			"is_public": 1,
			"filters_json": json.dumps([[DT, "severity", "=", "critical"]]),
		},
		{
			"doctype": "Number Card",
			"name": "Open Alerts",
			"label": "Open Alerts",
			"document_type": DT,
			"function": "Count",
			"is_public": 1,
			"filters_json": json.dumps([[DT, "status", "=", "Open"]]),
		},
	]
	for c in cards:
		_insert(c)

	# Tie them together into one dashboard.
	if not _exists("Dashboard", "Naarni Alerts"):
		_insert(
			{
				"doctype": "Dashboard",
				"name": "Naarni Alerts",
				"dashboard_name": "Naarni Alerts",
				"is_default": 0,
				"cards": [
					{"card": "Total Alerts"},
					{"card": "Critical Alerts"},
					{"card": "Open Alerts"},
				],
				"charts": [
					{"chart": "Alerts Over Time", "width": "Full"},
					{"chart": "Alerts by Severity", "width": "Half"},
					{"chart": "Alerts by Type", "width": "Half"},
					{"chart": "Alerts by Vehicle", "width": "Full"},
				],
			}
		)
