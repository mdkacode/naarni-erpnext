"""OEM master DocType (e.g., Azad, NaArNi).

PRD requires 'Azad/NaArNi autofill' on the Job Card OEM field. The value is
now derived from the Vehicle master rather than hardcoded on the Job Card.
"""

from frappe.model.document import Document


class OEM(Document):
    pass
