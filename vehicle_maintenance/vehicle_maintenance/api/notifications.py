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
		fields=[
			"name",
			"subject",
			"email_content",
			"document_type",
			"document_name",
			"read",
			"creation",
		],
		order_by="creation desc",
		limit_page_length=limit,
		limit_start=int(offset or 0),
	)
	data = [
		{
			"name": r["name"],
			"subject": r["subject"],
			"body": r.get("email_content"),
			# `job_card` is kept for older builds that read it by that name, but
			# it was never only job cards — an alert notification points at a
			# Service Ticket. `reference_doctype` is what a tap should route on.
			"job_card": r.get("document_name"),
			"reference_doctype": r.get("document_type"),
			"reference_name": r.get("document_name"),
			"deeplink": _deeplink_for(r.get("document_type"), r.get("document_name")),
			"read": bool(r.get("read")),
			"creation": str(r["creation"]),
		}
		for r in rows
	]
	return {"success": True, "data": data}


def _deeplink_for(doctype: str | None, name: str | None) -> str:
	"""App route for a bell row, so the handset never has to guess from the text."""
	if not name:
		return ""
	if doctype == "Service Ticket":
		return f"naarni://ticket/{name}"
	if doctype == "VM Chat Room":
		return f"naarni://chat/{name}"
	return f"naarni://jobcard/{name}"


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


@frappe.whitelist(methods=["POST"])
def deactivate_push_token(device_token: str) -> dict:
	"""Stop pushing to this device. Called by the App on sign-out.

	Without it, a handset that has been handed to someone else keeps receiving
	the previous user's breakdown alerts, and every one of those is a delivery
	the ledger counts as a success while the person who needed it heard nothing.

	Scoped to the caller's own tokens — a device token is not a secret, so this
	must not let anyone silence another user's phone.
	"""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)
	if not (device_token or "").strip():
		frappe.throw(_("Device token is required."))

	name = frappe.db.get_value("Push Token", {"device_token": device_token, "user": user}, "name")
	if name:
		frappe.db.set_value("Push Token", name, "is_active", 0, update_modified=False)
	return {"success": True, "message": _("Device unregistered.")}


@frappe.whitelist()
def push_health(hours: int = 24) -> dict:
	"""Delivery statistics from the Push Delivery ledger.

	The answer to "are notifications working?" should come from what FCM
	actually accepted, not from someone watching a handset. Admin-only: the
	counts are fleet-wide.
	"""
	frappe.only_for(["System Manager", "Central Ops"])
	from vehicle_maintenance.fleet_service import push_delivery

	return {"success": True, "data": push_delivery.health(hours)}


@frappe.whitelist()
def my_push_status() -> dict:
	"""What the *calling device's* user should know about their own push setup.

	Lets the App show an honest banner — "alerts can't reach this phone" — rather
	than the silence that reads as "the alert never fired".
	"""
	user = frappe.session.user
	if user == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)

	from vehicle_maintenance.fleet_service import push_delivery

	devices = frappe.db.count("Push Token", {"user": user, "is_active": 1})
	pending = frappe.db.count(
		"Push Delivery", {"user": user, "status": ["in", ["Queued", "Retrying", "Sending"]]}
	)
	return {
		"success": True,
		"data": {
			"push_enabled": push_delivery.is_enabled(),
			"registered_devices": devices,
			"pending_deliveries": pending,
		},
	}


@frappe.whitelist(methods=["POST"])
def send_test_push(user: str | None = None) -> dict:
	"""Send a real push through the full pipeline and report what happened.

	Deliberately not a mock. It queues a genuine Push Delivery row, delivers it
	inline, and returns the per-device outcome, so "are notifications working?"
	is answered by FCM rather than by inspection. Dead tokens get retired as a
	side effect, which is usually half the answer on a handset that has been
	reinstalled a few times.

	Defaults to the caller; naming another user is admin-only.
	"""
	target = user or frappe.session.user
	if target != frappe.session.user:
		frappe.only_for(["System Manager", "Central Ops"])
	if target == "Guest":
		frappe.throw(_("Authentication required."), frappe.PermissionError)

	from vehicle_maintenance.fleet_service import push_delivery

	if not push_delivery.is_enabled():
		frappe.throw(_("Push is disabled (notifications_push_enabled)."))

	names = push_delivery.queue_push(
		target,
		_("Test alert"),
		_("If you can see this, push notifications are reaching this device."),
		reference_doctype="Service Ticket",
		reference_name="TEST",
		priority="Critical",
		dedup_suffix=frappe.generate_hash(length=8),
	)
	if not names:
		return {
			"success": True,
			"data": {"devices": 0, "results": []},
			"message": _("No active device is registered for this user."),
		}

	results = []
	for name in names:
		push_delivery.deliver(name)
		row = frappe.db.get_value("Push Delivery", name, ["status", "attempts", "last_error"], as_dict=True)
		results.append({"delivery": name, **(row or {})})

	sent = sum(1 for r in results if r.get("status") == "Sent")
	return {
		"success": True,
		"data": {"devices": len(names), "accepted": sent, "results": results},
		"message": _("{0} of {1} device(s) accepted the push.").format(sent, len(names)),
	}
