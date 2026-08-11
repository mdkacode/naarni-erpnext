"""Process Action Set — a reusable bundle of automations.

Steps link to one instead of repeating the same rows. "Critical failure
response" — raise the NCR, notify the supervisor, quarantine the run — is
authored once, and changing who gets alerted is a single edit rather than
twenty.
"""

import frappe
from frappe import _
from frappe.model.document import Document

from vehicle_maintenance.process_engine import constants as C

#: Actions that are meaningless without a target, so we refuse to save them empty
#: rather than log a silent no-op at the station.
_NEEDS_TARGET = {
	C.ACT_NOTIFY_ROLE,
	C.ACT_NOTIFY_USER,
	C.ACT_JUMP_TO_STEP,
	C.ACT_SET_FIELD_ON_SUBJECT,
	C.ACT_CREATE_DOCUMENT,
	C.ACT_SEND_EMAIL,
	C.ACT_SEND_SMS,
	C.ACT_RUN_SERVER_SCRIPT,
	C.ACT_ADD_TAG,
}


class ProcessActionSet(Document):
	def validate(self):
		for action in self.actions or []:
			if action.action_type in _NEEDS_TARGET and not (action.target or "").strip():
				frappe.throw(
					_("Action '{0}' needs a target — a role, user, step, field or script name.").format(
						action.action_type
					)
				)
			if action.trigger == C.TRIGGER_ON_OPTION and not (action.trigger_value or "").strip():
				frappe.throw(_("An On Option trigger needs the option value that fires it."))

	def on_update(self):
		frappe.cache().delete_key(f"process_engine:actionset:{self.name}")
