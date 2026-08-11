"""Process StepOption (child table).

One selectable outcome on a Choice step. `is_pass` drives scoring, `is_critical` drives quarantine — which is why the engine needs no built-in Pass/Fail.
"""

from frappe.model.document import Document


class ProcessStepOption(Document):
	pass
