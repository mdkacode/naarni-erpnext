"""Fault Code master (OEM/CAN codes).

Backs the searchable Fault Code dropdown on Breakdown job cards so the SE/
technician selects a known code (with its description) instead of typing.
"""

from frappe.model.document import Document


class FaultCode(Document):
	pass
