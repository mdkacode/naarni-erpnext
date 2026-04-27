"""Whitelisted APIs for the structured PMS checklist flow.

Powers the section-by-section mobile UI:
- get_check_sheet_template     → load 103-item template grouped by section
- start_section                → begin per-section timer
- end_section                  → flush responses + close timer
- submit_checklist             → finalize after every item has a response
- list_templates / get_template_detail → admin dashboard read APIs
- section_timing_summary       → aggregate per-technician per-section metrics
"""

from __future__ import annotations

import json
from typing import Any

import frappe
from frappe import _
from frappe.utils import now_datetime, time_diff_in_seconds

# ---------- helpers ----------


def _ok(data: Any, message: str | None = None) -> dict:
	return {"success": True, "data": data, "message": message}


def _ensure_jc_access(job_card: str):
	if not job_card:
		frappe.throw(_("job_card is required"))
	if not frappe.db.exists("Job Card", job_card):
		frappe.throw(_("Job Card {0} not found").format(job_card))
	frappe.has_permission("Job Card", doc=job_card, ptype="write", throw=True)
	return frappe.get_doc("Job Card", job_card)


def _resolve_template_code(job_card_doc) -> str:
	"""Return the SHEET_x code for a Job Card, falling back to odometer mapping."""
	if job_card_doc.get("check_sheet_template"):
		return job_card_doc.check_sheet_template
	odo = int(job_card_doc.get("odometer_reading") or 0)
	if odo <= 20_000:
		return "SHEET_A"
	if odo <= 40_000:
		return "SHEET_B"
	if odo <= 80_000:
		return "SHEET_C"
	return "SHEET_D"


def _load_template(template_code: str):
	if not frappe.db.exists("Check Sheet Template", template_code):
		frappe.throw(_("Check Sheet Template {0} is not seeded").format(template_code))
	return frappe.get_doc("Check Sheet Template", template_code)


def _section_lookup(template) -> dict:
	return {s.section_code: s for s in template.sections}


# ---------- Read APIs ----------


@frappe.whitelist()
def get_check_sheet_template(job_card: str | None = None, template_code: str | None = None) -> dict:
	"""Return template grouped by sections.

	If `job_card` is passed, the template is auto-resolved (and persisted on the JC).
	Otherwise `template_code` is used directly. Read-only operation.
	"""
	if job_card:
		jc = _ensure_jc_access(job_card)
		template_code = _resolve_template_code(jc)
		if jc.get("check_sheet_template") != template_code and frappe.db.exists(
			"Check Sheet Template", template_code
		):
			frappe.db.set_value("Job Card", job_card, "check_sheet_template", template_code)

	if not template_code:
		frappe.throw(_("Either job_card or template_code is required"))

	template = _load_template(template_code)

	sections_out: list[dict] = []
	for section in sorted(template.sections, key=lambda s: s.sequence or 0):
		items = [
			{
				"sr_no": i.sr_no,
				"parameter": i.parameter,
				"inspection_method": i.inspection_method,
				"std_time_minutes": i.std_time_minutes or 0,
			}
			for i in template.items
			if i.section_code == section.section_code
		]
		items.sort(key=lambda x: x["sr_no"])
		sections_out.append(
			{
				"section_code": section.section_code,
				"section_title": section.section_title,
				"sequence": section.sequence,
				"item_count": len(items),
				"items": items,
			}
		)

	return _ok(
		{
			"template_code": template.template_code,
			"title": template.title,
			"total_std_time_minutes": template.total_std_time_minutes or 0,
			"total_items": len(template.items),
			"sections": sections_out,
		}
	)


@frappe.whitelist()
def list_templates() -> dict:
	"""Admin dashboard: list all available templates with summary stats."""
	rows = frappe.get_all(
		"Check Sheet Template",
		fields=[
			"name",
			"template_code",
			"title",
			"is_active",
			"applicable_km_min",
			"applicable_km_max",
			"total_std_time_minutes",
		],
		order_by="template_code asc",
	)
	for r in rows:
		r["item_count"] = frappe.db.count(
			"Check Sheet Item", {"parent": r["name"], "parenttype": "Check Sheet Template"}
		)
		r["section_count"] = frappe.db.count(
			"Check Sheet Section", {"parent": r["name"], "parenttype": "Check Sheet Template"}
		)
	return _ok(rows)


# ---------- Write APIs ----------


@frappe.whitelist()
def start_section(job_card: str, section_code: str) -> dict:
	"""Begin a section timer. Idempotent: returns the open row if one already exists."""
	if not section_code:
		frappe.throw(_("section_code is required"))

	jc = _ensure_jc_access(job_card)
	template = _load_template(_resolve_template_code(jc))
	sections = _section_lookup(template)
	if section_code not in sections:
		frappe.throw(_("Unknown section {0}").format(section_code))
	section = sections[section_code]

	user = frappe.session.user

	# Idempotency: reuse an open timing row from the same user
	for row in jc.section_timings or []:
		if row.section_code == section_code and row.technician == user and not row.ended_at:
			return _ok(
				{
					"section_code": section_code,
					"started_at": str(row.started_at),
					"reused": True,
				}
			)

	# Std time = sum across this section's items
	std_time = sum((i.std_time_minutes or 0) for i in template.items if i.section_code == section_code)

	jc.append(
		"section_timings",
		{
			"section_code": section_code,
			"section_title": section.section_title,
			"technician": user,
			"started_at": now_datetime(),
			"std_time_minutes": std_time,
		},
	)
	jc.save(ignore_permissions=False)
	return _ok(
		{
			"section_code": section_code,
			"started_at": str(now_datetime()),
			"reused": False,
		}
	)


@frappe.whitelist()
def end_section(job_card: str, section_code: str, responses: str | list | None = None) -> dict:
	"""Close the open timing row and upsert per-item responses for the section.

	`responses` is a list of {sr_no, status, remarks, photo}. JSON string accepted from the wire.
	"""
	if not section_code:
		frappe.throw(_("section_code is required"))

	jc = _ensure_jc_access(job_card)
	template = _load_template(_resolve_template_code(jc))

	if isinstance(responses, str):
		try:
			responses = json.loads(responses)
		except json.JSONDecodeError:
			frappe.throw(_("Invalid responses payload"))
	responses = responses or []

	user = frappe.session.user
	now = now_datetime()

	# 1. Close the timing row for this user + section
	closed = False
	for row in jc.section_timings or []:
		if row.section_code == section_code and row.technician == user and not row.ended_at:
			row.ended_at = now
			row.duration_seconds = int(time_diff_in_seconds(row.ended_at, row.started_at) or 0)
			closed = True
			break
	if not closed:
		# User never called start_section — synthesize a zero-duration row so submission isn't blocked.
		jc.append(
			"section_timings",
			{
				"section_code": section_code,
				"section_title": next(
					(s.section_title for s in template.sections if s.section_code == section_code),
					section_code,
				),
				"technician": user,
				"started_at": now,
				"ended_at": now,
				"duration_seconds": 0,
			},
		)

	# 2. Upsert responses keyed by sr_no
	item_index = {i.sr_no: i for i in template.items if i.section_code == section_code}
	valid_status = {"OK", "RR", "RI", "NA"}
	existing_by_sr = {r.sr_no: r for r in (jc.inspection_responses or []) if r.section_code == section_code}

	for entry in responses:
		sr_no = int(entry.get("sr_no") or 0)
		if sr_no not in item_index:
			frappe.throw(_("Item Sr. {0} is not in section {1}").format(sr_no, section_code))
		status = (entry.get("status") or "").upper()
		if status and status not in valid_status:
			frappe.throw(_("Invalid status {0} for Sr. {1}").format(status, sr_no))

		payload = {
			"sr_no": sr_no,
			"section_code": section_code,
			"parameter_snapshot": item_index[sr_no].parameter,
			"status": status or None,
			"remarks": entry.get("remarks"),
			"photo": entry.get("photo"),
			"recorded_at": now,
			"recorded_by": user,
		}
		if sr_no in existing_by_sr:
			existing_by_sr[sr_no].update(payload)
		else:
			jc.append("inspection_responses", payload)

	jc.save(ignore_permissions=False)

	return _ok(
		{
			"section_code": section_code,
			"ended_at": str(now),
			"responses_saved": len(responses),
		}
	)


@frappe.whitelist()
def submit_checklist(job_card: str) -> dict:
	"""Finalize the inspection: validate every item has a response, stamp completion."""
	jc = _ensure_jc_access(job_card)
	template = _load_template(_resolve_template_code(jc))

	expected = {(i.sr_no, i.section_code) for i in template.items}
	actual = {(r.sr_no, r.section_code) for r in jc.inspection_responses or [] if r.status}
	missing = expected - actual
	if missing:
		frappe.throw(
			_("{0} item(s) still missing a response. First missing: Sr. {1} in section {2}").format(
				len(missing), *next(iter(sorted(missing)))
			)
		)

	jc.inspection_completed_at = now_datetime()
	jc.save(ignore_permissions=False)

	return _ok(
		{
			"job_card": jc.name,
			"inspection_completed_at": str(jc.inspection_completed_at),
			"total_responses": len(jc.inspection_responses or []),
		}
	)


# ---------- Admin dashboard summary ----------


@frappe.whitelist()
def section_timing_summary(
	from_date: str | None = None,
	to_date: str | None = None,
	technician: str | None = None,
	template_code: str | None = "SHEET_A",
) -> dict:
	"""Aggregate avg/min/max duration per section + per-technician for the dashboard panel.

	Restricted to Admin / Depot Manager / Central Ops / Service Engineer.
	"""
	allowed_roles = {"System Manager", "Depot Manager", "Central Ops", "Service Engineer"}
	user_roles = set(frappe.get_roles(frappe.session.user))
	if not (allowed_roles & user_roles):
		frappe.throw(_("Not permitted"), frappe.PermissionError)

	conds = ["1=1"]
	params: dict[str, Any] = {}
	if from_date:
		conds.append("jc.creation >= %(from_date)s")
		params["from_date"] = from_date
	if to_date:
		conds.append("jc.creation <= %(to_date)s")
		params["to_date"] = to_date
	if technician:
		conds.append("st.technician = %(technician)s")
		params["technician"] = technician
	if template_code:
		conds.append("jc.check_sheet_template = %(template_code)s")
		params["template_code"] = template_code

	where = " AND ".join(conds)

	rows = frappe.db.sql(
		f"""
        SELECT
            st.section_code,
            st.section_title,
            COUNT(st.name)                                AS sample_size,
            ROUND(AVG(st.duration_seconds)/60.0, 2)       AS avg_minutes,
            ROUND(MIN(st.duration_seconds)/60.0, 2)       AS min_minutes,
            ROUND(MAX(st.duration_seconds)/60.0, 2)       AS max_minutes,
            ROUND(AVG(st.std_time_minutes), 2)            AS std_minutes
        FROM `tabJob Card Section Timing` st
        INNER JOIN `tabJob Card` jc ON jc.name = st.parent
        WHERE st.ended_at IS NOT NULL AND {where}
        GROUP BY st.section_code, st.section_title
        ORDER BY st.section_code
        """,
		params,
		as_dict=True,
	)

	return _ok(rows)
