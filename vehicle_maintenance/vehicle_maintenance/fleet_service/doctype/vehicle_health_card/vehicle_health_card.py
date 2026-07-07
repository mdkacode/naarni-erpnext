"""Vehicle Health Card DocType.

Auto-generated on Service-Engineer closure of a PMS + Repair Job Card. Captures
the Pre-PMS vs Post-PMS category breakdown and overall improvement percentage
for the customer-facing Health Card report (PRD p.7-8).

`generate_from_job_card` is the single entry point called from
`job_card.py::_generate_health_card_if_closed` on SE closure. It is idempotent
on the (vehicle, job_card) pair — if a card already exists for the same source
Job Card, no duplicate is created.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import now_datetime

CATEGORY_SCORE_FIELDS = (
	"category",
	"component_count",
	"pre_pms_score",
	"post_pms_score",
	"improvement",
)


class VehicleHealthCard(Document):
	pass


def generate_from_job_card(job_card, *, force: bool = False) -> str | None:
	"""Create (or return existing) Vehicle Health Card for this Job Card.

	Args:
	    job_card: Saved Job Card document. Must be PMS + Repair type.
	    force: If True, regenerate even when a card already exists.

	Returns:
	    The Health Card name, or None if this Job Card is not eligible
	    (e.g., not PMS + Repair, or no category scores recorded).
	"""
	if getattr(job_card, "job_card_type", None) != "PMS + Repair":
		return None

	existing = frappe.db.get_value("Vehicle Health Card", {"job_card": job_card.name}, "name")
	if existing and not force:
		return existing
	if existing and force:
		frappe.delete_doc("Vehicle Health Card", existing, ignore_permissions=True, force=True)

	category_rows = job_card.get("category_scores", [])
	if not category_rows and not job_card.get("post_pms_score"):
		return None

	hc = frappe.new_doc("Vehicle Health Card")
	hc.vehicle = job_card.vehicle
	hc.job_card = job_card.name
	hc.generated_on = now_datetime()
	hc.overall_pre_score = job_card.pre_pms_score
	hc.overall_post_score = job_card.post_pms_score
	hc.improvement = job_card.score_improvement
	hc.odometer_reading = job_card.odometer_reading

	for row in category_rows:
		hc.append("category_scores", {field: row.get(field) for field in CATEGORY_SCORE_FIELDS})

	for alert in _resolved_alerts_for(job_card):
		hc.append("resolved_alerts", alert)

	hc.summary_notes = _build_summary(job_card)
	hc.insert(ignore_permissions=True)
	return hc.name


def _resolved_alerts_for(job_card) -> list[dict]:
	"""Resolved Service Tickets for this vehicle fixed during the service window
	(from the Job Card's creation onward) → Health Card line items."""
	if not job_card.vehicle:
		return []
	rows = frappe.get_all(
		"Service Ticket",
		filters={
			"vehicle": job_card.vehicle,
			"status": "Resolved",
			"resolved_at": [">=", job_card.creation],
		},
		fields=["name", "title", "severity", "resolved_at", "resolved_by_name", "resolution_reason"],
		order_by="resolved_at desc",
		limit_page_length=50,
	)
	return [
		{
			"ticket": r["name"],
			"alert_name": r.get("title"),
			"severity": r.get("severity"),
			"resolved_on": r.get("resolved_at"),
			"resolved_by": r.get("resolved_by_name"),
			"response": r.get("resolution_reason"),
		}
		for r in rows
	]


def _build_summary(job_card) -> str:
	"""Compose a short human-readable summary for the Health Card PDF."""
	pre = job_card.pre_pms_score or 0
	post = job_card.post_pms_score or 0
	impr = job_card.score_improvement or 0
	parts_count = len(
		[
			i
			for i in job_card.get("repair_items", [])
			if i.get("activity_type") in ("Spare Replacement", "Both")
		]
	)
	labour_count = len(
		[i for i in job_card.get("repair_items", []) if i.get("activity_type") == "Only Repair"]
	)
	maint_count = len(job_card.get("maintenance_items", []))

	lines = [
		_("Vehicle {0} underwent scheduled maintenance (odometer {1} km).").format(
			job_card.vehicle_number or job_card.vehicle,
			job_card.odometer_reading or 0,
		),
		_("Pre-PMS Health: {0:.1f}% · Post-PMS Health: {1:.1f}% · Improvement: {2:+.1f}%").format(
			pre, post, impr
		),
		_("Summary: {0} parts replaced, {1} labour repairs, {2} maintenance jobs.").format(
			parts_count,
			labour_count,
			maint_count,
		),
	]
	return "\n".join(lines)


# ── Whitelisted APIs ──


@frappe.whitelist()
def get_for_job_card(job_card_name: str) -> dict:
	"""Return the Health Card linked to a given Job Card, for the Vue portal.

	Envelope shape matches other vehicle_maintenance APIs. Permission-gated:
	requesting user must have read access to the source Job Card.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)

	name = frappe.db.get_value("Vehicle Health Card", {"job_card": job_card_name}, "name")
	if not name:
		return {"success": True, "data": None}

	hc = frappe.get_doc("Vehicle Health Card", name)
	return {
		"success": True,
		"data": {
			"name": hc.name,
			"vehicle": hc.vehicle,
			"vehicle_number": hc.vehicle_number,
			"job_card": hc.job_card,
			"generated_on": hc.generated_on,
			"overall_pre_score": float(hc.overall_pre_score or 0),
			"overall_post_score": float(hc.overall_post_score or 0),
			"improvement": float(hc.improvement or 0),
			"odometer_reading": hc.odometer_reading,
			"summary_notes": hc.summary_notes,
			"category_scores": [{f: row.get(f) for f in CATEGORY_SCORE_FIELDS} for row in hc.category_scores],
			"resolved_alerts": [
				{
					"alert_name": row.get("alert_name"),
					"severity": row.get("severity"),
					"resolved_on": row.get("resolved_on"),
					"resolved_by": row.get("resolved_by"),
					"response": row.get("response"),
					"ticket": row.get("ticket"),
				}
				for row in hc.get("resolved_alerts", [])
			],
		},
	}
