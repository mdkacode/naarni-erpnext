"""Process Entity Type — anything a process can scan.

Holds the admin-authored QR patterns. Saving clears the compiled-pattern cache
so a corrected pattern takes effect on the next scan rather than the next
restart, and the sample payload is re-parsed so the author sees immediately
whether their pattern actually works.
"""

import json

import frappe
from frappe import _
from frappe.model.document import Document

from vehicle_maintenance.process_engine import scanning


class ProcessEntityType(Document):
	def validate(self):
		self._validate_patterns()
		self._refresh_sample()

	def on_update(self):
		scanning.clear_pattern_cache(self.name)

	def _validate_patterns(self):
		"""Reject a malformed regex at save time rather than at the station."""
		import re

		for line in (self.qr_pattern or "").splitlines():
			line = line.strip()
			if not line or line.startswith("#"):
				continue
			try:
				re.compile(line)
			except re.error as exc:
				frappe.throw(_("Invalid QR pattern:<br><code>{0}</code><br>{1}").format(line, exc))

	def _refresh_sample(self):
		"""Parse `sample_payload` so the author can see the result before saving."""
		if not self.sample_payload:
			self.sample_result = None
			return
		scanning.clear_pattern_cache(self.name)
		parsed = scanning.parse_payload(self.name, self.sample_payload)
		if parsed.get("parse_failed"):
			self.sample_result = _("No pattern matched. The raw payload would still be stored.")
		else:
			self.sample_result = json.dumps(
				{k: v for k, v in parsed.items() if v and k not in ("raw_payload", "parse_failed")},
				indent=2,
			)
