# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Hand `Group Admin` to the people who already had the power, once.

Creating a chat group used to be open to five operational roles — anybody who
could run a depot could open a room, and the room list grew faster than anyone
could keep track of who was in what. It is now a permission of its own.

Narrowing a permission mid-flight has an obvious failure mode: everybody who
relied on it loses it at the moment of deploy, with no warning and no way to ask
for it back. So the seniormost holders keep it, and everyone else has to be
given it deliberately — which is the point of the change.

Deliberately **once**, tracked by a marker. Re-running would undo an admin who
had taken the role off somebody on purpose.
"""

import frappe

ROLE = "Group Admin"

#: Who keeps it automatically. Not `Service Engineer` or `Depot Manager` — those
#: are the two that made the old rule too wide in the first place.
INHERIT_FROM = ("System Manager", "Central Ops")

MARKER = "vm_group_admin_seeded"


def execute() -> None:
	if frappe.db.get_default(MARKER):
		return
	if not frappe.db.exists("Role", ROLE):
		print(f"seed_group_admin: role {ROLE} does not exist yet; skipping")
		return

	holders = set(
		frappe.get_all(
			"Has Role",
			filters={"role": ["in", INHERIT_FROM], "parenttype": "User"},
			pluck="parent",
			limit_page_length=0,
		)
	)
	already = set(
		frappe.get_all(
			"Has Role",
			filters={"role": ROLE, "parenttype": "User"},
			pluck="parent",
			limit_page_length=0,
		)
	)
	pending = sorted(
		frappe.get_all(
			"User",
			filters={
				"name": ["in", list(holders - already)],
				"enabled": 1,
				"user_type": "System User",
			},
			pluck="name",
			limit_page_length=0,
		)
	)

	granted = 0
	for user in pending:
		try:
			doc = frappe.get_doc("User", user)
			doc.append("roles", {"role": ROLE})
			# `mobile_no` is mandatory on User here; a service account without one
			# must not block a role grant it is not the subject of.
			doc.flags.ignore_phone_requirement = True
			doc.save(ignore_permissions=True)
			granted += 1
		except Exception:
			frappe.log_error(title=f"seed_group_admin: {user}", message=frappe.get_traceback())

	print(f"seed_group_admin: granted {ROLE} to {granted} user(s); assign the rest by hand")
	frappe.db.set_default(MARKER, "1")
