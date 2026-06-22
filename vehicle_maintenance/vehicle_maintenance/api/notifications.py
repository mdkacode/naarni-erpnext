"""Whitelisted notification endpoints for the App + web SPA notification bell.

Reads the user's Frappe Notification Log entries (written by
`fleet_service/notifications.py`). Realtime delivery happens over socket.io via
the `vm_notification` event; these endpoints back the durable list, the unread
badge, and mark-as-read. Consumed by the React Native App and the Vue SPA.
"""

import frappe
from frappe import _


@frappe.whitelist()
def get_my_notifications(limit: int = 30, offset: int = 0, unread_only: int = 0) -> dict:
	"""Return the logged-in user's notifications, newest first.

	Args:
	    limit: Page size (max 100).
	    offset: Page offset for infinite scroll.
	    unread_only: When truthy, return only unread entries.

	Returns:
	    {success, data: [{name, subject, body, job_card, read, priority, creation}]}.
	"""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)

	limit = min(int(limit or 30), 100)
	filters = {"for_user": user}
	if int(unread_only or 0):
		filters["read"] = 0

	rows = frappe.get_all(
		"Notification Log",
		filters=filters,
		fields=["name", "subject", "email_content", "document_name", "read", "creation"],
		order_by="creation desc",
		limit_page_length=limit,
		limit_start=int(offset or 0),
	)
	data = [
		{
			"name": r["name"],
			"subject": r["subject"],
			"body": r.get("email_content"),
			"job_card": r.get("document_name"),
			"read": bool(r.get("read")),
			"creation": str(r["creation"]),
		}
		for r in rows
	]
	return {"success": True, "data": data}


@frappe.whitelist()
def get_unread_count() -> dict:
	"""Return the unread notification count for the logged-in user (bell badge)."""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)
	count = frappe.db.count("Notification Log", {"for_user": user, "read": 0})
	return {"success": True, "data": {"unread": count}}


@frappe.whitelist()
def mark_notification_read(name: str = "", mark_all: int = 0) -> dict:
	"""Mark one notification (by `name`) or all of the user's notifications read.

	A user may only mark their own notifications; we filter on `for_user` so this
	can never touch another user's log.
	"""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)

	if int(mark_all or 0):
		frappe.db.set_value(
			"Notification Log",
			{"for_user": user, "read": 0},
			"read",
			1,
			update_modified=False,
		)
		return {"success": True, "message": _("All notifications marked read.")}

	if not name:
		frappe.throw(_("Notification name or mark_all is required."))
	owner = frappe.db.get_value("Notification Log", name, "for_user")
	if owner != user:
		frappe.throw(_("Not permitted."), frappe.PermissionError)
	frappe.db.set_value("Notification Log", name, "read", 1, update_modified=False)
	return {"success": True, "message": _("Notification marked read.")}


@frappe.whitelist()
def register_push_token(device_token: str, platform: str = "android") -> dict:
	"""Register (or refresh) the calling user's device push token.

	Called by the App after it obtains a push token. Idempotent: an existing row
	for the same token is reactivated and re-pointed at the current user rather
	than duplicated, so re-installs and account switches stay clean.

	Args:
	    device_token: The FCM/APNs/Expo token from the device.
	    platform: android | ios | web.

	Returns:
	    {success, data: {push_token: <name>}}.
	"""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)
	if not (device_token or "").strip():
		frappe.throw(_("Device token is required."))
	if platform not in ("android", "ios", "web"):
		frappe.throw(_("Invalid platform."))

	existing = frappe.db.get_value("Push Token", {"device_token": device_token}, "name")
	if existing:
		doc = frappe.get_doc("Push Token", existing)
		doc.user = user
		doc.platform = platform
		doc.is_active = 1
		doc.save(ignore_permissions=True)
	else:
		doc = frappe.get_doc(
			{
				"doctype": "Push Token",
				"user": user,
				"device_token": device_token,
				"platform": platform,
				"is_active": 1,
			}
		).insert(ignore_permissions=True)

	return {"success": True, "data": {"push_token": doc.name}}
