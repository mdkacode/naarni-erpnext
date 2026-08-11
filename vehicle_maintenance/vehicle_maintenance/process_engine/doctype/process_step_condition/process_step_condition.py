"""Process StepCondition (child table).

One clause of a step's visibility rule. Structured rows, never a formula language — evaluated by `engine.conditions`, never eval().
"""

from frappe.model.document import Document


class ProcessStepCondition(Document):
	pass
