# PRD — Battery Assembly QC & Traceability Module

**Status:** Draft for approval — research + design only, no code written yet
**Date:** 2026-08-10
**Target app:** `vehicle_maintenance` (existing Frappe app), new module `battery_plant`
**Surfaces:** Android app (operator), Frappe Desk (admin config), Vue SPA (traceability + reports)

---

## 0. Stated assumptions

These shape the whole document. Flag any you disagree with and I will revise before building.

| # | Assumption | Why | If wrong |
|---|---|---|---|
| A1 | The **Android app is the operator surface**. It already ships CameraX, `PhotoStamper` (date-time + lat/long + user burn-in), `LocationProvider`, and an offline-tolerant Retrofit layer — every primitive this feature needs. | Cheapest path, and the plant floor is a phone/tablet environment, not a desk. | Vue SPA spec would replace §8; ~2 extra weeks. |
| A2 | **Frappe Desk is the admin config surface** — no custom admin UI is built. Steps, templates, components and brands are all editable as normal Desk documents. | You asked for "configurable from the dashboard". Desk *is* the dashboard, and it gives you versioning, audit trail, import/export and permissions for free. | A custom Vue config builder adds ~3 weeks. |
| A3 | Cell-level QR is captured **at module granularity, not per cell**. See §5.3 — 576 scans per bus is not a workable ask. | Physics of the shift clock. | Per-cell scanning is supported by the same model; it just becomes a slower configured option. |
| A4 | The module lives in the **existing `vehicle_maintenance` app** under a new Frappe module `battery_plant`. | One `bench migrate`, one deploy, one auth system, and batteries link to `Vehicle` for the 12-per-bus mapping. | A separate app doubles deploy surface. |
| A5 | Nothing in the product hardcodes a brand. Report branding is a picker at generation time, defaulting to whatever you set. | Your explicit requirement. | — |

---

## 1. Executive summary

Naarni's plant assembles EV traction battery packs. Each pack is **3 modules × 16 cells = 48 cells**, plus a slave BMS, cooling plate, upper case, bus bars, connector plate and MSD. **12 packs go into each bus.** Today, QC is two paper sheets — a 17-check *Before Installation* sheet and a 22-check *Installation* sheet — filled by hand and signed.

Paper has three failure modes this product removes:

1. **No evidence.** A tick in a box is not proof the check happened. Industry calls the failure mode *pencil whipping*; it is well documented that it is usually not fraud but a rational response to time pressure ([Facilio](https://facilio.com/blog/pencil-whipping/), [GoAudits](https://goaudits.com/blog/pencil-whipping-box-checking/)).
2. **No traceability.** When a pack fails in the field, there is no way to answer "which other packs share that module batch?" India's incoming **Battery Passport** rules make this a legal exposure, not just an ops one ([EVTech](https://evtech.news/battery-technology/india-introduces-battery-passport-for-ev-batteries-from-2026-qr-based-digital-identity-to-transform-safety-traceability-and-global-ev-trade.html)).
3. **Frozen process.** Changing a check means reprinting sheets and retraining. Nothing is versioned.

This PRD specifies a **configurable checklist engine** where every step — its response type, whether it wants a photo, whether it wants a QR scan, its pass band, its criticality — is an admin-editable record, not code. Operators run it as a **guided, section-at-a-time flow** designed against published shopfloor-UX evidence. Every photo is geo-stamped and camera-only. Every scanned component is stored with its serial, manufacturing date and module number, and **no scan is ever mandatory** — manual entry and skip-with-reason always exist.

Output is a signed, white-labelled **Battery QC Certificate** and a **pack genealogy report**, branded per a selectable profile (Skyworth today, anything tomorrow).

---

## 2. Research — how Indian automotive assembly & verification actually works

This section is the reason the UI in §8 looks the way it does. Designing a shopfloor tool from a desk produces software that gets abandoned in week three.

### 2.1 The human system on the line

**The three-role hierarchy is near-universal.** Indian automotive plants (and their tier-1 suppliers) run a consistent structure:

- **Operator / Associate** — usually ITI-trained (Industrial Training Institute diploma), sometimes contract staff. Does the physical work. Owns *self-inspection*: the checks that are part of doing the job right.
- **Line Inspector / QC Inspector** — checks the operator's work at defined gates. Independent reporting line to Quality, deliberately not to Production, so schedule pressure cannot override a reject.
- **Shift Supervisor / Line Leader** — owns the hour-by-hour plan, escalation, and the andon response.

The two checklists you supplied map onto this cleanly. *Before Installation* is an **incoming/self-inspection** gate — the operator confirming the parts they're about to build with are sound. *Installation* is a **process + final-verification** gate, which in most plants is at least partly inspector-signed. **The product must therefore support two-level sign-off**, and this codebase already has that exact pattern in the KM Report `Checker L1 → Checker L2` workflow ([seed_km_report_workflow.py](vehicle_maintenance/vehicle_maintenance/patches/v1_6/seed_km_report_workflow.py)) — we reuse the shape.

**Language is not optional.** Published field experience is blunt about it: a single Indian plant commonly has workers speaking **3–5 different languages**, and some operators — particularly older and contract staff — are fluent in WhatsApp but **have never installed an app from a store** ([Leap10x](https://www.leap10x.in/blogs/compliance-training-manufacturing-workers/)). Design consequences: a Hindi/English label on every step, sourced from a config field, not a translation file the plant can't edit; icons carrying meaning independent of text; and zero onboarding that assumes app-store literacy (side-load or MDM push, and a login the supervisor can do once).

**Time pressure is the design constraint.** Takt time governs everything. A QC flow that takes materially longer than the paper sheet will be defeated — operators will batch-fill it at the end of the shift, which reproduces pencil whipping with better timestamps. **Target: the digital flow must be no slower than paper.** This is the single most important non-functional requirement in this document, and §8 is engineered around it.

### 2.2 What IATF 16949 expects at the station

IATF 16949 is the automotive QMS standard and the practical minimum to supply any OEM in India; **500+ Indian businesses hold it** ([CertIndia](https://certindia.org/iatf-16949-certification-india/)). It layers automotive requirements on ISO 9001:2015 ([Wikipedia](https://en.wikipedia.org/wiki/IATF_16949)).

The one clause that most directly shapes this product: **the control plan must be present at the point of use.** Auditors verify this by walking to the workstation and asking the operator to show it ([iFactory](https://ifactoryapp.com/inspection-management/iatf-16949-audit-checklist)). A digital checklist that carries method, spec limits and reaction plan *on the step itself* satisfies this better than a laminated sheet — and it is why §6's step schema carries `inspection_method`, `min/max`, and a reaction path rather than just a label.

Related expectations we design for: records must be retrievable and attributable; non-conformances need a controlled disposition path (**NCR → quarantine → rework/scrap → verification**) rather than an eraser; and changes to the control plan must be **versioned**, which is why templates in §6 are immutable-on-publish.

### 2.3 What Indian EV battery regulation specifically requires

This is where the QR requirement stops being nice-to-have.

**AIS-156** mandates traceability **at pack level, cell level, BMS level and charger level**. These requirements — pack traceability, additional safety fuse, regen protection, cell-to-cell spacing, microprocessor-based BMS — landed in **Phase-1, effective 01-Dec-2022** ([EVreporter](https://evreporter.com/ais-156-standard-additional-safety-requirements-practitioners-perspective/), [ARAI guide](https://evreporter.com/battery-safety-standards-in-india-by-arai/)).

**Marking convention** in the industry matches your description exactly: the module cover carries a Data Matrix or QR code alongside alphanumerics — **module serial number, date of manufacture, batch reference** — and manufacture date is conventionally **DDMMYY** on every cell ([evXpertz](https://evxpertz.com/blogs/battery-pack-traceability-compliance-india)). Our QR parser (§5.2) is configured for exactly this shape, and is admin-editable because vendors vary.

**Battery Passport (from 2026).** The proposed CPCB framework assigns every EV battery manufactured, imported, sold or deployed in India a **unique 21-character alphanumeric ID plus a QR code**, with a digital passport accessible by mobile scan ([EVTech](https://evtech.news/battery-technology/india-introduces-battery-passport-for-ev-batteries-from-2026-qr-based-digital-identity-to-transform-safety-traceability-and-global-ev-trade.html)). **The `Battery Assembly` record designed here is the natural source of truth for that passport** — it already holds the pack ID, component genealogy, manufacture date, and test results. We reserve a `passport_id` field now so the schema does not need migrating later.

The underlying principle from battery-industry traceability practice: a mark applied at the start of production becomes **the thread that connects every subsequent test result and process parameter to that individual component** ([Pryor](https://www.pryormarking.com/applications/ev-battery-marking/), [Keyence](https://www.keyence.com/products/marker/laser-marker/resources/laser-marking-resources/electrode-traceability-for-battery-quality-control.jsp)).

### 2.4 Why catching it early is the commercial case

The economics of QC gating are well quantified: **a line at 120% of rated cycle time with 94% first-pass yield out-earns a line at 100% cycle time with 88% FPY**, because by the time a defect is caught at final QC the part has already absorbed labour, energy, materials and machine time — and if it propagated upstream, an entire batch may need rework or scrap ([JR Automation](https://www.jrautomation.com/blog/the-last-millimeter-problem-why-battery-module-and-pack-assembly-reliability-starts-with-precision)).

This is precisely the argument for the *Before Installation* gate. Catching a bent cooling plate or a <52 V module **before** it is torqued into a pack saves the whole pack's assembly labour. So the product must make **FPY, gate-level reject rate, and top failing checks** first-class metrics (§10), not an afterthought — that is how you prove the module paid for itself.

Genealogy is the containment tool: when a batch goes bad, **genealogy queries identify exactly what to hold, rework or scrap** ([SG Systems](https://sgsystemsglobal.com/glossary/nonconformance-report-ncr/)). Without component-to-pack linkage you quarantine everything; with it you quarantine nine packs.

### 2.5 Pencil whipping — designing against it, gently

The literature converges on the same countermeasures: **right-sized checklists, time-stamped evidence, mandatory photo/video on completion, and a culture that rewards reporting problems** ([Facilio](https://facilio.com/blog/pencil-whipping/), [UpKeep](https://upkeep.com/learning/pencil-whipping/), [Redlist](https://www.getredlist.com/hazards-of-the-pencil-whip/)). And critically: it is **usually not fraud — it emerges as a rational response to impossible workloads and blame cultures** ([GoAudits](https://goaudits.com/blog/pencil-whipping-box-checking/)).

That last finding sets the tone. We build **friction where it buys evidence and nowhere else**:

| Countermeasure | Included? | Rationale |
|---|---|---|
| No "Mark all as Pass" button | ✅ Yes | Single highest-leverage change. Costs the honest operator ~15 taps. |
| Per-step timestamps | ✅ Yes | Free. Enables the "fast entry" advisory below. |
| Camera-only photos (no gallery picker) | ✅ Yes | A gallery picker makes photo evidence worthless. |
| Geo + time + user burn-in on every photo | ✅ Yes | Already built (`PhotoStamper`). |
| "Fast entry" flag on the report | ⚠️ Advisory only | Section completed implausibly fast is **noted on the report**, never blocked. |
| Optional plant geofence | ⚠️ Warn only | Warns if capture is far from the plant. Never blocks — GPS indoors is unreliable. |
| Hard block on missing photo | ❌ No | Per your standing guidance: required photos stay optional with a manual fallback. Skipping records a reason and surfaces it on the report. |

**Design stance: the system makes the truth easy to record and dishonesty visible in the report — it does not police the operator at the point of work.** A blocking gate on a plant floor at 6 pm gets solved with a second phone.

---

## 3. Goals & non-goals

### 3.1 Goals

| ID | Goal | Success measure |
|---|---|---|
| G1 | Every pack passes through a recorded QC process | 100% of packs shipped have a submitted `Battery Assembly` |
| G2 | Every check step is admin-configurable without code | Adding a step and changing its response type is a Desk edit, deployed by nobody |
| G3 | Photo evidence is geo/time/user-attributable | 100% of captured photos carry burn-in + stored lat/long/accuracy |
| G4 | Component genealogy from pack → module → BMS | "Which packs contain module batch X" answerable in one query |
| G5 | Digital is not slower than paper | Median *Before Installation* completion ≤ paper baseline (measure paper first) |
| G6 | Reports carry a selectable brand, never a hardcoded one | Brand picker at generation; zero brand strings in code |
| G7 | QR is never a blocker | Every scan step has manual-entry and skip paths |

### 3.2 Non-goals (this phase)

- Automated instrument integration (torque wrench telemetry, diagnosis-laptop file import). Values are keyed in; the schema is built to accept machine input later.
- Cell-level individual scanning as the default (see §5.3).
- CPCB Battery Passport submission API — we reserve the field, we don't integrate.
- Warranty/RMA claim processing.
- Replacing the existing PMS inspection sheets (`inspection_sheets.py`) — that stays; this is a separate, plant-side flow.
- Frappe Desk theming / custom admin builder.

---

## 4. Domain model & the numbers

```
Bus (Vehicle)
└── 12 × Battery Pack            ← positions 1–12
    ├── 3 × Module               ← QR: serial + mfg date + module no.
    │   └── 16 × Cell            ← 48 cells per pack (see §5.3)
    ├── 1 × Slave BMS            ← QR
    ├── 1 × Cooling Plate (bottom, with TIM pad)
    ├── 1 × Upper Case + inner insulation + name plate
    ├── Bus bars (+ / −) + Connector plate + MSD base
    └── Heater + wiring
```

**Per bus:** 12 packs · 36 modules · 12 slave BMS · **576 cells**.

That 576 is the number that drives assumption A3. At a realistic 4–6 seconds per successful scan, per-cell scanning is **~50 minutes of pure scanning per bus** — before any checks. It will not survive contact with a shift. §5.3 gives the alternative.

**Nominal specs extracted from your sheets** (all become configurable step parameters, not constants):
- Module voltage: **> 52 V** each, ×3
- Insulation resistance: **> 2 MΩ** (the *Before Installation* sheet's remark says "> 2 ohm" — almost certainly a typo for 2 MΩ; flagged in §14)
- Torque: module mount **10 Nm ±1**, slave BMS mount **10 Nm ±1**, bus bar **15 Nm and 10 Nm**, top cover→cooling plate **6 Nm**, top cover→connector plate **6 Nm**
- Diagnosis laptop: 48 cell voltages (max/min/difference), **9 temperature sensors** (max/min/difference), fault codes, BMU number match

---

## 5. QR / traceability design

### 5.1 Principle: never mandatory

Explicit requirement, honoured throughout. Every scan point offers three paths:

1. **Scan** — camera, continuous mode, haptic + tone on decode
2. **Type it** — manual serial entry, flagged `is_manual_entry = 1`
3. **Skip** — records a reason from a canned dropdown; appears in the report's completeness column

A pack with zero scans still completes and still gets a certificate. The certificate simply shows a lower traceability completeness score. **Visibility, not enforcement.**

### 5.2 Configurable QR parsing

QR payloads vary by vendor and change without notice. Parsing therefore lives in config, not code.

`Battery Component Type` carries a `qr_pattern` — a Python regex with **named groups**. The parser tries each active pattern for that component type in priority order; the first match wins; on no match, the **raw payload is stored verbatim** and the row is flagged `parse_failed` for an admin to fix by adding a pattern. Nothing is ever lost or rejected.

```
Recognised named groups → stored fields
  (?P<serial>…)   → serial_no
  (?P<mfg>…)      → mfg_date        (DDMMYY per industry convention; format configurable)
  (?P<module>…)   → module_number
  (?P<batch>…)    → batch_ref
  (?P<rev>…)      → revision
```

Example pattern for a module label:
```
^(?P<serial>[A-Z0-9]{12})-(?P<mfg>\d{6})-M(?P<module>[123])$
```

Admin can test a pattern against a sample payload from within the Desk form before saving. Duplicate-serial detection is a **warning by default** (`duplicate_policy`: Warn / Block / Ignore, configurable) — a legitimate rework re-scan must not be blocked.

### 5.3 The cell-scanning recommendation

**Recommended default: scan at module level (3 per pack). Do not scan 48 cells individually.**

Rationale, and why traceability does not actually suffer:

- **The cell→module link already exists upstream.** Cells are married to a module at the module supplier/line. Recording *which 3 modules* went into a pack, plus each module's own cell manifest, gives full pack→cell genealogy transitively. Re-scanning 48 cells at pack assembly re-records information that is already known.
- **Installation step 21 already reads all 48 cell voltages** from the diagnosis laptop. That is per-cell *evidence* — arguably more valuable than a per-cell *identity* scan, and it is one screen instead of 48.
- **Cost/benefit.** 576 scans per bus vs 36. The 540 extra scans buy a link that is already derivable.

**But it stays configurable.** `Battery Component Type` has `expected_count_per_pack`; set Cell to 48 and the flow will ask for 48 scans. The decision is yours to change at any time from the Desk, with no code change. We simply do not ship it as the default.

A middle option worth considering: capture the **module's cell-batch reference** (one extra field on the module scan) — gives batch-level cell containment for one extra scan field instead of 48 scans.

---

## 6. The configurable checklist engine

This is the core of the ask. Everything an admin can change lives in DocTypes.

> **Note on the existing pattern:** the current PMS checklists are hardcoded Python lists in [inspection_sheets.py](vehicle_maintenance/vehicle_maintenance/fleet_service/inspection_sheets.py). That is exactly the pattern this module must *not* repeat. Battery QC is fully data-driven from day one.

### 6.1 Configuration DocTypes (admin-editable)

#### `Battery Model` — what a pack *is*
| Field | Type | Notes |
|---|---|---|
| `model_code` | Data (unique) | e.g. `SKY-48C-3M` |
| `display_name` | Data | Shown to operators |
| `module_count` | Int | 3 |
| `cells_per_module` | Int | 16 |
| `total_cells` | Int (read-only) | Computed = 48 |
| `nominal_voltage` | Float | Pack nominal |
| `oem` | Link → OEM | Reuses existing doctype |
| `serial_format` | Data | Optional validation regex for pack serials |
| `active_template` | Link → Battery QC Template | Auto-loaded when a pack of this model starts |

#### `Battery Component Type` — what gets scanned
| Field | Type | Notes |
|---|---|---|
| `component_code` | Data (unique) | `MODULE`, `SLAVE_BMS`, `COOLING_PLATE`, `UPPER_CASE`, `BUS_BAR`, `MSD`, `CELL` |
| `label` / `label_hi` | Data | Bilingual |
| `expected_count_per_pack` | Int | Module=3, Slave BMS=1, Cell=48-if-enabled |
| `qr_pattern` | Small Text | Named-group regex (§5.2) |
| `mfg_date_format` | Data | Default `DDMMYY` |
| `duplicate_policy` | Select | Warn (default) / Block / Ignore |
| `is_scan_enabled` | Check | Turn cell scanning on/off with one checkbox |
| `icon` | Data | For the operator UI chip |

#### `Battery QC Template` — a versioned control plan
| Field | Type | Notes |
|---|---|---|
| `template_name` | Data | "Skyworth 48-cell pack QC v3" |
| `battery_model` | Link → Battery Model | |
| `version` | Int (read-only) | Auto-increments on publish |
| `status` | Select | Draft / Published / Retired |
| `effective_from` | Date | |
| `stages` | Table → Battery QC Stage | Ordered |
| `steps` | Table → Battery QC Step | The checks |

**Versioning rule:** a Published template is **immutable**. Editing creates `version + 1` in Draft. Every `Battery Assembly` stores `template` **and** `template_version`, so a pack built in March reproduces exactly the March control plan — an IATF expectation (§2.2) and a prerequisite for any credible audit.

#### `Battery QC Stage` (child) — the gates
| Field | Type | Notes |
|---|---|---|
| `stage_code` | Data | `BEFORE_INSTALL`, `INSTALL` |
| `stage_label` / `_hi` | Data | "Before Installation" |
| `sequence` | Int | Ordering |
| `requires_signoff_role` | Link → Role | Blank = operator self-inspection |
| `requires_second_signoff` | Check | Turns on the L1→L2 pattern |
| `blocks_next_stage_on_fail` | Check | Whether a critical fail here gates the next stage |

Configurable stages mean you can add *Pre-Dispatch*, *Rework Verification*, or *Bus Installation* later without touching code.

#### `Battery QC Step` (child) — **the configurable step you asked for**

Every column below is an admin control. This table is the heart of the PRD.

| Field | Type | Purpose |
|---|---|---|
| `step_no` | Int | Display number (survives reordering) |
| `section` / `section_hi` | Data | "Bottom Cooling Plate", "Module", … — drives the wizard grouping |
| `check_parameter` | Small Text | The instruction, verbatim from the sheet |
| `check_parameter_hi` | Small Text | Hindi rendering |
| `inspection_method` | Select | Visual / Functional / Torque / Measurement / Document |
| **`response_type`** | Select | **Pass-Fail** · **Pass-Fail-NA** · **Measurement** · **Torque** · **Select** · **Text** · **Photo Only** · **QR Scan** · **Checkbox** |
| `select_options` | Small Text | Newline options when `response_type = Select` |
| `unit` | Data | V, MΩ, Nm, bar |
| `min_value` / `max_value` | Float | Pass band (module > 52 V → min 52) |
| `nominal_value` / `tolerance` | Float | Torque 10 ±1 → nominal 10, tolerance 1 |
| `pass_condition` | Select | Within Range / Greater Than / Less Than / Equals |
| **`requires_photo`** | Check | Ask for a photo on this step |
| `min_photos` / `max_photos` | Int | Default 1 / 3 |
| `photo_hint` / `_hi` | Data | "Photo of the weld joint, close up" |
| `photo_required_on` | Select | Always / On Fail Only / Never — *On Fail Only* is the sweet spot for speed |
| **`requires_qr`** | Check | Ask for a scan on this step |
| `qr_component_type` | Link → Battery Component Type | |
| `qr_scan_count` | Int | How many to scan here |
| `is_critical` | Check | Fail ⇒ quarantine + supervisor alert |
| `is_mandatory` | Check | Must be answered to submit (default **off** — see §2.5) |
| `allow_skip_with_reason` | Check | Default **on** |
| `skip_reasons` | Small Text | Canned dropdown, per your no-free-text guidance |
| `reference_image` | Attach Image | "What good looks like" — highest-value field in this table |
| `reference_image_bad` | Attach Image | "What bad looks like" |
| `help_text` / `_hi` | Text | Expandable method detail |
| `reaction_plan` | Small Text | What to do on fail (IATF control-plan requirement) |
| `expected_seconds` | Int | Baseline for the "fast entry" advisory |
| `is_active` | Check | Retire a step without deleting history |

**Everything you listed — image, QR, pass/fail — plus the pass bands, criticality, bilingual labels, reference photos and reaction plan, all editable per step from the Desk.**

Seeding: a patch loads both supplied sheets as `Battery QC Template v1` so the plant starts on day one with the exact checks it uses today. From then on it is theirs to edit.

### 6.2 Transaction DocTypes (created by the flow)

#### `Battery Assembly` — one per physical pack (submittable)
Identity (`battery_serial_no`, `battery_pack_number`, `battery_model`, `passport_id` reserved) · Provenance (`template`, `template_version`, `plant`, `station`, `line`, `shift`, `assembled_by`, `started_at`, `completed_at`) · Outcome (`workflow_state`, `final_status`, `is_first_pass`, `pass_count`, `fail_count`, `skip_count`, `critical_fail_count`, `traceability_completeness_pct`) · Measurements (`pack_voltage`, `insulation_resistance`, `cell_v_max/min/diff`, `temp_max/min/diff`, `fault_codes`) · Destination (`vehicle`, `position_in_bus` 1–12, `installed_on`) · Children: `results`, `component_scans`, `deviations`.

**Naming:** `BAT-.YYYY.-.#####` when the pack serial isn't scanned; otherwise the scanned serial, subject to `Battery Model.serial_format`.

#### `Battery QC Result` (child) — one row per step executed
Snapshots `step_no`, `section`, `check_parameter`, `response_type`, `is_critical` **at execution time** (so later template edits never rewrite history), plus `response`, `value_numeric`, `value_text`, `is_deviation`, `remark`, `skip_reason`, `answered_at`, `answered_by`, `seconds_spent`, `photos` (child), `entry_flag` (Normal / Fast Entry / Late Entry).

#### `Battery QC Photo` (child) — the geo-tagged evidence
`file_url`, `thumbnail_url`, `captured_at` (device), `server_received_at`, `captured_by`, `latitude`, `longitude`, `accuracy_m`, `location_source` (GPS / Network / Unavailable), `geofence_status` (Inside / Outside / Unknown), `is_stamped`, `exif_written`, `caption`.

#### `Battery Component Scan` (child) — genealogy
`component_type`, `raw_payload`, `serial_no`, `mfg_date`, `module_number`, `batch_ref`, `position_index`, `scanned_at`, `scanned_by`, `latitude`, `longitude`, `is_manual_entry`, `parse_failed`, `duplicate_of`.

#### `Battery Deviation` — the NCR
`battery_assembly`, `step_no`, `severity` (Minor / Major / Critical), `description`, `raised_by/at`, `disposition` (Rework / Use-As-Is / Scrap / Return to Vendor), `disposition_by/at`, `root_cause`, `corrective_action`, `verified_by/at`, `status` (Open / In Rework / Verified / Closed), `photos`.

#### `Bus Battery Installation` — the 12-per-bus map
`vehicle`, `installed_on`, child rows of `position` (1–12) → `battery_assembly` → `installed_by`. Validates all 12 positions filled and no pack installed on two buses. **Rule: only a `QC Passed` pack can be installed** — configurable, warn-by-default.

#### `Battery Plant Settings` (Single) — global switches
Default plant/geofence radius, default brand profile, default language, fast-entry threshold %, whether QC-pass gates installation, photo compression quality, offline retention days.

#### `Report Brand Profile` — §11
`brand_code`, `display_name`, `logo`, `address`, `footer_text`, `primary_color`, `certificate_title`, `signatory_name`, `signatory_designation`, `is_default`.

### 6.3 Roles

| Role | Can |
|---|---|
| **Battery Operator** | Create/execute assemblies, capture photos & scans, submit for QC |
| **Battery QC Inspector** | Everything above + verify/approve, raise & dispose deviations |
| **Battery Plant Manager** | + edit templates, publish versions, manage brands, view all reports |
| *System Manager* | Full |

Existing roles (`Central Ops`, `Depot Manager`) get read access so the fleet side can see what's installed on a bus.

### 6.4 Workflow

```
Draft ──start──▶ In Progress ──submit──▶ Awaiting QC ──approve──▶ QC Passed ──install──▶ Installed
                     │                        │
                     │                        └──reject──▶ Quarantined ──rework──▶ In Rework ──▶ Awaiting QC
                     └── critical fail ──────────────────▶ Quarantined
```

Exported as a Frappe Workflow fixture, per the repo's fixture rules. `requires_second_signoff` on a stage inserts an `Awaiting QC L2` state, reusing the KM Checker L1/L2 shape.

---

## 7. Geo-tagged photo pipeline

Largely **already built** — [`PhotoStamper.kt`](android-app/app/src/main/java/com/naarni/service/core/camera/PhotoStamper.kt) burns date-time, lat/long (± accuracy) and the capturing user into the bottom-left, and [`api/images.py`](vehicle_maintenance/vehicle_maintenance/api/images.py) already persists lat/long alongside the file. We extend rather than rebuild.

```
1. Operator taps "Take photo" on a step
2. CameraX opens — camera only, no gallery picker
3. LocationProvider supplies the freshest fix (last-known if the live fix is slow;
   never blocks the shutter)
4. PhotoStamper burns:  📷 <step label>
                        10 Aug 2026, 14:32:07 IST
                        28.612894, 77.229446  (±8 m)
                        By: Ramesh Kumar (Battery Operator)
5. EXIF GPS tags written (machine-readable; the burn-in is human-readable)
6. Downscale to configurable max edge (default 1600 px, ~85% JPEG)
7. Queue locally → upload via Frappe upload_file → attach with lat/long/accuracy
8. Geofence evaluated server-side: Inside / Outside / Unknown — recorded, never blocking
```

**Deliberate choices:** the shutter never waits on GPS (indoor plant GPS is slow and often unavailable — `location_source = Unavailable` is a valid, recorded outcome); both burn-in *and* EXIF, because burn-in survives screenshots and EXIF survives resizing; no gallery import, ever.

---

## 8. UI/UX specification

### 8.1 Research-derived principles

Published shopfloor-UX guidance is unusually specific, and it contradicts consumer mobile design in ways that matter:

| Principle | Evidence | Applied here |
|---|---|---|
| **Touch targets ≥ 64 px** | Gloved operators cannot hit the 44 px consumer floor; industrial interfaces use **60 px+**, with **64–72 px** cited as the practical band ([Glyphic](https://glyphic.design/work/shopfloor-ux/), [Aufait](https://www.aufaitux.com/blog/manufacturing-ux-design/)) | Pass/Fail buttons **72 dp** tall, 12 dp gaps |
| **Never colour alone** | Use colour, icon **and** text ([OEE Intellisuite](https://oeeintellisuite.com/blog/ui-ui-ux-design-in-manufacturing/)) | ✓ PASS / ✕ FAIL / — N/A: each has glyph + word + colour |
| **No hover, no sliders** | Panels are touch-only; precise cursor work is unreliable, and vibration defeats sliders — use **stepped/discrete inputs** ([Glyphic](https://glyphic.design/work/shopfloor-ux/)) | Zero hover states; numeric keypad, not sliders |
| **High contrast, anti-glare** | Operators wear PPE restricting peripheral vision; contrast must be validated in real plant luminance ([Emixa](https://www.emixa.com/blog/ux-in-manufacturing-design-for-people-not-just-for-machines), [Medium](https://medium.com/@sihambouguern/ux-in-manufacturing-designing-software-that-works-on-the-factory-floor-86ba9f1e0afc)) | ≥7:1 body text; high-contrast mode in settings |
| **Interlocked states** | High-risk commands stay disabled until conditions are safe ([Glyphic](https://glyphic.design/work/shopfloor-ux/)) | *Submit* stays disabled until required responses exist — the one place we do gate |
| **Right-sized checklists** | Oversized checklists cause pencil whipping ([Facilio](https://facilio.com/blog/pencil-whipping/)) | Section-at-a-time, 2–5 checks per screen |
| **Design for the environment** | Noise, gloves, split attention ([UXmatters](https://www.uxmatters.com/mt/archives/2017/08/ux-for-the-industrial-environment-part-1.php)) | Haptics + tone on scan; works one-thumb |

### 8.2 The structural insight

**Your paper form already contains the ideal wizard structure.** Its own section groupings are 2–5 checks each — exactly the "right-sized" chunk the anti-pencil-whipping research prescribes:

| *Before Installation* — 17 checks | | *Installation* — 22 checks | |
|---|---|---|---|
| Bottom Cooling Plate | 5 | Module & Cooling Plate Installation | 5 |
| Upper Case | 3 | Slave BMS, Bus Bar & Insulation Sheet | 5 |
| Module | 3 | Top Cover & Labeling | 4 |
| Bus Bar & Connector Plate | 4 | Voltage & Resistance | 2 |
| Slave BMS | 2 | Air Leak Testing | 2 |
| | | Diagnosis Check | 4 |

So: **one section = one screen.** 5 screens for *Before Installation*, 6 for *Installation*. Not 39 screens (too slow), not 1 giant scroll (invites pencil whipping). This maps to the operator's physical workflow — they are already standing at the cooling plate when they answer the cooling-plate questions.

> ⚠️ **Data note:** the *Installation* sheet numbers 1–23 but **has no #18** — it jumps 17 → 19. So it is 22 checks, not 23. Confirm whether a check was dropped in editing before we seed.

### 8.3 Screen flow

```
┌─ 1 IDENTIFY ─┐  ┌─ 2 STAGE ─┐  ┌─ 3 RUN ────────┐  ┌─ 4 SCANS ─┐  ┌─ 5 REVIEW ─┐  ┌─ 6 QC ────┐
│ Scan pack QR │─▶│ Before /  │─▶│ Section 1 of 5 │─▶│ Components│─▶│ Summary +  │─▶│ Inspector │
│ or type it   │  │ Install   │  │ … Section 5    │  │ (skippable)│  │ sign-off   │  │ verify    │
└──────────────┘  └───────────┘  └────────────────┘  └───────────┘  └────────────┘  └───────────┘
```

### 8.4 Screen 1 — Identify the pack

```
╔══════════════════════════════════════════╗
║  ←        New Battery QC          EN|हिं ║
╠══════════════════════════════════════════╣
║                                          ║
║        ┌──────────────────────┐          ║
║        │                      │          ║
║        │    [ ⛶  QR frame ]   │          ║   Camera live on entry —
║        │                      │          ║   no tap needed to start
║        └──────────────────────┘          ║
║      Point at the pack's QR label        ║
║      पैक के QR लेबल पर कैमरा रखें            ║
║                                          ║
║   ────────────── or ──────────────       ║
║                                          ║
║   ┌────────────────────────────────┐     ║
║   │  ⌨   Type serial number        │     ║   72dp — always available,
║   └────────────────────────────────┘     ║   QR is never mandatory
║                                          ║
║   Recent:  SKY-2408-0417 · SKY-2408-0416 ║
╚══════════════════════════════════════════╝
```

On decode: model auto-resolves → template + version auto-load → shift/station/operator auto-fill from session and settings. **Zero typing in the happy path.**

### 8.5 Screen 2 — Stage picker

```
╔══════════════════════════════════════════╗
║  ←   SKY-2408-0417 · Pack #A-3           ║
║      Skyworth 48-cell (3×16)             ║
╠══════════════════════════════════════════╣
║  ┌────────────────────────────────────┐  ║
║  │ ✓  BEFORE INSTALLATION             │  ║
║  │    17 checks · Done 14:02           │  ║   Completed = green,
║  │    Ramesh Kumar · 16 ✓  1 ✕         │  ║   tappable to review
║  └────────────────────────────────────┘  ║
║  ┌────────────────────────────────────┐  ║
║  │ ▶  INSTALLATION            [START] │  ║   Next action = filled,
║  │    22 checks · ~12 min              │  ║   primary colour, 72dp
║  └────────────────────────────────────┘  ║
║  ┌────────────────────────────────────┐  ║
║  │ 🔒 QC VERIFICATION                  │  ║   Locked states shown,
║  │    Needs QC Inspector               │  ║   not hidden — sets
║  └────────────────────────────────────┘  ║   expectations
╚══════════════════════════════════════════╝
```

### 8.6 Screen 3 — The check runner *(the screen that decides adoption)*

```
╔══════════════════════════════════════════╗
║  ←  Before Installation      1/5   ⋮     ║
║  ▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░  20%         ║   Sticky progress
║  BOTTOM COOLING PLATE                    ║   Sticky section name
╠══════════════════════════════════════════╣
║ ┌──────────────────────────────────────┐ ║
║ │ 1   Check cooling plate welding      │ ║   18sp, high contrast
║ │     quality and joints                │ ║
║ │     वेल्डिंग गुणवत्ता और जोड़ जाँचें         │ ║   Hindi, from config
║ │     ◉ Visual                    ⓘ    │ ║   method chip + help
║ │                                       │ ║
║ │  ┌─────────┐┌─────────┐┌──────────┐  │ ║
║ │  │    ✓    ││    ✕    ││    —     │  │ ║   72dp tall
║ │  │  PASS   ││  FAIL   ││   N/A    │  │ ║   glyph + word + colour
║ │  └─────────┘└─────────┘└──────────┘  │ ║
║ │                                       │ ║
║ │  🖼 What good looks like        →     │ ║   reference_image
║ └──────────────────────────────────────┘ ║
║ ┌──────────────────────────────────────┐ ║
║ │ 2   Check the cooling plate for any  │ ║   Next check, same card.
║ │     damage or bending          ✓ ✕ — │ ║   2–5 per screen.
║ └──────────────────────────────────────┘ ║
║          … 3 more in this section        ║
╠══════════════════════════════════════════╣
║  [  NEXT SECTION  →  ]   3 of 5 answered ║   Sticky footer,
╚══════════════════════════════════════════╝   honest count
```

**Interaction rules**
- Tapping PASS **auto-scrolls to the next unanswered check** — the flow reads as one continuous motion. This is what keeps digital at paper speed.
- **No "mark all pass".** Deliberate (§2.5).
- Tapping FAIL **expands in place**: severity chips → canned reason dropdown → optional note → photo prompt. Never a modal; modals lose context and get dismissed.
- `is_critical` + FAIL → amber banner *"This pack will be quarantined for QC review"* + realtime supervisor notification via the existing `publish_realtime` + FCM path.
- Long-press a check → full help text, reaction plan, and good/bad reference images side by side.

**Response-type renderings**

*Torque (10 Nm ±1):*
```
┌──────────────────────────────────────┐
│ 4  Module mounting bolt torque       │
│    Target  10 Nm ± 1     (9.0–11.0)  │
│    ┌──────────────────────────────┐  │
│    │           10.4  Nm           │  │  ← live green ring in band,
│    └──────────────────────────────┘  │     red out of band
│    [7][8][9]  ✓ Within specification │
│    [4][5][6]                          │  ← numeric keypad, no slider
│    [1][2][3]  ☑ Bolt head marked      │  ← the sheet's own requirement
│    [ 0 ][.][⌫]                        │     as a discrete checkbox
└──────────────────────────────────────┘
```

*Measurement (module voltage > 52 V, ×3):* three inline numeric fields with a live band indicator; the pack min/max/difference computes itself.

*Diagnosis check (48 cell voltages):* max / min / **auto-computed difference**, plus a `Photo Only` step for the laptop-screen capture. One screen, not 48 fields.

### 8.7 Screen 4 — Component scans

```
╔══════════════════════════════════════════╗
║  ←   Component Scanning       Skip all ▸ ║   Skip is a first-class
╠══════════════════════════════════════════╣   action, top-right
║        ┌──────────────────────┐          ║
║        │    [ ⛶ live QR ]     │          ║   Continuous mode:
║        └──────────────────────┘          ║   camera stays open,
║   Scanned 3 of 5 · keep scanning         ║   haptic + tone each hit
║                                          ║
║   MODULES                          2/3   ║
║   ✓ SKY-M-88213401 · 12 Aug 26 · Mod 1  ║   Parsed fields shown so
║   ✓ SKY-M-88213402 · 12 Aug 26 · Mod 2  ║   the operator can eyeball
║   ○ Module 3          [Scan] [Type] [—] ║   them — three paths always
║                                          ║
║   SLAVE BMS                        1/1   ║
║   ✓ SKY-B-4471029 · 09 Aug 26           ║
║                                          ║
║   ⓘ Cells are traced via their module.   ║   Explains A3 to the operator
║                                          ║
║   [        CONTINUE  →        ]          ║   Never disabled
╚══════════════════════════════════════════╝
```

### 8.8 Screen 5 — Review & sign

Failures first (tap to jump back), then passes collapsed, then completeness. Signature pad, operator auto-filled from session. Submit is the **one** interlocked control — disabled only while a step marked `is_mandatory` is unanswered, with the blocker named.

```
╔══════════════════════════════════════════╗
║  ←   Review & Submit                     ║
╠══════════════════════════════════════════╣
║   ┌────────┬────────┬────────┬────────┐  ║
║   │  ✓ 20  │  ✕ 1   │  — 1   │ 📷 6   │  ║
║   │ passed │ failed │  n/a   │ photos │  ║
║   └────────┴────────┴────────┴────────┘  ║
║                                          ║
║   ⚠ NEEDS ATTENTION                      ║
║   ┌────────────────────────────────────┐ ║
║   │ ✕ 10  Module 2 voltage 51.2 V      │ ║
║   │       Below 52 V minimum   CRITICAL│ ║
║   │       📷 1 photo         [ Review ]│ ║
║   └────────────────────────────────────┘ ║
║                                          ║
║   Traceability   ▓▓▓▓▓▓▓▓░░  80%  (4/5)  ║
║                                          ║
║   Performed by:  Ramesh Kumar            ║
║   ┌────────────────────────────────────┐ ║
║   │        ✍  Sign here                │ ║
║   └────────────────────────────────────┘ ║
║   [     SUBMIT FOR QC REVIEW      ]      ║
╚══════════════════════════════════════════╝
```

### 8.9 Screen 6 — QC Inspector verification

Same runner in **review mode**: operator's answer and photo shown per check, inspector supplies Agree / Disagree. Disagreement opens a `Battery Deviation`. Approve → `QC Passed`; Reject → `Quarantined`.

### 8.10 Frappe Desk (admin) — no custom UI needed

`Battery QC Template` renders as a normal Desk form: stages grid, steps grid, drag to reorder, duplicate a step, attach reference images, Publish button. A **Preview** button renders the operator view read-only so an admin can see their change as the operator will. That is the whole admin build.

### 8.11 Vue SPA (fleet side)

Read-only: `Bus → 12 battery positions`, tap a position → pack certificate and full genealogy. Plus the report generator (§11). Reuses existing `Wizard.vue` / `StatusBadge.vue` patterns.

### 8.12 Accessibility & environment

Minimum 16sp body / 18sp check text · WCAG AA+ (≥7:1 for check text) · one-thumb reachable primary actions · haptic on every state change (gloves + noise mean visual-only feedback gets missed) · optional high-contrast mode · **portrait only** (phones live in one hand on a line).

---

## 9. Offline-first

Plant floors have dead zones; the flow must never stall.

- Entire checklist run is **local-first** (Room DB). Network is needed only to fetch the template and to sync.
- Templates cached on login and refreshed by version; a cached template runs indefinitely.
- Photos queue on disk with their metadata; upload on reconnect with exponential backoff.
- Sync state is visible per pack: `● Synced` / `◐ Pending (3 photos)` / `⚠ Failed — retry`.
- Idempotent submission via a client-generated `client_uuid` so a retry never double-creates.
- Configurable local retention (default 30 days) after successful sync.

---

## 10. Reports & analytics

**Operational (Frappe Query Reports + SPA):** First Pass Yield by day/shift/operator/model · Top 10 failing checks (drives kaizen) · Deviations open/aged · Traceability completeness · Fast-entry advisory · Bus readiness (packs ready vs the 12 needed) · Operator throughput.

**Traceability (the containment tool):** *Where-used* — given a module/BMS serial or batch, list every pack and every bus it reached. This is the query that turns a field failure from "quarantine the fleet" into "quarantine nine packs" (§2.4).

---

## 11. White-label reporting

**Requirement: no product-owner brand name appears anywhere in a generated report.** Brand is data.

`Report Brand Profile` (§6.2) holds display name, logo, address, footer, primary colour, certificate title and signatory block. The generator shows a **Brand** dropdown defaulting to `Battery Plant Settings.default_brand` — set it to *Skyworth* today, add others whenever.

**Documents produced**

1. **Battery QC Certificate** (per pack) — brand header/logo · pack serial, model, manufacture date · component genealogy table (serial · mfg date · module no.) · every check with result, value, spec and photo thumbnails · deviations and dispositions · operator + inspector signatures · QR linking back to the record.
2. **Bus Battery Installation Report** — all 12 packs by position, with each pack's QC status and certificate link.
3. **Batch / Shift QC Summary** — FPY, failures by check, deviations, for a date range or shift.
4. **Traceability / Where-Used Report** — the containment query above.

All as Frappe Print Formats (HTML/Jinja) → PDF, generated in a background job per the repo's >2 s rule, delivered in-app and by email using the existing `Report Stakeholder` mechanism.

> **Reuse note:** `Fleet Report Config` already implements per-customer white-label branding (`display_name` + `logo`) for the KM/SLA reports. `Report Brand Profile` is the same idea generalised to plant reports — worth considering whether they should merge into one branding master.

---

## 12. API surface

All under `vehicle_maintenance/api/battery.py`, permission-checked, returning the repo's `{success, data, message}` envelope.

| Method | Purpose |
|---|---|
| `get_qc_template(battery_model \| battery_serial)` | Full template + stages + steps + reference images, versioned & cacheable |
| `start_assembly(serial, model, stage, client_uuid)` | Idempotent create/resume |
| `save_step_result(assembly, step_no, response, value, remark, seconds_spent)` | Incremental save — survives an app kill |
| `upload_step_photo(assembly, step_no, file_url, lat, lng, accuracy, captured_at)` | Extends the existing `upload_bus_image` pattern |
| `record_component_scan(assembly, component_type, raw_payload, manual?)` | Server-side parse via configured pattern; always stores raw |
| `submit_stage(assembly, stage, signature)` | Validate mandatory steps → advance workflow |
| `verify_stage(assembly, stage, decision, remarks)` | Inspector L1/L2 |
| `get_assembly(name)` | Full record for review mode |
| `install_batteries(vehicle, positions[])` | The 12-per-bus mapping |
| `generate_qc_certificate(assembly, brand_profile)` | Enqueues PDF |
| `where_used(serial \| batch_ref)` | Genealogy containment |

Compatible with `Authorization: Bearer` for the Android client, per the repo's API rules.

---

## 13. Delivery phases

| Phase | Scope | Est. |
|---|---|---|
| **P0 — Foundation** | All DocTypes, roles, workflow fixture, seed patch loading both sheets as Template v1, Desk config UX, `Report Brand Profile` | 5–6 d |
| **P1 — API** | Full `api/battery.py`, QR parser + pattern tester, geofence eval, unit tests | 4–5 d |
| **P2 — Android operator flow** | Screens 1–5, ML Kit barcode (**new dependency** — not currently in the build), photo pipeline extension, offline queue | 8–10 d |
| **P3 — QC verification** | Screen 6, deviations/NCR, quarantine + supervisor realtime alerts | 3–4 d |
| **P4 — Reports** | 4 print formats, brand picker, background generation, email delivery | 4–5 d |
| **P5 — Bus installation & analytics** | 12-per-bus mapping, FPY/where-used reports, SPA read-only views | 4–5 d |
| **P6 — Pilot** | One line, one shift, side-by-side with paper; measure against G5 | 5 d |

**~5–7 weeks.** P0–P2 alone is a usable pilot.

**Only new third-party dependency:** `com.google.mlkit:barcode-scanning` on Android. CameraX, location and photo stamping are already in the build.

---

## 14. Open questions

Answers change the build; none block starting P0.

1. **Missing check #18.** The *Installation* sheet runs 1–23 but skips 18 (22 actual checks). Dropped in editing, or a real check to recover?
2. **Insulation resistance units.** *Before Installation* remark says "Insulation resistence >2ohm"; *Installation* step 16 says "> 2 MΩ". Confirm **2 MΩ** for both.
3. **Bus bar torque, step 8.** "15 & 10 Nm" — two different bolt groups? If so it should be two steps with different bands.
4. **Cell scanning.** Confirm A3 (module-level default). Recommendation: adopt, and add a cell-batch-reference field on the module scan.
5. **Pack serial source.** Does a pack QR exist at the *start* of assembly, or is the pack ID generated by this system and the label printed after? Changes Screen 1.
6. **Second-level sign-off.** Which stages need an independent QC Inspector — *Installation* only, or both?
7. **Operator identity.** One shared station login, or individual logins? Individual is required for meaningful attribution; shared is faster. Recommendation: station device + operator PIN at start of each pack.
8. **Retention.** How long must QC photos be kept? Drives storage sizing (~6 photos × 12 packs × N buses).
9. **Existing pack history.** Backfill already-built packs, or start clean from go-live?
10. **Brand list.** Just Skyworth initially, or seed several profiles?

---

## 15. Sources

**Standards & regulation** — [IATF 16949 (Wikipedia)](https://en.wikipedia.org/wiki/IATF_16949) · [IATF 16949 audit checklist, control plan at point of use](https://ifactoryapp.com/inspection-management/iatf-16949-audit-checklist) · [IATF certification in India](https://certindia.org/iatf-16949-certification-india/) · [AIS-156 practitioner's perspective](https://evreporter.com/ais-156-standard-additional-safety-requirements-practitioners-perspective/) · [Battery safety standards in India (ARAI)](https://evreporter.com/battery-safety-standards-in-india-by-arai/) · [Battery pack traceability & compliance in India](https://evxpertz.com/blogs/battery-pack-traceability-compliance-india) · [India Battery Passport from 2026](https://evtech.news/battery-technology/india-introduces-battery-passport-for-ev-batteries-from-2026-qr-based-digital-identity-to-transform-safety-traceability-and-global-ev-trade.html) · [EV battery marking](https://www.pryormarking.com/applications/ev-battery-marking/) · [Electrode traceability (Keyence)](https://www.keyence.com/products/marker/laser-marker/resources/laser-marking-resources/electrode-traceability-for-battery-quality-control.jsp)

**Manufacturing practice** — [Battery module & pack assembly precision / FPY economics](https://www.jrautomation.com/blog/the-last-millimeter-problem-why-battery-module-and-pack-assembly-reliability-starts-with-precision) · [NCR workflow & quarantine](https://sgsystemsglobal.com/glossary/nonconformance-report-ncr/) · [In-process quality control](https://ifactoryapp.com/blog/in-process-quality-control) · [First-pass yield](https://en.wikipedia.org/wiki/First-pass_yield) · [Battery pack assembly process](https://www.semcoinfratech.com/battery-pack-assembly-process-explained/)

**Pencil whipping** — [Facilio](https://facilio.com/blog/pencil-whipping/) · [GoAudits](https://goaudits.com/blog/pencil-whipping-box-checking/) · [UpKeep](https://upkeep.com/learning/pencil-whipping/) · [Redlist](https://www.getredlist.com/hazards-of-the-pencil-whip/) · [Tractian](https://tractian.com/en/glossary/pencil-whipping)

**Shopfloor UX** — [Glyphic — Shopfloor UX](https://glyphic.design/work/shopfloor-ux/) · [Aufait — Manufacturing UX](https://www.aufaitux.com/blog/manufacturing-ux-design/) · [Emixa — design for people](https://www.emixa.com/blog/ux-in-manufacturing-design-for-people-not-just-for-machines) · [UXmatters — industrial environments](https://www.uxmatters.com/mt/archives/2017/08/ux-for-the-industrial-environment-part-1.php) · [UX in manufacturing (Medium)](https://medium.com/@sihambouguern/ux-in-manufacturing-designing-software-that-works-on-the-factory-floor-86ba9f1e0afc) · [OEE Intellisuite](https://oeeintellisuite.com/blog/ui-ux-design-in-manufacturing/) · [Digital work instructions](https://www.indx.com/solution/digital-work-instructions) · [Glove-compatible touchscreens](https://www.lcdonsale.com/info/finding-industrial-touchscreens-that-actually-103232263.html) · [Manufacturing dashboard UX](https://fuselabcreative.com/manufacturing-dashboard-ux-design/)

**Workforce** — [Compliance training for manufacturing workers (India)](https://www.leap10x.in/blogs/compliance-training-manufacturing-workers/) · [Shop floor software adoption](https://www.globalshopsolutions.com/blog/12-ways-to-boost-shop-floor-software-adoption)
