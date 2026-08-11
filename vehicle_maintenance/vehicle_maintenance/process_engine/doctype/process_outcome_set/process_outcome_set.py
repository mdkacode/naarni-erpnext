"""Process Outcome Set — a reusable answer vocabulary.

Defining "Pass / Fail / N-A" once and linking 39 steps to it beats authoring the
same three rows 39 times: a wording change, a Hindi translation or a colour fix
lands everywhere at once, and an author picks a vocabulary from a dropdown
instead of retyping one.
"""

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import cint


class ProcessOutcomeSet(Document):
	def validate(self):
		values = [(o.value or "").strip() for o in self.options or []]
		if len(values) != len(set(values)):
			frappe.throw(_("Option values must be unique within a set."))
		if not any(cint(o.is_pass) for o in self.options or []):
			frappe.throw(
				_("At least one option must count as a pass, or no step using this set can ever pass.")
			)
		for idx, option in enumerate(self.options or [], start=1):
			if not option.label:
				option.label = option.value
			if not cint(option.sequence):
				option.sequence = idx

	def on_update(self):
		frappe.cache().delete_key(f"process_engine:outcome:{self.name}")
