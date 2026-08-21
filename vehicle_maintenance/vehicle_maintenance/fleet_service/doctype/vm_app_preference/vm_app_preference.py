# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

import frappe
from frappe.model.document import Document


class VMAppPreference(Document):
	pass


def for_user(user: str | None = None):
	"""This person's preferences, creating the row the first time it is asked for.

	Created on read rather than at signup so it cannot be missed by any of the
	several ways an account comes into being — invite, sync, or an admin typing
	one in by hand.
	"""
	user = user or frappe.session.user
	name = frappe.db.get_value("VM App Preference", {"user": user}, "name")
	if name:
		return frappe.get_doc("VM App Preference", name)
	doc = frappe.get_doc({"doctype": "VM App Preference", "user": user})
	doc.insert(ignore_permissions=True)
	return doc
