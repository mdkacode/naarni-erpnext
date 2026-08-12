"""Scheduled tasks for the Fleet Service module.

`monitor_job_card_tat` runs every 5 minutes (see hooks.py `scheduler_events`)
and emits Notification Log entries for Job Cards approaching or breaching their
SLA target, per the PRD TAT table.

In-Desk notifications only — never email from this task.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.utils import add_to_date, escape_html, get_datetime, now_datetime, time_diff_in_seconds

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
# The alert engine posts two kinds of heartbeat to `Alert Engine Heartbeat`:
#   * `timer`  — its own every-60s beat (engine >= 0.9.6). The process is alive.
#   * `ingest` — one per /ingest cycle (~1/min, driven by Airflow's
#     factsprod_vehicle_recent_info_silver push). Telemetry is still flowing.
#
# Two clocks, because they fail for different reasons and need different fixes:
# a dead pod is an infra problem, a silent Airflow DAG is a data-pipeline problem.
# Before 0.9.6 there was only the ingest-driven beat, so the watchdog could only
# say "engine OR pipeline is down" — which is what made every page a guess.
#
# This task runs every 2 minutes and EMAILS ops when either clock goes stale (or
# the engine reports 0 rules / Teams off / delivery errors), with the engine's own
# traceback and the recent Frappe Error Log tracebacks attached. Emails are
# debounced + throttled, and a recovery notice is sent when it comes back.
# Recipients: site_config `alert_watchdog_recipients` (list or comma string),
# else the default below.
# ---------------------------------------------------------------------------

WATCHDOG_STALE_SECS_DEFAULT = 300  # a beat older than this is "stale"
# Telemetry is looser than the engine beat on purpose: ingest depends on an Airflow
# DAG run finishing, so a slow Trino query or a queued task can legitimately push a
# cycle out past 5 min without anything being broken. Override: site_config
# `alert_watchdog_ingest_stale_secs`.
WATCHDOG_INGEST_STALE_SECS_DEFAULT = 600
# Debounce: the heartbeat rides the every-minute telemetry ingest, so a single
# spot reschedule / slow ingest cycle can leave one check stale even though the
# pipeline is fine. Require the problem on N consecutive runs (this task fires
# every 2 min) before paging — ~7-9 min of *continuous* silence — so a brief gap
# never flaps but a real sustained outage still alerts. Override per site with
# `alert_watchdog_confirm_checks`.
WATCHDOG_CONFIRM_CHECKS_DEFAULT = 2
WATCHDOG_RENOTIFY_MINS_DEFAULT = 30  # re-email at most every 30 min while down
WATCHDOG_DEFAULT_RECIPIENTS = ["mayank.dwivedi@naarni.com"]
# Stack-trace budget for the page: enough to identify the failure, not so much that
# the mail is unreadable (or rejected by the SMTP size limit).
WATCHDOG_TRACE_LOGS = 3  # how many Frappe Error Log entries to attach
WATCHDOG_TRACE_CHARS = 3000  # per-traceback char cap in the email
WATCHDOG_ERROR_LOG_HOURS = 6  # only attach Error Logs from this recent a window
# Error Log titles worth attaching to an alert-pipeline page, matched case-insensitively.
WATCHDOG_LOG_KEYWORDS = ("alert", "watchdog", "heartbeat", "engine", "ingest", "novu", "teams")
# The watchdog's own log entry. Excluded from the attached traces: it matches the
# keywords above and would otherwise fill all three slots with a restatement of the
# email the reader is already holding, pushing out the traceback that explains it.
WATCHDOG_LOG_TITLE = "Alert engine watchdog: DOWN"


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
	ingest_age_secs: float | None = None,
	ingest_stale_secs: int = WATCHDOG_INGEST_STALE_SECS_DEFAULT,
	self_timed: bool = False,
) -> list[str]:
	"""Pure decision: given the heartbeat state, list the reasons alerts may not be
	delivering (empty list == healthy). Kept dependency-free so it is unit-testable
	without a bench.

	`age_secs` is the age of the newest beat of ANY kind (engine liveness) and
	`ingest_age_secs` the age of the newest ingest-driven beat (telemetry liveness).
	`self_timed` says the engine posts its own timer beats, which is what makes the
	two ages independent — without it they are the same number and the wording falls
	back to the pre-0.9.6 "engine or pipeline" phrasing rather than claiming more
	than the data supports."""
	problems: list[str] = []
	if not has_heartbeat:
		return [
			"No heartbeat has EVER been received from the alert engine. It may not be "
			"deployed (needs engine >= 0.9.5) or cannot reach Frappe."
		]
	engine_stale = age_secs is not None and age_secs > stale_secs
	if engine_stale and self_timed:
		problems.append(
			f"ALERT ENGINE IS DOWN — no beat of any kind for {int(age_secs)}s (threshold "
			f"{stale_secs}s), including its own 60s self-timer. The engine process is not "
			"running (pod evicted/crashed/rescheduled) or cannot reach Frappe. Nothing is "
			"being evaluated and NO alert can be delivered. Check the engine pod."
		)
	elif engine_stale:
		problems.append(
			f"Heartbeat is STALE — last seen {int(age_secs)}s ago (threshold {stale_secs}s). "
			"The alert engine or telemetry pipeline is DOWN; alerts are NOT being delivered."
		)
	elif ingest_age_secs is not None and ingest_age_secs > ingest_stale_secs:
		# Engine beat is fresh but no ingest cycle has landed: the engine is healthy
		# and idle, waiting for data that isn't coming. Different outage, different fix.
		problems.append(
			f"TELEMETRY INGEST HAS STOPPED — the engine is UP (last beat {int(age_secs)}s ago) "
			f"but no /ingest cycle has arrived for {int(ingest_age_secs)}s (threshold "
			f"{ingest_stale_secs}s). Airflow's factsprod_vehicle_recent_info_silver push is not "
			"reaching the engine, so there is no data to evaluate and no alert can fire. "
			"Check the DAG, not the engine pod."
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


def _fmt_age(secs: float | None) -> str:
	if secs is None:
		return "never"
	secs = int(secs)
	return f"{secs // 60}m {secs % 60}s" if secs >= 60 else f"{secs}s"


def _pre(text: str | None) -> str:
	"""One traceback, escaped, capped and rendered monospace for the email."""
	raw = (text or "").strip()
	body = escape_html(raw[:WATCHDOG_TRACE_CHARS]) or "(empty)"
	if len(raw) > WATCHDOG_TRACE_CHARS:
		body += f"\n… truncated ({len(raw) - WATCHDOG_TRACE_CHARS} more chars — see the Error Log)"
	return (
		'<pre style="background:#f6f8fa;border:1px solid #d0d7de;border-radius:6px;padding:10px;'
		"font:12px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap;"
		f'word-break:break-word;overflow-x:auto">{body}</pre>'
	)


def _recent_error_logs(limit: int = WATCHDOG_TRACE_LOGS) -> list[dict]:
	"""The most recent Frappe-side tracebacks, preferring alert-pipeline ones.

	Best-effort by design: diagnostics must never be the reason a page fails to go
	out, so every failure here degrades to 'no traces' rather than raising."""
	try:
		title_field = "method" if frappe.get_meta("Error Log").has_field("method") else None
		fields = ["name", "creation", "error"] + ([title_field] if title_field else [])
		rows = frappe.get_all(
			"Error Log",
			filters={"creation": [">", add_to_date(now_datetime(), hours=-WATCHDOG_ERROR_LOG_HOURS)]},
			fields=fields,
			order_by="creation desc",
			limit_page_length=25,
		)
	except Exception:
		return []
	if title_field:
		rows = [r for r in rows if (r.get(title_field) or "") != WATCHDOG_LOG_TITLE]
	relevant = (
		[r for r in rows if any(k in (r.get(title_field) or "").lower() for k in WATCHDOG_LOG_KEYWORDS)]
		if title_field
		else []
	)
	return [
		{
			"title": (r.get(title_field) if title_field else None) or r.get("name"),
			"at": r.get("creation"),
			"trace": r.get("error") or "",
		}
		for r in (relevant or rows)[:limit]
	]


def _watchdog_state_html(hb, age_secs: float | None, ingest_age_secs: float | None) -> str:
	"""Both clocks side by side — the first thing to read on a page, because which
	one is stale IS the diagnosis."""
	rows = [
		(
			"Engine beat (any kind)",
			f"{_fmt_age(age_secs)} ago · {hb.last_seen or 'never'} · via {hb.last_beat_source or 'ingest'}",
		),
		("Telemetry ingest cycle", f"{_fmt_age(ingest_age_secs)} ago · {hb.last_ingest_at or 'never'}"),
		("Engine self-timer", str(hb.last_timer_at) if hb.last_timer_at else "not reported (engine < 0.9.6)"),
		("Engine version", hb.engine_version or "unknown"),
		("Rules loaded", hb.rules_loaded or 0),
		("Teams delivery", "enabled" if hb.teams_enabled else "DISABLED"),
		("Errors last cycle", hb.last_errors or 0),
		(
			"Last cycle counters",
			f"rows={hb.last_rows or 0} breaches={hb.last_breaches or 0} "
			f"fired={hb.last_fired or 0} teams={hb.last_teamed or 0}",
		),
		("Consecutive bad checks", hb.consecutive_bad or 0),
	]
	trs = "".join(
		f'<tr><td style="border:1px solid #d0d7de;padding:4px 8px"><b>{escape_html(str(k))}</b></td>'
		f'<td style="border:1px solid #d0d7de;padding:4px 8px">{escape_html(str(v))}</td></tr>'
		for k, v in rows
	)
	return f'<table style="border-collapse:collapse;font-size:13px">{trs}</table>'


def _watchdog_subject(problems: list[str]) -> str:
	"""Name the outage in the subject line. Ops triage from a phone lock screen, and
	'engine down' and 'telemetry stopped' go to different people — a subject that
	says only 'DOWN' makes every page start with the same investigation."""
	head = problems[0] if problems else ""
	if head.startswith("TELEMETRY INGEST HAS STOPPED"):
		return "🚨 Naarni telemetry ingest STOPPED — engine up, no data arriving"
	if head.startswith("ALERT ENGINE IS DOWN"):
		return "🚨 Naarni Alert Engine DOWN — engine process not running"
	return "🚨 Naarni Alert Engine DOWN — alerts at risk"


def _watchdog_traces_html(hb) -> str:
	"""Stack traces from both sides: what the engine itself last failed on, and what
	Frappe logged. A page that carries the traceback is one you can act on from the
	phone; a page that says only 'something is wrong' costs a laptop and 20 minutes."""
	blocks: list[str] = []
	if hb.last_error_trace or hb.last_error_context:
		blocks.append(
			"<h4>Engine-reported failure</h4>"
			f"<p><b>{escape_html(hb.last_error_context or 'unknown context')}</b>"
			f" · <i>{escape_html(str(hb.last_error_at or 'unknown time'))}</i></p>"
			+ _pre(hb.last_error_trace)
		)
	logs = _recent_error_logs()
	if logs:
		items = "".join(
			f"<p><b>{escape_html(str(log['title']))}</b> · <i>{escape_html(str(log['at']))}</i></p>"
			+ _pre(log["trace"])
			for log in logs
		)
		blocks.append(f"<h4>Frappe Error Log (last {WATCHDOG_ERROR_LOG_HOURS}h)</h4>{items}")
	if not blocks:
		return (
			"<h4>Stack traces</h4>"
			"<p>None on either side — the engine reported no failure and Frappe logged no error "
			f"in the last {WATCHDOG_ERROR_LOG_HOURS}h. A beacon that simply stops with no traceback "
			"anywhere points <b>outside</b> the application code: the engine pod was evicted or "
			"rescheduled, the network path to Frappe broke, or Airflow never called /ingest.</p>"
		)
	return "".join(blocks)


def monitor_alert_engine() -> None:
	"""Email ops if the alert engine's heartbeat is stale or it reports it can't
	deliver. Runs every 2 minutes (hooks.py). Idempotent + throttled."""
	hb = frappe.get_single("Alert Engine Heartbeat")
	stale_secs = int(frappe.conf.get("alert_watchdog_stale_secs") or WATCHDOG_STALE_SECS_DEFAULT)
	ingest_stale_secs = int(
		frappe.conf.get("alert_watchdog_ingest_stale_secs") or WATCHDOG_INGEST_STALE_SECS_DEFAULT
	)
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
	# Telemetry clock. None until an ingest-driven beat has ever landed — on a site
	# whose first-ever beat came from the engine's timer we simply don't know when
	# ingest last worked, and inventing a number there would page on a guess. The
	# first real ingest arms it (within a minute on a live pipeline).
	ingest_age = time_diff_in_seconds(now, get_datetime(hb.last_ingest_at)) if hb.last_ingest_at else None
	problems = engine_health_problems(
		has_heartbeat=True,
		age_secs=age,
		rules_loaded=hb.rules_loaded or 0,
		teams_enabled=bool(hb.teams_enabled),
		last_errors=hb.last_errors or 0,
		stale_secs=stale_secs,
		ingest_age_secs=ingest_age,
		ingest_stale_secs=ingest_stale_secs,
		self_timed=bool(hb.last_timer_at),
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
				"<ul>" + "".join(f"<li>{escape_html(p)}</li>" for p in problems) + "</ul>"
				"<h4>State</h4>"
				+ _watchdog_state_html(hb, age, ingest_age)
				+ _watchdog_traces_html(hb)
				+ f"<p>Confirmed over {streak} consecutive checks (every 2 minutes); "
				"you'll get a recovery email when it clears.</p>"
			)
			try:
				_send_watchdog_email(_watchdog_subject(problems), body)
			except Exception:
				frappe.log_error(title="Watchdog email failed", message=frappe.get_traceback())
			hb.db_set("down_notified", 1, update_modified=False)
			hb.db_set("last_notified_at", now, update_modified=False)
		hb.db_set("last_status", status, update_modified=False)
		frappe.log_error(title=WATCHDOG_LOG_TITLE, message=status)
	else:
		if hb.down_notified:
			body = (
				"<h3>✅ Naarni Alert Engine — RECOVERED</h3>"
				f"<p>Both heartbeats are healthy again as of {now}.</p>"
				"<h4>State</h4>"
				+ _watchdog_state_html(hb, age, ingest_age)
				+ "<p>Previous problem: "
				+ escape_html(hb.last_status or "unknown")
				+ "</p>"
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


# ---------------------------------------------------------------------- roster


def close_forgotten_duty_punches() -> None:
	"""Auto check-out anyone left open past the configured window.

	A no-op unless Roster Settings has auto check-out enabled. Errors are logged
	per engineer inside `roster.close_forgotten_punches`, so one bad row cannot
	stop the sweep.
	"""
	from vehicle_maintenance.fleet_service import roster

	closed = roster.close_forgotten_punches()
	if closed:
		frappe.logger("naarni").info(f"roster: auto checked-out {closed} forgotten punch(es)")


def mark_duty_absentees() -> None:
	"""Mark yesterday's rostered-but-unpunched days Absent, when the depot opts in."""
	from vehicle_maintenance.fleet_service import roster

	marked = roster.mark_absentees()
	if marked:
		frappe.logger("naarni").info(f"roster: marked {marked} absentee(s)")
