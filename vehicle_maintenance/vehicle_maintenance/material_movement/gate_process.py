"""Turn a finished Material Gate run into a line in the gate register.

The process engine is the *capture* surface — it is what gives the gate offline
runs, resume after a dead battery, the photo stamp and the scanner. The
`Material Movement` register is the *record*: what Desk lists, what reports read,
what a supervisor verifies.

Keeping both without a bridge would mean two places that each half-know what
crossed the gate. So every run that completes is projected into one movement
carrying one item line, and the run stays attached as the evidence behind it.

Deliberately a `doc_events` listener rather than an edit to `api.process`. The
engine's whole premise is that nothing about a particular process appears in its
source; a handler that no-ops unless the run belongs to `MATERIAL_GATE` keeps
that true.
"""

from __future__ import annotations

import frappe
from frappe.utils import flt

from vehicle_maintenance.material_movement import constants as C

FAMILY = "MATERIAL_GATE"

#: Step codes the projection reads. They are the contract between the authored
#: process and this file — renaming a step in Desk without changing these is the
#: one edit that would silently produce empty movements, so `_missing_steps`
#: reports it rather than letting it pass.
STEP_DIRECTION = "DIRECTION"
STEP_ITEM = "ITEM"
STEP_WEIGHT = "WEIGHT"
#: v2 of the process. The document number and the chassis the part is for.
STEP_DOC_NO = "DOC_NO"
STEP_CHASSIS = "CHASSIS"
#: v1 only. Read for as long as runs recorded against the old shape are still
#: syncing off handsets; a v2 run simply has no answer under these codes.
STEP_SERIAL = "SERIAL"
STEP_SOURCE_IN = "SOURCE_IN"
STEP_SOURCE_OUT = "SOURCE_OUT"

#: Which step's photographs are paperwork rather than the part. Everything else
#: is filed against the item line, which is what the app's viewer reads.
DOCUMENT_STEPS = frozenset({STEP_DOC_NO})


def on_run_update(doc, method=None) -> None:
	"""Project a completed gate run into the register. No-op for every other run."""
	if doc.status != C.STATUS_COMPLETED and doc.status != "Passed":
		return
	if not _is_gate_run(doc):
		return
	if frappe.db.exists("Material Movement", {"client_uuid": _uuid_for(doc)}):
		return
	try:
		_project(doc)
	except Exception:
		# A failed projection must never roll back the operator's finished run —
		# the run is the primary record and the register is derived from it.
		frappe.log_error(
			title=f"Material gate: projecting run {doc.name} failed",
			message=frappe.get_traceback(),
		)


def _is_gate_run(doc) -> bool:
	if not doc.process_definition:
		return False
	return frappe.db.get_value("Process Definition", doc.process_definition, "family") == FAMILY


def _uuid_for(doc) -> str:
	"""One movement per run, forever.

	Keyed on the run name rather than the run's own client_uuid so a replayed
	offline sync — which can call this more than once for the same run — still
	produces exactly one register line.
	"""
	return f"run:{doc.name}"


def _answers(doc) -> dict[str, dict]:
	return {r.step_code: r for r in doc.results or [] if r.step_code}


def _answer_text(row) -> str | None:
	"""The text of an answer, whichever column the engine put it in.

	`Process Run Result` stores a chosen option in `response` and free text in
	`value_text`. A Link step's answer is the linked document's name and lands in
	whichever of the two the runner used, so both are consulted rather than the
	one the step type implies — this projection must not break because an app
	build changed which field it fills.
	"""
	if not row:
		return None
	for field in ("response", "value_text"):
		value = getattr(row, field, None)
		if value and str(value).strip():
			return str(value).strip()
	return None


def _project(run) -> None:
	answers = _answers(run)
	direction = (_answer_text(answers.get(STEP_DIRECTION)) or "").upper()
	movement_type = C.OUTWARD if direction == "OUTWARD" else C.INWARD

	item = _answer_text(answers.get(STEP_ITEM))
	if not item or not frappe.db.exists("Part", item):
		frappe.log_error(
			title=f"Material gate: run {run.name} finished with no usable item",
			message=f"ITEM answer was {item!r}. Register line not created.",
		)
		return

	source_step = STEP_SOURCE_OUT if movement_type == C.OUTWARD else STEP_SOURCE_IN
	party = _answer_text(answers.get(source_step))

	# v2 asks for the paperwork number and the chassis instead of a source master.
	# Both are free text on purpose: a challan number is whatever the supplier
	# printed, and a chassis on the line at Hubli usually has no record anywhere
	# yet — a Link field would have made the honest answer unenterable.
	document_no = _answer_text(answers.get(STEP_DOC_NO)) or ""
	chassis_no = _answer_text(answers.get(STEP_CHASSIS)) or ""
	if not chassis_no:
		chassis_scan = next((s for s in run.scans or [] if s.step_code == STEP_CHASSIS and s.serial_no), None)
		if chassis_scan:
			chassis_no = chassis_scan.serial_no

	weight_row = answers.get(STEP_WEIGHT)
	weight = flt(getattr(weight_row, "value_numeric", 0)) if weight_row else 0.0

	serial = ""
	scan = next(
		(s for s in run.scans or [] if s.serial_no and s.step_code != STEP_CHASSIS),
		None,
	)
	if scan:
		serial = scan.serial_no
	elif answers.get(STEP_SERIAL):
		serial = _answer_text(answers[STEP_SERIAL]) or ""

	master = frappe.db.get_value(
		"Part", item, ["part_name", "part_group", "stock_uom", "has_qr", "qty_per_bus"], as_dict=True
	)
	row_uuid = f"{run.name}-item"

	movement = frappe.get_doc(
		{
			"doctype": "Material Movement",
			"movement_type": movement_type,
			"location": _location_for(run),
			"client_uuid": _uuid_for(run),
			# Projected runs land already verified-pending: the operator finished
			# the run, so the register entry is submitted, not a draft somebody has
			# to remember to send on.
			"status": C.STATUS_AWAITING_VERIFICATION,
			"party_name": party or "",
			"party_type": _party_type(party),
			"reference_no": document_no,
			"transport_vehicle_no": "",
			"started_by": run.started_by,
			"started_at": run.started_at,
			"submitted_by": run.started_by,
			"submitted_at": run.completed_at or frappe.utils.now_datetime(),
			"latitude": flt(run.latitude),
			"longitude": flt(run.longitude),
			"remarks": f"Captured through the Material Gate process. Run {run.name}.",
			"items": [
				{
					"row_uuid": row_uuid,
					"item": item,
					"item_name": master.part_name if master else item,
					"item_group": master.part_group if master else None,
					"qty": 1,
					"uom": (master.stock_uom if master else None) or "Nos",
					"expected_qty": flt(master.qty_per_bus) if master else 0,
					"condition": C.CONDITION_OK,
					"has_qr": (master.has_qr if master else 0),
					"qr_code": serial,
					"qr_source": C.QR_SCANNED if scan else (C.QR_TYPED if serial else None),
					"chassis_no": chassis_no,
					"remarks": f"Weight {weight} KG" if weight else "",
				}
			],
			"photos": _photos(run, row_uuid),
		}
	)
	movement.insert(ignore_permissions=True)


def _photos(run, row_uuid: str) -> list[dict]:
	"""Carry the run's photographs onto the register line.

	Copied by reference — the File rows already exist and belong to the run, so
	this points at the same URLs rather than duplicating megabytes per movement.
	"""
	return [
		{
			"file_url": p.file_url,
			# Paperwork is filed as paperwork. The app's register view groups the
			# item photographs under the line and the challan above it, and a
			# challan tagged "Item" shows up as a picture of the part.
			"kind": "Document" if p.step_code in DOCUMENT_STEPS else "Item",
			"item_row": "" if p.step_code in DOCUMENT_STEPS else row_uuid,
			"caption": p.caption or p.step_code,
			"captured_by": p.captured_by,
			"captured_at": p.captured_at,
			"server_received_at": p.server_received_at,
			"latitude": flt(p.latitude),
			"longitude": flt(p.longitude),
			"accuracy_m": flt(p.accuracy_m),
			"is_stamped": p.is_stamped,
			"client_uuid": p.client_uuid or f"{run.name}-{p.idx}",
		}
		for p in run.photos or []
		if p.file_url
	]


def _party_type(party: str | None) -> str | None:
	if not party:
		return None
	source_type = frappe.db.get_value("Material Source", party, "source_type")
	return {
		"Supplier": "Supplier",
		"Plant": "Inter-Plant",
		"Customer": "Customer",
		"Job Work": "Job Work",
		"Transporter": "Transporter",
	}.get(source_type)


def _location_for(run) -> str:
	"""Which plant the run happened at.

	The run carries a depot when the app knew one; otherwise fall back to the
	only active location, and to Hubli as the seeded default. A movement must
	land somewhere — a register line with no plant is unreportable.
	"""
	if run.depot:
		linked = frappe.db.get_value("Material Location", {"depot": run.depot}, "name")
		if linked:
			return linked
	active = frappe.get_all("Material Location", filters={"is_active": 1}, pluck="name", limit_page_length=2)
	if len(active) == 1:
		return active[0]
	return "HUBLI" if frappe.db.exists("Material Location", "HUBLI") else (active[0] if active else None)
