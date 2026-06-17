"""Alert Event DocType controller.

A log row for every alert the engine fired (the same incidents posted to Teams).
Written by the engine via `vehicle_maintenance.api.alerts.ingest_alert_event`.
Read-only data foundation for dashboards/charts; no business logic here.
"""

from frappe.model.document import Document


class AlertEvent(Document):
	pass
