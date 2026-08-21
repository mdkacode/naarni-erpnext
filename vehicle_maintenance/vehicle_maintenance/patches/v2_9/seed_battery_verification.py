"""Give the battery line a verification engineer, and a plant to belong to.

Battery Assembly QC has asked for a second signoff on its Installation stage
since the day it was published — `requires_second_signoff` is set, so every
finished installation goes to *Awaiting Verification* and waits. What it has
never had is anybody able to give that signoff from a phone: the endpoint
exists, the app's repository even has a method for it, and nothing calls
either. So the packs wait.

This patch does three things and nothing else:

**A role.** `Battery Verification Engineer`, which the Installation stage now
names instead of the generic `Process Verifier`. Everybody who holds
`Process Verifier` today is granted the new role, so nobody loses access on
the migrate that introduces it.

**A plant on people.** One custom field on User. `Material Location` is already
the company's plant master — Hubli and Narsapura, seeded with the material
gate — and inventing a second one is how the same plant ends up under two
names.

**A plant on the runs that already exist.** Every Battery QC run recorded so
far was carried out at Narsapura, because that is the only place the process
runs. Back-filled rather than left blank, because a verification queue scoped
by plant would otherwise show an empty list over a real backlog.

Idempotent throughout: it grants what is missing and changes what still says
what it said when it was seeded, and does nothing at all on the second run.
"""

from __future__ import annotations

import frappe

from vehicle_maintenance.process_engine import constants as C

ROLE = "Battery Verification Engineer"

FAMILY = "BATTERY_QC"
STAGE = "INSTALLATION"

#: The one plant the battery process runs at today. Used to back-fill existing
#: runs and to set the plant on the first engineers — both are statements of
#: fact about the line as it stands, not defaults for the future: a run started
#: after this patch is stamped from the operator's own profile.
PLANT = "NARSAPURA"

USER_PLANT_FIELD = "vm_plant"


def execute() -> None:
	_seed_role()
	_seed_user_plant_field()
	_point_the_stage_at_the_new_role()
	_carry_over_existing_verifiers()
	_backfill_run_plant()


def _seed_role() -> None:
	if frappe.db.exists("Role", ROLE):
		return
	frappe.get_doc(
		{
			"doctype": "Role",
			"role_name": ROLE,
			"desk_access": 1,
			# Not restricted to a desk user: this role is held by people who work
			# on a phone, and a Website User silently drops desk roles on save.
			"is_custom": 0,
		}
	).insert(ignore_permissions=True)
	print(f"battery verification: created role {ROLE}")


def _seed_user_plant_field() -> None:
	"""Which plant somebody works at. One link, on the record that already exists."""
	if frappe.db.exists("Custom Field", {"dt": "User", "fieldname": USER_PLANT_FIELD}):
		return
	frappe.get_doc(
		{
			"doctype": "Custom Field",
			"dt": "User",
			"fieldname": USER_PLANT_FIELD,
			"label": "Plant",
			"fieldtype": "Link",
			"options": "Material Location",
			"insert_after": "location",
			"description": (
				"Where this person works. Stamped onto the runs they start, and what "
				"scopes a verifier to their own site's queue."
			),
		}
	).insert(ignore_permissions=True)
	print(f"battery verification: added User.{USER_PLANT_FIELD}")


def _point_the_stage_at_the_new_role() -> None:
	"""Name the new role on the stage that already asks for a second signoff.

	Edited in place rather than authored as a new version. Who may sign a stage
	off is access control, not process content: it is not part of what the
	certificate asserts, and a new version would strand every in-flight run on
	the old one — including the ones sitting in the queue this patch exists to
	drain.

	Guarded on the value it is replacing, so a plant that has since chosen a
	different role keeps their choice.
	"""
	for name in frappe.get_all(
		"Process Definition", filters={"family": FAMILY, "status": C.DEF_PUBLISHED}, pluck="name"
	):
		row = frappe.db.get_value(
			"Process Stage",
			{"parent": name, "stage_code": STAGE},
			["name", "second_signoff_role", "requires_second_signoff"],
			as_dict=True,
		)
		if not row or not row.requires_second_signoff:
			continue
		if row.second_signoff_role not in (None, "", C.ROLE_VERIFIER):
			continue
		frappe.db.set_value("Process Stage", row.name, "second_signoff_role", ROLE)
		frappe.clear_cache(doctype="Process Definition")
		frappe.cache().delete_key(f"process_engine:def:{name}")
		print(f"battery verification: {name}/{STAGE} now signed off by {ROLE}")


def _carry_over_existing_verifiers() -> None:
	"""Nobody loses access on the migrate that renames who has it."""
	holders = frappe.get_all("Has Role", filters={"role": C.ROLE_VERIFIER}, pluck="parent")
	granted = 0
	for user in set(holders):
		if not frappe.db.exists("User", user):
			continue
		doc = frappe.get_doc("User", user)
		if doc.user_type != "System User":
			continue
		if any(r.role == ROLE for r in doc.roles):
			continue
		doc.append("roles", {"role": ROLE})
		if not doc.get(USER_PLANT_FIELD):
			# The only plant the process runs at. Wrong for nobody today, and
			# an administrator changes it on the User record the day it is.
			doc.set(USER_PLANT_FIELD, PLANT if frappe.db.exists("Material Location", PLANT) else None)
		doc.save(ignore_permissions=True)
		frappe.clear_cache(user=user)
		granted += 1
	if granted:
		print(f"battery verification: granted {ROLE} to {granted} existing verifier(s)")


def _backfill_run_plant() -> None:
	"""Existing battery runs belong to Narsapura, because that is where they happened."""
	if not frappe.db.exists("Material Location", PLANT):
		return
	definitions = frappe.get_all("Process Definition", filters={"family": FAMILY}, pluck="name")
	if not definitions:
		return
	stale = frappe.get_all(
		"Process Run",
		filters={"process_definition": ["in", definitions], "plant": ["in", ["", None]]},
		pluck="name",
	)
	for name in stale:
		frappe.db.set_value("Process Run", name, "plant", PLANT, update_modified=False)
	if stale:
		frappe.db.commit()  # nosemgrep — after_migrate runs outside a request transaction
		print(f"battery verification: stamped {len(stale)} existing run(s) as {PLANT}")
