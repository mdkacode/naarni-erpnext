"""Service Ticket controller.

A lightweight actionable item for a Service Engineer, auto-raised from an Alert
Event for a bus at the engineer's depot (or created manually), and convertible
into a Job Card. Status: Open → Acknowledged → Resolved.
"""

from frappe.model.document import Document


class ServiceTicket(Document):
	pass
