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
CATEGORICAL_OPS = {"==", "!="}


class AlertType(Document):
	def validate(self) -> None:
		self._normalize_alert_id()
		self._validate_alert_id()
		if self.op not in VALID_OPS:
			frappe.throw(_("Operator must be one of: {0}").format(", ".join(sorted(VALID_OPS))))
		if not self.title:
			self.title = self.alert_name
		self._validate_rule()

	def _validate_rule(self) -> None:
		"""Numeric params need a threshold + numeric operator; Categorical/Boolean
		params need a match value + == / != operator."""
		ptype = frappe.db.get_value("Telemetry Parameter", self.parameter, "data_type") or "Numeric"
		if ptype in ("Categorical", "Boolean"):
			if self.op not in CATEGORICAL_OPS:
				frappe.throw(_("{0} is {1} — operator must be == or !=.").format(self.parameter, ptype))
			if not (self.match_value or "").strip():
				frappe.throw(_("Set a Match Value for the {0} parameter {1}.").format(ptype, self.parameter))
			self.default_threshold = 0
		else:
			if self.default_threshold is None:
				frappe.throw(
					_("Set a Default Threshold for the numeric parameter {0}.").format(self.parameter)
				)
			self.match_value = None

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
