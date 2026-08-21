# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Move any world-readable profile picture into the private bucket.

Nothing this app stores should be fetchable without a session, and a face is
the least defensible exception: a public url is a photograph of a named employee
that needs no login and cannot be recalled once it has been shared.

New avatars are private by construction — `set_profile_photo` refuses a public
file — so this exists for what was already there: pictures uploaded through Desk,
and anything set while the first cut of this feature briefly stored them public.

Frappe's File does the actual move when `is_private` changes, including renaming
on disk and rewriting `file_url`; the User's pointer is then updated to match.
Idempotent, per-user best-effort, and it never touches a gravatar or any other
absolute url — those are somebody else's to serve.
"""

import frappe


def execute() -> None:
	rows = frappe.get_all(
		"User",
		filters={"user_image": ["like", "/files/%"]},
		fields=["name", "user_image"],
		limit_page_length=0,
	)
	moved = 0
	for row in rows:
		try:
			if _privatise(row["name"], row["user_image"]):
				moved += 1
		except Exception:
			frappe.log_error(title=f"Avatar privatise failed ({row['name']})", message=frappe.get_traceback())
	if moved:
		frappe.db.commit()
		print(f"[onboarding] moved {moved} profile picture(s) out of the public bucket")


def _privatise(user: str, url: str) -> bool:
	name = frappe.db.get_value("File", {"file_url": url, "is_private": 0}, "name")
	if not name:
		# No File row for it — a hand-set path, or already private. Leave the
		# pointer alone rather than break an avatar we cannot account for.
		return False

	doc = frappe.get_doc("File", name)
	doc.is_private = 1
	doc.save(ignore_permissions=True)

	# `handle_is_private_changed` rewrites file_url as part of that save.
	if doc.file_url and doc.file_url != url:
		frappe.db.set_value("User", user, "user_image", doc.file_url, update_modified=False)
	return True
