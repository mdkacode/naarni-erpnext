"""Give the platform owner an administrator account on every site this ships to.

**Why a patch and not a console command.** Production runs on Azure behind a
locked-down network: there is no SSH, no `bench` shell, and the only channel
that reaches the live site is a deploy. A role that has to exist in production
therefore has to arrive the same way the code does. The grant is written down,
reviewable in the diff, and applied identically to every environment — which is
better than the alternative it replaces, somebody typing role names into a form
at midnight.

**Why it is safe to leave in the migrate list.** It is keyed on a phone number,
so it grants nothing on a site where that person has no account, and it is
idempotent — a second migrate finds the roles already there and does nothing.
It also never *removes* a role, so an administrator who trims this account's
access later keeps their decision.

`System Manager` is the role that carries User creation. That is the whole
point of the request behind this patch: one person able to set up everybody
else, rather than every new starter needing a deploy.
"""

from __future__ import annotations

import frappe

from vehicle_maintenance.overrides.user import normalize_phone

#: Who runs this platform, by phone. Phone rather than email because that is the
#: identity the app logs in with, and the one that stays put when somebody's
#: email changes — see the login flow.
PLATFORM_ADMINS = (
	"9936142128",  # Mayank
)

#: What "administrator" means here.
#:
#: `System Manager` is the one that matters — it carries User creation, role
#: assignment and the Desk itself. The rest are this app's own roles, granted
#: so the owner sees the same screens the people they are setting up will see;
#: an administrator who cannot open the tab somebody is complaining about is
#: an administrator debugging blind.
ADMIN_ROLES = (
	"System Manager",
	"Depot Manager",
	"Central Ops",
	"Process Author",
	"Process Operator",
	"Process Verifier",
	"Battery Verification Engineer",
	"Material Gate Operator",
	"Material Supervisor",
)


def execute() -> None:
	for phone in PLATFORM_ADMINS:
		_grant(phone)


def _grant(phone: str) -> None:
	name = _find_user(phone)
	if not name:
		# Loud, because a silent miss here looks exactly like a successful run
		# and the person is left wondering why they still cannot open Desk.
		print(f"platform admin: no user with mobile_no {phone} on this site; nothing granted")
		return

	user = frappe.get_doc("User", name)
	changed = False

	if user.user_type != "System User":
		# A Website User silently drops desk roles on save — the trap that made
		# an earlier grant look as though it had worked when it had not.
		user.user_type = "System User"
		changed = True

	if not user.enabled:
		user.enabled = 1
		changed = True

	held = {row.role for row in user.roles}
	for role in ADMIN_ROLES:
		if role in held or not frappe.db.exists("Role", role):
			continue
		user.append("roles", {"role": role})
		changed = True

	if not changed:
		return

	user.flags.ignore_phone_requirement = True
	user.save(ignore_permissions=True)
	frappe.clear_cache(user=user.name)
	frappe.db.commit()  # nosemgrep — after_migrate runs outside a request transaction
	print(f"platform admin: {user.name} ({phone}) now holds {', '.join(ADMIN_ROLES)}")


def _find_user(phone: str) -> str | None:
	"""The account behind a phone number, however it happens to be stored.

	`mobile_no` is normalised to the last ten digits on save, but accounts that
	predate that rule — or that arrived from the Naarni directory — can still
	carry a country code or spacing, and a plain equality match would miss them
	and report the person as absent.
	"""
	digits = normalize_phone(phone)
	exact = frappe.db.get_value("User", {"mobile_no": digits}, "name")
	if exact:
		return exact
	for candidate in frappe.get_all(
		"User", filters={"mobile_no": ["like", f"%{digits}"]}, pluck="name", limit=5
	):
		stored = frappe.db.get_value("User", candidate, "mobile_no") or ""
		if normalize_phone(stored) == digits:
			return candidate
	return None
