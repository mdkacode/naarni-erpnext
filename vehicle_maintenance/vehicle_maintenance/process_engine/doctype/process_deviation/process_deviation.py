"""Process Deviation — the non-conformance record.

Stamps who disposed and who verified, so the disposition path is attributable
rather than a note in a margin.
"""

import frappe
from frappe.model.document import Document


class ProcessDeviation(Document):
	def validate(self):
		before = self.get_doc_before_save()

		if self.disposition and (not before or before.disposition != self.disposition):
			self.disposition_by = frappe.session.user
			self.disposition_at = frappe.utils.now_datetime()

		if self.status == "Verified" and (not before or before.status != "Verified"):
			self.verified_by = frappe.session.user
			self.verified_at = frappe.utils.now_datetime()

	def before_insert(self):
		if not self.raised_by:
			self.raised_by = frappe.session.user
		if not self.raised_at:
			self.raised_at = frappe.utils.now_datetime()
