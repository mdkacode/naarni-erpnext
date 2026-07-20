"""Service account for the `frappe_km_daily_sync` Airflow DAG.

The DAG (dview-naarni-data-platform, 02:30 IST) upserts `Vehicle KM Daily` rows
over the REST API and needs an API key/secret to do it. Creating that account by
hand on each site is not reproducible and silently leaves fresh environments
without it, so it is seeded here instead (CLAUDE.md §10).

Deliberate choices:

* `user_type = "System User"` — Website Users cannot reach `/api/resource/...`,
  which is the path the DAG's preflight hits. A Website User fails preflight
  with a 403 that reads like a bad credential.
* A real `mobile_no` (see SERVICE_PHONE), NOT a validation bypass. `mobile_no` is
  mandatory here because humans authenticate by phone, and it is enforced twice —
  `overrides.user.validate_user`, plus a Property Setter `reqd=1` from
  `patches.v0_3.make_user_mobile_no_mandatory`. Bypass flags clear both only for
  the save that sets them; the resulting row is then unsaveable by everything
  else, including frappe's own `generate_keys()`. Giving the account a sentinel
  number keeps it an ordinary, editable User.
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

# Reserved sentinel phone for this service account.
#
# Do NOT be tempted to bypass the mobile_no requirement with
# flags.ignore_phone_requirement / ignore_mandatory instead: those only apply to
# saves we control. A User row stored with an empty mobile_no cannot be saved by
# anyone else either — frappe's own generate_keys() calls user_details.save(),
# which re-runs validate_user and throws "Phone number is mandatory". That makes
# the account impossible to issue API keys for, which is its entire purpose.
#
# Ten digits (normalize_phone requires >= 10 and keeps the last 10), unique, and
# not a dialable Indian mobile — real numbers never start with 0.
SERVICE_PHONE = "0000000001"


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
				"mobile_no": SERVICE_PHONE,
				"send_welcome_email": 0,
				"enabled": 1,
			}
		)
		user.append("roles", {"role": ROLE})
		user.insert(ignore_permissions=True)
	else:
		# Repair an account seeded by the earlier revision, which bypassed the
		# mobile_no gate and so left the row unsaveable by anything else.
		# db.set_value writes straight to the table — a .save() here would trip
		# the very validation we are repairing.
		if not frappe.db.get_value("User", EMAIL, "mobile_no"):
			frappe.db.set_value("User", EMAIL, "mobile_no", SERVICE_PHONE)

		if not frappe.db.exists("Has Role", {"parent": EMAIL, "role": ROLE}):
			user = frappe.get_doc("User", EMAIL)
			user.append("roles", {"role": ROLE})
			user.save(ignore_permissions=True)

	# add_permission is a no-op when the rule already exists.
	add_permission(DOCTYPE, ROLE, 0)
	for perm in ("read", "write", "create"):
		update_permission_property(DOCTYPE, ROLE, 0, perm, 1)

	frappe.db.commit()
