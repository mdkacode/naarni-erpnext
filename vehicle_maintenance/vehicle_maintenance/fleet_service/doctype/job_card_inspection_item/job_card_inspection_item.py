"""Job Card Inspection Item (child table).

One PMS inspection checklist result. Three-tier items carry a `component_status`
(Good / Repair-Replace Recommended / Repair-Replace Immediately) that feeds the
health-score engine; measurement items carry a numeric value with range bounds.
"""

from frappe.model.document import Document


class JobCardInspectionItem(Document):
	pass
