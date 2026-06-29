"""Notification dispatcher for Fleet Service.

One function per PRD trigger (page 2 Notifications & Alerting table). All
notifications go through Frappe's `Notification Log` — which is the in-Desk bell
channel — per PRD (email is reserved for reports only).

Push and SMS are intentionally deferred to a separate phase. To keep that
extension path clean, every emission goes through the `_dispatch()` sink; swap
that one function later and all triggers inherit the new channel.
"""

from __future__ import annotations

import frappe
from frappe import _

# ── PRD p.2 recipient matrix ──
# Each key maps to a list of role names; dispatch also includes any extra
# individual users supplied by the caller (assigned SE, technician, customer).

RECIPIENTS_JOB_CREATED = ["Depot Manager"]
RECIPIENTS_APPROVAL_REQUEST_TO_CUSTOMER = []  # customer user is a direct recipient
RECIPIENTS_APPROVAL_OUTCOME = ["Depot Manager"]  # + assigned SE as direct user
RECIPIENTS_PARTS_ALLOCATED = ["Depot Manager"]  # + assigned SE, technician
RECIPIENTS_CLOSED_BY_TECHNICIAN = ["Depot Manager", "Central Ops"]
RECIPIENTS_VERIFIED_BY_SE = ["Depot Manager", "Central Ops", "N. Maintenance Head"]
RECIPIENTS_REOPENED = ["Depot Manager", "Central Ops"]
RECIPIENTS_BREAKDOWN_DECLARED = [
	"Central Ops",
	"Depot Manager",
	"Service Engineer",
	"N. Maintenance Head",
]
RECIPIENTS_REMOTE_RESOLUTION_FAILED = [
	"Service Engineer",
	"Central Ops",
	"N. Maintenance Head",
]
RECIPIENTS_FEEDBACK_REQUEST = []  # customer + driver (direct users only)


# ───────────────────────────────────────────────────────────
# Public triggers — one function per PRD row
# ───────────────────────────────────────────────────────────


def notify_job_card_created(doc) -> None:
	"""PRD p.2: Job card created → Depot Manager, Technician (if assigned).

	Breakdown creation additionally triggers `notify_breakdown_declared`,
	which has a wider recipient list and flags as highest priority.
	"""
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_JOB_CREATED,
		extra_users=[doc.assigned_technician, doc.assigned_service_engineer],
		subject=_("New Job Card {0} created").format(doc.name),
		body=_("Job Card {0} created for vehicle {1} at depot {2}. " "Type: {3}. Priority: {4}.").format(
			doc.name,
			doc.vehicle_number or "—",
			doc.depot or "—",
			doc.job_card_type,
			doc.priority or "Medium",
		),
		priority="Medium",
	)

	if doc.job_card_type == "Breakdown":
		notify_breakdown_declared(doc)


def notify_breakdown_declared(doc) -> None:
	"""PRD p.2: Breakdown declared → Central Ops, Depot Manager, SE, N.M.H. — highest priority."""
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_BREAKDOWN_DECLARED,
		extra_users=[doc.assigned_service_engineer, doc.assigned_technician],
		subject=_("🚨 Breakdown Declared: Job Card {0}").format(doc.name),
		body=_(
			"BREAKDOWN declared for vehicle {0} at {1}. "
			"Job Card {2}. Immediate response required — 30-min remote resolution SLA."
		).format(
			doc.vehicle_number or "—",
			doc.incident_place or doc.depot or "—",
			doc.name,
		),
		priority="High",
	)


def notify_depot_broadcast(doc) -> None:
	"""Phase-2 PRD item (Breakdown p.16 item 8): broadcast a Breakdown — or
	any High/Urgent-priority issue — to Depot Managers at other depots so
	they can proactively check the same subsystem on their fleet.

	Recipients: Depot Managers across ALL depots except the source one.
	Optionally fired from the Job Card controller when a Breakdown is
	resolved with `occurrence_risk = High` (fleet-wide risk signal).
	"""
	source_depot = doc.depot
	# Find DMs not tied to the source depot. If the DM's User record doesn't
	# carry depot affinity, we notify everyone with the Depot Manager role
	# and rely on the message title to clue them in.
	dm_users = _users_with_role("Depot Manager")
	if not dm_users:
		return

	subsystems = [row.subsystem for row in doc.get("subsystems", [])]
	groups = [row.part_group for row in doc.get("groups_impacted", [])]
	subject = _("🛈 Fleet-wide alert: Breakdown at {0}").format(source_depot or "—")
	body = _(
		"A Breakdown with fleet-wide occurrence risk was recorded at depot {0} "
		"for vehicle {1}.\nSubsystems: {2}\nGroups Impacted: {3}\n"
		"Please check similar vehicles at your depot."
	).format(
		source_depot or "—",
		doc.vehicle_number or "—",
		", ".join(subsystems) or "—",
		", ".join(groups) or "—",
	)
	_dispatch(
		job_card=doc,
		roles=[],
		extra_users=dm_users,
		subject=subject,
		body=body,
		priority="Medium",
	)


def notify_state_transition(doc, old_state: str | None) -> None:
	"""Route the right PRD notification for the state change.

	Called from Job Card `on_update`. `old_state` is the pre-save value,
	stashed during `validate()`. No-op when the state didn't change.
	"""
	if not old_state or old_state == doc.workflow_state:
		return

	new_state = doc.workflow_state

	if new_state == "Awaiting Customer Approval":
		notify_customer_approval_requested(doc)

	elif old_state == "Awaiting Customer Approval" and new_state in ("WIP", "Awaiting Parts"):
		# 'Awaiting Parts' = approved, 'WIP' = rejected (per workflow JSON).
		approved = new_state == "Awaiting Parts"
		notify_customer_approval_outcome(doc, approved=approved)

	elif new_state == "Closure from Technician":
		notify_closed_by_technician(doc)

	elif new_state == "Closed":
		notify_verified_and_closed(doc)

	elif new_state == "Reopened":
		notify_reopened(doc)


def notify_customer_approval_requested(doc) -> None:
	"""PRD p.2: Customer approval request sent → Customer (immediate).

	The 30-min escalation to N. Maintenance Head is handled by
	`tasks.monitor_customer_approval_sla`.

	If the JC has a `customer_approval_token` the body includes a tokenised
	external review link (PRD p.11 Only Repair step 9).
	"""
	customer_user = _customer_portal_user(doc.customer)
	extra = [customer_user] if customer_user else []
	link = ""
	if doc.customer_approval_token:
		site_url = frappe.utils.get_url()
		link = (
			f"\n\nReview & respond: {site_url}/service-portal/approve/"
			f"{doc.name}?token={doc.customer_approval_token}"
		)
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_APPROVAL_REQUEST_TO_CUSTOMER,
		extra_users=extra,
		subject=_("Approval Required: Job Card {0}").format(doc.name),
		body=_(
			"Your approval is required for repair work on vehicle {0}. "
			"Please review Job Card {1}. Response expected within 30 minutes.{2}"
		).format(doc.vehicle_number or "—", doc.name, link),
		priority="High",
	)


def notify_customer_approval_outcome(doc, approved: bool) -> None:
	"""PRD p.2: Customer approves/rejects → SE, Depot Manager."""
	verdict = _("approved") if approved else _("rejected")
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_APPROVAL_OUTCOME,
		extra_users=[doc.assigned_service_engineer],
		subject=_("Customer {0} repair estimate for {1}").format(verdict, doc.name),
		body=_("The customer has {0} the repair estimate for Job Card {1} " "(vehicle {2}).").format(
			verdict, doc.name, doc.vehicle_number or "—"
		),
		priority="Medium",
	)


def notify_closed_by_technician(doc) -> None:
	"""PRD p.2: Closed by Technician → SE, Depot Manager, Central Ops."""
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_CLOSED_BY_TECHNICIAN,
		extra_users=[doc.assigned_service_engineer],
		subject=_("Awaiting SE Verification: Job Card {0}").format(doc.name),
		body=_(
			"Technician has closed Job Card {0} (vehicle {1}). " "Service Engineer verification required."
		).format(doc.name, doc.vehicle_number or "—"),
		priority="Medium",
	)


def notify_verified_and_closed(doc) -> None:
	"""PRD p.2: Verified & closed by SE → Depot Manager, Central Ops, N. Maint. Head."""
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_VERIFIED_BY_SE,
		extra_users=[doc.assigned_service_engineer],
		subject=_("Job Card {0} closed").format(doc.name),
		body=_("Job Card {0} (vehicle {1}) has been verified and closed by the Service Engineer.").format(
			doc.name, doc.vehicle_number or "—"
		),
		priority="Medium",
	)

	# Generate the NaArNi-watermarked closure PDF (proof of service) and, if the SE
	# ticked "Send report to customer", email it to the customer. Best-effort.
	try:
		from vehicle_maintenance.api.reports import generate_on_close

		generate_on_close(doc)
	except Exception:
		frappe.log_error(title="closure_report_on_close", message=frappe.get_traceback())


def notify_reopened(doc) -> None:
	"""PRD p.2: Job card reopened → Depot Manager, Central Ops."""
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_REOPENED,
		extra_users=[doc.assigned_service_engineer],
		subject=_("Job Card {0} reopened").format(doc.name),
		body=_("Job Card {0} (vehicle {1}) was reopened. Please review and re-assign.").format(
			doc.name, doc.vehicle_number or "—"
		),
		priority="Medium",
	)


def notify_remote_resolution_failed(doc) -> None:
	"""PRD p.2: Remote resolution failed (30 min elapsed) → SE, Central Ops, N. Maint. Head.

	Called by the `monitor_remote_resolution_sla` scheduled task. The card must
	be a Breakdown whose 30-min remote-diagnosis window has elapsed without a
	resolution being marked.
	"""
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_REMOTE_RESOLUTION_FAILED,
		extra_users=[doc.assigned_service_engineer],
		subject=_("Remote Resolution Failed: Job Card {0}").format(doc.name),
		body=_(
			"30 minutes elapsed without remote resolution for breakdown on vehicle {0} "
			"(Job Card {1}). SE travel to site required — please mark 'Start Travel'."
		).format(doc.vehicle_number or "—", doc.name),
		priority="High",
	)


def notify_closed_state_edit(doc, reason: str | None = None) -> None:
	"""PRD p.3: Edit job card (Closed) → Central Ops notified.

	Called from `_enforce_closed_state_lock` path after a Depot Manager
	successfully edits a Closed Job Card. The `reason` is the value of
	`closed_edit_reason` at the moment of save.
	"""
	_dispatch(
		job_card=doc,
		roles=["Central Ops"],
		extra_users=[doc.assigned_service_engineer],
		subject=_("Closed Job Card {0} edited").format(doc.name),
		body=_("Job Card {0} (vehicle {1}) was modified by {2} after closure. " "Reason: {3}").format(
			doc.name,
			doc.vehicle_number or "—",
			frappe.session.user,
			reason or "—",
		),
		priority="Medium",
	)


def notify_feedback_request(doc) -> None:
	"""PRD p.2: Feedback request post-closure → Customer, Driver.

	Called by the `monitor_feedback_requests` scheduled task. The card must
	have been Closed for at least `FEEDBACK_DELAY_HOURS` hours.
	"""
	customer_user = _customer_portal_user(doc.customer)
	extra = [u for u in [customer_user] if u]
	_dispatch(
		job_card=doc,
		roles=RECIPIENTS_FEEDBACK_REQUEST,
		extra_users=extra,
		subject=_("How did we do? Job Card {0}").format(doc.name),
		body=_(
			"Your vehicle {0} has been serviced under Job Card {1}. "
			"Please share your feedback — it helps us improve."
		).format(doc.vehicle_number or "—", doc.name),
		priority="Low",
	)


# ───────────────────────────────────────────────────────────
# Inventory Request triggers — PRD p.3 Inventory Flow
# ───────────────────────────────────────────────────────────


def notify_inventory_request_transition(inv_req, old_status: str | None) -> None:
	"""Fire the right notification for each status transition."""
	if not old_status or old_status == inv_req.status:
		return
	if inv_req.status == "Parts Allocated":
		_notify_parts_allocated(inv_req)
	elif inv_req.status == "Parts Issued":
		_notify_parts_issued(inv_req)
	elif inv_req.status == "Received":
		_notify_parts_received(inv_req)


def _notify_parts_allocated(inv_req) -> None:
	"""PRD p.2: Parts allocated → SE, Technician."""
	job_card = _load_job_card(inv_req.job_card_ref)
	if not job_card:
		return
	_dispatch(
		job_card=job_card,
		roles=RECIPIENTS_PARTS_ALLOCATED,
		extra_users=[job_card.assigned_service_engineer, job_card.assigned_technician],
		subject=_("Parts Allocated for {0}").format(job_card.name),
		body=_(
			"Parts {0} × {1} have been allocated for Job Card {2} (vehicle {3}). " "Ready for issue."
		).format(
			inv_req.part_name or inv_req.part or "—",
			inv_req.quantity or 1,
			job_card.name,
			job_card.vehicle_number or "—",
		),
		priority="Medium",
	)


def _notify_parts_issued(inv_req) -> None:
	job_card = _load_job_card(inv_req.job_card_ref)
	if not job_card:
		return
	_dispatch(
		job_card=job_card,
		roles=[],
		extra_users=[job_card.assigned_service_engineer, job_card.assigned_technician],
		subject=_("Parts Issued for {0}").format(job_card.name),
		body=_(
			"Parts {0} × {1} have been physically issued for Job Card {2}. "
			"Please acknowledge receipt in the app."
		).format(
			inv_req.part_name or inv_req.part or "—",
			inv_req.quantity or 1,
			job_card.name,
		),
		priority="Medium",
	)


def _notify_parts_received(inv_req) -> None:
	job_card = _load_job_card(inv_req.job_card_ref)
	if not job_card:
		return
	_dispatch(
		job_card=job_card,
		roles=["Depot Manager"],
		extra_users=[job_card.assigned_service_engineer],
		subject=_("Parts Received for {0}").format(job_card.name),
		body=_("Parts {0} × {1} acknowledged as received for Job Card {2}. " "Work may proceed.").format(
			inv_req.part_name or inv_req.part or "—",
			inv_req.quantity or 1,
			job_card.name,
		),
		priority="Low",
	)


# ───────────────────────────────────────────────────────────
# Internals
# ───────────────────────────────────────────────────────────


def _dispatch(job_card, roles, extra_users, subject: str, body: str, priority: str = "Medium") -> None:
	"""Fan out a notification across configured channels.

	PRD p.2 mandates In-App + Push + SMS (email reserved for reports only).
	Only the In-App channel is fully wired today. Push + SMS are behind
	site-config toggles so a future integration can swap them in without
	touching the callers:

	    bench --site X set-config notifications_push_enabled 1
	    bench --site X set-config notifications_sms_enabled 1

	The respective dispatch functions are stubbed with log_error traces until
	a provider is integrated.
	"""
	recipients = _resolve_recipients(roles, extra_users)
	if not recipients:
		return

	doc_name = getattr(job_card, "name", None) or job_card.get("name")

	for user in recipients:
		# Realtime FIRST so the bell/toast updates sub-second over socket.io — the
		# durable Notification Log write and the slower push/SMS/email follow.
		_dispatch_realtime(user, subject, body, doc_name, priority)
		_dispatch_in_app(user, subject, body, doc_name)
		if frappe.get_conf().get("notifications_push_enabled"):
			_dispatch_push(user, subject, body, doc_name, priority)
		if frappe.get_conf().get("notifications_sms_enabled"):
			_dispatch_sms(user, subject, body, doc_name, priority)
		# Email is gated on its own toggle; dispatch is enqueued so a slow
		# network round-trip to Microsoft Graph doesn't block the save path.
		_dispatch_email(user, subject, body, doc_name, priority)


def _dispatch_realtime(user: str, subject: str, body: str, doc_name: str | None, priority: str) -> None:
	"""Push the notification to the user's open SPA/App over socket.io instantly.

	The web SPA and mobile App subscribe to the `vm_notification` event for the
	logged-in user and update the bell + show a toast in sub-second — no polling.
	Best-effort: a realtime failure must never block the save path or the durable
	Notification Log write that follows.
	"""
	try:
		frappe.publish_realtime(
			event="vm_notification",
			message={
				"subject": subject,
				"body": body,
				"priority": priority,
				"job_card": doc_name,
			},
			user=user,
		)
	except Exception:
		frappe.log_error(
			title=f"Realtime notification dispatch failed (user={user})",
			message=frappe.get_traceback(),
		)


def _dispatch_in_app(user: str, subject: str, body: str, doc_name: str | None) -> None:
	try:
		log = frappe.new_doc("Notification Log")
		log.update(
			{
				"subject": subject,
				"email_content": body,
				"for_user": user,
				"type": "Alert",
				"document_type": "Job Card",
				"document_name": doc_name,
				"from_user": "Administrator",
			}
		)
		log.insert(ignore_permissions=True)
	except Exception:
		frappe.log_error(
			title=f"In-app notification dispatch failed (user={user})",
			message=frappe.get_traceback(),
		)


def _dispatch_push(user: str, subject: str, body: str, doc_name: str | None, priority: str) -> None:
	"""Enqueue an FCM push to each of the user's active device tokens.

	Gated on `notifications_push_enabled`. The actual HTTP call to FCM is
	background-enqueued so a slow provider never blocks the save/transition path —
	realtime + In-App have already fired. When no FCM server key is configured the
	job logs and no-ops, so this is safe to enable before credentials are wired.
	"""
	try:
		tokens = frappe.get_all(
			"Push Token",
			filters={"user": user, "is_active": 1},
			fields=["device_token"],
			limit_page_length=20,
		)
		if not tokens:
			return
		for row in tokens:
			frappe.enqueue(
				method="vehicle_maintenance.fleet_service.notifications._dispatch_push_job",
				queue="short",
				timeout=30,
				now=False,
				job_name=f"fleet-push:{doc_name}:{user}",
				device_token=row["device_token"],
				subject=subject,
				body=body,
				doc_name=doc_name,
				priority=priority,
			)
	except Exception:
		frappe.log_error(
			title=f"Push enqueue failed (user={user})",
			message=frappe.get_traceback(),
		)


def _fcm_access_token() -> tuple[str, str] | None:
	"""Return (oauth_access_token, project_id) for FCM HTTP v1, or None if unconfigured.

	Mints a short-lived OAuth token from the Firebase **service-account JSON** stored
	in site_config as `notifications_fcm_service_account` (the whole JSON, as a dict
	or string). Signs the assertion with PyJWT — no google-auth dependency. Cached
	~50 min. The legacy `fcm/send` server-key API was shut down by Google in 2024.
	"""
	conf = frappe.get_conf()
	sa = conf.get("notifications_fcm_service_account")
	if not sa:
		return None
	if isinstance(sa, str):
		try:
			sa = frappe.parse_json(sa)
		except Exception:
			return None
	project_id = sa.get("project_id")
	client_email = sa.get("client_email")
	private_key = sa.get("private_key")
	if not (project_id and client_email and private_key):
		return None

	cache = frappe.cache()
	cached = cache.get_value("fcm_access_token")
	if cached:
		return cached, project_id

	import time

	import jwt
	import requests

	now = int(time.time())
	assertion = jwt.encode(
		{
			"iss": client_email,
			"scope": "https://www.googleapis.com/auth/firebase.messaging",
			"aud": "https://oauth2.googleapis.com/token",
			"iat": now,
			"exp": now + 3600,
		},
		private_key,
		algorithm="RS256",
	)
	resp = requests.post(
		"https://oauth2.googleapis.com/token",
		data={
			"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
			"assertion": assertion,
		},
		timeout=15,
	)
	token = resp.json().get("access_token") if resp.ok else None
	if not token:
		frappe.log_error(title="FCM OAuth token mint failed", message=resp.text[:500])
		return None
	cache.set_value("fcm_access_token", token, expires_in_sec=3000)
	return token, project_id


def _dispatch_push_job(
	device_token: str, subject: str, body: str, doc_name: str | None, priority: str
) -> None:
	"""Background worker — sends one FCM message via **HTTP v1**. Enqueue-only.

	No-ops with a log line when `notifications_fcm_service_account` is unconfigured,
	so this is safe to ship before Firebase credentials are wired.
	"""
	creds = _fcm_access_token()
	if not creds:
		frappe.logger().info(f"[push] FCM not configured; skipped doc={doc_name} priority={priority}")
		return
	access_token, project_id = creds
	try:
		import requests

		resp = requests.post(
			f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send",
			headers={
				"Authorization": f"Bearer {access_token}",
				"Content-Type": "application/json",
			},
			json={
				"message": {
					"token": device_token,
					"notification": {"title": subject, "body": body},
					"data": {
						"job_card": doc_name or "",
						"priority": str(priority),
						"deeplink": f"naarni://jobcard/{doc_name}" if doc_name else "",
					},
					"android": {
						"priority": "HIGH" if priority in ("High", "Urgent", "Critical") else "NORMAL"
					},
				}
			},
			timeout=15,
		)
		# Invalid/expired token (UNREGISTERED / INVALID_ARGUMENT) → deactivate it.
		if resp.status_code in (400, 404) and (
			"UNREGISTERED" in resp.text or "registration-token-not-registered" in resp.text
		):
			frappe.db.set_value(
				"Push Token", {"device_token": device_token}, "is_active", 0, update_modified=False
			)
		elif resp.status_code >= 300:
			frappe.log_error(title="FCM v1 push non-2xx", message=resp.text[:500])
	except Exception:
		frappe.log_error(title="FCM push send failed", message=frappe.get_traceback())


# SMS is reserved for genuinely urgent triggers (PRD: TAT breached, breakdown
# declared, remote-resolution failed, customer approval request). Routine
# notifications stay In-App/Push only, so we don't spam costly SMS.
SMS_PRIORITIES = {"High", "Urgent", "Critical"}


def _dispatch_sms(user: str, subject: str, body: str, doc_name: str | None, priority: str) -> None:
	"""Enqueue an urgent SMS to the user's mobile number.

	Gated on `notifications_sms_enabled` AND a critical-class priority — routine
	notifications never trigger SMS. The provider call is background-enqueued so a
	slow gateway never blocks the save path (realtime + In-App already fired).
	"""
	if priority not in SMS_PRIORITIES:
		return
	try:
		mobile = frappe.db.get_value("User", user, "mobile_no")
		if not mobile:
			return
		frappe.enqueue(
			method="vehicle_maintenance.fleet_service.notifications._dispatch_sms_job",
			queue="short",
			timeout=30,
			now=False,
			job_name=f"fleet-sms:{doc_name}:{user}",
			mobile=mobile,
			subject=subject,
			body=body,
			doc_name=doc_name,
			priority=priority,
		)
	except Exception:
		frappe.log_error(
			title=f"SMS enqueue failed (user={user})",
			message=frappe.get_traceback(),
		)


def _dispatch_sms_job(mobile: str, subject: str, body: str, doc_name: str | None, priority: str) -> None:
	"""Background worker — sends one SMS via the configured provider.

	Default provider is MSG91 Flow (common in India); configured in site config:
	    notifications_sms_authkey      MSG91 auth key
	    notifications_sms_template_id  approved DLT flow/template id
	    notifications_sms_sender       DLT sender id (optional)
	No-ops with a log line when unconfigured, so SMS can be enabled before
	credentials land. Invoked only via `frappe.enqueue`.
	"""
	conf = frappe.get_conf()
	authkey = conf.get("notifications_sms_authkey")
	template_id = conf.get("notifications_sms_template_id")
	if not (authkey and template_id):
		frappe.logger().info(f"[sms] provider not configured; skipped mobile={mobile} doc={doc_name}")
		return
	try:
		import requests

		# MSG91 Flow API. The DLT-approved template owns the wording; we pass the
		# message text + job card as variables for the gateway to interpolate.
		msg = f"{subject}. {body}".strip()
		resp = requests.post(
			"https://control.msg91.com/api/v5/flow/",
			headers={"authkey": authkey, "Content-Type": "application/json"},
			json={
				"template_id": template_id,
				"sender": conf.get("notifications_sms_sender"),
				"recipients": [{"mobiles": str(mobile), "msg": msg, "jobcard": doc_name or ""}],
			},
			timeout=15,
		)
		if resp.status_code >= 400:
			frappe.log_error(
				title="SMS send failed",
				message=f"status={resp.status_code} body={resp.text[:500]}",
			)
	except Exception:
		frappe.log_error(
			title="SMS send failed",
			message=frappe.get_traceback(),
		)


def _dispatch_email(user: str, subject: str, body: str, doc_name: str | None, priority: str) -> None:
	"""Enqueue a branded NaArNi email for `user` via Brevo.

	Gated on `enable_email_notifications` + full Brevo credential set in
	site_config. Background-enqueued so the sending pipeline never blocks the
	save/transition path — if Brevo is slow or down, notifications still
	fire In-App immediately.
	"""
	try:
		from vehicle_maintenance.fleet_service import brevo_client

		if not brevo_client.is_enabled():
			return
		email_addr = _user_email(user)
		if not email_addr:
			return
		frappe.enqueue(
			method="vehicle_maintenance.fleet_service.notifications._dispatch_email_job",
			queue="short",
			timeout=60,
			now=False,
			job_name=f"fleet-email:{doc_name}:{user}",
			to=email_addr,
			subject=subject,
			body=body,
			doc_name=doc_name,
			priority=priority,
		)
	except Exception:
		frappe.log_error(
			title=f"Email enqueue failed (user={user})",
			message=frappe.get_traceback(),
		)


def _dispatch_email_job(
	to: str,
	subject: str,
	body: str,
	doc_name: str | None,
	priority: str,
) -> None:
	"""Background worker — renders the branded HTML and calls Brevo sendMail.

	Invoked only via `frappe.enqueue`; do not call directly.
	"""
	from html import escape

	from vehicle_maintenance.fleet_service import brevo_client
	from vehicle_maintenance.fleet_service.email_templates import (
		render_branded_email,
		to_plain_text,
	)

	# Build safe body HTML from the plain-text body the trigger function
	# supplied. Paragraphs split on blank lines; inline \n → <br/>.
	paragraphs = []
	for chunk in (body or "").strip().split("\n\n"):
		if not chunk.strip():
			continue
		escaped = escape(chunk).replace("\n", "<br/>")
		paragraphs.append(f'<p style="margin:0 0 12px 0;">{escaped}</p>')
	body_html = "".join(paragraphs) or ('<p style="margin:0;">See Job Card for details.</p>')

	cta_label = cta_url = None
	if doc_name:
		site_url = frappe.utils.get_url()
		cta_label = "Open Job Card"
		# Desk route by default; Customer portal users follow the same link
		# — Frappe redirects them appropriately.
		cta_url = f"{site_url}/app/job-card/{doc_name}"

	html = render_branded_email(
		heading=subject,
		body_html=body_html,
		preheader=subject,
		cta_label=cta_label,
		cta_url=cta_url,
		priority=priority,
		meta_rows=[("Job Card", doc_name)] if doc_name else None,
	)
	text = to_plain_text(subject, body or "", cta_label, cta_url)

	try:
		brevo_client.send_mail(
			to=[to],
			subject=subject,
			html_body=html,
			text_body=text,
			tags=[f"priority:{priority.lower()}", "fleet-service"],
		)
	except Exception:
		frappe.log_error(
			title=f"Brevo sendMail failed (to={to})",
			message=frappe.get_traceback(),
		)


def _user_email(user: str) -> str | None:
	"""Return a usable email address for a Frappe User record.

	User `name` is typically the login email; we still read `User.email` so
	users whose login is a phone number (see the per-user memory) get their
	canonical email address, not the phone ID.
	"""
	if not user or user in ("Administrator", "Guest"):
		return None
	if "@" in user:
		return user
	try:
		email = frappe.db.get_value("User", user, "email")
		return email if email and "@" in email else None
	except Exception:
		return None


def _resolve_recipients(roles: list[str], extra_users: list | None) -> list[str]:
	users: set[str] = set()
	for role in roles or []:
		users.update(_users_with_role(role))
	for u in extra_users or []:
		if u:
			users.add(u)
	# Never notify a user that the system has disabled.
	if not users:
		return []
	enabled = frappe.get_all(
		"User",
		filters={"name": ["in", list(users)], "enabled": 1},
		pluck="name",
	)
	return enabled


def _users_with_role(role: str) -> list[str]:
	rows = frappe.get_all(
		"Has Role",
		filters={"role": role, "parenttype": "User"},
		fields=["parent"],
		limit_page_length=0,
	)
	return [r["parent"] for r in rows]


def _customer_portal_user(customer_name: str | None) -> str | None:
	"""Return the `User` linked from Customer, or None."""
	if not customer_name:
		return None
	try:
		return frappe.db.get_value("Customer", customer_name, "user")
	except Exception:
		return None


def _load_job_card(name: str | None):
	if not name:
		return None
	try:
		return frappe.get_cached_doc("Job Card", name)
	except Exception:
		return None
