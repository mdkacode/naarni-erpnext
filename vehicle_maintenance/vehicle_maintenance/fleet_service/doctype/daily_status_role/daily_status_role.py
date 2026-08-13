# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Daily Status Role (child table).

Used twice on Daily Status Settings — once for who is asked for a status, once
for who receives the digest — because both questions are answered by a role and
neither deserves a table of its own.
"""

from frappe.model.document import Document


class DailyStatusRole(Document):
	pass
