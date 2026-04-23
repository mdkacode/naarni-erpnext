"""Service Estimate controller."""

import frappe
from frappe import _
from frappe.model.document import Document


class ServiceEstimate(Document):
    def validate(self) -> None:
        self._compute_total()

    def _compute_total(self) -> None:
        self.total_amount = sum(
            float(item.get("amount") or 0) for item in self.get("items", [])
        )
