# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""VM Daily Status Item (child table).

One line of somebody's day. Rows are written by the summariser and may be edited
by a manager afterwards — which is why `bucket` is a plain Select rather than
something derived: a manager reclassifying "in progress" as "blocked" is a
judgement the record should keep.
"""

from frappe.model.document import Document


class VMDailyStatusItem(Document):
	pass
