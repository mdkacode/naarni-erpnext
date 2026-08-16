# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

from frappe.model.document import Document


class VMChatPresence(Document):
	"""When each user was last reachable.

	Nothing but a timestamp per user. The controller is empty on purpose: rows
	are written by the heartbeat with `set_value` rather than through the
	document API, because a validate/on_update cycle per user per few minutes
	buys nothing for a field no rule applies to.

	**The timestamp field is `seen_at`, not `last_seen`.** Frappe strips the
	optional columns (`_seen`, `_comments`, `_assign`, …) from every query with
	a *substring* test — `model/db_query.py::set_optional_columns` — so any
	fieldname containing `_seen` is silently dropped from `frappe.get_all`
	results on a doctype that does not track seen. No error, no warning: the
	key is simply absent from the returned dict, which presents as "the column
	is in the database and populated, but nobody is ever reported as seen".
	"""

	pass
