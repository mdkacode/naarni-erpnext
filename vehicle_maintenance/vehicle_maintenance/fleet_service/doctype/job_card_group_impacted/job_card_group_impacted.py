"""Job Card Group Impacted child table.

Represents a Part Group impacted by a Breakdown job card. Referenced by the
Job Card `groups_impacted` Table MultiSelect per PRD p.15 step 4
("Group Impacted: Select which group(s) have been impacted").
"""

from frappe.model.document import Document


class JobCardGroupImpacted(Document):
    pass
