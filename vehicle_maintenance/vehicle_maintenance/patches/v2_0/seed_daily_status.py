# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Seed sensible defaults for the daily status feature. Idempotent.

Runs after every migrate. It only ever fills in a Single that has never been
configured — an admin's choices are never overwritten, and the feature stays
**off** until someone deliberately enables it, because the first thing it does
when enabled is start messaging every technician on the floor.
"""

from __future__ import annotations

import frappe

# Who is asked for a status out of the box. Deliberately the roles that do the
# work, not the ones that read the report.
DEFAULT_REPORTING_ROLES = ("Technician", "Service Engineer", "Aftersales Eng")

# Who reads it. Central Ops and System Manager see every depot by construction
# (see daily_status_digest.GLOBAL_RECIPIENT_ROLES); Depot Manager sees theirs.
DEFAULT_RECIPIENT_ROLES = ("Depot Manager", "Central Ops", "N. Maintenance Head")


def execute() -> None:
	_seed_onyx()
	_seed_azure()
	_seed_daily_status()


def _seed_azure() -> None:
	"""Point Azure AI at the same resource ONYX uses. Left disabled — no key in git."""
	if not frappe.db.exists("DocType", "Azure AI Settings"):
		return
	doc = frappe.get_single("Azure AI Settings")
	if doc.endpoint:
		return
	doc.endpoint = "https://naanri.openai.azure.com"
	doc.deployment = "gpt-4.1-mini"
	doc.api_version = "2025-03-01-preview"
	doc.timeout_seconds = 45
	doc.max_tokens = 1200
	doc.enabled = 0
	doc.flags.ignore_permissions = True
	doc.save()


def _seed_onyx() -> None:
	"""Point ONYX at our deployment. Left disabled — the API key is not in git."""
	if not frappe.db.exists("DocType", "Onyx Settings"):
		return
	doc = frappe.get_single("Onyx Settings")
	if doc.base_url:
		return
	doc.base_url = "https://ai.naarni.com"
	doc.api_prefix = "/api"
	doc.persona_id = 0
	doc.timeout_seconds = 45
	doc.enabled = 0
	doc.flags.ignore_permissions = True
	doc.save()


def _seed_daily_status() -> None:
	if not frappe.db.exists("DocType", "Daily Status Settings"):
		return
	doc = frappe.get_single("Daily Status Settings")
	if doc.reporting_roles or doc.recipient_roles:
		return  # already configured by a human; leave it alone

	for role in DEFAULT_REPORTING_ROLES:
		if frappe.db.exists("Role", role):
			doc.append("reporting_roles", {"role": role})
	for role in DEFAULT_RECIPIENT_ROLES:
		if frappe.db.exists("Role", role):
			doc.append("recipient_roles", {"role": role})

	doc.enabled = 0
	doc.flags.ignore_permissions = True
	doc.save()
