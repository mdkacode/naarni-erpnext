"""Demo data seeder for Fleet Service.

Idempotent — safe to run multiple times. Creates one user per role, master
data, and a spread of Job Cards in varying states so the FSM/SLA engine has
something to chew on.

Run:
    bench --site <site-name> execute vehicle_maintenance.fleet_service.seed.seed_demo_data

Wipe demo (re-runnable slate):
    bench --site <site-name> execute vehicle_maintenance.fleet_service.seed.wipe_demo_data
"""

from __future__ import annotations

import frappe
from frappe.utils import add_to_date, now_datetime

DEMO_PASSWORD = "Welcome@123"

# ── Users to create, one per role (+ one Depot Manager for the Closed-lock test) ──
DEMO_USERS: list[dict] = [
    {
        "email": "depot.manager@demo.local",
        "first_name": "Dinesh",
        "last_name": "DepotManager",
        "roles": ["Depot Manager"],
    },
    {
        "email": "service.engineer@demo.local",
        "first_name": "Sara",
        "last_name": "ServiceEngineer",
        "roles": ["Service Engineer"],
    },
    {
        "email": "technician@demo.local",
        "first_name": "Tarun",
        "last_name": "Technician",
        "roles": ["Technician"],
    },
    {
        "email": "central.ops@demo.local",
        "first_name": "Chandra",
        "last_name": "CentralOps",
        "roles": ["Central Ops"],
    },
    {
        "email": "aftersales@demo.local",
        "first_name": "Ayesha",
        "last_name": "Aftersales",
        "roles": ["Aftersales Eng"],
    },
    {
        "email": "customer@demo.local",
        "first_name": "Carlos",
        "last_name": "Customer",
        "roles": ["Customer"],
    },
]

DEMO_CUSTOMERS: list[dict] = [
    {
        "customer_name": "ACME Logistics",
        "customer_code": "ACME",
        "customer_type": "Company",
        "mobile_no": "+91-9000000001",
        "email_id": "ops@acme.demo",
        "city": "Bengaluru",
        "state": "Karnataka",
    },
    {
        "customer_name": "Bharat Transit",
        "customer_code": "BHT",
        "customer_type": "Company",
        "mobile_no": "+91-9000000002",
        "email_id": "ops@bharat.demo",
        "city": "Mumbai",
        "state": "Maharashtra",
    },
]

DEMO_DEPOTS: list[dict] = [
    {
        "depot_name": "Bengaluru Central Workshop",
        "location_code": "BLR01",
        "city": "Bengaluru",
        "state": "Karnataka",
    },
    {
        "depot_name": "Mumbai North Depot",
        "location_code": "MUM01",
        "city": "Mumbai",
        "state": "Maharashtra",
    },
]

DEMO_PART_GROUPS: list[dict] = [
    {"part_group_name": "Filters", "bus_system": "Powertrain"},
    {"part_group_name": "Brakes", "bus_system": "Brakes"},
    {"part_group_name": "Batteries", "bus_system": "Battery & BMS"},
]

DEMO_PARTS: list[dict] = [
    {
        "part_code": "FLT-AIR-001",
        "part_name": "Air Filter 12in",
        "part_group": "Filters",
        "stock_uom": "Nos",
        "standard_cost": 450,
        "manufacturer": "Bosch",
    },
    {
        "part_code": "BRK-PAD-F01",
        "part_name": "Brake Pad Front Set",
        "part_group": "Brakes",
        "stock_uom": "Set",
        "standard_cost": 2800,
        "manufacturer": "TVS Brakes",
    },
    {
        "part_code": "BAT-12V-100",
        "part_name": "Battery 12V 100Ah",
        "part_group": "Batteries",
        "stock_uom": "Nos",
        "standard_cost": 8500,
        "manufacturer": "Exide",
    },
]

DEMO_VEHICLES: list[dict] = [
    {
        "registration_number": "KA01AB1234",
        "make_model": "Tata Starbus EV",
        "year_of_manufacture": 2023,
        "fuel_type": "Electric",
        "customer": "ACME Logistics",
    },
    {
        "registration_number": "MH12CD5678",
        "make_model": "Ashok Leyland Circuit",
        "year_of_manufacture": 2022,
        "fuel_type": "Electric",
        "customer": "Bharat Transit",
    },
]

# Each tuple: (customer, depot, vehicle, job_card_type, hours_ago_opened, state, description)
DEMO_JOB_CARDS: list[dict] = [
    {
        "customer": "ACME Logistics",
        "depot": "Bengaluru Central Workshop",
        "vehicle": "KA01AB1234",
        "job_card_type": "PMS + Repair",
        "hours_ago_opened": 0.5,           # Well within SLA
        "workflow_state": "WIP",
        "description": "Fresh PMS — should be green.",
        "complaint_description": "Routine 15,000 km PMS checks requested.",
    },
    {
        "customer": "ACME Logistics",
        "depot": "Bengaluru Central Workshop",
        "vehicle": "KA01AB1234",
        "job_card_type": "Only Repair",
        "hours_ago_opened": 3.3,           # Past 80% warning (3.2h of 4h)
        "workflow_state": "WIP",
        "description": "Should trigger SLA warning on next cron tick.",
        "complaint_description": "Grinding noise from front brakes.",
    },
    {
        "customer": "Bharat Transit",
        "depot": "Mumbai North Depot",
        "vehicle": "MH12CD5678",
        "job_card_type": "PMS + Repair",
        "hours_ago_opened": 5.0,           # Past breach (4h)
        "workflow_state": "Awaiting Parts",
        "description": "Should trigger SLA breach on next cron tick.",
        "complaint_description": "Full PMS + HVAC complaint from driver.",
    },
    {
        "customer": "Bharat Transit",
        "depot": "Mumbai North Depot",
        "vehicle": "MH12CD5678",
        "job_card_type": "Breakdown",
        "hours_ago_opened": 0.7,           # Past 30-min breach
        "workflow_state": "WIP",
        "description": "Breakdown past 30-min target; should fire immediate escalation.",
        "complaint_description": "Vehicle stalled on highway, towing arranged.",
    },
]


# ───────────────────────────────────────────────────────────
# Public entrypoints
# ───────────────────────────────────────────────────────────

def seed_demo_data() -> None:
    """Top-level: create everything. Idempotent."""
    print("\n=== Fleet Service demo seeder ===")
    _create_roles()
    _create_users()
    _create_customers()
    _create_depots()
    _create_part_groups()
    _create_parts()
    _create_vehicles()
    _create_job_cards()
    _create_inventory_requests()
    frappe.db.commit()
    _print_credentials()


def wipe_demo_data() -> None:
    """Delete seeded demo records (vehicle, job cards, inv requests, masters, users).

    Job Cards can't be deleted via `frappe.delete_doc` because `on_trash` blocks
    it — we use `frappe.db.sql` to hard-delete directly for the demo dataset.
    """
    print("\n=== Wiping demo data ===")
    # Inventory requests (FK to job cards)
    for name in frappe.get_all("Inventory Request", pluck="name"):
        frappe.delete_doc("Inventory Request", name, ignore_permissions=True, force=True)
    # Job Cards: bypass on_trash guard for demo wipe
    for name in frappe.get_all("Job Card", pluck="name"):
        frappe.db.sql("DELETE FROM `tabJob Card` WHERE name=%s", name)
    # Vehicles
    for v in DEMO_VEHICLES:
        if frappe.db.exists("Vehicle", v["registration_number"]):
            frappe.delete_doc("Vehicle", v["registration_number"],
                              ignore_permissions=True, force=True)
    # Parts
    for p in DEMO_PARTS:
        if frappe.db.exists("Part", p["part_code"]):
            frappe.delete_doc("Part", p["part_code"],
                              ignore_permissions=True, force=True)
    # Part Groups
    for g in DEMO_PART_GROUPS:
        if frappe.db.exists("Part Group", g["part_group_name"]):
            frappe.delete_doc("Part Group", g["part_group_name"],
                              ignore_permissions=True, force=True)
    # Depots
    for d in DEMO_DEPOTS:
        if frappe.db.exists("Depot", d["depot_name"]):
            frappe.delete_doc("Depot", d["depot_name"],
                              ignore_permissions=True, force=True)
    # Customers
    for c in DEMO_CUSTOMERS:
        if frappe.db.exists("Customer", c["customer_name"]):
            frappe.delete_doc("Customer", c["customer_name"],
                              ignore_permissions=True, force=True)
    # Users
    for u in DEMO_USERS:
        if frappe.db.exists("User", u["email"]):
            frappe.delete_doc("User", u["email"], ignore_permissions=True, force=True)
    frappe.db.commit()
    print("Wipe complete.\n")


# ───────────────────────────────────────────────────────────
# Internals
# ───────────────────────────────────────────────────────────

def _create_roles() -> None:
    for role in [
        "Depot Manager", "Service Engineer", "Technician",
        "Central Ops", "Aftersales Eng", "Customer",
    ]:
        if not frappe.db.exists("Role", role):
            frappe.get_doc({"doctype": "Role", "role_name": role}).insert(
                ignore_permissions=True
            )
            print(f"  + Role: {role}")


def _create_users() -> None:
    for u in DEMO_USERS:
        if frappe.db.exists("User", u["email"]):
            continue
        user = frappe.get_doc({
            "doctype": "User",
            "email": u["email"],
            "first_name": u["first_name"],
            "last_name": u["last_name"],
            "send_welcome_email": 0,
            "new_password": DEMO_PASSWORD,
            "enabled": 1,
            "user_type": "System User",
            "roles": [{"role": r} for r in u["roles"]],
        })
        user.insert(ignore_permissions=True)
        print(f"  + User: {u['email']}  roles={u['roles']}")


def _create_customers() -> None:
    for c in DEMO_CUSTOMERS:
        if frappe.db.exists("Customer", c["customer_name"]):
            continue
        frappe.get_doc({"doctype": "Customer", **c}).insert(ignore_permissions=True)
        print(f"  + Customer: {c['customer_name']}  code={c['customer_code']}")


def _create_depots() -> None:
    for d in DEMO_DEPOTS:
        if frappe.db.exists("Depot", d["depot_name"]):
            continue
        frappe.get_doc({"doctype": "Depot", **d}).insert(ignore_permissions=True)
        print(f"  + Depot: {d['depot_name']}  code={d['location_code']}")


def _create_part_groups() -> None:
    for g in DEMO_PART_GROUPS:
        if frappe.db.exists("Part Group", g["part_group_name"]):
            continue
        frappe.get_doc({"doctype": "Part Group", **g}).insert(ignore_permissions=True)
        print(f"  + Part Group: {g['part_group_name']}")


def _create_parts() -> None:
    for p in DEMO_PARTS:
        if frappe.db.exists("Part", p["part_code"]):
            continue
        frappe.get_doc({"doctype": "Part", "is_active": 1, **p}).insert(
            ignore_permissions=True
        )
        print(f"  + Part: {p['part_code']}  {p['part_name']}")


def _create_vehicles() -> None:
    for v in DEMO_VEHICLES:
        if frappe.db.exists("Vehicle", v["registration_number"]):
            continue
        frappe.get_doc({"doctype": "Vehicle", **v}).insert(ignore_permissions=True)
        print(f"  + Vehicle: {v['registration_number']}  {v['make_model']}")


def _create_job_cards() -> None:
    """Create spread of Job Cards with backdated opened_at to exercise SLA states."""
    now = now_datetime()
    for spec in DEMO_JOB_CARDS:
        opened_at = add_to_date(now, hours=-spec["hours_ago_opened"])
        # Skip if an identical job card already exists (rough dedupe by vehicle+type+description)
        existing = frappe.get_all(
            "Job Card",
            filters={
                "vehicle": spec["vehicle"],
                "job_card_type": spec["job_card_type"],
                "complaint_description": spec["complaint_description"],
            },
            pluck="name",
        )
        if existing:
            continue

        jc = frappe.get_doc({
            "doctype": "Job Card",
            "customer": spec["customer"],
            "depot": spec["depot"],
            "vehicle": spec["vehicle"],
            "job_card_type": spec["job_card_type"],
            "priority": "Medium",
            "job_card_date": now.date(),
            "odometer_reading": 12000,
            "complaint_description": spec["complaint_description"],
            "workflow_state": spec["workflow_state"],
        })
        jc.insert(ignore_permissions=True)

        # Backdate opened_at so SLA monitor sees realistic elapsed times.
        frappe.db.set_value(
            "Job Card", jc.name, "opened_at", opened_at, update_modified=False
        )
        print(f"  + Job Card: {jc.name}  type={spec['job_card_type']}  "
              f"state={spec['workflow_state']}  opened {spec['hours_ago_opened']}h ago")


def _create_inventory_requests() -> None:
    """One inventory request per Job Card for part-flow testing."""
    cards = frappe.get_all(
        "Job Card",
        fields=["name", "workflow_state"],
        filters={"workflow_state": ["in", ("WIP", "Awaiting Parts")]},
    )
    parts = [p["part_code"] for p in DEMO_PARTS]
    if not parts:
        return
    for i, card in enumerate(cards):
        # Skip if this card already has an inventory request (idempotency).
        if frappe.db.exists("Inventory Request", {"job_card_ref": card["name"]}):
            continue
        ir = frappe.get_doc({
            "doctype": "Inventory Request",
            "job_card_ref": card["name"],
            "part": parts[i % len(parts)],
            "quantity": 2,
            "urgency_level": "High" if i % 2 else "Medium",
            "status": "Requested",
        })
        ir.insert(ignore_permissions=True)
        print(f"  + Inventory Request: {ir.name}  job={card['name']}  part={ir.part}")


def _print_credentials() -> None:
    print("\n───────────────── LOGIN CREDENTIALS ─────────────────")
    print(f"Password for every demo user:  {DEMO_PASSWORD}")
    print("─────────────────────────────────────────────────────")
    print(f"{'Role':<18} {'Email':<32} {'UI':<20}")
    print("─" * 74)
    ui_map = {
        "Depot Manager": "Frappe Desk",
        "Service Engineer": "Vue SPA",
        "Technician": "Vue SPA",
        "Central Ops": "Frappe Desk",
        "Aftersales Eng": "Frappe Desk",
        "Customer": "Vue SPA",
    }
    for u in DEMO_USERS:
        role = u["roles"][0]
        print(f"{role:<18} {u['email']:<32} {ui_map.get(role, '-'):<20}")
    print("─────────────────────────────────────────────────────")
    print("Admin:  use your existing Administrator account (for Desk admin tasks)\n")
