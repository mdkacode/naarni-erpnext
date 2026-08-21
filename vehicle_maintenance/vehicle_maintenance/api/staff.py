# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Adding a member of staff, and nothing else.

Deliberately not the Frappe User form. That form asks thirty questions to
create somebody who needs four answers, and the two that actually matter here —
which role, and which depots — are buried among settings nobody at a depot has
an opinion about. Getting a new engineer working should take a phone number, a
name and a role.

**Login is by phone.** `auth.py` finds an account by `mobile_no`, so that field
is the identity as far as signing in is concerned and the email is a contact
detail. An account created here can sign in with OTP immediately; there is no
invite to accept and nothing to activate.

**Roles are an allow-list, never free text.** `System Manager` is not on it and
cannot be assigned through this endpoint at any privilege level — an
account-creation screen that can mint an administrator is a privilege-escalation
hole wearing a form.
"""

import frappe
from frappe import _

from vehicle_maintenance.overrides.user import normalize_phone

#: Who may add somebody. Depot Manager is included so the app screen is usable
#: by the person who actually notices a new engineer has turned up.
CAN_ADD_STAFF = ("System Manager", "Central Ops", "Depot Manager")

#: Roles this endpoint may grant, and whether depot access is part of the job.
#:
#: An explicit list rather than "every role this app declares", because the two
#: are not the same question: what a role *is* and what an admin screen may hand
#: out are different decisions, and conflating them means every new role becomes
#: assignable by accident.
ASSIGNABLE_ROLES: dict[str, dict] = {
	"Service Engineer": {"label": "Service Engineer", "depots": True},
	"Technician": {"label": "Technician", "depots": False},
	"Depot Manager": {"label": "Depot Manager", "depots": True},
	"Battery QA Admin": {"label": "Battery QA Admin", "depots": False},
	"Process Operator": {"label": "Process Operator", "depots": False},
	"Process Verifier": {"label": "Process Verifier", "depots": False},
	"Material Gate Operator": {"label": "Material Gate Operator", "depots": False},
	"Material Supervisor": {"label": "Material Supervisor", "depots": False},
	"Aftersales Eng": {"label": "Aftersales Engineer", "depots": False},
	"Central Ops": {"label": "Central Ops", "depots": False},
	"Group Admin": {"label": "Group Admin", "depots": False},
	"Fleet Reports": {"label": "Fleet Reports (view only)", "depots": False},
	"Sales Executive": {"label": "Sales Executive", "depots": False},
}

#: Roles that carry real reach, so only a System Manager may hand them out.
ELEVATED_ROLES = ("Central Ops", "Group Admin", "Battery QA Admin", "Depot Manager")


def _guard() -> None:
	frappe.only_for(CAN_ADD_STAFF)


@frappe.whitelist()
def assignable_roles() -> dict:
	"""The roles the caller may assign, in the order they should be offered.

	Returns: {success, data: {roles: [{name, label, depots, elevated}]}}.
	"""
	_guard()
	senior = "System Manager" in frappe.get_roles(frappe.session.user)
	rows = [
		{
			"name": name,
			"label": meta["label"],
			"depots": meta["depots"],
			"elevated": name in ELEVATED_ROLES,
		}
		for name, meta in ASSIGNABLE_ROLES.items()
		if senior or name not in ELEVATED_ROLES
	]
	return {"success": True, "data": {"roles": rows}}


@frappe.whitelist()
def list_depots(query: str = "", limit: int = 50) -> dict:
	"""Depots for the multi-select. Returns: {success, data: {depots: [...]}}."""
	_guard()
	filters = {}
	if (query or "").strip():
		filters["depot_name"] = ["like", f"%{query.strip()}%"]
	depots = frappe.get_all(
		"Depot",
		filters=filters,
		fields=["name", "depot_name", "city", "state"],
		order_by="depot_name asc",
		limit_page_length=min(int(limit or 50), 200),
	)
	return {"success": True, "data": {"depots": depots}}


@frappe.whitelist()
def list_staff(query: str = "", limit: int = 50) -> dict:
	"""Staff accounts, newest first, for the list beside the form.

	Explicit field list, and no password or session data — this is a directory,
	not an export of the User table.

	Returns: {success, data: {staff: [{name, full_name, mobile_no, roles, depots}]}}.
	"""
	_guard()
	filters = {"enabled": 1, "user_type": "System User", "name": ["not in", ["Administrator", "Guest"]]}
	term = (query or "").strip()
	or_filters = None
	if term:
		or_filters = {"full_name": ["like", f"%{term}%"], "mobile_no": ["like", f"%{term}%"]}

	users = frappe.get_all(
		"User",
		filters=filters,
		or_filters=or_filters,
		fields=["name", "full_name", "mobile_no", "creation"],
		order_by="creation desc",
		limit_page_length=min(int(limit or 50), 200),
	)
	if not users:
		return {"success": True, "data": {"staff": []}}

	names = [u["name"] for u in users]
	held: dict[str, list[str]] = {}
	for row in frappe.get_all(
		"Has Role",
		filters={"parent": ["in", names], "parenttype": "User", "role": ["in", list(ASSIGNABLE_ROLES)]},
		fields=["parent", "role"],
		limit_page_length=0,
	):
		held.setdefault(row["parent"], []).append(row["role"])

	# One query for every depot assignment on the page rather than one per row.
	depots: dict[str, list[str]] = {}
	for row in frappe.get_all(
		"Depot Engineer",
		filters={"user": ["in", names], "parenttype": "Depot"},
		fields=["parent", "user"],
		limit_page_length=0,
	):
		depots.setdefault(row["user"], []).append(row["parent"])

	return {
		"success": True,
		"data": {
			"staff": [
				{
					"name": u["name"],
					"full_name": u["full_name"] or u["name"],
					"mobile_no": u["mobile_no"],
					"roles": sorted(held.get(u["name"], [])),
					"depots": sorted(depots.get(u["name"], [])),
					"created_at": str(u["creation"]),
				}
				for u in users
			]
		},
	}


@frappe.whitelist()
def create_staff(
	phone: str,
	full_name: str,
	role: str,
	email: str | None = None,
	depots: str | list | None = None,
) -> dict:
	"""Create a staff account that can sign in immediately.

	`phone`, `full_name` and `role` are required. `email` is optional and is
	only a contact detail — the account is keyed on the phone number either way,
	because that is what the OTP flow looks up.

	`depots` is a JSON list of Depot names, and only means anything for a role
	whose work is depot-scoped. Access is stored where this app already keeps it:
	as a `Depot Engineer` row on each Depot, which is what `get_my_depots` reads.

	Returns: {success, data: {user, phone, role, depots}}.
	"""
	_guard()

	full_name = (full_name or "").strip()
	role = (role or "").strip()
	email = (email or "").strip() or None
	if not full_name:
		frappe.throw(_("Name is required."))
	if not role:
		frappe.throw(_("Role is required."))

	meta = ASSIGNABLE_ROLES.get(role)
	if not meta:
		frappe.throw(_("{0} cannot be assigned from here.").format(role))
	if role in ELEVATED_ROLES and "System Manager" not in frappe.get_roles(frappe.session.user):
		frappe.throw(_("Only a System Manager can assign {0}.").format(role), frappe.PermissionError)

	# Throws on anything shorter than ten digits, and canonicalises the rest, so
	# '+91 98765 43210' and '9876543210' cannot become two accounts.
	normalized = normalize_phone(phone)

	existing = frappe.db.get_value(
		"User", {"mobile_no": normalized}, ["name", "full_name", "enabled"], as_dict=True
	)
	if existing:
		frappe.throw(_("{0} already belongs to {1}.").format(normalized, existing.full_name or existing.name))

	chosen = _clean_depots(depots) if meta["depots"] else []

	doc = frappe.new_doc("User")
	# A real address when one is given, and `auth.py`'s phone-shaped convention
	# otherwise. Either is safe: sign-in resolves the account by `mobile_no`, not
	# by this, so the email is a contact detail that happens to also be the
	# docname. Both shapes already exist on this site.
	doc.email = email or f"{normalized}@naarni.phone"
	doc.first_name = full_name
	doc.mobile_no = normalized
	doc.user_type = "System User"
	doc.send_welcome_email = 0
	doc.append("roles", {"role": role})
	doc.flags.ignore_permissions = True
	doc.insert(ignore_permissions=True)

	for depot in chosen:
		_grant_depot(depot, doc.name)

	frappe.msgprint(_("{0} can sign in now with {1}.").format(full_name, normalized), alert=True)
	return {
		"success": True,
		"data": {"user": doc.name, "phone": normalized, "role": role, "depots": chosen},
	}


def _clean_depots(depots) -> list[str]:
	"""Normalise the depot list and reject anything that is not a real depot."""
	from vehicle_maintenance.api.chat import _as_list

	wanted = [d for d in dict.fromkeys(_as_list(depots, "depots")) if (d or "").strip()]
	if not wanted:
		return []
	real = set(frappe.get_all("Depot", filters={"name": ["in", wanted]}, pluck="name", limit_page_length=0))
	missing = [d for d in wanted if d not in real]
	if missing:
		frappe.throw(_("No such depot: {0}").format(", ".join(missing)))
	return [d for d in wanted if d in real]


def _grant_depot(depot: str, user: str) -> None:
	"""Add the user to a depot's engineer list, without duplicating a row.

	Written on the Depot rather than the User because that is where this app
	already keeps it — `get_my_depots` reads `Depot Engineer` rows — and a second
	home for the same fact is how the two start disagreeing.
	"""
	doc = frappe.get_doc("Depot", depot)
	if any((row.user or "") == user for row in (doc.get("service_engineers") or [])):
		return
	doc.append("service_engineers", {"user": user})
	doc.save(ignore_permissions=True)
