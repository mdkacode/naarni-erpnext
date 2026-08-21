"""Material Location — a plant or store whose gate records movements."""

from __future__ import annotations

import re

from frappe import _, throw
from frappe.model.document import Document

_CODE = re.compile(r"^[A-Z0-9_]{2,20}$")


class MaterialLocation(Document):
	def validate(self):
		# The code is the document name and shows up in every export, so it is
		# normalised rather than merely checked — "hubli " and "Hubli" becoming
		# two plants is a data problem nobody notices until a report is wrong.
		self.location_code = (self.location_code or "").strip().upper().replace(" ", "_")
		if not _CODE.match(self.location_code):
			throw(_("Location code must be 2-20 characters: A-Z, 0-9 or underscore."))

	def gate_list(self) -> list[str]:
		"""The configured gates, one per line, blanks dropped."""
		return [line.strip() for line in (self.gates or "").splitlines() if line.strip()]
