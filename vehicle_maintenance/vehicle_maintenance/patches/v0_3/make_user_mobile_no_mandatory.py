"""Make `mobile_no` mandatory + quick-entry on the core User doctype.

Phone is the primary login identifier for staff, so the New User dialog must
show it alongside email and refuse to save without it. Using Property Setters
rather than editing the core DocType keeps us core-safe and idempotent.
"""

import frappe
from frappe.custom.doctype.property_setter.property_setter import make_property_setter


def execute():
	make_property_setter(
		"User",
		"mobile_no",
		"reqd",
		1,
		"Check",
		validate_fields_for_doctype=False,
	)
	make_property_setter(
		"User",
		"mobile_no",
		"in_standard_filter",
		1,
		"Check",
		validate_fields_for_doctype=False,
	)
	make_property_setter(
		"User",
		"mobile_no",
		"in_list_view",
		1,
		"Check",
		validate_fields_for_doctype=False,
	)
	# Quick entry dialog: include mobile_no alongside email and first_name.
	# Frappe uses `in_quick_entry` + the field's reqd flag to pick fields.
	make_property_setter(
		"User",
		"mobile_no",
		"no_copy",
		1,
		"Check",
		validate_fields_for_doctype=False,
	)
	frappe.clear_cache(doctype="User")
