# PRD — Naarni Care Process Engine

**Supersedes:** the battery-specific design in `BATTERY_QC_PRD.md`, which becomes **seed data** on this engine
**Status:** Draft for approval — design only, no code written
**Date:** 2026-08-10
**Target app:** `vehicle_maintenance`, new module `process_engine`

---

## 1. What changed, and why it is the right call

The battery PRD designed a battery module. You have asked instead for a **generic, admin-authored process engine** that the Naarni Care app plugs into — Processes, each holding Modules, each holding Steps, with values and pass/fail actions defined entirely by an admin.

That is the correct instinct, and the evidence is already in your codebase:

- `inspection_sheets.py` — PMS A/B/C/D checklists, **hardcoded in Python**. Changing a check needs a developer, a commit and a deploy.
- The battery QC design would have added a **second** hardcoded checklist.
- Vehicle PDI, depot opening checks, driver daily checks, tyre inspections, 5S audits — every one of these is the same shape, and each would have become a third, fourth and fifth hardcoded checklist.

**One engine, many processes.** Battery QC stops being code and becomes a record. The existing PMS sheets migrate onto the same engine and the hardcoded Python gets deleted.

### 1.1 The honest cost

| | Battery-specific build | Generic engine |
|---|---|---|
| Effort | ~5–7 weeks | ~8–10 weeks |
| Cost of process #2 | ~4 weeks | **~2 hours of admin time** |
| Cost of process #10 | ~35 weeks cumulative | still ~2 hours each |

**Break-even is process #2.** You already have at least four in view (Battery QC, PMS A/B/C/D, Vehicle PDI, driver daily check). The decision is not close.

The trap to avoid is pretending the engine is infinitely general. §7 states exactly where the boundary is, in writing, so nobody is surprised in month six.

---

## 2. The one design decision that makes it generic

**Outcomes are data, not code.**

Most checklist systems hardcode "Pass / Fail". That single decision is what makes them un-generalisable — the moment someone needs *Good / Recommend / Immediate* (which your PMS sheets already use), or *OK / Rework / Scrap*, or *Present / Missing / Damaged*, you are writing code again.

So in this engine there is **no built-in Pass/Fail**. There is a **Choice** step whose options are admin-authored rows, and each option carries its own meaning:

| Option label | `is_pass` | `is_critical` | Colour | Icon |
|---|---|---|---|---|
| Pass | ✅ | — | green | ✓ |
| Fail | — | ✅ | red | ✕ |
| N/A | ✅ | — | grey | – |

Pass/Fail is just that table with two rows. Your PMS three-tier is the same table with three. A torque check is a Number-with-tolerance whose pass band is admin-set. **Every outcome vocabulary in the company becomes configuration.**

Everything else in this document follows from that decision.

---

## 3. Hierarchy and naming

Your model: **Process → Modules → Steps.** Adopted exactly.

One recommendation, and it is a real one. **"Module" is triply overloaded here:**
1. Frappe already has a `Module Def` doctype (`fleet_service` is a Frappe module).
2. A battery pack physically contains **3 modules** — the thing being inspected.
3. Your process grouping.

A schema that uses one word for three things costs you forever. So:

> **Schema name:** `Process Stage`
> **What your team sees:** whatever you want — the display label is a field.

`Process Definition.stage_label` sets the word per process. Set it to `Module` and every screen, button and report in the battery process reads "Module". A different process can call them "Phases" or "Sections". **You get your word; the schema stays unambiguous.**

```
Process Definition            "Battery Assembly QC"        (versioned, published)
└── Process Stage             "Before Installation"        (the sign-off gate)
    │                         └── section: "Bottom Cooling Plate"   ← screen grouping
    └── Process Step          "Check welding quality"      (the question)
        ├── Options           Pass / Fail / N-A            (the outcome vocabulary)
        ├── Conditions        show only if step 4 = Fail   (branching)
        └── Actions           on Fail → notify + quarantine (the automation)
```

Three authored levels, exactly as you described. `section` is a plain field on the step that drives screen pagination — so a 17-step stage still renders as 5 right-sized screens without a fourth level of nesting.

---

## 4. Subject binding — what makes it plug-and-play

This is the piece that turns a checklist tool into a platform, and it is the part most teams miss.

A process has to run *against something*. `Process Definition.subject_doctype` names it:

| Process | `subject_doctype` | `identifier_mode` | Result |
|---|---|---|---|
| Battery Assembly QC | `Battery Pack` | Scan QR | Pack-level QC + genealogy |
| Vehicle PDI | `Vehicle` | Pick from list | Pre-delivery inspection |
| PMS A/B/C/D | `Job Card` | From context | Replaces `inspection_sheets.py` |
| Driver daily check | `Vehicle` | Scan QR | Daily fitness record |
| Depot opening | `Depot` | From session | Shift-start audit |
| 5S / safety audit | *(blank)* | Auto-generate | Standalone audit |

The run record links polymorphically via a Dynamic Link (`subject_doctype` + `subject_name`). Set the field and the process attaches to that entity's timeline; leave it blank and the process stands alone.

**That one field is the entire plug-and-play story.** Point a process at a doctype and it becomes that doctype's process. No code.

---

## 5. Schema

### 5.1 Definition layer — admin-authored, versioned

#### `Process Definition`
| Field | Type | Purpose |
|---|---|---|
| `process_code` / `process_name` | Data | Identity |
| `description`, `icon`, `color` | — | How it appears in the app's process list |
| `subject_doctype` | Link → DocType | §4. Blank = standalone |
| `subject_label` | Data | "Battery Pack", "Bus" — shown to the operator |
| `identifier_mode` | Select | Scan QR / Manual / Auto-generate / Pick from list / From context |
| `stage_label` | Data | §3 — "Module", "Stage", "Phase" |
| `version` / `status` / `effective_from` | — | Draft → Published → Retired |
| `allowed_roles` | Table | Who may run it |
| `author_roles` | Table | Who may edit it |
| `stages` / `steps` | Table | The content |
| `completion_actions` | Table | Fires on process complete |
| `report_template` / `default_brand` | Link | §10 |
| `allow_offline`, `allow_resume`, `expected_minutes` | — | Runtime behaviour |
| `min_app_step_types` | Int | §8.2 — app compatibility floor |

**Immutability:** Published is frozen. Editing forks `version + 1` as Draft. Runs record definition **and** version, so a run from March replays March's process exactly. Non-negotiable for audit.

#### `Process Stage` (child)
`stage_code` · `label` / `label_alt` · `sequence` · `signoff_role` · `requires_second_signoff` + `second_signoff_role` · `blocks_next_on_critical` · `screen_grouping` (By Section / Fixed Count / One Per Screen) · `steps_per_screen` · `entry_conditions`

#### `Process Step` (child) — the unit the admin actually authors

**Identity** — `step_code` (stable across reorders) · `sequence` · `stage` · `section` / `section_alt`

**Content** — `label` / `label_alt` · `help_text` / `help_text_alt` · `method_label` (free text: "Visual", "Functional", "Torque") · `reference_image_good` · `reference_image_bad` · `reaction_plan`

**Input** — `response_type` (§6 catalog) · `options` (table) · `unit` · `min_value` / `max_value` · `nominal_value` / `tolerance` · `pass_condition` · `decimals` · `default_value` · `computed_from` (for derived steps)

**Evidence** — `requires_photo` · `photo_policy` (Always / On Fail / On Pass / Never) · `min_photos` / `max_photos` · `photo_hint` · `requires_scan` · `scan_entity_type` / `scan_count` · `requires_signature`

**Governance** — `is_critical` · `is_mandatory` (default **off**) · `allow_skip` (default **on**) · `skip_reasons` · `expected_seconds` · `weight` (for scoring) · `is_active`

**Logic** — `visibility_conditions` (table) · `actions` (table)

#### `Process Step Option` (child) — §2, the outcome vocabulary
`value` · `label` / `label_alt` · `is_pass` · `is_critical` · `color` · `icon` · `sequence` · `requires_remark` · `requires_photo`

#### `Process Step Condition` (child) — branching without an expression language
`when_step` (Link → step_code) · `operator` (Equals / Not Equals / Greater / Less / Is Answered / Is Not Answered / Is Failed / Is Out Of Range) · `value` · `join` (AND / OR)

Deliberately **not** a formula field and never `eval()`. Conditions are structured rows evaluated by a small interpreter. This covers the realistic cases, stays safe, and — critically — stays editable by a non-programmer. A formula language would quietly re-introduce the "ask a developer" problem this whole engine exists to remove.

#### `Process Step Action` (child) — "the action, pass fail etc"
| Field | Values |
|---|---|
| `trigger` | On Answer · On Pass · On Fail · On Critical · On Skip · On Out Of Range · On Option (specific) · On Stage Complete · On Process Complete |
| `action_type` | Notify Role · Notify User · Raise Deviation · Block Stage · Quarantine Run · Set Run Status · Require Photo · Require Remark · Jump To Step · Set Field On Subject · Create Document · Send Email · Send SMS · Add Tag · **Run Server Script** |
| `params` | Small Text (JSON) — role, message template, target field, doctype |
| `severity` | Info / Minor / Major / Critical |
| `message_template` | Jinja over the run context |

`Run Server Script` is the escape hatch (§7). It calls Frappe's own sandboxed **Server Script** doctype — which is itself admin-editable, sandboxed, and permission-controlled. So the 10% that needs logic still does not need a deploy.

#### `Process Entity Type` — anything scannable
`entity_code` · `label` · `expected_count` · `qr_pattern` (named-group regex) · `date_format` · `duplicate_policy` (Warn / Block / Ignore) · `is_scan_enabled` · `icon`

Generalises the battery's component types. A battery module, a tyre, a filter, a fire extinguisher, a tool — same record shape.

### 5.2 Runtime layer

**`Process Run`** — `process_definition` + `definition_version` · `subject_doctype` + `subject_name` (Dynamic Link) · `run_identifier` · `status` · `current_stage` · `started_at/by` · `completed_at` · `station` / `shift` / `location` · `pass_count` / `fail_count` / `skip_count` / `critical_count` · `score_pct` · `is_first_pass` · `trace_completeness_pct` · children: `results`, `scans`, `photos`, `signoffs`, `deviations`

**`Process Run Result`** (child) — snapshots the step's label, type and criticality **at execution time**, so later template edits never rewrite history. Plus `response`, `value_numeric`, `value_text`, `is_pass`, `is_deviation`, `remark`, `skip_reason`, `answered_at/by`, `seconds_spent`, `entry_flag`.

**`Process Run Photo`** (child) — `file_url` · `captured_at` (device) · `server_received_at` · `captured_by` · `latitude` / `longitude` / `accuracy_m` · `location_source` · `geofence_status` · `is_stamped` · `exif_written`

**`Process Run Scan`** (child) — `entity_type` · `raw_payload` · parsed `serial_no` / `mfg_date` / `module_number` / `batch_ref` · `is_manual_entry` · `parse_failed` · `duplicate_of`

**`Process Run Signoff`** (child) — `stage` · `role` · `user` · `decision` · `signature` · `remarks` · `signed_at`

**`Process Deviation`** — the generic NCR: severity, description, disposition, root cause, corrective action, verification, status.

**`Process Engine Settings`** (Single) — defaults: brand, language, geofence radius, fast-entry threshold, photo quality, offline retention.

### 5.3 Roles

| Role | Can |
|---|---|
| **Process Author** | Create, edit, test and publish definitions |
| **Process Operator** | Run processes their roles allow |
| **Process Verifier** | Second-level sign-off, dispose deviations |
| **Process Viewer** | Read runs and reports |

Role assignment per process is itself configuration (`allowed_roles`), so a new process does not need new roles.

---

## 6. The response type catalog

Fifteen types. Composable into anything; extending the list is the one thing that needs development (§7).

| # | Type | Renders as | Covers |
|---|---|---|---|
| 1 | **Choice (single)** | Large option buttons | Pass/Fail, Pass/Fail/NA, three-tier, OK/Rework/Scrap |
| 2 | **Choice (multi)** | Multi-select chips | "Which panels are damaged?" |
| 3 | **Number** | Numeric keypad | Odometer, count |
| 4 | **Number in range** | Keypad + live band | Voltage > 52 V, IR > 2 MΩ |
| 5 | **Number with tolerance** | Keypad + gauge | Torque 10 Nm ±1 |
| 6 | **Computed** | Read-only result | Cell V max−min difference, auto |
| 7 | **Text (short)** | Single-line | Remarks |
| 8 | **Text (long)** | Multi-line | Observations |
| 9 | **Yes/No** | Toggle | "Bolt head marked?" |
| 10 | **Date / DateTime** | Picker | Manufacture date |
| 11 | **Photo only** | Camera | Nameplate photo, laptop screen |
| 12 | **Scan** | Continuous scanner | Any `Process Entity Type` |
| 13 | **Signature** | Signature pad | Operator / inspector |
| 14 | **Link** | Searchable dropdown | Pick a Part, User, Depot |
| 15 | **Section note** | Read-only instruction | Safety warnings, no answer needed |

Type 6 (**Computed**) is worth calling out — it is how "48 cell voltages: max / min / difference" collapses to two inputs and one auto-calculated result, and how a process gets a total score without code.

---

## 7. Zero-code vs. development — stated plainly

You asked for "development free for the future". Here is the honest boundary, so it is agreed up front rather than discovered later.

### ✅ Zero-code, forever — admin does it, nothing is deployed

- Create a whole new process end to end
- Add / remove / reorder / relabel stages and steps
- Change any step's response type, pass band, tolerance, units
- Author the outcome vocabulary (Pass/Fail, three-tier, anything)
- Turn photo / scan / signature requirements on and off, per step
- Set conditional visibility and branching
- Wire actions: notify, raise deviation, block, quarantine, set a field, create a document, send email/SMS
- Add scannable entity types and their QR patterns
- Translate every operator-facing string
- Attach good/bad reference images
- Assign roles, sign-off levels, second-approval requirements
- Add report brands (logo, name, signatory)
- Version, publish, retire, clone

### ⚠️ Needs development — and roughly what each costs

| Change | Why | Effort |
|---|---|---|
| A brand-new **response type** (draw-on-image, audio note, NFC tap) | Needs a renderer in the app | ~2–4 days each |
| A brand-new **action type** (push to SAP, call a REST API) | Unless a Server Script covers it — often it does | ~1–3 days |
| A new **report layout** (brand, fields and sections are config) | New Jinja print format | ~2–3 days |
| A **subject doctype that does not exist yet** | New doctype | Varies |
| Structural changes to the engine itself | — | — |

### 🔓 The escape hatch

The **Run Server Script** action calls Frappe's sandboxed Server Script doctype — itself editable in the browser, permission-gated, no `eval()` of user text in our code, no deploy. That covers most of the middle ground between "configuration" and "real development".

**Realistic expectation: ~90–95% of future processes are pure configuration.** Anything claiming 100% is selling something.

---

## 8. The app: one renderer, not N screens

### 8.1 What ships in Naarni Care

The Android app's navigation is a flat `NavHost` with a `Tab` enum ([MainShell.kt](android-app/app/src/main/java/com/naarni/service/ui/navigation/MainShell.kt)). Three additions, and then it never changes again:

```
Tab.Processes            → ProcessListScreen     — processes this user's roles allow
composable("process/{code}")  → ProcessStartScreen    — identify the subject
composable("run/{name}")      → ProcessRunnerScreen   — the generic renderer
```

`ProcessRunnerScreen` is driven entirely by the definition JSON. Inside it, `StepRenderer` is a single `when (step.response_type)` over the 15 catalog types. Everything else — photo stamping, scanning, offline queue, signature, sync — is shared infrastructure the app already has or gains once.

**Adding a process = zero app changes.** That is the contract.

Reused as-is: `PhotoStamper.kt` (geo/time/user burn-in), `LocationProvider`, CameraX, `SmartSelect` (searchable dropdowns), `Refreshable`, the notification and FCM path, `SessionManager`.

### 8.2 Version compatibility — the detail most teams miss

If an admin publishes a process using a step type an older installed app does not know, that app must not crash.

- App sends `supported_step_types` version on every definition fetch.
- Server marks unsupported steps and returns them anyway.
- Unknown types render as a read-only card: *"This step needs a newer app version"* with an update prompt — the rest of the process still runs.
- `Process Definition.min_app_step_types` lets an author see, at publish time, which app versions can run their process.

Without this, the first new step type breaks every phone in the plant on a Monday morning.

### 8.3 The operator experience is unchanged

Everything in the battery PRD's UI research still stands and now applies to *every* process: 72 dp targets for gloves, glyph + word + colour, section-at-a-time screens, tap-and-advance, fail-expands-in-place, no "mark all pass", camera-only geo-stamped photos, offline-first. The engine renders those patterns generically instead of one process hardcoding them.

---

## 9. Governance — what actually kills no-code platforms

Not the technology. **Sprawl.** Six months in, 40 half-finished processes, three near-duplicates of the same checklist, nobody sure which is current. Planning for this now is the difference between a platform and a mess.

| Control | Mechanism |
|---|---|
| **Authoring is a privilege** | `Process Author` role, separate from operators |
| **Draft → Test → Publish** | Test runs are flagged and excluded from all analytics |
| **Preview as operator** | Author sees the exact operator screens before publishing |
| **Immutable versions** | Published never mutates; edit forks a version |
| **Change log + diff** | What changed between v2 and v3, and who |
| **Clone from library** | New processes start from a working one, not blank |
| **Usage analytics** | Runs per process per month — dead processes get retired |
| **Retire, never delete** | History stays valid and reproducible |
| **Publish checklist** | Warns on: no steps, no sign-off role, unreachable conditional steps, orphaned actions, unsupported step types |

That last one is worth the build on its own. An unreachable step is the no-code equivalent of dead code, and only a linter will catch it.

---

## 10. Reports

Generic, brand-selectable, same as before but no longer battery-specific.

- **`Process Report Template`** — which sections appear (summary, per-step results, photos, scans, deviations, signatures), page size, whether photos are thumbnails or full-page.
- **`Report Brand Profile`** — display name, logo, address, footer, colour, certificate title, signatory. **No product-owner name in any code path.** Brand is a dropdown at generation, defaulting to whatever you set — Skyworth today, anything tomorrow.

Four generic outputs: **Run Certificate** (any process) · **Batch / Shift Summary** (FPY, failures by step) · **Where-Used / Traceability** (given a scanned serial, every run and subject it reached) · **Subject History** (every process ever run against this bus / pack / depot).

`Fleet Report Config` already does per-customer white-labelling for KM/SLA reports — worth deciding whether the two branding masters merge.

---

## 11. Migration path

| Wave | Process | Notes |
|---|---|---|
| 1 | **Battery Assembly QC** | Seeded as a definition: 2 stages, 39 steps, from your CSVs. Zero battery-specific code. |
| 2 | **PMS A/B/C/D** | Migrate `inspection_sheets.py` onto the engine and **delete the hardcoded Python**. Proves the engine on an existing, in-use process. |
| 3 | **Vehicle PDI**, **Driver daily check** | Pure configuration — authored by your team, not by developers. |

Wave 2 is the important one. It is the proof that this is a platform and not a second silo, and it retires code rather than adding it.

---

## 12. Phasing

| Phase | Scope | Est. |
|---|---|---|
| **P0** Schema | All definition + runtime doctypes, roles, versioning, immutability | 6–7 d |
| **P1** Config UX | Desk authoring, publish checklist, preview-as-operator, clone, diff | 5–6 d |
| **P2** Runtime API | `get_definition`, `start_run`, `save_result`, conditions interpreter, **action dispatcher**, scan parser | 6–7 d |
| **P3** App renderer | `ProcessListScreen`, `ProcessStartScreen`, `ProcessRunnerScreen`, all 15 step types, offline queue, version compatibility | 10–12 d |
| **P4** Sign-off & deviations | Verifier flow, NCR, quarantine, realtime alerts | 4–5 d |
| **P5** Reports | Report template engine, brand profiles, 4 outputs, background generation | 5–6 d |
| **P6** Seed & migrate | Battery QC seed, PMS migration, retire `inspection_sheets.py` | 4–5 d |
| **P7** Pilot | Battery line, one shift, side-by-side with paper | 5 d |

**~9–11 weeks.** P0–P3 is a working engine running battery QC.

Only new third-party dependency in the whole build: `com.google.mlkit:barcode-scanning`.

---

## 13. Open questions

Engine-level. The ten battery-specific questions in `BATTERY_QC_PRD.md` still stand.

1. **The word.** Confirm `stage_label = "Module"` for the battery process — your team sees "Module", schema says Stage. Or would you rather the schema say Module too, accepting the collision with Frappe's `Module Def` and the battery's physical modules?
2. **Who authors?** Which named people get `Process Author`? This is the highest-privilege role in the system — a bad publish reaches every phone.
3. **Approval to publish?** Should publishing a process need a second approver, like your KM report L1/L2 workflow? Recommended for anything safety-related.
4. **PMS migration timing** — wave 2 as scoped, or after the battery pilot proves out?
5. **Scoring.** Do you want weighted scores and a pass threshold per process (e.g. "≥ 90% and no critical fails = Pass"), or is pass/fail per step enough? Cheap to build now, awkward to retrofit.
6. **Multi-tenancy.** Will different customers ever need different versions of the same process? Changes whether definitions are global or customer-scoped. Cheap now, expensive later.
7. **Scheduling.** Should processes be *assignable* (this bus is due for PDI today) or purely on-demand? Affects whether a `Process Assignment` doctype is in P0.
8. **Offline authoring** — assume authors are always online in Desk? (Recommended: yes.)
