# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Seed the designation picklist, and back-fill invites for everyone already here.

The back-fill is the part that matters. Turning on invite-only would otherwise
mean that anybody whose account somehow needed re-provisioning could not get back
in, and it leaves no record of the people who joined before there was a gate.
Writing an Accepted invite per existing user makes the list of who has access
complete from day one, which is the whole point of having it.

Idempotent, and safe to run on every migrate: designations are created only when
absent, and the back-fill skips anyone who already has an invite row.
"""

import frappe
from frappe.utils import now_datetime

from vehicle_maintenance.overrides.user import normalize_phone

# Ordered the way a depot would read them rather than alphabetically — the point
# of the sort_order field is that the common answers are near the top of a
# picker on a phone.
DESIGNATIONS = [
	("Technician", 10),
	("Senior Technician", 20),
	("Service Engineer", 30),
	("Depot Manager", 40),
	("Ops Manager", 50),
	("Store Keeper", 60),
	("Driver", 70),
	("Quality Inspector", 80),
	("Electrician", 90),
	("Supervisor", 100),
	("Trainee", 110),
	("Other", 999),
]


def execute() -> None:
	_seed_designations()
	_backfill_invites()


def _seed_designations() -> None:
	for label, order in DESIGNATIONS:
		if frappe.db.exists("VM Designation", label):
			continue
		try:
			frappe.get_doc(
				{
					"doctype": "VM Designation",
					"designation_name": label,
					"sort_order": order,
					"is_active": 1,
				}
			).insert(ignore_permissions=True)
		except Exception:
			frappe.log_error(title=f"Designation seed failed ({label})", message=frappe.get_traceback())


def _backfill_invites() -> None:
	"""One Accepted invite per existing account, so nobody is locked out later."""
	users = frappe.get_all(
		"User",
		filters={"enabled": 1, "user_type": "System User"},
		fields=["name", "full_name", "mobile_no"],
		limit_page_length=0,
	)
	made = 0
	for user in users:
		phone = (user.get("mobile_no") or "").strip()
		if not phone:
			continue
		try:
			phone = normalize_phone(phone)
		except Exception:
			# A number the login path could never match anyway. Leaving it alone
			# is honest: inventing a normalised form here would create an invite
			# that does not correspond to any possible login.
			continue
		if frappe.db.exists("VM User Invite", {"phone": phone}):
			continue
		try:
			doc = frappe.get_doc(
				{
					"doctype": "VM User Invite",
					"phone": phone,
					"full_name": (user.get("full_name") or phone).strip(),
					"status": "Pending",
					"notes": "Back-filled: had access before invites existed.",
				}
			)
			# No welcome email for somebody who has been using the app for months.
			doc.flags.ignore_permissions = True
			doc.insert(ignore_permissions=True)
			doc.db_set(
				{"status": "Accepted", "user": user["name"], "accepted_at": now_datetime()},
				update_modified=False,
			)
			made += 1
		except Exception:
			frappe.log_error(
				title=f"Invite back-fill failed ({user['name']})", message=frappe.get_traceback()
			)
	if made:
		print(f"[onboarding] back-filled {made} invite(s) for existing users")
