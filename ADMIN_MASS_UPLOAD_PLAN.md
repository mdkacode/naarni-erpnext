# Frappe Admin Completeness + Mass Upload — Plan

> Goal: a polished, easy Frappe Desk admin for the B2B commercial-bus-EV fleet, with
> **bulletproof CSV/XLSX mass upload** on every master. Audit done — see findings below.
> Careful/responsible: enable bulk import on **masters** (fleet onboarding data), NOT on
> workflow-driven transactional docs (Job Card, Service Estimate, Inventory Request, …)
> where bulk insert would create broken/stateless records.

## Audit findings
- **Mass upload OFF everywhere** — all 30 non-child doctypes have `allow_import: 0`, so they
  don't even show in Frappe's Data Import tool. ← root cause of "mass upload missing".
- **No Desk workspace** — no Fleet Service landing page (shortcuts, number cards, charts).
- **No saved reports** — only `api/dashboard.py` endpoints + a seeded alerts dashboard patch.
- Print formats exist (Health Card, PMS). Naming is import-friendly. Child tables correct.
- Thin list views on OEM / Part Group / Service Contract.

## Phase A — Enable mass upload on all masters (CRITICAL, low-risk) ✅ start here
Set `allow_import: 1` on the data-entry masters (Frappe auto-generates the CSV/XLSX template +
import UI once this is on):
- Fleet identity: **Vehicle, Customer, Depot, OEM**
- Parts/catalog: **Part, Part Group, Subsystem, Complaint Catalog, Fault Code, Observation Template**
- Telemetry: **Telemetry Parameter, Telemetry Code**
- Commercial/CRM: **Service Contract, Lead, Lead Source, Lead Status**
- Alerts config: **Alert Type, Notification Channel, Alert Channel Preference, Alert Subscription**

Deliberately NOT enabled (flow-generated — bulk insert = broken state):
Job Card, Service Estimate, Inventory Request, Service Ticket, Alert Event, Vehicle Health Card,
Job Card Closure Record, Customer Feedback, Push Token.

## Phase B — Mass-upload UX (templates + safe order + workspace shortcut)
- Document the **dependency-safe import order** (Link fields must resolve):
  `OEM → Part Group → Depot → Customer → Subsystem → Telemetry Parameter`
  then `Vehicle (OEM,Depot,Customer) · Part (Part Group) · Complaint/Fault/Observation (Subsystem)
  · Telemetry Code (Parameter) · Service Contract (Customer)`.
- Ship **sample CSV templates** in the repo for the big three (Vehicle, Part, Customer).
- Add a **Fleet Service workspace** with a "Data Import & Setup" card linking Data Import per
  doctype + shortcuts to every master.

## Phase C — Admin polish
- Enrich `in_list_view` on thin masters (OEM, Part Group, Service Contract, Telemetry Parameter).
- Workspace number cards: Open Job Cards, SLA breaches today, Vehicles, Open Tickets.
- Quick filters / standard filters where useful.

## Phase D — Reports
- Saved reports for ops: Job Cards by state, TAT adherence (wrap existing `tat_adherence_report`),
  Vehicles by depot, Parts consumption, Open inventory requests.

## Rollout
Each phase is JSON/fixtures only (no risky logic). Deploy via develop → Azure `bench migrate`
(reloads doctypes + imports workspace fixture). Verify the Data Import tool lists each master and
a sample CSV imports cleanly.
