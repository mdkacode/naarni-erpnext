"""Process StepAction (child table).

One automation bound to a step outcome. Dispatched server-side by `engine.actions` so the client cannot bypass it.
"""

from frappe.model.document import Document


class ProcessStepAction(Document):
	pass
