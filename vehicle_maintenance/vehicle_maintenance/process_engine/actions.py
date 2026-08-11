"""The action dispatcher — what "configure the automation" actually means.

Every `Process Step Action` row is executed here, server-side, on the same
request that saves the answer. Doing it server-side is the point: the client
cannot skip a notification or dodge a quarantine by not calling an endpoint.

Failures are contained. One broken notification must not lose an operator's
answer, so every handler is wrapped — a failure is logged and reported back as a
warning, and the run still saves. The only exception is `Quarantine Run`, which
mutates the run in memory and therefore cannot half-apply.
"""

from __future__ import annotations

import json

import frappe
from frappe import _
from frappe.utils import cint

from vehicle_maintenance.process_engine import constants as C


def _params(action) -> dict:
	"""Parse an action's optional JSON parameter blob, tolerating junk."""
	raw = (action.get("params") or "").strip()
	if not raw:
		return {}
	try:
		parsed = json.loads(raw)
		return parsed if isinstance(parsed, dict) else {}
	except (ValueError, TypeError):
		return {}


def _render(template: str, context: dict) -> str:
	"""Render an action's Jinja message, falling back to the raw template."""
	if not template:
		return ""
	try:
		return frappe.render_template(template, context)
	except Exception:
		return template


def matching_actions(actions: list[dict], result: dict, trigger_set: set[str]) -> list[dict]:
	"""Select the action rows whose trigger fired for this outcome.

	`trigger_set` is computed by the caller from the judged result, so the rules
	about what counts as a fail or a critical live in one place (`_triggers_for`)
	rather than being re-derived by every handler.
	"""
	fired = []
	for action in actions or []:
		if not cint(action.get("is_active", 1)):
			continue
		trigger = action.get("trigger")
		if trigger == C.TRIGGER_ON_OPTION:
			want = (action.get("trigger_value") or "").strip().lower()
			got = str(result.get("response") or "").strip().lower()
			if want and want == got:
				fired.append(action)
			continue
		if trigger in trigger_set:
			fired.append(action)
	return fired


def triggers_for(result: dict) -> set[str]:
	"""Which triggers this judged result fires."""
	triggers = {C.TRIGGER_ON_ANSWER}
	if cint(result.get("is_skipped")):
		return {C.TRIGGER_ON_SKIP}
	if result.get("is_pass"):
		triggers.add(C.TRIGGER_ON_PASS)
	elif result.get("is_answered"):
		triggers.add(C.TRIGGER_ON_FAIL)
	if result.get("is_deviation"):
		triggers.add(C.TRIGGER_ON_OUT_OF_RANGE)
	if cint(result.get("is_critical")) and not result.get("is_pass"):
		triggers.add(C.TRIGGER_ON_CRITICAL)
	return triggers


# --------------------------------------------------------------------- handlers


def _notify(run, action, context, users: list[str]) -> None:
	"""In-app realtime notification plus the app's existing push path."""
	message = _render(action.get("message_template"), context) or _("{0}: attention needed on {1}").format(
		context.get("process_name") or "", run.run_identifier or run.name
	)

	for user in users:
		frappe.publish_realtime(
			event="vm_notification",
			message={
				"title": context.get("process_name") or _("Process Alert"),
				"body": message,
				"severity": action.get("severity") or "Major",
				"doctype": "Process Run",
				"name": run.name,
			},
			user=user,
		)

	try:
		from vehicle_maintenance.fleet_service import notifications as vm_notifications

		sender = getattr(vm_notifications, "send_push_to_users", None)
		if callable(sender):
			sender(users, context.get("process_name") or _("Process Alert"), message)
	except Exception:
		# Push is best-effort; the realtime notification already landed.
		frappe.log_error(title="Process Engine: push notification failed", message=frappe.get_traceback())


def _users_for_role(role: str) -> list[str]:
	if not role:
		return []
	rows = frappe.get_all(
		"Has Role",
		filters={"role": role, "parenttype": "User"},
		fields=["parent"],
		limit_page_length=200,
	)
	users = [r.parent for r in rows if r.parent not in ("Administrator", "Guest")]
	if not users:
		return []
	enabled = frappe.get_all(
		"User",
		filters={"name": ["in", users], "enabled": 1},
		fields=["name"],
		limit_page_length=200,
	)
	return [u.name for u in enabled]


def _raise_deviation(run, action, step, result, context) -> str:
	doc = frappe.get_doc(
		{
			"doctype": "Process Deviation",
			"process_run": run.name,
			"step_code": result.get("step_code"),
			"step_label": result.get("label"),
			"severity": action.get("severity") or "Major",
			"status": "Open",
			"subject_doctype": run.subject_doctype,
			"subject_name": run.subject_name,
			"raised_by": frappe.session.user,
			"raised_at": frappe.utils.now_datetime(),
			"description": _render(action.get("message_template"), context)
			or (result.get("label") or _("Step failed")),
			"observed_value": str(result.get("response") or result.get("value_numeric") or ""),
			"expected_value": result.get("spec_summary") or "",
		}
	)
	doc.insert(ignore_permissions=True)
	return doc.name


def dispatch(run, step: dict, result: dict, actions: list[dict]) -> dict:
	"""Run every action whose trigger fired for this result.

	Mutates `run` in memory for the actions that change run state (quarantine,
	block, status) — the caller is responsible for saving. Returns
	``{applied: [...], warnings: [...], client_hints: [...]}``; `client_hints`
	is what the app uses to prompt for a photo or remark before the operator
	moves on, rather than discovering it at submit time.
	"""
	fired = matching_actions(actions, result, triggers_for(result))
	applied: list[str] = []
	warnings: list[str] = []
	hints: list[dict] = []

	context = {
		"run": run,
		"step": step,
		"result": result,
		"process_name": run.process_name,
		"subject": run.subject_name,
		"identifier": run.run_identifier,
	}

	for action in fired:
		atype = action.get("action_type")
		target = (action.get("target") or "").strip()

		if atype in C.CLIENT_HINT_ACTIONS:
			hints.append({"action": atype, "target": target, "message": action.get("message_template")})
			applied.append(atype)
			continue

		try:
			if atype == C.ACT_QUARANTINE:
				run.status = C.STATUS_QUARANTINED
				reason = _render(action.get("message_template"), context) or _(
					"Critical failure at step {0}"
				).format(result.get("display_no") or result.get("step_code"))
				run.quarantine_reason = reason

			elif atype == C.ACT_BLOCK_STAGE:
				blocked = {s for s in (run.blocked_stages or "").split("\n") if s.strip()}
				blocked.add(target or step.get("stage") or "")
				run.blocked_stages = "\n".join(sorted(b for b in blocked if b))

			elif atype == C.ACT_SET_RUN_STATUS:
				if target in (
					C.STATUS_IN_PROGRESS,
					C.STATUS_AWAITING_VERIFICATION,
					C.STATUS_QUARANTINED,
					C.STATUS_IN_REWORK,
				):
					run.status = target
				else:
					warnings.append(_("Ignored invalid run status '{0}'").format(target))
					continue

			elif atype == C.ACT_NOTIFY_ROLE:
				_notify(run, action, context, _users_for_role(target))

			elif atype == C.ACT_NOTIFY_USER:
				_notify(run, action, context, [target] if target else [])

			elif atype == C.ACT_RAISE_DEVIATION:
				applied.append(f"{atype}:{_raise_deviation(run, action, step, result, context)}")
				continue

			elif atype == C.ACT_SET_FIELD_ON_SUBJECT:
				if not (run.subject_doctype and run.subject_name and target):
					warnings.append(_("Set Field On Subject needs a subject and a fieldname"))
					continue
				value = _params(action).get("value")
				if value is None:
					value = result.get("response")
				if frappe.get_meta(run.subject_doctype).get_field(target):
					frappe.db.set_value(run.subject_doctype, run.subject_name, target, value)
				else:
					warnings.append(_("{0} has no field '{1}'").format(run.subject_doctype, target))
					continue

			elif atype == C.ACT_CREATE_DOCUMENT:
				payload = _params(action)
				if not target:
					warnings.append(_("Create Document needs a target doctype"))
					continue
				payload["doctype"] = target
				frappe.get_doc(payload).insert(ignore_permissions=True)

			elif atype == C.ACT_SEND_EMAIL:
				recipients = [r.strip() for r in target.split(",") if r.strip()]
				if recipients:
					frappe.sendmail(
						recipients=recipients,
						subject=_("{0} — {1}").format(run.process_name, run.run_identifier or run.name),
						message=_render(action.get("message_template"), context),
						reference_doctype="Process Run",
						reference_name=run.name,
						now=False,
					)

			elif atype == C.ACT_SEND_SMS:
				numbers = [n.strip() for n in target.split(",") if n.strip()]
				if numbers:
					frappe.enqueue(
						"frappe.core.doctype.sms_settings.sms_settings.send_sms",
						receiver_list=numbers,
						msg=_render(action.get("message_template"), context),
						queue="short",
					)

			elif atype == C.ACT_ADD_TAG:
				if target:
					frappe.get_doc("Process Run", run.name).add_tag(target)

			elif atype == C.ACT_RUN_SERVER_SCRIPT:
				# The escape hatch. Frappe's own Server Script doctype is
				# sandboxed and permission-gated, so admins get real logic
				# without a deploy and without us eval()-ing anything.
				if not target:
					warnings.append(_("Run Server Script needs a script name"))
					continue
				script = frappe.get_doc("Server Script", target)
				script.execute_method()

			else:
				warnings.append(_("Unknown action type '{0}'").format(atype))
				continue

			applied.append(atype)

		except Exception:
			# An action must never cost the operator their answer.
			frappe.log_error(
				title=f"Process Engine action failed: {atype}",
				message=f"Run: {run.name}\nStep: {result.get('step_code')}\n{frappe.get_traceback()}",
			)
			warnings.append(_("Action '{0}' could not be completed and was logged.").format(atype))

	return {"applied": applied, "warnings": warnings, "client_hints": hints}


def dispatch_completion(run, actions: list[dict]) -> dict:
	"""Fire the process-level `completion_actions` when a run finishes."""
	pseudo_result = {
		"is_pass": run.result == "Pass",
		"is_answered": True,
		"is_skipped": 0,
		"is_critical": 1 if cint(run.critical_count) else 0,
		"is_deviation": 1 if cint(run.fail_count) else 0,
		"response": run.result,
		"step_code": "",
		"label": run.process_name,
	}
	rows = [
		a
		for a in (actions or [])
		if a.get("trigger") in (C.TRIGGER_ON_PROCESS_COMPLETE, C.TRIGGER_ON_PASS, C.TRIGGER_ON_FAIL)
	]
	# Completion actions are matched on the run-level outcome, not a step's.
	fired = matching_actions(
		rows, pseudo_result, triggers_for(pseudo_result) | {C.TRIGGER_ON_PROCESS_COMPLETE}
	)
	return dispatch(run, {"stage": run.current_stage}, pseudo_result, fired)
