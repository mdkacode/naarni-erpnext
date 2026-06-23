# Naarni integration (auth SSO + vehicle directory)

Integrates the Frappe app with the **Naarni backend** (the `analytics-service`
repo, served at `https://api.naarni.com/api`) for:

- **Unified OTP login** — users sign in with phone + OTP; Frappe brokers the OTP
  against Naarni and establishes a Frappe session. No passwords.
- **Vehicle directory** — operators, status, depot and live **odometer / running-km**
  + telemetry, sourced from Naarni's `vehicle_recent_info_silver` tables.

Everything is **inert until configured**: with `enable_naarni_integration` unset,
`request_otp`/`verify_otp` return "temporarily unavailable", the sync is a no-op,
and the existing phone+password login (`login_with_phone`) is untouched.

## Architecture

| Concern | Approach |
|---|---|
| Login | **Unified OTP SSO**, Frappe-brokered. App → Frappe `request_otp`/`verify_otp` → Naarni `/v1/auth/*`. Frappe knows the phone (so it maps phone→user directly; the JWT `sub` is an opaque UUID). |
| Service auth | **Naarni service account.** A 90-day refresh token in site_config mints short-lived access tokens (`service_token()`) for the ADMIN-gated vehicle endpoints. |
| Vehicle data | **Sync catalog + live detail.** A scheduled job upserts `/v1/analytics/vehicles` into the Frappe `Vehicle` doctype (fast local dropdown); job-card form-open fetches `/v1/analytics/vehicles/{id}` for the live odometer/operator. |

## One-time bootstrap (console only — handles secrets)

```bash
bench --site <site> console
```

```python
from vehicle_maintenance.integrations import setup_naarni
setup_naarni.bootstrap_device()                      # registers broker device
setup_naarni.bootstrap_request_otp("9876543210")     # service-account phone
setup_naarni.bootstrap_complete("9876543210", "123456")  # OTP just received
setup_naarni.enable()                                # flip the master switch

from vehicle_maintenance.integrations import naarni_vehicles
naarni_vehicles.sync_vehicle_directory()             # first pull
setup_naarni.status()                                # confirm config (no secrets printed)
```

Optionally also set, for defence-in-depth JWT verification:

```bash
bench --site <site> set-config -g naarni_jwt_public_key "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"
bench --site <site> set-config naarni_jwt_issuer "<iss>"
```

If the Naarni `client_id` differs from the default `naarni-app`:

```bash
bench --site <site> set-config naarni_client_id "<client id>"
```

## Verified (read-only, against production)

- `GET /api/v1/analytics/vehicles` → `401` (deployed + ADMIN-gated)
- `POST /api/v1/auth/token` `grant_type=phone` → `401 "Invalid phone"` (grant live)
- `POST /api/v1/auth/otp/generate` `{}` → `400 "Required header 'x-device-id' is not present"`

## Files

- `naarni_client.py` — the only place that speaks Naarni's HTTP contract.
- `naarni_vehicles.py` — directory sync + live per-vehicle detail; `sync_now` (admin) + scheduled `sync_vehicle_directory`.
- `setup_naarni.py` — one-time bootstrap helpers.
- `../api/auth.py` — `request_otp` / `verify_otp` whitelisted endpoints.
- `../patches/v1_1/seed_naarni_custom_fields.py` — `User.naarni_user_uuid` custom field.
