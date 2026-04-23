"""Depot / Workshop master DocType."""

import re

import frappe
from frappe import _
from frappe.model.document import Document

LOCATION_CODE_PATTERN = re.compile(r"^[A-Z0-9]{2,10}$")


class Depot(Document):
    def validate(self) -> None:
        self._normalize_location_code()
        self._validate_location_code()

    def _normalize_location_code(self) -> None:
        if self.location_code:
            self.location_code = self.location_code.strip().upper()

    def _validate_location_code(self) -> None:
        if not self.location_code or not LOCATION_CODE_PATTERN.match(self.location_code):
            frappe.throw(
                _("Location Code must be 2-10 uppercase alphanumeric characters (A-Z, 0-9).")
            )
