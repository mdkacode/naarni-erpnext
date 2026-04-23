"""Job Card Subsystem child table.

Links a Job Card to one or more `Subsystem` master rows, enabling the PRD-required
multi-select for the 'Selects Subsystem' field on Only Repair / Software Update /
Breakdown job cards.
"""

from frappe.model.document import Document


class JobCardSubsystem(Document):
    pass
