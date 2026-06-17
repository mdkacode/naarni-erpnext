"""Notification Channel DocType controller.

A label for grouping/categorising Alert Types (e.g. "Safety", "Battery Ops").
Teams delivery uses a single Naarni webhook configured on the alert engine, so
these labels do not change where a message goes — they are organisational only.
"""

from frappe.model.document import Document


class NotificationChannel(Document):
	pass
