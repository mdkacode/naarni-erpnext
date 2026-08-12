# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""VM Chat Mention — child table of VM Chat Message.

One row per person named with `@` in a message. No logic of its own: the chat
API validates that a mentioned user is actually a member of the room before
writing these, and `chat_notify` reads them to decide who gets woken up.
"""

from frappe.model.document import Document


class VMChatMention(Document):
	pass
