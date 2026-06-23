"""One-time, console-only bootstrap for the Naarni integration.

Run these from `bench --site <site> console` (NOT over HTTP — they read/write
secrets to site_config). Order:

    from vehicle_maintenance.integrations import setup_naarni

    # 1. Register the shared broker device (writes uuid + numeric id to site_config).
    setup_naarni.bootstrap_device()

    # 2. Send an OTP to the Naarni service-account phone.
    setup_naarni.bootstrap_request_otp("9876543210")

    # 3. Exchange the OTP -> stores the 90-day service refresh token.
    setup_naarni.bootstrap_complete("9876543210", "123456")

    # 4. Flip the master switch and pull the directory once.
    setup_naarni.enable()
    from vehicle_maintenance.integrations import naarni_vehicles
    naarni_vehicles.sync_vehicle_directory()

`status()` prints what's configured (never the secret values) for diagnostics.
"""

from __future__ import annotations

import uuid as uuidlib

import frappe
from frappe.installer import update_site_config

from vehicle_maintenance.integrations import naarni_client


def bootstrap_device(device_type: str = "MOBILE_APP") -> dict:
	"""Register a fresh broker device with Naarni and persist its uuid + numeric id."""
	device_uuid = str(uuidlib.uuid4())
	device_id = naarni_client.register_device(device_uuid, device_type=device_type)
	update_site_config("naarni_broker_device_uuid", device_uuid)
	update_site_config("naarni_broker_device_id", device_id)
	print(f"Broker device registered: uuid={device_uuid} id={device_id}")
	return {"device_uuid": device_uuid, "device_id": device_id}


def bootstrap_request_otp(phone: str) -> None:
	"""Send a login OTP to the service-account phone (uses the broker device)."""
	naarni_client.request_otp(phone.strip())
	print(f"OTP sent to {phone}. Call bootstrap_complete(phone, otp) next.")


def bootstrap_complete(phone: str, otp: str) -> dict:
	"""Exchange phone+OTP for tokens and store the refresh token in site_config."""
	tokens = naarni_client.exchange_phone_otp(phone.strip(), str(otp).strip())
	refresh = tokens.get("refresh_token")
	if not refresh:
		raise RuntimeError(
			"Naarni returned no refresh_token — the service client must allow the "
			"refresh_token grant. Got keys: " + ", ".join(tokens.keys())
		)
	update_site_config("naarni_service_refresh_token", refresh)
	claims = naarni_client.decode_claims_unverified(tokens["access_token"])
	print(
		"Service refresh token stored. Account sub="
		f"{claims.get('sub')} authorities={claims.get('authorities')}"
	)
	return {"sub": claims.get("sub"), "authorities": claims.get("authorities")}


def enable() -> None:
	update_site_config("enable_naarni_integration", 1)
	print("enable_naarni_integration = 1")


def disable() -> None:
	update_site_config("enable_naarni_integration", 0)
	print("enable_naarni_integration = 0")


def status() -> dict:
	"""Print which Naarni keys are set (booleans only — never the secret values)."""
	conf = frappe.conf or {}
	report = {
		"enabled": bool(conf.get("enable_naarni_integration")),
		"base_url": naarni_client.base_url(),
		"client_id": naarni_client.client_id(),
		"broker_device_uuid_set": bool(conf.get("naarni_broker_device_uuid")),
		"broker_device_id_set": bool(conf.get("naarni_broker_device_id")),
		"service_refresh_token_set": bool(conf.get("naarni_service_refresh_token")),
		"jwt_public_key_set": bool(conf.get("naarni_jwt_public_key")),
	}
	for k, v in report.items():
		print(f"  {k}: {v}")
	return report
