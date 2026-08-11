"""Roster Settings — one Single doc holding every roster/attendance policy.

Deliberately a Single rather than per-depot config: the policies here are
company-wide questions ("do we block a punch outside the geofence?"), and the
only genuinely per-depot value — where the depot physically is — belongs on the
Depot record, which is where an admin looks for it.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import flt


class RosterSettings(Document):
	def validate(self) -> None:
		if self.geofence_mode == "Block" and not self.require_location:
			# Blocking on distance while accepting fix-less punches means anyone who
			# denies location permission bypasses the geofence entirely. Say so
			# rather than letting an admin believe the gate is closed.
			frappe.msgprint(
				_(
					"Geofence is set to <b>Block</b> but a location fix is not required, "
					"so a punch with no GPS is still accepted. Tick <b>Require a Location Fix</b> "
					"to close that gap — at the cost of blocking anyone whose phone cannot see the sky."
				),
				indicator="orange",
				alert=True,
			)
		if flt(self.auto_checkout_after_hours) and flt(self.auto_checkout_after_hours) < 1:
			frappe.throw(_("Auto check-out must be at least 1 hour after check-in."))
