"""Fleet Report Config — per-customer branding, thresholds and recipients for the
monthly KM & SLA reports.

Canonical home for the contract uptime target and the white-label logo/name so the
report works for any customer regardless of whether an optional Service Contract exists.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document

DEFAULT_UPTIME_TARGET = 95.0


class FleetReportConfig(Document):
	def validate(self):
		if not self.display_name:
			self.display_name = (
				frappe.db.get_value("Customer", self.customer, "customer_name") or self.customer
			)
		if self.contract_uptime_target is not None and not (0 <= self.contract_uptime_target <= 100):
			frappe.throw(_("Contract Uptime Target must be between 0 and 100."))
		for row in self.stakeholders or []:
			if row.email and "@" not in row.email:
				frappe.throw(_("Stakeholder email '{0}' is not a valid address.").format(row.email))

	def recipient_emails(self) -> list[str]:
		"""De-duplicated, valid stakeholder email addresses."""
		seen: list[str] = []
		for row in self.stakeholders or []:
			email = (row.email or "").strip()
			if email and "@" in email and email not in seen:
				seen.append(email)
		return seen
