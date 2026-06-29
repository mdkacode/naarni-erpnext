# Job Card — PRD E2E Completion Plan

> Backend is ~95% PRD-complete (workflow, scoring, SLA, force-close, inventory, approval,
> breakdown, software, feedback all exist as whitelisted APIs). The gap is the **Android app**,
> which drives only: create-with-inspection, list, summary, transition, edit complaint/priority.
> This plan turns `JobCardDetailScreen` into the full PRD lifecycle cockpit, role- & state-gated.

## Backend method inventory (already exists)
- `save_repair_items(job_card, rows)` — repair job list (part_group, bus_system, description,
  activity_type, component_status, qty, estimated/actual_amount, item_status)
- `save_maintenance_items(job_card, rows)` — maintenance job list (maintenance_type, action, qty, unit, amounts)
- `save_software_components(job_card, rows)` — software update (component, pre/post_version, status, retry)
- `create_inventory_request(job_card, part, quantity, urgency, notes)` + `advance_inventory_status(name, next_status)`
- `force_close_job_card(job_card, severity, reason)` — Minor/Major/Critical authority-gated
- `record_customer_approval_decision(job_card, decision, ...)` — accept/reject per-part
- `update_breakdown_diagnosis(...)` — remote diagnosis / travel / trial trip / RCA
- `reopen_job_card(job_card, reason)`
- `submit_customer_feedback / get_customer_feedback`
- `save_groups_impacted / save_subsystems`
- `get_job_card_summary` already returns: repair_items, maintenance_items, category_scores,
  software_components, breakdown{}, scores, force-close, approval flags, viewer_roles, viewer_is_depot_manager

## Backend additions (small)
- [ ] `list_inventory_requests(job_card_name)` reader (summary doesn't return them)
- [ ] `list_parts(part_group?, txt, limit)` picker for inventory + repair editors
- [ ] `get_job_card_summary`: include `inventory_requests` list inline (avoid extra call)

## Phases (implement one by one)

### Phase 1 — Detail screen: full read-only visibility ✅ verifiable, no deploy
Expand `JobCardDetail` DTO + parsing to include repair_items, maintenance_items, category_scores,
software_components, breakdown, force-close, approval, viewer roles. Render read-only sections so
the whole job card is visible per PRD. Role/state gating helpers.

### Phase 2 — Repair & Maintenance Job Lists (Technician/SE)
API+repo: `saveRepairItems`, `saveMaintenanceItems`, `listPartGroups`, `listParts`.
UI: add/edit/remove repair rows (part group + activity + component status + qty + amount) and
maintenance rows. Post-work item status. Save → reload.

### Phase 3 — Inventory Request flow
Backend: `list_inventory_requests`. API+repo: list, `createInventoryRequest`, `advanceInventoryStatus`.
UI: Tech/SE raise request (part picker + qty + urgency); DM sees Allocate→Issue; Tech acknowledges Received.
Status chips reflect Requested→Allocated→Issued→Received.

### Phase 4 — Customer Approval + Force Close + Reopen
API+repo: `recordApprovalDecision`, `forceCloseJobCard`, `reopenJobCard`.
UI: approval card (Approve / Reject+reason) when Awaiting Customer Approval; Force-Close dialog
(severity Minor/Major/Critical + mandatory reason, authority hint); Reopen action when Closed.

### Phase 5 — Type-specific: Software Update + Breakdown
Software: components editor (pre/post version, status, retry, calibration).
Breakdown: diagnosis panel (incident place, fault codes, remote status, travel start/arrive,
trial trip km, fix type, RCA) via `update_breakdown_diagnosis`.

### Phase 6 — Health scores + Customer feedback + report
Category score breakdown table + improvement %. Customer feedback card (rating + comment).
"Send report to customer" toggle.

## Verification
Build the debug APK after each phase; install on the **physical device** (`863d00...`, never emulator);
drive the flow logged in as the user's SE. Backend additions deploy only with explicit user authorization.
