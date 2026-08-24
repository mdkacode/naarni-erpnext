# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Guaranteed-delivery push pipeline.

The old path was fire-and-forget: build a payload, `frappe.enqueue` it, and hope.
Three things silently ate notifications, and all three are the kind that only
show up in the field:

* **The payload carried a `notification` block.** Android then renders the alert
  itself and never calls `onMessageReceived`, so the app's own high-importance
  channel, its deeplink and its sound were all bypassed whenever the app was
  backgrounded — which is nearly always. Worse, the manifest pointed FCM's
  default channel at `job_cards`, an id the app deletes on every launch, so the
  SDK fell back to a DEFAULT-importance "Miscellaneous" channel: no heads-up,
  no bell, easily muted. A critical alert arrived as a grey line in the shade.
* **A single transient failure was fatal.** One 503 from FCM, one DNS blip, one
  RQ worker restart, and that alert was gone with nothing but a log line.
* **Nothing recorded whether a push landed**, so "notifications aren't working"
  could never be confirmed or refuted.

This module fixes all three. Every push becomes a durable **Push Delivery** row
written in the caller's transaction *before* any network work, so the intent
survives Redis being down, the worker being killed, and the site restarting. A
worker claims the row, sends a **data-only** message, and records the outcome.
Anything not `Sent` is retried on a backoff by [`sweep_pending`], which runs
every minute. Urgent traffic that still has not landed escalates to SMS.

The invariant: a Push Delivery row in any state other than `Sent` or `Dead` is
work the sweeper still owns. Nothing is ever dropped on the floor.
"""

from __future__ import annotations

import hashlib

import frappe
from frappe.utils import add_to_date, now_datetime

# ── Retry schedule ──
# Seconds after the previous attempt. Urgent traffic retries hard and early: a
# breakdown alert is worthless twenty minutes late. Routine traffic backs off so
# an FCM outage doesn't turn into a self-inflicted thundering herd.
BACKOFF_URGENT = [0, 5, 15, 30, 60, 120, 300, 600, 900, 900]
BACKOFF_ROUTINE = [0, 30, 120, 300, 600, 1200, 1800, 1800]

# A Sending row older than this was orphaned by a worker that died mid-send.
# Reclaimed by the sweeper — at-least-once beats a lost critical alert.
STUCK_SENDING_SEC = 120

# Urgent push with no accepted delivery on ANY of the user's devices after this
# long falls back to SMS. Chosen to sit just past the urgent backoff's first
# four attempts, so SMS only fires once push has genuinely had its chance.
SMS_FALLBACK_AFTER_SEC = 180

# Rows the sweeper will re-enqueue per run. Bounds a backlog replay after an
# outage so the short queue isn't buried in one tick.
SWEEP_BATCH = 300

# Ledger rows older than this are pruned by the daily housekeeping task.
LEDGER_RETENTION_DAYS = 30

# Only these escalate to SMS. "Medium" (what a *warning* alert maps to) still
# gets HIGH-priority push — the user's requirement is that warnings are
# immediate — but it does not spend money on an SMS.
SMS_CLASS = {"critical", "urgent", "high"}

FCM_ENDPOINT = "https://fcm.googleapis.com/v1/projects/{project}/messages:send"

# FCM says this token is gone: the app was uninstalled, or the token rotated.
# Permanent, so the row dies and the token is deactivated rather than retried.
DEAD_TOKEN_MARKERS = (
	"UNREGISTERED",
	"registration-token-not-registered",
	"INVALID_ARGUMENT",
	"SENDER_ID_MISMATCH",
)


def is_enabled() -> bool:
	"""Push is on only when explicitly configured — this stays a no-op otherwise."""
	return bool(frappe.get_conf().get("notifications_push_enabled"))


def token_hash(device_token: str) -> str:
	"""Stable short id for a device token.

	FCM tokens are too long for a `Data` column, so they live in `Small Text`
	and can't carry a unique index. Hashing gives dedupe and a log-safe handle
	that never puts the live token in an error message.
	"""
	return hashlib.sha256((device_token or "").encode()).hexdigest()[:32]


# ───────────────────────────────────────────────────────────
# Enqueue
# ───────────────────────────────────────────────────────────


def _default_deeplink(doctype: str | None, name: str | None) -> str:
	"""A tap target for a caller that didn't supply one.

	A notification with nowhere to go is a notification the person swipes away,
	so this is a floor rather than a convenience: every push gets a route, and
	forgetting to pass one degrades to the record it is about instead of to a
	dead tap.
	"""
	if not name:
		return ""
	if doctype == "Service Ticket":
		return f"naarni://ticket/{name}"
	if doctype == "VM Chat Room":
		return f"naarni://chat/{name}"
	return f"naarni://jobcard/{name}"


def queue_push(
	user: str,
	title: str,
	body: str,
	*,
	reference_doctype: str | None = None,
	reference_name: str | None = None,
	deeplink: str | None = None,
	priority: str = "Medium",
	dedup_suffix: str = "",
) -> list[str]:
	"""Write a Push Delivery row per active device and kick off delivery.

	Returns the ledger row names (empty when push is off or the user has no
	device). Never raises: a notification failing to *queue* must not roll back
	the job card or ticket that triggered it.

	The row is committed as part of the caller's transaction, so even if the
	enqueue below fails outright — Redis down, queue full — the sweeper finds
	the row within a minute and delivers it. That is the whole point.
	"""
	if not is_enabled():
		return []

	try:
		tokens = frappe.get_all(
			"Push Token",
			filters={"user": user, "is_active": 1},
			fields=["name", "device_token", "platform"],
			order_by="modified desc",
			limit_page_length=0,
		)
	except Exception:
		frappe.log_error(title=f"Push token lookup failed (user={user})", message=frappe.get_traceback())
		return []

	if not tokens:
		return []

	urgent = (priority or "").strip().lower() != "low"
	link = deeplink or _default_deeplink(reference_doctype, reference_name)
	# One notification can legitimately reach the same handset twice if a stale
	# duplicate row exists for the token; collapse on the hash so it doesn't.
	seen: set[str] = set()
	names: list[str] = []

	for row in tokens:
		digest = token_hash(row["device_token"])
		if digest in seen:
			continue
		seen.add(digest)

		dedup = f"{reference_doctype or 'x'}:{reference_name or 'x'}:{dedup_suffix or title}:{digest}"
		try:
			doc = frappe.get_doc(
				{
					"doctype": "Push Delivery",
					"user": user,
					"device_token": row["device_token"],
					"push_token": row["name"],
					"status": "Queued",
					"priority": priority,
					"urgent": 1 if urgent else 0,
					"attempts": 0,
					"next_attempt_at": now_datetime(),
					"title": (title or "")[:255],
					"body": body or "",
					"deeplink": link,
					"reference_doctype": reference_doctype or "",
					"reference_name": reference_name or "",
					"dedup_key": dedup,
				}
			).insert(ignore_permissions=True)
		except Exception:
			frappe.log_error(title=f"Push ledger write failed (user={user})", message=frappe.get_traceback())
			continue

		names.append(doc.name)
		_try_enqueue(doc.name, urgent)

	return names


def _try_enqueue(delivery: str, urgent: bool) -> None:
	"""Hand the row to a worker now. Best-effort — the sweeper is the guarantee.

	`enqueue_after_commit=True` so the worker can never read a row the caller's
	transaction hasn't committed yet. That ordering was the other way round on
	the old path, which is a race that only shows up under load.
	"""
	try:
		frappe.enqueue(
			method="vehicle_maintenance.fleet_service.push_delivery.deliver",
			queue="short",
			timeout=90,
			enqueue_after_commit=True,
			job_name=f"push:{delivery}",
			delivery=delivery,
		)
	except Exception:
		# Deliberately swallowed and NOT logged as an error: the row is durable
		# and the sweeper will pick it up. Logging here would turn a routine
		# Redis hiccup into an error-log flood during exactly the incident when
		# the log needs to stay readable.
		frappe.logger().warning(f"[push] enqueue failed for {delivery}; sweeper will retry")


# ───────────────────────────────────────────────────────────
# Delivery
# ───────────────────────────────────────────────────────────


def deliver(delivery: str) -> None:
	"""Background worker — send one ledger row.

	Claims the row atomically so the sweeper and the original enqueue can never
	both send it. Every exit path leaves the row in a state the sweeper can
	reason about; nothing is left `Sending` for longer than [`STUCK_SENDING_SEC`].
	"""
	if not _claim(delivery):
		return

	row = frappe.db.get_value(
		"Push Delivery",
		delivery,
		[
			"name",
			"user",
			"device_token",
			"push_token",
			"title",
			"body",
			"deeplink",
			"priority",
			"urgent",
			"attempts",
			"reference_doctype",
			"reference_name",
		],
		as_dict=True,
	)
	if not row:
		return

	try:
		ok, permanent, detail = _send(row)
	except Exception:
		ok, permanent, detail = False, False, frappe.get_traceback()[-480:]

	if ok:
		frappe.db.set_value(
			"Push Delivery",
			delivery,
			{
				"status": "Sent",
				"sent_at": now_datetime(),
				"fcm_message_id": detail[:140],
				"last_error": "",
			},
			update_modified=False,
		)
		frappe.db.commit()
		return

	if permanent:
		_kill_token(row.get("push_token"), row.get("device_token"))
		frappe.db.set_value(
			"Push Delivery",
			delivery,
			{"status": "Dead", "last_error": detail[:480]},
			update_modified=False,
		)
		frappe.db.commit()
		return

	_schedule_retry(delivery, int(row.get("attempts") or 0), bool(row.get("urgent")), detail)


def _claim(delivery: str) -> bool:
	"""Move Queued/Retrying → Sending, incrementing the attempt counter.

	A conditional UPDATE rather than read-then-write: two workers racing for the
	same row (the enqueue and the sweeper, most often) both run this, and only
	the one that actually changed a row proceeds. Read-then-write would send the
	same alert twice, which trains people to ignore it.
	"""
	frappe.db.sql(
		"""
		UPDATE `tabPush Delivery`
		   SET status = 'Sending', attempts = attempts + 1, modified = %(now)s
		 WHERE name = %(name)s
		   AND status IN ('Queued', 'Retrying')
		""",
		{"name": delivery, "now": now_datetime()},
	)
	claimed = frappe.db.sql("SELECT ROW_COUNT()")[0][0] == 1
	frappe.db.commit()
	return claimed


def _schedule_retry(delivery: str, attempts: int, urgent: bool, detail: str) -> None:
	"""Park the row for its next attempt, or bury it once the schedule is spent."""
	schedule = BACKOFF_URGENT if urgent else BACKOFF_ROUTINE
	if attempts >= len(schedule):
		frappe.db.set_value(
			"Push Delivery",
			delivery,
			{"status": "Dead", "last_error": detail[:480]},
			update_modified=False,
		)
		frappe.log_error(
			title="Push delivery exhausted retries",
			message=f"delivery={delivery} attempts={attempts}\n{detail[:1200]}",
		)
	else:
		frappe.db.set_value(
			"Push Delivery",
			delivery,
			{
				"status": "Retrying",
				"next_attempt_at": add_to_date(now_datetime(), seconds=schedule[attempts]),
				"last_error": detail[:480],
			},
			update_modified=False,
		)
	frappe.db.commit()


def _kill_token(push_token: str | None, device_token: str | None) -> None:
	"""Deactivate a token FCM has told us is gone, so we stop paying for it."""
	try:
		if push_token:
			frappe.db.set_value("Push Token", push_token, "is_active", 0, update_modified=False)
		elif device_token:
			frappe.db.set_value(
				"Push Token", {"device_token": device_token}, "is_active", 0, update_modified=False
			)
	except Exception:
		frappe.log_error(title="Push token deactivate failed", message=frappe.get_traceback())


# ───────────────────────────────────────────────────────────
# FCM HTTP v1
# ───────────────────────────────────────────────────────────


def _send(row: dict) -> tuple[bool, bool, str]:
	"""POST one message to FCM. Returns `(sent, permanent_failure, detail)`.

	**Data-only, deliberately.** A `notification` block hands rendering to the
	Android system tray and skips `onMessageReceived` while the app is
	backgrounded, which is precisely when the app needs to run: to pick the
	high-importance alert channel, apply the user's chosen tone, attach the
	deeplink, and dedupe against what the socket already showed. Data-only plus
	`priority: HIGH` buys our code a wake-up even in Doze. The chat path learned
	this the hard way; the alert path had not.

	iOS is served by the `apns` block instead, which is APNs-specific and does
	not reintroduce an Android notification block.
	"""
	from vehicle_maintenance.fleet_service.notifications import _fcm_access_token

	creds = _fcm_access_token()
	if not creds:
		# Unconfigured is not a delivery failure to retry forever — but it is
		# also not permanent, so the row waits for credentials to be wired.
		return False, False, "FCM not configured (notifications_fcm_service_account)"
	access_token, project_id = creds

	urgent = bool(row.get("urgent"))
	title = row.get("title") or "Naarni Fleet Service"
	body = row.get("body") or ""
	link = row.get("deeplink") or ""
	ref_type = row.get("reference_doctype") or ""
	ref_name = row.get("reference_name") or ""

	message = {
		"token": row["device_token"],
		"data": {
			# `type` lets the handset route without parsing the deeplink.
			"type": "alert" if ref_type == "Service Ticket" else "notification",
			"title": title,
			"body": body,
			"priority": str(row.get("priority") or "Medium"),
			"urgent": "1" if urgent else "0",
			"deeplink": link,
			# Kept for older builds that read `route`; harmless duplication on
			# a payload well under the 4 KB ceiling.
			"route": link,
			"reference_doctype": ref_type,
			"reference_name": ref_name,
			# The app marks this id seen so the catch-up sync doesn't raise the
			# same alert a second time when it later pulls the bell.
			"delivery": row["name"],
		},
		"android": {
			# HIGH for everything that is not explicitly Low. A *warning* alert
			# arrives as severity Medium, and the requirement is that warnings
			# are immediate — NORMAL priority lets Doze sit on it for hours.
			"priority": "HIGH" if urgent else "NORMAL",
			# No collapse_key: unlike a chat burst, two alerts are two incidents
			# and neither may swallow the other.
			"direct_boot_ok": True,
		},
		"apns": {
			"headers": {
				"apns-priority": "10" if urgent else "5",
				"apns-push-type": "alert",
			},
			"payload": {
				"aps": {
					"alert": {"title": title, "body": body},
					"sound": "default",
					"interruption-level": "time-sensitive" if urgent else "active",
					"content-available": 1,
				}
			},
		},
	}

	import requests

	try:
		resp = requests.post(
			FCM_ENDPOINT.format(project=project_id),
			headers={
				"Authorization": f"Bearer {access_token}",
				"Content-Type": "application/json",
			},
			json={"message": message},
			timeout=20,
		)
	except Exception as exc:
		# Transport-level: DNS, TLS, timeout. Always retryable.
		return False, False, f"transport: {exc}"

	if resp.status_code < 300:
		try:
			return True, False, (resp.json() or {}).get("name", "")
		except Exception:
			return True, False, ""

	text = (resp.text or "")[:480]

	# A stale OAuth token: drop the cache so the next attempt mints a fresh one
	# instead of replaying the same rejected credential through every retry.
	if resp.status_code in (401, 403):
		frappe.cache().delete_value("fcm_access_token")
		return False, False, f"{resp.status_code}: {text}"

	if resp.status_code in (400, 404) and any(m in text for m in DEAD_TOKEN_MARKERS):
		return False, True, f"{resp.status_code}: {text}"

	# 429 and 5xx are FCM asking us to come back later; other 4xx are our bug,
	# but retrying a handful of times costs nothing and a malformed-payload
	# regression should not silently discard a critical alert.
	return False, False, f"{resp.status_code}: {text}"


# ───────────────────────────────────────────────────────────
# Sweeper — the actual delivery guarantee
# ───────────────────────────────────────────────────────────


def sweep_pending() -> None:
	"""Re-enqueue every push that hasn't landed yet. Runs every minute.

	This is what turns best-effort into guaranteed. It covers the cases the
	enqueue path structurally cannot:

	* the enqueue never reached Redis (Redis down, queue full);
	* the worker took the job and was killed mid-send (`Sending`, orphaned);
	* FCM was unreachable and the row is parked on its backoff;
	* the whole site restarted between the ledger write and the send.

	Idempotent by construction: [`_claim`] means a row already in flight is
	skipped, so overlapping sweeps cannot double-send.
	"""
	if not is_enabled():
		return

	now = now_datetime()

	# Reclaim rows a dead worker left mid-flight. Marking them Retrying rather
	# than Queued keeps the attempt count honest.
	try:
		frappe.db.sql(
			"""
			UPDATE `tabPush Delivery`
			   SET status = 'Retrying', next_attempt_at = %(now)s
			 WHERE status = 'Sending'
			   AND modified < %(cutoff)s
			""",
			{"now": now, "cutoff": add_to_date(now, seconds=-STUCK_SENDING_SEC)},
		)
		frappe.db.commit()
	except Exception:
		frappe.log_error(title="Push sweeper reclaim failed", message=frappe.get_traceback())

	try:
		due = frappe.get_all(
			"Push Delivery",
			filters={
				"status": ["in", ["Queued", "Retrying"]],
				"next_attempt_at": ["<=", now],
			},
			fields=["name", "urgent"],
			# Urgent first: during a backlog replay a breakdown must not queue
			# behind two hundred routine job-card notifications.
			order_by="urgent desc, next_attempt_at asc",
			limit_page_length=SWEEP_BATCH,
		)
	except Exception:
		frappe.log_error(title="Push sweeper query failed", message=frappe.get_traceback())
		return

	for row in due:
		_try_enqueue(row["name"], bool(row["urgent"]))

	_escalate_undelivered(now)


def _escalate_undelivered(now) -> None:
	"""SMS the people whose urgent push still hasn't landed on any device.

	The last line of defence, and the one that covers what no amount of retrying
	can: a handset that is off, out of coverage, or has had the app force-stopped
	by an OEM battery manager, where FCM will accept the message and simply never
	deliver it. Critical-class only — a warning gets a hard-retried push, not a
	billed SMS.
	"""
	cutoff = add_to_date(now, seconds=-SMS_FALLBACK_AFTER_SEC)
	try:
		stale = frappe.get_all(
			"Push Delivery",
			filters={
				"urgent": 1,
				"fallback_sent": 0,
				"status": ["in", ["Queued", "Retrying", "Dead"]],
				"creation": ["<=", cutoff],
			},
			fields=["name", "user", "title", "body", "priority", "reference_name"],
			order_by="creation asc",
			limit_page_length=100,
		)
	except Exception:
		frappe.log_error(title="Push escalation query failed", message=frappe.get_traceback())
		return

	if not stale:
		return

	from vehicle_maintenance.fleet_service import notifications as notif

	for row in stale:
		# Another handset may have taken it. One accepted delivery for this
		# notification is enough — don't SMS someone whose phone already buzzed.
		delivered = frappe.db.exists(
			"Push Delivery",
			{
				"user": row["user"],
				"reference_name": row.get("reference_name") or "",
				"title": row.get("title") or "",
				"status": "Sent",
			},
		)
		frappe.db.set_value("Push Delivery", row["name"], "fallback_sent", 1, update_modified=False)
		if delivered:
			continue
		if (row.get("priority") or "").strip().lower() not in SMS_CLASS:
			continue
		try:
			notif._dispatch_sms(
				row["user"], row.get("title") or "", row.get("body") or "", row.get("reference_name"), "High"
			)
		except Exception:
			frappe.log_error(title="Push SMS escalation failed", message=frappe.get_traceback())

	frappe.db.commit()


def prune_ledger() -> None:
	"""Drop settled ledger rows past retention. Daily housekeeping.

	Only `Sent` and `Dead` rows: anything else is still owed a delivery, and a
	retention sweep must never be the thing that discards an undelivered alert.
	"""
	cutoff = add_to_date(now_datetime(), days=-LEDGER_RETENTION_DAYS)
	try:
		frappe.db.sql(
			"""
			DELETE FROM `tabPush Delivery`
			 WHERE status IN ('Sent', 'Dead')
			   AND creation < %(cutoff)s
			 LIMIT 5000
			""",
			{"cutoff": cutoff},
		)
		frappe.db.commit()
	except Exception:
		frappe.log_error(title="Push ledger prune failed", message=frappe.get_traceback())


# ───────────────────────────────────────────────────────────
# Diagnostics
# ───────────────────────────────────────────────────────────


def health(hours: int = 24) -> dict:
	"""Delivery stats for the last `hours` — what 'is push working?' should read.

	Surfaced through `api.notifications.push_health` so the answer comes from the
	ledger rather than from someone staring at a handset.
	"""
	since = add_to_date(now_datetime(), hours=-abs(int(hours or 24)))
	rows = frappe.db.sql(
		"""
		SELECT status, COUNT(*) AS n
		  FROM `tabPush Delivery`
		 WHERE creation >= %(since)s
		 GROUP BY status
		""",
		{"since": since},
		as_dict=True,
	)
	counts = {r["status"]: r["n"] for r in rows}
	total = sum(counts.values())
	sent = counts.get("Sent", 0)
	return {
		"enabled": is_enabled(),
		"configured": bool(frappe.get_conf().get("notifications_fcm_service_account")),
		"window_hours": abs(int(hours or 24)),
		"total": total,
		"sent": sent,
		"pending": counts.get("Queued", 0) + counts.get("Retrying", 0) + counts.get("Sending", 0),
		"dead": counts.get("Dead", 0),
		"success_rate": round(sent / total, 4) if total else None,
		"active_devices": frappe.db.count("Push Token", {"is_active": 1}),
	}
