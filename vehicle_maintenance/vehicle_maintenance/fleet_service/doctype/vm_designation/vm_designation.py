# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

import frappe
from frappe.model.document import Document


class VMDesignation(Document):
	def validate(self):
		self.designation_name = (self.designation_name or "").strip()
		if not self.designation_name:
			frappe.throw(frappe._("A designation needs a name."))
