"""Lead Activity — child table row on Lead."""

import frappe
from frappe import _
from frappe.model.document import Document

SUMMARY_MAX = 500


class LeadActivity(Document):
	def validate(self) -> None:
		if self.summary and len(self.summary) > SUMMARY_MAX:
			frappe.throw(_("Activity summary must be {0} characters or fewer.").format(SUMMARY_MAX))
