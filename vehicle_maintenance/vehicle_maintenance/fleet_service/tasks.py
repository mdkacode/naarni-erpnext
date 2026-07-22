"""Scheduled tasks for the Fleet Service module.

`monitor_job_card_tat` runs every 5 minutes (see hooks.py `scheduler_events`)
and emits Notification Log entries for Job Cards approaching or breaching their
SLA target, per the PRD TAT table.

In-Desk notifications only — never email from this task.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.utils import get_datetime, now_datetime, time_diff_in_seconds

from vehicle_maintenance.fleet_service import notifications

# PRD TAT targets in seconds (page 3 SLA table).
# Resolution keys: for Only Repair we derive the sub-type from `repair_subtype`.
TAT_TARGETS_SECONDS: dict[str, float] = {
	"PMS + Repair": 4 * 3600,
	"Only Repair": 4 * 3600,  # Regular
	"Only Repair Accidental Major": 24 * 3600,  # Accidental Major
	"Software Update": 3 * 3600,
	"Breakdown": 30 * 60,
}

# Warning recipients per type (PRD p.2). Breakdown has no 80% warning.
WARNING_ROLES_BY_TYPE: dict[str, list[str]] = {
	"PMS + Repair": ["Depot Manager", "Central Ops", "N. Maintenance Head"],
	"Only Repair": ["Depot Manager", "Central Ops", "N. Maintenance Head"],
	"Only Repair Accidental Major": ["Service Engineer", "Aftersales Eng"],
	"Software Update": ["Service Engineer", "Depot Manager"],
	"Breakdown": [],
}

# Breach recipients per type (PRD p.2 Notifications table).
BREACH_ROLES_BY_TYPE: dict[str, list[str]] = {
	"PMS + Repair": ["Depot Manager", "Central Ops", "Service Engineer", "N. Maintenance Head"],
	"Only Repair": ["Depot Manager", "Central Ops", "Service Engineer", "N. Maintenance Head"],
	"Only Repair Accidental Major": ["Service Engineer", "Aftersales Eng", "N. Maintenance Head"],
	"Software Update": ["Service Engineer", "Depot Manager", "N. Maintenance Head"],
	"Breakdown": ["Service Engineer", "Central Ops", "Aftersales Eng", "N. Maintenance Head"],
}

WARNING_THRESHOLD = 0.8  # 80%

# 30-min customer approval SLA (PRD p.3): escalate to N. Maintenance Head.
CUSTOMER_APPROVAL_SLA_SECONDS = 30 * 60
CUSTOMER_APPROVAL_ESCALATION_ROLES = ["N. Maintenance Head", "Depot Manager"]

# Post-closure delay before feedback request (PRD p.2 "XX hrs after closed").
# Tuned to 24h; expose later via a settings DocType if needed.
FEEDBACK_DELAY_SECONDS = 24 * 3600

# Breakdown remote-resolution SLA (PRD p.2 / p.3): 30 minutes, then escalate.
REMOTE_RESOLUTION_SLA_SECONDS = 30 * 60

OPEN_STATES = (
	"Open",
	"WIP",
	"Awaiting Customer Approval",
	"Awaiting Parts",
	"Parts Fitted",
	"Closure from Technician",
	"Verification Pending",
	"Reopened",
)


def _resolve_tat_key(card: dict) -> str:
	"""Collapse Only Repair + Accidental Major sub-type onto a dedicated TAT key."""
	jct = card.get("job_card_type") or ""
	if jct == "Only Repair" and card.get("repair_subtype") == "Accidental Major":
		return "Only Repair Accidental Major"
	return jct


def monitor_job_card_tat() -> None:
	"""Scan open Job Cards; emit warning/breach notifications idempotently.

	Idempotency is enforced by `sla_warning_sent` and `sla_breach_sent` flags
	on Job Card — each alert fires at most once per SLA cycle. On reopen
	(transition back into WIP), the controller clears both flags.
	"""
	now = now_datetime()

	open_cards = frappe.get_all(
		"Job Card",
		filters={
			"workflow_state": ["in", OPEN_STATES],
			"force_closed": 0,
			"sla_breach_sent": 0,
		},
		fields=[
			"name",
			"job_card_type",
			"repair_subtype",
			"opened_at",
			"assigned_service_engineer",
			"assigned_technician",
			"vehicle_number",
			"sla_warning_sent",
		],
		limit_page_length=0,
	)

	for card in open_cards:
		try:
			_process_card(card, now)
		except Exception:
			frappe.log_error(
				title=f"SLA monitor failed for {card.get('name')}",
				message=frappe.get_traceback(),
			)


def _process_card(card: dict, now) -> None:
	opened_at = card.get("opened_at")
	if not opened_at:
		return

	tat_key = _resolve_tat_key(card)
	target_seconds = TAT_TARGETS_SECONDS.get(tat_key)
	if not target_seconds:
		return  # Unknown type — don't guess.

	elapsed = time_diff_in_seconds(now, get_datetime(opened_at))
	if elapsed <= 0:
		return

	label = tat_key.replace("Only Repair Accidental Major", "Only Repair (Accidental Major)")

	# ── Breach ──
	if elapsed >= target_seconds:
		_notify(
			job_card=card,
			roles=BREACH_ROLES_BY_TYPE.get(tat_key, []),
			subject=_("SLA Breached: Job Card {0}").format(card["name"]),
			body=_(
				"Job Card {0} ({1}) for vehicle {2} has breached its " "{3:.0f}-minute SLA target."
			).format(
				card["name"],
				label,
				card.get("vehicle_number") or "—",
				target_seconds / 60,
			),
		)
		frappe.db.set_value("Job Card", card["name"], "sla_breach_sent", 1, update_modified=False)
		return

	# ── Warning (80%) — Breakdown skips this entirely ──
	warning_roles = WARNING_ROLES_BY_TYPE.get(tat_key, [])
	if not warning_roles:
		return
	if card.get("sla_warning_sent"):
		return

	warning_cutoff = target_seconds * WARNING_THRESHOLD
	if elapsed < warning_cutoff:
		return

	_notify(
		job_card=card,
		roles=warning_roles,
		subject=_("SLA Warning: Job Card {0} at 80% of target").format(card["name"]),
		body=_(
			"Job Card {0} ({1}) for vehicle {2} has consumed 80% of its "
			"{3:.0f}-minute SLA target. Action required to avoid breach."
		).format(
			card["name"],
			label,
			card.get("vehicle_number") or "—",
			target_seconds / 60,
		),
	)
	frappe.db.set_value("Job Card", card["name"], "sla_warning_sent", 1, update_modified=False)


# ───────────────────────────────────────────────────────────
# Customer Approval SLA monitor (PRD p.3: 30-min → N. Maint. Head)
# ───────────────────────────────────────────────────────────


def monitor_customer_approval_sla() -> None:
	"""Escalate stale customer-approval waits to N. Maintenance Head.

	Fires when a Job Card has been in 'Awaiting Customer Approval' for >30 min
	without `customer_approval_received_at` being set. Idempotent via the
	`customer_approval_escalated` flag.
	"""
	now = now_datetime()
	cards = frappe.get_all(
		"Job Card",
		filters={
			"workflow_state": "Awaiting Customer Approval",
			"customer_approval_escalated": 0,
			"force_closed": 0,
		},
		fields=[
			"name",
			"job_card_type",
			"customer_approval_requested_at",
			"opened_at",
			"vehicle_number",
			"assigned_service_engineer",
			"assigned_technician",
		],
		limit_page_length=0,
	)

	for card in cards:
		try:
			anchor = card.get("customer_approval_requested_at") or card.get("opened_at")
			if not anchor:
				continue
			elapsed = time_diff_in_seconds(now, get_datetime(anchor))
			if elapsed < CUSTOMER_APPROVAL_SLA_SECONDS:
				continue
			_notify(
				job_card=card,
				roles=CUSTOMER_APPROVAL_ESCALATION_ROLES,
				subject=_("Customer Approval Overdue: Job Card {0}").format(card["name"]),
				body=_(
					"Customer approval for Job Card {0} (vehicle {1}) has been "
					"pending for over 30 minutes. Escalating to N. Maintenance Head."
				).format(card["name"], card.get("vehicle_number") or "—"),
			)
			frappe.db.set_value(
				"Job Card",
				card["name"],
				"customer_approval_escalated",
				1,
				update_modified=False,
			)
		except Exception:
			frappe.log_error(
				title=f"Customer-approval SLA failed for {card.get('name')}",
				message=frappe.get_traceback(),
			)


# ───────────────────────────────────────────────────────────
# Post-closure feedback request (PRD p.2)
# ───────────────────────────────────────────────────────────


def monitor_remote_resolution_sla() -> None:
	"""Escalate breakdown cards whose 30-min remote-diagnosis window expired.

	Fires once per card (idempotent via `remote_resolution_escalation_sent`).
	Only considers Breakdown cards whose `remote_resolution_status` is still
	'In Progress'. When the SLA elapses we:
	  1. Stamp `remote_resolution_failed_at` + flip status to 'Failed'.
	  2. Dispatch notification #11 (SE, Central Ops, N. Maint. Head, Aftersales Eng).
	"""
	now = now_datetime()
	cards = frappe.get_all(
		"Job Card",
		filters={
			"job_card_type": "Breakdown",
			"remote_resolution_status": "In Progress",
			"remote_resolution_escalation_sent": 0,
			"force_closed": 0,
		},
		fields=[
			"name",
			"remote_resolution_started_at",
			"opened_at",
			"vehicle_number",
		],
		limit_page_length=0,
	)

	for card in cards:
		try:
			anchor = card.get("remote_resolution_started_at") or card.get("opened_at")
			if not anchor:
				continue
			elapsed = time_diff_in_seconds(now, get_datetime(anchor))
			if elapsed < REMOTE_RESOLUTION_SLA_SECONDS:
				continue
			doc = frappe.get_doc("Job Card", card["name"])
			notifications.notify_remote_resolution_failed(doc)
			frappe.db.set_value(
				"Job Card",
				card["name"],
				{
					"remote_resolution_status": "Failed",
					"remote_resolution_failed_at": now,
					"remote_resolution_escalation_sent": 1,
				},
				update_modified=False,
			)
		except Exception:
			frappe.log_error(
				title=f"Remote-resolution SLA failed for {card.get('name')}",
				message=frappe.get_traceback(),
			)


def monitor_critical_followups() -> None:
	"""Sweep force-closed Critical Job Cards that lack a follow-up.

	The controller's `on_update` hook tries to create the follow-up JC
	immediately on Critical force close. This task is the fallback that
	picks up any that slipped through (e.g., the hook errored). Runs every
	5 minutes.
	"""
	candidates = frappe.get_all(
		"Job Card",
		filters={
			"force_closed": 1,
			"force_close_severity": "Critical",
			"followup_job_card": ["is", "not set"],
		},
		pluck="name",
		limit_page_length=0,
	)
	for name in candidates:
		try:
			doc = frappe.get_doc("Job Card", name)
			doc._create_critical_followup_if_needed()
		except Exception:
			frappe.log_error(
				title=f"monitor_critical_followups failed for {name}",
				message=frappe.get_traceback(),
			)


def monitor_feedback_requests() -> None:
	"""For every Closed card past the feedback delay, send a one-time
	feedback-request notification to the customer. Idempotent via
	`feedback_request_sent` flag.
	"""
	now = now_datetime()
	cards = frappe.get_all(
		"Job Card",
		filters={
			"workflow_state": "Closed",
			"feedback_request_sent": 0,
			"force_closed": 0,
		},
		fields=["name", "closed_at", "customer", "vehicle_number"],
		limit_page_length=0,
	)

	for card in cards:
		try:
			closed_at = card.get("closed_at")
			if not closed_at:
				continue
			elapsed = time_diff_in_seconds(now, get_datetime(closed_at))
			if elapsed < FEEDBACK_DELAY_SECONDS:
				continue
			doc = frappe.get_doc("Job Card", card["name"])
			notifications.notify_feedback_request(doc)
			frappe.db.set_value(
				"Job Card",
				card["name"],
				"feedback_request_sent",
				1,
				update_modified=False,
			)
		except Exception:
			frappe.log_error(
				title=f"Feedback-request failed for {card.get('name')}",
				message=frappe.get_traceback(),
			)


def _notify(job_card: dict, roles: list[str], subject: str, body: str) -> None:
	"""Create Notification Log entries for every enabled user holding any of
	the given roles, plus the card's assigned SE and Technician.
	"""
	recipients: set[str] = set()
	for role in roles:
		recipients.update(_users_with_role(role))

	for extra in (
		job_card.get("assigned_service_engineer"),
		job_card.get("assigned_technician"),
	):
		if extra:
			recipients.add(extra)

	for user in recipients:
		log = frappe.new_doc("Notification Log")
		log.update(
			{
				"subject": subject,
				"email_content": body,
				"for_user": user,
				"type": "Alert",
				"document_type": "Job Card",
				"document_name": job_card["name"],
				"from_user": "Administrator",
			}
		)
		log.insert(ignore_permissions=True)


def _users_with_role(role: str) -> list[str]:
	"""Return enabled System Users who hold the given role."""
	rows = frappe.get_all(
		"Has Role",
		filters={"role": role, "parenttype": "User"},
		fields=["parent"],
		limit_page_length=0,
	)
	users = [r["parent"] for r in rows]
	if not users:
		return []
	return frappe.get_all(
		"User",
		filters={"name": ["in", users], "enabled": 1, "user_type": "System User"},
		pluck="name",
	)


# ---------------------------------------------------------------------------
# Alert-engine watchdog (dead-man's-switch)
#
# The alert engine posts a heartbeat to `Alert Engine Heartbeat` on every ingest
# cycle (~1/min). This task runs every 2 minutes and EMAILS ops the moment that
# beacon goes stale (or the engine reports 0 rules / Teams off / delivery errors),
# so a dead alert pipeline is never silent. Emails are throttled and a recovery
# notice is sent when it comes back.  Recipients: site_config `alert_watchdog_
# recipients` (list or comma string), else the default below.
# ---------------------------------------------------------------------------

WATCHDOG_STALE_SECS_DEFAULT = 300  # a beat older than this is "stale"
# Debounce: the heartbeat rides the every-minute telemetry ingest, so a single
# spot reschedule / slow ingest cycle can leave one check stale even though the
# pipeline is fine. Require the problem on N consecutive runs (this task fires
# every 2 min) before paging — ~7-9 min of *continuous* silence — so a brief gap
# never flaps but a real sustained outage still alerts. Override per site with
# `alert_watchdog_confirm_checks`.
WATCHDOG_CONFIRM_CHECKS_DEFAULT = 2
WATCHDOG_RENOTIFY_MINS_DEFAULT = 30  # re-email at most every 30 min while down
WATCHDOG_DEFAULT_RECIPIENTS = ["mayank.dwivedi@naarni.com"]


def _watchdog_recipients() -> list[str]:
	raw = frappe.conf.get("alert_watchdog_recipients")
	if isinstance(raw, str):
		recips = [x.strip() for x in raw.split(",") if x.strip()]
	elif isinstance(raw, list | tuple):
		recips = [str(x).strip() for x in raw if str(x).strip()]
	else:
		recips = []
	return recips or list(WATCHDOG_DEFAULT_RECIPIENTS)


def _send_watchdog_email(subject: str, message: str) -> None:
	# now=True: send synchronously — a down alert must not sit in a queue.
	frappe.sendmail(recipients=_watchdog_recipients(), subject=subject, message=message, now=True)


def engine_health_problems(
	*,
	has_heartbeat: bool,
	age_secs: float | None,
	rules_loaded: int,
	teams_enabled: bool,
	last_errors: int,
	stale_secs: int,
) -> list[str]:
	"""Pure decision: given the heartbeat state, list the reasons alerts may not be
	delivering (empty list == healthy). Kept dependency-free so it is unit-testable
	without a bench."""
	problems: list[str] = []
	if not has_heartbeat:
		return [
			"No heartbeat has EVER been received from the alert engine. It may not be "
			"deployed (needs engine >= 0.9.5) or cannot reach Frappe."
		]
	if age_secs is not None and age_secs > stale_secs:
		problems.append(
			f"Heartbeat is STALE — last seen {int(age_secs)}s ago (threshold {stale_secs}s). "
			"The alert engine or telemetry pipeline is DOWN; alerts are NOT being delivered."
		)
	if (rules_loaded or 0) == 0:
		problems.append(
			"Engine reports 0 rules loaded — no alert can fire. Check get_engine_config / Alert Subscriptions."
		)
	if not teams_enabled:
		problems.append(
			"Engine reports Teams delivery is DISABLED (no channel and no env webhook). Alerts cannot reach Teams."
		)
	if (last_errors or 0) > 0:
		problems.append(
			f"Engine reported {last_errors} delivery error(s) in the last cycle — some alerts may be failing to deliver."
		)
	return problems


def monitor_alert_engine() -> None:
	"""Email ops if the alert engine's heartbeat is stale or it reports it can't
	deliver. Runs every 2 minutes (hooks.py). Idempotent + throttled."""
	hb = frappe.get_single("Alert Engine Heartbeat")
	stale_secs = int(frappe.conf.get("alert_watchdog_stale_secs") or WATCHDOG_STALE_SECS_DEFAULT)
	confirm_checks = max(
		1, int(frappe.conf.get("alert_watchdog_confirm_checks") or WATCHDOG_CONFIRM_CHECKS_DEFAULT)
	)
	now = now_datetime()

	# Arm-on-first-beat: until the engine has posted ONE heartbeat, the switch is
	# unarmed — don't email. This keeps rollout clean (Frappe can be migrated before
	# engine >= 0.9.5 is deployed without spurious "engine down" mails). Once a beat
	# has ever arrived, a stale beacon is a real outage and does alert.
	if not hb.last_seen:
		hb.db_set("consecutive_bad", 0, update_modified=False)
		hb.db_set(
			"last_status",
			"Unarmed — no heartbeat received yet (deploy engine >= 0.9.5).",
			update_modified=False,
		)
		frappe.db.commit()
		return

	age = time_diff_in_seconds(now, get_datetime(hb.last_seen))
	problems = engine_health_problems(
		has_heartbeat=True,
		age_secs=age,
		rules_loaded=hb.rules_loaded or 0,
		teams_enabled=bool(hb.teams_enabled),
		last_errors=hb.last_errors or 0,
		stale_secs=stale_secs,
	)

	if problems:
		# Debounce: only page once the problem has persisted across `confirm_checks`
		# consecutive runs. A one-off blip (a slow ingest cycle, a spot reschedule)
		# clears the streak on the next healthy run and never pages.
		streak = (hb.consecutive_bad or 0) + 1
		hb.db_set("consecutive_bad", streak, update_modified=False)
		status = "; ".join(problems)
		if streak < confirm_checks:
			hb.db_set(
				"last_status",
				f"pending {streak}/{confirm_checks} bad checks: {status}",
				update_modified=False,
			)
			frappe.db.commit()
			return

		renotify = int(frappe.conf.get("alert_watchdog_renotify_mins") or WATCHDOG_RENOTIFY_MINS_DEFAULT)
		due = True
		if hb.down_notified and hb.last_notified_at:
			mins = time_diff_in_seconds(now, get_datetime(hb.last_notified_at)) / 60.0
			due = mins >= renotify
		if due:
			body = (
				"<h3>🚨 Naarni Alert Engine — DELIVERY AT RISK</h3>"
				"<p>The alert-engine watchdog detected a problem. Alerts may not be reaching users.</p>"
				"<ul>" + "".join(f"<li>{frappe.utils.escape_html(p)}</li>" for p in problems) + "</ul>"
				f"<p><b>Last heartbeat:</b> {hb.last_seen or 'never'}<br>"
				f"<b>Engine version:</b> {hb.engine_version or 'unknown'}<br>"
				f"<b>Rules loaded:</b> {hb.rules_loaded or 0}</p>"
				"<p>Checked every 2 minutes; you'll get a recovery email when it clears.</p>"
			)
			try:
				_send_watchdog_email("🚨 Naarni Alert Engine DOWN — alerts at risk", body)
			except Exception:
				frappe.log_error(title="Watchdog email failed", message=frappe.get_traceback())
			hb.db_set("down_notified", 1, update_modified=False)
			hb.db_set("last_notified_at", now, update_modified=False)
		hb.db_set("last_status", status, update_modified=False)
		frappe.log_error(title="Alert engine watchdog: DOWN", message=status)
	else:
		if hb.down_notified:
			body = (
				"<h3>✅ Naarni Alert Engine — RECOVERED</h3>"
				f"<p>Heartbeat is healthy again as of {now}.</p>"
				f"<p><b>Engine version:</b> {hb.engine_version or 'unknown'} · "
				f"<b>Rules loaded:</b> {hb.rules_loaded or 0}</p>"
			)
			try:
				_send_watchdog_email("✅ Naarni Alert Engine recovered", body)
			except Exception:
				frappe.log_error(title="Watchdog recovery email failed", message=frappe.get_traceback())
		hb.db_set("consecutive_bad", 0, update_modified=False)
		hb.db_set("down_notified", 0, update_modified=False)
		hb.db_set("last_status", "OK", update_modified=False)

	frappe.db.commit()


# ──────────────────────────── monthly KM report ────────────────────────────


def send_monthly_km_reports() -> dict:
	"""Scheduled on the 1st @ 10:00 IST — email each enabled customer LAST month's KM report.

	The cron fires in the site timezone (verify System Settings.time_zone at deploy);
	the *previous month* is derived from the IST calendar date, and each customer is
	processed in a background job. Idempotent: a customer whose snapshot for that month
	is already Sent is skipped, so an odd/duplicate cron firing never double-emails.
	"""
	from vehicle_maintenance.fleet_service import km_report
	from vehicle_maintenance.integrations.naarni_km_daily import _ist_today

	year_month = km_report.prev_month(_ist_today().strftime("%Y-%m"))
	customers = frappe.get_all("Fleet Report Config", filters={"enabled": 1}, pluck="customer")

	queued = 0
	for customer in customers:
		already_sent = frappe.db.get_value(
			"KM Report Snapshot",
			{"customer": customer, "report_month": year_month, "status": "Sent"},
			"name",
		)
		if already_sent:
			continue
		frappe.enqueue(
			"vehicle_maintenance.fleet_service.monthly_km_report.run_for_customer",
			queue="long",
			customer=customer,
			year_month=year_month,
		)
		queued += 1

	frappe.logger("naarni").info(f"monthly km reports: queued {queued} for {year_month}")
	return {"month": year_month, "queued": queued}


def expire_km_report_snapshots() -> None:
	"""Hourly sweeper: flip snapshots whose 7-day public token has passed to Expired."""
	frappe.db.sql(
		"""
		UPDATE `tabKM Report Snapshot`
		SET status = 'Expired'
		WHERE status != 'Expired' AND token_expires_on IS NOT NULL AND token_expires_on < %(now)s
		""",
		{"now": now_datetime()},
	)
	frappe.db.commit()
