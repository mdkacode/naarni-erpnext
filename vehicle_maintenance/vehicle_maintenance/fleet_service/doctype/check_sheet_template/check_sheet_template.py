import frappe
from frappe.model.document import Document


class CheckSheetTemplate(Document):
	def validate(self):
		self._validate_sections()
		self._compute_total_std_time()

	def _validate_sections(self):
		codes = {s.section_code for s in self.sections or []}
		for item in self.items or []:
			if item.section_code not in codes:
				frappe.throw(
					frappe._("Item Sr. {0} references unknown section '{1}'").format(
						item.sr_no, item.section_code
					)
				)

	def _compute_total_std_time(self):
		self.total_std_time_minutes = sum((i.std_time_minutes or 0) for i in self.items or [])
