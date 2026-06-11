"""Alert Subscription Vehicle — child table of Alert Subscription.

Holds the specific vehicles an alert applies to when vehicle_scope = "Selected".
`device_id` is fetched from the linked Vehicle for the engine config payload.
"""

from frappe.model.document import Document


class AlertSubscriptionVehicle(Document):
	pass
