"""Process RunResult (child table).

One answered step, with the step definition snapshotted at answer time so later template edits cannot rewrite history.
"""

from frappe.model.document import Document


class ProcessRunResult(Document):
	pass
