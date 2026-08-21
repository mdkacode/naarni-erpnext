# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Low-level HTTP client for ONYX (https://ai.naarni.com) — our self-hosted AI.

ONYX is the Onyx (onyx-dot-app) fork we run on Azure alongside this site. This
module is the single place that speaks its HTTP contract; everything else calls
:func:`complete`, never ``requests`` directly.

The one endpoint we use is the non-streaming chat call::

    POST {base}/api/chat/send-chat-message
    Authorization: Bearer on_xxxxxxxx
    {"message": "...", "stream": false, "include_citations": false,
     "allowed_tool_ids": [], "chat_session_info": {"persona_id": 0, ...}}
    -> 200 {"answer": "...", "message_id": 42, "chat_session_id": "..."}

ONYX takes two kinds of bearer credential and this client works with either: an
**API key** (``on_…``), which resolves to a machine user, or a **personal access
token** (``onyx_pat_…``), which resolves to the person who minted it and is
additionally capped by that token's scopes. Both go in ``Authorization: Bearer``
— a PAT is rejected in the raw non-bearer form an API key would tolerate. Note
that a *scoped* PAT reaches only routes guarded by ONYX's `require_permission`,
which covers the chat endpoint but not `/onyx-api/ingestion`.

Three details of that request matter and are easy to get wrong:

* **``stream: false``** returns a plain JSON ``ChatFullResponse`` instead of an
  SSE stream, which is what a background job wants.
* **``allowed_tool_ids: []``** disables *every* tool, including document search.
  Without it the assistant runs RAG over the whole ONYX corpus and starts citing
  unrelated documents inside a shift summary.
* **``include_citations: false``** stops ``[1]``/``[2]`` markers being spliced
  into the answer text we then have to parse.

**ONYX cannot guarantee structured output.** ``structured_response_format`` is
honoured by the LLM layer but the chat path passes ``None`` (a documented TODO
upstream), so JSON has to be asked for in the prompt and validated here. Callers
must treat a malformed answer as normal, not exceptional — see
:func:`extract_json`.

Configuration lives on the **Onyx Settings** Single (base URL, API key, persona,
timeout). site_config keys of the same name win over it, so a site can be pinned
without touching the database.
"""

from __future__ import annotations

import json
import re
from typing import Any

import frappe
import requests
from frappe.utils import cint

# Our ONYX deployment. Onyx's own nginx serves the API under /api, so the base is
# just the host and `_url()` adds the prefix.
DEFAULT_BASE_URL = "https://ai.naarni.com"
DEFAULT_API_PREFIX = "/api"

# Persona 0 is Onyx's built-in default assistant. With tools disabled it is a
# plain LLM, which is exactly what summarisation wants.
DEFAULT_PERSONA_ID = 0
DEFAULT_TIMEOUT = 45

# Circuit breaker. ONYX being down must degrade this app to its deterministic
# fallback within one failure burst, not make every caller wait out a timeout.
_BREAKER_KEY = "onyx_consecutive_failures"
_BREAKER_TRIP_AT = 3
_BREAKER_COOLDOWN_SECS = 15 * 60


class OnyxConfigError(RuntimeError):
	"""ONYX is disabled or its required configuration is missing."""


class OnyxApiError(RuntimeError):
	"""ONYX returned a non-2xx response, timed out, or sent an unparseable body."""


# ──────────────────────────────── config ────────────────────────────────


def _settings() -> dict:
	"""Onyx Settings as a plain dict, overridden by site_config of the same name.

	Returns an empty-ish dict rather than throwing when the Single has never been
	saved, so `is_enabled()` is safe to call on a fresh site.
	"""
	conf = frappe.conf or {}
	try:
		doc = frappe.get_cached_doc("Onyx Settings")
	except Exception:
		doc = None

	def pick(conf_key: str, field: str, default=None):
		"""site_config wins, then the Single's field, then the built-in default."""
		if conf.get(conf_key) is not None:
			return conf.get(conf_key)
		value = getattr(doc, field, None) if doc else None
		return default if value in (None, "") else value

	return {
		# `cint` rather than `bool`: site_config values set with `bench set-config`
		# arrive as strings, and the string "0" is truthy — which would turn the
		# integration on for a site that explicitly turned it off.
		"enabled": bool(cint(pick("onyx_enabled", "enabled", 0))),
		"base_url": str(pick("onyx_base_url", "base_url", DEFAULT_BASE_URL)).rstrip("/"),
		"api_prefix": str(pick("onyx_api_prefix", "api_prefix", DEFAULT_API_PREFIX)),
		"persona_id": int(pick("onyx_persona_id", "persona_id", DEFAULT_PERSONA_ID) or 0),
		"timeout": int(pick("onyx_timeout_seconds", "timeout_seconds", DEFAULT_TIMEOUT) or DEFAULT_TIMEOUT),
		"api_key": _api_key(doc, conf),
	}


def _api_key(doc, conf: dict) -> str | None:
	if conf.get("onyx_api_key"):
		return str(conf["onyx_api_key"])
	if not doc:
		return None
	try:
		return doc.get_password("api_key", raise_exception=False)
	except Exception:
		return None


def is_enabled() -> bool:
	"""Master switch. Off until an admin ticks Enabled *and* stores an API key."""
	cfg = _settings()
	return bool(cfg["enabled"] and cfg["api_key"])


def _url(path: str) -> str:
	cfg = _settings()
	prefix = ("/" + cfg["api_prefix"].strip("/")) if cfg["api_prefix"].strip("/") else ""
	return f"{cfg['base_url']}{prefix}/{path.lstrip('/')}"


# ──────────────────────────────── breaker ───────────────────────────────


def _breaker_open() -> bool:
	try:
		return int(frappe.cache().get_value(_BREAKER_KEY) or 0) >= _BREAKER_TRIP_AT
	except Exception:
		return False


def _record_failure() -> None:
	try:
		cache = frappe.cache()
		count = int(cache.get_value(_BREAKER_KEY) or 0) + 1
		cache.set_value(_BREAKER_KEY, count, expires_in_sec=_BREAKER_COOLDOWN_SECS)
	except Exception:
		pass


def _record_success() -> None:
	try:
		frappe.cache().delete_value(_BREAKER_KEY)
	except Exception:
		pass


# ──────────────────────────────── the call ──────────────────────────────


def complete(
	prompt: str,
	*,
	description: str | None = None,
	timeout: int | None = None,
	persona_id: int | None = None,
) -> str:
	"""Send `prompt` to ONYX with tools disabled and return the answer text.

	Args:
	    prompt:      The full message. There is no separate system role on this
	                 endpoint — framing and instructions go in here.
	    description: Chat-session label stored in ONYX, e.g.
	                 ``"daily-status/BHW/2026-08-13/ramesh@naarni.com"``. This is
	                 the audit trail: an admin can open ONYX and see exactly what
	                 was sent for any generated summary.
	    timeout:     Overrides the configured socket timeout (seconds).
	    persona_id:  Overrides the configured persona.

	Returns:
	    The assistant's answer as plain text (never None; may be empty).

	Raises:
	    OnyxConfigError: integration disabled or unconfigured, or breaker open.
	    OnyxApiError:    transport error, non-2xx, or unparseable body.
	"""
	cfg = _settings()
	if not cfg["enabled"]:
		raise OnyxConfigError("ONYX integration is disabled.")
	if not cfg["api_key"]:
		raise OnyxConfigError("ONYX API key is not configured.")
	if _breaker_open():
		raise OnyxConfigError("ONYX circuit breaker is open after repeated failures.")

	payload: dict[str, Any] = {
		"message": prompt,
		"stream": False,
		"include_citations": False,
		# Empty list — not None — is what disables every tool, including search.
		"allowed_tool_ids": [],
		"chat_session_info": {
			"persona_id": persona_id if persona_id is not None else cfg["persona_id"],
			"description": (description or "vehicle-maintenance")[:200],
		},
	}

	try:
		response = requests.post(
			_url("/chat/send-chat-message"),
			json=payload,
			headers={
				"Authorization": f"Bearer {cfg['api_key']}",
				"Content-Type": "application/json",
			},
			timeout=timeout or cfg["timeout"],
		)
	except requests.RequestException as exc:
		_record_failure()
		raise OnyxApiError(f"ONYX request failed: {exc}") from exc

	if response.status_code >= 400:
		_record_failure()
		raise OnyxApiError(f"ONYX returned HTTP {response.status_code}: {response.text[:500]}")

	try:
		body = response.json()
	except ValueError as exc:
		_record_failure()
		raise OnyxApiError("ONYX returned a non-JSON body.") from exc

	if body.get("error_msg"):
		_record_failure()
		raise OnyxApiError(f"ONYX reported an error: {body['error_msg']}")

	_record_success()
	return (body.get("answer") or "").strip()


# ─────────────────────────────── ingestion ──────────────────────────────


def ingest_document(
	*,
	document_id: str,
	semantic_identifier: str,
	text: str,
	link: str | None = None,
	metadata: dict[str, str] | None = None,
	cc_pair_id: int | None = None,
	timeout: int | None = None,
) -> dict:
	"""Upsert a document into ONYX's search index.

	This is what lets someone ask ONYX "what were the blockers at this depot last
	week?" instead of only reading today's email. `document_id` must be stable for
	the thing being described — ONYX upserts on it, so regenerating a record
	updates its document rather than adding a second copy.

	Requires an API key with curator or admin rights, and an ingestion
	connector-credential pair configured in ONYX.

	Returns: {"document_id": ..., "already_existed": bool}.

	Raises:
	    OnyxConfigError / OnyxApiError, as :func:`complete`.
	"""
	cfg = _settings()
	if not cfg["enabled"]:
		raise OnyxConfigError("ONYX integration is disabled.")
	if not cfg["api_key"]:
		raise OnyxConfigError("ONYX API key is not configured.")

	payload: dict[str, Any] = {
		"document": {
			"id": document_id,
			"semantic_identifier": semantic_identifier,
			# `type` is the discriminator on ONYX's section union — send it explicitly
			# rather than relying on which branch pydantic picks.
			"sections": [{"type": "text", "text": text, "link": link}],
			"source": "ingestion_api",
			"metadata": metadata or {},
		}
	}
	if cc_pair_id:
		payload["cc_pair_id"] = cc_pair_id

	try:
		response = requests.post(
			_url("/onyx-api/ingestion"),
			json=payload,
			headers={
				"Authorization": f"Bearer {cfg['api_key']}",
				"Content-Type": "application/json",
			},
			timeout=timeout or cfg["timeout"],
		)
	except requests.RequestException as exc:
		raise OnyxApiError(f"ONYX ingestion failed: {exc}") from exc

	if response.status_code >= 400:
		raise OnyxApiError(f"ONYX ingestion returned HTTP {response.status_code}: {response.text[:500]}")

	try:
		return response.json()
	except ValueError as exc:
		raise OnyxApiError("ONYX ingestion returned a non-JSON body.") from exc


# ───────────────────────────── JSON extraction ──────────────────────────

# ```json … ``` (or a bare ``` fence) is what models emit when asked for JSON.
_FENCE_RE = re.compile(r"```(?:json)?\s*(.+?)```", re.DOTALL | re.IGNORECASE)


def extract_json(text: str) -> dict | None:
	"""Best-effort parse of a JSON object out of a model answer. Never raises.

	Tries, in order: the whole string, the first fenced block, then the first
	balanced ``{...}`` span. Returns None when nothing parses to a dict — callers
	are expected to fall back rather than treat that as an error, because ONYX has
	no structured-output guarantee on this endpoint.
	"""
	if not text:
		return None

	candidates: list[str] = [text.strip()]
	fence = _FENCE_RE.search(text)
	if fence:
		candidates.append(fence.group(1).strip())
	start = text.find("{")
	end = text.rfind("}")
	if start != -1 and end > start:
		candidates.append(text[start : end + 1])

	for candidate in candidates:
		try:
			parsed = json.loads(candidate)
		except (TypeError, ValueError):
			continue
		if isinstance(parsed, dict):
			return parsed
	return None


# ──────────────────────────────── ops probe ─────────────────────────────


@frappe.whitelist()
def ping() -> dict:
	"""Ops smoke test: round-trip a trivial prompt and report what came back.

	Returns: {success, data: {enabled, url, answer|error}}. Never throws on an
	ONYX-side failure — the point is to *see* the failure.
	"""
	frappe.only_for(["System Manager", "Central Ops"])
	cfg = _settings()
	data: dict[str, Any] = {
		"enabled": cfg["enabled"],
		"url": _url("/chat/send-chat-message"),
		"persona_id": cfg["persona_id"],
		"key_configured": bool(cfg["api_key"]),
		"breaker_open": _breaker_open(),
	}
	try:
		data["answer"] = complete(
			"Reply with exactly: OK", description="vehicle-maintenance/ping", timeout=20
		)
		data["ok"] = True
	except (OnyxConfigError, OnyxApiError) as exc:
		data["ok"] = False
		data["error"] = str(exc)
	return {"success": True, "data": data}
