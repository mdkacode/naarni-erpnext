"""Notification Channel DocType controller.

A delivery destination for alerts — today a Microsoft Teams channel, identified by
its Power Automate incoming-webhook URL. Naarni-managed; Alert Types reference a
channel by name. The alert engine reads the webhook (decrypted) via
`get_engine_config` and posts each alert's Adaptive Card to the matching channel.
"""

import frappe
from frappe import _
from frappe.model.document import Document


class NotificationChannel(Document):
	def validate(self) -> None:
		if not (self.get_password("webhook_url", raise_exception=False) or "").strip():
			frappe.throw(_("A Teams Webhook URL is required for this channel."))
		self._enforce_single_default()

	def _enforce_single_default(self) -> None:
		"""At most one channel is the default. Marking this one default clears the
		flag on every other channel so routing is never ambiguous."""
		if not self.is_default:
			return
		others = frappe.get_all(
			"Notification Channel",
			filters={"is_default": 1, "name": ["!=", self.name]},
			pluck="name",
		)
		for other in others:
			frappe.db.set_value("Notification Channel", other, "is_default", 0)
