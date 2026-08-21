# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Azure OpenAI client — the same models ONYX runs on, called directly.

ONYX is backed by our own Azure OpenAI resource (`gpt-4.1-mini` on
`naanri.openai.azure.com`), so talking to Azure directly is the same model and
the same token cost with one hop fewer. It is worth doing for one reason that
has nothing to do with latency:

**Azure gives us guaranteed JSON; ONYX cannot.** ONYX's chat endpoint drops
`structured_response_format` on the floor (a documented upstream TODO), so every
caller has to ask for JSON in the prose and hope. Azure's
`response_format: {"type": "json_schema", "strict": true}` makes the model
*unable* to return anything but the schema. A whole class of "the summary did not
parse today" simply stops existing.

What ONYX is still the only way to do is **search** — the ingestion API and the
assistant over the corpus. So this module does not replace
`onyx_client`; it takes over the summarising, and ONYX keeps the retrieval.

Configuration lives on the **Azure AI Settings** Single, overridden by
site_config keys of the same name prefixed `azure_ai_`.
"""

from __future__ import annotations

import json
from typing import Any

import frappe
import requests
from frappe.utils import cint

# Our Azure OpenAI resource, as configured in ONYX today. The resource name is
# spelled "naanri" there; that is the real host, not a typo to fix here.
DEFAULT_ENDPOINT = "https://naanri.openai.azure.com"
DEFAULT_DEPLOYMENT = "gpt-4.1-mini"

# Structured outputs need 2024-08-01-preview or newer. This matches the version
# ONYX's provider record carries.
DEFAULT_API_VERSION = "2025-03-01-preview"

DEFAULT_TIMEOUT = 45
DEFAULT_MAX_TOKENS = 1200

# Same dead-man's-switch as the ONYX client: a burst of failures must drop
# callers onto their deterministic path rather than making each one wait out a
# timeout.
_BREAKER_KEY = "azure_ai_consecutive_failures"
_BREAKER_TRIP_AT = 3
_BREAKER_COOLDOWN_SECS = 15 * 60


class AzureConfigError(RuntimeError):
	"""Azure AI is disabled or its required configuration is missing."""


class AzureApiError(RuntimeError):
	"""Azure returned a non-2xx response, timed out, or sent an unusable body."""


# ──────────────────────────────── config ────────────────────────────────


def _settings() -> dict:
	conf = frappe.conf or {}
	try:
		doc = frappe.get_cached_doc("Azure AI Settings")
	except Exception:
		doc = None

	def pick(conf_key: str, field: str, default=None):
		if conf.get(conf_key) is not None:
			return conf.get(conf_key)
		value = getattr(doc, field, None) if doc else None
		return default if value in (None, "") else value

	return {
		# cint, not bool: site_config values set with `bench set-config` arrive as
		# strings and the string "0" is truthy.
		"enabled": bool(cint(pick("azure_ai_enabled", "enabled", 0))),
		"endpoint": str(pick("azure_ai_endpoint", "endpoint", DEFAULT_ENDPOINT)).rstrip("/"),
		"deployment": str(pick("azure_ai_deployment", "deployment", DEFAULT_DEPLOYMENT)),
		"api_version": str(pick("azure_ai_api_version", "api_version", DEFAULT_API_VERSION)),
		"timeout": int(
			pick("azure_ai_timeout_seconds", "timeout_seconds", DEFAULT_TIMEOUT) or DEFAULT_TIMEOUT
		),
		"max_tokens": int(
			pick("azure_ai_max_tokens", "max_tokens", DEFAULT_MAX_TOKENS) or DEFAULT_MAX_TOKENS
		),
		"api_key": _api_key(doc, conf),
	}


def _api_key(doc, conf: dict) -> str | None:
	if conf.get("azure_ai_api_key"):
		return str(conf["azure_ai_api_key"])
	if not doc:
		return None
	try:
		return doc.get_password("api_key", raise_exception=False)
	except Exception:
		return None


def is_enabled() -> bool:
	"""Master switch. Off until an admin ticks Enabled *and* stores a key."""
	cfg = _settings()
	return bool(cfg["enabled"] and cfg["api_key"])


def _url(cfg: dict) -> str:
	return (
		f"{cfg['endpoint']}/openai/deployments/{cfg['deployment']}"
		f"/chat/completions?api-version={cfg['api_version']}"
	)


# ──────────────────────────────── breaker ───────────────────────────────


def _breaker_open() -> bool:
	try:
		return int(frappe.cache().get_value(_BREAKER_KEY) or 0) >= _BREAKER_TRIP_AT
	except Exception:
		return False


def _record_failure() -> None:
	try:
		cache = frappe.cache()
		cache.set_value(
			_BREAKER_KEY,
			int(cache.get_value(_BREAKER_KEY) or 0) + 1,
			expires_in_sec=_BREAKER_COOLDOWN_SECS,
		)
	except Exception:
		pass


def _record_success() -> None:
	try:
		frappe.cache().delete_value(_BREAKER_KEY)
	except Exception:
		pass


# ──────────────────────────────── the calls ─────────────────────────────


def complete(
	prompt: str,
	*,
	system: str | None = None,
	schema: dict | None = None,
	schema_name: str = "response",
	timeout: int | None = None,
	temperature: float = 0.0,
) -> str:
	"""Call the deployment and return the assistant's message content.

	Args:
	    prompt:      The user message.
	    system:      Optional system message. Unlike ONYX's chat endpoint, Azure
	                 takes a real system role, so instructions do not have to be
	                 crammed into the user turn.
	    schema:      A JSON Schema. When given, the model is *constrained* to it —
	                 `strict: true` means the response cannot be malformed. The
	                 schema must set `additionalProperties: false` and list every
	                 property in `required`, which is Azure's rule, not ours.
	    temperature: 0 by default. A shift summary should not be creative.

	Returns:
	    The message content as a string (JSON text when `schema` was given).

	Raises:
	    AzureConfigError: disabled, unconfigured, or breaker open.
	    AzureApiError:    transport error, non-2xx, refusal, or truncation.
	"""
	cfg = _settings()
	if not cfg["enabled"]:
		raise AzureConfigError("Azure AI integration is disabled.")
	if not cfg["api_key"]:
		raise AzureConfigError("Azure AI API key is not configured.")
	if _breaker_open():
		raise AzureConfigError("Azure AI circuit breaker is open after repeated failures.")

	messages: list[dict[str, str]] = []
	if system:
		messages.append({"role": "system", "content": system})
	messages.append({"role": "user", "content": prompt})

	payload: dict[str, Any] = {
		"messages": messages,
		"temperature": temperature,
		"max_tokens": cfg["max_tokens"],
	}
	if schema:
		payload["response_format"] = {
			"type": "json_schema",
			"json_schema": {"name": schema_name, "strict": True, "schema": schema},
		}

	try:
		response = requests.post(
			_url(cfg),
			json=payload,
			headers={"api-key": cfg["api_key"], "Content-Type": "application/json"},
			timeout=timeout or cfg["timeout"],
		)
	except requests.RequestException as exc:
		_record_failure()
		raise AzureApiError(f"Azure AI request failed: {exc}") from exc

	if response.status_code >= 400:
		_record_failure()
		raise AzureApiError(f"Azure AI returned HTTP {response.status_code}: {response.text[:500]}")

	try:
		body = response.json()
		choice = body["choices"][0]
	except (ValueError, KeyError, IndexError) as exc:
		_record_failure()
		raise AzureApiError("Azure AI returned a body with no choices.") from exc

	message = choice.get("message") or {}

	# A refusal is a successful HTTP call with no usable content — surface it as
	# an error so the caller falls back instead of storing an empty summary.
	if message.get("refusal"):
		_record_failure()
		raise AzureApiError(f"Azure AI refused: {str(message['refusal'])[:200]}")

	# Truncation silently produces invalid JSON under a schema. Treat it as a
	# failure rather than handing the caller half an object.
	if choice.get("finish_reason") == "length":
		_record_failure()
		raise AzureApiError("Azure AI hit the token limit before finishing the response.")

	_record_success()
	return (message.get("content") or "").strip()


def complete_json(
	prompt: str,
	*,
	schema: dict,
	system: str | None = None,
	schema_name: str = "response",
	timeout: int | None = None,
) -> dict:
	"""`complete` with a schema, parsed. Returns a dict guaranteed to fit `schema`.

	Raises AzureApiError if the content somehow does not parse — which strict mode
	makes a bug rather than an expected outcome, so it is worth hearing about.
	"""
	text = complete(prompt, system=system, schema=schema, schema_name=schema_name, timeout=timeout)
	try:
		parsed = json.loads(text)
	except (TypeError, ValueError) as exc:
		raise AzureApiError("Azure AI returned unparseable JSON under a strict schema.") from exc
	if not isinstance(parsed, dict):
		raise AzureApiError("Azure AI returned JSON that was not an object.")
	return parsed


# ──────────────────────────────── ops probe ─────────────────────────────


@frappe.whitelist()
def ping() -> dict:
	"""Ops smoke test: round-trip a tiny schema-constrained call and report back.

	Returns: {success, data: {...}}. Never throws on an Azure-side failure — the
	point is to see the failure.
	"""
	frappe.only_for(["System Manager", "Central Ops"])
	cfg = _settings()
	data: dict[str, Any] = {
		"enabled": cfg["enabled"],
		"url": _url(cfg),
		"deployment": cfg["deployment"],
		"key_configured": bool(cfg["api_key"]),
		"breaker_open": _breaker_open(),
	}
	try:
		data["answer"] = complete_json(
			"Reply with ok set to true.",
			schema={
				"type": "object",
				"properties": {"ok": {"type": "boolean"}},
				"required": ["ok"],
				"additionalProperties": False,
			},
			schema_name="ping",
			timeout=20,
		)
		data["ok"] = True
	except (AzureConfigError, AzureApiError) as exc:
		data["ok"] = False
		data["error"] = str(exc)
	return {"success": True, "data": data}
