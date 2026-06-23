# NAARNI Vehicle Maintenance Platform — Implementation Plan (v2, 2026-06-22)

> **Status of this document.** v1 (archived at `IMPLEMENTATION_PLAN_v1_archive.md`) was a
> greenfield build plan. Most of it is now **built**. This v2 reconciles the plan with what
> actually shipped, and re-focuses remaining work on the product owner's **#1 non-negotiable
> requirement: the system is for non-tech-savvy users — almost every field must be auto-filled,
> and every choice field must be a searchable dropdown-with-suggestions, never free text.**
>
> Scope covered here: **Backend (Frappe)** · **Web app (Vue 3 / Frappe UI SPA)** · **Mobile app
> (React Native, consumes the same APIs)** · **Notifications (in-app + push + SMS)**.

---

## TABLE OF CONTENTS

0. [How to read this plan](#0-how-to-read-this-plan)
1. [Current State Reconciliation (what is actually built)](#1-current-state-reconciliation)
2. [⭐ THE NON-NEGOTIABLE: Auto-fill & Dropdown Contract](#2--the-non-negotiable-auto-fill--dropdown-contract)
3. [Backend gaps to close](#3-backend-gaps-to-close)
4. [Structured Inspection — closing the score gap](#4-structured-inspection)
5. [Web app (Vue SPA) remaining work](#5-web-app-vue-spa-remaining-work)
6. [Mobile app (React Native) delivery plan](#6-mobile-app-react-native)
7. [Notifications: in-app + push + SMS](#7-notifications-in-app--push--sms)
8. [Gap-based roadmap (sprints)](#8-gap-based-roadmap)
9. [Verification & acceptance](#9-verification--acceptance)
10. [100% PRD coverage checklist (the remaining 10%)](#10-100-prd-coverage-checklist)

Detailed data-model field lists, full API surface, and the original sample-report mappings
remain valid in the archive (`IMPLEMENTATION_PLAN_v1_archive.md` §3, §7, §10, §13). This v2 does
not repeat them; it points to them where useful.

---

## 0. How to read this plan

The platform already has a substantial, working backend and a partial web app. So this is **not**
a "build from scratch" plan — it is a **gap-closure plan**. Every item below is one of:

- **✅ DONE** — exists and matches the PRD. Listed only for context; do not rebuild.
- **🟡 PARTIAL** — exists but incomplete or diverged from PRD; needs finishing.
- **🔴 MISSING** — required by PRD or by the auto-fill/dropdown mandate; must be built.

The single most important theme — repeated in §2 and threaded through §5/§6 — is: **eliminate
free-text and manual entry**. When in doubt, a field is either auto-derived from context or chosen
from a searchable, suggestion-backed list.

---

## 1. Current State Reconciliation

### 1.1 What is built and matches the PRD (✅ DONE — do not rebuild)

**Backend** (`vehicle_maintenance/vehicle_maintenance/`):

| Area | State | Notes |
|---|---|---|
| Job Card DocType | ✅ | All 4 types (PMS+Repair, Only Repair, Software Update, Breakdown); `job_card_type`, `repair_subtype`, priority. 50+ fields incl. breakdown, software, force-close, photos, SLA, health-score fields. |
| Autoname | ✅ | `{CUSTOMER_CODE}-{DEPOT_CODE}-{YYYY}-{#####}` per PRD. |
| Child tables | ✅ | `subsystems`, `repair_items`, `maintenance_items`, `software_components`, `groups_impacted`, `category_scores`, closure record. |
| Workflow | ✅ | Open → WIP → Awaiting Customer Approval → Awaiting Parts → Parts Fitted → Closure from Technician → Verification Pending → Closed, + Reopened loop. Breakdown fast-path skips approval. `workflows/job_card_workflow.json`. |
| SLA engine | ✅ | Per-type targets (PMS 4h, Repair 4h, Accidental 24h, Software 3h, Breakdown remote 0.5h / on-site 4h) + per-contract overrides; 80% warning + breach via 5-min scheduler in `fleet_service/tasks.py`. |
| Health-score formula | ✅ | Good=10 / Recommended=5 / Immediately=0; Category% = Σ/(N·10)·100; Vehicle% = mean of categories; Pre/Post + improvement. In `job_card.py::_compute_health_scores`. **But fed only by `repair_items`, not by the inspection checklist — see §4.** |
| Inventory Request | ✅ | 5-step flow (Requested → Parts Allocated → Parts Issued → Received) + >₹1,000 customer-approval gate. |
| Force close / severity matrix | ✅ | Minor/Major/Critical authority gates; Critical auto-creates 24h follow-up card. |
| Notifications (in-app) | ✅ | All 12 PRD triggers + extras fire to Frappe Notification Log via `fleet_service/notifications.py`. |
| Masters | ✅ | Vehicle, Customer, Depot, OEM, Part, Part Group (`bus_system` field), Subsystem, Service Contract, Vehicle Health Card. |
| Phone-based auth | ✅ | `api/auth.py::login_with_phone`; `overrides/user.py` enforces mobile_no + synthetic email. |
| CRM / Leads | ✅ | Lead lifecycle, reminders, dashboards. |
| Alerts/telemetry | ✅ | Alert Type/Subscription/Event + Naarni engine ingest. |

**Web app** (`frontend/src/`): Vue 3.4 + frappe-ui 0.1.69 + vue-router + Tailwind. Routes for
dashboards (role-aware), Job Card create wizard, technician inspection wizard, job card detail
(role-gated customer vs internal), health card view, CRM. Phone login.

### 1.2 Where the build DIVERGED from v1 plan (important — don't "fix" these back)

The v1 plan proposed standalone DocTypes that were **deliberately not built**; the team folded
them in differently. Treat the shipped approach as canonical:

| v1 planned | What actually shipped | Decision |
|---|---|---|
| `Bus System` master DocType | `Part Group.bus_system` Select field | Keep the Select; it's sufficient for grouping/scoring. |
| `PMS Checksheet` + `PMS Checksheet Item` DocTypes (fixtures) | Hardcoded checklists in `frontend/composables/useInspectionSheet.js` | **Problematic — see §4.** Move to backend masters. |
| `PMS Inspection Result` DocType | Inspection stored as a **text comment** on the Job Card | **Problematic — see §4.** |
| `Concern Code` / `Labor Code` masters | Not built; complaint & SE notes are **free text** | **Build them — see §2/§3.** This blocks the dropdown mandate. |

### 1.3 The three real gaps (everything in this plan rolls up to these)

1. **The dropdown/auto-fill mandate is only ~40% met** in the web app. Vehicle & depot have
   bespoke searchable inputs; almost everything else is free text or a plain `<select>`. There is
   **no reusable searchable-select component** and **no master data** behind complaint/observation/
   fault-code fields. → **§2 (the headline work).**
2. **Inspection results don't reach the score engine.** The technician's checklist is saved as a
   comment, so Pre/Post-PMS health scores only reflect manually-added repair_items. → **§4.**
3. **Push & SMS notifications are stubs**; there is **no in-app notification bell** in the SPA.
   PRD requires In-App + Push + SMS. → **§7.**

Plus a smaller one: **no mobile (React Native) app exists yet** — the APIs are mobile-ready but no
client. → **§6.**

### 1.4 Two product principles that govern every decision below

- **Keep it basic.** This is a simple operational app for depot staff, not an ERP. Prefer the
  smallest thing that satisfies the PRD: reuse existing DocTypes/endpoints, no new frameworks, no
  speculative abstractions. Every screen = a short tap-driven wizard. If a feature isn't in the
  PRD's Phase 1, it goes to Sprint 6, not now.
- **Notifications are instant and sensitive.** Every state change a user cares about must reach them
  **immediately** (sub-second in-app, push within seconds), not on a polling delay. "Sensitive" =
  the right people, every time, with escalation if unacknowledged. This reshapes §7.

### 1.5 The remaining ~10% to reach 100% PRD coverage (index)

The backend covers ~90% of the PRD. The exact remaining 10% is enumerated as a checklist in
**§10** so nothing is missed. Headline misses: structured inspection feeding scores (§4), instant
push/SMS + realtime in-app (§7), Repair/Software/Breakdown **PDF reports** + report-approval-to-
customer flow (§10), the maintenance oil/coolant/filter photo flows (§10), and the full edit-
permission/audit-trail matrix (§10).

---

## 2. ⭐ THE NON-NEGOTIABLE: Auto-fill & Dropdown Contract

> **Rule (product owner, mandatory):** A non-tech user must be able to complete any form by
> *tapping choices*, not typing. Default every field to auto-filled-from-context. If a value can't
> be derived, present a **searchable dropdown with suggestions** (recent/most-likely on top). Free
> text is allowed **only** for genuinely open content (a photo's caption, a final remark) — and even
> then, offer canned suggestions first.

This section is the heart of v2. It has four parts: **(A)** one reusable component, **(B)** one
backend prefetch endpoint, **(C)** the master data that makes suggestions possible, and **(D)** a
field-by-field contract for every form.

### 2.A One reusable component: `SmartSelect.vue` (🔴 MISSING — build first)

Today the SPA reinvents a searchable input per page (vehicle, depot) and falls back to `<select>`
elsewhere. Replace all of it with **one** component so the behavior is consistent everywhere.

`frontend/src/components/SmartSelect.vue` — props:

```
modelValue            v-model
fetch(query)          async fn → [{ value, label, sublabel?, badge?, recent?, meta? }]
                      (server-backed search; debounced 250ms; min 0 chars so it opens with
                       suggestions BEFORE typing)
suggestions           optional eager list shown when query is empty (recent / most-likely first)
allowCreate           default false. If true, shows "＋ Add ‘<text>’" — used ONLY where a master
                      genuinely needs new entries (and routes through a create endpoint).
multiple              default false (chips when true — replaces SubsystemPicker)
icon, placeholder, required, disabled
```

Behaviour requirements (all non-negotiable for the UX):
- Opens a list on focus **without typing** (shows `suggestions`, "recent", or top-N).
- Keyboard + large tap targets (min 44px rows), works one-handed on a phone.
- Shows `sublabel` (e.g. model + customer under a vehicle) and a `badge` (e.g. "in stock").
- Never a bare HTML `<select>`; never a free-text box where a list exists.

Then **delete/replace**: the ad-hoc vehicle & depot search blocks in `JobCardNew.vue`, the
plain `<select>`s in `LeadNew.vue`/`LeadsList.vue`, and `SubsystemPicker.vue` (becomes
`SmartSelect multiple`).

### 2.B One backend prefetch endpoint: `get_job_card_form_context` (🔴 MISSING)

The web/mobile create-flow should make **one** call after the vehicle is chosen and get back
*everything* needed to auto-fill the form — so the user types nothing else.

`api/job_card.py::get_job_card_form_context(vehicle, job_card_type)` → returns:

```jsonc
{
  "success": true,
  "data": {
    "vehicle_number": "...", "make_model": "...", "oem": "...",
    "customer": "...", "customer_name": "...", "customer_phone": "...",
    "depot": "...", "depot_name": "...",          // default depot for this vehicle/customer
    "service_contract": "...",                     // active contract, auto-picked
    "service_type": "...",                          // derived from job_card_type
    "priority": "...",                              // derived from job_card_type
    "check_sheet": "Sheet B",                       // derived from odometer (PMS)
    "pms_tolerance_level": "...",                    // from contract/odometer (PMS)
    "last_pms_date": "...", "last_pms_odometer": 0,  // for Breakdown autofill
    "last_serviced_by": "...", "last_service_tolerance_level": "...",
    "suggested_complaints": [ ... ],                // recent + common, for the complaint SmartSelect
    "suggested_subsystems": [ ... ],                // most-replaced groups on this vehicle/model
    "odometer_estimate": 0                          // last known + avg daily km (editable hint)
  }
}
```

This consolidates today's scattered `get_customer_name` / `get_last_pms_info` / `search_*` calls.
The controller already computes most of these on `before_save`; this endpoint exposes them **before**
save so the UI can show them as pre-filled (and locked where the PRD says non-editable).

### 2.C Master data that makes "suggestions" possible (🔴 MISSING — required)

Dropdowns-with-suggestions need something to suggest. Create these lightweight masters (each a
simple DocType + seed fixture + `after_migrate` seeder, following the existing
`patches/v0_x/seed_*.py` pattern):

| New master | Backs which field(s) | Seed source | Notes |
|---|---|---|---|
| **Complaint Catalog** (`complaint_catalog`) | `complaint_description`, customer/driver VOC | PRD VOC + common bus complaints, grouped by Subsystem | `allowCreate` on → new complaints captured become future suggestions. |
| **Fault Code** (`fault_code`) | Breakdown `fault_code_1/2/3` | OEM fault-code lists (Azad/NaArNi) + CAN codes | Link to Subsystem/Part Group; shows description on select. |
| **Observation Template** (`observation_template`) | `se_observations`, technician notes | Common SE findings per Subsystem | Canned phrases; still allows free addition. |
| **Service Reason** (reuse Select) | Software `reason`, update type | PRD: Performance Improvement / Regular / Emergency (Bug Fix) | Already enumerable — keep as SmartSelect over a fixed list. |

City/State: replace the 37-item `<select>` with a SmartSelect over a small seeded
**Indian State/City** list (or reuse Frappe's Country/Territory). Assigned-user, depot, source,
status dropdowns: SmartSelect over existing list endpoints (`list_users_by_role`, `search_depots`,
CRM dropdowns) — they already return data, they just need the component.

### 2.D Field-by-field contract (the spec the UI must satisfy)

Legend: **AUTO** = pre-filled, read-only or editable-hint, no typing. **SMART** = SmartSelect
(searchable + suggestions). **TEXT** = free text *allowed* (last resort; offer suggestions first).

**Job Card Create — common header (all 4 types)** — drives `JobCardNew.vue` + mobile:

| Field | Today | Target | Source |
|---|---|---|---|
| Location / Depot | bespoke search | **AUTO** (default from vehicle) → **SMART** to change | `get_job_card_form_context.depot` |
| Job Card Number | not shown | **AUTO** (server autoname; show after create) | autoname |
| Vehicle Number | bespoke search | **SMART** (reg + model + customer sublabel) | `search_vehicles` |
| Customer Name | hidden autofill | **AUTO** read-only | context |
| Odometer | manual number | **AUTO** estimate (last + avg/day) → editable; OR **photo→OCR** (Phase 2) | context `odometer_estimate` |
| Service Type | hidden | **AUTO** (from type) | context |
| Check Sheet (PMS) | not shown | **AUTO** (from odometer) read-only | context |
| OEM | not shown | **AUTO** read-only | context |
| Service Contract | not shown | **AUTO** (active contract) read-only | context |
| PMS Tolerance (PMS) | not shown | **AUTO** read-only | context |
| Date / Start / End time | not shown | **AUTO** (server, non-editable per PRD) | server |
| Priority | hidden | **AUTO** (from type) editable via SMART | context |
| Complaint / VOC | **free textarea** 🔴 | **SMART multiple** (Complaint Catalog, grouped by subsystem) + optional note | §2.C |
| SE Observations | **free textarea** 🔴 | **SMART** (Observation Template) + optional note | §2.C |
| Assign Technician / SE | varies | **SMART** (`list_users_by_role`, default = self/last) | existing |

**Type-specific:**

- **PMS+Repair** → Subsystems: **SMART multiple** (suggest most-replaced for this model). Repair
  items: part = **SMART** over Part (badge = in-stock); activity/component status = tap chips;
  qty stepper. No free-text part names.
- **Only Repair** → same repair-item rules; chassis photo capture; per-part replace/repair chips.
- **Software Update** → component = **SMART** (Subsystem/ECU list); reason = **SMART** (fixed
  list); versions captured by **photo** then confirmed, not typed where avoidable.
- **Breakdown** → incident place = chips (Depot/En Route); groups impacted = **SMART multiple**;
  `fault_code_1/2/3` = **SMART** (Fault Code master) 🔴 (today free text); fix type / risks = chips.

**Inventory Request panel** → part = **SMART** (badge in-stock/qty), urgency = chips, qty = stepper.

**Inspection wizard** → vehicle = **SMART** (today it's a *manual text box* 🔴 — inconsistent with
JobCardNew; fix); each check = tap chips (Good/Recommended/Immediate) or measurement stepper with
the min/max already known; notes offer Observation Template suggestions.

**CRM Lead form** → state/city/depot/source/assigned/industry/interested = **SMART**; existing
customer/company = **SMART** over Customer (so we don't create duplicates); phone validated; only
the final "notes" stays TEXT.

**Acceptance for §2:** open any create form, complete it on a phone using only taps + the camera,
typing only inside a search box or an optional note. No bare `<select>`, no required free-text.

---

## 3. Backend gaps to close

| Item | State | Work |
|---|---|---|
| `get_job_card_form_context` | 🔴 | New endpoint, §2.B. Reuse existing `before_save` derivations. |
| Complaint Catalog / Fault Code / Observation Template DocTypes + seeders | 🔴 | §2.C. Simple masters; `after_migrate` idempotent seeders like `patches/v0_x/seed_*`. Add to `fixtures` in hooks. |
| Suggestion endpoints | 🔴 | `list_complaints(subsystem?, txt)`, `list_fault_codes(group?, txt)`, `list_observation_templates(subsystem?, txt)` — each returns SmartSelect rows with `recent` flag (recent = used on this vehicle/model). |
| Structured inspection persistence | 🟡 | §4 — store checklist as rows feeding the score, not a comment. |
| Push/SMS dispatch | 🔴 | §7 — extend `notifications.py::_dispatch` sink. |
| Notification list API | 🟡 | `api/notifications.py` (get_my_notifications, unread_count, mark_read) — referenced by v1 §13 but confirm/implement for the bell. |
| Odometer OCR (Phase 2) | 🔴 | `upload.py` → read odometer photo → suggested reading (the PRD's "Auto Fill from Photo"). |

All new endpoints follow CLAUDE.md §6: docstring, `frappe.only_for`/`has_permission` at top,
plain-dict envelope `{success, data, message}`, `_()` strings, parameterized SQL only.

---

## 4. Structured Inspection — closing the score gap

**Problem.** `create_job_card_with_inspection` saves the checklist as a **comment**. The health-score
engine reads only `repair_items.component_status`. So Pre/Post-PMS scores ignore the actual
inspection — the PRD's central analytic is effectively disconnected from the data the technician
enters.

**Fix (minimal, reuses the existing score engine):**

1. New child table **`Job Card Inspection Item`** on Job Card: `check_id`, `label`, `category`
   (→ maps to Part Group `bus_system`), `input_type`, `value`, `component_status`
   (Good/Recommended/Immediate), `measurement_value`, `min`, `max`, `out_of_range`, `pre_photo`,
   `post_photo`, `phase` (Pre/Post).
2. Move the hardcoded checklists out of `useInspectionSheet.js` into a backend master
   **`PMS Checksheet` + `PMS Checksheet Item`** (seed A/B/C/D from the existing composable data —
   it's already structured with id/label/category/inputType/min/max). Frontend fetches the sheet by
   odometer via an endpoint instead of bundling it. (Single source of truth; matches CLAUDE.md
   fixtures rule.)
3. `create_job_card_with_inspection` writes inspection rows (not a comment); `_compute_health_scores`
   additionally aggregates **inspection** rows by category (each maps Good/Recommended/Immediate →
   10/5/0) so Pre-PMS score reflects the real inspection, and Post-PMS reflects re-inspection after
   work. Repair-item scoring stays for repair-only flows.
4. Vehicle Health Card already renders `category_scores`; once inspection feeds them, the card and
   the customer Health Card report become accurate automatically.

This is the highest-value backend change after §2, because it makes the headline "vehicle health
score" trustworthy.

---

## 5. Web app (Vue SPA) remaining work

| Screen / piece | State | Work |
|---|---|---|
| `SmartSelect.vue` | 🔴 | §2.A — build first; it unblocks everything else. |
| `PhotoCapture.vue` | 🔴 | Camera/gallery capture with mark/circle annotation (PRD: circle what's broken). Used by inspection, repair, breakdown, software. |
| `JobCardNew.vue` | 🟡 | Rewire to one `get_job_card_form_context` call; replace bespoke searches with SmartSelect; add complaint/observation SmartSelects; surface AUTO fields as read-only chips. |
| Inspection wizard | 🟡 | Vehicle field → SmartSelect; checklist from backend sheet; write structured rows (§4); add PhotoCapture per failed item. |
| Repair entry | 🟡 | Part SmartSelect with stock badge; replace/repair chips; pre/post photo; >₹1k → approval CTA. |
| Customer approval (public, tokenized) | ✅ backend / 🟡 UI | Confirm the no-login approval page consumes `submit_approval_via_link`; per-part approve/reject with reason SmartSelect. |
| Software update panel | 🟡 | Step UI; version-by-photo; retry from step 7; reason SmartSelect. |
| Breakdown panel | 🟡 | Fault codes → SmartSelect (Fault Code master); travel/trial-trip timers; remote-resolution countdown. |
| Notification bell | 🔴 | §7 — header bell + list, unread count, deep-link to card. |
| Lead forms | 🟡 | All `<select>` → SmartSelect; existing-customer dedupe. |
| Dashboards | ✅/🟡 | Already real-data; add SLA color badges + TAT analytics tiles (PRD Phase 2). |

Keep CLAUDE.md §7 rules: `createResource`/`createListResource` only, role-gated rendering via
`permissions.js` (omit unauthorized DOM, never CSS-hide), EAS wizards ≤4–5 fields/step.

---

## 6. Mobile app — **native Kotlin (Android)** → see `KOTLIN_APP_PLAN.md`

> **Updated:** the App is now a **native Kotlin (Android)** app for **Service Engineers** — chosen for
> speed on low-end devices — not React Native. Full detail (architecture, geo/time/user **photo
> stamping**, **Alerts tab**, **Tickets + deeplinks**, screen specs, performance budget, and the
> backend additions that must deploy first) lives in **`KOTLIN_APP_PLAN.md`**. The summary below is
> retained for context; the Kotlin plan governs.

The APIs are already mobile-ready (token auth in `auth.py`, plain-dict envelopes) and this sprint
added the App's backbone (`get_job_card_form_context`, suggestion search endpoints, `register_push_token`
+ FCM, `vm_notification` realtime).

**Plan (new repo/folder `mobile/`, Expo + TypeScript):**

1. **Auth** — token login (`login_with_phone` → store api_key:api_secret in SecureStore);
   `Authorization: token …` on every request. Phone-first, matching the web ([[feedback-phone-login]]).
2. **Shared contract** — one `api.ts` mirroring the web wrapper; reuse the **same endpoints**,
   especially `get_job_card_form_context` so the create flow is identical and equally auto-filled.
3. **The dropdown mandate applies doubly on mobile** — implement a native `SmartSelect` (bottom-sheet
   list, search, suggestions, big tap rows). Native camera for PhotoCapture (odometer, pre/post,
   circle-to-mark). This is where auto-fill matters most (technicians in the field).
4. **Screens (parity, role-scoped):** login → role dashboard → Job Card create wizard (4 types) →
   inspection wizard → job card detail + transitions → inventory request → breakdown timers →
   notifications. Offline-friendly capture queue for poor depot connectivity (Phase 2).
5. **Push** — Expo Notifications → device token registered via a new
   `api/notifications.py::register_push_token`; backend sends via FCM/APNs (§7).

Sequencing: mobile starts **after** `SmartSelect` + `get_job_card_form_context` land on web, so the
contract is proven once and reused. Phase 1 mobile = technician/SE field flows (create, inspect,
photos, transitions, push); customer/admin stay web.

---

## 7. Notifications: instant, sensitive, multi-channel

PRD requires **In-App + Push + SMS** (email reserved for reports/approvals), delivered
**immediately**. Today the sink `fleet_service/notifications.py::_dispatch(...)` already fans out to
four channel functions — `_dispatch_in_app` (Notification Log) ✅, `_dispatch_push` 🔴 **stub**,
`_dispatch_sms` 🔴 **stub**, `_dispatch_email` ✅ — gated by site config. **What's missing for
"immediately":** (a) push/SMS providers aren't wired; (b) there is **no `frappe.publish_realtime`**,
so in-app messages sit in a table until the SPA happens to refetch — not instant; (c) no bell UI.

### 7.1 Immediacy architecture (the core change)

Notifications are of two kinds and must be delivered differently:

- **Event notifications** (state changes: created, approval requested, approved/rejected, parts
  allocated/issued, closed by tech, verified, reopened, breakdown declared, feedback request).
  These fire **synchronously inside the document event** (`on_update`/`after_insert`) the instant
  the change is committed — they must NOT wait for the scheduler. For each recipient:
  1. `frappe.publish_realtime(event="vm_notification", message={...}, user=recipient)` → the SPA/app
     receives it over socket.io in **sub-second**, updates the bell, and toasts it.
  2. `_dispatch_in_app` writes the Notification Log (durable history / unread count).
  3. `frappe.enqueue(_dispatch_push, ...)` and (for sensitive triggers) `_dispatch_sms` — fired
     immediately via a short queue so the HTTP response isn't blocked, delivered within seconds.
- **Time/SLA notifications** (80% TAT, breach, 30-min approval, 30-min remote-resolution). These are
  inherently time-based, so the scheduler owns them — but **tighten the cadence**: run the SLA
  monitors **every 1 minute** for the sensitive ones (breach, breakdown remote-resolution, customer
  approval) instead of 5, so escalation latency is ≤60s. Keep the cheap ones (feedback) hourly.

"Sensitive" delivery guarantees: each trigger has a **fixed recipient set by role** (already in
`notifications.py`), and the highest-priority triggers (TAT breached, breakdown declared, remote
resolution failed, customer approval request) **escalate** if not acted on — the existing idempotent
flags (`sla_breach_sent`, `customer_approval_escalated`, `remote_resolution_escalation_sent`) already
prevent duplicates and drive escalation; reuse them.

### 7.2 Channel work

1. **Realtime in-app** 🔴 — add `frappe.publish_realtime` calls in `_dispatch` (per recipient);
   frontend subscribes via frappe-ui's socket. This is the single biggest "feels instant" win.
2. **Bell + API** 🔴 — `api/notifications.py`: `get_my_notifications`, `get_unread_count`,
   `mark_notification_read`. `NotificationBell.vue` in `NavBar` (unread badge, list, deep-link).
3. **Push** 🔴 — replace the `_dispatch_push` stub: `register_push_token(token, platform)` + Push
   Token DocType; send via FCM (Android/web) + APNs (iOS). Already enqueued & config-gated — just
   implement the provider call. Mobile registers an Expo token.
4. **SMS** 🔴 — replace the `_dispatch_sms` stub with a pluggable provider (MSG91/Twilio/Gupshup)
   behind `send_sms(to, template, ctx)`; reserve for the 4 critical triggers (matrix below);
   templated, `_()`-wrapped, DLT-compliant template IDs.

Respect the demo-blocker guidance ([[feedback-demo-blockers]]): channels degrade gracefully — if
push/SMS isn't configured, realtime + in-app still fire and nothing hard-blocks.

### 7.3 Channel-by-trigger matrix (PRD "Notifications & Alerting" + "TAT/SLA")

Every row also gets **realtime in-app** (sub-second). Latency target: in-app instant, push ≤5s,
SMS ≤15s.

| Trigger | In-app | Push | SMS |
|---|:--:|:--:|:--:|
| Job card created | ✅ | – | – |
| Customer approval request | ✅ | ✅ | ✅ |
| Approve / reject | ✅ | ✅ | – |
| Parts allocated/issued | ✅ | ✅ | – |
| TAT 80% approaching | ✅ | ✅ | – |
| TAT breached | ✅ | ✅ | ✅ |
| Closed by technician / verified by SE | ✅ | ✅ | – |
| Reopened | ✅ | ✅ | – |
| Breakdown declared | ✅ | ✅ | ✅ |
| Remote resolution failed (30m) | ✅ | ✅ | ✅ |
| Feedback request (post-closure) | ✅ | ✅ | – |

---

## 8. Gap-based roadmap (sprints) — **APP-FIRST**

> **Sequencing decision (product owner):** the **mobile App is the primary client**. So we
> **tighten the backend to 100% and make it fully app-ready first**, then build the App, then bring
> the **web SPA up to parity as a secondary** track. Each sprint is independently shippable.

**Sprint 1 — Backend hardening to make it 100% & app-ready (no UI). ← START HERE.**
The App is only as good as the contract it calls, so finish the backend first.
- `get_job_card_form_context(vehicle, job_card_type)` — the single auto-fill call the App makes
  after picking a vehicle (returns every prefilled value; §2.B).
- Suggestion masters + endpoints: **Complaint Catalog**, **Fault Code**, **Observation Template**
  DocTypes + idempotent seeders + `list_*` search endpoints (so the App's dropdowns have data; §2.C).
- **Structured inspection → score** (§4): `Job Card Inspection Item` child + `PMS Checksheet`/`Item`
  masters seeded from `useInspectionSheet.js`; `create_job_card_with_inspection` writes rows;
  score engine aggregates them. Makes the health score trustworthy.
- **Instant-notification backend** (§7): `frappe.publish_realtime` on doc events; `register_push_token`
  + Push Token DocType; implement the `_dispatch_push` provider (FCM/APNs); `api/notifications.py`
  (list/unread/mark-read); tighten sensitive SLA monitors to 1-min.
- Finalize & document the **mobile API contract** (token auth + every endpoint the App needs).
- **Exit:** every App screen has a complete, permission-checked, auto-filled endpoint; `bench
  run-tests` green; you can drive a full PMS+Repair and Breakdown card via API calls alone.

**Sprint 2 — Mobile App Phase 1 (Expo + TypeScript) (§6). ← primary client.**
- Auth (phone → token in SecureStore), role dashboard, Job Card create wizard (4 types) on a native
  `SmartSelect` (bottom-sheet search + suggestions) + camera PhotoCapture, inspection wizard,
  job-card detail + transitions, inventory request, breakdown timers, **push + notifications screen**.
- **Exit:** a technician completes a field job card start-to-finish on the phone App, fully
  auto-filled, tapping only — and gets instant push on assignment/breakdown.

**Sprint 3 — Backend: reports + the rest of 100% coverage (§10) + SMS.**
- Three missing PDF reports (**Repair, Software, Breakdown/RCA**) + Depot-Manager
  report-approval→send-to-customer action; complete customer Summary Health Card (consumables +
  labour). Maintenance oil/coolant/filter capture endpoints/fields. Verify the full
  edit-permission/audit-trail matrix (§10.1) + per-card activity log. Wire **SMS** provider for the
  4 critical triggers.
- **Exit:** **100% PRD Phase-1 coverage** on the backend; every report generated and shareable.

**Sprint 4 — Mobile App Phase 1.5.**
- Surface reports in-App (view/share PDF), customer-facing tracking + per-part approval, feedback;
  offline capture queue for poor depot connectivity.
- **Exit:** customer + closure flows work on the App; resilient to flaky depot networks.

**Sprint 5 — Web SPA to parity (secondary).**
- Reuse the same proven contract: `SmartSelect.vue` + `PhotoCapture.vue`; rewire `JobCardNew` and
  the repair/software/breakdown panels to zero free-text; notification bell; lead forms; SLA badges
  + TAT tiles. (This is the old web-first Sprint 1/3/4 work, now secondary.)
- **Exit:** desk-less staff/customers can do everything on the web too.

**Sprint 6 — Phase 2 polish.**
- Odometer/version OCR; AI damage-level suggestions; exploded-diagram part picker; warranty;
  vendor job mgmt; WhatsApp job-card creation. The PRD's explicit "Phase 2" tables.

---

## 9. Verification & acceptance

Per CLAUDE.md §8.3 + §13. For each sprint:

- **Backend:** `bench run-tests --app vehicle_maintenance` — unit tests for new endpoints'
  permission checks, the form-context derivations, score aggregation incl. inspection rows, and
  notification channel routing. Migrations clean (`bench migrate`), fixtures exported.
- **The dropdown audit (mandatory gate every sprint):** grep the changed Vue/RN for raw `<select>`
  and required free-text `<textarea>`/`<input type=text>` on create forms — **must be zero** outside
  optional-notes. New code uses `SmartSelect`/`PhotoCapture` only.
- **Auto-fill audit:** on every create form, count fields the user must type — target ≤1 (the search
  query) per step; everything else AUTO or tap.
- **Manual smoke (the real test):** complete each of the 4 job-card types on a phone-sized viewport
  (and the Expo app from Sprint 5) using only taps, search, and camera. If any step forces typing
  a value that exists in a master, it fails the gate.
- Security checklist (CLAUDE.md §13) on every PR; never modify Frappe/ERPNext core.

---

## 10. 100% PRD coverage checklist

This walks **every section of the PRD** so the remaining ~10% is explicit and nothing is dropped.
Status: ✅ done · 🟡 partial · 🔴 missing. "Phase 2" items are PRD-deferred and live in Sprint 6.

### 10.1 Foundations
| PRD area | Status | To reach 100% |
|---|---|---|
| 5 user roles + access control | ✅ | — |
| 4 job card types | ✅ | — |
| Job card states + Force Closed / Reopened side states | ✅ | — |
| TAT definitions & per-type SLAs + escalation paths | ✅ | — |
| Inventory request & allocation (5 steps) + >₹1k gate | ✅ | — |
| Force close & severity matrix (Minor/Major/Critical, 24h follow-up) | ✅ | — |
| Audit trail & **edit-permission matrix** (who edits in each state, all logged) | 🟡 | Verify/complete enforcement for every state row in the PRD table (Open=SE/DM, In-Progress=SE only, Closed=DM only +reason, reopen=Customer/Central Ops, force-close reason mandatory, delete never). Surface a per-card **activity log** view. |

### 10.2 PMS + Repair flow (PRD §1.A, 17 steps)
| Step / feature | Status | To reach 100% |
|---|---|---|
| Create form auto-fill (location, JC#, vehicle, customer, odometer, service type, checksheet, OEM, contract, PMS tolerance, date/times) | 🟡 | §2 — wire `get_job_card_form_context`; surface all AUTO fields; non-editable date/times. |
| Auto-select checksheet A/B/C/D by odometer | ✅ (frontend) → 🟡 | §4 — move sheet to backend master, single source. |
| Inspection POC = Technician/Self assignment | 🟡 | Add SMART picker (default self) on create. |
| Three-tier inspection (Good/Recommended/Immediate) + skip-photo-when-okay | 🟡 | §4 structured rows + PhotoCapture only on not-okay. |
| Consumables auto-marked "not okay" | 🔴 | Rule in inspection: consumable items default to Recommended/Immediate per PRD note 9. |
| **Pre-PMS / Post-PMS score** from inspection | 🟡 | §4 — feed inspection rows into score engine (currently only repair_items). |
| Repair Job List (denting=labour, part-change=inventory lookup, >1k approval, post-repair photo, customer reject→re-estimate) | 🟡 | Repair entry UI on SmartSelect + photo; reject path already in backend. |
| **Maintenance Job List** — oil/coolant: pre & post photo/video + volume; filter: pre & post photo | 🔴 | `maintenance_items` has pre/post photo fields; build the guided oil/coolant/filter capture flow + volume input. |
| Force close unresolved (severity) / mark open | ✅ | — |
| Technician marks closed → SE verify → SE close (completion time) → reopen loop | ✅ | — |
| **Vehicle Health Card** generated on SE closure + Score Calculation | ✅ backend / 🟡 | accurate once §4 lands. |
| **PMS report (PDF)** for Naarni team + **Summary Health Card** to customer (parts/lubes/consumables + labour) | 🟡 | `pms_report` + `vehicle_health_card_report` print formats exist; complete the customer summary (consumables + labour lines) + the Depot-Manager **report-approval→send-to-customer** action. |

### 10.3 Only Repair flow (PRD §2)
| Feature | Status | To reach 100% |
|---|---|---|
| Subsystem multiselect, chassis/VIN photo, fault context | 🟡 | SmartSelect multiple + PhotoCapture. |
| Group→parts selection, replace/repair per part, qty, photo, inventory lookup (hidden from customer) | 🟡 | Repair UI. |
| Customer approval **link** (per-part approve/reject + feedback); N.Maint.Head fallback if unresponsive | ✅ backend / 🟡 UI | Build the public tokenized per-part approval page. |
| Post-repair photo per part; close→SE verify | 🟡 | UI. |
| **Repair report (PDF)** to customer | 🔴 | New print format (sample: Repair Report). |

### 10.4 Software Update flow (PRD §3)
| Feature | Status | To reach 100% |
|---|---|---|
| Component multiselect + reason (Perf/Regular/Emergency) | 🟡 | SmartSelect. |
| Pre version photo → upgrade → calibration values → post version photo; retry from step 7 on fail | 🟡 (backend fields ✅) | Step UI + PhotoCapture + retry. |
| SE verify per component; report (optional to customer) | 🟡 | UI + optional **software report (PDF)**. |

### 10.5 Breakdown flow (PRD §4)
| Feature | Status | To reach 100% |
|---|---|---|
| Immediate JC creation; last-PMS autofill | ✅ | — |
| Remote diagnosis attempt; **30-min timer**; incident place; group impacted; fault codes | ✅ backend / 🟡 | Fault codes → Fault Code master SMART; countdown UI. |
| Travel start/arrive timestamps + duration; on-spot diagnosis | ✅ backend / 🟡 | Timer UI. |
| Resolution: permanent/temporary/force-closed; next-level engineer; recurrence/occurrence risk; **fleet broadcast** | ✅ | — |
| Trial trip (start/end km + time), total downtime, vehicle handover | ✅ backend / 🟡 | Trial-trip UI. |
| **RCA report** request → Aftersales Eng → Depot Manager shares with customer | 🟡 | RCA fields ✅; build the **breakdown/RCA report (PDF)** + share flow. |

### 10.6 Notifications & reporting (cross-cutting)
| Feature | Status | To reach 100% |
|---|---|---|
| 12 triggers, correct recipients | ✅ | — |
| **Instant** in-app (realtime), **push**, **SMS** | 🟡/🔴 | §7 — realtime publish + push/SMS providers + bell. **This is the user's emphasis.** |
| Email reserved for reports/approvals | ✅ | — |
| PDF reports: PMS ✅, Health Card ✅, **Repair 🔴, Software 🔴, Breakdown/RCA 🔴** | 🟡 | §10.2–10.5 — add the three missing print formats + report-approval-to-customer action. |

### 10.7 Explicitly Phase 2 (defer to Sprint 6 — do NOT build now)
NPS/feedback analytics, live customer tracking link, ticketing into JC, invoice generation, AI/LLM
diagnosis & damage detection, exploded-diagram part picker, lifetime part-group scoring, DIY repair,
vendor job management, warranty raise/claim/check, WhatsApp/phone/email job-card creation, CAN
fault-code reading, remote video/chat support, factory-level flagging, cross-depot broadcast UI.
(PRD "Phase 2" tables for PMS, Repair, Software, Breakdown.)

**Bottom line for 100%:** the closing 10% = §4 (structured inspection→score) + §7 (instant
push/SMS/realtime + bell) + three PDF reports & the report-approval-to-customer action + the
maintenance oil/coolant/filter capture flow + completing the type-specific create UIs on
SmartSelect/PhotoCapture + verifying the full edit-permission/audit matrix. All of it is in
Sprints 1–4.

---

### Appendix — pointers into the v1 archive (still valid)

- Full Job Card / child-table field lists: archive §3.
- Complete API surface & mobile auth/endpoint contract: archive §7, §13.
- Sample-report → PDF template mappings: archive §10.
- Notification trigger list (all 12) with recipients: archive §9.1.
