"""Complaint Catalog master.

Backs the searchable complaint dropdown on the Job Card create form so a
non-tech user taps a known complaint instead of typing free text. New complaints
captured in the field can be added here and become future suggestions.
"""

from frappe.model.document import Document


class ComplaintCatalog(Document):
	pass
