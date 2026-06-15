"""Telemetry Parameter DocType controller — catalog of silver columns."""

import frappe
from frappe import _
from frappe.model.document import Document


class TelemetryParameter(Document):
	def validate(self) -> None:
		if not self.label:
			self.label = self.parameter_name.replace("_", " ").title()
		if self.data_type in ("Categorical", "Boolean") and not (self.allowed_values or "").strip():
			# Not fatal, but warn — categorical params without values can't show a dropdown.
			frappe.msgprint(
				_("{0} is {1} but has no Allowed Values — the value dropdown will be empty.").format(
					self.parameter_name, self.data_type
				),
				indicator="orange",
			)

	def value_list(self) -> list[str]:
		return [v.strip() for v in (self.allowed_values or "").splitlines() if v.strip()]
