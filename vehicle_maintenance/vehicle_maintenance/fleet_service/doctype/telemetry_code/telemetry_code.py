"""Telemetry Code: maps a coded telemetry value to its human meaning.

The engine reads these via get_engine_config and shows the meaning in alerts (e.g.
vcu_fault_code 142 -> 'Motor over-temperature'). The name is parameter-code so a
CSV re-import updates the same row instead of duplicating it.
"""

import frappe
from frappe import _
from frappe.model.document import Document


class TelemetryCode(Document):
	def validate(self) -> None:
		# Normalise the code (trim) so "142 " and "142" don't become two rows.
		if self.code is not None:
			self.code = str(self.code).strip()
		if not self.code:
			frappe.throw(_("Code is required."))
