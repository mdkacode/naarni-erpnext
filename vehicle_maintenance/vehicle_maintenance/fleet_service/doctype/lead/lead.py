"""Lead DocType controller — CRM prospect record."""

import re
from datetime import datetime

import frappe
from frappe import _
from frappe.model.document import Document

PHONE_DIGITS = re.compile(r"\D+")


class Lead(Document):
	def validate(self) -> None:
		self._normalize_phone()
		self._validate_status_terminal_transition()

	def before_insert(self) -> None:
		if not self.status:
			default = frappe.db.get_value(
				"Lead Status", {"stage": "Open"}, "name", order_by="display_order asc"
			)
			if default:
				self.status = default

	# ------------------------------------------------------------------ helpers

	def _normalize_phone(self) -> None:
		if not self.phone:
			return
		digits = PHONE_DIGITS.sub("", self.phone)
		# Match existing auth convention: last 10 digits
		self.phone = digits[-10:] if len(digits) >= 10 else digits
		if len(self.phone) != 10:
			frappe.throw(_("Phone must contain at least 10 digits."))

	def _validate_status_terminal_transition(self) -> None:
		if self.is_new() or not self.has_value_changed("status") or not self.status:
			return
		previous = self.get_doc_before_save()
		if not previous or not previous.status:
			return
		prev_terminal = frappe.db.get_value("Lead Status", previous.status, "is_terminal")
		if prev_terminal:
			frappe.throw(
				_("Lead {0} is in a terminal status ({1}) and cannot be changed.").format(
					self.name, previous.status
				)
			)

	# ------------------------------------------------------------------ API used by controllers/api

	def append_activity(
		self,
		activity_type: str,
		summary: str | None = None,
		outcome: str | None = None,
		next_action: str | None = None,
	) -> None:
		"""Append a Lead Activity row. Caller is responsible for `save()`."""
		self.append(
			"activities",
			{
				"activity_type": activity_type,
				"activity_date": datetime.now(),
				"summary": summary,
				"outcome": outcome,
				"next_action": next_action,
				"performed_by": frappe.session.user,
			},
		)
