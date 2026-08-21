# Monthly KM Report & SLA Management — Access & Runbook

_Built 2026-07-02. Frappe deployed to prod (`service.naarni.com`); Java `km-daily` endpoint merged to `main` + rolled out to EKS. See "Deploy status" at the bottom._

## What it does
Replaces the old manually-maintained Google-Sheet KM/SLA tabs with honest, telematics-driven reports **computed in Frappe** from per-day odometer facts pulled from the Naarni analytics backend.

- **Monthly KM Report** — per vehicle, month > week > day: Start KM (odometer at 12:00 AM IST), End KM (11:59 PM IST), distance, billable KM. Service/breakdown days can be excluded by Ops and are not counted.
- **SLA Management** — per-vehicle uptime = active days ÷ (calendar days − service/breakdown days); breach when below the customer's contract target.

---

## How to access the reports

### 1. Public monthly report link (customers / stakeholders)
- Every month on the **1st at 10:00 AM IST**, each enabled customer's stakeholders are emailed a **white-labelled** report (their logo + name, "powered by NaArNi") for the **previous month**, via Brevo.
- The email's **"View KM Report"** button opens a public page: `https://service.naarni.com/km-report/<token>`
  - No login. Copies the webApp look. Shows KM totals, month-on-month trend, an **SLA/uptime** card, and the per-vehicle table.
  - The link is **active for 7 days**, then shows a friendly "link has expired" card.
- To (re)generate a report + fresh link on demand (Ops): run the API `regenerate_snapshot` (below) or the Desk action.

### 2. Desk reports (Ops / Depot Manager — internal)
In Frappe Desk → **Reports**:
- **KM Billing Report** — filters: Customer + Month (YYYY-MM). Per-vehicle Start Odo, End Odo, Distance, Billable KM, Active/Excluded/Service/Breakdown days.
- **SLA Report** — filters: Customer + From/To dates (any range = day/week/month view). Per-vehicle Uptime %, Target, Status (green ≥ target / red breach), Active/Calendar days.

### 3. Whitelisted APIs (SPA / integrations) — all permission-checked
Base: `POST /api/method/vehicle_maintenance.api.km_reports.<fn>`
- `get_km_report(customer, year_month)` → month rollup + totals + 6-month trend.
- `get_vehicle_km_breakdown(vehicle, year_month)` → week + day drill-down.
- `get_sla_dashboard(customer, start, end)` → per-vehicle uptime + fleet summary for any range.
- `correct_km_day(row_name, is_excluded, exclusion_reason, override_distance, corrected_distance, notes)` → apply an Ops correction.
- `regenerate_snapshot(customer, year_month)` → rebuild the snapshot + return a fresh public URL (Ops only).

### 4. Raw daily data (Ops) — DocType **Vehicle KM Daily**
One row per vehicle per day (synced read-only). To **exclude a day** (vehicle in service/breakdown) or **override the distance**, open the row and use the **Operator Correction** section: tick "Exclude this day" + pick a Reason, or tick "Override distance" + enter a value. Corrections survive the daily re-sync.

---

## Set-up (per customer) — DocType **Fleet Report Config**
One record per Customer controls branding, thresholds and recipients:
- **Send monthly report** (enable/disable), **Brand / Display Name**, **Logo** (shown on the report + email).
- **Min KM per Vehicle** (a reference line on the chart — not a billing floor), **Contract Uptime Target %** (default 95; SLA breach threshold).
- **Stakeholders** table — the email recipients.
- Per-vehicle overrides live on the **Vehicle** doctype: `Min KM per Month (override)`, `Uptime Target % (override)`.

---

## How the data flows
```
Naarni backend  POST /api/v1/analytics/km-daily  (Trino energy_mileage_daily, IST-bucketed)
      │  daily rolling sync (idempotent; never clobbers corrections)
      ▼
Frappe: Vehicle KM Daily  ──►  km_report.py / sla.py rollups (honor exclusions)
      │                              │
      ▼                              ▼
KM Report Snapshot (7-day token) + white-label email    Desk reports / APIs
      ▼
Public page /km-report/<token>
```
Scheduled jobs (in `hooks.py`): daily KM sync `30 1 * * *`, monthly report `0 10 1 * *` (IST), hourly token-expiry sweeper.

---

## Prod configuration checklist (run once on the Azure VM: `ssh frappe@<VM_HOST>`, `cd ~/frappe-bench`)
> The KM sync is a safe no-op until the Naarni integration is enabled, so nothing breaks before these are done.

1. **Confirm the report cron fires at 10:00 AM IST** — the cron `0 10 1 * *` runs in the site timezone:
   ```
   bench --site service.naarni.com execute "frappe.db.get_single_value" --args "['System Settings','time_zone']"
   ```
   - If `Asia/Kolkata` → nothing to do.
   - If `UTC` → either set the site timezone to Asia/Kolkata, **or** change the cron in `hooks.py` to `30 4 1 * *` (= 10:00 IST).
2. **Ensure the Naarni integration is enabled + has a service token** (needed for the KM sync):
   ```
   bench --site service.naarni.com console      # then: frappe.conf.get("enable_naarni_integration")
   ```
   If not enabled, bootstrap it the same way vehicle sync was set up (`integrations.naarni_vehicles.connect_naarni` with an admin token / set `naarni_service_token` + `enable_naarni_integration`).
3. **Backfill history** (once the km-daily endpoint is live in prod):
   ```
   bench --site service.naarni.com execute vehicle_maintenance.integrations.naarni_km_daily.sync_km_daily_backfill --kwargs "{'start':'2026-06-01','end':'2026-06-30'}"
   ```
4. **Create a Fleet Report Config** for each customer (logo, target, stakeholders) so the monthly email goes out.

Smoke test (no waiting for cron):
```
bench --site service.naarni.com execute vehicle_maintenance.fleet_service.tasks.send_monthly_km_reports
```

---

## Deploy status — ✅ COMPLETE & VERIFIED (2026-07-02)
- **Frappe → Azure (`service.naarni.com`):** ✅ DEPLOYED via `Deploy to Azure VM` (PR #32 → develop). Verified: site `200`, `/km-report/<bad-token>` → `404` (route + page live, correct no-leak behaviour). New DocTypes + Vehicle custom fields migrated on prod.
- **Java `km-daily` → EKS (`naarni-services/naarni-backend`):** ✅ ROLLED OUT. PR #18 → `main`; image built (ECR `naarni-backend-development:32f5119a2f22e8f5b025921c87d5fa636f10d021`); `kubectl set image` rolling update — new pod `585ff6999d-*` healthy (1/1, 0 restarts, clean 45s startup). Verified: `POST /api/v1/analytics/km-daily` → `401` (live + guarded, not 404); existing `/analytics/vehicles` → `401` (no regression).
- **Only remaining step (needs Azure VM SSH — see "Prod configuration checklist"):** verify site timezone, ensure the Naarni integration is enabled + has a service token, and backfill history. Until then the daily KM sync safely no-ops; nothing is broken.
  - Rollback if ever needed: backend `kubectl -n naarni-services rollout undo deployment/naarni-backend` (prev image `…:79aa86b…`).

## Rollback (if ever needed)
- Backend: `kubectl -n naarni-services rollout undo deployment/naarni-backend`
- Frappe: revert the merge on `develop`; the deploy workflow re-runs on push. (Migrations are additive — new DocTypes/fields; safe to leave.)

## Source
- Frappe: `vehicle_maintenance/vehicle_maintenance/` — `fleet_service/{km_report,sla,monthly_km_report,tasks}.py`, `doctype/{vehicle_km_daily,fleet_report_config,report_stakeholder,km_report_snapshot}`, `www/km-report/`, `api/km_reports.py`, `integrations/naarni_km_daily.py`. 39 unit tests.
- Java: `analytics-service/.../analytics/...KmDaily*` + `AnalyticsController.getKmDaily`. 3 unit tests.
