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

JC = "Job Card"


def _safe(fn):
	try:
		fn()
	except Exception:
		frappe.log_error(title="seed_fleet_workspace", message=frappe.get_traceback())


def _ensure_number_card(name: str, document_type: str, label: str, filters: list) -> None:
	if not frappe.db.exists("DocType", document_type):
		return
	if frappe.db.exists("Number Card", name):
		return
	frappe.get_doc(
		{
			"doctype": "Number Card",
			"name": name,
			"label": label,
			"document_type": document_type,
			"function": "Count",
			"is_public": 1,
			"filters_json": json.dumps(filters),
		}
	).insert(ignore_permissions=True, ignore_if_duplicate=True)


def _master_links() -> list:
	masters = [
		"Vehicle",
		"Customer",
		"Depot",
		"OEM",
		"Part",
		"Part Group",
		"Subsystem",
		"Complaint Catalog",
		"Fault Code",
		"Observation Template",
		"Telemetry Parameter",
		"Telemetry Code",
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
		"Alert Event",
		"Vehicle Health Card",
		"Lead",
	]
	return [
		{"type": "Link", "label": m, "link_type": "DocType", "link_to": m}
		for m in ops
		if frappe.db.exists("DocType", m)
	]


def _setup_links() -> list:
	out = [
		{"type": "Link", "label": "Data Import (CSV/XLSX)", "link_type": "DocType", "link_to": "Data Import"}
	]
	for m in ("Alert Type", "Notification Channel"):
		if frappe.db.exists("DocType", m):
			out.append({"type": "Link", "label": m, "link_type": "DocType", "link_to": m})
	return out


def execute() -> None:
	_safe(
		lambda: _ensure_number_card(
			"FS Open Job Cards",
			JC,
			"Open Job Cards",
			[[JC, "workflow_state", "not in", ["Closed", "Force Closed"]]],
		)
	)
	_safe(
		lambda: _ensure_number_card(
			"FS Awaiting Approval",
			JC,
			"Awaiting Approval",
			[[JC, "workflow_state", "=", "Awaiting Customer Approval"]],
		)
	)
	_safe(lambda: _ensure_number_card("FS SLA Breached", JC, "SLA Breached", [[JC, "sla_breached", "=", 1]]))
	_safe(lambda: _ensure_number_card("FS Vehicles", "Vehicle", "Vehicles", []))

	_safe(_build_workspace)


def _build_workspace() -> None:
	# Card link groups (each starts with a Card Break, then its links).
	links: list = []

	def card(card_label: str, rows: list) -> None:
		if not rows:
			return
		links.append({"type": "Card Break", "label": card_label})
		links.extend(rows)

	card("Masters", _master_links())
	card("Operations", _ops_links())
	card("Setup & Mass Import", _setup_links())

	shortcuts = []
	for label, dt, color in [
		("Vehicles", "Vehicle", "Blue"),
		("Job Cards", "Job Card", "Green"),
		("Parts", "Part", "Orange"),
		("Customers", "Customer", "Cyan"),
		("Mass Import", "Data Import", "Grey"),
	]:
		if frappe.db.exists("DocType", dt):
			shortcuts.append(
				{"type": "DocType", "label": label, "link_to": dt, "color": color, "doc_view": "List"}
			)

	number_cards = [
		{"label": lbl}
		for lbl in ("Open Job Cards", "Awaiting Approval", "SLA Breached", "Vehicles")
		if frappe.db.exists("Number Card", "FS " + lbl)
	]

	# Page content blocks (rendered top→bottom).
	content = [{"type": "header", "data": {"text": "Fleet Service", "col": 12}}]
	for nc in number_cards:
		content.append({"type": "number_card", "data": {"number_card_name": "FS " + nc["label"], "col": 3}})
	for sc in shortcuts:
		content.append({"type": "shortcut", "data": {"shortcut_name": sc["label"], "col": 3}})
	for grp in ("Masters", "Operations", "Setup & Mass Import"):
		content.append({"type": "card", "data": {"card_name": grp, "col": 4}})

	# Number-card child rows reference the real card name (FS <label>).
	nc_rows = [{"number_card_name": "FS " + nc["label"], "label": nc["label"]} for nc in number_cards]

	payload = {
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

	if frappe.db.exists("Workspace", "Fleet Service"):
		doc = frappe.get_doc("Workspace", "Fleet Service")
		doc.content = payload["content"]
		doc.set("links", [])
		for r in links:
			doc.append("links", r)
		doc.set("shortcuts", [])
		for r in shortcuts:
			doc.append("shortcuts", r)
		doc.set("number_cards", [])
		for r in nc_rows:
			doc.append("number_cards", r)
		doc.public = 1
		doc.save(ignore_permissions=True)
	else:
		frappe.get_doc(payload).insert(ignore_permissions=True, ignore_if_duplicate=True)
