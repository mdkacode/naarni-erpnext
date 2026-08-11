# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""VM Chat Upload — staging state for one resumable chunked upload.

The docname is the upload_id and is a random hash, so it is not guessable from
another user's session. Ownership is the standard `owner` field; the API checks
it on every call.
"""

import os

import frappe
from frappe.model.document import Document

# Where partial uploads live until commit. Under the site's private files so it
# is never web-servable, and on the same volume as the final destination so the
# commit is a rename rather than a copy.
STAGING_DIRNAME = "chat_staging"


def staging_dir() -> str:
	path = frappe.get_site_path("private", "files", STAGING_DIRNAME)
	os.makedirs(path, exist_ok=True)
	return path


class VMChatUpload(Document):
	@property
	def staging_path(self) -> str:
		# self.name is a server-generated hash; no user input reaches this path.
		return os.path.join(staging_dir(), self.name)

	def discard(self, reason: str = "") -> None:
		"""Abort and remove the partial file. Safe to call twice."""
		try:
			if os.path.exists(self.staging_path):
				os.remove(self.staging_path)
		except OSError:
			frappe.log_error(
				title=f"Chat upload cleanup failed ({self.name})", message=frappe.get_traceback()
			)
		self.db_set("status", "Aborted", update_modified=False)
		if reason:
			self.db_set("error", reason[:500], update_modified=False)
