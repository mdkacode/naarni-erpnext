"""Report Stakeholder — a recipient of the monthly white-labelled KM/SLA report.

Child table of Fleet Report Config. Kept as a proper child table (never a JSON blob)
so recipients are queryable and validated.
"""

from __future__ import annotations

from frappe.model.document import Document


class ReportStakeholder(Document):
	pass
