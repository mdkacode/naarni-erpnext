"""Seed the 2-level internal-checker workflow for the Monthly KM Report.

A KM Report Snapshot is generated in `Pending Check 1`; a KM Checker L1 approves it
to `Pending Check 2`; a KM Checker L2 approves it to `Approved`, which is what
releases the white-labelled customer email (see monthly_km_report.send_on_approval).
Either checker can Reject. No email ever leaves before both approvals.

Idempotent + defensive: every write is guarded and any error is logged and
swallowed, so a workflow-schema difference on a Frappe version can never abort
`bench migrate` (same contract as seed_fleet_workspace).
"""

import frappe

WORKFLOW = "KM Report Approval"
DOCTYPE = "KM Report Snapshot"
ROLE_L1 = "KM Checker L1"
ROLE_L2 = "KM Checker L2"
# Who may move a freshly-generated / rejected report back into review.
ROLE_OPS = "Central Ops"

STATES = [
	# (state, style, allow_edit_role)
	("Pending Check 1", "Warning", ROLE_OPS),
	("Pending Check 2", "Warning", ROLE_L1),
	("Approved", "Success", ROLE_L2),
	("Rejected", "Danger", ROLE_OPS),
]

TRANSITIONS = [
	# (from, action, to, allowed_role)
	("Pending Check 1", "Approve (L1)", "Pending Check 2", ROLE_L1),
	("Pending Check 1", "Reject", "Rejected", ROLE_L1),
	("Pending Check 2", "Approve & Send (L2)", "Approved", ROLE_L2),
	("Pending Check 2", "Reject", "Rejected", ROLE_L2),
]


def execute() -> None:
	try:
		_seed()
	except Exception:
		frappe.log_error(title="seed_km_report_workflow: could not seed KM Report Approval workflow")


def _seed() -> None:
	if not frappe.db.exists("DocType", DOCTYPE):
		return

	for role in (ROLE_L1, ROLE_L2):
		if not frappe.db.exists("Role", role):
			# desk_access so a checker can open the report in Desk and act on it.
			frappe.get_doc({"doctype": "Role", "role_name": role, "desk_access": 1}).insert(
				ignore_permissions=True
			)

	for state, style, _role in STATES:
		if not frappe.db.exists("Workflow State", state):
			frappe.get_doc(
				{"doctype": "Workflow State", "workflow_state_name": state, "style": style}
			).insert(ignore_permissions=True)

	for _from, action, _to, _role in TRANSITIONS:
		if not frappe.db.exists("Workflow Action Master", action):
			frappe.get_doc({"doctype": "Workflow Action Master", "workflow_action_name": action}).insert(
				ignore_permissions=True
			)

	# Grant the two checker roles read/write on the snapshot so they can review it.
	from frappe.permissions import add_permission, update_permission_property

	for role in (ROLE_L1, ROLE_L2):
		add_permission(DOCTYPE, role, 0)
		for perm in ("read", "write"):
			update_permission_property(DOCTYPE, role, 0, perm, 1)

	if not frappe.db.exists("Workflow", WORKFLOW):
		wf = frappe.get_doc(
			{
				"doctype": "Workflow",
				"workflow_name": WORKFLOW,
				"document_type": DOCTYPE,
				"is_active": 1,
				"override_status": 0,  # we keep `status` for delivery; workflow drives `workflow_state`
				"workflow_state_field": "workflow_state",
				"states": [
					{"state": s, "doc_status": "0", "allow_edit": role, "style": style}
					for s, style, role in STATES
				],
				"transitions": [
					{"state": f, "action": a, "next_state": t, "allowed": role, "allow_self_approval": 1}
					for f, a, t, role in TRANSITIONS
				],
			}
		)
		wf.insert(ignore_permissions=True)

	frappe.db.commit()
