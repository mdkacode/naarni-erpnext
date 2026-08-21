# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Azure AI Settings — direct access to the Azure OpenAI resource ONYX runs on.

The contract lives in `vehicle_maintenance.integrations.azure_ai`; this Single
only holds what an admin may change without a deploy. site_config keys
(`azure_ai_enabled`, `azure_ai_api_key`, ...) override every field here.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import cint

# Structured outputs — the entire reason to call Azure directly — landed in this
# API version. Anything older silently ignores the schema and returns prose,
# which is a far worse failure than an error because it looks like it worked.
MIN_STRUCTURED_OUTPUT_VERSION = "2024-08-01"


class AzureAISettings(Document):
	def validate(self) -> None:
		self.endpoint = (self.endpoint or "").strip().rstrip("/")
		if self.endpoint and not self.endpoint.startswith(("http://", "https://")):
			frappe.throw(_("Endpoint must start with http:// or https://"))
		if self.endpoint and "/openai/" in self.endpoint:
			frappe.throw(
				_("Endpoint should be the resource host only — the deployment path is added automatically.")
			)

		if self.enabled and not (self.api_key or (frappe.conf or {}).get("azure_ai_api_key")):
			frappe.throw(_("Set an API key before enabling Azure AI."))

		if cint(self.timeout_seconds) and cint(self.timeout_seconds) < 5:
			frappe.throw(_("Timeout must be at least 5 seconds."))
		if cint(self.max_tokens) and cint(self.max_tokens) < 200:
			frappe.throw(_("Max response tokens must be at least 200, or summaries will be cut off."))

		self._warn_on_old_api_version()

	def _warn_on_old_api_version(self) -> None:
		"""An old api-version does not error — it quietly returns prose instead.

		That is the worst kind of misconfiguration, so say it out loud rather than
		letting it show up months later as "the summaries stopped being structured".
		"""
		version = (self.api_version or "").strip()
		if version and version[:10] < MIN_STRUCTURED_OUTPUT_VERSION:
			frappe.msgprint(
				_(
					"API version <b>{0}</b> is older than {1}, which is when structured outputs "
					"were introduced. Azure will ignore the schema and return prose, and every "
					"summary will quietly fall back."
				).format(version, MIN_STRUCTURED_OUTPUT_VERSION),
				indicator="orange",
				alert=True,
			)

	def on_update(self) -> None:
		frappe.clear_document_cache("Azure AI Settings", "Azure AI Settings")
		try:
			frappe.cache().delete_value("azure_ai_consecutive_failures")
		except Exception:
			pass
