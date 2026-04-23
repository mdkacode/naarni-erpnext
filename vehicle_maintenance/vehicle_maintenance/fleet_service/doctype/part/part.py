"""Part (SKU / parts master) DocType controller.

Canonical catalog of spare parts referenced by Inventory Requests, Repair Items,
and future Stock Bin / Stock Ledger records.
"""

import re

import frappe
from frappe import _
from frappe.model.document import Document

PART_CODE_PATTERN = re.compile(r"^[A-Z0-9][A-Z0-9\-]{1,39}$")


class Part(Document):
    def validate(self) -> None:
        self._normalize_part_code()
        self._validate_part_code()

    def _normalize_part_code(self) -> None:
        if self.part_code:
            self.part_code = self.part_code.strip().upper()

    def _validate_part_code(self) -> None:
        if not self.part_code or not PART_CODE_PATTERN.match(self.part_code):
            frappe.throw(
                _(
                    "Part Code must be 2-40 characters, start with a letter/digit, "
                    "and contain only uppercase letters, digits, and dashes."
                )
            )


@frappe.whitelist()
def search_parts(query: str = "", part_group: str | None = None, limit: int = 20) -> dict:
    """Typeahead search for parts, scoped to active ones.

    Args:
        query: Substring to match against part_code or part_name.
        part_group: Optional Part Group filter.
        limit: Max rows (capped at 50).

    Returns:
        Envelope with list of {part_code, part_name, part_group, stock_uom}.
    """
    frappe.has_permission("Part", throw=True)
    limit = min(max(int(limit), 1), 50)

    filters: dict = {"is_active": 1}
    if part_group:
        filters["part_group"] = part_group

    or_filters = None
    if query:
        q = f"%{query}%"
        or_filters = [["part_code", "like", q], ["part_name", "like", q]]

    rows = frappe.get_all(
        "Part",
        filters=filters,
        or_filters=or_filters,
        fields=["name as part_code", "part_name", "part_group", "stock_uom"],
        order_by="part_name asc",
        limit_page_length=limit,
    )
    return {"success": True, "data": rows}
