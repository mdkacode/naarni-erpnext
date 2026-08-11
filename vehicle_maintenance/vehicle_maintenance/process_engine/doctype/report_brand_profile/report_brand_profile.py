"""Report Brand Profile — white-label identity for generated documents.

The brand on a certificate is always a record, never a string in the code. This
controller only enforces that exactly one profile can be the default, so report
generation never has to guess between two.
"""

import frappe
from frappe.model.document import Document


class ReportBrandProfile(Document):
	def validate(self):
		if not self.is_default:
			return
		others = frappe.get_all(
			"Report Brand Profile",
			filters={"is_default": 1, "name": ["!=", self.name]},
			pluck="name",
		)
		for other in others:
			frappe.db.set_value("Report Brand Profile", other, "is_default", 0)
