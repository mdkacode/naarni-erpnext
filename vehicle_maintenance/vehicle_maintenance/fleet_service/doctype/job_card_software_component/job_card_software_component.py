"""Job Card Software Component child table (Milestone 4).

Represents a single ECU/component whose software is being updated during a
Software Update job card, with pre/post version capture, calibration values,
and retry tracking per PRD p.13-14.
"""

from frappe.model.document import Document


class JobCardSoftwareComponent(Document):
    pass
