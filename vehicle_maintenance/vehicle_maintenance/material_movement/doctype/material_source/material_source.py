"""Material Source — where material came from, or where it is going."""

from __future__ import annotations

from frappe.model.document import Document


class MaterialSource(Document):
	def validate(self):
		self.source_name = (self.source_name or "").strip()
