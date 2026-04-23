# NAARNI Vehicle Maintenance Platform — Complete Implementation Plan

> **Version:** 1.0 | **Date:** 2026-04-17 | **Author:** Claude (for Mayank @ NaArNi)
> **Platform:** Frappe Framework (Python 3.11+) + Frappe UI (Vue 3) + React Native (Mobile)
> **Custom App:** `vehicle_maintenance`

---

## TABLE OF CONTENTS

1. [Executive Summary & Current State Analysis](#1-executive-summary--current-state-analysis)
2. [Gap Analysis: What Exists vs What's Needed](#2-gap-analysis)
3. [Data Model — Complete DocType Definitions](#3-data-model)
4. [Workflow State Machines — All 4 Job Card Types](#4-workflows)
5. [PMS Checksheet System — 20K/40K/80K](#5-pms-checksheet-system)
6. [Health Score Calculation Engine](#6-health-score-engine)
7. [API Layer — Complete Mobile-Ready Endpoints](#7-api-layer)
8. [Frontend (Frappe UI / Vue 3) — Role-Based UI](#8-frontend)
9. [Notification & SLA Engine](#9-notification--sla-engine)
10. [Report Generation (PDF)](#10-report-generation)
11. [Inventory Management Flow](#11-inventory-management)
12. [Audit Trail & Force Close System](#12-audit-trail--force-close)
13. [Mobile App API Contract (React Native)](#13-mobile-api-contract)
14. [Phased Implementation Roadmap](#14-phased-roadmap)
15. [Memory & Session Continuity Strategy](#15-memory-strategy)
16. [File-by-File Implementation Order](#16-file-by-file-order)

---

## 1. EXECUTIVE SUMMARY & CURRENT STATE ANALYSIS

### 1.1 What the Platform Does

NaArNi Assured is a vehicle maintenance job card platform for **electric bus fleets**. It manages the entire lifecycle of four types of service jobs — PMS (Preventive Maintenance), Repair, Software Update, and Breakdown — from creation through inspection, customer approval, parts allocation, execution, verification, and closure. It serves 5 distinct user roles across both web (Frappe UI) and mobile (React Native) interfaces.

### 1.2 What Already Exists (Codebase Audit)

The existing `vehicle_maintenance` app at `/vehicle_maintenance/` has a **solid foundation** but is approximately **40% complete** for Phase 1. Here's what's built:

**Backend — Mostly Scaffolded (60% done):**
- ✅ 10 DocTypes defined (Job Card, Vehicle, Customer, Depot, Service Contract, Part Group, Service Estimate + child tables)
- ✅ Job Card controller with SLA computation, QR code generation, cost calculation (279 lines)
- ✅ 23 whitelisted API endpoints across 4 modules (job_card, dashboard, estimate, inventory)
- ✅ Workflow JSON with 7 states and 10+ transitions
- ✅ Hooks configured with doc_events, fixtures, website routes
- ✅ Client script for Desk (181 lines) with auto-fill logic

**Frontend — Scaffolded But Shallow (35% done):**
- ✅ Vue 3 SPA with 9 routes, 11 components, Vite + Tailwind configured
- ✅ Job Card creation wizard (6-step form)
- ✅ Dashboard pages for all 4 roles
- ⚠️ TechnicianInspection page is a skeleton (476 bytes — nearly empty)
- ⚠️ CustomerJobCard page is minimal (1.2KB)
- ❌ No PMS checksheet UI
- ❌ No photo upload integration
- ❌ No real-time status tracking

**Critical Gaps (What's Missing):**
- ❌ **PMS Checksheet DocTypes** — The entire 20K/40K/80K inspection system has no data model
- ❌ **Software Update Job Card flow** — No DocType for tracking software versions, pre/post validation
- ❌ **Breakdown Job Card flow** — No fields for remote diagnosis, fault codes, trial trip
- ❌ **Inspection Sheet DocType** — Referenced in code but never created
- ❌ **Notification system** — No implementation of the 12+ notification triggers
- ❌ **Force Close / Severity system** — No data model or logic
- ❌ **Photo/Video upload** — No attachment handling in any flow
- ❌ **PDF Report generation** — Print format exists but doesn't match the sample reports
- ❌ **Inventory allocation tracking** — Request system exists but no allocation/issue/acknowledge flow
- ❌ **Customer approval link system** — No implementation
- ❌ **VOC (Voice of Customer)** at subsystem/component level — Not modeled
- ❌ **Job Card Naming** — PRD requires `CustomerCode-LocationCode-Date-Serial` but code uses `JC-.YYYY.-.#####`
- ❌ **Warranty tracking** — Referenced in service history but not modeled
- ❌ **Concern Code / Labor Code system** — Service history shows structured codes (GC4045, PM0006) but no master data model

### 1.3 Key Architectural Decisions (Self-Debate)

**Decision 1: Single Job Card DocType vs Separate Per Type?**

*Argument FOR separate:* Each type (PMS, Repair, Software Update, Breakdown) has very different fields. Software Update needs version tracking; Breakdown needs fault codes and trial trip data; PMS needs checksheet linkage. Separate DocTypes would be cleaner.

*Argument AGAINST:* The PRD explicitly states unified job card states. All types share the same core workflow, same notification system, same TAT/SLA engine. The reporting dashboard aggregates across types. Splitting would mean 4× the API endpoints, 4× the frontend pages, and fragmented analytics.

**→ DECISION: Single Job Card DocType with type-specific Section Breaks** that show/hide based on `job_card_type`. This matches the existing code and the PRD's unified state machine. Type-specific child tables (Software Update Items, Breakdown Diagnosis) are linked separately.

**Decision 2: PMS Checksheet — Static JSON vs DocType?**

*Argument FOR static JSON:* Checksheets are standardized (20K/40K/80K). Storing them as JSON fixtures means faster loading and no DB queries.

*Argument AGAINST:* The PRD says "Check sheet: Opens based on the KMs." Different OEMs might have different checksheets. Admin should be able to modify checksheet items without code changes. DocType gives us Frappe's built-in CRUD, permissions, and version control.

**→ DECISION: DocType-based.** Create `PMS Checksheet` (master) and `PMS Checksheet Item` (child) DocTypes. Pre-populate via fixtures for 20K/40K/80K. This allows Admin to customize without code changes while keeping the data in Frappe's ecosystem.

**Decision 3: Job Card Naming Convention**

PRD requires: `CustomerCode-LocationCode-Date-SerialNumber` (e.g., `ZB-GGN-210326-002`)
Current code uses: `JC-.YYYY.-.#####`

**→ DECISION: Custom naming via `autoname` override.** Implement `autoname` method in Job Card controller that generates `{customer_code}-{depot_code}-{YYMMDD}-{serial}`. This matches sample reports exactly.

**Decision 4: Concern Codes & Labor Codes as Master Data?**

The BharatBenz service history shows structured concern codes (GC4045 = specific failure) and labor codes (35132469 = specific labor operation). The PRD's Part Group system partially covers this but doesn't go deep enough.

**→ DECISION: Add `Concern Code` and `Labor Code` master DocTypes.** These enable structured reporting, warranty claims, and analytics. Pre-populate from the service history data.

---

## 2. GAP ANALYSIS

### 2.1 DocTypes — Existing vs Required

| DocType | Exists? | Status | Action Required |
|---------|---------|--------|-----------------|
| Job Card | ✅ | Needs major field additions | Add 30+ fields for breakdown, software update, force close, naming |
| Job Card Repair Item | ✅ | Mostly complete | Add concern_code link, warranty_applicable, pre/post photo fields |
| Job Card Maintenance Item | ✅ | Needs expansion | Add consumable_type [Top Up, Full Replacement], pre_photo, post_photo, volume_filled, drained_photo, new_product_photo |
| Vehicle | ✅ | Needs expansion | Add chassis_number, oem, last_pms_date, last_pms_odometer, last_service_by |
| Customer | ✅ | Needs expansion | Add customer_code (for naming), contact_person, fleet_size |
| Depot | ✅ | Needs expansion | Add depot_code (for naming), storekeeper |
| Service Contract | ✅ | Complete | Minor: add accidental_repair_tat_hours |
| Part Group | ✅ | Complete | Good as-is |
| Service Estimate | ✅ | Complete | Good as-is |
| Service Estimate Item | ✅ | Complete | Good as-is |
| **PMS Checksheet** | ❌ | Missing | **CREATE** — Master for 20K/40K/80K sheets |
| **PMS Checksheet Item** | ❌ | Missing | **CREATE** — Individual check parameters |
| **PMS Inspection Result** | ❌ | Missing | **CREATE** — Per-job-card inspection responses |
| **PMS Inspection Result Item** | ❌ | Missing | **CREATE** — Individual item results with scores |
| **Software Update Item** | ❌ | Missing | **CREATE** — Child table for SW version tracking |
| **Post Update Validation** | ❌ | Missing | **CREATE** — Child table for post-SW validation |
| **Breakdown Diagnosis** | ❌ | Missing | **CREATE** — Fault codes, remote resolution, trial trip |
| **Force Close Log** | ❌ | Missing | **CREATE** — Severity, authority, follow-up tracking |
| **Inventory Request** | ❌ | Missing | **CREATE** — Separate from Job Card for proper workflow |
| **Inventory Request Item** | ❌ | Missing | **CREATE** — Line items for inventory requests |
| **Concern Code** | ❌ | Missing | **CREATE** — Master data (GC codes from service history) |
| **Labor Code** | ❌ | Missing | **CREATE** — Master data (labor operations) |
| **VOC Entry** | ❌ | Missing | **CREATE** — Voice of Customer at subsystem level |
| **Job Card Activity Log** | ❌ | Missing | **CREATE** — Timestamped audit trail beyond Frappe's default |
| **Notification Log** | ❌ | Missing | **CREATE** — Track sent notifications for debugging |
| **OEM** | ❌ | Missing | **CREATE** — OEM master (Azad, NaArNi, BharatBenz) |
| **Bus System** | ❌ | Missing | **CREATE** — Master for the 17 PMS categories + 45 repair groups |

### 2.2 API Endpoints — Existing vs Required

| Endpoint | Exists? | Action |
|----------|---------|--------|
| `create_job_card_with_inspection` | ✅ | Needs refactoring for all 4 types |
| `get_job_card_summary` | ✅ | Needs type-specific data inclusion |
| `get_my_job_cards` | ✅ | Good |
| `transition_job_card` | ✅ | Needs type-specific transition validation |
| `update_job_card` | ✅ | Needs field expansion |
| `get_available_actions` | ✅ | Good |
| `get_user_roles` | ✅ | Good |
| `get_job_card_by_qr` | ✅ | Good |
| Dashboard APIs (4) | ✅ | Need real data integration |
| Estimate APIs (3) | ✅ | Good |
| Inventory APIs (2) | ✅ | Need expansion for full flow |
| **PMS Checksheet APIs** | ❌ | CREATE — get_checksheet, submit_inspection |
| **Photo Upload API** | ❌ | CREATE — upload_photo, get_photos |
| **Software Update APIs** | ❌ | CREATE — submit_sw_update, validate_update |
| **Breakdown APIs** | ❌ | CREATE — start_remote_diagnosis, log_fault_code, start_trial_trip, end_trial_trip |
| **Force Close API** | ❌ | CREATE — force_close_issue |
| **Customer Tracking API** | ❌ | CREATE — get_tracking_link, get_live_status |
| **Notification APIs** | ❌ | CREATE — get_notifications, mark_read |
| **Inventory Full Flow APIs** | ❌ | CREATE — allocate_parts, issue_parts, acknowledge_receipt |
| **Report APIs** | ❌ | CREATE — generate_pms_report, generate_repair_report, generate_sw_report |
| **VOC APIs** | ❌ | CREATE — submit_voc, get_voc_for_vehicle |
| **Health Score API** | ❌ | CREATE — get_vehicle_health, get_health_history |
| **Feedback APIs** | ❌ | CREATE — submit_feedback, get_feedback |

---

## 3. DATA MODEL — COMPLETE DOCTYPE DEFINITIONS

### 3.1 Job Card (MODIFIED — Major Expansion)

**Naming Rule Change:** `autoname` method → `{customer_code}-{depot_code}-{YYMMDD}-{serial}`

**New Fields to Add:**

```
Section: Breakdown Info (visible only when job_card_type == "Breakdown")
─────────────────────────────────────────────────────────────────────
- incident_place          Select    [Depot, En Route]
- fault_code_1            Data      (from odometer/diagnostics)
- fault_code_2            Data
- fault_code_3            Data
- remote_diagnosis_attempted  Check
- remote_diagnosis_result     Select  [Resolved, Failed, Partial]
- remote_diagnosis_notes      Small Text
- travel_start_time       Datetime  (when SE starts traveling to BD location)
- travel_arrival_time     Datetime  (when SE arrives at BD location)
- travel_duration_mins    Int       (auto-calculated)
- resolution_type         Select    [Permanent Fix, Temporary Fix, Force Closed]
- recurrence_risk_vehicle Select    [High, Low]
- occurrence_risk_fleet   Select    [High, Low]
- next_level_engineer     Link → User
- trial_trip_start_odo    Int
- trial_trip_end_odo      Int
- trial_trip_start_time   Datetime
- trial_trip_end_time     Datetime
- trial_trip_distance_km  Float     (auto: end_odo - start_odo)
- trial_trip_duration     Duration  (auto-calculated)
- dead_kms                Float     (= trial_trip_distance_km)
- vehicle_handover_time   Datetime
- total_downtime_mins     Int       (auto: handover_time - opened_at)
- rca_required            Check
- rca_report              Text Editor

Section: Software Update Info (visible only when job_card_type == "Software Update")
─────────────────────────────────────────────────────────────────────
- software_update_items   Table → Software Update Item
- post_update_validations Table → Post Update Validation

Section: PMS Info (visible only when job_card_type == "PMS + Repair")
─────────────────────────────────────────────────────────────────────
- checksheet_type         Select    [A - 20000 KM, B - 40000 KM, C - 80000 KM]
- checksheet_link         Link → PMS Checksheet
- pms_tolerance_level     Data      (auto-calculated: overdue KMs and %)
- scheduled_pms_kms       Int       (from vehicle's maintenance schedule)
- inspection_result       Link → PMS Inspection Result
- pending_for_next_pms    Table → Pending PMS Item  (items deferred to next PMS)

Section: Vehicle History Context
─────────────────────────────────────────────────────────────────────
- last_pms_date           Date      (auto-fill from vehicle)
- last_pms_odometer       Int       (auto-fill from vehicle)
- last_pms_tolerance      Data      (auto-fill from vehicle)
- last_serviced_by_se     Data      (auto-fill from vehicle)
- last_serviced_by_tech   Data      (auto-fill from vehicle)

Section: VOC (Voice of Customer)
─────────────────────────────────────────────────────────────────────
- voc_entries             Table → VOC Entry

Section: Force Close
─────────────────────────────────────────────────────────────────────
- has_force_closed_items  Check     (auto-set)
- force_close_logs        Table → Force Close Log

Section: Photos
─────────────────────────────────────────────────────────────────────
- vin_plate_photo         Attach Image
- odometer_photo          Attach Image
- arrival_photo_front     Attach Image  (already exists)
- arrival_photo_rear      Attach Image  (already exists)
- arrival_photo_left      Attach Image  (already exists)
- arrival_photo_right     Attach Image  (already exists)

Section: Approval & Tracking
─────────────────────────────────────────────────────────────────────
- customer_tracking_link  Data      (auto-generated URL)
- customer_approval_sent_at  Datetime
- customer_approval_received_at  Datetime
- customer_approval_status  Select  [Pending, Approved, Rejected]
- customer_rejection_feedback  Text
- approval_escalated_to   Link → User  (N. Maintenance Head)

Section: Closure & Verification
─────────────────────────────────────────────────────────────────────
- technician_closed_at    Datetime
- se_verified_at          Datetime
- se_verification_notes   Small Text
- reopened_count          Int       (auto-incremented)
- reopen_reason           Small Text
- depot_manager_approved  Check
- depot_manager_approved_at  Datetime
- report_sent_to_customer Check
- report_sent_at          Datetime

Section: Feedback
─────────────────────────────────────────────────────────────────────
- customer_rating         Rating    (1-5)
- customer_feedback       Text
- driver_rating           Rating    (1-5)
- driver_feedback         Text
- feedback_requested_at   Datetime
```

### 3.2 NEW DocType: PMS Checksheet (Master)

```
DocType: PMS Checksheet
Module: Fleet Service
Naming: checksheet_name (e.g., "Check Sheet A - 20000 KM")
─────────────────────────────────────────────────────────────────────
Fields:
- checksheet_name         Data        (unique)
- checksheet_code         Select      [A, B, C]
- km_interval             Int         (20000, 40000, 80000)
- oem                     Link → OEM
- description             Small Text
- items                   Table → PMS Checksheet Item
- total_check_points      Int         (auto-count)
- is_active               Check       (default: 1)

Permissions:
- Admin:          Full CRUD
- Depot Manager:  Read
- Service Engineer: Read
- Technician:     Read
```

### 3.3 NEW DocType: PMS Checksheet Item (Child Table)

```
DocType: PMS Checksheet Item
─────────────────────────────────────────────────────────────────────
Fields:
- sr_no                   Int
- section_name            Data        (e.g., "I. Check Bus Exterior")
- check_parameter         Data        (e.g., "All Lamp Glasses both Front/Side / Rear")
- inspection_method       Select      [Visual, Functional, Repair, Lubrication, Measure, Visual & Functional, Visual / Top up]
- std_time_mins           Int
- category                Link → Bus System  (e.g., "Outer Body", "Brake System")
- requires_photo          Check       (1 if inspection_method is Repair or Measure)
- requires_measurement    Check
- measurement_unit        Data        (e.g., "mm", "°", "V", "bar")
- acceptable_range        Data        (e.g., "≤1.8mm", "± 10°", ">27V")
```

### 3.4 NEW DocType: PMS Inspection Result

```
DocType: PMS Inspection Result
Module: Fleet Service
Naming: INSP-.YYYY.-.#####
─────────────────────────────────────────────────────────────────────
Fields:
- job_card                Link → Job Card (mandatory)
- checksheet              Link → PMS Checksheet (mandatory)
- vehicle                 Link → Vehicle (auto from job card)
- inspection_date         Date
- inspected_by            Link → User (technician)
- supervised_by           Link → User (SE)

Section: Scores (Auto-Calculated)
- pre_pms_vehicle_score   Float       (0-100%)
- post_pms_vehicle_score  Float       (0-100%)
- score_improvement_pct   Float       (auto: ((post-pre)/pre)*100)

Section: Category Scores (Auto-Calculated — one row per category)
- category_scores         Table → PMS Category Score

Section: Inspection Items
- items                   Table → PMS Inspection Result Item

Section: Spares & Consumables Summary
- spares_changed          Table → Job Card Repair Item  (link back)
- consumables_changed     Table → Job Card Maintenance Item  (link back)
- labour_jobs             Table → Labour Job Entry
- pending_for_next_pms    Table → Pending PMS Item
```

### 3.5 NEW DocType: PMS Inspection Result Item (Child Table)

```
Fields:
- checksheet_item         Data        (reference to PMS Checksheet Item)
- sr_no                   Int
- section_name            Data
- check_parameter         Data
- inspection_method       Data
- category                Link → Bus System

Status Fields:
- pre_status              Select      [Good, Repair/Replace Recommended, Repair/Replace Immediately, N/A]
- pre_score               Int         (auto: Good=10, Recommended=5, Immediately=0)
- pre_observation         Small Text
- pre_photo               Attach Image
- pre_video               Attach

Post-Service Fields:
- post_status             Select      [Good, Repair/Replace Recommended, Repair/Replace Immediately, N/A]
- post_score              Int         (auto-calculated)
- post_photo              Attach Image
- post_video              Attach

Action:
- action_taken            Select      [No Action, Repaired, Replaced, Cleaned, Topped Up, Lubricated, Adjusted, Deferred]
- action_notes            Small Text
- part_used               Link → Part Group
- part_qty                Float
- labour_time_mins        Int
```

### 3.6 NEW DocType: PMS Category Score (Child Table)

```
Fields:
- category                Link → Bus System  (e.g., "Outer Body")
- total_components        Int
- max_possible_score      Int         (= total_components × 10)
- pre_score_sum           Int
- pre_score_pct           Float       (= pre_score_sum / max_possible_score × 100)
- post_score_sum          Int
- post_score_pct          Float
- improvement_pct         Float       (= ((post-pre)/pre) × 100)
```

### 3.7 NEW DocType: Software Update Item (Child Table for Job Card)

```
Fields:
- component               Select      [BMS, Cluster Meter, Air Compressor, HVAC, PLC, MCU, DMS/ADAS, VCU, 4in1, ECAS, EBS, Motor]
- pre_version             Data        (e.g., "v1.2.0")
- post_version            Data        (e.g., "v1.3.0")
- update_type             Select      [Performance Improvement, Regular Update, Emergency Update (Bug Fix)]
- reason                  Small Text
- pre_photo               Attach Image  (photo of odometer/display showing version)
- post_photo              Attach Image
- calibration_values      Small Text    (SOP-based)
- calibration_photo       Attach Image  (internal only — not shown to customer)
- status                  Select      [Pending, In Progress, Success, Failed, Retry]
- retry_count             Int
- failure_photo           Attach Image  (if failed)
- failure_notes           Small Text
```

### 3.8 NEW DocType: Post Update Validation (Child Table)

```
Fields:
- component               Data        (mirrors Software Update Item component)
- validation_check         Data        (e.g., "Motor Temperature Monitoring", "SOC Stability")
- observation              Small Text   (e.g., "Motor temperature found normal during operation")
- status                   Select      [Pass, Fail, Inconclusive]
```

### 3.9 NEW DocType: VOC Entry (Child Table)

```
Fields:
- bus_system               Link → Bus System
- part_group               Link → Part Group (optional, for component-level)
- description              Small Text   (driver/customer complaint text)
- reported_by              Select      [Driver, Customer, SE]
- severity                 Select      [Low, Medium, High, Critical]
- linked_to_repair_item    Link → Job Card Repair Item  (if resolved in this JC)
```

### 3.10 NEW DocType: Force Close Log (Child Table)

```
Fields:
- issue_description        Small Text
- bus_system               Link → Bus System
- part_group               Link → Part Group
- severity                 Select      [Minor, Major, Critical]
- force_close_authority    Select      [SE, Depot Manager, N. Maintenance Head, Aftersales Engineer]
- closed_by                Link → User
- reason                   Text (mandatory)
- follow_up_required       Check
- follow_up_deadline       Date        (Critical: 24hrs, Major: 24hrs, Minor: next PMS)
- follow_up_job_card       Link → Job Card  (auto-created for Critical)
- status                   Select      [Open, Follow-up Created, Resolved]
```

### 3.11 NEW DocType: Inventory Request

```
DocType: Inventory Request
Module: Fleet Service
Naming: IR-.YYYY.-.#####
─────────────────────────────────────────────────────────────────────
Fields:
- job_card                Link → Job Card (mandatory)
- vehicle                 Link → Vehicle (auto)
- depot                   Link → Depot (auto)
- requested_by            Link → User
- request_date            Datetime (auto)
- urgency                 Select      [Normal, Urgent, Emergency]
- status                  Select      [Requested, Partially Allocated, Fully Allocated, Parts Issued, Acknowledged, Procurement Initiated]
- items                   Table → Inventory Request Item
- total_estimated_value   Currency (auto-sum)
- requires_customer_approval  Check   (auto: 1 if any item > ₹1000)
- customer_approval_status    Select  [Not Required, Pending, Approved, Rejected]
- allocated_by            Link → User  (Depot Manager)
- allocated_at            Datetime
- issued_by               Link → User  (Depot Manager / Storekeeper)
- issued_at               Datetime
- acknowledged_by         Link → User  (Technician/SE)
- acknowledged_at         Datetime

Permissions:
- Technician:      Create, Read
- Service Engineer: Create, Read, Write
- Depot Manager:   Full CRUD
- Central Ops:     Read
```

### 3.12 NEW DocType: Inventory Request Item (Child Table)

```
Fields:
- part_group              Link → Part Group
- part_name               Data
- part_number             Data        (e.g., "A4009811818")
- description             Small Text
- qty_requested           Float
- qty_allocated           Float       (filled by Depot Manager)
- qty_issued              Float       (filled on physical handover)
- unit                    Select      [Pc, Ltr, Kg, Set, Pair]
- estimated_rate          Currency
- estimated_amount        Currency    (auto: qty × rate)
- in_stock                Check       (auto from inventory lookup)
- procurement_needed      Check       (auto: 1 if not in_stock)
- status                  Select      [Requested, Allocated, Issued, Acknowledged]
```

### 3.13 NEW DocType: Concern Code (Master)

```
DocType: Concern Code
Naming: concern_code (e.g., "GC4045")
─────────────────────────────────────────────────────────────────────
Fields:
- concern_code            Data (unique, primary key)
- description             Data (e.g., "DIFFERENTIAL OIL LEAK")
- bus_system              Link → Bus System
- part_group              Link → Part Group
- severity_default        Select [Minor, Major, Critical]
- oem                     Link → OEM (optional)
```

### 3.14 NEW DocType: Labor Code (Master)

```
DocType: Labor Code
Naming: labor_code (e.g., "35132469")
─────────────────────────────────────────────────────────────────────
Fields:
- labor_code              Data (unique)
- description             Data (e.g., "Rear rearward axle drive head removal and refitment")
- standard_time_hours     Float
- bus_system              Link → Bus System
- part_group              Link → Part Group
- requires_specialist     Check
```

### 3.15 NEW DocType: OEM (Master)

```
DocType: OEM
Naming: oem_name
─────────────────────────────────────────────────────────────────────
Fields:
- oem_name                Data (unique)  — e.g., "NaArNi", "Azad", "BharatBenz"
- oem_code                Data           — e.g., "NRN", "AZD", "BB"
- contact_email           Data
- support_phone           Data
```

### 3.16 NEW DocType: Bus System (Master)

```
DocType: Bus System
Naming: system_name
─────────────────────────────────────────────────────────────────────
Fields:
- system_name             Data (unique)  — e.g., "Outer Body", "Brake System"
- system_code             Data           — e.g., "OB", "BRK"
- usage_context           Select [PMS, Repair, Both]
- display_icon            Data           — emoji/icon reference
- display_order           Int            — for UI sorting
```

**Pre-populated Data (from PMS Excel Category-Groups sheet):**

PMS Categories (17): Outer Body, Maintenance Consumables, Bus Interior, Driving Control System, Traction Motor System, HV Power Supply, Air Compressor, Steering System, Axle System, Brake System, LV Electrical, Suspension System, Tyres & Wheels, Others, EV Charging, Air Conditioning, Passenger Comfort & Safety, Drive Shaft

Repair Groups (45): HV Battery, PDU, MCU, Traction Motor, 4in1, VCU, HVAC, Charging Port, PLC, FDSS, Defroster, Brake, Front Suspension, Rear Suspension, ECAS, Front Axle, Front Wheel Hub, Tie Rod Assembly, Rear Axle, Main Reducer, Steering System, Central Lubrication System, Drive Shaft, Pedals & Control, Air Tanks & Boosters, Driver Dashboard & Infotainment, Driver Module, Cameras & DVR, Interior Electrical, Outer Electricals, TCM Cooling, Air Compressor, Battery Cooling, Windshields, Glasses, Passenger Comfort, Bumpers, Flaps, Locks, Mirrors, Stay Rods, Doors, Wheels, HV Wiring Harness, LV Wiring Harness

### 3.17 Vehicle DocType MODIFICATIONS

```
New fields to add:
- chassis_number          Data
- oem                     Link → OEM
- customer_code           Data        (for JC naming)
- last_pms_date           Date
- last_pms_odometer       Int
- last_pms_checksheet     Select [A, B, C]
- last_serviced_by_se     Data
- last_serviced_by_tech   Data
- next_pms_due_kms        Int         (auto-calculated)
- vehicle_health_score    Float       (latest post-PMS score)
- total_job_cards         Int         (auto-count)
- total_breakdowns        Int         (auto-count)
- warranty_start_date     Date
- warranty_end_date       Date
```

---

## 4. WORKFLOWS — ALL 4 JOB CARD TYPES

### 4.1 Unified State Machine (Modified from Existing)

The existing workflow has 7 states. The PRD requires adjustments:

```
STATES:
─────────────────────────────────────────────────────────────────────
1. Open              (Job card created, not yet started)
2. WIP               (Work in progress — inspection/repair active)
3. Awaiting Customer Approval  (Estimate sent, waiting for customer)
4. Awaiting Parts     (Customer approved, parts not yet available)
5. Parts Fitted       (Parts received and installed by technician)
6. Verification Pending  (Technician marked complete, SE reviewing)
7. Closed             (SE verified and closed)
8. Force Closed       (NEW — Issue force-closed with severity tracking)
9. Reopened           (NEW — Previously closed, reopened for issues)

TRANSITIONS:
─────────────────────────────────────────────────────────────────────
Open → WIP                          [Depot Manager, SE]        "Start Work"
WIP → Awaiting Customer Approval    [SE]                       "Send Estimate"
  condition: doc.service_estimate AND doc.job_card_type != "Breakdown"
Awaiting Customer Approval → WIP    [SE]                       "Customer Rejects"
Awaiting Customer Approval → Awaiting Parts  [SE]              "Customer Approves"
WIP → Awaiting Parts                [SE, Technician]           "Request Parts"
Awaiting Parts → Parts Fitted       [Technician, SE]           "Parts Received & Fitted"
Parts Fitted → WIP                  [Technician, SE]           "Continue Work"
WIP → Verification Pending          [Technician, SE]           "Submit for Verification"
Parts Fitted → Verification Pending [Technician, SE]           "Submit for Verification"
Verification Pending → Closed       [SE]                       "Close Job Card"
Verification Pending → WIP          [SE]                       "Verification Failed / Reopen"
Closed → Reopened                   [Customer, Central Ops]    "Reopen Job Card"
Reopened → WIP                      [SE]                       "Resume Work"
WIP → Force Closed                  [SE, Depot Manager]        "Force Close"
  condition: doc.has_force_closed_items

SPECIAL RULES BY TYPE:
─────────────────────────────────────────────────────────────────────
- Breakdown: Skips "Awaiting Customer Approval" for emergency/critical parts
- Breakdown: Requires end verification by Aftersales Engineer or Vehicle Engineer
- Software Update: Simplified flow — Open → WIP → Verification Pending → Closed
  (No customer approval, no parts flow)
- PMS + Repair: Full flow with checksheet completion required before WIP → Verification
```

### 4.2 Type-Specific Flow Details

**PMS + Repair (Steps mapped to PRD):**
```
1. SE creates JC → selects PMS + Repair → system auto-selects checksheet (A/B/C)
2. SE assigns technician → enters VOC
3. Technician inspects per checksheet → marks each item OK/Not OK
4. System calculates Pre-PMS Score
5. For Not OK items → generates Repair Job List + Maintenance Job List
6. Repair items > ₹1000 → requires customer approval
7. Inventory request → Depot Manager allocates → Parts issued → Tech acknowledges
8. Technician performs work → uploads post-repair photos
9. Technician marks closed → SE verifies → SE closes
10. System calculates Post-PMS Score → generates Health Card
11. PDF report → Depot Manager approves → sent to customer
```

**Only Repair (Steps mapped to PRD):**
```
1. SE creates JC → selects Repair → selects affected subsystems
2. SE records VOC at group level
3. Technician selects groups → selects parts to change/repair/refurbish
4. Technician uploads assessment photos (with annotation option)
5. System does inventory lookup → two lists: Parts to Replace + Labour
6. Customer approval link generated → customer approves/rejects per part
7. If non-responsive → N. Maintenance Head can override
8. Depot Manager allocates parts → Technician fits parts → uploads post photos
9. Unresolved issues → Force Close with severity
10. Technician marks closed → SE verifies → SE closes
11. PDF report → Depot Manager approves → sent to customer
```

**Software Update (Steps mapped to PRD):**
```
1. SE creates JC → selects Software Update → selects components
2. Technician selects update reason per component
3. Take photo of current version (PRE)
4. Perform update via USB/HW
5. Enter calibration values (if applicable) → take photo (internal only)
6. Take photo of new version (POST)
7. If failed → retry from step 3
8. SE verifies SW versions → marks all validations
9. Report generation → optional send to customer
```

**Breakdown (Steps mapped to PRD):**
```
1. JC created immediately upon breakdown notification
2. SE fills: incident place, fault codes, affected groups
3. SE attempts remote diagnosis (30 min window)
4. If remote fails → SE travels to location (timestamps tracked)
5. On-spot diagnosis → PRE photos → issue identification
6. SE follows troubleshooting SOP → override if unavailable
7. Resolution found → permanent/temporary/force-close
8. If temporary → assign next-level engineer → set recurrence risk
9. Trial trip → odometer start/end → dead KMs calculation
10. Vehicle handed over → total downtime calculated
11. SE closes JC → report generated → RCA request to Aftersales
```

---

## 5. PMS CHECKSHEET SYSTEM

### 5.1 Checksheet Selection Logic

```python
def get_checksheet_for_odometer(odometer_reading: int) -> str:
    """
    Determine which checksheet (A/B/C) based on odometer.
    A = 20,000 KM intervals (20K, 60K, 100K, 140K...)
    B = 40,000 KM intervals (40K, 120K, 200K...)
    C = 80,000 KM intervals (80K, 160K, 240K...)

    Logic: Find nearest scheduled PMS KM, then determine type.
    - Every 80K → C
    - Every 40K (not 80K) → B
    - Every 20K (not 40K or 80K) → A
    """
    nearest_pms_km = round(odometer_reading / 20000) * 20000
    if nearest_pms_km == 0:
        nearest_pms_km = 20000

    if nearest_pms_km % 80000 == 0:
        return "C"  # 80K checksheet (most comprehensive)
    elif nearest_pms_km % 40000 == 0:
        return "B"  # 40K checksheet
    else:
        return "A"  # 20K checksheet (basic)
```

### 5.2 PMS Tolerance Calculation

```python
def calculate_pms_tolerance(odometer: int, scheduled_pms_kms: int) -> dict:
    """
    From sample report: "Overdue by 15,000 KM (13%)"
    """
    overdue_km = odometer - scheduled_pms_kms
    tolerance_pct = (overdue_km / scheduled_pms_kms) * 100 if scheduled_pms_kms else 0
    return {
        "scheduled_pms_kms": scheduled_pms_kms,
        "overdue_km": max(0, overdue_km),
        "tolerance_pct": round(abs(tolerance_pct), 1),
        "status": "Overdue" if overdue_km > 0 else "On Time"
    }
```

### 5.3 Checksheet Data (Fixtures)

Three fixtures files to create:
- `pms_checksheet_20k.json` — 84 check parameters across 13 sections
- `pms_checksheet_40k.json` — 100+ check parameters across 15 sections (adds HV components, MCU, Battery)
- `pms_checksheet_80k.json` — 110+ check parameters across 16 sections (adds King Pin, all exhaustive checks)

Each checksheet item maps to a Bus System category for score calculation.

---

## 6. HEALTH SCORE CALCULATION ENGINE

### 6.1 Score Formula (From PRD Section 1.B)

```python
def calculate_health_scores(inspection_items: list) -> dict:
    """
    STATUS MAPPING:
      Good                      → 10 points
      Repair/Replace Recommended → 5 points
      Repair/Replace Immediately → 0 points

    CATEGORY SCORE:
      Category Score (%) = (Sum of Component Scores / Max Possible Score) × 100
      Where: Max Possible Score = Number of Components × 10

    VEHICLE HEALTH SCORE:
      Vehicle Health Score (%) = Average of all Category Scores

    IMPROVEMENT:
      Improvement (%) = ((Post Score − Pre Score) / Pre Score) × 100
    """
    score_map = {"Good": 10, "Repair/Replace Recommended": 5, "Repair/Replace Immediately": 0}

    categories = {}
    for item in inspection_items:
        cat = item.category
        if cat not in categories:
            categories[cat] = {"pre_scores": [], "post_scores": []}
        categories[cat]["pre_scores"].append(score_map.get(item.pre_status, 10))
        if item.post_status:
            categories[cat]["post_scores"].append(score_map.get(item.post_status, 10))

    category_results = []
    for cat_name, data in categories.items():
        max_score = len(data["pre_scores"]) * 10
        pre_pct = (sum(data["pre_scores"]) / max_score) * 100 if max_score else 0
        post_pct = (sum(data["post_scores"]) / max_score) * 100 if data["post_scores"] and max_score else pre_pct
        improvement = ((post_pct - pre_pct) / pre_pct) * 100 if pre_pct > 0 else 0

        category_results.append({
            "category": cat_name,
            "total_components": len(data["pre_scores"]),
            "max_possible_score": max_score,
            "pre_score_pct": round(pre_pct, 1),
            "post_score_pct": round(post_pct, 1),
            "improvement_pct": round(improvement, 1)
        })

    pre_vehicle_score = sum(c["pre_score_pct"] for c in category_results) / len(category_results) if category_results else 0
    post_vehicle_score = sum(c["post_score_pct"] for c in category_results) / len(category_results) if category_results else 0

    return {
        "pre_vehicle_score": round(pre_vehicle_score, 1),
        "post_vehicle_score": round(post_vehicle_score, 1),
        "improvement_pct": round(((post_vehicle_score - pre_vehicle_score) / pre_vehicle_score) * 100, 1) if pre_vehicle_score else 0,
        "categories": category_results
    }
```

### 6.2 Score Display (Matching Sample PMS Report)

The sample PMS Report shows:
- Pre PMS Vehicle Health: **74 / 100**
- Post PMS Vehicle Health: **89 / 100** ↑ +20% Improvement
- Per-category breakdown with Pre, Post, Change columns

This maps directly to the `PMS Category Score` child table rendered in the PDF report.

---

## 7. API LAYER — COMPLETE MOBILE-READY ENDPOINTS

### 7.1 API Module Structure

```
vehicle_maintenance/api/
├── __init__.py
├── auth.py              # Authentication & session (EXISTS — extend)
├── job_card.py          # Job card CRUD & lifecycle (EXISTS — major extension)
├── dashboard.py         # Role-based dashboards (EXISTS — extend)
├── estimate.py          # Service estimates (EXISTS — complete)
├── inventory.py         # Inventory management (EXISTS — extend)
├── inspection.py        # NEW — PMS inspection flow
├── software_update.py   # NEW — Software update flow
├── breakdown.py         # NEW — Breakdown diagnosis flow
├── reports.py           # NEW — PDF report generation
├── notifications.py     # NEW — Notification management
├── vehicle.py           # NEW — Vehicle master & health
├── feedback.py          # NEW — Customer/driver feedback
├── upload.py            # NEW — Photo/video upload handling
```

### 7.2 NEW API: inspection.py

```python
@frappe.whitelist()
def get_checksheet(checksheet_type: str, oem: str = "") -> dict:
    """Get PMS checksheet items for the given type (A/B/C).
    Returns structured data grouped by section and category."""

@frappe.whitelist()
def auto_select_checksheet(odometer_reading: int, vehicle: str) -> dict:
    """Auto-determine checksheet type based on odometer and return it.
    Also calculates PMS tolerance level."""

@frappe.whitelist()
def submit_pre_inspection(job_card_name: str, items: list) -> dict:
    """Submit pre-PMS inspection results.
    Creates PMS Inspection Result doc, calculates Pre-PMS scores.
    items: [{checksheet_item_id, pre_status, pre_observation, pre_photo}]"""

@frappe.whitelist()
def submit_post_inspection(job_card_name: str, items: list) -> dict:
    """Submit post-PMS results after work completion.
    Updates PMS Inspection Result, calculates Post-PMS scores.
    Updates Vehicle health score.
    items: [{item_id, post_status, action_taken, post_photo}]"""

@frappe.whitelist()
def get_inspection_result(job_card_name: str) -> dict:
    """Get full inspection result with scores for a job card."""
```

### 7.3 NEW API: software_update.py

```python
@frappe.whitelist()
def get_software_components() -> dict:
    """Get list of software-updatable components (from SW List)."""

@frappe.whitelist()
def submit_software_update(job_card_name: str, updates: list) -> dict:
    """Submit software update details.
    updates: [{component, pre_version, post_version, update_type, reason,
               pre_photo, post_photo, calibration_values, calibration_photo}]"""

@frappe.whitelist()
def mark_update_status(job_card_name: str, component: str, status: str, failure_photo: str = "", failure_notes: str = "") -> dict:
    """Mark individual component update as Success/Failed.
    If Failed, increment retry_count."""

@frappe.whitelist()
def submit_post_update_validation(job_card_name: str, validations: list) -> dict:
    """Submit post-update validation results.
    validations: [{component, validation_check, observation, status}]"""
```

### 7.4 NEW API: breakdown.py

```python
@frappe.whitelist()
def create_breakdown_job_card(vehicle_number: str, incident_place: str,
                               fault_codes: list, groups_impacted: list,
                               description: str = "") -> dict:
    """Create a breakdown job card immediately.
    Auto-fills vehicle history from last PMS."""

@frappe.whitelist()
def start_remote_diagnosis(job_card_name: str, diagnosis_notes: str = "") -> dict:
    """Mark remote diagnosis started. Starts 30-min timer."""

@frappe.whitelist()
def end_remote_diagnosis(job_card_name: str, result: str, notes: str = "") -> dict:
    """End remote diagnosis. result: Resolved/Failed/Partial.
    If Failed, triggers travel planning notifications."""

@frappe.whitelist()
def log_travel_timestamps(job_card_name: str, event: str) -> dict:
    """Log travel events: start_travel, arrived.
    Auto-calculates travel duration."""

@frappe.whitelist()
def submit_on_site_diagnosis(job_card_name: str, groups_confirmed: list,
                              fault_codes: list, photos: list) -> dict:
    """Submit on-site diagnosis findings."""

@frappe.whitelist()
def submit_resolution(job_card_name: str, resolution_type: str,
                       recurrence_risk: str, fleet_risk: str,
                       next_engineer: str = "") -> dict:
    """Submit breakdown resolution details."""

@frappe.whitelist()
def start_trial_trip(job_card_name: str, start_odometer: int) -> dict:
    """Start trial trip. Records start odometer and time."""

@frappe.whitelist()
def end_trial_trip(job_card_name: str, end_odometer: int) -> dict:
    """End trial trip. Calculates distance (dead KMs) and duration."""

@frappe.whitelist()
def handover_vehicle(job_card_name: str) -> dict:
    """Mark vehicle as handed over. Calculates total downtime."""
```

### 7.5 NEW API: reports.py

```python
@frappe.whitelist()
def generate_pms_report(job_card_name: str) -> dict:
    """Generate PMS Completion Report PDF matching sample format.
    Includes: header, vehicle health scores, category breakdown,
    spares changed, consumables changed, labour jobs, pending items."""

@frappe.whitelist()
def generate_repair_report(job_card_name: str) -> dict:
    """Generate Repair Completion Report PDF matching sample format.
    Includes: header, VOC, repair summary with pre/post photos,
    labour jobs performed."""

@frappe.whitelist()
def generate_software_update_report(job_card_name: str) -> dict:
    """Generate Software Update Report PDF matching sample format.
    Includes: header, update summary, post-update validation."""

@frappe.whitelist()
def generate_breakdown_report(job_card_name: str) -> dict:
    """Generate Breakdown Report PDF.
    Includes: timeline, diagnosis, resolution, trial trip, RCA request."""

@frappe.whitelist()
def get_customer_summary_report(job_card_name: str) -> dict:
    """Generate customer-facing summary (Health Card).
    Includes: Parts, Lubes & Consumables, Labour jobs."""
```

### 7.6 NEW API: notifications.py

```python
@frappe.whitelist()
def get_my_notifications(limit: int = 20, offset: int = 0) -> dict:
    """Get notifications for current user. Paginated."""

@frappe.whitelist()
def mark_notification_read(notification_id: str) -> dict:
    """Mark a notification as read."""

@frappe.whitelist()
def get_unread_count() -> dict:
    """Get count of unread notifications."""
```

### 7.7 NEW API: vehicle.py

```python
@frappe.whitelist()
def get_vehicle_health(vehicle_name: str) -> dict:
    """Get current vehicle health score and history."""

@frappe.whitelist()
def get_vehicle_service_history(vehicle_name: str, limit: int = 10) -> dict:
    """Get service history for a vehicle — all job cards."""

@frappe.whitelist()
def search_vehicles_extended(txt: str = "", limit: int = 10) -> dict:
    """Extended vehicle search with health score and last service info."""
```

### 7.8 NEW API: upload.py

```python
@frappe.whitelist()
def upload_job_card_photo(job_card_name: str, field_name: str, photo_data: str) -> dict:
    """Upload a photo for a specific job card field.
    Supports: vin_plate_photo, odometer_photo, arrival photos,
    repair item pre/post photos, inspection photos."""

@frappe.whitelist()
def upload_repair_item_photo(job_card_name: str, item_idx: int, photo_type: str, photo_data: str) -> dict:
    """Upload pre/post photo for a specific repair item."""
```

### 7.9 EXTENDED API: inventory.py (Add to Existing)

```python
@frappe.whitelist()
def create_inventory_request(job_card_name: str, items: list, urgency: str = "Normal") -> dict:
    """Create a formal Inventory Request linked to job card.
    Auto-checks stock availability. Flags items > ₹1000 for customer approval."""

@frappe.whitelist()
def allocate_inventory(request_name: str, allocations: list) -> dict:
    """Depot Manager allocates parts. Partial allocation supported.
    allocations: [{item_idx, qty_allocated}]"""

@frappe.whitelist()
def issue_inventory(request_name: str, issued_items: list) -> dict:
    """Physical handover confirmed by Depot Manager/Storekeeper.
    issued_items: [{item_idx, qty_issued}]"""

@frappe.whitelist()
def acknowledge_inventory(request_name: str) -> dict:
    """Technician/SE acknowledges receipt. Locks parts to job card."""
```

### 7.10 API Response Envelope (Standard for All)

```python
# Success:
{
    "success": True,
    "data": { ... },
    "message": "Job card created successfully"
}

# Error (via frappe.throw):
{
    "success": False,
    "exc_type": "ValidationError",
    "message": "Vehicle number is required"
}
```

### 7.11 Authentication for Mobile

```
# Token-based auth (already supported by Frappe):
Authorization: token api_key:api_secret

# Or OAuth 2.0 Bearer token:
Authorization: Bearer <access_token>

# Session cookie (web):
Cookie: sid=<session_id>
```

---

## 8. FRONTEND (FRAPPE UI / VUE 3) — ROLE-BASED UI

### 8.1 Design Philosophy: Extreme Simplification (EAS)

Every screen follows the EAS framework from CLAUDE.md:
- **Eliminate:** Only show fields relevant to the current user's role and current step
- **Automate:** Pre-fill everything possible (vehicle → customer, odometer → checksheet, date/time auto)
- **Simplify:** Multi-step wizards, max 4-5 fields per step, large tap targets

### 8.2 Route Structure (Expanded)

```
/service-portal                          → Dashboard (role-aware)
/service-portal/login                    → Login

# Job Card Routes
/service-portal/job-card/new             → Creation Wizard (SE only)
/service-portal/job-card/:name           → Detail View (role-aware)
/service-portal/job-card/:name/inspect   → PMS Inspection Wizard (Tech/SE)
/service-portal/job-card/:name/repair    → Repair Items Entry (Tech/SE)
/service-portal/job-card/:name/sw-update → Software Update Flow (Tech/SE)
/service-portal/job-card/:name/breakdown → Breakdown Diagnosis Flow (SE)
/service-portal/job-card/:name/photos    → Photo Gallery & Upload
/service-portal/job-card/:name/verify    → SE Verification View

# Customer Routes
/service-portal/my-fleet                 → Customer Fleet Dashboard
/service-portal/my-fleet/:vehicle        → Vehicle Health Card
/service-portal/approve/:token           → Customer Approval Page (public link)
/service-portal/track/:job_card          → Live Job Card Tracking (public link)
/service-portal/feedback/:token          → Post-Service Feedback Form

# Dashboard Routes
/service-portal/depot-manager            → Depot Manager Analytics
/service-portal/central-ops              → Central Ops Cross-Depot View

# Inventory Routes
/service-portal/inventory/requests       → Inventory Request List (DM)
/service-portal/inventory/:name          → Inventory Request Detail (DM)
```

### 8.3 Screen-by-Screen Specifications

**Screen: Job Card Creation Wizard (SE)**
```
Step 1: Select Type
  - 4 large cards: PMS + Repair | Only Repair | Software Update | Breakdown
  - Single tap selection → auto-advance

Step 2: Vehicle
  - Vehicle Number: Searchable dropdown (type-ahead)
  - On select → auto-fill: Customer, OEM, Model, Last PMS, Health Score
  - Odometer: Number input (OR photo capture → manual entry)
  - VIN Plate: Camera button → capture photo
  - If PMS: Auto-calculate checksheet type, tolerance level (shown as info badge)
  - If Breakdown: Show last PMS date, last service info prominently

Step 3: Location & Assignment
  - Depot: Searchable dropdown (auto if SE has single depot)
  - Service Engineer: Auto-filled with current user
  - Technician: Searchable dropdown (users with Technician role at this depot)
  - If Breakdown: Incident Place selector [Depot / En Route]

Step 4: VOC (Voice of Customer)
  - If PMS/Repair: Free text area + option to add subsystem-specific complaints
    → "Add Complaint" button → select Bus System → type description
  - If Breakdown: Fault Code 1, Fault Code 2 inputs + Group Impacted multi-select
  - If Software Update: Component multi-select from SW List

Step 5: Review & Submit
  - Summary card showing all entered data
  - Service Contract auto-selected (shown, not editable)
  - "Create Job Card" button
  - On success → navigate to Job Card Detail
```

**Screen: PMS Inspection Wizard (Technician/SE)**
```
Full-screen wizard, one section at a time.

Section view:
  - Section header (e.g., "I. Check Bus Exterior")
  - Category badge (e.g., "Outer Body")
  - For each check parameter:
    → Parameter text
    → Inspection method badge (Visual / Functional / Repair)
    → THREE-BUTTON selector: ✅ Good | ⚠️ Recommend | 🔴 Immediate
    → If NOT Good: Camera button appears → capture photo
    → Optional observation text field

Bottom bar:
  - Progress indicator (Section 3 of 13)
  - "Previous" / "Next Section" buttons
  - Auto-save on section change

Final section: Summary
  - Pre-PMS Score displayed (auto-calculated)
  - Category score breakdown (matching report format)
  - "Submit Inspection" button

Post-submission:
  - Auto-generates two lists:
    1. Repair Items (items marked Not Good with action needed)
    2. Maintenance Items (consumables due for replacement)
  - These populate the Job Card's repair_items and maintenance_items tables
```

**Screen: Customer Approval Page (Public Link)**
```
No login required — accessed via unique token URL.

Header: NaArNi Assured branding + Job Card number
Vehicle info card: Number, Model, Odometer

For each repair item:
  - Component name + description
  - Pre-repair photo thumbnail (tap to enlarge)
  - Estimated cost
  - ✅ Approve / ❌ Reject toggle per item
  - If rejected: feedback text field appears

Bottom:
  - Total estimated cost (sum of approved items)
  - "Submit Approval" button
  - Inactivity warning: "Response needed within 30 minutes"
```

**Screen: Breakdown Diagnosis Flow (SE)**
```
Step-by-step vertical timeline UI:

Step 1: Remote Diagnosis (Active)
  - Timer showing elapsed time (30 min countdown)
  - Notes text area for diagnosis attempts
  - Two buttons: "Resolved Remotely" | "Remote Failed — Travel Required"

Step 2: Travel (if remote failed)
  - "Start Travel" button → records timestamp
  - Shows elapsed travel time
  - "Arrived at Location" button → records timestamp, shows travel duration

Step 3: On-Site Diagnosis
  - Confirm affected groups (pre-selected from creation)
  - Additional fault codes entry
  - Camera: upload diagnostic photos/videos
  - "Issue Identified" button

Step 4: Resolution
  - Pre-repair photo capture
  - Troubleshooting steps (follow SOP or Force Override)
  - Resolution entry:
    → Type: Permanent / Temporary / Force Closed
    → If Temporary: Select next-level engineer
    → Recurrence Risk: High / Low
    → Fleet Occurrence Risk: High / Low

Step 5: Trial Trip
  - Start odometer (number input or photo)
  - "Start Trip" button → timer
  - "End Trip" button → end odometer
  - Auto-show: Distance (dead KMs), Duration

Step 6: Handover
  - "Vehicle Handed Over" button
  - Auto-show: Total downtime
  - "Close Job Card" button
```

### 8.4 Component Library (New Components Needed)

```
components/
├── PhotoCapture.vue         # Camera + upload component with preview
├── ScoreGauge.vue           # Circular gauge for health scores (74/100)
├── CategoryScoreBar.vue     # Horizontal bar showing Pre/Post/Change
├── InspectionItem.vue       # Single checksheet item with 3-state toggle
├── RepairItemCard.vue       # Repair item card with photo + cost
├── TimelineStep.vue         # Vertical timeline step (for breakdown flow)
├── ApprovalToggle.vue       # Approve/Reject per-item toggle
├── CountdownTimer.vue       # 30-min countdown for breakdown/approval
├── NotificationBell.vue     # Notification icon with unread badge
├── VehicleHealthCard.vue    # Mini card showing vehicle + score
├── SLABadge.vue             # Color-coded SLA status badge
├── JobCardTypeSelector.vue  # 4-card type selection
├── SearchableSelect.vue     # Type-ahead dropdown for vehicles/depots
├── StepProgress.vue         # Multi-step wizard progress indicator
└── PDFViewer.vue            # In-app PDF report viewer
```

---

## 9. NOTIFICATION & SLA ENGINE

### 9.1 Notification Triggers (From PRD — All 12)

```python
# vehicle_maintenance/utils/notifications.py

NOTIFICATION_MAP = {
    "job_card_created": {
        "notify": ["Depot Manager", "Technician (if assigned)"],
        "channels": ["in_app", "push"],
        "sla": "Immediate"
    },
    "customer_approval_requested": {
        "notify": ["Customer"],
        "channels": ["in_app", "push", "sms"],
        "sla": "Immediate",
        "escalation": {"after_mins": 30, "to": "N. Maintenance Head"}
    },
    "customer_approved": {
        "notify": ["SE", "Depot Manager"],
        "channels": ["in_app", "push"],
        "sla": "Immediate"
    },
    "customer_rejected": {
        "notify": ["SE", "Depot Manager"],
        "channels": ["in_app", "push"],
        "sla": "Immediate"
    },
    "parts_allocated": {
        "notify": ["SE", "Technician"],
        "channels": ["in_app", "push"],
        "sla": "Immediate"
    },
    "tat_warning_80pct": {
        "notify": ["Depot Manager", "Central Ops", "N. Maintenance Head"],
        "channels": ["in_app", "push", "sms"],
        "sla": "Auto-triggered at 80% elapsed"
    },
    "tat_breached": {
        "notify": ["Central Ops", "Depot Manager", "SE", "N. Maintenance Head"],
        "channels": ["in_app", "push", "sms", "email"],
        "sla": "Immediate escalation"
    },
    "technician_closed": {
        "notify": ["SE", "Depot Manager", "Central Ops"],
        "channels": ["in_app", "push"],
        "sla": "Immediate — triggers SE verification"
    },
    "se_verified_closed": {
        "notify": ["Depot Manager", "Central Ops", "N. Maintenance Head"],
        "channels": ["in_app", "push"],
        "sla": "Immediate"
    },
    "job_card_reopened": {
        "notify": ["Depot Manager", "Central Ops"],
        "channels": ["in_app", "push"],
        "sla": "Immediate"
    },
    "breakdown_declared": {
        "notify": ["Central Ops", "Depot Manager", "SE", "N. Maintenance Head"],
        "channels": ["in_app", "push", "sms"],
        "sla": "Immediate — highest priority"
    },
    "remote_resolution_failed": {
        "notify": ["SE", "Central Ops", "N. Maintenance Head"],
        "channels": ["in_app", "push", "sms"],
        "sla": "System-triggered at 30 min"
    },
    "feedback_request": {
        "notify": ["Customer", "Driver"],
        "channels": ["in_app", "push", "sms"],
        "sla": "Sent X hours after card closed"
    }
}
```

### 9.2 SLA Engine (Background Job)

```python
# Runs every 5 minutes via Frappe scheduler
def check_sla_breaches():
    """
    For each open job card:
    1. Calculate elapsed time since opened_at
    2. Compare against SLA target (from Service Contract or defaults)
    3. At 80% → trigger tat_warning_80pct notification
    4. At 100% → trigger tat_breached notification + set sla_breached flag
    """

# SLA Defaults (from PRD):
SLA_DEFAULTS = {
    "PMS + Repair": 4.0,           # hours
    "Only Repair": 4.0,            # hours
    "Only Repair (Accidental)": 24.0,  # hours
    "Software Update": 3.0,        # hours
    "Breakdown (Remote)": 0.5,     # hours (30 minutes)
    "Breakdown (On-site)": None,   # configurable per contract
    "Customer Approval": 0.5       # hours (30 minutes)
}
```

### 9.3 Scheduler Hooks

```python
# hooks.py additions
scheduler_events = {
    "cron": {
        "*/5 * * * *": [
            "vehicle_maintenance.utils.sla.check_sla_breaches"
        ],
        "0 * * * *": [
            "vehicle_maintenance.utils.notifications.send_pending_feedback_requests"
        ]
    }
}
```

---

## 10. REPORT GENERATION (PDF)

### 10.1 Report Types (Matching Sample PDFs)

**PMS Completion Report (matching Sample PMS Report-1.pdf):**
```
Header:
  - NAARNI ASSURED branding + "PMS Completion Report - Check Sheet [A/B/C]" + VERIFIED badge
  - Report ID, Generated timestamp
  - Vehicle, Date, Customer, Depot, Checksheet type
  - Odometer, Scheduled PMS KMs, PMS Tolerance, Start time, Completion time

Health Scores:
  - Pre PMS Vehicle Health: XX / 100
  - Post PMS Vehicle Health: XX / 100 + improvement %
  - Category-by-category breakdown (Pre, Post, Change columns)
    → 14+ categories with color-coded scores

Tables:
  - Spares Changed (S.No, Item, Qty)
  - Labour Jobs Performed (S.No, Activity)
  - Consumables Changed (S.No, Consumable)
  - Pending for Next PMS (S.No, Pending Item)

Footer:
  - "Powered by Naarni" + "Confidential • Internal Use"
```

**Repair Completion Report (matching Repair Report.pdf):**
```
Header:
  - NAARNI ASSURED + "Repair Completion Report" + VERIFIED
  - Job Card, Vehicle, Customer, Depot, Date, Start/End, Service Type

VOC Section:
  - Customer complaint text

Repair Summary Table:
  - S.No, Component/Issue, Pre Photo, Post Photo, Qty, Status

Labour Jobs Performed:
  - S.No, Activity
```

**Software Update Report (matching Software Update Report.pdf):**
```
Header:
  - NAARNI ASSURED + "Software Update Report" + VERIFIED
  - Job Card, Vehicle, Customer, Depot, Service Type, Date

Software Update Summary:
  - S.No, Component, Pre Version, Post Version, Update Type, Reason, Status

Post Update Validation:
  - S.No, Component, Validation Check, Observation, Status
```

### 10.2 Implementation Approach

Use `frappe.utils.pdf.get_pdf()` with Jinja2 HTML templates + WeasyPrint. Create HTML templates in `vehicle_maintenance/templates/` that match the exact visual style of the sample PDFs (green header, NAARNI branding, card-based layout, VERIFIED badge).

---

## 11. INVENTORY MANAGEMENT FLOW

### 11.1 Complete Flow (5 Steps from PRD)

```
Step 1: Tech/SE identifies parts needed
  → Creates Inventory Request within job card
  → System checks: if any item value > ₹1000 → flag for customer approval

Step 2: System sends request to Depot Manager
  → Notification with job card no., vehicle no., part details
  → Auto inventory lookup shows in-stock status

Step 3: Depot Manager processes
  → If in stock: allocates parts → status "Parts Allocated"
  → If not in stock: initiates procurement (local or central)
  → Can partial allocate

Step 4: Physical handover
  → Depot Manager / Storekeeper marks "Parts Issued"
  → Separate confirmation from allocation

Step 5: Acknowledgment
  → Technician/SE acknowledges receipt in app
  → Parts locked to this job card
  → Job card state can move to "Parts Fitted"
```

---

## 12. AUDIT TRAIL & FORCE CLOSE SYSTEM

### 12.1 Audit Trail Requirements

Frappe's built-in Version tracking handles most field-level changes. Additionally, create a `Job Card Activity Log` child table for semantic events:

```
Events to log:
- Job card created (by, at)
- Workflow state changed (from, to, by, at)
- Technician assigned/changed
- SE assigned/changed
- Inspection started/completed
- Each repair item status change
- Customer approval sent/received
- Parts requested/allocated/issued/acknowledged
- Photos uploaded
- Force close action
- Reopen action (with reason)
- SE verification pass/fail
- Report generated/sent
```

### 12.2 Force Close Severity Matrix

```
Severity: Minor
  - Definition: Cosmetic or minor functional issue
  - Authority: SE can force close independently
  - Follow-up: At next scheduled PMS

Severity: Major
  - Definition: Bus operational but significant degradation
  - Authority: Depot Manager OR Aftersales Engineer approval mandatory
  - Follow-up: Within 24 hours

Severity: Critical
  - Definition: Bus non-operational or safety risk
  - Authority: N. Maintenance Head
  - Follow-up: Within 24 hours — new job card auto-created
```

---

## 13. MOBILE APP API CONTRACT (React Native)

### 13.1 Authentication Flow

```
POST /api/method/vehicle_maintenance.api.auth.login
Body: { "usr": "user@email.com", "pwd": "password" }
Response: { "success": true, "data": { "api_key": "...", "api_secret": "...", "user": "...", "roles": [...] } }

All subsequent requests:
Header: Authorization: token {api_key}:{api_secret}
```

### 13.2 Core Mobile Endpoints

```
# Dashboard
GET  /api/method/vehicle_maintenance.api.dashboard.get_{role}_dashboard

# Job Cards
GET  /api/method/vehicle_maintenance.api.job_card.get_my_job_cards?status=&limit=20&offset=0
GET  /api/method/vehicle_maintenance.api.job_card.get_job_card_summary?job_card_name=
POST /api/method/vehicle_maintenance.api.job_card.create_job_card_with_inspection
POST /api/method/vehicle_maintenance.api.job_card.transition_job_card
POST /api/method/vehicle_maintenance.api.job_card.update_job_card
GET  /api/method/vehicle_maintenance.api.job_card.get_available_actions?job_card_name=
GET  /api/method/vehicle_maintenance.api.job_card.get_job_card_by_qr?job_card_name=

# PMS Inspection
GET  /api/method/vehicle_maintenance.api.inspection.auto_select_checksheet?odometer=&vehicle=
GET  /api/method/vehicle_maintenance.api.inspection.get_checksheet?checksheet_type=
POST /api/method/vehicle_maintenance.api.inspection.submit_pre_inspection
POST /api/method/vehicle_maintenance.api.inspection.submit_post_inspection

# Software Update
GET  /api/method/vehicle_maintenance.api.software_update.get_software_components
POST /api/method/vehicle_maintenance.api.software_update.submit_software_update
POST /api/method/vehicle_maintenance.api.software_update.mark_update_status
POST /api/method/vehicle_maintenance.api.software_update.submit_post_update_validation

# Breakdown
POST /api/method/vehicle_maintenance.api.breakdown.create_breakdown_job_card
POST /api/method/vehicle_maintenance.api.breakdown.start_remote_diagnosis
POST /api/method/vehicle_maintenance.api.breakdown.end_remote_diagnosis
POST /api/method/vehicle_maintenance.api.breakdown.log_travel_timestamps
POST /api/method/vehicle_maintenance.api.breakdown.submit_resolution
POST /api/method/vehicle_maintenance.api.breakdown.start_trial_trip
POST /api/method/vehicle_maintenance.api.breakdown.end_trial_trip
POST /api/method/vehicle_maintenance.api.breakdown.handover_vehicle

# Estimates
POST /api/method/vehicle_maintenance.api.estimate.create_estimate
POST /api/method/vehicle_maintenance.api.estimate.approve_estimate
POST /api/method/vehicle_maintenance.api.estimate.reject_estimate

# Inventory
POST /api/method/vehicle_maintenance.api.inventory.create_inventory_request
POST /api/method/vehicle_maintenance.api.inventory.allocate_inventory
POST /api/method/vehicle_maintenance.api.inventory.issue_inventory
POST /api/method/vehicle_maintenance.api.inventory.acknowledge_inventory

# Photos
POST /api/method/vehicle_maintenance.api.upload.upload_job_card_photo
POST /api/method/vehicle_maintenance.api.upload.upload_repair_item_photo

# Notifications
GET  /api/method/vehicle_maintenance.api.notifications.get_my_notifications?limit=20
POST /api/method/vehicle_maintenance.api.notifications.mark_notification_read
GET  /api/method/vehicle_maintenance.api.notifications.get_unread_count

# Vehicle
GET  /api/method/vehicle_maintenance.api.vehicle.get_vehicle_health?vehicle_name=
GET  /api/method/vehicle_maintenance.api.vehicle.get_vehicle_service_history?vehicle_name=

# Reports
GET  /api/method/vehicle_maintenance.api.reports.generate_pms_report?job_card_name=
GET  /api/method/vehicle_maintenance.api.reports.generate_repair_report?job_card_name=

# Feedback
POST /api/method/vehicle_maintenance.api.feedback.submit_feedback

# Realtime (Socket.IO)
Event: "sla_breach" → { job_card, breach_type, elapsed_mins }
Event: "job_card_update" → { job_card, new_state, updated_by }
Event: "notification" → { type, message, job_card }
```

---

## 14. PHASED IMPLEMENTATION ROADMAP

### Phase 1A: Foundation & Core DocTypes (Week 1-2)

```
Priority: CRITICAL — Nothing else works without this

1. Fix Job Card naming convention (autoname override)
2. Create master DocTypes: OEM, Bus System, Concern Code, Labor Code
3. Populate Bus System fixtures (17 PMS categories + 45 repair groups)
4. Create PMS Checksheet + PMS Checksheet Item DocTypes
5. Populate checksheet fixtures (20K/40K/80K from Excel data)
6. Expand Vehicle DocType (chassis, OEM, last PMS fields)
7. Expand Customer DocType (customer_code)
8. Expand Depot DocType (depot_code)
9. Add all new fields to Job Card DocType (breakdown, software, PMS, force close, photos, etc.)
10. Create Software Update Item + Post Update Validation child tables
11. Create VOC Entry child table
12. Create Force Close Log child table
13. Create Pending PMS Item child table
14. Update workflow JSON with new states (Force Closed, Reopened)
15. Run bench migrate, verify all DocTypes load correctly
```

### Phase 1B: PMS Flow — End to End (Week 2-3)

```
Priority: HIGH — Core business value

Backend:
1. Create PMS Inspection Result + PMS Inspection Result Item + PMS Category Score DocTypes
2. Implement checksheet auto-selection logic (odometer → A/B/C)
3. Implement PMS tolerance calculation
4. Implement health score calculation engine (pre + post)
5. Create inspection.py API module (5 endpoints)
6. Update Job Card controller for PMS-specific validation
7. Implement auto-population of repair/maintenance items from inspection
8. Write unit tests for score calculation

Frontend:
9. Build PMS Inspection Wizard page (section-by-section)
10. Build InspectionItem component (3-state toggle + photo)
11. Build ScoreGauge component
12. Build CategoryScoreBar component
13. Integrate inspection results into JobCardDetail page
14. Add checksheet display in Job Card creation wizard
```

### Phase 1C: Repair Flow — End to End (Week 3-4)

```
Priority: HIGH

Backend:
1. Expand Job Card Repair Item with concern_code, warranty fields
2. Create Inventory Request + Inventory Request Item DocTypes
3. Implement full inventory flow APIs (create, allocate, issue, acknowledge)
4. Implement customer approval link generation (token-based)
5. Implement ₹1000 threshold logic for auto-approval requirement
6. Update estimate.py for per-item approval/rejection

Frontend:
7. Build repair item entry page (group selection → part selection → photo)
8. Build customer approval page (public, no-login)
9. Build inventory request management page (Depot Manager)
10. Build photo upload component (camera + gallery)
11. Integrate repair flow into JobCardDetail
```

### Phase 1D: Notification & SLA Engine (Week 4-5)

```
Priority: HIGH

Backend:
1. Create Notification Log DocType (or use Frappe's built-in)
2. Implement all 12 notification triggers
3. Implement SLA check background job (runs every 5 mins)
4. Implement 80% warning + breach escalation logic
5. Implement customer approval 30-min escalation
6. Add scheduler hooks
7. Implement push notification integration (FCM for mobile)
8. Implement SMS integration (for critical alerts)

Frontend:
9. Build NotificationBell component
10. Build notification list page
11. Build SLABadge component with color coding
12. Add SLA indicators to dashboard and job card list
```

### Phase 1E: Software Update Flow (Week 5)

```
Backend:
1. Implement software_update.py API module (4 endpoints)
2. Add retry logic for failed updates
3. Implement post-update validation flow

Frontend:
4. Build Software Update flow page (step-by-step)
5. Build version comparison display
6. Integrate into JobCardDetail
```

### Phase 1F: Breakdown Flow (Week 5-6)

```
Backend:
1. Implement breakdown.py API module (8 endpoints)
2. Implement 30-min remote diagnosis timer
3. Implement travel timestamp tracking
4. Implement trial trip calculations
5. Implement total downtime calculation
6. Implement RCA report request flow

Frontend:
7. Build Breakdown Diagnosis flow page (timeline UI)
8. Build CountdownTimer component
9. Build trial trip tracking UI
10. Integrate into JobCardDetail
```

### Phase 1G: Reports & PDF Generation (Week 6-7)

```
1. Create HTML/Jinja2 templates matching all 3 sample report formats
2. Implement PMS Completion Report generator
3. Implement Repair Completion Report generator
4. Implement Software Update Report generator
5. Implement Breakdown Report generator
6. Implement Customer Summary Health Card
7. Build PDF viewer in frontend
8. Implement report approval flow (Depot Manager → Customer)
```

### Phase 1H: Dashboard & Analytics (Week 7)

```
1. Populate Depot Manager dashboard with real data
2. Populate Central Ops dashboard with cross-depot analytics
3. Populate Customer dashboard with fleet health
4. Add TAT analytics (average completion times by type)
5. Add cost analytics (estimated vs actual)
6. Add SLA compliance metrics
```

### Phase 1I: Audit Trail & Force Close (Week 7-8)

```
1. Implement Job Card Activity Log
2. Implement Force Close flow with severity matrix
3. Implement auto-creation of follow-up job cards (Critical severity)
4. Implement edit restrictions by state (from PRD amendments table)
5. Implement reopen flow with reason tracking
```

### Phase 1J: Testing & Polish (Week 8)

```
1. Unit tests for all score calculations
2. Unit tests for all workflow transitions
3. Unit tests for all API permission checks
4. Integration tests for complete PMS flow
5. Integration tests for complete Repair flow
6. Frontend testing on mobile viewports
7. Performance optimization (N+1 queries, caching)
8. Fixtures export and version control verification
```

---

## 15. MEMORY & SESSION CONTINUITY STRATEGY

### 15.1 Memory Files Structure

To ensure work can resume across sessions or accounts, the following memory files will be maintained:

```
vehicle_maintenance/.claude/
├── CLAUDE.md                    # Main project rules (already exists)
├── memory/
│   ├── PROJECT_STATE.md         # Current implementation status
│   ├── DECISIONS_LOG.md         # Architectural decisions made
│   ├── COMPLETED_WORK.md        # What's been built, file paths
│   ├── PENDING_WORK.md          # What remains to be done
│   ├── DATA_MODEL_SUMMARY.md    # Quick reference for all DocTypes
│   └── API_REFERENCE.md         # Quick reference for all endpoints
```

### 15.2 What Gets Saved After Each Session

After each coding session, the memory files are updated with:
- Which files were created/modified (with paths)
- Which DocTypes were created (with field lists)
- Which API endpoints are working
- Which frontend pages are complete
- Current blockers or issues
- Next steps to pick up from

### 15.3 Resumption Protocol

When starting a new session:
1. Read `PROJECT_STATE.md` to understand current status
2. Read `PENDING_WORK.md` to know what to do next
3. Read `DECISIONS_LOG.md` to respect prior architectural choices
4. Continue implementation from where it stopped

---

## 16. FILE-BY-FILE IMPLEMENTATION ORDER

This is the exact sequence Claude should follow when coding:

```
BATCH 1: Master DocTypes & Fixtures
──────────────────────────────────────
1.  vehicle_maintenance/fleet_service/doctype/oem/oem.json
2.  vehicle_maintenance/fleet_service/doctype/oem/oem.py
3.  vehicle_maintenance/fleet_service/doctype/bus_system/bus_system.json
4.  vehicle_maintenance/fleet_service/doctype/bus_system/bus_system.py
5.  vehicle_maintenance/fleet_service/doctype/concern_code/concern_code.json
6.  vehicle_maintenance/fleet_service/doctype/labor_code/labor_code.json
7.  vehicle_maintenance/fixtures/bus_system.json  (17 PMS + 45 Repair entries)
8.  vehicle_maintenance/fixtures/oem.json  (NaArNi, Azad, BharatBenz)

BATCH 2: PMS Checksheet DocTypes & Fixtures
──────────────────────────────────────
9.  vehicle_maintenance/fleet_service/doctype/pms_checksheet_item/pms_checksheet_item.json
10. vehicle_maintenance/fleet_service/doctype/pms_checksheet/pms_checksheet.json
11. vehicle_maintenance/fleet_service/doctype/pms_checksheet/pms_checksheet.py
12. vehicle_maintenance/fixtures/pms_checksheet_20k.json  (84 items)
13. vehicle_maintenance/fixtures/pms_checksheet_40k.json  (100+ items)
14. vehicle_maintenance/fixtures/pms_checksheet_80k.json  (110+ items)

BATCH 3: Inspection Result DocTypes
──────────────────────────────────────
15. vehicle_maintenance/fleet_service/doctype/pms_inspection_result_item/...json
16. vehicle_maintenance/fleet_service/doctype/pms_category_score/...json
17. vehicle_maintenance/fleet_service/doctype/pms_inspection_result/...json
18. vehicle_maintenance/fleet_service/doctype/pms_inspection_result/...py

BATCH 4: New Child Tables for Job Card
──────────────────────────────────────
19. vehicle_maintenance/fleet_service/doctype/software_update_item/...json
20. vehicle_maintenance/fleet_service/doctype/post_update_validation/...json
21. vehicle_maintenance/fleet_service/doctype/voc_entry/...json
22. vehicle_maintenance/fleet_service/doctype/force_close_log/...json
23. vehicle_maintenance/fleet_service/doctype/pending_pms_item/...json
24. vehicle_maintenance/fleet_service/doctype/labour_job_entry/...json

BATCH 5: Inventory DocTypes
──────────────────────────────────────
25. vehicle_maintenance/fleet_service/doctype/inventory_request_item/...json
26. vehicle_maintenance/fleet_service/doctype/inventory_request/...json
27. vehicle_maintenance/fleet_service/doctype/inventory_request/...py

BATCH 6: Modify Existing DocTypes
──────────────────────────────────────
28. vehicle_maintenance/fleet_service/doctype/vehicle/vehicle.json  (add 15+ fields)
29. vehicle_maintenance/fleet_service/doctype/customer/customer.json  (add customer_code)
30. vehicle_maintenance/fleet_service/doctype/depot/depot.json  (add depot_code)
31. vehicle_maintenance/fleet_service/doctype/job_card/job_card.json  (add 50+ fields)
32. vehicle_maintenance/fleet_service/doctype/job_card/job_card.py  (major rewrite)
33. vehicle_maintenance/fleet_service/doctype/job_card_repair_item/...json  (add fields)

BATCH 7: Workflow Update
──────────────────────────────────────
34. vehicle_maintenance/workflows/job_card_workflow.json  (add Force Closed, Reopened states)

BATCH 8: Backend Utilities
──────────────────────────────────────
35. vehicle_maintenance/utils/scoring.py       (health score calculation)
36. vehicle_maintenance/utils/naming.py        (custom JC naming)
37. vehicle_maintenance/utils/notifications.py (notification engine)
38. vehicle_maintenance/utils/sla.py           (SLA check background job)
39. vehicle_maintenance/utils/checksheet.py    (checksheet selection logic)

BATCH 9: New API Modules
──────────────────────────────────────
40. vehicle_maintenance/api/inspection.py      (5 endpoints)
41. vehicle_maintenance/api/software_update.py (4 endpoints)
42. vehicle_maintenance/api/breakdown.py       (8 endpoints)
43. vehicle_maintenance/api/reports.py         (5 endpoints)
44. vehicle_maintenance/api/notifications.py   (3 endpoints)
45. vehicle_maintenance/api/vehicle.py         (3 endpoints)
46. vehicle_maintenance/api/upload.py          (2 endpoints)
47. vehicle_maintenance/api/feedback.py        (2 endpoints)

BATCH 10: Extend Existing APIs
──────────────────────────────────────
48. vehicle_maintenance/api/job_card.py        (update existing endpoints)
49. vehicle_maintenance/api/inventory.py       (add 4 new endpoints)
50. vehicle_maintenance/api/dashboard.py       (real data integration)

BATCH 11: Hooks & Scheduler
──────────────────────────────────────
51. vehicle_maintenance/hooks.py               (add scheduler, new fixtures)

BATCH 12: Report Templates
──────────────────────────────────────
52. vehicle_maintenance/templates/pms_report.html
53. vehicle_maintenance/templates/repair_report.html
54. vehicle_maintenance/templates/sw_update_report.html
55. vehicle_maintenance/templates/breakdown_report.html
56. vehicle_maintenance/templates/customer_health_card.html

BATCH 13: Frontend Components
──────────────────────────────────────
57. frontend/src/components/PhotoCapture.vue
58. frontend/src/components/ScoreGauge.vue
59. frontend/src/components/CategoryScoreBar.vue
60. frontend/src/components/InspectionItem.vue
61. frontend/src/components/RepairItemCard.vue
62. frontend/src/components/TimelineStep.vue
63. frontend/src/components/ApprovalToggle.vue
64. frontend/src/components/CountdownTimer.vue
65. frontend/src/components/NotificationBell.vue
66. frontend/src/components/VehicleHealthCard.vue
67. frontend/src/components/SLABadge.vue
68. frontend/src/components/SearchableSelect.vue

BATCH 14: Frontend Pages
──────────────────────────────────────
69. frontend/src/pages/PMSInspection.vue       (PMS inspection wizard)
70. frontend/src/pages/RepairEntry.vue          (Repair items entry)
71. frontend/src/pages/SoftwareUpdateFlow.vue   (SW update step-by-step)
72. frontend/src/pages/BreakdownDiagnosis.vue   (Breakdown timeline UI)
73. frontend/src/pages/CustomerApproval.vue     (Public approval page)
74. frontend/src/pages/CustomerTracking.vue     (Public tracking page)
75. frontend/src/pages/FeedbackForm.vue         (Post-service feedback)
76. frontend/src/pages/InventoryRequests.vue    (DM inventory management)
77. frontend/src/pages/VehicleHealth.vue        (Vehicle health card page)
78. frontend/src/pages/Notifications.vue        (Notification list)

BATCH 15: Modify Existing Frontend
──────────────────────────────────────
79. frontend/src/pages/JobCardNew.vue           (expand for all 4 types)
80. frontend/src/pages/JobCardDetail.vue        (add type-specific sections)
81. frontend/src/pages/Dashboard.vue            (add SLA indicators)
82. frontend/src/pages/TechnicianInspection.vue (replace skeleton)
83. frontend/src/pages/CustomerJobCard.vue      (expand with tracking)
84. frontend/src/router.js                      (add all new routes)
85. frontend/src/composables/useInspection.js   (expand)
86. frontend/src/utils/permissions.js           (expand)

BATCH 16: Tests
──────────────────────────────────────
87. vehicle_maintenance/fleet_service/doctype/job_card/test_job_card.py
88. vehicle_maintenance/tests/test_scoring.py
89. vehicle_maintenance/tests/test_sla.py
90. vehicle_maintenance/tests/test_inspection_api.py
91. vehicle_maintenance/tests/test_inventory_flow.py
```

---

## APPENDIX A: PMS CHECKSHEET DATA REFERENCE

### Check Sheet A — 20,000 KM (84 items)
Sections: Bus Exterior (8), Lubrication/Coolant/Oil (10), Driver Cabin (3), After Ignition (13), Bus Interiors (10), Traction Motor (1), Air Compressor (5), Steering System (8), Propeller Shaft (2), Front/Rear Axles (12), Brakes & Pneumatic (7), LV Battery (5), Suspension (1)

### Check Sheet B — 40,000 KM (100+ items)
Adds: HV Lines inspection (6), MCU checks (5), Power Battery checks (4), extended Steering (12), extended Air Compressor (6)

### Check Sheet C — 80,000 KM (110+ items)
Adds: King Pin play check, all-bolts tightening, insulation resistance measurements, comprehensive axle inspection

### Bus Systems for Scoring (14 from Sample Report)
Outer Body, Brake System, Maintenance Consumables, Bus Interior, Driving Control System, Traction Motor System, HV Power Supply, Air Compressor, Steering System, Axle System, LV Electrical, Suspension System, Tyres & Wheels, Others, EV Charging, Air Conditioning

---

## APPENDIX B: JOB CARD NAMING EXAMPLES

```
Format: {CustomerCode}-{DepotCode}-{YYMMDD}-{Serial}

Examples from sample reports:
  ZB-GGN-210326-001    (Zingbus, Gurgaon, 21 Mar 2026, 1st card of day)
  ZB-GGN-210326-002    (Zingbus, Gurgaon, 21 Mar 2026, 2nd card of day)
  ZB-GGN-210326-003    (Zingbus, Gurgaon, 21 Mar 2026, 3rd card of day)

From BharatBenz service history:
  8KM1QC0216           (Different format — OEM dealer system)
  JBC20016B2405453     (BharatBenz internal)
```

---

## APPENDIX C: CONCERN CODE MASTER DATA (From Service History)

```
GC4045  - DIFFERENTIAL OIL LEAK
GC1095  - FUEL LEAK FROM WATER SEPARATOR
GC3006  - KING PIN BREAKAGE
GC1120  - ENGINE OIL LEAKAGE FROM OIL SUMP
GC3054  - STABILIZER BAR BEND
GC1048  - ENGINE NOT STARTING
GC5037  - AIR LEAKAGE FROM RELAY VALVE
GC2025  - GEAR ROD LINKAGES WORN OUT
GC4006  - REAR AXLE HOUSING CRACK
GC4054  - REAR AXLE HOUSING CRACK - RT2
GC5053  - DUAL BRAKE VALVE LEAKY
GC5116  - V ROD BROKEN
GC7044  - WINDOW LIFT NOT WORKING
GC2084  - GEAR BOX MOUNTING CRACK
GC4007  - AXLE SHAFT
GC7065  - DOOR LOCK NOT WORKING
GC2029  - TRANSMISSION NOISY
GC1090  - SILENCER PIPE LEAKAGE
GC9019  - AC NOT WORKING
GC9002  - HEAD LAMPS NOT WORKING
GC2004  - CLUTCH PEDAL HARD TO DEPRESS
GC4001  - DIFFERENTIAL NOISY
GC2002  - EXCESS CLUTCH PEDAL PLAY
GC1017  - LOW PICK UP
GC4002  - AXLE SHAFT BREAKAGE @ FLANGE END
GC1046  - ENGINE MOUNTING LOOSEN
GC1123  - ENGINE OIL TOP UP
GC1130  - ENGINE OIL LEAK FROM OIL PRESSURE SENSOR
GC1047  - ENGINE MOUNTING RUBBER FAILURE
GC8001  - LOAD BODY SUB FRAME CRACKED

PM0001  - PDI (Pre-Delivery Inspection)
PM0003  - 1ST SERVICE
PM0004  - 2ND SERVICE
PM0005  - 3RD SERVICE
PM0006  - 4TH SERVICE
PM0010  - SCHEDULED MAINTENANCE

DHS001  - DIGITAL HEALTH SCAN
TS0020  - LOAD BODY CRACKED
TS0034  - OTHER COMPLAINTS ON HYDRAULIC SYSTEM
```

---

---

## APPENDIX D: VERIFICATION FIXES — Weak Coverage Items

### D.1 Job Card Deletion Prevention (PRD: "Cards can only be voided with reason; never deleted")

**Implementation:**
- In `job_card.json`: Set `"allow_delete": 0` (Frappe-level block)
- In `job_card.py`: Override `before_delete` hook → `frappe.throw(_("Job Cards cannot be deleted. Use Force Close or Void instead."))`
- Add a `is_voided` Check field + `void_reason` Text field to Job Card
- Add a "Void Job Card" action (Depot Manager only) that sets `is_voided = 1` and requires a reason
- Voided cards are hidden from default list views but remain in the database

### D.2 Edit Restrictions by Job Card State (PRD Amendments Table)

**Implementation in `job_card.py` validate method:**

```python
def _validate_edit_permissions_by_state(self):
    """Enforce PRD edit restrictions matrix."""
    if self.has_value_changed("workflow_state"):
        return  # Workflow transitions are handled separately

    old_doc = self.get_doc_before_save()
    if not old_doc:
        return

    state = self.workflow_state
    user_roles = frappe.get_roles()
    changed_fields = [f for f in self.meta.fields if self.has_value_changed(f.fieldname)]

    if not changed_fields:
        return

    if state == "Open":
        # SE and Depot Manager can edit
        allowed = {"Service Engineer", "Depot Manager"}
        if not (set(user_roles) & allowed):
            frappe.throw(_("Only SE or Depot Manager can edit job cards in Open state"))

    elif state in ("WIP", "Awaiting Customer Approval", "Awaiting Parts", "Parts Fitted"):
        # SE only (Technician cannot edit header fields)
        header_fields = {"vehicle", "customer", "depot", "job_card_type", "service_type",
                        "assigned_service_engineer", "service_contract"}
        if "Technician" in user_roles and not ("Service Engineer" in user_roles):
            changed_header = [f for f in changed_fields if f.fieldname in header_fields]
            if changed_header:
                frappe.throw(_("Technicians cannot edit header fields during WIP"))
        if not ({"Service Engineer", "Depot Manager"} & set(user_roles)):
            frappe.throw(_("Only SE can edit job cards in {0} state").format(state))

    elif state == "Closed":
        # Depot Manager only, requires reason
        if "Depot Manager" not in user_roles:
            frappe.throw(_("Only Depot Manager can edit closed job cards"))
        if not self.reopen_reason:
            frappe.throw(_("A reason is required when editing a closed job card"))
        # Notify Central Ops
        notify("closed_card_edited", self)

    elif state == "Force Closed":
        frappe.throw(_("Force-closed job cards cannot be edited"))
```

### D.3 Job Card Maintenance Item — Consumables Photo Fields

**Fields to add to `job_card_maintenance_item.json`:**

```
- consumable_type         Select      [Top Up, Full Replacement, Cleaning, Filter Change]
- volume_filled           Float       (for oil/coolant — litres)

For Replacement:
- drained_photo           Attach Image  (photo of drained oil/coolant)
- new_product_photo       Attach Image  (photo of new oil/coolant with volume)

For Cleaning/Filter:
- pre_cleaning_photo      Attach Image
- post_cleaning_photo     Attach Image

- pre_photo               Attach Image  (general pre-work photo)
- post_photo              Attach Image  (general post-work photo)
```

**UI Behavior:**
- If `consumable_type = "Full Replacement"` and item is oil/coolant → show drained_photo + new_product_photo + volume_filled
- If `consumable_type = "Cleaning"` or `"Filter Change"` → show pre_cleaning_photo + post_cleaning_photo
- Always show pre_photo and post_photo

---

*This plan is the complete technical blueprint for implementing the NaArNi Vehicle Maintenance Platform. Every section maps directly to PRD requirements, sample reports, and the existing codebase. Follow the phased roadmap and file-by-file order for systematic implementation.*
