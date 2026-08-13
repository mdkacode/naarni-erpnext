# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Daily Status Settings — one Single holding the whole feature's policy.

Company-wide rather than per-depot on purpose: "who owes a status" and "who reads
it" are org questions, and a depot that wants a different answer is really asking
for a different reporting line, which belongs in roles.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import cint


class DailyStatusSettings(Document):
	def validate(self) -> None:
		if not 0 <= cint(self.day_start_hour) <= 23:
			frappe.throw(_("Day start hour must be between 0 and 23."))
		if cint(self.max_messages) < 5:
			frappe.throw(_("Messages per summary must be at least 5."))

		if self.enabled and not self.reporting_roles:
			frappe.throw(_("Choose at least one role under Who Reports before enabling."))

		if self.digest_enabled and not (self.recipient_roles or self.extra_recipients):
			frappe.msgprint(
				_(
					"The digest has no recipients, so nothing will be emailed. Add a role or a person under The Digest."
				),
				indicator="orange",
				alert=True,
			)

		if self.use_ai:
			from vehicle_maintenance.integrations import onyx_client

			if not onyx_client.is_enabled():
				frappe.msgprint(
					_(
						"ONYX is not enabled, so days will be recorded and emailed in plain form "
						"until it is. Turn it on under <b>Onyx Settings</b>."
					),
					indicator="blue",
					alert=True,
				)

	def on_update(self) -> None:
		frappe.clear_document_cache("Daily Status Settings", "Daily Status Settings")
