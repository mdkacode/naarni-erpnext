"""Whitelisted API methods for inventory/parts request operations.

Handles the business rule: if requested parts exceed a threshold value,
block allocation and flag the Job Card for Customer Approval.
"""

import json as _json

import frappe
from frappe import _

# Must match the constant in the Job Card controller
INVENTORY_APPROVAL_THRESHOLD = 1000.0


@frappe.whitelist()
def submit_inventory_request(
	job_card_name: str,
	items: str | list[dict],
) -> dict:
	"""Evaluate a parts/inventory request against the Job Card.

	Business rule:
	  - If total estimated value of requested items > 1,000,
	    allocation is BLOCKED and the Job Card is flagged for Customer Approval.
	  - Otherwise, items are added to the Job Card and allocation proceeds.

	Args:
	    job_card_name: Name of the Job Card document.
	    items: JSON string or list of dicts, each with keys:
	        - item_description (str, required)
	        - item_type (str, default "Part")
	        - qty (float, default 1)
	        - rate (float, required)
	        - estimated_amount (float, optional — computed as qty * rate if omitted)
	        - part_number (str, optional)

	Returns:
	    dict with:
	        - success (bool)
	        - data.approved (bool) — whether allocation was allowed
	        - data.total_value (float) — total value of the request
	        - data.threshold (float) — the threshold that was evaluated
	        - data.items_added (int) — number of line items added
	        - message (str)
	"""
	frappe.only_for(["Service Engineer", "Technician", "Depot Manager"])
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	# --- Parse items ---
	if isinstance(items, str):
		items = _json.loads(items)

	if not items:
		frappe.throw(_("At least one item is required."))

	# --- Validate and normalise each item ---
	normalised: list[dict] = []
	for idx, item in enumerate(items, start=1):
		description = (item.get("item_description") or "").strip()
		if not description:
			frappe.throw(_("Row {0}: item_description is required.").format(idx))

		qty = float(item.get("qty") or 1)
		rate = float(item.get("rate") or 0)
		if rate <= 0:
			frappe.throw(_("Row {0}: rate must be greater than zero.").format(idx))

		estimated_amount = float(item.get("estimated_amount") or 0) or (qty * rate)

		normalised.append(
			{
				"item_description": description,
				"item_type": item.get("item_type", "Part"),
				"qty": qty,
				"rate": rate,
				"estimated_amount": estimated_amount,
				"actual_amount": 0,
				"part_number": item.get("part_number", ""),
				"status": "Pending",
			}
		)

	total_value = sum(i["estimated_amount"] for i in normalised)

	# --- Load the Job Card ---
	doc = frappe.get_doc("Job Card", job_card_name)

	# --- Threshold evaluation ---
	exceeds_threshold = total_value > INVENTORY_APPROVAL_THRESHOLD

	if exceeds_threshold:
		# Block allocation — flag the card, do NOT add items yet
		doc.requires_customer_approval = 1
		doc.parts_allocation_blocked = 1
		doc.add_comment(
			"Comment",
			_(
				"Inventory request of {0} blocked — exceeds threshold of {1}. " "Awaiting Customer Approval."
			).format(
				frappe.format_value(total_value, {"fieldtype": "Currency"}),
				frappe.format_value(INVENTORY_APPROVAL_THRESHOLD, {"fieldtype": "Currency"}),
			),
		)

		# Store the pending request as JSON so it can be released after approval
		doc.set("pending_inventory_request", _json.dumps(normalised))
		doc.save(ignore_permissions=True)

		# Trigger workflow transition to Awaiting Customer Approval if currently WIP
		if doc.workflow_state == "WIP" and doc.service_estimate:
			from frappe.model.workflow import apply_workflow

			apply_workflow(doc, "Send Estimate")
			doc.save(ignore_permissions=True)

		return {
			"success": True,
			"data": {
				"approved": False,
				"total_value": total_value,
				"threshold": INVENTORY_APPROVAL_THRESHOLD,
				"items_added": 0,
			},
			"message": _(
				"Parts request of {0} exceeds the {1} threshold. "
				"Job Card has been flagged for Customer Approval. "
				"Parts will be allocated after the customer approves."
			).format(
				frappe.format_value(total_value, {"fieldtype": "Currency"}),
				frappe.format_value(INVENTORY_APPROVAL_THRESHOLD, {"fieldtype": "Currency"}),
			),
		}

	# --- Under threshold: add items directly ---
	for item_data in normalised:
		doc.append("items", item_data)

	doc.save()

	return {
		"success": True,
		"data": {
			"approved": True,
			"total_value": total_value,
			"threshold": INVENTORY_APPROVAL_THRESHOLD,
			"items_added": len(normalised),
		},
		"message": _("{0} item(s) added to Job Card {1}.").format(len(normalised), doc.name),
	}


@frappe.whitelist()
def release_blocked_inventory(job_card_name: str) -> dict:
	"""Release previously blocked inventory after customer approval.

	Called after the Job Card transitions out of 'Awaiting Customer Approval'.
	Takes the stored pending_inventory_request and appends items to the card.

	Args:
	    job_card_name: The Job Card name.

	Returns:
	    dict confirming the release.
	"""
	frappe.only_for(["Service Engineer", "Depot Manager"])
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	doc = frappe.get_doc("Job Card", job_card_name)

	pending_raw = doc.get("pending_inventory_request")
	if not pending_raw:
		frappe.throw(_("No pending inventory request found on this Job Card."))

	pending_items = _json.loads(pending_raw)

	for item_data in pending_items:
		doc.append("items", item_data)

	doc.requires_customer_approval = 0
	doc.parts_allocation_blocked = 0
	doc.set("pending_inventory_request", None)
	doc.add_comment(
		"Comment", _("Blocked inventory released — {0} item(s) added.").format(len(pending_items))
	)
	doc.save()

	return {
		"success": True,
		"data": {"items_added": len(pending_items)},
		"message": _("{0} item(s) released and added to Job Card.").format(len(pending_items)),
	}
