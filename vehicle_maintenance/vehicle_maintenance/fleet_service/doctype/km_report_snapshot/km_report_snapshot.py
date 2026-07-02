"""KM Report Snapshot — an immutable generated monthly report + public share token.

The monthly task builds one snapshot per (customer, month), freezing a PII-safe view
model into `payload_json`. The public page at `/km-report/<token>` reads ONLY this
doctype (never the raw daily rows / users / customers), so a leaked token exposes just
the report — and only for 7 days.
"""

from __future__ import annotations

import secrets

import frappe
from frappe.model.document import Document
from frappe.utils import add_days, get_datetime, now_datetime

TOKEN_TTL_DAYS = 7


class KMReportSnapshot(Document):
	def before_insert(self):
		if not self.generated_at:
			self.generated_at = now_datetime()
		if not self.public_token:
			self.issue_token()
		if not self.status:
			self.status = "Draft"

	def issue_token(self) -> str:
		"""(Re)issue a 256-bit URL-safe token with a fresh 7-day expiry."""
		self.generated_at = now_datetime()
		self.public_token = secrets.token_urlsafe(32)
		self.token_expires_on = add_days(self.generated_at, TOKEN_TTL_DAYS)
		return self.public_token

	def is_link_active(self) -> bool:
		if self.status == "Expired":
			return False
		if not self.token_expires_on:
			return False
		return now_datetime() <= get_datetime(self.token_expires_on)
