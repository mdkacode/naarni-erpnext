# Postman Collection — Vehicle Maintenance API

Two files:

| File | Purpose |
|---|---|
| `vehicle-maintenance.postman_collection.json` | Requests grouped by module (Auth, Job Card, Customer, Inventory, Reports, Photos, Notifications, Health Card, CRM) |
| `vehicle-maintenance.postman_environment.json` | Environment with `base_url`, credentials, and placeholders for session cookies / API keys |

## Import

1. Postman → **Import** → drop both files
2. Select the environment in the top-right dropdown
3. Edit `phone` + `password` in the environment with your credentials

## First-time flow

1. Run **Auth → Login with Phone ✅**
   - Test script captures `sid`, `system_user`, `user`, `full_name` into env vars
2. Run **Auth → Get CSRF Token ✅**
   - Captures `csrf_token` into env
3. Any other request now uses `{{sid}}`, `{{system_user}}`, `{{csrf_token}}` automatically via the Cookie + `X-Frappe-CSRF-Token` headers

## Legend

- ✅ endpoint is live on `service.naarni.com` today
- 🆕 endpoint is **pending backend work** (mobile team needs this for the Android app) — Postman entry is a placeholder with the expected request shape

Pending endpoints are tracked in [`MOBILE_APP_FEATURE_DOC.md §14`](../../MOBILE_APP_FEATURE_DOC.md#14-backend-additions-needed-for-mobile).

## Using with the VM IP (before DNS)

The collection's pre-request script auto-adds the `Host` header when you change `base_url` to a raw IP. So you can test against `http://20.219.136.19` directly:

1. In the environment, set `base_url` = `http://20.219.136.19`
2. Keep `site` = `service.naarni.com`
3. Run any request — the script attaches `Host: service.naarni.com` so nginx routes to the right site

## Token auth (once issue_api_key ships)

After the 🆕 `issue_api_key` endpoint is live:

1. Run it once → save the returned `api_key` and `api_secret` into env vars
2. On each request, Postman uses `Authorization: token {{api_key}}:{{api_secret}}` (header is already present on the 🆕 placeholder requests)
3. No more cookies or CSRF needed — better match for mobile client behavior

## Regeneration

This collection is hand-maintained. When a new `@frappe.whitelist()` method lands in `vehicle_maintenance.api.*`, update the collection in the same PR — a future helper script will generate it from source, but for now treat it as source of truth for the mobile team.
