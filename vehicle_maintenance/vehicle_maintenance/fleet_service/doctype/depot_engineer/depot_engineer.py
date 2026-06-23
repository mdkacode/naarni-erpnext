"""Depot Engineer (child table).

A Service Engineer assigned to a Depot — backs Depot.service_engineers and drives
which alerts/tickets each SE sees.
"""

from frappe.model.document import Document


class DepotEngineer(Document):
	pass
