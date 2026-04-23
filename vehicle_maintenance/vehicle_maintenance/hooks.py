app_name = "vehicle_maintenance"
app_title = "Vehicle Maintenance"
app_publisher = "Your Name"
app_description = "Vehicle Maintenance Job Card Platform"
app_email = "your@email.com"
app_license = "MIT"

# Document Events
# Intentionally empty: the `JobCard` controller already exposes `validate` and
# `on_update` as class methods, which Frappe invokes automatically. Registering
# them here as hooks would double-fire every save (duplicating notifications
# and Closure Records).
doc_events: dict = {}

# Roles owned by this app — exported so `bench migrate` creates them on every site.
APP_ROLES = [
    "Depot Manager",
    "Service Engineer",
    "Technician",
    "Central Ops",
    "Aftersales Eng",
    "N. Maintenance Head",
    "Customer",
]

# DocTypes whose Custom Fields / Property Setters we want version-controlled.
CUSTOMIZED_DOCTYPES = [
    "Job Card",
    "Inventory Request",
    "Part",
    "Part Group",
    "Customer",
    "Depot",
    "Vehicle",
    "Service Estimate",
    "Service Contract",
    "Vehicle Health Card",
    "OEM",
    "Subsystem",
]

# Fixtures — version-controlled Workflows, Roles, Permissions, and customizations
fixtures = [
    {
        "dt": "Workflow",
        "filters": [["document_type", "in", ["Job Card", "Inventory Request"]]],
    },
    {"dt": "Workflow State"},
    {"dt": "Workflow Action Master"},
    {
        "dt": "Role",
        "filters": [["name", "in", APP_ROLES]],
    },
    {
        "dt": "Custom Field",
        "filters": [["dt", "in", CUSTOMIZED_DOCTYPES]],
    },
    {
        "dt": "Property Setter",
        "filters": [["doc_type", "in", CUSTOMIZED_DOCTYPES]],
    },
    {
        "dt": "Custom DocPerm",
        "filters": [["parent", "in", CUSTOMIZED_DOCTYPES]],
    },
    {
        "dt": "Client Script",
        "filters": [["dt", "in", CUSTOMIZED_DOCTYPES]],
    },
]

# Client Scripts for Desk forms
doctype_js = {
    "Job Card": "public/js/job_card.js",
}

# Scheduled tasks
scheduler_events = {
    "cron": {
        "*/5 * * * *": [
            "vehicle_maintenance.fleet_service.tasks.monitor_job_card_tat",
            "vehicle_maintenance.fleet_service.tasks.monitor_customer_approval_sla",
            "vehicle_maintenance.fleet_service.tasks.monitor_remote_resolution_sla",
            "vehicle_maintenance.fleet_service.tasks.monitor_critical_followups",
        ],
    },
    # Feedback requests trickle out hourly — a 5-minute cadence is overkill
    # because the gate is `closed_at + 24h`, but the task is cheap.
    "hourly": [
        "vehicle_maintenance.fleet_service.tasks.monitor_feedback_requests",
    ],
}

# Website route rules — serve the Vue 3 SPA for all /service-portal/* routes
website_route_rules = [
    {"from_route": "/service-portal/<path:app_path>", "to_route": "service-portal"},
]

