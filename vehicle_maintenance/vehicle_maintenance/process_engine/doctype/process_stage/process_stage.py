"""Process Stage (child table).

A sign-off gate within a process. The parent's `stage_label` decides what operators call these.
"""

from frappe.model.document import Document


class ProcessStage(Document):
	pass
