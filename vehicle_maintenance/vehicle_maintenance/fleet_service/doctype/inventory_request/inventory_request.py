"""Inventory Request DocType controller.

Tracks part requests raised against a Job Card through the fulfilment pipeline:
Requested -> Parts Allocated -> Parts Issued -> Received.
"""

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import now_datetime

from vehicle_maintenance.fleet_service import notifications

VALID_STATUS_FLOW: dict[str, list[str]] = {
    "Requested": ["Parts Allocated"],
    "Parts Allocated": ["Parts Issued", "Requested"],
    "Parts Issued": ["Received"],
    "Received": [],
}

STATUS_TIMESTAMP_MAP: dict[str, tuple[str, str]] = {
    "Parts Allocated": ("allocated_at", "allocated_by"),
    "Parts Issued": ("issued_at", "issued_by"),
    "Received": ("received_at", "received_by"),
}

# Roles permitted to advance an Inventory Request into each status.
# Per PRD p.3: only DM (or storekeeper via DM delegation) allocates/issues;
# Central Ops monitors via dashboards, does not allocate.
STATUS_ROLE_MAP: dict[str, list[str]] = {
    "Parts Allocated": ["Depot Manager"],
    "Parts Issued": ["Depot Manager"],
    "Received": ["Technician", "Service Engineer"],
}


class InventoryRequest(Document):
    def validate(self) -> None:
        # Stash pre-save status for notification routing in on_update.
        self._pre_save_status = (
            None if self.is_new() else self.get_db_value("status")
        )
        self._validate_quantity()
        self._validate_status_transition()

    def before_save(self) -> None:
        self._stamp_status_change()

    def on_update(self) -> None:
        try:
            notifications.notify_inventory_request_transition(
                self, getattr(self, "_pre_save_status", None)
            )
        except Exception:
            frappe.log_error(
                title=f"notify_inventory_request_transition failed for {self.name}",
                message=frappe.get_traceback(),
            )

    def _validate_quantity(self) -> None:
        if not self.quantity or float(self.quantity) <= 0:
            frappe.throw(_("Quantity must be a positive number."))

    def _validate_status_transition(self) -> None:
        if self.is_new():
            return
        old_status = self.get_db_value("status")
        if old_status == self.status:
            return
        allowed_next = VALID_STATUS_FLOW.get(old_status, [])
        if self.status not in allowed_next:
            frappe.throw(
                _("Invalid status transition from {0} to {1}.").format(old_status, self.status)
            )
        allowed_roles = STATUS_ROLE_MAP.get(self.status, [])
        user_roles = frappe.get_roles(frappe.session.user)
        if allowed_roles and not any(r in user_roles for r in allowed_roles):
            frappe.throw(
                _("You do not have permission to set status to {0}.").format(self.status)
            )

    def _stamp_status_change(self) -> None:
        if self.is_new():
            return
        old_status = self.get_db_value("status")
        if old_status == self.status:
            return
        mapping = STATUS_TIMESTAMP_MAP.get(self.status)
        if not mapping:
            return
        ts_field, user_field = mapping
        if not self.get(ts_field):
            self.set(ts_field, now_datetime())
        if not self.get(user_field):
            self.set(user_field, frappe.session.user)


@frappe.whitelist()
def list_for_job_card(job_card_ref: str) -> dict:
    """Return Inventory Requests for a given Job Card.

    Args:
        job_card_ref: Job Card name.

    Returns:
        Envelope with list of inventory request summaries.
    """
    frappe.has_permission("Job Card", doc=job_card_ref, throw=True)
    rows = frappe.get_all(
        "Inventory Request",
        filters={"job_card_ref": job_card_ref},
        fields=[
            "name",
            "part",
            "part_name",
            "part_group",
            "quantity",
            "uom",
            "urgency_level",
            "status",
            "request_date",
            "allocated_at",
            "issued_at",
            "received_at",
        ],
        order_by="request_date desc",
        limit_page_length=200,
    )
    return {"success": True, "data": rows}
