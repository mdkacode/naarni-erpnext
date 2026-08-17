"""Offline sync for the process engine — one inspection, pushed in one call.

An engineer inspecting a pack in a shed does not have a network, and waiting for
one is not an option: the pack is in front of them and there is a queue behind
them. So the app records everything on the handset and this module is where that
work lands when a network comes back.

Three properties matter more than anything else here, in this order.

**Replay must be free.** Every field-facing failure mode ends the same way — the
request was applied and the response was lost — so the device will send the same
batch again. A batch is therefore a *description of the run's state*, not a list
of operations: answers upsert by step code, scans and photos dedup on a device
UUID, a submit already signed off is a no-op. Sending the same batch a hundred
times leaves exactly what sending it once does, and that is asserted by the
tests, not assumed.

**Nothing is judged twice.** Actions — deviations, quarantines, the message that
wakes a supervisor — fire on the request that first records an answer and never
again, because `apply_answer` reports whether the row actually changed. Without
that, one dropped response would raise a duplicate deviation for work the
operator did once.

**Partial success is success.** A batch of forty answers with one bad step code
saves thirty-nine and reports the one, rather than rejecting the lot. The
alternative is a handset that can never drain its queue because of a single row
it will resend for ever.

The device may also start a run that the server has never seen. `start_run` was
already idempotent on the client UUID, so run creation reuses it verbatim: the
UUID the app minted the moment the operator pressed Start is the run's identity,
and it stays the same whether the network was there or not.
"""

from __future__ import annotations

import json

import frappe
from frappe import _
from frappe.utils import cint, flt

from vehicle_maintenance.api import process as api_process
from vehicle_maintenance.api.process import (
	_assert_can_run,
	_live_definition,
	_ok,
	_serialise_run,
	get_definition,
	list_processes,
	outstanding_work,
	with_deadlock_retry,
)
from vehicle_maintenance.process_engine import constants as C
from vehicle_maintenance.process_engine import scanning

#: How many answers one batch may carry.
#:
#: A generous ceiling on the largest real process (Battery QC is ~90 steps), not
#: a performance tuning knob. It exists so a corrupted queue cannot ask the
#: server to hold an unbounded document in memory.
MAX_ANSWERS = 500
MAX_SCANS = 500

#: How many times a contended sync is replayed.
#:
#: Twice the interactive default. The trade is different here: `save_step_result`
#: has an operator waiting with a finger on the screen, so it must fail fast and
#: let them retry; a sync is a background job on a handset that nobody is
#: watching, and the only cost of trying again is a second of a worker's time.
#:
#: The stress harness is the reason for the number. Thirty batches arriving for
#: one run together — a van of engineers reaching signal at the same moment —
#: left five failures at six attempts and none at twelve.
SYNC_ATTEMPTS = 12


def _loads(payload) -> dict:
	"""Accept the batch as a JSON string or as an already-parsed dict.

	Frappe hands whitelisted methods a string when the client posts JSON as a
	form field, and a dict when it posts a JSON body. Both are normal.
	"""
	if isinstance(payload, dict):
		return payload
	try:
		parsed = json.loads(payload or "{}")
	except (TypeError, ValueError):
		frappe.throw(_("The sync payload is not valid JSON."))
	if not isinstance(parsed, dict):
		frappe.throw(_("The sync payload must be an object."))
	return parsed


def _resolve_run(batch: dict):
	"""Find the run this batch belongs to, creating it if the device started it offline.

	Returns ``(doc, created)``.
	"""
	client_uuid = (batch.get("client_uuid") or "").strip()
	server_name = (batch.get("run") or "").strip()

	if client_uuid:
		existing = frappe.db.get_value("Process Run", {"client_uuid": client_uuid}, "name")
		if existing:
			return frappe.get_doc("Process Run", existing), False

	if server_name:
		# A run created online before the network went away. It has no client
		# UUID to match on, so the device sends the name it was given.
		return frappe.get_doc("Process Run", server_name), False

	if not client_uuid:
		frappe.throw(_("A client_uuid or a run name is required."))

	process = (batch.get("process") or "").strip()
	if not process:
		frappe.throw(_("A process is required to create a run."))

	# Reuse the online path wholesale rather than re-implementing the insert: it
	# already owns the unique-key race, the identifier pattern check and the
	# test-run guard, and every one of those still applies to a run that began
	# its life on a handset.
	created = api_process.start_run(
		process=process,
		identifier=batch.get("identifier") or None,
		subject_name=batch.get("subject_name") or None,
		client_uuid=client_uuid,
		station=batch.get("station") or None,
		shift=batch.get("shift") or None,
		depot=batch.get("depot") or None,
		latitude=batch.get("latitude"),
		longitude=batch.get("longitude"),
	)
	doc = frappe.get_doc("Process Run", created["data"]["name"])

	# The handset's clock is the only record of when the work actually began. A
	# run created at sync time would otherwise claim a start hours after the
	# operator started it, and duration is a number this shop floor reads.
	started = batch.get("started_at")
	if started and not created["data"].get("results"):
		frappe.db.set_value("Process Run", doc.name, "started_at", started, update_modified=False)
		doc.started_at = started

	return doc, True


def _apply_answers(doc, definition, answers: list[dict]) -> tuple[list[dict], list[dict]]:
	"""Apply queued answers to `doc` in memory. Returns ``(applied, rejected)``.

	Ordered by the device's own sequence number so a step answered twice offline
	lands in the order the operator answered it, not the order JSON happened to
	serialise.
	"""
	applied: list[dict] = []
	rejected: list[dict] = []

	ordered = sorted(answers, key=lambda a: cint(a.get("client_seq")))
	for answer in ordered:
		step_code = (answer.get("step_code") or "").strip()
		if not step_code:
			rejected.append({"step_code": "", "reason": _("Missing step code.")})
			continue
		try:
			result = api_process.apply_answer(
				doc,
				definition,
				step_code,
				response=answer.get("response"),
				value=answer.get("value"),
				remark=answer.get("remark"),
				skipped=cint(answer.get("skipped")),
				skip_reason=answer.get("skip_reason"),
				seconds_spent=cint(answer.get("seconds_spent")),
				answered_at=answer.get("answered_at") or None,
			)
		except Exception as exc:
			# One unusable row must not cost the operator the other thirty-nine.
			# It is reported by step code so the device can stop resending it and
			# show the engineer which check did not make it.
			frappe.log_error(
				title="process sync: answer rejected",
				message=f"run={doc.name} step={step_code}: {exc}",
			)
			rejected.append({"step_code": step_code, "reason": str(exc)})
			continue

		applied.append(
			{
				"step_code": step_code,
				"changed": result["changed"],
				"is_pass": cint(result["row"]["is_pass"]),
				"is_deviation": cint(result["row"]["is_deviation"]),
				"is_critical": cint(result["row"]["is_critical"]),
			}
		)

	return applied, rejected


def _apply_scans(doc, scans: list[dict]) -> tuple[list[str], list[dict]]:
	"""Append queued scans, skipping any this run already holds.

	Dedup is on the device's own UUID for the row, so a replay is exact rather
	than heuristic: two genuine scans of the same serial at the same position
	stay two rows, and one scan sent twice stays one.
	"""
	seen = {(s.client_uuid or "") for s in doc.scans or [] if s.client_uuid}
	applied: list[str] = []
	warnings: list[dict] = []

	for scan in scans:
		uuid = (scan.get("client_uuid") or "").strip()
		if uuid and uuid in seen:
			continue

		entity_type = (scan.get("entity_type") or "").strip()
		if not entity_type:
			continue

		parsed = scanning.parse_payload(entity_type, scan.get("payload") or "")
		policy = frappe.db.get_value("Process Entity Type", entity_type, "duplicate_policy") or "Warn"
		duplicate = scanning.find_duplicate(entity_type, parsed["serial_no"], exclude_run=doc.name)

		# Never thrown here, even under a Block policy. The scan happened hours
		# ago in a shed; refusing it now would strand the whole batch and lose
		# the genealogy row entirely. It is recorded, flagged, and the response
		# tells the device to warn the engineer.
		if duplicate and policy == "Block":
			warnings.append(
				{
					"client_uuid": uuid,
					"serial_no": parsed["serial_no"],
					"duplicate_of": duplicate,
					"message": _("{0} was already recorded on run {1}.").format(
						parsed["serial_no"], duplicate
					),
				}
			)

		doc.append(
			"scans",
			{
				"client_uuid": uuid or None,
				"entity_type": entity_type,
				"step_code": scan.get("step_code"),
				"position_index": cint(scan.get("position_index")),
				"raw_payload": parsed["raw_payload"],
				"serial_no": parsed["serial_no"],
				"mfg_date": parsed["mfg_date"],
				"module_number": parsed["module_number"],
				"batch_ref": parsed["batch_ref"],
				"revision": parsed["revision"],
				"parse_failed": parsed["parse_failed"],
				"is_manual_entry": cint(scan.get("is_manual_entry")),
				"duplicate_of": duplicate if policy != "Ignore" else None,
				"scanned_by": frappe.session.user,
				"scanned_at": scan.get("scanned_at") or frappe.utils.now_datetime(),
				"latitude": flt(scan.get("latitude")) if scan.get("latitude") not in (None, "") else None,
				"longitude": flt(scan.get("longitude")) if scan.get("longitude") not in (None, "") else None,
			},
		)
		if uuid:
			seen.add(uuid)
		applied.append(uuid or parsed["serial_no"] or "")

	return applied, warnings


def _apply_submits(doc, definition, stages: list[str]) -> tuple[list[str], list[dict]]:
	"""Sign off the stages the operator finished offline.

	Deliberately *not* a call to `submit_stage`: that saves and commits per
	stage, and a batch that submits three stages would leave two of them
	committed if the third threw. Here every stage lands in the same in-memory
	document and the caller commits once.
	"""
	applied: list[str] = []
	rejected: list[dict] = []

	ordered_stages = sorted(definition.stages or [], key=lambda x: cint(x.sequence))
	order = {s.stage_code: cint(s.sequence) for s in ordered_stages}
	for stage_code in sorted({s for s in stages if s}, key=lambda s: order.get(s, 0)):
		stage_def = next((s for s in ordered_stages if s.stage_code == stage_code), None)
		if not stage_def:
			rejected.append({"stage": stage_code, "reason": _("Not part of this process.")})
			continue
		if doc.signoff_for(stage_code, "Operator"):
			# Already signed off — a replay, not a second submit.
			continue
		if doc.stage_is_blocked(stage_code):
			rejected.append(
				{
					"stage": stage_code,
					"reason": _("Blocked pending review of an earlier failure."),
				}
			)
			continue

		missing = _missing_mandatory(doc, definition, stage_code)
		if missing:
			# The device judged this stage complete and the server disagrees —
			# usually because a conditional step became visible once an earlier
			# answer synced. Reported, not thrown: the rest of the batch is good
			# work and the engineer is told exactly which checks to go back to.
			rejected.append(
				{
					"stage": stage_code,
					"reason": _("These checks still need an answer: {0}").format(", ".join(missing)),
					"missing": missing,
				}
			)
			continue

		doc.append(
			"signoffs",
			{
				"stage": stage_code,
				"level": "Operator",
				"decision": "Submitted",
				"role": stage_def.signoff_role,
				"user": frappe.session.user,
				"user_full_name": frappe.utils.get_fullname(frappe.session.user),
				"signed_at": frappe.utils.now_datetime(),
			},
		)
		applied.append(stage_code)

	return applied, rejected


def _missing_mandatory(doc, definition, stage_code: str) -> list[str]:
	"""Mandatory, visible, unanswered steps in one stage — the submit interlock."""
	from vehicle_maintenance.process_engine import conditions

	answers = api_process._answer_map(doc)
	missing = []
	for step in definition.expanded_steps():
		if (
			step.get("stage") != stage_code
			or not cint(step.get("is_active", 1))
			or not cint(step.get("is_mandatory"))
		):
			continue
		if not conditions.is_visible(step, answers):
			continue
		if step["step_code"] not in answers:
			missing.append(step.get("display_no") or step["step_code"])
	return missing


def _advance_after_submits(doc, definition) -> None:
	"""Move the run pointer on, and close it if the batch finished the work.

	Mirrors the tail of `submit_stage`, applied once for the whole batch rather
	than once per stage — submitting four stages offline and syncing them
	together must land in the same place as submitting them one at a time on a
	good network.
	"""
	ordered = sorted(definition.stages or [], key=lambda x: cint(x.sequence))

	needs_verification = any(
		cint(s.requires_second_signoff)
		and doc.signoff_for(s.stage_code, "Operator")
		and not doc.signoff_for(s.stage_code, "Verifier L1")
		for s in ordered
	)

	open_stages = [
		s
		for s in ordered
		if not doc.stage_is_blocked(s.stage_code) and not doc.signoff_for(s.stage_code, "Operator")
	]

	if open_stages:
		doc.status = C.STATUS_IN_PROGRESS
		doc.current_stage = open_stages[0].stage_code
		return

	if needs_verification:
		doc.status = C.STATUS_AWAITING_VERIFICATION
		return

	outstanding = outstanding_work(doc, definition)
	if outstanding["stages"] or outstanding["steps"]:
		doc.status = C.STATUS_IN_PROGRESS
		return

	api_process._finalise(doc, definition)


@frappe.whitelist()
def sync_run(payload) -> dict:
	"""Push one handset-held inspection to the server. Safe to send twice.

	Args:
	    payload: JSON object (string or dict) shaped as::

	        {
	          "client_uuid": "…",        # the run's identity, minted at Start
	          "run": "PR-0001",          # instead, for a run created online
	          "process": "BATTERY-QC",   # required when creating
	          "identifier": "PACK-9931",
	          "started_at": "2026-08-17 09:04:11",
	          "answers": [{"step_code", "response", "value", "remark",
	                       "skipped", "skip_reason", "seconds_spent",
	                       "answered_at", "client_seq"}, …],
	          "scans":   [{"client_uuid", "entity_type", "payload",
	                       "step_code", "position_index", "is_manual_entry",
	                       "latitude", "longitude", "scanned_at"}, …],
	          "submit_stages": ["MOD1", "MOD2"]
	        }

	Returns the full server-side run — the same shape `get_run` returns — plus a
	``sync`` block describing what the server did with each part of the batch::

	    {
	        "created",
	        "answers_applied",
	        "answers_rejected",
	        "scans_applied",
	        "scan_warnings",
	        "stages_submitted",
	        "stages_rejected",
	        "outstanding",
	    }

	The device treats the returned run as authoritative and replaces its local
	copy with it: the server's verdict on every answer is the record, and the
	on-device judgement was only ever there to keep the engineer moving.
	"""
	batch = _loads(payload)

	answers = batch.get("answers") or []
	scans = batch.get("scans") or []
	if len(answers) > MAX_ANSWERS or len(scans) > MAX_SCANS:
		frappe.throw(_("This batch is too large. Split it and send again."))

	def _apply():
		doc, created = _resolve_run(batch)
		frappe.has_permission("Process Run", doc=doc, ptype="write", throw=True)

		# Serialise writers on this run, rather than letting them collide.
		#
		# A batch rewrites the run's whole child tables, so two arriving together
		# fail Frappe's optimistic lock and one is thrown away. Retrying works,
		# but under real contention it is exponential backoff against a document
		# that is only busy for forty milliseconds — the stress harness measured
		# 30 concurrent replays landing 3, and 27 of them burning their retries.
		#
		# A row lock turns that collision into a short queue. Taken *after*
		# `_resolve_run`, because creating a run commits and a commit would drop
		# the lock; and the document is re-read underneath it, because anything
		# read before the lock describes the run as it was before whoever we just
		# waited for finished with it.
		frappe.db.get_value("Process Run", doc.name, "name", for_update=True)
		doc = frappe.get_doc("Process Run", doc.name)

		definition = frappe.get_cached_doc("Process Definition", doc.process_definition)
		_assert_can_run(definition)

		# A closed run still accepts nothing, but the batch is not an error: the
		# device is replaying work the server already has. Answer with the run as
		# it stands so the handset can reconcile and clear its queue, rather than
		# throwing and leaving it retrying a finished inspection for ever.
		if doc.status in (C.STATUS_PASSED, C.STATUS_QUARANTINED, C.STATUS_CANCELLED):
			return _ok(
				{
					**_serialise_run(doc),
					"sync": {
						"created": created,
						"already_closed": True,
						"answers_applied": [],
						"answers_rejected": [],
						"scans_applied": [],
						"scan_warnings": [],
						"stages_submitted": [],
						"stages_rejected": [],
						"outstanding": outstanding_work(doc, definition),
					},
				},
				_("This inspection is already {0}.").format(doc.status.lower()),
			)

		applied_answers, rejected_answers = _apply_answers(doc, definition, answers)
		applied_scans, scan_warnings = _apply_scans(doc, scans)
		submitted, rejected_stages = _apply_submits(doc, definition, batch.get("submit_stages") or [])
		if submitted:
			_advance_after_submits(doc, definition)

		doc.save(ignore_permissions=True)
		# Committed before answering, like every other write in this API: a
		# response lost on the way back must never cost the engineer the work,
		# because the device's whole retry strategy assumes the opposite failure
		# — that the server has it and the phone does not know yet.
		frappe.db.commit()  # nosemgrep

		return _ok(
			{
				**_serialise_run(doc),
				"sync": {
					"created": created,
					"already_closed": False,
					"answers_applied": applied_answers,
					"answers_rejected": rejected_answers,
					"scans_applied": applied_scans,
					"scan_warnings": scan_warnings,
					"stages_submitted": submitted,
					"stages_rejected": rejected_stages,
					"outstanding": outstanding_work(doc, definition),
				},
			},
			_("Synced.") if not rejected_answers and not rejected_stages else None,
		)

	return with_deadlock_retry(_apply, attempts=SYNC_ATTEMPTS)


@frappe.whitelist()
def attach_photo_synced(
	run: str,
	step_code: str,
	file_url: str,
	client_uuid: str,
	captured_at: str | None = None,
	latitude: float | None = None,
	longitude: float | None = None,
	accuracy_m: float | None = None,
	location_source: str = "Unavailable",
	caption: str | None = None,
) -> dict:
	"""Attach a photo taken offline, keyed so a replay cannot duplicate it.

	Separate from the batch on purpose: a photo is two megabytes and one bad
	link, and bundling it with the answers would mean a failed image costs the
	whole inspection a retry. Each one uploads and attaches on its own, and the
	batch above stays small enough to succeed on a single bar of signal.
	"""
	client_uuid = (client_uuid or "").strip()
	if not client_uuid:
		frappe.throw(_("A client_uuid is required."))

	def _apply():
		doc = frappe.get_doc("Process Run", run)
		frappe.has_permission("Process Run", doc=doc, ptype="write", throw=True)

		if any((p.client_uuid or "") == client_uuid for p in doc.photos or []):
			# Already attached on an earlier attempt whose response was lost.
			return _ok(
				{
					"photo_count": len([p for p in doc.photos if p.step_code == step_code]),
					"duplicate": True,
				},
				_("Photo already recorded."),
			)

		if not file_url:
			frappe.throw(_("A file URL is required."))

		doc.append(
			"photos",
			{
				"client_uuid": client_uuid,
				"step_code": step_code,
				"file_url": file_url,
				"caption": caption,
				"captured_by": frappe.session.user,
				"captured_at": captured_at or frappe.utils.now_datetime(),
				"server_received_at": frappe.utils.now_datetime(),
				"latitude": flt(latitude) if latitude not in (None, "") else None,
				"longitude": flt(longitude) if longitude not in (None, "") else None,
				"accuracy_m": flt(accuracy_m) if accuracy_m not in (None, "") else None,
				"location_source": location_source or "Unavailable",
				"geofence_status": api_process._geofence_status(latitude, longitude),
				"is_stamped": 1,
			},
		)
		doc.save(ignore_permissions=True)
		frappe.db.commit()  # nosemgrep
		return _ok(
			{
				"photo_count": len([p for p in doc.photos if p.step_code == step_code]),
				"duplicate": False,
			},
			_("Photo saved."),
		)

	return with_deadlock_retry(_apply, attempts=SYNC_ATTEMPTS)


@frappe.whitelist()
def bootstrap(app_capability: int = 1) -> dict:
	"""Everything the app needs to run every process it is allowed to, offline.

	One call, made whenever the app has a network and the engineer does not need
	anything else. Without it the offline story has a hole you could drive a van
	through: an engineer who opens the app for the first time inside a shed has
	no definitions cached and cannot start the inspection they are standing in
	front of.

	Returns the process list, each full definition, and the handful of server
	settings the on-device evaluator needs to agree with the server's judgement.
	"""
	summaries = list_processes()["data"]

	definitions = []
	for summary in summaries:
		try:
			definitions.append(get_definition(summary["name"], app_capability)["data"])
		except frappe.PermissionError:
			# `list_processes` already filtered by role; a definition that fails
			# the stricter per-run check is simply left out rather than failing
			# the whole prefetch.
			continue

	settings = frappe.get_cached_doc("Process Engine Settings")
	return _ok(
		{
			"processes": summaries,
			"definitions": definitions,
			"settings": {
				"fast_entry_threshold_pct": flt(settings.fast_entry_threshold_pct) or 25,
				"geofence_enabled": cint(settings.geofence_enabled),
				"geofence_radius_m": cint(settings.geofence_radius_m),
				"plant_latitude": flt(settings.plant_latitude) or None,
				"plant_longitude": flt(settings.plant_longitude) or None,
			},
			"server_time": str(frappe.utils.now_datetime()),
		}
	)
