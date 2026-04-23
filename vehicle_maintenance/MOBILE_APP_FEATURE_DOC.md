# NaArNi Service — Android App Feature Specification

> **Status:** Planning · **Date:** 2026-04-24 · **Owner:** Mayank
> **Stack:** Kotlin · Jetpack Compose · MVVM · CameraX · Hilt · Retrofit · Room
> **Single app, all roles:** Depot Manager · Service Engineer · Technician · Central Ops · Customer · Aftersales Engineer · N. Maintenance Head
> **Backend:** Existing `vehicle_maintenance` Frappe app (no new business logic — only mobile-enabling endpoints to add)

---

## Table of Contents
1. [Why a Native Android App](#1-why-a-native-android-app)
2. [Architecture Overview](#2-architecture-overview)
3. [Tech Stack & Library Choices](#3-tech-stack--library-choices)
4. [Authentication & Session Management](#4-authentication--session-management)
5. [Role-Based Navigation Map](#5-role-based-navigation-map)
6. [Feature Catalog](#6-feature-catalog)
7. [Camera + GPS-Watermark Module](#7-camera--gps-watermark-module-deep-dive)
8. [Location Module](#8-location-module)
9. [Offline Mode + Sync Queue](#9-offline-mode--sync-queue)
10. [Push Notifications (FCM)](#10-push-notifications-fcm)
11. [API Surface Map](#11-api-surface-map)
12. [Data Models](#12-data-models)
13. [UI/UX Design System](#13-uiux-design-system)
14. [Backend Additions Needed](#14-backend-additions-needed-for-mobile)
15. [Phased Delivery Roadmap](#15-phased-delivery-roadmap)
16. [Out of Scope / Future](#16-out-of-scope--future)
17. [Open Questions](#17-open-questions)

---

## 1. Why a Native Android App

The PRD mentions React Native as a future plan (CLAUDE.md line 4). After 5 milestones of building the web SPA, a few field realities make the case for **native Kotlin** instead:

| Concern | Why native Kotlin wins |
|---|---|
| Camera with GPS+timestamp watermark | CameraX gives frame-level pixel control + sub-second EXIF metadata. RN bridges add latency that surfaces in shaky depot lighting. |
| Foreground location tracking (Breakdown travel) | Requires Foreground Service + persistent notification — first-class on Android, painful in RN. |
| Offline queue with photos (10–30 MB per JC) | Room + WorkManager are battle-tested. RN's offline story still relies on community libs. |
| FCM push deep linking | Native deep-link APIs are simpler and more reliable. |
| App size, cold start, battery | Native ~8-12 MB vs RN ~25-40 MB. Matters when depot Wi-Fi is bad. |
| Crash reporting + memory profiling | Better tooling. |

**The Vue 3 web SPA stays as-is** for desktop access (Customer portal, Central Ops dashboards, Admin/Ops via Frappe Desk). Mobile becomes the primary surface for **field roles: Technician, SE, DM, Aftersales Eng**.

---

## 2. Architecture Overview

**Pattern:** MVVM + Clean Architecture lite. Single-Activity, Compose-only UI.

```
app/
├── src/main/java/in/naarni/service/
│   ├── core/                  # cross-cutting
│   │   ├── auth/              # session, token storage
│   │   ├── camera/            # CameraX wrapper + watermark renderer
│   │   ├── location/          # FusedLocationProviderClient wrapper
│   │   ├── network/           # Retrofit, OkHttp, interceptors
│   │   ├── offline/           # WorkManager sync queue
│   │   ├── push/              # FCM service + deep-link router
│   │   ├── permissions/       # runtime permission flow
│   │   └── theme/             # Material 3 theming, colors, typography
│   ├── data/                  # data layer
│   │   ├── api/               # Retrofit interfaces (1 per Frappe module)
│   │   ├── db/                # Room entities + DAOs
│   │   ├── models/            # DTOs (Frappe response shapes)
│   │   └── repo/              # Repositories (network ↔ db ↔ ui)
│   ├── domain/                # framework-free use cases
│   │   ├── jobcard/           # CreateJobCard, AdvanceState, ForceClose…
│   │   ├── inventory/         # RaiseRequest, AdvanceStatus…
│   │   ├── customer/          # SubmitFeedback, ApproveEstimate…
│   │   └── breakdown/         # MarkArrived, RecordTrialTrip, RaiseRca…
│   ├── ui/                    # Compose screens grouped by feature
│   │   ├── auth/              # LoginScreen, OtpScreen
│   │   ├── home/              # role-routed home
│   │   ├── jobcard/           # creation wizards (4 type-specific), detail
│   │   ├── inspection/        # check-sheet flow with 3-tier UI
│   │   ├── inventory/         # request list, raise form, allocation, receipt
│   │   ├── breakdown/         # remote-diag timer, travel, trial trip
│   │   ├── customer/          # vehicle list, JC tracker, feedback
│   │   ├── reports/           # TAT adherence, repeated issues, health card
│   │   ├── notifications/     # in-app inbox + tap-through
│   │   └── shared/            # buttons, cards, chips, modals
│   ├── di/                    # Hilt modules (NetworkModule, DbModule…)
│   └── ServiceApp.kt          # Application class
├── src/main/res/              # icons, drawables, themes, strings (en + hi)
└── src/test/                  # unit tests
└── src/androidTest/           # instrumentation tests
```

**Single-Activity** (`MainActivity`) hosts a `NavHost`. All screens are Composable destinations. Deep links handled by `NavHost.handleDeepLink()` for both push notifications and the customer-approval URL we already issue.

---

## 3. Tech Stack & Library Choices

| Concern | Library | Why |
|---|---|---|
| Min SDK | 24 (Android 7.0) | Covers ~98% of devices in India |
| Target SDK | 35 (latest) | Required for Play Store |
| UI | **Jetpack Compose + Material 3** | Modern, no XML maintenance |
| Navigation | androidx.navigation:navigation-compose | Type-safe with `Serializable` routes |
| DI | **Hilt** | Standard Android DI, plays well with Compose |
| Networking | **Retrofit 2 + OkHttp 4** | Industry standard |
| JSON | **Moshi (KSP codegen)** | Faster than Gson, kotlin-aware |
| DB | **Room (KSP codegen)** | Compile-time SQL safety, coroutines-first |
| Async | **Coroutines + Flow** | Idiomatic Kotlin |
| Image loading | **Coil 3** | Compose-first, no glide bloat |
| Camera | **CameraX (1.4+)** | Lifecycle-aware, simpler than Camera2 |
| Location | **com.google.android.gms:play-services-location** (FusedLocationProviderClient) | Battery-efficient |
| Maps (optional) | **Google Maps Compose** or **MapLibre** | For depot location visualisation |
| Push | **Firebase Cloud Messaging** | Reliable, free, deep-link friendly |
| Background work | **WorkManager** | Required for offline sync + photo uploads |
| Token storage | **EncryptedSharedPreferences** + **Android Keystore** | Hardware-backed where available |
| Logging | **Timber** | Tagged logs, easier filtering |
| Crash reporting | **Firebase Crashlytics** | Free, integrates with FCM project |
| Analytics | **Firebase Analytics** (opt-in only) | Funnel analysis |
| Testing | JUnit 5 + MockK + Turbine | Coroutine-aware |
| UI Testing | Compose UI Test + Hilt test runner | Per-screen instrumentation |
| Build | Gradle KTS + Version Catalog | Declarative, type-safe deps |
| CI | GitHub Actions (or Bitrise) | Build APK + run tests on PR |

**Bundle expectation:** ~12 MB APK (split per ABI), ~28 MB AAB.

---

## 4. Authentication & Session Management

### Login flow — phone-based (matches the recently-fixed web Login.vue)

```
┌─────────────────┐
│ Splash (1.5s)   │  → check stored API key
└────────┬────────┘
         │ no key                  with key
         ▼                          ▼
┌─────────────────┐         ┌──────────────┐
│ LoginScreen     │         │ Bootstrap    │
│ +91 [10-digits] │         │ /get_logged_ │
│ [Password]      │         │  user        │
│ [Sign In]       │         │ + roles      │
└────────┬────────┘         └──────┬───────┘
         │ POST /api/method/login          │
         ▼                                  ▼
┌────────────────────────────┐    ┌────────────────────┐
│ POST issue_api_key         │    │ HomeScreen routed  │
│ (NEW endpoint — see §14)   │    │ by primary role    │
│ → store in Keystore        │    └────────────────────┘
└──────┬─────────────────────┘
       ▼
[next launch uses Bearer token]
```

### Token strategy

- **Login** uses Frappe's session-cookie endpoint (`/api/method/login`).
- Immediately after login, mobile calls a **NEW** whitelisted endpoint `vehicle_maintenance.api.auth.issue_api_key()` that:
  1. Generates an `api_key`/`api_secret` pair on the User record
  2. Returns them once
- App stores the pair in **EncryptedSharedPreferences** (hardware-backed via Keystore on supported devices).
- All subsequent requests use the `Authorization: token <api_key>:<api_secret>` header — **no cookies, no CSRF needed**.
- Logout calls a new `/revoke_api_key` endpoint and wipes local storage.

### Session bootstrap on launch
1. Fetch `/api/method/frappe.auth.get_logged_user` → confirms key still works
2. Fetch user roles via existing `vehicle_maintenance.fleet_service.doctype.job_card.job_card.get_user_roles`
3. Cache `User` snapshot (id, full_name, roles, depot if available, mobile) in Room
4. Route to role-appropriate Home

### Phone validation
Same regex as web: `^[6-9]\d{9}$`. The TextField uses an inline `VisualTransformation` to auto-format as `XXXXX XXXXX`. Strict 10-digit cap.

---

## 5. Role-Based Navigation Map

After login, the app routes to a role-appropriate `Home` screen. If the user holds multiple roles (e.g., DM also serves as SE in small depots), a chip selector at the top lets them switch context.

| Primary role | Home screen | Bottom nav tabs |
|---|---|---|
| **Technician** | "My Tasks" — JCs assigned to me, sorted by SLA | Tasks · Inspect · Notifications · Profile |
| **Service Engineer** | "My Job Cards" — created/assigned, with [+ Create JC] FAB | Job Cards · Create · Inventory · Notifications · Profile |
| **Depot Manager** | Depot Overview — open JCs, parts pending allocation, approvals | Dashboard · Approvals · Inventory · Reports · Profile |
| **Central Ops** | Fleet Map + TAT compliance | Fleet · TAT · Issues · Reports · Profile |
| **Customer** | "My Vehicles" — active JCs with status timeline | Vehicles · Job Cards · Notifications · Profile |
| **Aftersales Engineer** | Critical Force-Closes + RCA-pending Breakdowns | Critical · RCA · History · Profile |
| **N. Maintenance Head** | Escalations queue (TAT breaches, customer non-response, Critical) | Escalations · Approvals · Reports · Profile |

**Multi-role users:** chip selector in TopAppBar (e.g., `[Service Engineer ▾]`) — taps reveal other available role contexts. State persisted across launches.

---

## 6. Feature Catalog

Each feature is mapped to: **PRD reference** · **Roles that use it** · **Screens** · **API endpoints called**.

### 6.1 — Job Card Creation: PMS + Repair (PRD p.4-9, 17 steps)

**Roles:** Service Engineer (creator) · Technician (executes inspection)

**Screens:**
1. `JobCardTypePicker` — 4 large tiles (PMS+Repair / Only Repair / SW Update / Breakdown)
2. `VehicleSearchScreen` — search by reg# OR scan QR (fleet stickers) OR scan VIN plate via OCR
3. `OdometerCaptureScreen` — manual entry + **mandatory dashboard photo** (camera with watermark — see §7)
4. `JcContextScreen` — auto-filled (customer, OEM, contract, PMS tolerance) shown as read-only chips for confirmation
5. `InspectionPocScreen` — pick Self / Technician / Both (multi-toggle)
6. `InspectionChecklistScreen` — for each item from the auto-selected check sheet (A/B/C/D by km), 3-tier chip (Good / Repair-Replace Recommended / Repair-Replace Immediately). Photo mandatory if anything but Good. Per-category page with progress dots.
7. `RepairJobBuilderScreen` — list builder; per row: part group (autocomplete), activity type (Only Repair / Spare Replacement / Both), qty, rate, evidence photos. Live total. Customer approval banner if line >₹1,000.
8. `MaintenanceJobBuilderScreen` — oil/coolant/filter rows with action-aware photo labels (Drained vs Old-Filter vs Pre-Cleaning).
9. `ReviewAndSubmitScreen` — final summary, submit creates JC in `Open` state.

**APIs:**
- `vehicle_maintenance.api.job_card.search_vehicles`
- `vehicle_maintenance.api.job_card.get_customer_name`
- `vehicle_maintenance.api.job_card.list_part_groups`
- `frappe.client.insert` (Job Card)
- `vehicle_maintenance.api.job_card.save_repair_items`
- `vehicle_maintenance.api.job_card.save_maintenance_items`
- `/api/method/upload_file` per photo

### 6.2 — Job Card Creation: Only Repair (PRD p.10-13, 17 steps)

**Roles:** SE (creator) · Tech (executes)

**Screens (delta from PMS):**
1. After type selection → `RepairSubtypePicker` — Regular vs Accidental Major (Major escalates SLA to 24h)
2. **VIN-plate camera** screen (separate from odometer; PRD p.11)
3. `SubsystemMultiSelectScreen` — Subsystem chips (search + tap to add)
4. `GroupSelectionScreen` — Tech picks Part Groups before parts (PRD p.11 step 6)
5. `DamageAssessmentScreen` — photos with circle/mark annotation (see §7.5)
6. `PartListBuilderScreen` — Parts to Replace / Labour Applicable two-tab list
7. **`RequestCustomerApprovalScreen`** — generates/sends the approval link via the existing `customer_approval_token` flow; shows a copyable link + WhatsApp-share intent

**APIs (extras):**
- `vehicle_maintenance.api.job_card.save_subsystems`
- `vehicle_maintenance.api.job_card.transition_job_card` (to move to "Awaiting Customer Approval")
- `vehicle_maintenance.api.job_card.record_customer_approval_decision` (when customer responds in-person)

### 6.3 — Job Card Creation: Software Update (PRD p.13-14, 17 steps)

**Roles:** SE (creator) · Tech (executes) · or Both

**Screens (delta):**
1. `SoftwareComponentBuilderScreen` — list of components with:
   - Component name (free text or pick from "software list tab")
   - Reason chip (Performance / Regular / Emergency)
   - **Pre-version photo** (camera, watermarked) + Pre version text
   - "Upgrade" CTA → opens calibration sub-screen
   - Calibration values + calibration photo (marked **internal only — hidden from customer reports**)
   - **Post-version photo** + Post version text
   - On fail → "Retry" button → resets pre/post versions, increments retry_count
2. `OptionalCustomerReportToggleScreen` — checkbox for `send_report_to_customer` (PRD p.14 step 17)

**APIs:** `vehicle_maintenance.api.job_card.save_software_components`

### 6.4 — Job Card Creation: Breakdown (PRD p.14-16, 13 steps)

**Roles:** SE (primary) · Aftersales Eng (RCA)

**This flow is time-critical** — UI prioritises speed over polish.

**Screens:**
1. **`BreakdownQuickIntakeScreen`** — 1-screen form: vehicle search, incident place (Depot / En Route chip), groups impacted (multi-chip), fault codes (1, 2, 3+ rows). [Submit] creates JC in `Open` and immediately stamps `remote_resolution_started_at`.
2. **`BreakdownLastPmsBanner`** — auto-filled card shows Last PMS Date / Odometer / Tolerance / Last Serviced By (no editing — read-only context)
3. **`RemoteDiagnosisScreen`** — countdown timer to 30-min SLA; voice-call-driver button (`tel:`); two CTAs:
   - **[Mark Resolved]** → JC moves to `Closed` directly per Breakdown fast-path
   - **[Mark Failed → Travel]** → starts travel timer
4. **`TravelToSiteScreen`** — Foreground Service runs while travelling; map shows route. CTAs:
   - **[Mark Start Travel]** — captures location + timestamp
   - **[Mark Arrived]** — captures location + timestamp + computes travel duration
5. **`OnSpotDiagnosisScreen`** — confirm groups, fault codes 1..N (dynamic add), PRE photo with description
6. **`SopOverrideScreen`** — checkbox `Force Override` (no SOP) + reason textarea, OR `Process Override` (SOP didn't work) + alternate steps textarea
7. **`ResolutionScreen`** — fix type chips (Permanent / Temporary / Force Closed). If Temporary → Next-Level Engineer picker. Recurrence Risk + Occurrence Risk High/Low chips.
8. **`TrialTripScreen`** — Start Trip (capture start km via odometer photo) → End Trip (capture end km). Auto-computes dead km + trip duration.
9. **`HandoverScreen`** — [Mark Vehicle Handed Over] → stamps `vehicle_handover_at`, computes total down time
10. **`AftersalesRcaScreen`** (Aftersales Eng role) — multi-line RCA notes; on save, stamps `rca_received_at` and notifies DM to share with customer

**APIs:**
- `vehicle_maintenance.api.job_card.update_breakdown_diagnosis` (the workhorse — every step posts a partial update)
- `vehicle_maintenance.api.job_card.save_groups_impacted`
- `vehicle_maintenance.api.job_card.get_last_pms_info` (read-only fetch for the banner)
- `vehicle_maintenance.api.job_card.transition_job_card`

### 6.5 — Job Card Detail (lifecycle home for any existing JC)

**Roles:** All internal + Customer (read-only)

**Screen sections (collapsed cards, tap to expand):**
- **Header** — JC #, vehicle, type, current state badge, priority, SLA chip (green / amber / red)
- **Workflow Actions** — context-aware chips: Start Work · Send to Approval · Close · Reopen · Force Close
- **Health Score** (PMS+Repair only) — Pre/Post/Improvement tiles + per-category breakdown bar
- **Subsystems** chips (non-PMS types)
- **Repair Jobs** list (editable in WIP states)
- **Maintenance Jobs** list (PMS only)
- **Software Components** list (SW Update only)
- **Breakdown Diagnosis** panel (Breakdown only) — collapsible sub-cards: Last PMS · Incident · Remote SLA timer · Travel · Resolution · Trial Trip · RCA
- **Inventory Requests** list with role-aware action button per row
- **Customer Approval** card (when Awaiting Customer Approval) — Send Link · Mark Approved In-Person · Mark Rejected
- **Closure History** — list of `Job Card Closure Record` entries (sequence, closed_at, reopen reason)
- **Vehicle Health Card** link (post-closure, PMS only)
- **Audit Trail** (Frappe Version log read-only timeline)

**APIs:**
- `vehicle_maintenance.api.job_card.get_job_card_summary` (loads everything in one shot — already returns role-gated fields)
- `vehicle_maintenance.api.job_card.get_available_actions`
- `vehicle_maintenance.api.job_card.transition_job_card`
- `vehicle_maintenance.fleet_service.doctype.inventory_request.inventory_request.list_for_job_card`
- `vehicle_maintenance.fleet_service.doctype.job_card_closure_record.job_card_closure_record.list_for_job_card`
- `vehicle_maintenance.fleet_service.doctype.vehicle_health_card.vehicle_health_card.get_for_job_card`

### 6.6 — Inventory: Request → Allocate → Issue → Receive (PRD p.3-4)

**Roles:** Tech/SE (raise + receive) · DM (allocate + issue) · Central Ops (read-only monitor)

**Screens:**
1. `InventoryListScreen` (DM home tab) — pending requests grouped by status with role-aware bulk actions
2. `RaiseRequestSheet` — bottom sheet from JC detail: part autocomplete, qty, urgency chip, optional notes
3. `AllocationDetailScreen` — DM sees one request, taps [Allocate] → status flips to "Parts Allocated", or [Initiate Procurement] (Phase 2 — see §16)
4. `IssueConfirmationDialog` — DM scans/confirms physical handover → "Parts Issued"
5. `ReceiveConfirmationDialog` — Tech taps [Acknowledge Receipt] on an Issued request → "Received"

**APIs:**
- `vehicle_maintenance.fleet_service.doctype.part.part.search_parts`
- `vehicle_maintenance.api.job_card.create_inventory_request`
- `vehicle_maintenance.api.job_card.advance_inventory_status`
- `vehicle_maintenance.fleet_service.doctype.inventory_request.inventory_request.list_for_job_card`

### 6.7 — Customer Portal (in-app)

**Roles:** Customer

**Screens:**
1. `MyVehiclesScreen` — owned vehicles with current JC status badge
2. `JobCardTrackerScreen` — read-only JC detail with progress timeline (PMS step 1→17 visualised)
3. `ApproveEstimateScreen` — when JC is Awaiting Customer Approval: list of parts with per-line accept/reject toggle, JC-level rejection reason textarea, [Approve All] / [Reject Selected] CTAs
4. `ReopenJobCardScreen` — modal with mandatory reason
5. `FeedbackScreen` — 5-star rating + 0-10 NPS + Yes/Maybe/No recommend chip + comments
6. `HealthCardViewer` — clean PDF-like card for sharing/screenshot

**Tokenised approval link** (from notification) deep-links straight to step 3 with auth bypass via the customer_approval_token (see §10 deep-link routing).

**APIs:**
- `vehicle_maintenance.api.job_card.get_my_job_cards`
- `vehicle_maintenance.api.job_card.get_job_card_summary`
- `vehicle_maintenance.api.job_card.record_customer_approval_decision`
- `vehicle_maintenance.api.job_card.reopen_job_card`
- `vehicle_maintenance.api.job_card.submit_customer_feedback`
- `vehicle_maintenance.fleet_service.doctype.vehicle_health_card.vehicle_health_card.get_for_job_card`

### 6.8 — Reports & Analytics (Central Ops + DM)

**Screens:**
1. `TatAdherenceScreen` — date range picker, depot filter, table of by-type compliance + overall %; tappable rows drill into the underlying JC list
2. `RepeatedIssuesScreen` — vehicle picker → list of bus systems with ≥2 occurrences in the window
3. `FleetMapScreen` (Central Ops only) — map pins for active Breakdowns + WIP Major repairs

**APIs:**
- `vehicle_maintenance.api.job_card.tat_adherence_report`
- `vehicle_maintenance.api.job_card.repeated_issues_for_vehicle`

### 6.9 — Force Close

**Roles:** SE (Minor) · DM (Major) · N. Maint. Head (Critical)

**Screen:** `ForceCloseDialog` — severity chips with role-aware enable/disable + mandatory reason textarea.

**API:** `vehicle_maintenance.api.job_card.force_close_job_card`

When `severity = Critical`, server auto-creates the 24h follow-up JC; mobile app surfaces it under "My Tasks" for the assigned Aftersales Eng.

### 6.10 — Notification Inbox

**Roles:** All

In-app inbox mirrors the server-side `Notification Log`. Tapping an entry deep-links to the relevant JC. Push notification taps land on the same screen (or the destination JC directly via deep link).

**API:** `frappe.client.get_list` on `Notification Log` filtered by `for_user`.

---

## 7. Camera + GPS-Watermark Module (Deep Dive)

### 7.1 — Functional spec

The PRD requires photo evidence at multiple points (odometer, pre/post repair, drained oil, new oil, filter pre/post, VIN plate, damage assessment, breakdown PRE photo, calibration values, software versions). Each photo must be:

1. **Genuine** — captured live in-app, not picked from gallery
2. **Geo-tagged** — exact lat/lng of capture
3. **Time-stamped** — server-trusted time, not just device clock
4. **Tamper-evident** — visual watermark + EXIF + server-side hash check

### 7.2 — Capture flow

```
[User taps "Take Photo"]
        │
        ▼
┌────────────────────────────────────────────┐
│ Permission gate (Camera + Fine Location)   │
│  - explain why (rationale dialog) on 1st   │
└────────────┬───────────────────────────────┘
             │ granted
             ▼
┌────────────────────────────────────────────┐
│ Get location FIX (single high-accuracy)     │
│  - timeout 5s, fall back to last-known     │
│  - if no fix at all → block capture        │
│    with error "Location required"           │
└────────────┬───────────────────────────────┘
             │ fix
             ▼
┌────────────────────────────────────────────┐
│ Sync time with server                       │
│  - HEAD request to /api/method/ping         │
│  - read Date header → compute device offset │
│  - cached for 60s; on miss, fall back to    │
│    device time + flag in EXIF "device-time" │
└────────────┬───────────────────────────────┘
             │ trusted timestamp
             ▼
┌────────────────────────────────────────────┐
│ CameraX preview + capture                   │
│  - lockFocus before shutter                 │
│  - capture to in-memory ImageProxy          │
└────────────┬───────────────────────────────┘
             │ raw bitmap
             ▼
┌────────────────────────────────────────────┐
│ WatermarkRenderer.compose(...)              │
│  - draws 4-line footer banner               │
│  - embeds GPS in EXIF                       │
│  - computes SHA-256 of final bytes          │
│  - returns (jpegBytes, sha256, metadata)    │
└────────────┬───────────────────────────────┘
             │ jpeg + hash + metadata
             ▼
┌────────────────────────────────────────────┐
│ Upload via /api/method/upload_file          │
│  - attaches to Job Card OR child item       │
│  - extra POST to NEW endpoint               │
│    .../register_photo_metadata               │
│    {file_url, sha256, lat, lng, accuracy,    │
│     captured_at_server_time, device_id}     │
└────────────────────────────────────────────┘
```

### 7.3 — Watermark visual spec

```
┌─────────────────────────────────────────────────┐
│                                                 │
│                 [PHOTO CONTENT]                 │
│                                                 │
│                                                 │
├─────────────────────────────────────────────────┤
│ ⛳ 12.9716°N, 77.5946°E ±4m · Bengaluru, KA     │  ← line 1
│ 📅 24 Apr 2026, 11:42:13 IST                    │  ← line 2
│ 🚌 KA-01-AB-1234 · JC: BHT-DEPOT-2026-00007    │  ← line 3
│ 👤 Priya Engineer (SE) · NaArNi Service         │  ← line 4
└─────────────────────────────────────────────────┘
```

- Footer is ~22% of image height, semi-opaque dark gradient (rgba 0,0,0,0.65)
- Font: Roboto Medium, white, 14sp scaled to image dimensions
- Always at the bottom (rotated to image orientation)
- Reverse-geocoded place name via Geocoder (cached); falls back to coordinates only if offline
- Server time displayed in IST regardless of device locale

### 7.4 — EXIF embedding (defense in depth)

Even if the watermark is cropped, the EXIF carries:
- `GPSLatitude` / `GPSLongitude` / `GPSHorizontalAccuracy`
- `DateTimeOriginal` (server-trusted)
- `Software` = `NaArNi Service Android v<x.y.z>`
- `UserComment` = `JC=<name>;USER=<id>;HASH=<sha256>`

### 7.5 — Photo annotation (circle/mark damage)

For Only Repair damage assessment (PRD p.11 step 7b), an annotation overlay screen lets the user:
- Draw circles, freehand strokes, arrows, text labels on the photo
- Color picker (red / amber / green)
- Undo/redo
- Save flattens the strokes into the JPEG before upload + watermark step

### 7.6 — Tamper resistance

- **Client-side hash** (`sha256` of final bytes) sent with the upload metadata
- **Server-side recompute** during a hash-verify task (cron) — mismatches flagged in audit log
- **No "pick from gallery" path** — only camera capture or QR scan
- For ultra-sensitive photos (calibration values, RCA evidence) consider HMAC with a per-user secret derived at device registration

### 7.7 — Code sketch (illustrative — not final)

```kotlin
// core/camera/WatermarkRenderer.kt
object WatermarkRenderer {
    suspend fun compose(
        raw: Bitmap,
        loc: Location,
        serverTime: Instant,
        ctx: CaptureContext,   // jc#, vehicle, user, role
    ): WatermarkedImage {
        val canvas = Canvas(raw)
        val footerHeight = (raw.height * 0.22f).toInt()
        val footerY = (raw.height - footerHeight).toFloat()

        // Gradient overlay
        val gradient = LinearGradient(
            0f, footerY, 0f, raw.height.toFloat(),
            Color.TRANSPARENT, Color.argb(166, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, footerY, raw.width.toFloat(), raw.height.toFloat(),
            Paint().apply { shader = gradient })

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = raw.width * 0.028f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        val placeName = ReverseGeocoder.placeName(loc) // "Bengaluru, KA" or null
        val lines = listOf(
            "⛳  ${"%.4f".format(loc.latitude)}°N, ${"%.4f".format(loc.longitude)}°E " +
                    "±${loc.accuracy.toInt()}m" + (placeName?.let { " · $it" } ?: ""),
            "📅 ${ServerTimeFormatter.istLong(serverTime)}",
            "🚌 ${ctx.vehicleNumber} · JC: ${ctx.jobCardName}",
            "👤 ${ctx.userFullName} (${ctx.roleShort}) · NaArNi Service",
        )

        var y = footerY + textPaint.textSize * 1.4f
        for (line in lines) {
            canvas.drawText(line, raw.width * 0.04f, y, textPaint)
            y += textPaint.textSize * 1.4f
        }

        // Encode JPEG
        val jpeg = ByteArrayOutputStream().use { os ->
            raw.compress(Bitmap.CompressFormat.JPEG, 88, os)
            os.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).toHexString()

        return WatermarkedImage(
            bytes = jpeg,
            sha256 = hash,
            location = loc,
            serverTime = serverTime,
            context = ctx,
        )
    }
}
```

---

## 8. Location Module

### 8.1 — Where location matters

| Use case | Trigger | Accuracy needed | Background? |
|---|---|---|---|
| Photo watermark | every photo capture | ≤10 m | foreground only |
| Mark Start Travel (Breakdown) | user tap | ≤20 m | foreground |
| Mark Arrived (Breakdown) | user tap | ≤20 m | foreground |
| Travel route tracking (optional) | between Start/Arrived | ≤50 m, 30s interval | **foreground service** |
| Trial Trip distance proof (optional) | between Trial Start/End | ≤50 m | foreground service |
| Fleet Map (Central Ops) | live | best-available | n/a — server polls |

### 8.2 — Library + permissions

Use `FusedLocationProviderClient` (Google Play Services). Permissions requested incrementally (just-in-time, with rationale):

- **First photo:** request `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`
- **First Mark Start Travel:** request `FOREGROUND_SERVICE_LOCATION` (Android 14+)
- Never request `ACCESS_BACKGROUND_LOCATION` — we don't track when the app is backgrounded outside of an active travel/trial flow

### 8.3 — Foreground service

When the user taps **Mark Start Travel**, app launches `BreakdownTravelService` (a `ForegroundService` with `FOREGROUND_SERVICE_TYPE_LOCATION`) that:
- Shows persistent notification: "Travelling to BD location · 12 km · [Tap to view]"
- Records `(timestamp, lat, lng, accuracy)` every 30s into Room
- On **Mark Arrived**, posts the polyline summary to the server (new endpoint — see §14) along with the arrival event
- Auto-stops if the app is killed or 4h elapses (safety cap)

### 8.4 — Privacy

- App settings page lists every photo's GPS attached (with a "remove from this card" button — soft deletes the metadata, audit logged)
- Travel polylines are never shared with the customer
- Reverse-geocoded place names cached locally only, not sent to server

---

## 9. Offline Mode + Sync Queue

Field roles work in depots with patchy connectivity. **Critical features must work offline.**

### 9.1 — What works offline

| Action | Offline behaviour |
|---|---|
| View previously-loaded JCs | ✅ from Room cache |
| Run inspection checklist + 3-tier rating | ✅ saved locally |
| Capture photos | ✅ saved to app cache, flagged for upload |
| Raise inventory request | ✅ queued |
| Advance state (Start Work, Close, etc.) | ⚠️ queued; if the transition is rejected on sync, surface as conflict |
| Search vehicles / parts | ✅ from cached masters (refreshed daily) |
| View Health Card | ✅ from cache |
| Upload photo | ❌ queued |
| Force close | ❌ queued (can't risk silent state change) |
| Customer approval link send | ❌ requires online |
| Login | ❌ requires online (first time); subsequent launches use cached session |

### 9.2 — Architecture

- **Room** holds `JobCardEntity`, `RepairItemEntity`, `MaintenanceItemEntity`, `InventoryRequestEntity`, `PhotoUploadQueueEntity`, `OutboxEntity` (state transitions queued)
- **WorkManager** scheduled jobs:
  - `PhotoUploadWorker` — drains photo queue, retries with exponential backoff
  - `OutboxFlushWorker` — drains state transitions + child-table saves
  - `MasterRefreshWorker` — daily refresh of Subsystems, Part Groups, Depots, Service Contracts
- **NetworkObserver** — registers a connectivity callback; on regain, triggers immediate workers

### 9.3 — Conflict handling

- Each outbox row carries an `intent_id` (UUID) — server-side dedup if a request is retried
- If a state transition conflicts (server says invalid), the row moves to a `conflicts` table; user sees a banner "1 sync conflict" with a tap-to-review screen showing the original intent vs the current server state, plus [Discard] and [Retry] CTAs
- Photo uploads are idempotent by their SHA-256 — duplicates rejected by the server-side metadata endpoint

---

## 10. Push Notifications (FCM)

### 10.1 — Architecture

- Mobile registers FCM device token with the backend (NEW endpoint — see §14)
- Backend's `_dispatch()` sink (already scaffolded in `notifications.py` — `_dispatch_push`) is wired to fan out to all registered devices for the recipient user
- App subscribes to topic `user_<id>` so multi-device works

### 10.2 — Payload shape

```json
{
  "data": {
    "type": "job_card",                    // or "inventory", "feedback_request", "fleet_alert"
    "subject": "🚨 Breakdown Declared: BHT-DEPOT-2026-00007",
    "body": "BREAKDOWN at Bangalore Depot for KA-01-AB-1234.",
    "deep_link": "naarni://jc/BHT-DEPOT-2026-00007",
    "priority": "high",
    "notification_log_id": "NL-2026-00342"
  }
}
```

App handles `data` payloads only (not `notification` blocks) so it controls the channel/icon/sound.

### 10.3 — Notification channels (Android 8+)

| Channel | Importance | Sound | Visual |
|---|---|---|---|
| `breakdown_alerts` | HIGH (heads-up) | bell+vibrate | red |
| `tat_alerts` | HIGH | beep+vibrate | amber |
| `customer_approvals` | DEFAULT | beep | blue |
| `info` | LOW | silent | gray |
| `feedback_reminders` | LOW | silent | gray |

### 10.4 — Deep links

`naarni://jc/<name>` → `JobCardDetailScreen`
`naarni://approve/<name>?token=<t>` → tokenised customer approval (matches the URL format the backend already includes in `notify_customer_approval_requested`)
`naarni://inventory/<name>` → inventory request detail
`naarni://feedback/<name>` → feedback modal

---

## 11. API Surface Map

Every screen → endpoints it depends on. Existing endpoints unless flagged **NEW**.

### Auth
- `POST /api/method/login` — phone-based (existing Frappe)
- **NEW** `POST vehicle_maintenance.api.auth.issue_api_key` — returns `{api_key, api_secret}` after successful login
- **NEW** `POST vehicle_maintenance.api.auth.revoke_api_key`
- **NEW** `POST vehicle_maintenance.api.auth.register_fcm_device` — `{token, device_id, platform: "android"}`
- **NEW** `POST vehicle_maintenance.api.auth.unregister_fcm_device`
- `GET /api/method/frappe.auth.get_logged_user`
- `GET vehicle_maintenance.fleet_service.doctype.job_card.job_card.get_user_roles`

### Job Card lifecycle
- `vehicle_maintenance.api.job_card.search_vehicles`
- `vehicle_maintenance.api.job_card.search_depots`
- `vehicle_maintenance.api.job_card.list_users_by_role`
- `vehicle_maintenance.api.job_card.list_part_groups`
- `vehicle_maintenance.api.job_card.list_subsystems`
- `vehicle_maintenance.api.job_card.get_customer_name`
- `vehicle_maintenance.api.job_card.get_last_pms_info` (Breakdown only)
- `frappe.client.insert` (create JC)
- `vehicle_maintenance.api.job_card.get_job_card_summary`
- `vehicle_maintenance.api.job_card.get_my_job_cards`
- `vehicle_maintenance.api.job_card.get_available_actions`
- `vehicle_maintenance.api.job_card.transition_job_card`
- `vehicle_maintenance.api.job_card.update_job_card`
- `vehicle_maintenance.api.job_card.save_repair_items`
- `vehicle_maintenance.api.job_card.save_maintenance_items`
- `vehicle_maintenance.api.job_card.save_software_components`
- `vehicle_maintenance.api.job_card.save_subsystems`
- `vehicle_maintenance.api.job_card.save_groups_impacted`
- `vehicle_maintenance.api.job_card.update_breakdown_diagnosis`
- `vehicle_maintenance.api.job_card.force_close_job_card`
- `vehicle_maintenance.api.job_card.reopen_job_card`

### Customer flows
- `vehicle_maintenance.api.job_card.record_customer_approval_decision`
- `vehicle_maintenance.api.job_card.get_approval_link_payload` (guest, with token)
- `vehicle_maintenance.api.job_card.submit_approval_via_link` (guest, with token)
- `vehicle_maintenance.api.job_card.submit_customer_feedback`
- `vehicle_maintenance.api.job_card.get_customer_feedback`

### Inventory
- `vehicle_maintenance.fleet_service.doctype.part.part.search_parts`
- `vehicle_maintenance.api.job_card.create_inventory_request`
- `vehicle_maintenance.api.job_card.advance_inventory_status`
- `vehicle_maintenance.fleet_service.doctype.inventory_request.inventory_request.list_for_job_card`

### Reports
- `vehicle_maintenance.api.job_card.tat_adherence_report`
- `vehicle_maintenance.api.job_card.repeated_issues_for_vehicle`

### Health Card / Closure history
- `vehicle_maintenance.fleet_service.doctype.vehicle_health_card.vehicle_health_card.get_for_job_card`
- `vehicle_maintenance.fleet_service.doctype.job_card_closure_record.job_card_closure_record.list_for_job_card`

### Photos
- `POST /api/method/upload_file` (existing Frappe)
- **NEW** `POST vehicle_maintenance.api.media.register_photo_metadata` — stores `{file_url, sha256, lat, lng, accuracy, captured_at, device_id}` for tamper-evidence

### Travel polylines (optional)
- **NEW** `POST vehicle_maintenance.api.breakdown.upload_travel_polyline` — stores compact polyline + start/end/duration on the JC

### Notification inbox
- `frappe.client.get_list` on `Notification Log`
- `frappe.client.set_value` on `Notification Log` to mark read

---

## 12. Data Models

### Kotlin DTOs (mirroring backend response shapes)

```kotlin
// data/models/JobCard.kt — partial
@JsonClass(generateAdapter = true)
data class JobCardSummary(
    val name: String,
    @Json(name = "viewer_roles") val viewerRoles: List<String>,
    @Json(name = "viewer_is_depot_manager") val viewerIsDepotManager: Boolean,
    @Json(name = "job_card_type") val jobCardType: JobCardType,
    @Json(name = "repair_subtype") val repairSubtype: String?,
    val vehicle: String,
    @Json(name = "vehicle_number") val vehicleNumber: String,
    @Json(name = "vehicle_make_model") val vehicleMakeModel: String?,
    @Json(name = "customer_name") val customerName: String?,
    val priority: Priority,
    @Json(name = "workflow_state") val workflowState: WorkflowState,
    @Json(name = "complaint_description") val complaintDescription: String?,
    @Json(name = "estimated_cost") val estimatedCost: Double,
    @Json(name = "actual_cost") val actualCost: Double,
    @Json(name = "opened_at") val openedAt: String?,
    @Json(name = "closed_at") val closedAt: String?,
    val depot: String?,
    @Json(name = "pre_pms_score") val prePmsScore: Double,
    @Json(name = "post_pms_score") val postPmsScore: Double,
    @Json(name = "score_improvement") val scoreImprovement: Double,
    @Json(name = "health_card") val healthCard: String?,
    @Json(name = "force_closed") val forceClosed: Int,
    @Json(name = "force_close_severity") val forceCloseSeverity: String?,
    @Json(name = "sla_breached") val slaBreached: Int,
    @Json(name = "requires_customer_approval") val requiresCustomerApproval: Int,
    val subsystems: List<String>,
    @Json(name = "followup_job_card") val followupJobCard: String?,
    @Json(name = "send_report_to_customer") val sendReportToCustomer: Int,
    @Json(name = "repair_items") val repairItems: List<RepairItem> = emptyList(),
    @Json(name = "maintenance_items") val maintenanceItems: List<MaintenanceItem> = emptyList(),
    @Json(name = "software_components") val softwareComponents: List<SoftwareComponent> = emptyList(),
    @Json(name = "category_scores") val categoryScores: List<CategoryScore> = emptyList(),
    val breakdown: BreakdownBlock? = null,
)

enum class JobCardType {
    @Json(name = "PMS + Repair") PMS_REPAIR,
    @Json(name = "Only Repair")  ONLY_REPAIR,
    @Json(name = "Software Update") SOFTWARE_UPDATE,
    @Json(name = "Breakdown")    BREAKDOWN,
}

enum class WorkflowState {
    @Json(name = "Open") OPEN,
    @Json(name = "WIP") WIP,
    @Json(name = "Awaiting Customer Approval") AWAITING_CUSTOMER_APPROVAL,
    @Json(name = "Awaiting Parts") AWAITING_PARTS,
    @Json(name = "Parts Fitted") PARTS_FITTED,
    @Json(name = "Closure from Technician") CLOSURE_FROM_TECHNICIAN,
    @Json(name = "Verification Pending") VERIFICATION_PENDING,
    @Json(name = "Closed") CLOSED,
    @Json(name = "Reopened") REOPENED,
}
```

(Full DTO list at implementation time will mirror every field returned by `get_job_card_summary` and the child tables.)

### Room entities

Mirror DTOs but with `@PrimaryKey`, `@Embedded`, foreign keys, and `lastSyncedAt: Long` per row for cache-staleness checks.

---

## 13. UI/UX Design System

### Theming
- **Material 3 dynamic color** on Android 12+; falls back to brand seed (`#0369A1` — same brand-500 as the web SPA's Tailwind theme)
- **Dark mode** auto-respects system setting
- **Typography:** Roboto throughout
- **Spacing scale:** 4 / 8 / 12 / 16 / 24 / 32 dp
- **Card radius:** 12 dp
- **Elevation:** 0 / 1 / 3 dp (Material 3 conventions)

### Component tokens (mapped from web Tailwind)
| Token | Web Tailwind | Compose color |
|---|---|---|
| brand-500 | #0EA5E9 | `Color(0xFF0EA5E9)` |
| brand-600 | #0284C7 | `Color(0xFF0284C7)` |
| amber-500 | #F59E0B | `Color(0xFFF59E0B)` |
| red-500 | #EF4444 | `Color(0xFFEF4444)` |
| green-500 | #22C55E | `Color(0xFF22C55E)` |

### Status badge colors (matches `StatusBadge.vue`)
| State | Color |
|---|---|
| Open | gray |
| WIP | brand |
| Awaiting Customer Approval | amber |
| Awaiting Parts | indigo |
| Parts Fitted | blue |
| Closure from Technician | purple |
| Verification Pending | amber |
| Closed | green |
| Reopened | red |
| Force Closed | red (filled) |

### Accessibility
- All tap targets ≥ 48 dp
- Color contrast AA minimum
- TalkBack labels on all icon-only buttons
- Dynamic text scaling supported

### Localisation
Phase 0: English only. Phase 1 adds Hindi (`res/values-hi/`). All strings in `strings.xml` from day one — no hardcoded text in Composables.

---

## 14. Backend Additions Needed for Mobile

These are net-new additions to `vehicle_maintenance` to support the app. Each is small (<1h of work).

### 14.1 — Auth & device

| Endpoint | Purpose |
|---|---|
| `vehicle_maintenance.api.auth.issue_api_key()` | Generate api_key+secret on the logged-in User; return once |
| `vehicle_maintenance.api.auth.revoke_api_key()` | Wipe the user's api_key/secret |
| `vehicle_maintenance.api.auth.register_fcm_device(token, device_id, platform)` | Stores in new `Mobile Device` DocType linked to User |
| `vehicle_maintenance.api.auth.unregister_fcm_device(device_id)` | Used on logout |

New DocType: **Mobile Device**
```
{
  user (Link → User),
  device_id (Data, unique),
  platform (Select: android/ios),
  fcm_token (Data),
  last_seen_at (Datetime),
  app_version (Data)
}
```

### 14.2 — Photo metadata + tamper evidence

| Endpoint | Purpose |
|---|---|
| `vehicle_maintenance.api.media.register_photo_metadata(file_url, sha256, lat, lng, accuracy, captured_at_iso, device_id, jc_name, child_field?)` | Stores metadata alongside the uploaded File for verification |

New DocType: **Photo Capture Metadata**
```
{
  file (Link → File),
  job_card (Link → Job Card),
  child_field (Data — e.g., "repair_items.pre_repair_photo[2]"),
  sha256 (Data),
  lat (Float),
  lng (Float),
  accuracy_m (Float),
  captured_at (Datetime — server-trusted),
  uploaded_at (Datetime — auto),
  device (Link → Mobile Device),
  reverse_geocode (Data — cached "Bengaluru, KA")
}
```

A daily scheduled task `monitor_photo_integrity` recomputes hashes for a sampled set and flags mismatches.

### 14.3 — FCM dispatch wiring

Update `_dispatch_push` in `notifications.py` to look up registered devices for the recipient and POST to FCM via the Firebase Admin SDK:

```python
def _dispatch_push(user, subject, body, doc_name, priority):
    if not frappe.get_conf().get("notifications_push_enabled"):
        return
    devices = frappe.get_all(
        "Mobile Device",
        filters={"user": user, "fcm_token": ["is", "set"]},
        fields=["device_id", "fcm_token", "platform"],
    )
    for d in devices:
        _fcm_send(d["fcm_token"], subject, body, doc_name, priority)
```

`_fcm_send` uses HTTP v1 API with a service-account JSON in site config (`fcm_service_account_path`).

### 14.4 — Travel polyline upload (optional)

| Endpoint | Purpose |
|---|---|
| `vehicle_maintenance.api.breakdown.upload_travel_polyline(job_card, encoded_polyline, distance_m, duration_s, points_count)` | Stores compact polyline (Google's encoded format) on the JC for audit + map display |

Adds two fields to Job Card: `travel_polyline_encoded` (Long Text) + `travel_distance_m` (Float).

### 14.5 — Estimated count

| Item | LOC | Files touched |
|---|---|---|
| `Mobile Device` DocType | ~50 lines JSON | 3 new |
| `Photo Capture Metadata` DocType | ~80 lines JSON | 3 new |
| `auth` API module | ~100 lines | 1 new |
| `media` API module | ~80 lines | 1 new |
| `breakdown` API extension | ~40 lines | append to existing |
| FCM dispatch wiring | ~60 lines + config | edit notifications.py + add config doc |
| Tests | ~200 lines | 2 new |
| **Total** | **~600 lines** | **9 new files** |

Should fit comfortably in **1-2 days** of backend work.

---

## 15. Phased Delivery Roadmap

### Phase 0 — Scaffold & Auth (1 week)
- App skeleton, Hilt, Compose nav, theme
- Login screen with phone validation
- `issue_api_key` backend + storage
- Bootstrap user/roles
- Splash + role-routed Home shell (empty tabs)
- CI: build APK on PR
- **Deliverable:** Login + role-routed empty tabs, internal builds via Firebase App Distribution

### Phase 1 — Tech & SE flows for **PMS + Repair** (2 weeks)
- Camera + watermark module (full spec)
- Permissions flow
- Vehicle search (incl. QR scan)
- PMS Repair wizard (9 screens)
- Inspection checklist with 3-tier rating
- Repair + Maintenance builders
- Job Card Detail (read-only)
- Inventory request raise
- **Deliverable:** A Tech can run a complete PMS inspection on a real vehicle

### Phase 2 — All 4 type wizards (2 weeks)
- Only Repair wizard + photo annotation
- Software Update wizard
- Breakdown wizard with foreground travel service
- RCA capture (Aftersales role)
- **Deliverable:** All 4 PRD flows usable in field

### Phase 3 — DM flows (1 week)
- Allocation queue
- Issue confirmation
- Force Close with severity matrix
- Closed-state edits with reason
- **Deliverable:** A DM can run a full depot day end-to-end

### Phase 4 — Customer portal (1 week)
- My Vehicles + JC tracker
- Approve/Reject estimate (in-app + tokenised deep link)
- Reopen
- Feedback (NPS + rating)
- Health Card viewer
- **Deliverable:** Customer-facing app launches to small pilot

### Phase 5 — Central Ops + N. Maint. Head (1 week)
- TAT Adherence dashboard
- Repeated Issues
- Fleet Map
- Escalations queue
- Closure history viewer
- **Deliverable:** Ops gets analytics on phone

### Phase 6 — Offline + Push + Polish (2 weeks)
- Room cache + sync queue
- WorkManager photo upload
- Conflict resolution UX
- FCM end-to-end
- Hindi localisation
- Crashlytics + analytics + onboarding
- Play Store submission prep
- **Deliverable:** Production-ready app

**Total: ~10 weeks of mobile work + ~1.5 weeks backend additions.**

---

## 16. Out of Scope / Future

Marked explicitly so we don't accidentally try to ship them in V1.

| Feature | Why deferred |
|---|---|
| iOS app | Field roles in India are 95%+ Android; Customer iOS via web SPA is sufficient initially |
| LLM-assisted diagnosis | PRD Phase 2; needs LLM provider + prompt engineering — separate project |
| Exploded diagrams (interactive part picking) | PRD Phase 2; needs CAD asset pipeline |
| Voice-to-text complaint capture | Nice-to-have; on-device speech recognition can be added in V2 |
| WhatsApp / phone-call JC creation | PRD Phase 2; needs WhatsApp Business API |
| AR damage assessment | Far future |
| Stock Management UI (Bin levels, PO) | Needs ERPNext Stock integration first; treat as separate workstream |
| Email integration (SendGrid) | Backend concern; mobile only consumes the resulting notifications |
| Vendor management | PRD Phase 2 |
| Warranty workflow | PRD Phase 2 |

---

## 17. Open Questions

1. **Multi-language scope:** English-only in V1, Hindi in Phase 6 — is that acceptable, or do we need Hindi from day one for technicians?
2. **Single device per user, or multi-device?** Recommend multi-device; users may switch phones. The `Mobile Device` DocType supports it.
3. **Customer app distribution:** Play Store public listing, or invite-only via Firebase App Distribution to specific customers? (Affects Play Store review timeline.)
4. **Photo storage:** Frappe's `File` DocType saves to local disk. At scale (1000+ photos/day) this needs S3 or equivalent. Decide before Phase 6.
5. **Trial trip distance accuracy:** PRD says "dead km" comes from odometer photos (start/end). Should the app cross-check against the recorded GPS polyline? Useful but adds complexity.
6. **Force-close 24h follow-up assignment:** Currently auto-assigned to *any* Aftersales Eng user. Should mobile let DM pick a specific person?
7. **Offline depot list:** How many depots total? If <500, we cache them all. If thousands, we need on-demand loading + recent-used cache.
8. **Customer phone vs email login:** PRD and code support phone-only. Customer onboarding will need an OTP flow if we don't preset passwords.
9. **Aftersales Eng role for Major force-close approval:** PRD says DM/Aftersales approval mandatory; do we need an in-app approval flow before the JC can be force-closed Major, or is the role-gate at submit time sufficient?
10. **App-update enforcement:** Force-update on critical version bump? Useful for security patches but UX-painful — recommend grace-period + warning, force only on hard blockers.

---

## Appendix A — Sample Compose Screen (illustrative)

```kotlin
@Composable
fun JobCardDetailScreen(
    jcName: String,
    vm: JobCardDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { JcTopBar(jcName, state.summary?.workflowState) },
        bottomBar = { JcActionBar(state, onAction = vm::onAction) },
    ) { padding ->
        when (val s = state) {
            JcDetailState.Loading -> CircularProgressIndicator()
            is JcDetailState.Error -> ErrorCard(s.error, onRetry = vm::reload)
            is JcDetailState.Loaded -> Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState())
            ) {
                HeaderCard(s.summary)
                if (s.summary.jobCardType == JobCardType.PMS_REPAIR) {
                    HealthScoreCard(s.summary)
                }
                if (s.summary.requiresCustomerApproval == 1) {
                    ApprovalRequiredBanner(s.summary, onSendLink = vm::sendApprovalLink)
                }
                RepairItemsCard(
                    items = s.summary.repairItems,
                    canEdit = s.canEditWork,
                    onEdit = vm::editRepairItems,
                )
                if (s.summary.jobCardType == JobCardType.BREAKDOWN) {
                    BreakdownDiagnosisPanel(s.summary.breakdown!!, vm::saveBreakdown)
                }
                InventoryRequestsCard(jcName)
                ClosureHistoryCard(jcName)
            }
        }
    }
}
```

## Appendix B — File-tree commitment

When implementation begins, the first commit lands the directory skeleton from §2 with empty stubs — so reviewers can see the architecture before any feature lands. CI fails the PR if a new file lands outside the prescribed structure (verified via a simple `tree` snapshot test).

---

**Doc owner:** Mayank · **Last updated:** 2026-04-24
**Companion docs:** [PRD JOB CARD.pdf](../../PRD JOB CARD.pdf) · [PRD_ALIGNMENT_PLAN.md](./PRD_ALIGNMENT_PLAN.md)
