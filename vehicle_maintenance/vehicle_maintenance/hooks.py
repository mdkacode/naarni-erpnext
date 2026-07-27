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
	# Release the customer KM email the moment an L2 checker approves the snapshot.
	"KM Report Snapshot": {
		"on_update": "vehicle_maintenance.fleet_service.monthly_km_report.send_on_approval",
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
	"vehicle_maintenance.patches.v1_0.seed_suggestion_masters.execute",
	"vehicle_maintenance.patches.v1_1.seed_naarni_custom_fields.execute",
	"vehicle_maintenance.patches.v1_2.seed_fleet_workspace.execute",
	"vehicle_maintenance.patches.v1_3.seed_parts_catalog.execute",
	"vehicle_maintenance.patches.v1_4.backfill_alert_subscription_severity.execute",
	"vehicle_maintenance.patches.v1_5.seed_km_report_custom_fields.execute",
	"vehicle_maintenance.patches.v1_6.seed_alert_responses.execute",
	"vehicle_maintenance.patches.v1_6.add_alert_event_indexes.execute",
	"vehicle_maintenance.patches.v1_6.seed_km_sync_service_user.execute",
	"vehicle_maintenance.patches.v1_6.seed_km_report_workflow.execute",
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
	# View-only access to the KM & SLA reports/dashboards — assign from the User form.
	"Fleet Reports",
	# Two-level internal checkers for the Monthly KM Report before it emails the customer.
	"KM Checker L1",
	"KM Checker L2",
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
		"filters": [["document_type", "in", ["Job Card", "Inventory Request", "Lead", "KM Report Snapshot"]]],
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
		# Sensitive, time-critical SLA monitors run EVERY MINUTE so breach and
		# breakdown escalations reach users within ~60s (notifications must be
		# instant). Idempotency flags on the Job Card prevent duplicate alerts.
		"* * * * *": [
			"vehicle_maintenance.fleet_service.tasks.monitor_job_card_tat",
			"vehicle_maintenance.fleet_service.tasks.monitor_customer_approval_sla",
			"vehicle_maintenance.fleet_service.tasks.monitor_remote_resolution_sla",
		],
		# Non-urgent housekeeping stays on a relaxed cadence.
		"*/5 * * * *": [
			"vehicle_maintenance.fleet_service.tasks.monitor_critical_followups",
		],
		# Alert-engine watchdog: email ops if the engine heartbeat goes stale or it
		# reports it cannot deliver (dead-man's-switch for the alert pipeline).
		"*/2 * * * *": [
			"vehicle_maintenance.fleet_service.tasks.monitor_alert_engine",
		],
		"*/15 * * * *": [
			"vehicle_maintenance.api.crm.dispatch_due_reminders",
		],
		# Pull the Naarni vehicle directory (operators, status, depot) every 2 hours
		# so the app's fleet list stays current. No-op when the integration is disabled.
		"0 */2 * * *": [
			"vehicle_maintenance.integrations.naarni_vehicles.sync_vehicle_directory",
		],
		# NOTE: the per-day odometer pull (`naarni_km_daily.sync_km_daily`, formerly
		# "30 1 * * *") is deliberately NOT scheduled here.
		#
		# The `frappe_km_daily_sync` Airflow DAG (dview-naarni-data-platform,
		# 02:30 IST) now writes the same `Vehicle KM Daily` row keys, sourced from
		# facts_prod.cpoall_session_aggregates. Running both makes them race: this
		# cron's 3-day rolling lookback would overwrite the DAG's rows every morning,
		# so whichever ran last would decide the billing figures.
		#
		# `sync_km_daily` / `sync_km_daily_backfill` remain callable by hand (bench
		# execute, or the whitelisted Ops endpoint) for one-off repair. Re-enabling
		# this schedule means going back to two writers — don't, unless the DAG is
		# being retired at the same time.
		# Monthly KM Report — 1st of the month at 10:00 IST for the PREVIOUS month.
		# NOTE: cron fires in the site timezone — verify System Settings.time_zone is
		# Asia/Kolkata (else adjust, e.g. UTC -> "30 4 1 * *"). The task also derives
		# the target month from the IST date and is idempotent per (customer, month).
		"0 10 1 * *": [
			"vehicle_maintenance.fleet_service.tasks.send_monthly_km_reports",
		],
	},
	# Feedback requests trickle out hourly — a 5-minute cadence is overkill
	# because the gate is `closed_at + 24h`, but the task is cheap.
	"hourly": [
		"vehicle_maintenance.fleet_service.tasks.monitor_feedback_requests",
		# Expire KM report public tokens once their 7-day window passes.
		"vehicle_maintenance.fleet_service.tasks.expire_km_report_snapshots",
	],
}

# Website route rules — serve the Vue 3 SPA for all /service-portal/* routes,
# and the public token page for /km-report/<token>.
website_route_rules = [
	{"from_route": "/service-portal/<path:app_path>", "to_route": "service-portal"},
	{"from_route": "/km-report/<token>", "to_route": "km-report"},
]
