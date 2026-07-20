"""Service account for the `frappe_km_daily_sync` Airflow DAG.

The DAG (dview-naarni-data-platform, 02:30 IST) upserts `Vehicle KM Daily` rows
over the REST API and needs an API key/secret to do it. Creating that account by
hand on each site is not reproducible and silently leaves fresh environments
without it, so it is seeded here instead (CLAUDE.md §10).

Deliberate choices:

* `user_type = "System User"` — Website Users cannot reach `/api/resource/...`,
  which is the path the DAG's preflight hits. A Website User fails preflight
  with a 403 that reads like a bad credential.
* `flags.ignore_phone_requirement` — `overrides.user.validate_user` makes
  `mobile_no` mandatory because humans here authenticate by phone. This account
  never logs in interactively, so the flag (added for exactly this case) applies.
* No password is set, so the account is API-token-only.
* Read/write/create but **not delete**: the DAG never deletes (it upserts on
  `row_key`), so granting delete would only widen the blast radius of a leaked
  Airflow Variable.

The API key/secret are NOT created here — `generate_keys` shows the secret once
and it has to be copied into Airflow by hand:

    bench --site <site> execute \
        frappe.core.doctype.user.user.generate_keys \
        --args "['airflow-km@naarni.com']"
"""

import frappe
from frappe.permissions import add_permission, update_permission_property

EMAIL = "airflow-km@naarni.com"
ROLE = "KM Sync Bot"
DOCTYPE = "Vehicle KM Daily"


def execute() -> None:
	# Seeding a service account must never take a deploy down: `after_migrate`
	# failures abort `bench migrate` and fail the pipeline. Log and move on.
	try:
		_seed()
	except Exception:
		frappe.log_error(title=f"seed_km_sync_service_user: could not seed {EMAIL}")


def _seed() -> None:
	if not frappe.db.table_exists(DOCTYPE):
		# KM reports not installed on this site yet; the DAG has nothing to write to.
		return

	if not frappe.db.exists("Role", ROLE):
		frappe.get_doc(
			{
				"doctype": "Role",
				"role_name": ROLE,
				# Required for REST access to /api/resource on a System User.
				"desk_access": 1,
			}
		).insert(ignore_permissions=True)

	if not frappe.db.exists("User", EMAIL):
		user = frappe.get_doc(
			{
				"doctype": "User",
				"email": EMAIL,
				"first_name": "Airflow KM Sync",
				"user_type": "System User",
				"send_welcome_email": 0,
				"enabled": 1,
			}
		)
		user.append("roles", {"role": ROLE})
		# `mobile_no` is gated TWICE and both have to be cleared:
		#   1. overrides.user.validate_user  -> flags.ignore_phone_requirement
		#   2. a Property Setter reqd=1 from patches.v0_3.make_user_mobile_no_mandatory,
		#      enforced by the framework's _validate_mandatory() -> ignore_mandatory
		# Clearing only (1) still raises MandatoryError. See module docstring.
		user.flags.ignore_phone_requirement = True
		user.insert(ignore_permissions=True, ignore_mandatory=True)
	elif not frappe.db.exists("Has Role", {"parent": EMAIL, "role": ROLE}):
		# User pre-created by hand (e.g. during the initial rollout) — attach the role.
		user = frappe.get_doc("User", EMAIL)
		user.append("roles", {"role": ROLE})
		user.flags.ignore_phone_requirement = True
		user.flags.ignore_mandatory = True
		user.save(ignore_permissions=True)

	# add_permission is a no-op when the rule already exists.
	add_permission(DOCTYPE, ROLE, 0)
	for perm in ("read", "write", "create"):
		update_permission_property(DOCTYPE, ROLE, 0, perm, 1)

	frappe.db.commit()
