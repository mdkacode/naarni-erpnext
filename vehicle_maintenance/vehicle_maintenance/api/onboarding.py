"""Job Card onboarding flow APIs.

Implements the gated post-creation flow:

    Created → Assigned → Inspection (every value) → Before-Images → Proceed (Open→WIP)

Each gate is enforced server-side. The frontend wizard ([JobCardOnboarding.vue])
is a UI for these endpoints; bypassing it (Desk, Postman, direct
`transition_job_card`) still hits the same gates because `complete_onboarding`
and the `before_workflow_action` hook in [job_card.py] validate identically.

Inspection content is sourced from the `PMS Check Sheet` master DocType
(seeded as a fixture: SHEET_A 103 items, SHEET_B 125, SHEET_C 143). Per-item
responses are stored as `Job Card Inspection Response` child rows on the JC.
"""

from __future__ import annotations

import json
from typing import Any

import frappe
from frappe import _
from frappe.utils import now_datetime

# Required Before-Images. Keep this list small and explicit — every entry blocks proceed.
REQUIRED_BEFORE_PHOTOS: tuple[str, ...] = (
	"chassis_photo",
	"odometer_photo",
)


# ---------- helpers ----------


def _ok(data: Any, message: str | None = None) -> dict:
	return {"success": True, "data": data, "message": message}


def _load_jc(job_card_name: str):
	if not job_card_name:
		frappe.throw(_("job_card_name is required"))
	if not frappe.db.exists("Job Card", job_card_name):
		frappe.throw(_("Job Card {0} not found").format(job_card_name))
	frappe.has_permission("Job Card", doc=job_card_name, ptype="read", throw=True)
	return frappe.get_doc("Job Card", job_card_name)


def _resolve_template_code(jc) -> str | None:
	"""Pick the right Sheet for this JC. Only PMS + Repair has a sheet."""
	if jc.job_card_type != "PMS + Repair":
		return None
	if jc.get("pms_check_sheet"):
		return jc.pms_check_sheet
	odo = int(jc.get("odometer_reading") or 0)
	if odo <= 20_000:
		return "SHEET_A"
	if odo <= 40_000:
		return "SHEET_B"
	if odo <= 80_000:
		return "SHEET_C"
	return "SHEET_D"


def _load_template(template_code: str):
	if not frappe.db.exists("PMS Check Sheet", template_code):
		frappe.throw(_("PMS Check Sheet {0} is not seeded").format(template_code))
	return frappe.get_doc("PMS Check Sheet", template_code)


def _expected_sr_nos(jc) -> list[int]:
	"""Return the master list of sr_no the technician must answer for this JC."""
	code = _resolve_template_code(jc)
	if not code or not frappe.db.exists("PMS Check Sheet", code):
		return []
	return [
		int(i.sr_no)
		for i in frappe.get_all(
			"PMS Check Item",
			filters={"parent": code, "parenttype": "PMS Check Sheet"},
			fields=["sr_no"],
			order_by="sr_no asc",
		)
	]


def _compute_completion(jc) -> tuple[float, list[int]]:
	"""(pct, missing_sr_nos) for the JC's inspection responses."""
	expected = _expected_sr_nos(jc)
	if not expected:
		# Non-PMS or template not seeded — treat as 100% (no gate).
		return 100.0, []
	answered = {int(r.sr_no) for r in (jc.inspection_responses or []) if r.status and r.sr_no}
	missing = [s for s in expected if s not in answered]
	pct = round(100.0 * (len(expected) - len(missing)) / max(len(expected), 1), 1)
	return pct, missing


def _gate_status(jc) -> dict:
	has_assignment = bool(jc.get("assigned_service_engineer") or jc.get("assigned_technician"))
	pct, missing_inspection = _compute_completion(jc)
	inspection_done = pct >= 100.0
	missing_photos = [f for f in REQUIRED_BEFORE_PHOTOS if not jc.get(f)]
	photos_done = not missing_photos

	return {
		"created": True,
		"assigned": has_assignment,
		"inspection_done": inspection_done,
		"inspection_pct": pct,
		"inspection_missing_count": len(missing_inspection),
		"inspection_missing": missing_inspection[:50],  # cap to keep response small
		"photos_done": photos_done,
		"photos_missing": missing_photos,
		"ready_to_proceed": has_assignment and inspection_done and photos_done,
	}


# ---------- Read APIs ----------


@frappe.whitelist()
def get_check_sheet_for_jc(job_card_name: str) -> dict:
	"""Return the resolved PMS Check Sheet (with all items) for a Job Card.

	Used by the onboarding wizard's checklist step. Items come grouped by section.
	"""
	jc = _load_jc(job_card_name)
	code = _resolve_template_code(jc)
	if not code:
		return _ok(
			{
				"applicable": False,
				"reason": "Not a PMS + Repair Job Card — no checklist required.",
			}
		)

	if not frappe.db.exists("PMS Check Sheet", code):
		return _ok(
			{
				"applicable": True,
				"template_code": code,
				"items": [],
				"warning": _("Template {0} not seeded. Run `bench migrate` to load the fixture.").format(
					code
				),
			}
		)

	template = _load_template(code)

	# Persist the template link on the JC for later reads.
	if jc.get("pms_check_sheet") != code:
		frappe.db.set_value("Job Card", job_card_name, "pms_check_sheet", code, update_modified=False)

	# Group items by section preserving sequence.
	sections: dict[str, dict] = {}
	section_order: list[str] = []
	for item in sorted(template.items, key=lambda i: int(i.sr_no)):
		if item.section_code not in sections:
			section_order.append(item.section_code)
			sections[item.section_code] = {
				"section_code": item.section_code,
				"section_title": item.section_title,
				"items": [],
			}
		sections[item.section_code]["items"].append(
			{
				"sr_no": int(item.sr_no),
				"parameter": item.parameter,
				"inspection_method": item.inspection_method,
			}
		)

	# Existing responses by sr_no for resume-on-reload UX.
	responses_by_sr = {}
	for r in jc.inspection_responses or []:
		responses_by_sr[int(r.sr_no)] = {
			"status": r.status,
			"remarks": r.remarks or "",
			"photo": r.photo or "",
		}

	return _ok(
		{
			"applicable": True,
			"template_code": code,
			"title": template.title,
			"total_items": len(template.items),
			"sections": [sections[c] for c in section_order],
			"responses": responses_by_sr,
		}
	)


@frappe.whitelist()
def get_onboarding_state(job_card_name: str) -> dict:
	"""Return gate-by-gate status. Cheap; safe to poll."""
	jc = _load_jc(job_card_name)
	state = _gate_status(jc)
	state.update(
		{
			"job_card": jc.name,
			"workflow_state": jc.workflow_state,
			"job_card_type": jc.job_card_type,
			"vehicle_number": jc.vehicle_number,
			"odometer_reading": jc.odometer_reading,
			"pms_check_sheet": jc.get("pms_check_sheet"),
			"assigned_service_engineer": jc.get("assigned_service_engineer"),
			"assigned_technician": jc.get("assigned_technician"),
			"chassis_photo": jc.get("chassis_photo"),
			"odometer_photo": jc.get("odometer_photo"),
		}
	)
	return _ok(state)


# ---------- Write APIs (one per gate) ----------


@frappe.whitelist()
def assign_job_card(
	job_card_name: str,
	assigned_service_engineer: str | None = None,
	assigned_technician: str | None = None,
) -> dict:
	"""DM-only assignment write. At least one of SE/Tech is required."""
	if not (assigned_service_engineer or assigned_technician):
		frappe.throw(_("Pick at least one Service Engineer or Technician."))

	jc = _load_jc(job_card_name)
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	user_roles = set(frappe.get_roles(frappe.session.user))
	if not (user_roles & {"Depot Manager", "N. Maintenance Head", "Administrator"}):
		frappe.throw(_("Only the Depot Manager can assign a Job Card."), frappe.PermissionError)

	if assigned_service_engineer is not None:
		jc.assigned_service_engineer = assigned_service_engineer or None
	if assigned_technician is not None:
		jc.assigned_technician = assigned_technician or None

	# permlevel:1 fields — bypass validator since the role check above is authoritative.
	jc.save(ignore_permissions=True)
	return _ok(_gate_status(jc), message=_("Assignment saved."))


@frappe.whitelist()
def save_inspection_responses(job_card_name: str, responses: str | list) -> dict:
	"""Upsert per-item checklist responses. Idempotent.

	`responses` = [{sr_no, status, remarks?, photo?}].
	`status` ∈ {OK, RR, RI, NA}. Only items whose sr_no is in the master template are written.
	"""
	if isinstance(responses, str):
		try:
			responses = json.loads(responses)
		except json.JSONDecodeError:
			frappe.throw(_("Invalid responses payload"))
	responses = responses or []

	jc = _load_jc(job_card_name)
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	code = _resolve_template_code(jc)
	if not code:
		frappe.throw(_("Inspection only applies to PMS + Repair job cards."))
	template = _load_template(code)

	item_index = {int(i.sr_no): i for i in template.items}
	valid_status = {"OK", "RR", "RI", "NA"}
	user = frappe.session.user
	now = now_datetime()

	existing = {int(r.sr_no): r for r in (jc.inspection_responses or [])}

	written = 0
	for entry in responses:
		try:
			sr = int(entry.get("sr_no"))
		except (TypeError, ValueError):
			continue
		if sr not in item_index:
			continue
		status = (entry.get("status") or "").upper()
		if status and status not in valid_status:
			frappe.throw(_("Invalid status {0} for Sr. {1}").format(status, sr))

		master_item = item_index[sr]
		payload = {
			"sr_no": sr,
			"section_code": master_item.section_code,
			"parameter_snapshot": master_item.parameter,
			"status": status or None,
			"remarks": entry.get("remarks") or None,
			"photo": entry.get("photo") or None,
			"recorded_at": now,
			"recorded_by": user,
		}
		if sr in existing:
			existing[sr].update(payload)
		else:
			jc.append("inspection_responses", payload)
		written += 1

	pct, _missing = _compute_completion(jc)
	jc.inspection_completion_pct = pct
	if jc.get("pms_check_sheet") != code:
		jc.pms_check_sheet = code

	jc.save(ignore_permissions=False)
	return _ok(
		{"completion_pct": pct, "saved": written, "total": len(template.items)},
		message=_("Inspection responses saved."),
	)


@frappe.whitelist()
def attach_before_photos(
	job_card_name: str,
	chassis_photo: str | None = None,
	odometer_photo: str | None = None,
) -> dict:
	"""Attach Before-Image URLs (already uploaded via /api/method/upload_file)."""
	jc = _load_jc(job_card_name)
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if chassis_photo is not None:
		jc.chassis_photo = chassis_photo or None
	if odometer_photo is not None:
		jc.odometer_photo = odometer_photo or None

	jc.save(ignore_permissions=False)
	return _ok(_gate_status(jc), message=_("Photos attached."))


@frappe.whitelist()
def complete_onboarding(job_card_name: str) -> dict:
	"""Final gate. Validates all 4 steps then transitions Open → WIP.

	Hard-fails (frappe.throw) if anything is missing — never silently advances.
	"""
	jc = _load_jc(job_card_name)
	frappe.has_permission("Job Card", doc=job_card_name, ptype="write", throw=True)

	if jc.workflow_state not in ("Open", "Reopened"):
		frappe.throw(
			_("Onboarding can only complete from Open / Reopened (current: {0}).").format(jc.workflow_state)
		)

	state = _gate_status(jc)
	if not state["assigned"]:
		frappe.throw(_("Step 2 incomplete: assign a Service Engineer or Technician first."))
	if not state["inspection_done"]:
		frappe.throw(
			_("Step 3 incomplete: {0} checklist item(s) still missing.").format(
				state["inspection_missing_count"]
			)
		)
	if not state["photos_done"]:
		labels = {"chassis_photo": "Chassis / VIN plate", "odometer_photo": "Odometer dashboard"}
		missing = ", ".join(labels.get(p, p) for p in state["photos_missing"])
		frappe.throw(_("Step 4 incomplete: missing Before-Image(s) — {0}.").format(missing))

	jc.workflow_state = "WIP"
	jc.onboarding_completed_at = now_datetime()
	jc.save(ignore_permissions=True)

	return _ok(
		{"job_card": jc.name, "workflow_state": jc.workflow_state},
		message=_("Onboarding complete. Job Card is now WIP."),
	)
