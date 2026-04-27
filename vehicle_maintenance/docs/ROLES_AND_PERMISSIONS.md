# Roles & Permissions — Who Can Do What

Quick reference for the EV-bus Vehicle Maintenance platform. All roles are
created automatically by `bench migrate` (via the `APP_ROLES` list in
[hooks.py](../vehicle_maintenance/hooks.py)) and any DocType-level perms below
can be tightened or relaxed at runtime via **Frappe Desk → Role Permission
Manager**.

> **Demo password** for every seeded user: `Welcome@123`

---

## At-a-glance matrix

| Role | UI | Create Job Card | Inspect / Fill Checklist | Approve Customer Estimate | Sign-off Closure | Edit Master Data | View Reports |
|------|-----|:-:|:-:|:-:|:-:|:-:|:-:|
| **Depot Manager**          | Frappe Desk    | ✅ | — | — | ✅ | ✅ | ✅ |
| **Service Engineer**       | Vue SPA        | ✅ | ✅ | review | ✅ | — | partial |
| **Technician**             | Vue SPA        | ✅ (PMS only) | ✅ | — | request | — | own |
| **Battery Specialist**     | Vue SPA        | — | HV battery sections only | — | — | — | own |
| **Charging Infra Tech**    | Vue SPA        | — | charging socket section only | — | — | — | own |
| **Quality Inspector**      | Vue SPA / Desk | — | — | — | ✅ (final QC) | — | ✅ |
| **Central Ops**            | Frappe Desk    | — | — | — | escalate | — | ✅ |
| **Aftersales Eng**         | Frappe Desk    | — | — | — | breakdown only | — | ✅ |
| **N. Maintenance Head**    | Frappe Desk    | — | — | — | force-close ✅ | — | ✅ |
| **Customer**               | Vue SPA        | request | — | ✅ | reopen ✅ | — | own fleet |
| **Sales Executive**        | Frappe Desk    | — | — | — | — | Lead/CRM only | CRM |

Legend: ✅ allowed · — not allowed · *italics* = scope-limited.

---

## Per-role detail

### Depot Manager — `Depot Manager`
- Allocates Job Cards to Service Engineers and Technicians.
- Edits master data: Vehicles, Customers, Depots, Parts, Part Groups, Service Contracts.
- Owns Inventory Request approval (parts > ₹1,000 require their nod).
- Force-close authority for **Minor** and **Major** severities.
- *Demo user:* `depot.manager@demo.local`

### Service Engineer — `Service Engineer`
- Opens & runs Job Cards from start (`Open` → `WIP` → … → `Verification Pending`).
- Performs vehicle inspection in the Vue SPA; auto-assigned as the SE on cards they create.
- Reviews customer estimate decisions and routes WIP between **Awaiting Parts** and **Closure from Technician**.
- Cannot approve their own card's closure (Quality Inspector / Aftersales does that for breakdowns).
- *Demo user:* `service.engineer@demo.local`

### Technician — `Technician`
- Picks up assigned Job Cards on the Vue SPA.
- Fills the structured PMS checklist (`/service-portal/job-card/:jobCard/pms-checklist`) — per-section timing is captured automatically.
- Logs labor, requests inventory, attaches photos, marks items repaired.
- Requests closure once all items are complete.
- *Demo user:* `technician@demo.local`

### Battery Specialist — `Battery Specialist` *(EV)*
- Subset of Technician with HV-Battery focus.
- Owns sections involving HV battery, BMS, traction motor, regen module.
- Can be paired with Technician role (the seed user has both).
- Should be tagged on any Job Card flagged with HV-BAT-* fault codes.
- *Demo user:* `battery.specialist@demo.local`

### Charging Infra Tech — `Charging Infra Tech` *(EV)*
- Subset of Technician with EV charging focus.
- Owns the EV Charging Socket section (PMS Sheet A · XV) and depot EVSE upkeep.
- Diagnoses CCS2 / AC inlet failures, contactor faults, and DC-DC issues.
- *Demo user:* `charging.tech@demo.local`

### Quality Inspector — `Quality Inspector` *(EV)*
- Final QC pass after Verification Pending.
- Reviews inspection responses, photo evidence, and section timings vs. std.
- Has read access to the `PMS Section Timing` report so anomalies surface fast.
- Closes the Job Card or kicks back to WIP.
- *Demo user:* `qa.inspector@demo.local`

### Central Ops — `Central Ops`
- Cross-depot visibility. Reads everything; writes only escalations.
- Owns SLA-breach escalation workflows.
- Can reopen Closed cards on customer dispute.
- *Demo user:* `central.ops@demo.local`

### Aftersales Eng — `Aftersales Eng`
- Owns Breakdown follow-up: RCA notes, customer communication, force-close on breakdown.
- *Demo user:* `aftersales@demo.local`

### N. Maintenance Head — `N. Maintenance Head`
- Network-level authority. Sole approver for **Critical**-severity force-closes.
- Reads everything; rare write actions.

### Customer — `Customer`
- Logs in via phone (no email) on the Vue SPA.
- Sees only their own fleet (`my-fleet`).
- Approves / rejects estimates, gives post-service feedback, can reopen Closed cards.
- *Demo user:* `customer@demo.local`

### Sales Executive — `Sales Executive`
- CRM-only: Leads, Lead Activities, Reminders.
- No access to Job Cards, Vehicles (beyond linked-from-Lead read).

---

## Where the rules live

- **DocType permissions** — declared in each DocType's JSON `permissions` array (e.g., [vehicle.json](../vehicle_maintenance/fleet_service/doctype/vehicle/vehicle.json), [oem.json](../vehicle_maintenance/fleet_service/doctype/oem/oem.json)). These are the static defaults.
- **Workflow transition gates** — declared in [job_card.py](../vehicle_maintenance/fleet_service/doctype/job_card/job_card.py) `VALID_TRANSITIONS`. State changes are enforced server-side, not just in the UI.
- **Force-close authority** — `FORCE_CLOSE_AUTHORITY` map in [job_card.py](../vehicle_maintenance/fleet_service/doctype/job_card/job_card.py).
- **API permission checks** — every `@frappe.whitelist()` method begins with `frappe.has_permission(...)`. Nothing trusts the frontend.

To change a role's permissions at runtime without a deploy: **Desk → Role Permission Manager → pick DocType → tweak the role row**. Custom DocPerms are version-controlled via the `Custom DocPerm` fixture entry in [hooks.py](../vehicle_maintenance/hooks.py), so permanent changes get committed.

---

## Adding a new role

1. Add the role name to `APP_ROLES` in [hooks.py](../vehicle_maintenance/hooks.py) — `bench migrate` creates it on every site.
2. Add a `{role: ..., read/write/create/delete: 1}` row to the relevant DocType JSON `permissions` arrays.
3. (Optional) Add a demo user in `DEMO_USERS` in [seed.py](../vehicle_maintenance/fleet_service/seed.py) with the role attached.
4. Update the matrix at the top of this doc.
5. Run `bench --site <site> migrate` and re-seed with `bench --site <site> execute vehicle_maintenance.fleet_service.seed.seed_demo_data`.

---

## Re-seed commands

```bash
# Wipe and re-seed (idempotent — safe to run repeatedly)
bench --site <site> execute vehicle_maintenance.fleet_service.seed.wipe_demo_data
bench --site <site> execute vehicle_maintenance.fleet_service.seed.seed_demo_data
```

Seeded data after running the seeder:
- **9 demo users** (1 per role + 1 extra DM is reused from the legacy set)
- **6 OEMs** (Tata Motors, Ashok Leyland, Olectra Greentech, BYD India, JBM Auto, PMI Electro)
- **4 customers**, **4 depots**
- **8 EV buses** spanning the 4 customers
- **11 parts** across **11 part groups**, EV-bus-skewed
- **10 Job Cards** in mixed states (PMS, Repair, Breakdown, Software Update, QA-pending)
