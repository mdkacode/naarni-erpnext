"""Seed the default Naarni Teams notification channel.

The webhook URL is read from site_config key `default_teams_webhook` (set via
`bench set-config`, kept OUT of git). When present, this creates/updates a single
default Notification Channel so alerts post to Teams out of the box — Alert Types
with no channel chosen fall back to it. When the config key is absent, this is a
no-op (channels can still be created by hand in the Notification Channel list).

Idempotent: re-running keeps the URL current and ensures it stays the default.
"""

import frappe

CHANNEL_NAME = "Naarni Teams"


def execute() -> None:
	url = (frappe.conf.get("default_teams_webhook") or "").strip()
	if not url:
		return
	if frappe.db.exists("Notification Channel", CHANNEL_NAME):
		doc = frappe.get_doc("Notification Channel", CHANNEL_NAME)
	else:
		doc = frappe.new_doc("Notification Channel")
		doc.channel_name = CHANNEL_NAME
	doc.channel_type = "Teams"
	doc.webhook_url = url
	doc.enabled = 1
	doc.is_default = 1
	doc.save(ignore_permissions=True)
