# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Onyx Settings — connection details for the ONYX AI service.

The contract itself lives in `vehicle_maintenance.integrations.onyx_client`;
this Single only holds what an admin may change without a deploy. site_config
keys (`onyx_enabled`, `onyx_base_url`, `onyx_api_key`, ...) override every field
here, so a site can be pinned outside the database when that is preferred.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document


class OnyxSettings(Document):
	def validate(self) -> None:
		self.base_url = (self.base_url or "").strip().rstrip("/")
		if self.base_url and not self.base_url.startswith(("http://", "https://")):
			frappe.throw(_("Base URL must start with http:// or https://"))
		if self.enabled and not (self.api_key or (frappe.conf or {}).get("onyx_api_key")):
			frappe.throw(_("Set an API key before enabling ONYX."))
		if self.timeout_seconds and int(self.timeout_seconds) < 5:
			frappe.throw(_("Timeout must be at least 5 seconds."))

	def on_update(self) -> None:
		# A rotated key or a flipped switch must take effect on the next call, not
		# after the cached Single expires.
		frappe.clear_document_cache("Onyx Settings", "Onyx Settings")
		try:
			frappe.cache().delete_value("onyx_consecutive_failures")
		except Exception:
			pass
