"""Whitelisted API methods for Service Estimate operations."""

import frappe
from frappe import _


@frappe.whitelist()
def create_estimate(job_card_name: str, items: list[dict]) -> dict:
    """Create a Service Estimate linked to a Job Card.

    Args:
        job_card_name: The parent Job Card name.
        items: List of dicts with keys: description, item_type, qty, rate, amount.

    Returns:
        dict with the created estimate name.
    """
    frappe.only_for(["Service Engineer", "Depot Manager"])
    frappe.has_permission("Job Card", doc=job_card_name, throw=True)

    if isinstance(items, str):
        import json
        items = json.loads(items)

    doc = frappe.get_doc({
        "doctype": "Service Estimate",
        "job_card": job_card_name,
        "items": [
            {
                "description": item["description"],
                "item_type": item.get("item_type", ""),
                "qty": float(item.get("qty", 1)),
                "rate": float(item.get("rate", 0)),
                "amount": float(item.get("amount", 0)),
            }
            for item in items
        ],
    })
    doc.insert()

    # Link estimate back to the Job Card
    frappe.db.set_value("Job Card", job_card_name, "service_estimate", doc.name)

    return {
        "success": True,
        "data": {"name": doc.name, "total_amount": float(doc.total_amount or 0)},
        "message": _("Estimate {0} created.").format(doc.name),
    }


@frappe.whitelist()
def approve_estimate(estimate_name: str, remarks: str = "") -> dict:
    """Customer approves a Service Estimate.

    Args:
        estimate_name: The Service Estimate document name.
        remarks: Optional customer remarks.

    Returns:
        dict confirming approval.
    """
    frappe.has_permission("Service Estimate", doc=estimate_name, ptype="write", throw=True)

    doc = frappe.get_doc("Service Estimate", estimate_name)
    if doc.status != "Sent to Customer":
        frappe.throw(_("Estimate must be in 'Sent to Customer' status to approve."))

    doc.status = "Approved"
    doc.customer_remarks = remarks
    doc.save()

    return {
        "success": True,
        "data": {"name": doc.name, "status": doc.status},
        "message": _("Estimate approved."),
    }


@frappe.whitelist()
def reject_estimate(estimate_name: str, remarks: str = "") -> dict:
    """Customer rejects a Service Estimate.

    Args:
        estimate_name: The Service Estimate document name.
        remarks: Required reason for rejection.

    Returns:
        dict confirming rejection.
    """
    frappe.has_permission("Service Estimate", doc=estimate_name, ptype="write", throw=True)

    if not remarks:
        frappe.throw(_("Please provide a reason for rejection."))

    doc = frappe.get_doc("Service Estimate", estimate_name)
    if doc.status != "Sent to Customer":
        frappe.throw(_("Estimate must be in 'Sent to Customer' status to reject."))

    doc.status = "Rejected"
    doc.customer_remarks = remarks
    doc.save()

    return {
        "success": True,
        "data": {"name": doc.name, "status": doc.status},
        "message": _("Estimate rejected."),
    }
