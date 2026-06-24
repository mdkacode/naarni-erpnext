import frappe
import frappe.sessions
from frappe import _
from frappe.auth import LoginManager

from vehicle_maintenance.integrations import naarni_client
from vehicle_maintenance.overrides.user import normalize_phone

# Role granted to a user auto-provisioned on first Naarni OTP login. Overridable
# per-site via the `naarni_default_role` site_config key.
DEFAULT_NAARNI_ROLE = "Service Engineer"


@frappe.whitelist()
def get_csrf_token() -> str:
	"""Return the current session's CSRF token for the SPA to attach to POST requests."""
	return frappe.sessions.get_csrf_token()


@frappe.whitelist(allow_guest=True)
def login_with_phone(phone: str, password: str) -> dict:
	"""Authenticate a staff user by phone number + password."""
	if not password:
		frappe.throw(_("Password is required."), frappe.AuthenticationError)

	normalized = normalize_phone(phone)

	candidates = frappe.db.get_all(
		"User",
		filters={"mobile_no": normalized, "enabled": 1},
		fields=["name", "full_name", "user_type"],
		limit=2,
	)
	if not candidates:
		frappe.throw(_("No account found for that phone number."), frappe.AuthenticationError)
	if len(candidates) > 1:
		frappe.throw(
			_("Multiple accounts share this phone number. Contact an administrator."),
			frappe.AuthenticationError,
		)

	user = candidates[0]
	lm = LoginManager()
	lm.authenticate(user=user.name, pwd=password)
	# post_login sets cookies via frappe.local.response; guard for non-HTTP callers
	# (tests, console) where that context doesn't exist.
	if getattr(frappe.local, "request", None) is not None:
		lm.post_login()

	roles = frappe.get_roles(user.name)
	return {
		"success": True,
		"data": {
			"user": user.name,
			"full_name": user.full_name,
			"user_type": user.user_type,
			"roles": roles,
		},
		"message": _("Logged in."),
	}


# ──────────────────────────── Unified OTP SSO (Naarni-brokered) ────────────────────────────


@frappe.whitelist(allow_guest=True)
def request_otp(phone: str, device_uuid: str | None = None, platform: str = "ANDROID") -> dict:
	"""Ask the Naarni backend to send a login OTP to `phone`.

	The app talks only to Frappe; Frappe relays to Naarni. `device_uuid` is the
	app install's own device identity (registered with Naarni on first use); the
	same device must be replayed to `verify_otp`. Omit it (web/legacy) to use the
	shared broker device. Returns a generic success envelope — we never reveal
	whether the number exists, to avoid user enumeration.
	"""
	if not naarni_client.is_login_enabled():
		frappe.throw(_("Phone login is temporarily unavailable."), frappe.ValidationError)

	# Validate shape early (also the canonical form we'll match the Frappe user on).
	normalize_phone(phone)

	try:
		naarni_client.request_otp(phone.strip(), device_uuid=device_uuid, platform=platform)
	except naarni_client.NaarniApiError:
		# Don't leak Naarni-side detail (rate limits, unknown number) to the client.
		frappe.log_error(title="Naarni request_otp failed")
		frappe.throw(_("Could not send the code. Please try again."), frappe.ValidationError)

	return {"success": True, "data": {"sent": True}, "message": _("Code sent.")}


@frappe.whitelist(allow_guest=True)
def verify_otp(phone: str, otp: str, device_uuid: str | None = None, platform: str = "ANDROID") -> dict:
	"""Verify phone + OTP via Naarni, then establish a Frappe session.

	`device_uuid` MUST be the same device passed to `request_otp` (Naarni keys the
	OTP on contact + device). On success: exchanges the OTP for Naarni tokens,
	finds-or-creates the Frappe user keyed by phone, stores the Naarni user UUID,
	and logs the user in (sets `sid`).
	"""
	if not naarni_client.is_login_enabled():
		frappe.throw(_("Phone login is temporarily unavailable."), frappe.ValidationError)
	if not (otp or "").strip():
		frappe.throw(_("Enter the code."), frappe.AuthenticationError)

	normalized = normalize_phone(phone)

	try:
		tokens = naarni_client.exchange_phone_otp(
			phone.strip(), otp.strip(), device_uuid=device_uuid, platform=platform
		)
	except naarni_client.NaarniApiError:
		frappe.throw(_("That code is invalid or expired."), frappe.AuthenticationError)

	# Best-effort: verify signature (iff a public key is configured) and read claims.
	claims = naarni_client.verify_access_token(tokens["access_token"])
	naarni_uuid = claims.get("sub")
	authorities = claims.get("authorities") or []

	user = _provision_naarni_user(normalized, naarni_uuid, authorities)

	# If a Naarni admin logs in and the vehicle-sync service account isn't seeded
	# yet, bootstrap it from this login so the whole fleet starts flowing into
	# Frappe — no manual console step needed. Best-effort; never blocks login.
	_maybe_bootstrap_vehicle_sync(authorities, tokens, device_uuid)

	lm = LoginManager()
	lm.user = user
	if getattr(frappe.local, "request", None) is not None:
		lm.post_login()
	else:  # non-HTTP callers (tests/console)
		frappe.set_user(user)

	doc = frappe.get_cached_doc("User", user)
	return {
		"success": True,
		"data": {
			"user": user,
			"full_name": doc.full_name,
			"user_type": doc.user_type,
			"roles": frappe.get_roles(user),
		},
		"message": _("Logged in."),
	}


def _is_naarni_admin(authorities: list) -> bool:
	"""True if the Naarni claims grant an admin role (vehicle endpoints need ADMIN)."""
	return any("ADMIN" in str(a).upper() for a in (authorities or []))


def _maybe_bootstrap_vehicle_sync(authorities: list, tokens: dict, device_uuid: str | None) -> None:
	"""Seed the vehicle-sync service account from an admin's OTP login (once).

	The vehicle directory is ADMIN-gated, so the sync needs an admin's token. Rather
	than a manual console bootstrap, the first Naarni admin to log in donates their
	90-day refresh token + this device as the service credentials, enables the
	integration, and triggers an immediate sync. Idempotent (skips once seeded) and
	fully guarded so a failure here never breaks login.
	"""
	try:
		if not _is_naarni_admin(authorities):
			return
		access = (tokens or {}).get("access_token")
		if not access:
			return

		from frappe.installer import update_site_config

		first_time = not (frappe.conf or {}).get("naarni_service_token")
		# Refresh the stored service token on every admin login so it never goes
		# stale (the access token is ~30-day); enable + kick off a sync the first time.
		update_site_config("naarni_service_token", access)
		frappe.conf["naarni_service_token"] = access
		frappe.cache().delete_value("naarni_service_access_token")
		if first_time:
			update_site_config("enable_naarni_integration", 1)
			frappe.conf["enable_naarni_integration"] = 1
			frappe.enqueue(
				"vehicle_maintenance.integrations.naarni_vehicles.sync_vehicle_directory",
				queue="long",
				enqueue_after_commit=True,
			)
		frappe.logger("naarni").info("vehicle-sync service token refreshed from admin login")
	except Exception:
		frappe.log_error(title="Naarni vehicle-sync bootstrap from login failed")


def _provision_naarni_user(phone: str, naarni_uuid: str | None, authorities: list) -> str:
	"""Find-or-create the Frappe user for a Naarni phone login; return its name.

	Non-destructive: existing roles are preserved; we only *add* the default app
	role on first provisioning and keep the stored Naarni UUID fresh.
	"""
	existing = frappe.db.get_all(
		"User",
		filters={"mobile_no": phone},
		fields=["name", "enabled"],
		limit=2,
	)
	if len(existing) > 1:
		frappe.throw(_("Multiple accounts share this phone number. Contact an administrator."))

	if existing:
		user = existing[0].name
		if not existing[0].enabled:
			frappe.throw(_("This account is disabled. Contact an administrator."), frappe.AuthenticationError)
		if naarni_uuid and frappe.db.get_value("User", user, "naarni_user_uuid") != naarni_uuid:
			frappe.db.set_value("User", user, "naarni_user_uuid", naarni_uuid, update_modified=False)
		return user

	# Auto-provision. Phone-only identity: synthesise a stable, non-routable email
	# (Frappe requires User.name to be an email) and grant the default app role.
	default_role = (frappe.conf or {}).get("naarni_default_role") or DEFAULT_NAARNI_ROLE
	email = f"{phone}@naarni.phone"
	doc = frappe.new_doc("User")
	doc.email = email
	doc.first_name = phone
	doc.mobile_no = phone
	doc.user_type = "System User"
	doc.send_welcome_email = 0
	if naarni_uuid:
		doc.naarni_user_uuid = naarni_uuid
	doc.append("roles", {"role": default_role})
	doc.flags.ignore_permissions = True
	doc.insert(ignore_permissions=True)
	return doc.name
