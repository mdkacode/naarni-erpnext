"""Idempotent seeder: Custom Fields that back the Naarni integration.

`naarni_user_uuid` on the core User doctype links a Frappe staff user to their
Naarni account UUID (the JWT `sub`). We add it as a Custom Field rather than
editing core (Golden Rule), and via create_custom_fields so it is reproducible
on every `bench migrate`.
"""

import frappe
from frappe.custom.doctype.custom_field.custom_field import create_custom_fields


def execute() -> None:
	create_custom_fields(
		{
			"User": [
				{
					"fieldname": "naarni_user_uuid",
					"label": "Naarni User UUID",
					"fieldtype": "Data",
					"read_only": 1,
					"no_copy": 1,
					"insert_after": "mobile_no",
					"description": "Account UUID in the Naarni backend (api.naarni.com), set on OTP login.",
				}
			]
		},
		ignore_validate=True,
	)
	frappe.db.commit()
