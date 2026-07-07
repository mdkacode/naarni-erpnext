"""Mobile-app runtime helpers (version gate, etc.)."""

import frappe

DEFAULT_UPDATE_URL = "https://play.google.com/store/apps/details?id=com.naarni.service"
DEFAULT_MESSAGE = "A new version of Naarni Service is available. Please update to continue."


@frappe.whitelist(allow_guest=True)
def get_app_update(platform: str = "ANDROID", version_code: int = 0) -> dict:
	"""Tell the app whether it needs (force) or has (optional) an update.

	Called on launch — BEFORE login (allow_guest) — with the installed
	versionCode. Controlled from the single "App Update Settings" DocType.

	Defensive by design: any failure returns "no update" so a backend hiccup can
	never hard-block the field engineers out of the app.

	Returns {success, data: {update_available, force_update, latest_version_code,
	update_url, message}}.
	"""
	try:
		installed = int(version_code or 0)
	except (TypeError, ValueError):
		installed = 0

	data = {
		"update_available": False,
		"force_update": False,
		"latest_version_code": 0,
		"update_url": DEFAULT_UPDATE_URL,
		"message": DEFAULT_MESSAGE,
	}
	try:
		s = frappe.get_cached_doc("App Update Settings")
		latest = int(s.get("android_latest_version_code") or 0)
		minimum = int(s.get("android_min_version_code") or 0)
		data["latest_version_code"] = latest
		data["update_available"] = bool(latest and installed < latest)
		data["force_update"] = bool(minimum and installed < minimum)
		data["update_url"] = s.get("android_update_url") or DEFAULT_UPDATE_URL
		data["message"] = s.get("update_message") or DEFAULT_MESSAGE
	except Exception:
		frappe.log_error(title="get_app_update failed")

	return {"success": True, "data": data}
