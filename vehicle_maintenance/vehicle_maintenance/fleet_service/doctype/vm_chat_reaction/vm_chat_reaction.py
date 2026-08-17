# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

import frappe
from frappe.model.document import Document


class VMChatReaction(Document):
	"""One person's one emoji on one message.

	Nothing to validate here. What has to hold — one row per (message, user,
	emoji) — is enforced by the index below rather than by a read-then-write
	check, because two taps arriving together would both pass such a check and
	leave a duplicate that renders as "👍 2" from a single person.
	"""

	pass


def on_doctype_update():
	"""Composite unique index on (message, user, emoji).

	Also the read path: every reaction lookup is by message, and this index
	leads with it.
	"""
	frappe.db.add_unique("VM Chat Reaction", ["message", "user", "emoji"], constraint_name="unique_reaction")
