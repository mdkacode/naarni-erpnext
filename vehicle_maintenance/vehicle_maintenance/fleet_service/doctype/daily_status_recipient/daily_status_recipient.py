# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Daily Status Recipient (child table).

A person who receives every depot's digest regardless of role — the escape hatch
for "the plant head also wants this", which is otherwise a new role.
"""

from frappe.model.document import Document


class DailyStatusRecipient(Document):
	pass
