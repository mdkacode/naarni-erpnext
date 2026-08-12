"""Seed the 'Fleet Service' Desk workspace — the admin landing page.

Gives Ops/Admin a single tidy page: number cards (open job cards, SLA breaches,
vehicles), quick shortcuts, grouped links to every master/operation, and a
**Setup & Mass Import** card pointing at Frappe's Data Import (CSV/XLSX) tool.

Fully defensive and re-runnable: every write is guarded; any error is logged and
swallowed so a workspace-schema difference on a Frappe version can never abort
`bench migrate`. Existing cards/workspace are refreshed in place.
"""

import json

import frappe

from vehicle_maintenance.utils.workspace import ensure_number_card, upsert_workspace

JC = "Job Card"


def _safe(fn):
	try:
		fn()
	except Exception:
		frappe.log_error(title="seed_fleet_workspace", message=frappe.get_traceback())


def _master_links() -> list:
	# Lean landing page: daily-use masters only. The deep-config masters (OEM,
	# Part Group, Subsystem, Complaint Catalog, Fault Code, Observation Template,
	# Telemetry Parameter/Code) are still fully reachable via global search / their
	# own list views — just not surfaced on this page.
	masters = [
		"Vehicle",
		"Customer",
		"Depot",
		"Part",
		"Service Contract",
	]
	return [
		{"type": "Link", "label": m, "link_type": "DocType", "link_to": m}
		for m in masters
		if frappe.db.exists("DocType", m)
	]


def _ops_links() -> list:
	ops = [
		"Job Card",
		"Service Ticket",
		"Inventory Request",
		"Service Estimate",
		"Vehicle Health Card",
	]
	return [
		{"type": "Link", "label": m, "link_type": "DocType", "link_to": m}
		for m in ops
		if frappe.db.exists("DocType", m)
	]


def _setup_links() -> list:
	# Lean page: keep only the bulk-import entry point. Alert Type / Notification
	# Channel are one-time setup, reachable via search when needed.
	return [
		{"type": "Link", "label": "Data Import (CSV/XLSX)", "link_type": "DocType", "link_to": "Data Import"}
	]


def _report_links() -> list:
	# The KM/SLA reports, labelled the way users look for them.
	reports = [("Monthly KM Report", "KM Billing Report"), ("SLA Management", "SLA Report")]
	return [
		{"type": "Link", "label": label, "link_type": "Report", "link_to": rep}
		for label, rep in reports
		if frappe.db.exists("Report", rep)
	]


# (label, doctype, filters) — the four numbers the landing page leads with.
FS_CARDS = [
	("Open Job Cards", JC, [[JC, "workflow_state", "not in", ["Closed", "Force Closed"]]]),
	("Awaiting Approval", JC, [[JC, "workflow_state", "=", "Awaiting Customer Approval"]]),
	("SLA Breached", JC, [[JC, "sla_breached", "=", 1]]),
	("Vehicles", "Vehicle", []),
]


def execute() -> None:
	_safe(_build_workspace)


def _build_workspace() -> None:
	# Card link groups (each starts with a Card Break, then its links).
	links: list = []

	def card(card_label: str, rows: list) -> None:
		if not rows:
			return
		links.append({"type": "Card Break", "label": card_label})
		links.extend(rows)

	card("Reports", _report_links())
	card("Masters", _master_links())
	card("Operations", _ops_links())
	card("Setup & Mass Import", _setup_links())

	shortcuts = []
	# KM/SLA reports first — the ones users ask for by name.
	for label, rep, color in [
		("Monthly KM Report", "KM Billing Report", "Purple"),
		("SLA Management", "SLA Report", "Yellow"),
	]:
		if frappe.db.exists("Report", rep):
			shortcuts.append({"type": "Report", "label": label, "link_to": rep, "color": color})
	for label, dt, color in [
		("Vehicles", "Vehicle", "Blue"),
		("Job Cards", "Job Card", "Green"),
		("Customers", "Customer", "Cyan"),
	]:
		if frappe.db.exists("DocType", dt):
			shortcuts.append(
				{"type": "DocType", "label": label, "link_to": dt, "color": color, "doc_view": "List"}
			)

	# Number Card autonames from its label and ignores any name we pass, so the
	# workspace must reference what was actually stored — referencing our own
	# intended name meant Frappe dropped every row and the page showed no
	# numbers, while each migrate quietly inserted another copy of every card.
	nc_rows = []
	for label, doctype, filters in FS_CARDS:
		name = ensure_number_card(label, doctype, filters)
		if name:
			nc_rows.append({"number_card_name": name, "label": label})

	# Page content blocks (rendered top→bottom).
	content = [{"type": "header", "data": {"text": "Fleet Service", "col": 12}}]
	for row in nc_rows:
		content.append(
			{"type": "number_card", "data": {"number_card_name": row["number_card_name"], "col": 3}}
		)
	for sc in shortcuts:
		content.append({"type": "shortcut", "data": {"shortcut_name": sc["label"], "col": 3}})
	for grp in ("Reports", "Masters", "Operations", "Setup & Mass Import"):
		content.append({"type": "card", "data": {"card_name": grp, "col": 4}})

	upsert_workspace(
		{
			"doctype": "Workspace",
			"name": "Fleet Service",
			"label": "Fleet Service",
			"title": "Fleet Service",
			"module": "Fleet Service",
			"public": 1,
			"icon": "service",
			"content": json.dumps(content),
			"links": links,
			"shortcuts": shortcuts,
			"number_cards": nc_rows,
		}
	)
