# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""VM Chat Member — child table of VM Chat Room.

Holds the per-user read cursor (`last_read_seq`) the unread badge is computed
from, plus the mute and push preferences. No logic of its own; the room
controller owns membership validation.
"""

from frappe.model.document import Document


class VMChatMember(Document):
	pass
