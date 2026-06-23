# NAARNI Service Engineer App — Native Kotlin (Android) Plan

> **Goal.** A brand-new **native Kotlin Android app** for **Service Engineers**, built to be
> **extremely fast and usable on low-end devices**, talking to the existing Frappe
> `vehicle_maintenance` backend. Every input is **auto-filled or a searchable dropdown** (the
> product's non-negotiable). It adds **geo/time/user-stamped photos**, an **Alerts tab** wired to the
> existing Frappe alerts engine, **Tickets** auto-raised from alerts for the bus/depot assigned to a
> service, and **deeplinks** so a tapped alert/ticket/notification opens the exact screen.
>
> **Sequencing (per product owner):** finalize this plan now; **start building the app only after the
> backend is deployed.** §9 lists the backend additions that must ship/deploy first.
>
> This supersedes the "React Native" mention in `IMPLEMENTATION_PLAN.md` §6 — the App is **native
> Kotlin**.

---

## 0. Decisions baked in (and the one open fork)

**Baked-in defaults** (chosen for speed on low-end devices; change before build if you disagree):
- **Language/UI:** Kotlin + **Jetpack Compose** (Material 3), single-Activity. Compose 1.6+ with R8
  full-mode + **Baseline Profiles** is fast on low-end; if profiling on a target device shows jank we
  fall back to Views for the 1–2 hottest screens only. Rationale in §3.
- **minSdk 24 (Android 7.0)**, targetSdk latest. Covers ~99% of in-field devices; keeps APK small.
- **APK budget < 12 MB**, cold start < 1.5s on a 2 GB-RAM device. Enforced (§3).
- **Offline-first**: Room cache + WorkManager upload queue. A technician in a no-signal depot can
  capture photos and complete a card; it syncs when back online.
- **Auth:** phone login → Frappe **API key/secret** token stored in EncryptedSharedPreferences
  ([[feedback-phone-login]]).

**The one open decision (recommended default chosen, flag if wrong):**
- **"Ticket" model.** Recommended: a thin **`Service Ticket`** DocType auto-created from an Alert
  Event (and later from SE/customer/driver per PRD Phase 2), assigned to the affected bus's depot +
  SE, convertible to a Job Card. *Alternative:* reuse **Alert Event** itself as the ticket (it already
  has `status` + `job_card` + location). I went with **Service Ticket** for extensibility; it's a
  10-minute swap to the alternative if you prefer fewer doctypes. Everything below assumes Service
  Ticket.

---

## 1. What the app must do (requirements → where handled)

| Requirement (your words) | Where in this plan |
|---|---|
| New native app in **Kotlin**, **extremely fast**, **low-end devices** | §2 architecture, §3 performance |
| Photo: stamp **date-time + LAT/LONG** bottom-left, clearly visible | §5 camera & stamping |
| Photo also shows **name of the user who clicked it** | §5 |
| **Everything auto-fill or dropdown** | §4 (reuses `get_job_card_form_context` + `SmartSelect` native) |
| Alerts system already on Frappe → **separate Alerts tab** | §6 |
| Bus/**depot assigned to a service** gets **alerts + tickets** | §7 + §9 backend |
| **Deeplink** from alert/ticket/notification → the exact ticket screen | §8 |
| Push notifications reach SE instantly | §8 (FCM, reuses Push Token backend already built) |
| **UI proper, easy to use**, for Service Engineers | §10 screens |
| Start after **backend deployed** | §9 (pre-deploy backend work) + §11 roadmap |

---

## 2. Architecture

**Pattern:** MVVM + a light Clean split (`ui` → `domain` → `data`), single-Activity, Compose
Navigation. Repository pattern; one source of truth = Room, network fills/refreshes it.

```
app/
 ├─ di/                 Hilt modules (or manual DI if we cut Hilt for size)
 ├─ core/
 │   ├─ network/        Retrofit + OkHttp + kotlinx.serialization; FrappeApi; TokenInterceptor
 │   ├─ auth/           SessionManager (EncryptedSharedPreferences), login
 │   ├─ realtime/       Socket.IO client → "vm_notification" (mirrors web)
 │   ├─ location/       FusedLocationProvider wrapper
 │   ├─ camera/         CameraX capture + StampRenderer (§5)
 │   └─ sync/           WorkManager workers (upload queue, pull refresh)
 ├─ data/
 │   ├─ db/             Room: JobCard, Ticket, AlertEvent, Draft, PendingUpload, Master caches
 │   ├─ remote/         DTOs + mappers (Frappe {success,data} envelope)
 │   └─ repo/           JobCardRepo, TicketRepo, AlertRepo, MasterRepo, NotificationRepo
 ├─ domain/             Use-cases (CreateJobCard, StampAndUploadPhoto, AcknowledgeTicket, …)
 └─ ui/
     ├─ navigation/     NavHost, deeplink graph (§8)
     ├─ components/     SmartSelect, PhotoCaptureButton, SlaBadge, StatusChip, EmptyState
     ├─ home/  jobcards/  alerts/  tickets/  profile/   (one package per bottom-nav tab)
     └─ theme/          Material 3, large tap targets, high contrast
```

**Networking:** Retrofit to the Frappe whitelisted methods (`/api/method/vehicle_maintenance.*`).
`TokenInterceptor` adds `Authorization: token <key>:<secret>`. All responses unwrap the standard
`{success, data, message}` envelope; `data` maps to DTOs. 4xx `frappe.throw` messages surface as
user-friendly errors.

**Libraries (small, battle-tested only — every dep justified for the APK budget):**
Retrofit + OkHttp + kotlinx.serialization; Room; WorkManager; CameraX; play-services-location;
Coil (lightweight image loading); DataStore/EncryptedSharedPreferences; Socket.IO-client; Hilt
(optional). **No** heavy chart/UI kitchen-sink libs.

---

## 3. Performance on low-end devices (hard budget, not aspiration)

- **Compose discipline:** stable params, `remember`/`derivedStateOf`, lazy lists with keys, no
  allocation in composition. Strong-skipping mode on.
- **Baseline + Startup Profiles** generated via Macrobenchmark → AOT-compile hot paths; measurably
  cuts cold start and first-frame on low-end.
- **R8 full mode + resource shrinking**; per-ABI splits or App Bundle; target **< 12 MB** download.
- **Images:** capture at a sane resolution (e.g. max 1600px long edge), JPEG ~80% — small uploads,
  fast render; Coil with memory+disk cache and downsampling. Never load full-res into a list.
- **DB-first UI:** screens render from Room instantly; network refresh updates in place (no spinners
  blocking the field worker).
- **Cold start < 1.5s, scroll 60fps, create-card flow usable on 2 GB RAM / Android 7.** Verified on a
  low-end physical device each milestone (§12).
- **Battery/data aware:** WorkManager constraints (unmetered for bulk photo sync optional), GPS only
  during capture, socket only in foreground (push covers background).

---

## 4. Auto-fill & dropdowns (the non-negotiable, native)

Reuses the backend already built this sprint:
- After the SE picks a **vehicle** + **job card type**, the app calls **`get_job_card_form_context`**
  → fills vehicle/customer/OEM/depot/contract/service type/priority/check-sheet/last-PMS and returns
  `suggested_complaints` / `suggested_subsystems`. The SE types **nothing** else.
- **`SmartSelect` (Compose component)** = a bottom-sheet searchable list: opens with suggestions
  before typing, big 48dp+ rows, label+sublabel+badge, optional "＋ Add". Backed by the search
  endpoints: `search_vehicles`, `search_depots`, `list_subsystems`, `list_part_groups`,
  `list_complaints`, `list_fault_codes`, `list_observation_templates`, `list_users_by_role`.
- **Master caches** (Subsystem, Part, Part Group, Complaint, Fault Code, Observation, Depot) sync to
  Room on login + daily, so dropdowns are instant and work offline.
- **Rule enforced in code review:** no free-text `TextField` on a create form except an optional
  final note; everything else is SmartSelect or auto-filled + read-only.

---

## 5. Camera: geo + time + user stamping (a headline feature)

**Capture pipeline (CameraX `ImageCapture`):**
1. On shutter, grab a **fresh location** from `FusedLocationProviderClient` (high accuracy, with a
   short timeout + last-known fallback; if denied/unavailable, stamp `Location: unavailable` rather
   than block — soft gate per [[feedback-demo-blockers]]).
2. Decode to a mutable Bitmap; **`StampRenderer`** draws a stamp block in the **bottom-left**:
   ```
   ┌───────────────────────────────────────┐
   │ ▓▓ 22 Jun 2026, 14:32:07 IST           │   ← white bold on a 60% black rounded bar
   │ ▓▓ 12.97123, 77.59456  (±8 m)          │   ← lat, long (6 dp) + accuracy
   │ ▓▓ By: Ravi Kumar (Service Engineer)   │   ← logged-in user's full name + role
   └───────────────────────────────────────┘
   ```
   - Font size scales to image width (~3.5%) so it's **clearly visible** on any screen.
   - Semi-transparent dark rounded background bar for contrast on light/dark photos.
   - Optional small NAARNI watermark/job-card-number top-right (config).
3. Also write **EXIF GPS + DateTimeOriginal** into the JPEG (machine-readable, survives crop).
4. Save to app storage; enqueue a **WorkManager upload** → Frappe `upload_file`, attached to the
   target (Job Card field / repair-item pre/post / inspection item / breakdown / ticket). Offline =
   queued, retried with backoff; the UI shows "1 photo pending sync".

**User name source:** `SessionManager` holds the logged-in user's `full_name` + primary role (from
login). No extra call. **Tamper-resistance:** stamp is burned into pixels *and* EXIF; server records
the uploading user independently, so the on-image "By:" can be cross-checked.

**Permissions:** Camera + Fine Location requested with a clear rationale screen; if location is
permanently denied, capture still works but stamps "Location unavailable" and flags the photo.

---

## 6. Alerts tab (wired to the existing Frappe alerts engine)

The alerts system is **already built** on Frappe (Alert Type / Alert Subscription / **Alert Event** /
Naarni engine ingest). The app does **not** reimplement it — it **reads** it.

**Alerts tab shows:** a live, filterable list of **Alert Events** for the buses/depots the SE covers
(severity color, parameter+value vs threshold, vehicle reg, time, **map pin** from
`latitude/longitude/maps_link`, status Open/Acknowledged/Resolved). Tap → Alert detail with the
reading, location map, history for that bus, and actions: **Acknowledge**, **Create Ticket / Job
Card**, open linked Job Card if `job_card` is set.

**Data:** Alert Event already has everything we need (`vehicle, registration_number, device_id,
severity, status, parameter, op, value, threshold, unit, message, latitude, longitude, maps_link,
occurred_at, job_card`). The only gap is **scoping to the SE** — see §7/§9 (`get_my_alert_events`).

**Subscriptions sub-screen (optional for SE):** reuse `get_alert_catalog` / `get_my_subscriptions` /
`upsert_subscription` read-mostly so the SE can see what's monitored (editing stays
customer/admin-led).

---

## 7. Tickets — bus/depot assigned to a service gets alerts **and** tickets

**Concept.** A **Service Ticket** is the actionable item an SE works from. It is **auto-raised when an
alert fires** for a bus whose depot is assigned to that SE, and can also be created manually (and
later from customer/driver, PRD Phase 2). It carries the alert context + location, has a status
lifecycle, and **converts into a Job Card** (Breakdown/Repair) in one tap.

**Auto-raise flow (server-side, in `ingest_alert_event`):**
1. Alert Event ingested for `vehicle` → resolve the vehicle's **depot** and that depot's **on-duty
   SE** (§9 adds this mapping).
2. Create/with-dedup a **Service Ticket** linked to the Alert Event, vehicle, depot, assigned SE,
   severity, and a `deeplink`.
3. Fire an **instant notification** (realtime `vm_notification` + FCM push) to that SE with a
   **deeplink payload** → tapping opens the ticket (§8).

**Tickets tab:** the SE's open/ack tickets, sorted by severity+age, with SLA age; tap → Ticket detail
(alert reading, map, bus history, "Acknowledge", "Resolve", **"Create Job Card from Ticket"** which
pre-fills the create wizard via `get_job_card_form_context` and links back). Resolving a ticket
without a job card requires a reason.

---

## 8. Deeplinks & push (tap → exact screen)

**Deeplink scheme:** custom `naarni://` + HTTPS App Links to the portal domain (fallback to web).
- `naarni://ticket/{id}` → Ticket detail
- `naarni://jobcard/{name}` → Job Card detail
- `naarni://alert/{id}` → Alert detail
Compose Navigation declares these in the nav-graph with `deepLink {}`; `AndroidManifest` registers
the scheme + (optional) verified App Links for `https://<portal>/service-portal/...`.

**Push payload (extends the FCM `data` already sent by the backend):**
```json
{ "type": "ticket|jobcard|alert", "id": "<name>", "priority": "Critical",
  "title": "...", "body": "..." }
```
A `FirebaseMessagingService` builds a notification whose tap `PendingIntent` carries the deeplink →
the right screen opens directly, even from cold start. Token registered via the **already-built**
`register_push_token` endpoint; backend push send is the **already-built** `_dispatch_push`/FCM path.

**Realtime in foreground:** Socket.IO subscribes to `vm_notification` (built this sprint) → in-app
toast + bell badge without a push round-trip.

---

## 9. Backend additions required BEFORE deploy (the gate)

These ship to the Frappe app and deploy first; only then does app coding start.

1. **`Service Ticket` DocType** — fields: `vehicle`, `registration_number`, `depot`, `assigned_to`
   (SE/User), `alert_event` (Link), `severity`, `status` (Open/Acknowledged/Resolved), `title`,
   `message`, `latitude`, `longitude`, `maps_link`, `job_card` (Link), `source` (Alert/Manual/
   Customer/Driver), `dedup_key`, `acknowledged_at`, `resolved_at`, `resolution_reason`,
   `deeplink`. Naming `TKT-.YYYY.-.#####`.
2. **Bus → Depot → SE assignment** (today Vehicle has no depot; alerts are per-customer):
   - Add `depot` (Link) to **Vehicle** (a bus's home depot), or a small `Vehicle Depot Assignment`.
   - A way to resolve a depot's **on-duty/assigned SE(s)** — e.g. `assigned_service_engineers`
     (Table MultiSelect of User) on **Depot**, or a Depot Assignment doctype. Keep it simple: a
     multiselect on Depot.
3. **Extend `ingest_alert_event`** — after recording the Alert Event, resolve depot+SE, create the
   Service Ticket (dedup), and dispatch realtime+push with the `{type:'ticket', id}` deeplink.
4. **New whitelisted endpoints** (role-checked, envelope-shaped):
   - `get_my_alert_events(severity?, status?, limit, offset)` — alert events scoped to the SE's
     depots/buses (the §6 feed).
   - `get_my_tickets(status?, limit, offset)`, `get_ticket(name)`, `acknowledge_ticket(name)`,
     `resolve_ticket(name, reason)`, `create_job_card_from_ticket(name, job_card_type)`.
   - `acknowledge_alert_event(name)` (sets Alert Event status).
5. **Mobile token issue** — confirm phone login returns/creates an **api_key/api_secret** for the
   `Authorization: token` header (extend `api/auth.login_with_phone` or add `issue_api_keys`).
6. **Already done this sprint (reuse, no work):** `get_job_card_form_context`; `list_complaints/
   fault_codes/observation_templates`; suggestion masters; `register_push_token` + FCM
   `_dispatch_push`; `vm_notification` realtime; notification bell API; 1-min SLA cadence.

Each addition: docstring, `frappe.only_for`/`has_permission`, plain-dict envelope, `_()` strings,
fixtures committed, `bench migrate` + `bench run-tests` green (CLAUDE.md §6/§13). Never touch core.

---

## 10. Screens (Service-Engineer-first, easy to use)

**Bottom nav (5 tabs):** Home · Job Cards · **Alerts** · **Tickets** · Profile.

- **Login** — +91 phone + password (or OTP later); big inputs; remembers session.
- **Home** — greeting; **SLA-at-risk** cards first; my open job cards + tickets count; one giant
  **"＋ Create Job Card"** button; sync status pill.
- **Job Cards list** — filter chips (type/state/SLA); each row: bus reg, type, state, SLA timer.
- **Create Job Card wizard** — 4 types; steps ≤4 fields; everything auto-filled/SmartSelect (§4);
  per-step **PhotoCapture** (stamped). Type-specific steps: PMS inspection checklist (tap Good/
  Recommended/Immediate + measurement steppers), repair parts (SmartSelect + stock badge + photo),
  software (version-by-photo), breakdown (fault-code SmartSelect + remote-resolution **countdown** +
  travel/trial-trip timers).
- **Job Card detail** — state + actions (transition buttons gated by role/state), SLA badge,
  inventory request, photos, health score, customer-approval status.
- **Alerts** (§6) — list + detail with map.
- **Tickets** (§7) — list + detail with **Acknowledge / Resolve / Create Job Card**.
- **Profile** — user, role, depot(s), push/sync toggles, logout.

**UX rules:** large tap targets, one primary action per screen, plain language (no Frappe field
names), inline validation, optimistic UI with sync, dark-on-light high contrast for outdoor depot
use, works one-handed.

---

## 11. Roadmap

**Phase 0 — Backend additions & deploy (pre-app gate, §9).** Service Ticket + assignment mapping +
`ingest_alert_event` ticket automation + SE-scoped feeds + mobile token. Deploy. *App build starts
after this.*

**Phase 1 — App foundation.** Project, DI, network+auth (token), Room, Material 3 theme, bottom-nav
shell, `SmartSelect`, `PhotoCapture` with stamping (§5), master-cache sync, FCM + deeplink plumbing.

**Phase 2 — Core SE flows.** Job Cards list/detail/transitions; **Create Job Card wizard** (4 types,
fully auto-filled); inventory request; offline capture queue.

**Phase 3 — Alerts + Tickets + deeplinks.** Alerts tab, Tickets tab, ticket→job-card, push deeplinks
opening exact screens, realtime bell.

**Phase 4 — Hardening.** Baseline profiles, low-end device pass, APK shrink, offline edge cases,
crash/ANR telemetry, Play internal-testing release.

**Phase 5 — Phase-2 features** (as PRD): OCR odometer/version, AI damage hints, customer/driver
tickets, reports in-app.

---

## 12. Acceptance gates

- **Auto-fill:** create each of the 4 job-card types typing nothing but a search query; everything
  else auto-filled or tapped. Zero free-text on create forms (lint/review gate).
- **Photo stamp:** every captured photo shows, bottom-left and clearly legible, **date-time + lat/
  long + the capturing user's name**; EXIF GPS present; uploads (and survives offline→online).
- **Alerts/Tickets:** firing an alert for a bus in the SE's depot creates a ticket and delivers an
  **instant push**; tapping it **deeplinks straight to that ticket** from cold start.
- **Performance:** cold start < 1.5s, 60fps lists, APK < 12 MB on a 2 GB / Android 7 device.
- **Offline:** complete a job card + photos with no network; it syncs cleanly on reconnect.
- Backend: `bench migrate` + `bench run-tests --app vehicle_maintenance` green; fixtures committed.
