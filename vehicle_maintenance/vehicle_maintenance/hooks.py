app_name = "vehicle_maintenance"
app_title = "Vehicle Maintenance"
app_publisher = "Your Name"
app_description = "Vehicle Maintenance Job Card Platform"
app_email = "your@email.com"
app_license = "MIT"

# Document Events — JobCard has class-method validate/on_update and must stay
# unregistered here to avoid double-firing. User validate enforces phone-based
# auth: mandatory mobile_no, synthetic email fallback from the phone.
doc_events: dict = {
	"User": {
		"validate": "vehicle_maintenance.overrides.user.validate_user",
	},
}

# Idempotent seeders run after every migrate. Each function checks existence
# before inserting, so this is safe to invoke repeatedly.
after_migrate = [
	"vehicle_maintenance.patches.v0_4.seed_crm_masters.execute",
	"vehicle_maintenance.patches.v0_6.seed_telemetry_parameters.execute",
	"vehicle_maintenance.patches.v0_5.seed_alert_types.execute",
	"vehicle_maintenance.patches.v0_7.upgrade_alert_operators.execute",
	"vehicle_maintenance.patches.v0_9.seed_alerts_dashboard.execute",
]

# Roles owned by this app — exported so `bench migrate` creates them on every site.
APP_ROLES = [
	"Depot Manager",
	"Service Engineer",
	"Technician",
	"Central Ops",
	"Aftersales Eng",
	"N. Maintenance Head",
	"Customer",
	"Sales Executive",
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
		"filters": [["document_type", "in", ["Job Card", "Inventory Request", "Lead"]]],
	},
	{
		"dt": "Lead Source",
	},
	{
		"dt": "Lead Status",
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
	"Alert Type": "public/js/alert_type.js",
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
		"*/15 * * * *": [
			"vehicle_maintenance.api.crm.dispatch_due_reminders",
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
