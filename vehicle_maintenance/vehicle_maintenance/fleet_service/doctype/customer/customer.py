"""Customer DocType controller."""

import re

import frappe
from frappe import _
from frappe.model.document import Document

CUSTOMER_CODE_PATTERN = re.compile(r"^[A-Z0-9]{2,10}$")


class Customer(Document):
    def validate(self) -> None:
        self._normalize_customer_code()
        self._validate_customer_code()

    def _normalize_customer_code(self) -> None:
        if self.customer_code:
            self.customer_code = self.customer_code.strip().upper()

    def _validate_customer_code(self) -> None:
        if not self.customer_code or not CUSTOMER_CODE_PATTERN.match(self.customer_code):
            frappe.throw(
                _("Customer Code must be 2-10 uppercase alphanumeric characters (A-Z, 0-9).")
            )
