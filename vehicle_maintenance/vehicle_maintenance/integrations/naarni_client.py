"""Low-level HTTP client for the Naarni backend (api.naarni.com).

The Naarni backend (the `analytics-service` repo — one Spring Boot app exposing
the `auth`, `analytics`, `fleet`, `user` modules) is the system of record for
**users/operators** and **live vehicle telemetry**. This module is the single
place that speaks its HTTP contract; everything else in the app calls these
functions, never `requests` directly.

Two distinct auth surfaces are used:

1. **Brokered user login (unified OTP SSO).** The app sends phone + OTP to *our*
   Frappe endpoints; Frappe relays to Naarni here. Because Frappe brokers the
   request it already knows the phone, so it maps phone -> Frappe user directly
   (the Naarni JWT's `sub` is an opaque user UUID, *not* the phone). See
   `request_otp()` / `exchange_phone_otp()`.

2. **Service account (machine-to-machine).** A single Naarni admin login whose
   90-day **refresh token** is stored in site_config. We mint short-lived access
   tokens from it (`service_token()`) to read the ADMIN-gated vehicle directory
   (`list_vehicles()` / `get_vehicle_detail()`).

Required site_config entries (never commit these — set with `bench set-config`):

    enable_naarni_integration     1                       # master on/off
    naarni_base_url               https://api.naarni.com/api  # optional, this default
    naarni_client_id              <oauth client id>
    naarni_broker_device_uuid     <uuid>                  # from setup_naarni.bootstrap
    naarni_broker_device_id       <numeric device id>     # from setup_naarni.bootstrap
    naarni_service_refresh_token  <90-day refresh token>  # from setup_naarni.bootstrap
    naarni_jwt_public_key         "-----BEGIN PUBLIC KEY-----\n..."  # optional hardening
    naarni_jwt_issuer             <iss>                   # optional, checked iff key set

Docs for the contract live in the analytics-service repo:
    auth/controller/AuthController.java         (/v1/auth/otp/generate, /v1/auth/token)
    auth/controller/DeviceController.java       (/v1/devices)
    analytics/controller/VehicleDirectoryController.java  (/v1/analytics/vehicles)
"""

from __future__ import annotations

import json
from typing import Any

import frappe
import requests

# The Naarni backend is served behind the `/api` ALB path prefix (helm
# API_BASE_PATH=/api). Verified live: GET /api/v1/analytics/vehicles -> 401,
# POST /api/v1/auth/token -> 401 "Invalid phone". Override per-site with
# `naarni_base_url` if a different environment is used.
DEFAULT_BASE_URL = "https://api.naarni.com/api"
DEFAULT_CLIENT_ID = "naarni-app"
DEFAULT_PLATFORM = "ANDROID"

# Exact header / param / grant names from analytics-service commons + auth Constants.
HEADER_DEVICE_ID = "x-device-id"
HEADER_PLATFORM = "x-platform"
GRANT_PHONE = "phone"
GRANT_REFRESH = "refresh_token"

# Cache the minted service access token a fraction of its real 30-day life so a
# rotated/revoked token self-heals quickly without a per-request round trip.
_SERVICE_TOKEN_CACHE_KEY = "naarni_service_access_token"
_SERVICE_TOKEN_TTL_SECS = 6 * 60 * 60  # 6h


class NaarniConfigError(RuntimeError):
	"""Required Naarni site_config is missing or malformed."""


class NaarniApiError(RuntimeError):
	"""Naarni returned a non-2xx response or an unparseable body."""


# ──────────────────────────── config ────────────────────────────


def _conf() -> dict:
	return frappe.conf or {}


def is_enabled() -> bool:
	"""Master switch for service-account features (vehicle directory sync).

	Off by default — the sync needs the service refresh token, so it stays inert
	until explicitly configured + enabled.
	"""
	return bool(_conf().get("enable_naarni_integration"))


def is_login_enabled() -> bool:
	"""Whether OTP login is available.

	OTP login only uses PUBLIC Naarni endpoints (device register + otp/generate +
	token) and needs no service account — so it is ON by default and works with
	zero site_config. Set `naarni_login_disabled` to 1 to turn it off (kill switch).
	"""
	return not bool(_conf().get("naarni_login_disabled"))


def base_url() -> str:
	return (_conf().get("naarni_base_url") or DEFAULT_BASE_URL).rstrip("/")


def client_id() -> str:
	return _conf().get("naarni_client_id") or DEFAULT_CLIENT_ID


def _timeout() -> tuple[int, int]:
	conf = _conf()
	return (int(conf.get("naarni_connect_timeout") or 5), int(conf.get("naarni_read_timeout") or 20))


def _broker_device() -> tuple[str, int]:
	"""(uuid, numeric_id) of the Frappe broker device shared by OTP login + service refresh."""
	conf = _conf()
	dev_uuid = conf.get("naarni_broker_device_uuid")
	dev_id = conf.get("naarni_broker_device_id")
	if not dev_uuid or not dev_id:
		raise NaarniConfigError(
			"naarni_broker_device_uuid / naarni_broker_device_id not set — "
			"run vehicle_maintenance.integrations.setup_naarni.bootstrap()"
		)
	return str(dev_uuid), int(dev_id)


def _require_enabled() -> None:
	if not is_enabled():
		raise NaarniConfigError("Naarni integration is disabled (enable_naarni_integration)")


def _require_login_enabled() -> None:
	if not is_login_enabled():
		raise NaarniConfigError("Naarni OTP login is disabled (naarni_login_disabled)")


# ──────────────────────────── transport ────────────────────────────


def _url(path: str) -> str:
	return f"{base_url()}/{path.lstrip('/')}"


def _parse(resp: requests.Response) -> Any:
	if resp.status_code >= 400:
		raise NaarniApiError(f"{resp.request.method} {resp.url} -> {resp.status_code}: {resp.text[:500]}")
	if not resp.content:
		return None
	try:
		return resp.json()
	except json.JSONDecodeError as exc:
		raise NaarniApiError(f"Non-JSON response from {resp.url}: {resp.text[:200]}") from exc


def _post_form(path: str, data: dict, headers: dict | None = None) -> Any:
	resp = requests.post(_url(path), data=data, headers=headers or {}, timeout=_timeout())
	return _parse(resp)


def _post_json(path: str, payload: dict, headers: dict | None = None) -> Any:
	h = {"Content-Type": "application/json"}
	h.update(headers or {})
	resp = requests.post(_url(path), data=json.dumps(payload), headers=h, timeout=_timeout())
	return _parse(resp)


def _get(path: str, bearer: str) -> Any:
	resp = requests.get(_url(path), headers={"Authorization": f"Bearer {bearer}"}, timeout=_timeout())
	return _parse(resp)


# ──────────────────────────── devices ────────────────────────────


def _device_type_for(platform: str) -> str:
	"""Map an x-platform value to a Naarni DeviceType enum (MOBILE_APP | WEB_BROWSER)."""
	return "WEB_BROWSER" if (platform or "").upper() in {"WEB", "BACKEND"} else "MOBILE_APP"


def register_device(
	device_uuid: str,
	device_type: str = "MOBILE_APP",
	*,
	metadata: dict | None = None,
	ip_address: str | None = None,
	push_token: str | None = None,
) -> int:
	"""Register (idempotently, by UUID) a device with Naarni; return its numeric id.

	`POST /v1/devices` is public and returns the existing device if the UUID is
	already known, so this is safe to call repeatedly. `device_type` is the Naarni
	DeviceType enum (MOBILE_APP | WEB_BROWSER) — distinct from the x-platform value
	(ANDROID/IOS/WEB/BACKEND) sent on the auth calls.

	NOTE (verified against prod): the backend 500s on a payload without `metadata`,
	so we always send a non-empty metadata object. The created device record is
	returned under the envelope's `body` key (NaarniHttpResponse), not `data`.
	"""
	payload: dict[str, Any] = {
		"deviceUuid": device_uuid,
		"type": device_type,
		"status": "ACTIVE",
		"metadata": metadata or {"source": "vehicle_maintenance", "deviceType": device_type},
	}
	if ip_address:
		payload["ipAddress"] = ip_address
	if push_token:
		payload["pushNotificationToken"] = push_token

	body = _post_json("/v1/devices", payload)
	env = body if isinstance(body, dict) else {}
	# NaarniHttpResponse: {"body": {"id": .., "deviceUuid": ..}, "success": true, ...}
	record = env.get("body") or env.get("data") or env
	if not isinstance(record, dict):
		record = {}
	device_id = record.get("id")
	if device_id is None:
		raise NaarniApiError(f"Device registration returned no id: {body}")
	return int(device_id)


def _resolve_device(device_uuid: str | None, platform: str = DEFAULT_PLATFORM) -> tuple[str, int]:
	"""Resolve (uuid, numeric_id) for the device to use on an OTP/token call.

	With a per-app `device_uuid` (the proper flow — each install owns a device),
	register it with Naarni (idempotent) and cache uuid->id so the OTP *request*
	and *verify* hit the same device (Naarni validates the OTP against
	contact + deviceId). With no uuid, fall back to the shared broker device
	(web / legacy callers / the service account).
	"""
	if not device_uuid:
		return _broker_device()
	cache = frappe.cache()
	cache_key = f"naarni_device_id:{device_uuid}"
	cached = cache.get_value(cache_key)
	if cached:
		return device_uuid, int(cached)
	device_id = register_device(device_uuid, device_type=_device_type_for(platform))
	cache.set_value(cache_key, device_id, expires_in_sec=30 * 24 * 60 * 60)
	return device_uuid, device_id


# ──────────────────────────── user OTP login (brokered) ────────────────────────────


def request_otp(phone: str, device_uuid: str | None = None, platform: str = DEFAULT_PLATFORM) -> None:
	"""Ask Naarni to send a login OTP to `phone` from the caller's device.

	`device_uuid` is the app install's own device id (registered on first use);
	omit it to use the shared broker device.
	"""
	_require_login_enabled()
	dev_uuid, dev_id = _resolve_device(device_uuid, platform)
	_post_json(
		"/v1/auth/otp/generate",
		{"contact": phone, "contactType": "PHONE", "deviceId": dev_id},
		headers={HEADER_DEVICE_ID: dev_uuid, HEADER_PLATFORM: platform},
	)


def exchange_phone_otp(
	phone: str, otp: str | int, device_uuid: str | None = None, platform: str = DEFAULT_PLATFORM
) -> dict:
	"""Verify phone+OTP against Naarni; return {'access_token', 'refresh_token'}.

	Must use the SAME device as the matching `request_otp` call (Naarni keys the
	OTP on contact + deviceId). Raises NaarniApiError on an invalid/expired OTP.
	"""
	_require_login_enabled()
	dev_uuid, dev_id = _resolve_device(device_uuid, platform)
	body = _post_form(
		"/v1/auth/token",
		{
			"grant_type": GRANT_PHONE,
			"phone": phone,
			"otp": str(otp),
			"device_id": str(dev_id),
			"client_id": client_id(),
		},
		headers={HEADER_DEVICE_ID: dev_uuid, HEADER_PLATFORM: platform},
	)
	if not isinstance(body, dict) or not body.get("access_token"):
		raise NaarniApiError(f"Token endpoint returned no access_token: {body}")
	return body


# ──────────────────────────── service account ────────────────────────────


def _mint_service_token() -> str:
	refresh_token = _conf().get("naarni_service_refresh_token")
	if not refresh_token:
		raise NaarniConfigError(
			"naarni_service_refresh_token not set — run "
			"vehicle_maintenance.integrations.setup_naarni.bootstrap()"
		)
	dev_uuid, dev_id = _broker_device()
	body = _post_form(
		"/v1/auth/token",
		{
			"grant_type": GRANT_REFRESH,
			"refresh_token": refresh_token,
			"device_id": str(dev_id),
			"client_id": client_id(),
		},
		headers={HEADER_DEVICE_ID: dev_uuid, HEADER_PLATFORM: "BACKEND"},
	)
	token = body.get("access_token") if isinstance(body, dict) else None
	if not token:
		raise NaarniApiError(f"Refresh grant returned no access_token: {body}")
	return token


def service_token(*, force_refresh: bool = False) -> str:
	"""A cached Naarni access token for the service account (minted via refresh grant)."""
	_require_enabled()
	cache = frappe.cache()
	if not force_refresh:
		cached = cache.get_value(_SERVICE_TOKEN_CACHE_KEY)
		if cached:
			return cached
	token = _mint_service_token()
	cache.set_value(_SERVICE_TOKEN_CACHE_KEY, token, expires_in_sec=_SERVICE_TOKEN_TTL_SECS)
	return token


def _get_with_service_token(path: str) -> Any:
	"""GET `path` with the service token, refreshing once on a 401/403."""
	try:
		return _get(path, service_token())
	except NaarniApiError as exc:
		if " 401:" in str(exc) or " 403:" in str(exc):
			return _get(path, service_token(force_refresh=True))
		raise


# ──────────────────────────── vehicles (analytics) ────────────────────────────


def list_vehicles() -> list[dict]:
	"""ADMIN vehicle directory for the service account's fleet.

	Each row: {vehicleId, registrationNumber, model, make, status, depotName, operator}.
	"""
	_require_enabled()
	body = _get_with_service_token("/v1/analytics/vehicles")
	return body if isinstance(body, list) else []


def get_vehicle_detail(vehicle_id: int | str) -> dict | None:
	"""Curated live detail for one vehicle (identity/location/battery/motor/distance/status).

	`distance.odometerreading` is the running-km figure used to auto-fill the
	job-card odometer. Returns None if the vehicle is not accessible / unknown.
	"""
	_require_enabled()
	body = _get_with_service_token(f"/v1/analytics/vehicles/{vehicle_id}")
	return body if isinstance(body, dict) else None


# ──────────────────────────── JWT verification (optional hardening) ────────────────────────────


def verify_access_token(token: str) -> dict:
	"""Verify a Naarni access token's RS256 signature and return its claims.

	Used only when `naarni_jwt_public_key` is configured. In the brokered flow the
	token already arrived server-to-server straight from Naarni over HTTPS, so this
	is defence-in-depth, not the primary trust boundary.

	Raises NaarniApiError if a key is configured but the token fails validation.
	"""
	public_key = _conf().get("naarni_jwt_public_key")
	if not public_key:
		# No key configured -> skip verification, decode claims best-effort for `sub`.
		return decode_claims_unverified(token)

	import jwt  # PyJWT, ships with Frappe

	issuer = _conf().get("naarni_jwt_issuer")
	options = {"verify_aud": False}
	try:
		return jwt.decode(
			token,
			public_key,
			algorithms=["RS256"],
			issuer=issuer if issuer else None,
			options=options,
		)
	except jwt.PyJWTError as exc:
		raise NaarniApiError(f"Naarni token failed verification: {exc}") from exc


def decode_claims_unverified(token: str) -> dict:
	"""Decode JWT claims WITHOUT signature verification (for reading `sub`/`authorities`)."""
	import jwt

	try:
		return jwt.decode(token, options={"verify_signature": False})
	except jwt.PyJWTError:
		return {}
