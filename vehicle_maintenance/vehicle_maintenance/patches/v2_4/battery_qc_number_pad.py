# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Battery pack numbers are digits, so raise the digits keypad for them.

The operator types this label while holding a pack, often in gloves, and it is
the one field in the inspection that decides *which battery* everything else is
about. On a full QWERTY keyboard the digits are the small row along the top, and
a mistyped character does not fail — it opens a second inspection of a battery
that does not exist.

**Runs exactly once**, and the marker is what makes that true. Adding the column
backfills every existing row with its schema default of "Text", so there is no
way afterwards to tell "nobody has chosen" from "an admin chose Text" — and a
patch that re-decided this on every migrate would silently overrule them.
"""

import frappe

FAMILY = "BATTERY_QC"

#: Set once this has applied. `after_migrate` runs on every deploy; this is the
#: whole difference between a one-time default and a policy nobody can change.
MARKER = "vm_battery_qc_number_pad_applied"


def execute() -> None:
	if not frappe.db.has_column("Process Definition", "identifier_keypad"):
		# The field arrives with this release's schema sync; on a site part-way
		# through a migrate there is simply nothing to set yet.
		return
	if frappe.db.get_default(MARKER):
		return

	names = frappe.get_all(
		"Process Definition",
		filters={"family": FAMILY},
		fields=["name"],
		limit_page_length=50,
	)
	for row in names:
		frappe.db.set_value(
			"Process Definition", row["name"], "identifier_keypad", "Numbers Only", update_modified=False
		)

	frappe.db.set_default(MARKER, "1")
	if names:
		frappe.clear_cache(doctype="Process Definition")
		print(f"Battery QC: number pad enabled on {len(names)} definition(s)")  # noqa: T201
