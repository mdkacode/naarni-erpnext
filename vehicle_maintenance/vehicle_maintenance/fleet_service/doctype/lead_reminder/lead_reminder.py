"""Lead Reminder — scheduled outbound email/system ping for a Lead."""

import frappe
from frappe import _
from frappe.model.document import Document


class LeadReminder(Document):
	def validate(self) -> None:
		if self.status == "Scheduled" and not (self.message_template or self.message_body):
			frappe.throw(_("Provide either an Email Template or a Message body."))
