"""Alert Type DocType controller.

Naarni-managed catalog row. Customers do not create these; they subscribe via
Alert Subscription. The `alert_id` becomes the engine rule id, so it must be a
stable snake_case token.
"""

import re

import frappe
from frappe import _
from frappe.model.document import Document

ALERT_ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]{1,49}$")
VALID_OPS = {">", ">=", "<", "<=", "==", "!="}


class AlertType(Document):
	def validate(self) -> None:
		self._normalize_alert_id()
		self._validate_alert_id()
		if self.op not in VALID_OPS:
			frappe.throw(_("Operator must be one of: {0}").format(", ".join(sorted(VALID_OPS))))
		if not self.title:
			self.title = self.alert_name

	def _normalize_alert_id(self) -> None:
		if self.alert_id:
			self.alert_id = self.alert_id.strip().lower()

	def _validate_alert_id(self) -> None:
		if not self.alert_id or not ALERT_ID_PATTERN.match(self.alert_id):
			frappe.throw(
				_(
					"Alert ID must be lowercase snake_case, 2-50 chars, starting with a letter (e.g. pack_overheat)."
				)
			)
