"""Job Card Closure Record (Phase 2).

Immutable snapshot of a Job Card at the moment it was closed. PRD p.3 audit
rule: "Previous closure record retained" on reopen. A new record is created
each time a JC enters 'Closed'; when subsequently Reopened we backfill the
reopen_* columns on the matching record.
"""

from __future__ import annotations

import json as _json

import frappe
from frappe.model.document import Document
from frappe.utils import now_datetime


# Fields we capture into snapshot_json so a full restore is possible.
SNAPSHOT_FIELDS = (
    "name", "job_card_type", "repair_subtype", "vehicle", "vehicle_number",
    "customer", "customer_name", "odometer_reading", "depot", "priority",
    "complaint_description", "se_observations",
    "workflow_state", "opened_at", "closed_at",
    "estimated_cost", "actual_cost",
    "pre_pms_score", "post_pms_score", "score_improvement",
    "force_closed", "force_close_severity", "force_close_reason",
    "sla_target_hours", "sla_deadline", "sla_breached",
    "health_card", "requires_customer_approval",
    # Breakdown-specific
    "incident_place", "fault_code_1", "fault_code_2", "fault_code_3",
    "fix_type", "recurrence_risk", "occurrence_risk",
    "total_downtime_minutes",
    "remote_resolution_status", "remote_resolution_started_at",
    "remote_resolution_failed_at",
    "rca_notes", "rca_received_at",
    "force_override", "force_override_reason",
    "process_override", "process_override_steps",
)

SNAPSHOT_CHILD_TABLES = (
    "repair_items", "maintenance_items", "software_components",
    "subsystems", "groups_impacted", "category_scores",
)


class JobCardClosureRecord(Document):
    pass


def create_snapshot(job_card) -> str:
    """Create a Closure Record from a just-closed Job Card. Returns its name.

    The sequence number increments for each closure of the same JC so reopens
    produce a natural history (1, 2, 3…).
    """
    prior = frappe.get_all(
        "Job Card Closure Record",
        filters={"job_card": job_card.name},
        pluck="sequence",
    )
    next_seq = (max(prior) if prior else 0) + 1

    snapshot = {f: job_card.get(f) for f in SNAPSHOT_FIELDS}
    for child_field in SNAPSHOT_CHILD_TABLES:
        snapshot[child_field] = [
            {k: v for k, v in row.as_dict().items() if not k.startswith("_")}
            for row in job_card.get(child_field, [])
        ]

    record = frappe.new_doc("Job Card Closure Record")
    record.update({
        "job_card": job_card.name,
        "closed_at": job_card.closed_at or now_datetime(),
        "closed_by": frappe.session.user,
        "sequence": next_seq,
        "pre_pms_score": job_card.pre_pms_score,
        "post_pms_score": job_card.post_pms_score,
        "estimated_cost": job_card.estimated_cost,
        "actual_cost": job_card.actual_cost,
        "workflow_state_at_close": job_card.workflow_state,
        "force_closed": job_card.force_closed,
        "force_close_severity": job_card.force_close_severity,
        "force_close_reason": job_card.force_close_reason,
        "snapshot_json": _json.dumps(snapshot, default=str, indent=2),
    })
    record.insert(ignore_permissions=True)
    return record.name


def stamp_reopen(job_card_name: str, reason: str) -> str | None:
    """Backfill the most recent open Closure Record's reopen_* columns.

    Returns the record name, or None if there's no open record to stamp.
    Called from the Job Card controller when workflow_state leaves Closed.
    """
    latest = frappe.get_all(
        "Job Card Closure Record",
        filters={"job_card": job_card_name, "reopened_at": ["is", "not set"]},
        fields=["name"],
        order_by="sequence desc",
        limit=1,
    )
    if not latest:
        return None
    frappe.db.set_value(
        "Job Card Closure Record", latest[0]["name"],
        {
            "reopened_at": now_datetime(),
            "reopened_by": frappe.session.user,
            "reopen_reason": (reason or "").strip(),
        },
        update_modified=False,
    )
    return latest[0]["name"]


@frappe.whitelist()
def list_for_job_card(job_card_name: str) -> dict:
    """Return the full closure history for a Job Card, oldest → newest."""
    frappe.has_permission("Job Card", doc=job_card_name, throw=True)
    rows = frappe.get_all(
        "Job Card Closure Record",
        filters={"job_card": job_card_name},
        fields=[
            "name", "sequence", "closed_at", "closed_by",
            "workflow_state_at_close",
            "force_closed", "force_close_severity", "force_close_reason",
            "pre_pms_score", "post_pms_score",
            "estimated_cost", "actual_cost",
            "reopened_at", "reopened_by", "reopen_reason",
        ],
        order_by="sequence asc",
        limit_page_length=0,
    )
    return {"success": True, "data": rows}
