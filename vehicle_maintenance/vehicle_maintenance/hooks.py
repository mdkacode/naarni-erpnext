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
	# A finished Material Gate run becomes a line in the gate register. Lives as a
	# listener rather than an edit to the engine, so nothing about one particular
	# process leaks into `api.process` — the same rule Battery QC is held to.
	"Process Run": {
		"on_update": "vehicle_maintenance.material_movement.gate_process.on_run_update",
	},
	"User": {
		"validate": "vehicle_maintenance.overrides.user.validate_user",
	},
	# Release the customer KM email the moment an L2 checker approves the snapshot.
	"KM Report Snapshot": {
		"on_update": "vehicle_maintenance.fleet_service.monthly_km_report.send_on_approval",
	},
	# Fan a new alert into every chat channel an admin has opted in. Enqueued
	# inside the hook, so alert ingest never pays for chat fan-out.
	"Alert Event": {
		"after_insert": "vehicle_maintenance.fleet_service.chat_feed.on_alert_event",
	},
}

# Chat access control. DocType role permissions decide who may use chat at all;
# these decide *which rooms* — membership, not role, is the real boundary. Wiring
# both hooks means the generic /api/resource endpoints are covered too, not just
# our own whitelisted methods.
permission_query_conditions = {
	"VM Chat Room": "vehicle_maintenance.fleet_service.doctype.vm_chat_room.vm_chat_room.get_permission_query_conditions",
	"VM Chat Message": "vehicle_maintenance.fleet_service.doctype.vm_chat_message.vm_chat_message.get_permission_query_conditions",
}

has_permission = {
	"VM Chat Room": "vehicle_maintenance.fleet_service.doctype.vm_chat_room.vm_chat_room.has_permission",
	"VM Chat Message": "vehicle_maintenance.fleet_service.doctype.vm_chat_message.vm_chat_message.has_permission",
}

# Chat access control. DocType role permissions decide who may use chat at all;
# these decide *which rooms* — membership, not role, is the real boundary. Wiring
# both hooks means the generic /api/resource endpoints are covered too, not just
# our own whitelisted methods.
permission_query_conditions = {
	"VM Chat Room": "vehicle_maintenance.fleet_service.doctype.vm_chat_room.vm_chat_room.get_permission_query_conditions",
	"VM Chat Message": "vehicle_maintenance.fleet_service.doctype.vm_chat_message.vm_chat_message.get_permission_query_conditions",
	# A daily status is readable by its author and by a supervisor, nobody else —
	# the whole point of the DocType is who can see it.
	"VM Daily Status": "vehicle_maintenance.fleet_service.doctype.vm_daily_status.vm_daily_status.get_permission_query_conditions",
	# An inspection belongs to the person who performed it. Supervisors see all; an
	# operator sees only their own — enforced here so every endpoint touching
	# Process Run inherits it, rather than each one remembering to filter.
	"Process Run": "vehicle_maintenance.process_engine.doctype.process_run.process_run.get_permission_query_conditions",
	# Same split at the material gate: a supervisor sees the plant's movements, an
	# operator sees the ones they recorded.
	"Material Movement": "vehicle_maintenance.material_movement.doctype.material_movement.material_movement.get_permission_query_conditions",
}

has_permission = {
	"VM Chat Room": "vehicle_maintenance.fleet_service.doctype.vm_chat_room.vm_chat_room.has_permission",
	"VM Chat Message": "vehicle_maintenance.fleet_service.doctype.vm_chat_message.vm_chat_message.has_permission",
	"VM Daily Status": "vehicle_maintenance.fleet_service.doctype.vm_daily_status.vm_daily_status.has_permission",
	"Process Run": "vehicle_maintenance.process_engine.doctype.process_run.process_run.has_permission",
	"Material Movement": "vehicle_maintenance.material_movement.doctype.material_movement.material_movement.has_permission",
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
	"vehicle_maintenance.patches.v1_7.seed_process_engine.execute",
	"vehicle_maintenance.patches.v1_7.seed_battery_qc_process.execute",
	"vehicle_maintenance.patches.v1_9.seed_battery_qc_v2.execute",
	"vehicle_maintenance.patches.v1_8.seed_roster.execute",
	"vehicle_maintenance.patches.v2_1.publish_battery_qc.execute",
	"vehicle_maintenance.patches.v2_0.seed_daily_status.execute",
	# One-time: moves duty check-in from an advisory geofence to an enforced
	# 100 m one. Self-guarded, and self-arming — it does nothing until at least
	# one Depot has coordinates, then applies itself on the next migrate.
	"vehicle_maintenance.patches.v2_2.enforce_depot_geofence.execute",
	# Designation picklist, plus an Accepted invite for everyone who already had
	# access — without that back-fill, switching on invite-only would leave the
	# existing depot with no record of who was let in and no way to re-provision.
	"vehicle_maintenance.patches.v2_3.seed_onboarding.execute",
	# Moves any world-readable profile picture into the private bucket. A face
	# behind a public url needs no login and cannot be recalled once shared.
	"vehicle_maintenance.patches.v2_3.privatise_avatars.execute",
	# The pack number is digits; raise the digits keypad for it. Fills a blank
	# only, so an admin's own choice in Desk survives the next migrate.
	"vehicle_maintenance.patches.v2_4.battery_qc_number_pad.execute",
	# Material gate: the two plants, the 11 catalogue groups and the 151 items
	# transcribed from the GENE 13.5M weight sheet. Back-fills blank fields on
	# items that already exist and overwrites nothing an admin has edited.
	"vehicle_maintenance.patches.v2_6.seed_material_movement.execute",
	# The gate as a Process Definition: the direction/item/serial/source/photo/
	# weight run, its outcome set and scannable entity, the seed source list, and
	# the first operators. Seeds the process only when the family has no version,
	# so a plant edit is never overwritten.
	"vehicle_maintenance.patches.v2_7.seed_material_gate_process.execute",
	# Version 2 of that gate, published rather than left in Draft: direction,
	# document number, the chassis it is for, the part, its photo, its weight,
	# and a screen that reads it all back. Creates the version only when it is
	# absent, so a later version authored in Desk stays the live one.
	"vehicle_maintenance.patches.v2_8.rebuild_material_gate.execute",
	# The battery line's second signoff, which has been asked for since the
	# process was published and had nobody able to give it: a role, a plant on
	# people and on runs, and the Installation stage pointed at the new role.
	# Grants the role to everyone who already held Process Verifier, so the
	# migrate that renames who has access takes it from nobody.
	"vehicle_maintenance.patches.v2_9.seed_battery_verification.execute",
	# The platform owner's administrator account. Production is deploy-only —
	# no SSH, no bench shell — so a role that has to exist there arrives the
	# same way the code does. Keyed on a phone number, so it grants nothing on
	# a site where that person has no account, and never removes a role.
	"vehicle_maintenance.patches.v3_0.grant_platform_admin.execute",
]

# Roles owned by this app — exported so `bench migrate` creates them on every site.
APP_ROLES = [
	"Depot Manager",
	"Battery Verification Engineer",
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
	# Process engine. Author is the privileged one — a bad publish reaches every
	# phone on the floor — so it is deliberately separate from running a process.
	"Process Author",
	"Process Operator",
	"Process Verifier",
	"Process Viewer",
	# Owns the Battery Assembly QC process specifically: holds Process Author but
	# is listed in that process's author_roles, so it cannot edit Vehicle PDI.
	"Battery QA Admin",
	# Material gate (inward/outward). Operator records, supervisor verifies —
	# separate roles because a movement is verified by somebody other than the
	# person who recorded it.
	"Material Gate Operator",
	"Material Supervisor",
	"Material Viewer",
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
	"Process Definition": "public/js/process_definition.js",
	"Process Entity Type": "public/js/process_entity_type.js",
	"Duty Roster": "public/js/duty_roster.js",
	"VM Chat Room": "public/js/vm_chat_room.js",
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
			# Push delivery guarantee: re-enqueue any Push Delivery row FCM
			# hasn't accepted yet — the enqueue that never reached Redis, the
			# worker killed mid-send, the row parked on its backoff after an
			# FCM outage. Urgent rows still undelivered after three minutes
			# escalate to SMS. Without this the pipeline is best-effort; with
			# it a queued push can be delayed but not lost.
			"vehicle_maintenance.fleet_service.push_delivery.sweep_pending",
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
		# Close duty punches nobody checked out of. Without this a forgotten
		# check-out leaves an engineer showing "On Duty" on the board for days,
		# which quietly makes the whole board untrustworthy. Written as
		# source = Auto so it is never mistaken for the engineer's own punch.
		"*/30 * * * *": [
			"vehicle_maintenance.fleet_service.tasks.close_forgotten_duty_punches",
		],
		# Yesterday's rostered-but-unpunched days, once the depot opts in
		# (Roster Settings → Mark Absent Automatically, off by default).
		"30 5 * * *": [
			"vehicle_maintenance.fleet_service.tasks.mark_duty_absentees",
		],
		# Settled push ledger rows past retention. Only Sent/Dead are pruned —
		# anything still owed a delivery is never touched by housekeeping.
		"20 3 * * *": [
			"vehicle_maintenance.fleet_service.push_delivery.prune_ledger",
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
		# Daily status. Same site-timezone caveat as the KM report above — these
		# times are written for Asia/Kolkata. All three are no-ops until Daily
		# Status Settings is enabled.
		#
		# 18:45 — remind anyone on duty who has not said anything yet, in their own
		# status room, while they are still at the depot.
		"45 18 * * *": [
			"vehicle_maintenance.fleet_service.daily_status.nudge_missing",
		],
		# 20:00 — organise the day into VM Daily Status rows, one background job
		# per person so one bad room cannot cost everyone else their record.
		"0 20 * * *": [
			"vehicle_maintenance.fleet_service.daily_status.generate_all",
		],
		# 20:20 — email one rollup per depot, and file the day in ONYX search when
		# that is switched on. Deliberately 20 minutes after generation rather than
		# chained to it: a slow summary must delay the email, not cancel it.
		"20 20 * * *": [
			"vehicle_maintenance.fleet_service.daily_status_digest.send_digests",
			"vehicle_maintenance.fleet_service.daily_status_digest.index_day",
		],
	},
	# Feedback requests trickle out hourly — a 5-minute cadence is overkill
	# because the gate is `closed_at + 24h`, but the task is cheap.
	"hourly": [
		"vehicle_maintenance.fleet_service.tasks.monitor_feedback_requests",
		# Expire KM report public tokens once their 7-day window passes.
		"vehicle_maintenance.fleet_service.tasks.expire_km_report_snapshots",
	],
	"daily": [
		# Sweep chat uploads nobody finished. Without this every abandoned
		# 400 MB video stays in private/files/chat_staging forever.
		"vehicle_maintenance.api.chat_upload.cleanup_stale_uploads",
	],
}

# Website route rules — serve the Vue 3 SPA for all /service-portal/* routes,
# and the public token page for /km-report/<token>.
website_route_rules = [
	{"from_route": "/service-portal/<path:app_path>", "to_route": "service-portal"},
	{"from_route": "/km-report/<token>", "to_route": "km-report"},
]
