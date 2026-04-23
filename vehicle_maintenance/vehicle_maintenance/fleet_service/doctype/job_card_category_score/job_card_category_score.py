"""Job Card Category Score child table.

Persists per-category Pre-PMS / Post-PMS health scores so reports and the
Vehicle Health Card can surface the breakdown required by the PRD
(Score Calculation Method section).
"""

from frappe.model.document import Document


class JobCardCategoryScore(Document):
    pass
