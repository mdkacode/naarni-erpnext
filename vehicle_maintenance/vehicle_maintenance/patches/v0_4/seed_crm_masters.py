"""Seed default Lead Source, Lead Status, and CRM Email Templates.

Idempotent: each row is created only if missing. Safe to re-run via
`bench migrate`.
"""

import frappe

LEAD_SOURCES = [
	"Referral",
	"Walk-in",
	"Website",
	"Exhibition",
	"Cold Call",
	"Partner",
	"Other",
]

# (status_name, stage, display_order, is_terminal)
LEAD_STATUSES = [
	("New", "Open", 10, 0),
	("Contacted", "Working", 20, 0),
	("Qualified", "Working", 30, 0),
	("Proposal Sent", "Working", 40, 0),
	("Negotiation", "Working", 50, 0),
	("On Hold", "On Hold", 60, 0),
	("Won", "Won", 70, 1),
	("Lost", "Lost", 80, 1),
]

EMAIL_TEMPLATES = [
	{
		"name": "CRM — Follow-up after initial contact",
		"subject": "Following up on our conversation, {{ lead_name }}",
		"response": (
			"<p>Hi {{ lead_name }},</p>"
			"<p>Thanks for the time earlier. I'm following up to see if you had any "
			"questions about our maintenance offering.</p>"
			"<p>Happy to schedule a quick call whenever works for you.</p>"
		),
	},
	{
		"name": "CRM — Quote reminder",
		"subject": "Quote for {{ lead_name }} — awaiting your review",
		"response": (
			"<p>Hi {{ lead_name }},</p>"
			"<p>Just a friendly nudge on the quote we shared "
			"(est. value ₹{{ estimated_value }}). Let me know if you'd like "
			"any adjustments before {{ expected_close_date }}.</p>"
		),
	},
	{
		"name": "CRM — Demo scheduling",
		"subject": "Shall we schedule a demo, {{ lead_name }}?",
		"response": (
			"<p>Hi {{ lead_name }},</p>"
			"<p>Would love to walk you through our platform. Could you share a "
			"couple of time windows that work for you this week?</p>"
		),
	},
	{
		"name": "CRM — Re-engagement after silence",
		"subject": "Still interested in working together, {{ lead_name }}?",
		"response": (
			"<p>Hi {{ lead_name }},</p>"
			"<p>I haven't heard back in a while — want to keep the door open. "
			"If now isn't the right time, just let me know and I'll check back "
			"next quarter.</p>"
		),
	},
]


def execute() -> None:
	_seed_sources()
	_seed_statuses()
	_seed_templates()
	_ensure_role("Sales Executive")


def _seed_sources() -> None:
	for name in LEAD_SOURCES:
		if frappe.db.exists("Lead Source", name):
			continue
		frappe.get_doc(
			{
				"doctype": "Lead Source",
				"source_name": name,
				"is_active": 1,
			}
		).insert(ignore_permissions=True)


def _seed_statuses() -> None:
	for status_name, stage, order, terminal in LEAD_STATUSES:
		if frappe.db.exists("Lead Status", status_name):
			continue
		frappe.get_doc(
			{
				"doctype": "Lead Status",
				"status_name": status_name,
				"stage": stage,
				"display_order": order,
				"is_terminal": terminal,
			}
		).insert(ignore_permissions=True)


def _seed_templates() -> None:
	for tpl in EMAIL_TEMPLATES:
		if frappe.db.exists("Email Template", tpl["name"]):
			continue
		frappe.get_doc(
			{
				"doctype": "Email Template",
				"name": tpl["name"],
				"subject": tpl["subject"],
				"response": tpl["response"],
				"use_html": 1,
			}
		).insert(ignore_permissions=True)


def _ensure_role(role_name: str) -> None:
	if frappe.db.exists("Role", role_name):
		return
	frappe.get_doc(
		{
			"doctype": "Role",
			"role_name": role_name,
			"desk_access": 1,
		}
	).insert(ignore_permissions=True)
