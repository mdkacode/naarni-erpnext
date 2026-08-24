"""Push Delivery DocType controller.

A ledger row, not a business object: every field is written by
`fleet_service.push_delivery`. Kept logic-free so a sweeper re-enqueue never
triggers validation side effects on a row it is only re-reading.
"""

from frappe.model.document import Document


class PushDelivery(Document):
	pass
