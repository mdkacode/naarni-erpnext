# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Daily status: the day a person reports by talking, organised into a record.

The capture surface is deliberately not a form. Every reporting user gets one
chat room of kind ``Status`` — their own thread, nobody else in it — and they
talk into it through the day: typing, a voice note, a photo of the bay, a shared
ticket. That single decision is what makes this work on a phone at the end of a
shift, and it means the per-person timeline, the unread badges, the offline
cache and the search already exist.

This module turns that thread into a **VM Daily Status** row, one per person per
day, which is what stakeholders read and what the evening digest is built from.

Three properties are load-bearing:

* **Ordering is `seq`, never a timestamp** — the same rule the rest of chat runs
  on. A day covers ``(from_seq, to_seq]`` where ``from_seq`` is where yesterday's
  row stopped. A message sent after last night's digest is therefore carried into
  today's row rather than lost, and nothing is ever counted twice.
* **Idempotent on (user, date)**, enforced by a unique ``dedup_key`` in the
  database. A duplicated cron firing cannot produce two rows.
* **AI is an improvement, never a dependency.** The day is organised into
  finished / running / stuck by Azure OpenAI, falling back to ONYX, falling back
  to a verbatim rendering. When every one of those is off or unreachable the day
  is still recorded, still counted and still emailed. See :func:`_summarise`.

Azure is preferred over ONYX for summarising even though both reach the same
`gpt-4.1-mini` deployment, because Azure can be *constrained* to
:func:`status_schema` while ONYX's chat endpoint cannot — so with Azure "the
summary did not parse" stops being a thing that happens. ONYX remains the only
route for search, which is a different job.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import frappe
from frappe import _
from frappe.utils import cint, get_datetime, get_url, now_datetime

from vehicle_maintenance.fleet_service import chat_feed
from vehicle_maintenance.integrations import azure_ai, onyx_client

IST = timezone(timedelta(hours=5, minutes=30))

# Accounts that exist but are nobody — same list the chat directory holds back.
NON_HUMAN_USERS = ("Guest", "Administrator", "airflow-km@naarni.com")

# Advisory only. Used by the fallback renderer to lift a line that smells like
# trouble to the top of the digest when the AI was not available to do it
# properly. A false positive costs a manager one glance; a missed blocker costs a
# bus a day, so this leans towards flagging.
_BLOCKER_HINTS = (
	"stuck",
	"blocked",
	"blocker",
	"pending",
	"waiting",
	"not working",
	"no part",
	"part needed",
	"spare",
	"breakdown",
	"issue",
	"problem",
	"delay",
	"nahi",
	"nahin",
	"ruka",
	"band",
)


# ────────────────────────────── time and config ─────────────────────────────


def _ist_now() -> datetime:
	return datetime.now(timezone.utc).astimezone(IST)


def settings():
	"""The Daily Status Settings Single. Cached; safe on a site that never saved it."""
	return frappe.get_cached_doc("Daily Status Settings")


def is_enabled() -> bool:
	try:
		return bool(settings().enabled)
	except Exception:
		return False


def status_date_now(at: datetime | None = None) -> str:
	"""The status day an IST instant belongs to, as ``YYYY-MM-DD``.

	A night shift that runs past midnight belongs to the day it started, so the
	day boundary is ``day_start_hour`` (04:00 IST by default), not midnight.
	"""
	moment = at or _ist_now()
	try:
		start_hour = cint(settings().day_start_hour)
	except Exception:
		start_hour = 4
	if moment.hour < start_hour:
		moment = moment - timedelta(days=1)
	return moment.date().isoformat()


# ──────────────────────────────── who reports ───────────────────────────────


def reporting_roles() -> list[str]:
	try:
		return [r.role for r in (settings().reporting_roles or []) if r.role]
	except Exception:
		return []


def reporting_users() -> list[str]:
	"""Every enabled human holding a reporting role. Single query, no N+1."""
	roles = reporting_roles()
	if not roles:
		return []
	rows = frappe.get_all(
		"Has Role",
		filters={"role": ["in", roles], "parenttype": "User"},
		fields=["parent"],
		limit_page_length=0,
	)
	candidates = {r["parent"] for r in rows} - set(NON_HUMAN_USERS)
	if not candidates:
		return []
	enabled = frappe.get_all(
		"User",
		filters={"name": ["in", list(candidates)], "enabled": 1},
		fields=["name"],
		limit_page_length=0,
	)
	return sorted(u["name"] for u in enabled)


def was_on_duty(user: str, status_date: str) -> bool:
	"""Whether `user` was on duty that day, per the roster.

	Returns True when the roster cannot answer — a site that has not adopted duty
	punches must not silently stop asking everyone for a status.
	"""
	if not frappe.db.table_exists("Duty Attendance"):
		return True
	row = frappe.db.get_value(
		"Duty Attendance",
		{"user": user, "attendance_date": status_date},
		["status"],
		as_dict=True,
	)
	if not row:
		return False
	return row["status"] in ("On Duty", "Present", "Half Day")


def users_for(status_date: str) -> list[str]:
	"""The people expected to report on `status_date`."""
	users = reporting_users()
	try:
		on_duty_only = bool(settings().on_duty_only)
	except Exception:
		on_duty_only = False
	if not on_duty_only:
		return users
	return [u for u in users if was_on_duty(u, status_date)]


# ─────────────────────────────── the status room ────────────────────────────


def find_room(user: str) -> str | None:
	"""The user's status room, identified by membership rather than a marker field.

	A status room has exactly one member by construction, so "the Status-kind room
	this person belongs to" is unambiguous without adding a column to VM Chat Room
	that only one kind would ever use.
	"""
	mine = _rooms_of(user)
	if not mine:
		return None
	return frappe.db.get_value("VM Chat Room", {"kind": "Status", "name": ["in", mine]}, "name")


def _rooms_of(user: str) -> list[str]:
	rows = frappe.get_all(
		"VM Chat Member",
		filters={"user": user, "parenttype": "VM Chat Room"},
		fields=["parent"],
		limit_page_length=0,
	)
	return [r["parent"] for r in rows]


def ensure_room(user: str) -> str:
	"""The user's own status room, created on first use.

	Membership is the user alone. Supervisors are not enrolled: adding every depot
	manager to every technician's room would bury both their chat lists, and the
	supervisor bypass in `VM Chat Room.has_permission` already lets them open any
	one of these threads — deliberately, one at a time, from the status board.
	"""
	existing = find_room(user)
	if existing:
		return existing

	full_name = frappe.db.get_value("User", user, "full_name") or user
	from vehicle_maintenance.fleet_service import roster

	try:
		depot = roster.primary_depot(user)
	except Exception:
		depot = None

	doc = frappe.get_doc(
		{
			"doctype": "VM Chat Room",
			"title": _("Daily Status — {0}").format(full_name),
			"kind": "Status",
			"depot": depot,
			"members": [{"user": user, "member_role": "Admin"}],
		}
	)
	doc.insert(ignore_permissions=True)

	# The reminder cron and the app's first call can land at the same moment and
	# both find nothing. Two "Daily Status" threads in someone's chat list is a
	# visible bug, so lose the race deliberately: the oldest room wins, and the
	# one just created — which has no messages yet — is dropped.
	rooms = frappe.get_all(
		"VM Chat Room",
		filters={"kind": "Status", "name": ["in", _rooms_of(user)]},
		fields=["name"],
		order_by="creation asc",
		limit_page_length=0,
	)
	if len(rooms) > 1 and rooms[0]["name"] != doc.name:
		frappe.delete_doc("VM Chat Room", doc.name, force=True, ignore_permissions=True)
		return rooms[0]["name"]
	return doc.name


# ──────────────────────────────── collection ────────────────────────────────


def _previous_to_seq(room: str, status_date: str) -> int:
	"""Where the last generated day for this room stopped.

	This is what makes the window gapless: today starts where yesterday ended,
	whenever yesterday ended, so a message posted at 23:50 after the digest went
	out is carried forward instead of dropped.
	"""
	rows = frappe.get_all(
		"VM Daily Status",
		filters={"room": room, "status_date": ["<", status_date]},
		fields=["to_seq"],
		order_by="status_date desc",
		limit_page_length=1,
	)
	return cint(rows[0]["to_seq"]) if rows else 0


def collect(room: str, user: str, from_seq: int, to_seq: int) -> list[dict]:
	"""The user's own messages in ``(from_seq, to_seq]``, oldest first.

	Only what the person themselves said: a manager's reply in the thread is a
	useful conversation but it is not that person's status.
	"""
	if to_seq <= from_seq:
		return []
	# List-of-lists, not a dict: the window needs two conditions on `seq`, and
	# `between` is not reliably handled for integers by the query builder.
	return frappe.get_all(
		"VM Chat Message",
		filters=[
			["room", "=", room],
			["author", "=", user],
			["deleted", "=", 0],
			["seq", ">", from_seq],
			["seq", "<=", to_seq],
		],
		fields=[
			"name",
			"seq",
			"kind",
			"body",
			"transcript",
			"duration_ms",
			"ticket",
			"vehicle",
			"creation",
		],
		order_by="seq asc",
		limit_page_length=0,
	)


def _line_for(row: dict) -> str:
	"""One transcript line: time, then what was actually said."""
	when = get_datetime(row["creation"])
	stamp = when.strftime("%H:%M")
	body = (row.get("body") or "").strip()
	kind = row.get("kind")

	if kind == "audio":
		text = (row.get("transcript") or "").strip()
		if text:
			return f"[{stamp}] (voice) {text}"
		secs = round(cint(row.get("duration_ms")) / 1000)
		return f"[{stamp}] (voice note, {secs}s — not transcribed)"
	if kind in ("image", "video"):
		label = "photo" if kind == "image" else "video"
		return f"[{stamp}] ({label}) {body}".rstrip()
	if kind == "ticket":
		return f"[{stamp}] shared ticket {row.get('ticket') or ''} {body}".rstrip()
	return f"[{stamp}] {body}"


def transcript_for(rows: list[dict]) -> str:
	return "\n".join(_line_for(r) for r in rows)


def has_reported(user: str, status_date: str | None = None) -> bool:
	"""Whether `user` has said anything in their status room since the last day closed.

	Cheaper than generating a row, and the one question both the reminder and the
	app's "you have not reported yet" state need answered.
	"""
	status_date = status_date or status_date_now()
	room = ensure_room(user)
	from_seq = _previous_to_seq(room, status_date)
	to_seq = cint(frappe.db.get_value("VM Chat Room", room, "last_seq"))
	return bool(collect(room, user, from_seq, to_seq))


# ──────────────────────────────── summarising ───────────────────────────────


_SYSTEM = """You summarise one field technician's own end-of-day messages from a \
vehicle maintenance depot. They wrote these on a phone, often in a mix of English \
and Hindi, often as voice notes transcribed roughly.

Rules:
- Write in clear English, even when the message was in Hindi or Hinglish.
- Keep each item under 20 words and factual. Do not add anything they did not say.
- "blockers" is work that cannot continue — a missing part, a bus not released, \
waiting on someone. "needs_attention" is anything a manager should know that is \
not blocking.
- Leave a list empty rather than padding it.
- ticket and vehicle must be one of the reference IDs given, or null. Never \
invent an ID."""

_PROMPT = """Person: {name}
Date: {date}

Their messages, in order:
{transcript}

Reference IDs mentioned that day (use ONLY these, never invent one):
  Tickets: {tickets}
  Vehicles: {vehicles}"""

# Appended for ONYX only. Azure is *constrained* to the schema below and needs no
# shape instructions; ONYX has no structured-output support at all, so the shape
# has to be asked for in prose and validated afterwards.
_SHAPE_HINT = """

Return ONLY a JSON object, no prose before or after, in exactly this shape:

{"summary": "one plain sentence, max 20 words, describing the day",
 "completed": [{"text": "...", "ticket": null, "vehicle": null}],
 "in_progress": [{"text": "...", "ticket": null, "vehicle": null}],
 "blockers": [{"text": "...", "ticket": null, "vehicle": null}],
 "needs_attention": [{"text": "...", "ticket": null, "vehicle": null}]}"""


def _item_schema() -> dict:
	return {
		"type": "array",
		"items": {
			"type": "object",
			"properties": {
				"text": {"type": "string", "description": "Under 20 words, factual."},
				"ticket": {"type": ["string", "null"]},
				"vehicle": {"type": ["string", "null"]},
			},
			# Azure's strict mode requires every property listed in `required` and
			# `additionalProperties: false`. Nullable fields are expressed as a type
			# union, not by omitting them.
			"required": ["text", "ticket", "vehicle"],
			"additionalProperties": False,
		},
	}


def status_schema() -> dict:
	"""The JSON Schema the model is *constrained* to when Azure is in use."""
	return {
		"type": "object",
		"properties": {
			"summary": {"type": "string", "description": "One plain sentence, max 20 words."},
			"completed": _item_schema(),
			"in_progress": _item_schema(),
			"blockers": _item_schema(),
			"needs_attention": _item_schema(),
		},
		"required": ["summary", "completed", "in_progress", "blockers", "needs_attention"],
		"additionalProperties": False,
	}


def _summarise(user: str, status_date: str, rows: list[dict], depot: str | None) -> dict:
	"""Organise the day. Returns the fields to write onto the status row.

	Tries Azure OpenAI first, then ONYX, then gives up and renders the day
	verbatim. Azure is preferred for one reason: it can be *constrained* to
	`status_schema()`, so "the summary did not parse" stops being a thing that
	happens. ONYX has to be asked in prose and checked afterwards.

	Never raises. An unreachable AI degrades the record from organised to
	verbatim, which is a worse report but still a report.
	"""
	transcript = transcript_for(rows)
	tickets = sorted({r["ticket"] for r in rows if r.get("ticket")})
	vehicles = sorted({r["vehicle"] for r in rows if r.get("vehicle")})

	try:
		use_ai = bool(settings().use_ai)
		cap = cint(settings().max_messages) or 60
	except Exception:
		use_ai, cap = False, 60

	if not use_ai:
		return _fallback(transcript, rows, note="AI organising is switched off.")

	# Cap only what is *sent*; the full transcript is always kept on the record.
	prompt = _PROMPT.format(
		name=frappe.db.get_value("User", user, "full_name") or user,
		date=status_date,
		transcript=transcript_for(rows[-cap:]),
		tickets=", ".join(tickets) or "none",
		vehicles=", ".join(vehicles) or "none",
	)
	label = f"daily-status/{depot or 'no-depot'}/{status_date}/{user}"

	parsed, source, note, raw = _ask(prompt, label)
	if parsed is None:
		return _fallback(transcript, rows, note=note, raw=raw)

	items = _items_from(parsed, set(tickets), set(vehicles))
	summary = str(parsed.get("summary") or "").strip()[:280]
	if not items and not summary:
		return _fallback(transcript, rows, note=f"{source} returned an empty summary.", raw=raw)

	return {
		"summary_line": summary or _first_line(transcript),
		"items": items,
		"raw_transcript": transcript,
		"generated_by": "AI",
		# Which engine produced this. Worth keeping on the row: when a day reads
		# oddly, the first question is always which model wrote it.
		"generation_note": f"Organised by {source}.",
		"ai_raw_response": json.dumps(parsed, indent=2)[:20000],
	}


def _ask(prompt: str, label: str) -> tuple[dict | None, str, str, str | None]:
	"""Put the prompt to Azure, then ONYX. Returns (parsed, source, note, raw).

	`parsed` is None when neither could answer usefully, and `note` then explains
	which failed and why — that text lands on the status row, so it is written to
	be read by whoever is wondering why a day looks plain.
	"""
	problems: list[str] = []

	if azure_ai.is_enabled():
		try:
			parsed = azure_ai.complete_json(
				prompt, system=_SYSTEM, schema=status_schema(), schema_name="daily_status"
			)
			return parsed, "Azure OpenAI", "", json.dumps(parsed)
		except (azure_ai.AzureConfigError, azure_ai.AzureApiError) as exc:
			frappe.logger("naarni").warning(f"daily status: Azure unavailable ({label}): {exc}")
			problems.append(f"Azure: {exc}")

	if onyx_client.is_enabled():
		try:
			# ONYX has no system role on this endpoint and no schema support, so the
			# instructions and the shape both ride in the user turn.
			answer = onyx_client.complete(f"{_SYSTEM}\n\n{prompt}{_SHAPE_HINT}", description=label)
		except (onyx_client.OnyxConfigError, onyx_client.OnyxApiError) as exc:
			frappe.logger("naarni").warning(f"daily status: ONYX unavailable ({label}): {exc}")
			problems.append(f"ONYX: {exc}")
		else:
			parsed = onyx_client.extract_json(answer)
			if parsed:
				return parsed, "ONYX", "", answer
			problems.append("ONYX replied with something that was not JSON.")

	if not problems:
		problems.append("No AI provider is enabled.")
	return None, "", " ".join(problems)[:400], None


_BUCKET_KEYS = (
	("completed", "Completed"),
	("in_progress", "In Progress"),
	("blockers", "Blocker"),
	("needs_attention", "Needs Attention"),
)


def _items_from(parsed: dict, tickets: set[str], vehicles: set[str]) -> list[dict]:
	"""Validate the model's lists into child rows, dropping anything unsupported.

	Ticket and vehicle IDs are checked against what the person actually referenced
	that day. This is the whole defence against a plausible-sounding hallucinated
	plate number ending up on a record a manager acts on.
	"""
	items: list[dict] = []
	for key, bucket in _BUCKET_KEYS:
		entries = parsed.get(key)
		if not isinstance(entries, list):
			continue
		for entry in entries:
			if isinstance(entry, str):
				entry = {"text": entry}
			if not isinstance(entry, dict):
				continue
			text = str(entry.get("text") or "").strip()
			if not text:
				continue
			ticket = entry.get("ticket")
			vehicle = entry.get("vehicle")
			items.append(
				{
					"bucket": bucket,
					"text": text[:500],
					"ticket": ticket if ticket in tickets else None,
					"vehicle": vehicle if vehicle in vehicles else None,
				}
			)
	return items[:40]


def _first_line(transcript: str) -> str:
	first = (transcript.split("\n", 1)[0] if transcript else "").strip()
	# Drop the [HH:MM] stamp for the one-liner.
	if first.startswith("["):
		first = first.split("]", 1)[-1].strip()
	return first[:200]


def _fallback(transcript: str, rows: list[dict], *, note: str, raw: str | None = None) -> dict:
	"""The day, rendered without AI. Honest rather than fabricated structure.

	No attempt is made to guess what was finished versus still running — that is
	exactly the judgement the AI was for. Lines that read like trouble are lifted
	into Needs Attention on a keyword match, flagged as advisory, because a
	manager scanning forty people needs *something* pulled to the top.
	"""
	flagged = []
	for row in rows:
		text = ((row.get("body") or "") + " " + (row.get("transcript") or "")).lower()
		if any(hint in text for hint in _BLOCKER_HINTS):
			flagged.append(
				{
					"bucket": "Needs Attention",
					"text": _line_for(row).split("] ", 1)[-1][:500],
					"ticket": row.get("ticket"),
					"vehicle": row.get("vehicle"),
				}
			)

	return {
		"summary_line": _first_line(transcript),
		"items": flagged[:10],
		"raw_transcript": transcript,
		"generated_by": "Fallback",
		"generation_note": note,
		"ai_raw_response": (raw or "")[:20000] or None,
	}


# ──────────────────────────────── generation ────────────────────────────────


def generate_for_user(user: str, status_date: str | None = None) -> str | None:
	"""Build or rebuild one person's day. Returns the VM Daily Status name.

	Idempotent: re-running keeps the original ``from_seq`` and only extends
	``to_seq``, so a regeneration after a late message adds it rather than
	restating the day from a different starting point.
	"""
	status_date = status_date or status_date_now()
	room = ensure_room(user)
	dedup_key = f"{user}::{status_date}"

	existing_name = frappe.db.get_value("VM Daily Status", {"dedup_key": dedup_key}, "name")
	if existing_name:
		doc = frappe.get_doc("VM Daily Status", existing_name)
		from_seq = cint(doc.from_seq)
	else:
		doc = frappe.new_doc("VM Daily Status")
		doc.user = user
		doc.status_date = status_date
		doc.room = room
		from_seq = _previous_to_seq(room, status_date)

	to_seq = cint(frappe.db.get_value("VM Chat Room", room, "last_seq"))
	rows = collect(room, user, from_seq, to_seq)

	from vehicle_maintenance.fleet_service import roster

	try:
		depot = roster.primary_depot(user)
	except Exception:
		depot = None

	doc.room = room
	doc.depot = depot
	doc.from_seq = from_seq
	doc.to_seq = to_seq
	doc.message_count = len(rows)
	doc.voice_count = sum(1 for r in rows if r["kind"] == "audio")
	doc.photo_count = sum(1 for r in rows if r["kind"] in ("image", "video"))
	doc.generated_at = now_datetime()
	doc.set("items", [])

	if not rows:
		doc.state = "Not Reported"
		doc.summary_line = None
		doc.raw_transcript = None
		doc.generated_by = "Fallback"
		doc.generation_note = None
		doc.ai_raw_response = None
	else:
		result = _summarise(user, status_date, rows, depot)
		doc.state = "Generated" if doc.state in (None, "Not Reported") else doc.state
		doc.summary_line = result["summary_line"]
		doc.raw_transcript = result["raw_transcript"]
		doc.generated_by = result["generated_by"]
		doc.generation_note = result["generation_note"]
		doc.ai_raw_response = result["ai_raw_response"]
		for item in result["items"]:
			doc.append("items", item)

	doc.save(ignore_permissions=True)
	return doc.name


def generate_all(status_date: str | None = None) -> dict:
	"""Scheduled entry point: build every expected person's day.

	Each person is a separate background job. One user's room being misconfigured,
	or ONYX timing out on their summary, must not cost the other thirty-nine their
	record.
	"""
	if not is_enabled():
		return {"skipped": "daily status disabled"}

	status_date = status_date or status_date_now()
	users = users_for(status_date)
	for user in users:
		frappe.enqueue(
			"vehicle_maintenance.fleet_service.daily_status.generate_for_user",
			queue="long",
			timeout=180,
			user=user,
			status_date=status_date,
		)
	frappe.logger("naarni").info(f"daily status: queued {len(users)} for {status_date}")
	return {"date": status_date, "queued": len(users)}


# ────────────────────────────────── nudging ─────────────────────────────────


def nudge_missing(status_date: str | None = None) -> dict:
	"""Post the three questions into the room of anyone who has not spoken today.

	Runs before the digest. Tied to the roster so that a day off is not a missed
	report — nagging people on leave is how a reminder gets muted.
	"""
	if not is_enabled():
		return {"skipped": "daily status disabled"}
	try:
		if not settings().nudge_enabled:
			return {"skipped": "nudge disabled"}
		text = (settings().nudge_text or "").strip()
	except Exception:
		return {"skipped": "settings unavailable"}
	if not text:
		return {"skipped": "no nudge text"}

	status_date = status_date or status_date_now()
	nudged = 0
	for user in users_for(status_date):
		if has_reported(user, status_date):
			continue  # they have already said something today
		try:
			chat_feed.post(room=ensure_room(user), kind="system", body=text)
			nudged += 1
		except Exception:
			frappe.log_error(
				title=f"Daily status nudge failed (user={user})",
				message=frappe.get_traceback(),
			)
	frappe.logger("naarni").info(f"daily status: nudged {nudged} for {status_date}")
	return {"date": status_date, "nudged": nudged}


# ───────────────────────────── ONYX search index ────────────────────────────


def index_in_onyx(name: str) -> dict:
	"""Push one finished day into ONYX so it can be asked about later.

	Optional and off by default. The document id is stable, so a regeneration
	updates the same document rather than adding a second copy of the day.
	"""
	try:
		cfg = settings()
		if not cfg.index_in_onyx:
			return {"skipped": "indexing disabled"}
	except Exception:
		return {"skipped": "settings unavailable"}
	if not onyx_client.is_enabled():
		return {"skipped": "onyx disabled"}

	doc = frappe.get_doc("VM Daily Status", name)
	if doc.state == "Not Reported":
		return {"skipped": "nothing reported"}

	lines = [f"Daily status for {doc.employee_name} ({doc.user}) on {doc.status_date}."]
	if doc.depot:
		lines.append(f"Depot: {doc.depot}.")
	if doc.summary_line:
		lines.append(f"Summary: {doc.summary_line}")
	for item in doc.items or []:
		ref = " ".join(x for x in (item.ticket, item.vehicle) if x)
		lines.append(f"- [{item.bucket}] {item.text}{(' (' + ref + ')') if ref else ''}")
	if doc.raw_transcript:
		lines.append("\nIn their words:\n" + doc.raw_transcript)

	site = get_url()
	try:
		result = onyx_client.ingest_document(
			document_id=f"vm-daily-status/{doc.user}/{doc.status_date}",
			semantic_identifier=f"Daily Status — {doc.employee_name} — {doc.status_date}",
			text="\n".join(lines),
			link=f"{site}/app/vm-daily-status/{doc.name}",
			metadata={
				"depot": doc.depot or "",
				"user": doc.user,
				"date": str(doc.status_date),
				"source": "vehicle-maintenance",
			},
			cc_pair_id=cint(cfg.onyx_cc_pair_id) or None,
		)
	except (onyx_client.OnyxConfigError, onyx_client.OnyxApiError) as exc:
		frappe.logger("naarni").warning(f"daily status: ONYX indexing failed for {name}: {exc}")
		return {"error": str(exc)}

	frappe.db.set_value("VM Daily Status", name, "indexed_at", now_datetime(), update_modified=False)
	return result
