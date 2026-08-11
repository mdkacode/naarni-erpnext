"""Duty Roster Entry — one engineer on one date.

Child of Duty Roster. Validation lives on the parent, which is the only place
that can see the whole period and spot a double-booking.

One trap worth knowing: Frappe stamps **every** Time field with `nowtime()` when
a new row is created (`frappe.model.create_new.set_dynamic_default_values`,
unconditional — there is no default that opts out). So `start_time` / `end_time`
are never blank, and "blank means use the shift's timing" would silently give
every roster row a start time of whenever the roster was authored. That is what
`has_custom_time` is for: the flag, not the emptiness of the field, is what says
this day has its own hours.
"""

from frappe.model.document import Document


class DutyRosterEntry(Document):
	pass
