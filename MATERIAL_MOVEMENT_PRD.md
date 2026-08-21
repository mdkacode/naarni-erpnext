# Material Inward / Outward — Product & Technical Specification

**Module:** `vehicle_maintenance/material_movement`
**Status:** Specification + Implementation (v1)
**Applies to:** Hubli — Bus Manufacturing Plant · Narsapura — Battery Manufacturing / Assembly Plant
**Related:** [PROCESS_ENGINE_PRD.md](PROCESS_ENGINE_PRD.md) · [BATTERY_QC_PRD.md](BATTERY_QC_PRD.md) · [CLAUDE.md](.claude/CLAUDE.md)

---

## 1. Why this exists

Both plants move material through a gate all day and record none of it in a
system. A truck arrives at Hubli with sixteen structure bays and four HVAC units;
somebody signs a delivery challan, the challan goes in a drawer, and three weeks
later — when a bay is short or an HVAC unit is the wrong variant — there is no
record of what physically came off that truck, what condition it was in, or who
looked at it.

This module makes the gate the point of record. Every item that enters or leaves
a plant is captured against a catalogued item, with a quantity, a condition, a
**photograph**, and — where the item carries one — its **QR / serial number**. It
runs on the phone the gate clerk already has, in the same app as the Battery QC
inspections, and it produces a document an auditor can read.

### 1.1 What this is not

It is not a stock ledger. This module records **movement events**, not balances.
Deriving on-hand quantity from a stream of gate events is a reporting question,
and §12 sketches it, but no `Bin`-style running balance is written in v1. Getting
the event record right first is what makes a balance trustworthy later.

---

## 2. Relationship to the Process Engine

The Battery QC process runs on the generic Process Engine: an admin authors
stages and steps, and an operator answers them one at a time against **one
subject**. That shape is right for an inspection and wrong for a gate.

| | Process Engine run | Material Movement |
|---|---|---|
| Shape | Fixed list of questions, authored in advance | Open list of line items, discovered at the gate |
| Subject | One (a battery pack) | Many (whatever came off the truck) |
| Cardinality | ~59 steps known before the run starts | 1–200 items, unknown until the truck opens |
| Evidence | Photo per *step* | Photo per *item* |

Forcing a gate note into `Process Step` rows would mean authoring a step per
catalogue item — 151 steps, of which a typical truck answers four. So this is a
**sibling module, not a process**. What it deliberately *reuses* is everything
that was expensive to get right:

- **Photo stamping** — `StampingCamera` / `PhotoStamper`, unchanged: date-time,
  latitude/longitude and the capturing user's name burned into every frame.
- **Scanning** — `BarcodeScannerScreen` and the `Process Entity Type` QR pattern
  parser, so a scanned label is decomposed the same way it is on a battery pack.
- **The searchable dropdown** — `SmartSelect`, the app's one picker.
- **Roles and geo-capture** — the same role-gated tab pattern, the same
  `latitude`/`longitude` on the header, the same notification fan-out.
- **The response envelope** — `{success, data, message}` on every endpoint.

---

## 3. Locations

A new master, **Material Location**, rather than a reuse of `Depot`. A depot is
where buses are serviced and has service engineers and a duty geofence attached;
a plant is where they are built. Overloading `Depot` would have put "Hubli — Bus
Manufacturing Plant" into every depot picker in the duty-roster and job-card
flows, which is a worse outcome than one more small master.

| Code | Name | Type | Seeded |
|---|---|---|---|
| `HUBLI` | Hubli — Bus Manufacturing Plant | Bus Manufacturing | yes |
| `NARSAPURA` | Narsapura — Battery Manufacturing / Assembly Plant | Battery Manufacturing | yes |

Fields: `location_code`, `location_name`, `plant_type`, `city`, `state`,
`address`, `latitude`, `longitude`, `geofence_radius_m`, `gates` (free text list),
`is_active`.

Adding a third plant is a Desk form, not a deployment.

**One setup step after the first deploy.** Latitude and longitude are seeded
blank on purpose, so `geofence_status` reads `Unknown` until somebody fills them
in. A guessed city-centre coordinate would sit kilometres from the actual gate
and flag every movement `Outside`, which is worse than an honest `Unknown`. An
admin opens each Material Location once — ideally standing at the gate — sets the
two numbers, and the advisory starts working with no deploy. The fence is never
a block either way (§7.4).

---

## 4. Roles

| Role | Can | Cannot |
|---|---|---|
| **Material Gate Operator** | Create movements at their location; add/edit items, photos and QR codes; submit for verification | Verify their own movement; edit a verified one |
| **Material Supervisor** | Everything an operator can, plus verify or reject a submitted movement | — |
| **Material Viewer** | Read movements and reports | Write anything |

`System Manager` and `Depot Manager` hold supervisor-equivalent rights. Every
whitelisted method opens with `frappe.only_for(...)` or an explicit
`frappe.has_permission(...)`; the app's tab gating hides the tab but secures
nothing, exactly as the Checks tab already documents.

**Four-eyes rule.** A movement is verified by somebody other than the person who
recorded it. `verify_movement` rejects `submitted_by == frappe.session.user`
unless the site sets `material_allow_self_verify`, which exists so a single-clerk
night shift is not deadlocked.

---

## 5. Data model

```
Material Location            (master — Hubli, Narsapura)
Part / Part Group            (existing master — extended, §6)

Material Movement            (parent, one gate event)
 ├── Material Movement Item  (child — one catalogue item, qty, condition, QR)
 └── Material Movement Photo (child — evidence, pointed at an item row or the header)
```

### 5.1 Material Movement

Naming `MM-.YYYY.-.#####`.

| Group | Fields |
|---|---|
| Identity | `movement_type` (Inward/Outward), `location`, `gate`, `status`, `client_uuid` |
| Counterparty | `party_type` (Supplier/Customer/Inter-Plant/Job Work/Transporter/Internal), `party_name`, `purpose` |
| Transport | `transport_vehicle_no`, `driver_name`, `driver_phone` |
| Documents | `reference_type` (Invoice/Delivery Challan/E-Way Bill/Purchase Order/Gate Pass/Other), `reference_no`, `reference_date` |
| Context | `vehicle` (Link Vehicle — the bus this material is for, optional), `job_card` (optional) |
| People | `started_by`, `started_at`, `submitted_by`, `submitted_at`, `verified_by`, `verified_at`, `completed_at` |
| Geo | `latitude`, `longitude` |
| Rollups | `total_items`, `total_qty`, `photo_count`, `qr_count`, `damaged_count`, `new_item_count`, `evidence_pct` |
| Notes | `remarks`, `rejection_reason` |

`evidence_pct` is the share of item rows that carry at least one photo. It is the
number a supervisor scans before verifying, and the reason photos can stay
advisory (§8.3) without the record quietly rotting.

### 5.2 Material Movement Item

| Field | Notes |
|---|---|
| `row_uuid` | Client-generated. Every write is keyed on it, so a retried request updates the row it created rather than adding a second one. |
| `item` | Link → `Part` |
| `item_name`, `item_group`, `uom` | Denormalised at write time so the row still reads correctly if the master is later renamed |
| `qty`, `expected_qty` | `expected_qty` pre-filled from `Part.qty_per_bus` |
| `condition` | OK / Damaged / Short / Excess / Rejected |
| `has_qr`, `qr_code`, `qr_scanned_at`, `qr_source` | `qr_source` = Scanned / Typed — an audit cares which |
| `batch_no`, `mfg_date` | Parsed out of the QR when the pattern yields them |
| `photo_count` | Maintained by the server |
| `no_photo_reason` | Why evidence is missing, when it is |
| `is_new_item` | Set when the item was created inline at the gate (§7.2) |
| `remarks` | |

### 5.3 Material Movement Photo

`file_url`, `kind` (Item/Document/Vehicle/Gate/Damage), `item_row` (the
`row_uuid` this photo belongs to, blank for header photos), `caption`,
`captured_by`, `captured_at`, `server_received_at`, `latitude`, `longitude`,
`accuracy_m`, `is_stamped`, `client_uuid`.

Photos live on the parent and *point at* an item row rather than nesting inside
it, because Frappe has no grandchild table. This is the same shape
`Process Run Photo` uses against `step_code`, so one mental model covers both.

---

## 6. The item master

**Reused, not reinvented.** The app already has a `Part` / `Part Group` catalogue
driving job-card inventory requests. A second parallel item master would mean the
same physical HVAC unit existing under two codes, and every report having to
decide which one it meant. So the 13.csv items are loaded as Parts, and `Part`
gains five manufacturing fields:

| New field on `Part` | Purpose |
|---|---|
| `has_qr` | Item carries a serial/QR label worth recording |
| `qty_per_bus` | Sheet quantity — pre-fills the gate's quantity box |
| `spec` | Short spec line ("Glass, 8.7 mm") |
| `sheet_ref` | The 13.csv line(s) this item was transcribed from |
| `is_gate_created` | Created inline at the gate, pending admin review (§7.2) |

### 6.1 How 13.csv was organised

The weight sheet is a design document, not a catalogue, and it needed real work
before it could back a dropdown. Every decision is recorded in
`material_movement/catalogue.py` and enforced by `catalogue.verify()`:

1. **Coverage is asserted, not assumed.** Sheet lines 5–159 carry components
   (line 4 is a system header, 43 a spacer, 160 the "Total"). `verify()` fails if
   any line is transcribed twice or not at all. 155 component lines → **151
   items**.
2. **Three lighting pairs were the same part listed twice.** The sheet records
   headlamp high beam, headlamp low beam and the indicator once under
   *Aggregates* and again in the unnamed section 8. Merged, with both line
   numbers retained.
3. **Section 8 of the sheet has no system name** — 64 rows of cab, harness,
   plumbing, fastener and tool items in one heap. Left alone it would have been
   the largest and least searchable group in the catalogue, so it is split by
   function into **Cab & Controls**, **Electrical & Harness**, **Cooling & Fluid
   Lines**, **Fasteners & Consumables** and **Tools & Loose Equipment**.
4. **Names corrected, meanings preserved.** "Seteering cloumn", "Doam",
   "Beedings", "Tire and jack leaver" are typed as intended; the sheet's own
   wording survives in the item description so anyone holding the printout can
   still find the row.
5. **"Battery Weight × 12" is the HV Battery Pack.** A weight-study row naming a
   real part; catalogued as one.
6. **`qty_per_bus` is a hint, never a limit.** A truck may bring a part-load or a
   double-load, and the clerk must be able to say so.

### 6.2 Groups

| # | Group | Items | Bus system |
|---|---|---|---|
| 1 | Chassis & Driveline | 11 | Powertrain |
| 2 | Body Structure | 7 | Body & Structure |
| 3 | Exterior & Glazing | 24 | Body & Structure |
| 4 | Aggregates & Fitments | 24 | Other |
| 5 | Interior & Trim | 21 | Other |
| 6 | Mounting Brackets | 13 | Body & Structure |
| 7 | Cab & Controls | 15 | Other |
| 8 | Electrical & Harness | 17 | Electrical & Wiring |
| 9 | Cooling & Fluid Lines | 7 | Thermal Management |
| 10 | Fasteners & Consumables | 9 | Other |
| 11 | Tools & Loose Equipment | 3 | Other |
| | **Total** | **151** | |

The full item list is **Appendix A**.

### 6.3 QR-tracked items

18 items are seeded with `has_qr = 1` — the individually traceable,
warranty-bearing aggregates:

HV Battery Pack · Chassis Frame · Front Axle · Rear Axle · Steering System
Assembly · 4 in 1 Controller · BCS · TCS · Traction Motor · HVAC Unit · Wheel
Assembly · Screen 1 (Instrument Cluster) · Screen 2 (Instrument Panel) · Steering
Angle Sensor Assembly · T-Box · AVAS · MSD · Battery HV Cable

This is a **default, not a rule**. The operator may record a QR against any item,
and may move a QR-marked item that arrived without a readable label — the row is
flagged, not blocked.

---

## 7. Capturing an item

### 7.1 Suggest dropdown

The item field is a `SmartSelect` bottom sheet, per the app's standing mandate
(auto-fill everything, searchable dropdowns, no free text):

- Opens **already populated** — before a single keystroke — with, in order:
  1. items this operator has moved at this location in the last 30 days,
  2. items already on this movement's counterparty's previous movements,
  3. the rest of the catalogue alphabetically.
- Typing does a 250 ms-debounced server search over item code, item name and
  spec, so "8.7" finds both windshields and "YST240" finds the structure bays.
- Each row shows the item name, the group as a sub-label, and a `QR` badge when
  the item is QR-tracked.
- Selecting an item auto-fills `uom` and `expected_qty` and, if the item is
  QR-tracked, opens the scanner immediately.

### 7.2 Item not in the list → create it there and then

A gate clerk cannot be told to phone an administrator. The sheet at the bottom of
every empty search result is **"+ Add a new item"**, which opens a three-field
form:

| Field | Behaviour |
|---|---|
| Item name | Required. Pre-filled with whatever was typed into the search box. |
| Group | Searchable dropdown of the 11 groups. Defaults to the group the operator last used. |
| Unit | Nos / Set / Roll / Metre / Kg / Litre / Pair. Defaults to Nos. |
| Has QR label | Switch, off by default. |

On save the server creates a `Part` with:

- an auto-generated code `NEW-####` (never colliding with the catalogue prefixes),
- `is_gate_created = 1` — so admins can list exactly what the floor invented,
- `is_active = 1` — it is usable immediately,

and returns it already selected on the row, which is also stamped
`is_new_item = 1`. **Near-duplicate guard:** the server compares the typed name
against existing items case- and punctuation-insensitively and, on a match,
returns the existing item with a "we already have this one" message rather than
creating a twin. This is the single largest risk in the whole feature — an
inline-create button is how catalogues turn into 4,000 rows of
"headlamp", "Head Lamp", "HEADLAMP(front)" — and the guard plus the
`is_gate_created` review queue is the answer.

### 7.3 QR / serial

When the selected item is QR-tracked the scanner opens automatically. The
operator can also type the number if the label is damaged.

- Scanned values are parsed through the same pattern engine as battery packs; a
  pattern that yields `serial`, `batch` or `mfg` fills those fields.
- An unparseable payload is **stored verbatim**, never rejected.
- **Duplicate detection:** if the same QR appears on another movement, the app
  warns inline and shows where — a warning, not a block, because a genuine
  return-and-redispatch is exactly the same serial moving twice.
- `qr_source` records Scanned vs Typed.

### 7.4 Photo

At least one photo per item is the standard. It is enforced **advisorily**, in
line with the project's standing rule that a required photo must always have a
manual fallback:

- The app marks a photo-less row with an amber dot and offers **"Add photo"** or
  **"Can't photograph this"**, the latter asking for a one-tap reason (item
  inside sealed packaging / poor light / camera unavailable / other).
- The header shows a live `evidence_pct`.
- Submitting with rows below the bar produces a confirmation — *"7 of 12 items
  have a photo. Submit anyway?"* — not a refusal.
- The supervisor sees `evidence_pct` before verifying and can reject.

Every photo goes through `StampingCamera`: date-time, latitude/longitude and the
capturing user's name burned bottom-left, plus the fix stored as real
`latitude`/`longitude` columns so it can be queried and geofenced rather than
merely read.

---

## 8. Lifecycle

```
                    ┌──────────────────────── reject (reason) ─────────────┐
                    ▼                                                      │
  Draft ──▶ In Progress ──▶ Awaiting Verification ──▶ Completed            │
    │            │                     │                                   │
    │            │                     └───────────────────────────────────┘
    └────────────┴──▶ Cancelled
```

| From | To | Who | Rule |
|---|---|---|---|
| — | Draft | Operator | `start_movement` — header only, no items yet |
| Draft | In Progress | Operator | First item added |
| In Progress | Awaiting Verification | Operator | `submit_movement`; needs ≥ 1 item |
| Awaiting Verification | Completed | Supervisor | `verify_movement`; not the submitter (§4) |
| Awaiting Verification | In Progress | Supervisor | `reject_movement` with a mandatory reason |
| Draft / In Progress | Cancelled | Operator or Supervisor | Reason required |

`Completed` and `Cancelled` are terminal: `_assert_open()` refuses every write
against them, which is what stops a verified gate record from being edited after
the fact.

---

## 9. API

All under `vehicle_maintenance.api.material`. Every method returns
`{success, data, message}` and opens with a permission check.

| Method | Purpose |
|---|---|
| `get_gate_context()` | Everything the New-Movement screen needs in **one** round trip: locations, the operator's default, purposes, party types, reference types, conditions, UOMs, groups, recent counterparties, and whether the user may verify |
| `search_items(txt, group, location, limit)` | The suggest dropdown. Recents first, then catalogue |
| `create_item(item_name, item_group, uom, has_qr)` | Inline item creation with the near-duplicate guard |
| `start_movement(...)` | Opens a Draft and returns it |
| `save_item(movement, row_uuid, item, qty, ...)` | Upsert one line, keyed on `row_uuid` |
| `delete_item(movement, row_uuid)` | Remove a line and its photos |
| `attach_photo(movement, file_url, kind, item_row, ...)` | Register an uploaded, stamped photo |
| `delete_photo(movement, client_uuid)` | |
| `record_qr(movement, row_uuid, code, source)` | Store a QR, return duplicate warnings |
| `submit_movement(movement, remarks)` | → Awaiting Verification, notifies supervisors |
| `verify_movement(movement, remarks)` | → Completed, notifies the operator |
| `reject_movement(movement, reason)` | → In Progress, notifies the operator |
| `cancel_movement(movement, reason)` | |
| `get_movement(name)` | Full document for the detail screen |
| `my_movements(scope, location, limit, offset)` | List: `open` / `awaiting` / `finished` |
| `where_used(qr_code)` | Every movement a serial has passed through |

**Idempotency.** `start_movement` takes a `client_uuid` and returns the existing
document if one already exists for it; `save_item` and `attach_photo` are keyed
on `row_uuid` / `client_uuid`. A phone that loses signal mid-save and retries
produces one row, not two — the lesson the chat module already paid for.

---

## 10. App

A new bottom-tab, **Material**, visible only to the three material roles plus
`System Manager` / `Depot Manager`.

```
Material (list)
 ├── + New            → Gate Details      (type, location, party, transport, document)
 │                    → Items             (the working screen)
 │                        ├── SmartSelect item picker  → "+ Add a new item" sheet
 │                        ├── QR scan / type
 │                        └── StampingCamera per item
 │                    → Review & Submit
 └── tap a movement   → Detail (+ Verify / Reject for supervisors)
```

Following the EAS framework — the New-Movement flow is **three steps of four
fields**, not one form of nineteen:

1. **What & where** — Inward/Outward as two big buttons, location (pre-filled from
   the operator's default), purpose.
2. **Who & what document** — party type, party name, reference type + number.
   Skippable; a truck at the gate is not always accompanied by paperwork.
3. **Transport** — vehicle number, driver name, driver phone. Skippable.

Then the **Items screen**, which is where the clerk actually lives: a running
list of captured rows, a fat "Add item" button, and per row an inline quantity
stepper, a condition chip, a QR chip and a photo button. Each row reads as one
line — *`HVAC Unit · 1 Nos · OK · QR ✓ · 📷 2`* — so a 40-item movement is
scannable without opening anything.

Design follows the app's existing system: near-black surfaces, the single indigo
accent, `AppSurface`/semantic tokens, one `AppBar`, Rounded icons. Inward is
marked with a downward arrow and the accent; Outward with an upward arrow and the
muted tone — colour is never the only signal.

---

## 11. Screenshots and screen recording

**Blocked application-wide**, as a separate and unconditional change.

`FLAG_SECURE` is set on the single Activity's window in `MainActivity.onCreate`,
before `setContent`. That one flag covers the whole surface:

- screenshots (hardware combo, gesture, and `adb screencap`) fail with the
  system's "can't take screenshot" toast,
- screen recording and cast/mirror capture record a black frame,
- the recent-apps thumbnail renders blank, which is the leak people forget.

It is set unconditionally rather than per screen: the material gate, battery QC
and chat all handle commercially sensitive content, and a per-screen allowlist is
a list somebody eventually forgets to add to. `BuildConfig.DEBUG` is *not* an
exemption — a debug build on a developer's desk is exactly where a screenshot of
production data gets taken.

Two honest limits, stated because a security control that is oversold is worse
than none: it does not stop a photograph of the screen, and it is an OS-honoured
request — a rooted device or a modified OS can ignore it.

---

## 12. Reporting

v1 ships list + detail + the `where_used` serial trace. The obvious next
questions, all answerable from `Material Movement Item` without a schema change:

- **Gate register** — every movement at a location for a date range, printable.
- **Item flow** — inward vs outward quantity per item per location per month.
- **Damage rate** — `condition != "OK"` share by supplier.
- **Evidence quality** — mean `evidence_pct` by operator; the number that tells
  you whether the photo discipline is real.
- **Derived on-hand** — Σ inward − Σ outward per item per location. Sound only
  once opening stock is loaded, which is why it is not in v1.

---

## 13. Test plan

`test_material_movement.py`, on `FrappeTestCase`:

- `catalogue.verify()` — coverage, no duplicate codes/names, no orphan groups.
- Seeder idempotency — running twice creates nothing the second time and
  overwrites no admin edit.
- Lifecycle — every legal transition, and every illegal one throwing.
- Four-eyes — self-verification refused; allowed under the site flag.
- Idempotency — repeated `start_movement` with one `client_uuid` yields one
  document; repeated `save_item` with one `row_uuid` yields one row.
- Permissions — an operator cannot verify; a viewer cannot write; an operator at
  Hubli cannot write a Narsapura movement.
- Inline create — the near-duplicate guard returns the existing item for
  "head lamp" vs "Headlamp"; a genuinely new name creates one `Part` with
  `is_gate_created = 1`.
- Rollups — `total_items`, `photo_count`, `qr_count`, `evidence_pct` after a
  realistic sequence of writes and deletes.
- QR — duplicate serial warns and does not block; unparseable payload stored.

---

## Appendix A — The complete item list

Generated from `vehicle_maintenance/material_movement/catalogue.py`.
**Sheet** is the line number in `13.csv`. **Qty** is the sheet's stated quantity
per bus — blank where the sheet gave none. **QR** marks the items seeded as
serial-tracked.

<!-- catalogue:start -->

### A.1 Chassis & Driveline — 11 items

*Rolling chassis, axles, traction, HV packs and dampers.* · Bus system: **Powertrain**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `CHS-001` | Chassis Frame | 1 | Nos | ● | 5 | Steel frame, density 7833 kg/m³ — sheet: “Chassis Frame (Steel Frame: Density 7833kg/m3)” |
| `CHS-002` | Front Axle | 1 | Nos | ● | 6 | Independent LH and RH |
| `CHS-003` | Rear Axle | 1 | Nos | ● | 7 |  |
| `CHS-004` | Steering System Assembly | 1 | Set | ● | 8 | Steering wheel, bevel, PSG, rocker arm |
| `CHS-005` | HV Battery Pack | 12 | Nos | ● | 9 | 12 packs per bus — sheet: “Battery Weight” |
| `CHS-006` | 4 in 1 Controller | 1 | Nos | ● | 10 |  |
| `CHS-007` | BCS — Battery Cooling System | 1 | Nos | ● | 11 | sheet: “BCS” |
| `CHS-008` | TCS — Thermal Control System | 1 | Nos | ● | 12 | sheet: “TCS” |
| `CHS-009` | Traction Motor | 1 | Nos | ● | 13 | sheet: “Motor” |
| `CHS-010` | Front Shocker | 2 | Nos |  | 103 |  |
| `CHS-011` | Rear Shocker | 4 | Nos |  | 104 |  |

### A.2 Body Structure — 7 items

*YST240/YST355 skeleton bays: side, front, rear and roof.* · Bus system: **Body & Structure**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `STR-001` | Side Structure LH | 1 | Nos |  | 14 | YST240 / YST355, design weight 314 kg — sheet: “LH (YST240, YST355)” |
| `STR-002` | Side Structure RH | 1 | Nos |  | 15 | YST240 / YST355, design weight 314 kg — sheet: “RH (YST240, YST355)” |
| `STR-003` | Front Structure | 1 | Nos |  | 16 | YST240 / YST355, design weight 87.7 kg — sheet: “FRONT (YST240, YST355)” |
| `STR-004` | Rear Structure | 1 | Nos |  | 17 | YST240 / YST355, design weight 86.9 kg — sheet: “REAR (YST240, YST355)” |
| `STR-005` | Roof Structure | 1 | Nos |  | 18 | YST240 / YST355, design weight 305 kg — sheet: “ROOF (YST240, YST355)” |
| `STR-006` | Structure Add-ons |  | Set |  | 19 | L-bends, connectors etc. YST240 — sheet: “Add-ons(L-Bends, Connectors, etc) [YST240]” |
| `STR-007` | Service Door Step | 1 | Set |  | 20 | YST240, 3 steps — sheet: “Service door Step (YST240)” |

### A.3 Exterior & Glazing — 24 items

*Skin panels, flaps, doors, fascias and all glass.* · Bus system: **Body & Structure**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `EXT-001` | Luggage Flap | 6 | Nos |  | 21 | Aluminium, YST240 — sheet: “Luggage Flap (Aluminium, YST240)” |
| `EXT-002` | Battery Flap | 6 | Nos |  | 22 | Aluminium, YST240 — sheet: “Battery Flap (Aluminium, YST240)” |
| `EXT-003` | Service Flap — Service / Radiator | 2 | Nos |  | 23 | Aluminium, YST240, FRP — sheet: “Service Flap(Service, radiator) (Aluminium, YST240, FRP)” |
| `EXT-004` | Front Flap | 1 | Nos |  | 24 | FRP, YST240 — flap panel and structure — sheet: “Front Flap (FRP, YST240)” |
| `EXT-005` | Rear Flap | 1 | Nos |  | 25 | FRP, YST240 — flap panel and structure — sheet: “Rear Flap (FRP, YST240)” |
| `EXT-006` | Stretch Panel LH | 1 | Nos |  | 26 | YST240, 1 mm thick — sheet: “Stretch Panel LH (YST240)” |
| `EXT-007` | Stretch Panel RH | 1 | Nos |  | 27 | YST240, 1 mm thick — sheet: “Stretch Panel RH (YST240)” |
| `EXT-008` | Front Fascia | 1 | Set |  | 28 | FRP, YST240 — main fascia, bumper, inserts — sheet: “Front Fascia  (FRP, YST240)” |
| `EXT-009` | Rear Fascia | 1 | Set |  | 29 | FRP, YST240 — main fascia, bumper, inserts — sheet: “Rear Fascia (FRP, YST240)” |
| `EXT-010` | Roof Panel | 3 | Nos |  | 30 | YST240 — LH, RH, middle — sheet: “Roof Panels (YST240)” |
| `EXT-011` | Skirt Panel | 1 | Set |  | 31 | Aluminium, YST240, MS — panel and structure — sheet: “Skirt Panel (Aluminium, YST240, MS)” |
| `EXT-012` | Front Windshield — Top | 1 | Nos |  | 32 | Glass, 8.7 mm — sheet: “Front WindShield-Top (Glass)” |
| `EXT-013` | Front Windshield — Main | 1 | Nos |  | 33 | Glass, 8.7 mm — sheet: “Front WindShield-Main (Glass)” |
| `EXT-014` | Rear Windshield | 1 | Nos |  | 34 | Glass, 5 mm — sheet: “Rear WindShield (Glass)” |
| `EXT-015` | Upper Body Window Glass | 14 | Nos |  | 35 | 5 mm each, 14 glasses — sheet: “UB - Window Glass” |
| `EXT-016` | Lower Body Window Glass | 15 | Nos |  | 36 | 5 mm each, 15 glasses — sheet: “LB - Window Glass” |
| `EXT-017` | Driver Door | 1 | Nos |  | 37 | YST240, FRP, MS — structure, window frame, fixed and sliding glass, latch, lock, handle — sheet: “Driver door (YST240, FRP, MS)” |
| `EXT-018` | Service Door | 1 | Nos |  | 38 | YST240, FRP, MS — structure, window frame, fixed and sliding glass, latch, lock, handle — sheet: “Service door (YST240, FRP, MS)” |
| `EXT-019` | Emergency Door | 1 | Nos |  | 39 | YST240, FRP, MS — structure, fixed glass, latch, lock, handle — sheet: “Emergency Door (YST240, FRP, MS)” |
| `EXT-020` | Chrome Strip |  | Set |  | 40 | ABS, 3 mm — LH, RH, windshield — sheet: “Chrome Strip (ABS)” |
| `EXT-021` | Wheel Arch — Rear | 1 | Nos |  | 41 | sheet: “Wheel Arch Rear” |
| `EXT-022` | Wheel Arch — Front | 1 | Nos |  | 42 | sheet: “Wheel Arch Front” |
| `EXT-023` | Mud Flap | 4 | Nos |  | 119 |  |
| `EXT-024` | Precautionary Beam | 14 | Nos |  | 124 |  |

### A.4 Aggregates & Fitments — 24 items

*Bought-out assemblies fitted to the body: HVAC, lighting, seating, wheels.* · Bus system: **Other**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `AGG-001` | HVAC Unit | 1 | Nos | ● | 44 | 6 blowers, 4 condenser fans — sheet: “HVAC” |
| `AGG-002` | Escape Hatch | 3 | Nos |  | 45 | Roof hatch |
| `AGG-003` | DRL — Front | 2 | Nos |  | 46 | LH and RH — sheet: “DRL-Front” |
| `AGG-004` | DRL — Rear | 2 | Nos |  | 47 | LH and RH — sheet: “DRL-Rear” |
| `AGG-005` | Headlamp — Low Beam | 2 | Nos |  | 48, 140 | LH and RH — sheet: “Headlamp - Low Beam” |
| `AGG-006` | Headlamp — High Beam | 2 | Nos |  | 49, 139 | LH and RH — sheet: “Headlamp - High Beam” |
| `AGG-007` | Stop Lamp | 2 | Nos |  | 50 | LH and RH |
| `AGG-008` | Brake Lamp | 2 | Nos |  | 51 | LH and RH |
| `AGG-009` | Directional Indicator | 2 | Nos |  | 52, 142 | LH and RH — sheet: “Indicator” |
| `AGG-010` | Reverse Lamp | 2 | Nos |  | 53 | LH and RH — sheet: “Reverse lamp” |
| `AGG-011` | Marker Light | 2 | Nos |  | 54 | LH and RH |
| `AGG-012` | Fog Lamp — Front | 2 | Nos |  | 141 | sheet: “Fog lamp (Front)” |
| `AGG-013` | Wiper System | 1 | Set |  | 55 |  |
| `AGG-014` | Driver Seat | 1 | Nos |  | 56 |  |
| `AGG-015` | Co-Driver Seat | 1 | Nos |  | 57 | sheet: “Co-driver seat” |
| `AGG-016` | Wheel Assembly | 7 | Nos | ● | 58 | Tyre and rim, 7 wheels |
| `AGG-017` | Passenger Seat | 24 | Nos |  | 59 | 24 sitting berths |
| `AGG-018` | Destination Board | 2 | Nos |  | 60 | Front and rear |
| `AGG-019` | AC Louver |  | Set |  | 61 | sheet: “AC Louvers” |
| `AGG-020` | Cabin Light |  | Set |  | 62 |  |
| `AGG-021` | Roof Light |  | Set |  | 63 | sheet: “Roof Lights” |
| `AGG-022` | LED Strip |  | Set |  | 64 |  |
| `AGG-023` | Floor Service Hatch | 1 | Nos |  | 101 |  |
| `AGG-024` | Compartment Service Hatch | 2 | Nos |  | 159 | sheet: “Service hatch” |

### A.5 Interior & Trim — 21 items

*Cabin build-out: panels, flooring, berths, ducting and soft trim.* · Bus system: **Other**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `INT-001` | Washroom Module | 1 | Nos |  | 65 | sheet: “Washroom” |
| `INT-002` | AC Duct |  | Set |  | 66 |  |
| `INT-003` | Honeycomb Partition |  | Set |  | 67 | Single berth and double berth partition — sheet: “Honey comb partition” |
| `INT-004` | Dashboard | 1 | Nos |  | 68 |  |
| `INT-005` | Front Inner Dome | 1 | Nos |  | 69 | sheet: “Front Inner Doam” |
| `INT-006` | Rear Inner Dome | 1 | Nos |  | 70 | sheet: “Rear Inner Doam” |
| `INT-007` | Roof Interior Panel |  | Set |  | 71 | sheet: “Roof Interior panel” |
| `INT-008` | Berth Cushion |  | Set |  | 72 |  |
| `INT-009` | Berth Floor |  | Set |  | 73 |  |
| `INT-010` | Floor Plywood / Honeycomb |  | Set |  | 74 | sheet: “Floor plywood/Honeycomb” |
| `INT-011` | Vinyl Flooring |  | Roll |  | 75 | sheet: “Vinyl” |
| `INT-012` | Beadings |  | Set |  | 76 | sheet: “Beedings” |
| `INT-013` | Berth Side ABS Panel |  | Set |  | 78 |  |
| `INT-014` | Foam / Insulation |  | Set |  | 79 | sheet: “Foam/Insulation” |
| `INT-015` | Luggage Rack |  | Set |  | 80 | sheet: “Luggage rack” |
| `INT-016` | Driver Compartment Side Panel | 1 | Nos |  | 81 | sheet: “Driver comp side” |
| `INT-017` | Transition Duct | 1 | Nos |  | 82 |  |
| `INT-018` | Restrainer |  | Set |  | 83 |  |
| `INT-019` | Backrest / Headrest |  | Set |  | 84 | sheet: “Backrest/Headrest” |
| `INT-020` | A-Pillar LH/RH | 2 | Nos |  | 85 | sheet: “A Pillar LH/RH” |
| `INT-021` | Berth Curtain | 48 | Nos |  | 132 | sheet: “Berth curtains” |

### A.6 Mounting Brackets — 13 items

*Chassis-to-body and equipment mounting brackets.* · Bus system: **Body & Structure**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `SMB-001` | Chassis Body Mounting Bracket | 201 | Nos |  | 86 | sheet: “Chassis body mounting brackets ( 201 qty )” |
| `SMB-002` | Flat Type Side Mounting Bracket | 96 | Nos |  | 87 |  |
| `SMB-003` | Z Type Side Mounting Bracket | 10 | Nos |  | 88 |  |
| `SMB-004` | C-Type Taper Mounting Bracket — Big | 4 | Nos |  | 89 | sheet: “C-type taper mounting bracket - Big” |
| `SMB-005` | C-Type Taper Mounting Bracket — Medium | 4 | Nos |  | 90 | sheet: “C-type taper mounting bracket - Medium” |
| `SMB-006` | C-Type Taper Mounting Bracket — Small | 8 | Nos |  | 91 | sheet: “C-type taper mounting bracket - Small” |
| `SMB-007` | C-Type Mounting Bracket — Large | 2 | Nos |  | 92 | sheet: “C-Type mounting bracket - Large” |
| `SMB-008` | C-Type Mounting Bracket — Big | 70 | Nos |  | 93 | sheet: “C-Type mounting bracket - Big” |
| `SMB-009` | C-Type Mounting Bracket — Medium | 4 | Nos |  | 94 | sheet: “C-Type mounting bracket - Medium” |
| `SMB-010` | C-Type Mounting Bracket — Small | 3 | Nos |  | 95 | sheet: “C-Type mounting bracket - Small” |
| `SMB-011` | PLC Bracket | 1 | Nos |  | 125 |  |
| `SMB-012` | Battery Frame Layering | 1 | Nos |  | 126 |  |
| `SMB-013` | Bracket — General Purpose | 2 | Nos |  | 151 | sheet: “Bracket” |

### A.7 Cab & Controls — 15 items

*Driver station: pedals, column, screens, switches and defrost.* · Bus system: **Other**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `CAB-001` | Foot Pad | 1 | Nos |  | 97 |  |
| `CAB-002` | Driver Left Foot Pad | 1 | Nos |  | 98 |  |
| `CAB-003` | Screen 1 — Instrument Cluster | 1 | Nos | ● | 99 | sheet: “Screen 1 (Instrument cluster)” |
| `CAB-004` | Screen 2 — Instrument Panel | 1 | Nos | ● | 100 | sheet: “Screen 2 (Instrument Panel)” |
| `CAB-005` | Combination Switch | 1 | Nos |  | 105 | sheet: “combination switch” |
| `CAB-006` | Ignition Key Set | 1 | Set |  | 106 |  |
| `CAB-007` | Park Brake Lever | 1 | Nos |  | 108 |  |
| `CAB-008` | Accelerator Pedal | 1 | Nos |  | 109 |  |
| `CAB-009` | Dashboard Switch with Switch Plate | 14 | Nos |  | 110 | sheet: “Dashboard switches with switch plate” |
| `CAB-010` | Steering Column | 1 | Nos |  | 123 |  |
| `CAB-011` | Steering Angle Sensor Assembly | 1 | Nos | ● | 128 |  |
| `CAB-012` | Steering Column Decorative Cover | 1 | Nos |  | 129 | sheet: “Seteering cloumn decorative cover” |
| `CAB-013` | Steering Wheel | 1 | Nos |  | 138 |  |
| `CAB-014` | Defroster | 1 | Nos |  | 122 |  |
| `CAB-015` | Defroster Duct | 1 | Roll |  | 146 |  |

### A.8 Electrical & Harness — 17 items

*Harnesses, HV cables, grounding, telematics and electrical fitments.* · Bus system: **Electrical & Wiring**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `ELE-001` | Horn — High and Low Pitch | 2 | Nos |  | 107 | Hella — sheet: “Horns (Hella) High and low pith” |
| `ELE-002` | T-Box — Telematics Unit | 1 | Nos | ● | 111 | sheet: “T-Box” |
| `ELE-003` | Acoustic Vehicle Alerting System | 1 | Nos | ● | 113 | AVAS |
| `ELE-004` | DNR and OBD Connector | 1 | Set |  | 114 |  |
| `ELE-005` | Grounding Strap | 15 | Nos |  | 116 |  |
| `ELE-006` | LV Battery Terminal Cable | 1 | Nos |  | 117 |  |
| `ELE-007` | MSD — Manual Service Disconnect | 12 | Nos | ● | 118 | sheet: “MSD” |
| `ELE-008` | Buzzer | 1 | Nos |  | 135 |  |
| `ELE-009` | USB Port | 26 | Nos |  | 136 | sheet: “USB ports” |
| `ELE-010` | Reverse Parking Kit Harness | 1 | Set |  | 137 |  |
| `ELE-011` | Front Wall Harness | 1 | Nos |  | 143 |  |
| `ELE-012` | Dashboard Harness | 1 | Nos |  | 144 |  |
| `ELE-013` | Battery HV Cable | 4 | Nos | ● | 145 | sheet: “Battery HV cables” |
| `ELE-014` | Ceiling Wire Harness | 1 | Nos |  | 147 |  |
| `ELE-015` | Duct and Roof Harness | 2 | Nos |  | 148 | 1 pc each |
| `ELE-016` | Interior Wire Harness |  | Set |  | 77 | sheet: “Wire Harness” |
| `ELE-017` | Compartment Door Travel Switch | 1 | Nos |  | 157 |  |

### A.9 Cooling & Fluid Lines — 7 items

*Coolant hoses, connectors, valves and fittings.* · Bus system: **Thermal Management**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `CLG-001` | C-Type Coolant Hose | 4 | Nos |  | 96 | sheet: “C-Type coolant hose” |
| `CLG-002` | Coolant Hose |  | Metre |  | 133 | Bulk issue |
| `CLG-003` | Battery Coolant Connector | 12 | Nos |  | 134 |  |
| `CLG-004` | Straight Connector and T-Fitting | 3 | Nos |  | 112 |  |
| `CLG-005` | HVAC Connector | 1 | Set |  | 131 |  |
| `CLG-006` | Copper Ball Valve — Small | 2 | Nos |  | 150 | sheet: “Copper ball valve (Small)” |
| `CLG-007` | Threaded Transition Joint | 4 | Nos |  | 155 |  |

### A.10 Fasteners & Consumables — 9 items

*Bulk-issue fasteners, clips, clamps and sealing items.* · Bus system: **Other**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `FAS-001` | TPMS Sensor Fitting Clamp | 6 | Nos |  | 115 |  |
| `FAS-002` | Sealing Ring |  | Nos |  | 120 | Bulk issue |
| `FAS-003` | Insulated Wire Clip Bracket | 1 | Nos |  | 121 |  |
| `FAS-004` | MSD Screw |  | Nos |  | 127 | Bulk issue |
| `FAS-005` | Nut, Bolt and Fastener — Assorted |  | Kg |  | 149 | Bulk issue — sheet: “All nut bolt and fasteners” |
| `FAS-006` | Cable Tie (ZIP) |  | Nos |  | 152 | Bulk issue |
| `FAS-007` | Compensated Clamp |  | Nos |  | 153 | Bulk issue |
| `FAS-008` | Non-Standard Pipe Clamp |  | Nos |  | 154 | Bulk issue |
| `FAS-009` | Insulated Wire Clip | 3 | Nos |  | 156 |  |

### A.11 Tools & Loose Equipment — 3 items

*Loose equipment dispatched with the bus.* · Bus system: **Other**

| Code | Item | Qty | UOM | QR | Sheet | Spec / sheet wording |
|---|---|---|---|---|---|---|
| `TLS-001` | Wheel Chock | 2 | Nos |  | 102 |  |
| `TLS-002` | Tool Box with Tools | 1 | Set |  | 130 |  |
| `TLS-003` | Tyre and Jack Lever | 2 | Set |  | 158 | sheet: “Tire and jack leaver” |

<!-- catalogue:end -->
