# PRD Alignment Plan — Job Card System

> Tracks the 5-milestone effort to close every gap between the current `vehicle_maintenance` implementation and `PRD JOB CARD.pdf` (17 pages, 801 lines).
>
> Created: 2026-04-23. Owner: Mayank. Source of truth during this push.

---

## Default Decisions (taken when user approved the overall plan)

| # | Question | Decision |
|---|---|---|
| 1 | Scope deprioritization? | None — full PRD scope in play |
| 2 | Check-sheet thresholds (PRD wants 20K/40K/80K, code has 15K/60K) | **Additive** — add 20/40/80 as new valid thresholds; old values remain for in-flight JCs. A data patch in M2 reconciles old labels where safe. |
| 3 | OEM field | **Link on Vehicle master**, auto-fetched into Job Card. Removes hardcoded "NaArNi". |
| 4 | Health Card | **Separate DocType** `Vehicle Health Card` with its own print format (PDF routing). |
| 5 | Phase 2 PRD items (AI/LLM, WhatsApp JC, warranty, vendor mgmt) | Deferred — out of scope for this 5-milestone push. |

---

## Milestone Tracker

### ✅ Milestone 0 — Audit (complete, 2026-04-23)
Full gap analysis captured in chat; ~23 gaps identified across 3 severities.

### ✅ Milestone 1 — Backend Schema Completion (complete, 2026-04-23)
**Goal:** Close data-model gaps so the UI in later milestones has everything to bind to.

Tasks:
1. Per-category PMS score child table (`job_card_category_score`)
2. `inspection_poc` (Link→User) + `inspection_poc_self` (Check) fields on Job Card
3. `subsystem_affected` → Table MultiSelect backed by a new `Subsystem` master DocType
4. `oem` → Link fetched from `Vehicle.oem`; Vehicle gets new `oem` field (Link to a lightweight `OEM` DocType or Select with Azad/NaArNi)
5. Breakdown remote-resolution timer: `remote_resolution_started_at`, `remote_resolution_status`, `remote_resolution_failed_at`
6. New scheduler task `monitor_remote_resolution_sla` + notification trigger #11
7. Three-tier inspection status (`Good` / `Repair-Replace Recommended` / `Repair-Replace Immediately`) on `job_card_repair_item` + `job_card_maintenance_item`
8. `Vehicle Health Card` DocType (fields only; generation logic arrives in M2)
9. Extend check-sheet threshold Select options to include 20K/40K/80K
10. `bench migrate` + run `test_job_card.py` — zero regressions.

**Acceptance:** Migration clean, tests green, all new fields visible in Desk form, scheduler tick runs without error.

### ✅ Milestone 2 — Health Card + Score Engine (complete, 2026-04-23)
- Vehicle Health Card auto-created on SE closure (idempotent; non-PMS skipped)
- Per-category scores already persist (from M1) — builder copies them into the Health Card
- Print format `Vehicle Health Card Report` with customer-facing PDF layout
- Whitelisted API `get_for_job_card` for the Vue portal (M3 input)
- Added Administrator workflow-transition bypass for consistency with force-close and for testability
- 6 new tests; 19/19 pass

### ✅ Milestone 3 — Frontend: PMS + Repair flow (complete, 2026-04-23)
**Pivot from original plan:** rather than a new `PmsRepairWizard.vue` (the
JobCardNew flow already handles creation), M3 focused on enhancing the
post-creation work surface so the entire PMS lifecycle is usable.
- 20K/40K/80K thresholds in `useInspectionSheet.js` — added Sheet D (>80K)
- Three-tier inspection status (Good / Recommended / Immediately) replacing
  the old pass/fail UI; findings step surfaces both tiers distinctly
- Reusable components: `RepairJobTable`, `MaintenanceJobTable`,
  `ForceCloseButton`, `HealthScoreCard`
- Customer-facing `HealthCardView.vue` page with PDF download link
- Enhanced `JobCardDetail` — shows health scores, per-category breakdown,
  inline Repair/Maintenance editors, Force-Close modal, Health Card link,
  SLA-breach badge, customer-approval banner
- Extended `get_job_card_summary` API with health_card, score_improvement,
  force_close fields, category_scores, sla_breached, requires_customer_approval
- New whitelisted APIs: `force_close_job_card`, `save_repair_items`,
  `save_maintenance_items`, `list_part_groups`
- Router: `/service-portal/job-card/:jobCardName/health-card`
- End-to-end smoke test passes: PMS JC create → close → Health Card auto-generated

### ✅ Milestone 4 — Only Repair / Software Update / Breakdown flows (complete, 2026-04-23)
**Pivot (same as M3):** delivered as inline panels on `JobCardDetail` rather
than 3 × modal wizards. Same PRD outcomes, less duplication.
- Backend: `Job Card Software Component` child DocType; SW components table +
  `rca_notes` + `rca_received_at` + `vehicle_handover_at` on Job Card schema
- Breakdown auto-fills Last PMS info from the vehicle's most recent closed PMS
  card on `before_insert` (PRD p.15 — 4 fields)
- `rca_received_at` stamps on first `rca_notes` population; never overwrites
- New whitelisted APIs: `update_breakdown_diagnosis`, `save_software_components`,
  `save_subsystems`, `list_subsystems`, `get_last_pms_info`
- `get_job_card_summary` extended with a `breakdown` block (18 fields),
  `software_components`, `subsystems`; RCA gated to internal roles
- New Vue components:
  - `SubsystemPicker.vue` — searchable, category-grouped chip multi-select
  - `BreakdownDiagnosisPanel.vue` — fault codes, 30-min remote res status,
    travel/arrive marks, fix type, recurrence/occurrence risk, trial trip with
    auto dead-km, vehicle handover, downtime, RCA notes
  - `SoftwareUpdatePanel.vue` — component rows with reason, pre/post versions,
    calibration (internal-only section), retry flow with retry_count
- `JobCardDetail` gained type-aware panels plus subsystem editor
- 6 new tests (25/25 total passing); end-to-end smoke covers all three types

### ✅ Milestone 5 — Inventory + Customer Portal + Polish (complete, 2026-04-23)
- Backend: new `Customer Feedback` DocType (NPS + rating + comments, unique
  per Job Card, only allowed when Closed)
- Backend: new APIs — `create_inventory_request`, `advance_inventory_status`,
  `reopen_job_card`, `submit_customer_feedback`, `get_customer_feedback`
- `notify_closed_state_edit` — PRD p.3 Central Ops alert on any closed-card edit
- 3 new Vue components:
  - `InventoryRequestPanel.vue` — role-aware list + create form + status
    transitions (Allocate / Issue / Receive) driven by the backend role gates
  - `FeedbackModal.vue` — 5-star rating + 0-10 NPS + recommend chip + comments,
    pre-loads existing feedback for edits
  - `ReopenButton.vue` — mandatory reason, gated to Customer + Central Ops
- `JobCardDetail` now exposes all three to the right roles
- 8 new tests (33/33 total); end-to-end smoke covers Inventory flow (4
  transitions), Reopen, and Feedback submit-then-update

**Out of this milestone (deferred to Phase 2 per PRD):**
- Photo annotation (circle/mark on damage images) — out of M5 scope
- VOC at subsystem-component level — `complaint_description` remains the
  source of truth; finer-grained VOC belongs with exploded-diagram selection
  in Phase 2

---

## Out-of-scope (Phase 2 per PRD)
AI/LLM diagnosis, exploded-diagram selection, WhatsApp/email JC creation, DIY troubleshoot, warranty claim flow, vendor management, CAN fault-code reading.

---

## Progress Log
| Date | Milestone | Note |
|---|---|---|
| 2026-04-23 | M0 | Audit complete |
| 2026-04-23 | M1 | Complete — 13/13 tests pass; 5 new DocTypes (Subsystem, Job Card Subsystem, OEM, Job Card Category Score, Vehicle Health Card); Job Card schema gained inspection POC, 4-tier check sheet, Table MultiSelect subsystems, Link OEM with auto-fetch, remote-resolution 30-min timer, per-category score persistence, health_card link; new scheduler `monitor_remote_resolution_sla` wired to notification #11 |
| 2026-04-23 | M2 | Complete — 19/19 tests pass; Health Card auto-generation on SE closure (idempotent, PMS-only); `Vehicle Health Card Report` print format with customer-facing HTML/CSS; whitelisted API for Vue portal; Admin workflow-transition bypass. |
| 2026-04-23 | M3 | Complete — 19/19 tests pass; 6 new Vue components, 1 new page, 4 new API endpoints, router updated; end-to-end smoke verified. Pivoted from a separate PMS wizard to enhancing JobCardDetail as the PMS work surface. |
| 2026-04-23 | M4 | Complete — 25/25 tests pass; 1 new child DocType, 5 new APIs, 3 new Vue panels, Job Card summary extended, JobCardDetail now type-aware; Breakdown autofill + RCA timestamp hooks; smoke-tested end-to-end. |
| 2026-04-23 | M5 | Complete — 33/33 tests pass; new Customer Feedback DocType, 5 new APIs, Central Ops closed-edit notification, 3 new Vue components (Inventory / Feedback / Reopen); smoke-tested end-to-end. |
| 2026-04-23 | Phase A (permission fixes) | Complete — 6 DocType permission fixes; Aftersales Eng gained permlevel 1 write on Job Card for RCA; Inventory Request/Vehicle no-delete; Part/Service Estimate/Inventory Request role-tightened per PRD. |
| 2026-04-23 | Phase B (feature gaps) | Complete — 44/44 tests pass (11 new). Gap 15 notification trim, Gap 14 POC Both allowed, Gap 16 Breakdown on-site SLA, Gap 10 Force/Process Override, Gap 11 Groups Impacted Table MultiSelect, Gap 13 SW Update optional customer-report flag, Gap 8 Critical 24h auto-follow-up (controller + scheduler). |
| 2026-04-23 | Phase 2 (deferred items) | Complete — 54/54 tests pass (10 new). Closure Record DocType + controller hook, Part-level rejection feedback, Customer approval token + tokenised external link, TAT Adherence Report API, Repeated-issue detection, Fleet-wide Breakdown broadcast on high occurrence risk, Push/SMS channel scaffolding. Also fixed on_update double-fire bug (redundant doc_events hook). **PRD alignment complete.** |
