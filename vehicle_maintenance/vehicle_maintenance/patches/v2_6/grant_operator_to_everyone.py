# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Give every existing system user `Process Operator`, once.

An engineer reported `does not have access to this document` when an inspection
photo tried to upload. Frappe's `upload_file` checks **write** on Process Run
before attaching anything, and that permission comes from this role. Without it
somebody can walk a whole pack, answer every check and photograph every
terminal, and have none of it reach the server — silently, because the handset
shows the work saved, which it is, locally.

The hook in `overrides/user.py` covers every user who is saved from now on.
This is the backfill for the accounts that already exist and may never be
touched again.

Runs on every migrate rather than once, and does nothing when there is nothing
to do. There is no marker to get out of step with reality, and no window in
which an account created by some path that skipped the hook stays unable to
work.

System users only. Not the `All` role, which would have been a one-line change
to the doctype: `All` includes website users, so every customer portal account
would have been handed write access to inspection records.
"""

import frappe

OPERATOR_ROLE = "Process Operator"


def execute() -> None:
	if not frappe.db.exists("Role", OPERATOR_ROLE):
		print(f"grant_operator_to_everyone: role {OPERATOR_ROLE} does not exist; skipping")
		return

	already = set(
		frappe.get_all(
			"Has Role",
			filters={"role": OPERATOR_ROLE, "parenttype": "User"},
			pluck="parent",
			limit_page_length=0,
		)
	)
	candidates = frappe.get_all(
		"User",
		filters={
			"enabled": 1,
			"user_type": "System User",
			"name": ["not in", ["Administrator", "Guest"]],
		},
		pluck="name",
		limit_page_length=0,
	)
	pending = sorted(set(candidates) - already)

	if not pending:
		return

	granted = 0
	for user in pending:
		try:
			doc = frappe.get_doc("User", user)
			doc.append("roles", {"role": OPERATOR_ROLE})
			# This site makes `mobile_no` mandatory on User; a service account
			# without one must not block a role grant it is not the subject of.
			doc.flags.ignore_phone_requirement = True
			doc.save(ignore_permissions=True)
			granted += 1
		except Exception:
			# One bad user row must not stop the rest getting the role, and must
			# not take a migration down with it.
			frappe.log_error(title=f"grant_operator_to_everyone: {user}", message=frappe.get_traceback())

	print(f"grant_operator_to_everyone: granted {OPERATOR_ROLE} to {granted} user(s)")
