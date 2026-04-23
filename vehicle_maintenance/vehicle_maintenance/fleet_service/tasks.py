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
    "Only Repair": 4 * 3600,                        # Regular
    "Only Repair Accidental Major": 24 * 3600,      # Accidental Major
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
    "PMS + Repair": ["Depot Manager", "Central Ops", "Service Engineer",
                     "N. Maintenance Head"],
    "Only Repair": ["Depot Manager", "Central Ops", "Service Engineer",
                    "N. Maintenance Head"],
    "Only Repair Accidental Major": ["Service Engineer", "Aftersales Eng",
                                     "N. Maintenance Head"],
    "Software Update": ["Service Engineer", "Depot Manager",
                        "N. Maintenance Head"],
    "Breakdown": ["Service Engineer", "Central Ops", "Aftersales Eng",
                  "N. Maintenance Head"],
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
                "Job Card {0} ({1}) for vehicle {2} has breached its "
                "{3:.0f}-minute SLA target."
            ).format(
                card["name"],
                label,
                card.get("vehicle_number") or "—",
                target_seconds / 60,
            ),
        )
        frappe.db.set_value(
            "Job Card", card["name"], "sla_breach_sent", 1, update_modified=False
        )
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
    frappe.db.set_value(
        "Job Card", card["name"], "sla_warning_sent", 1, update_modified=False
    )


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
                "Job Card", card["name"], "customer_approval_escalated", 1,
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
                "Job Card", card["name"], "feedback_request_sent", 1,
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
