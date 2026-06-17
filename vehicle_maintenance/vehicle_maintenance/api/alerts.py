"""Alert configuration API.

Customer-facing, session-scoped CRUD that the webApp-naarni React app calls, plus
one service-authenticated method (`get_engine_config`) that the Naarni alert engine
polls to replace its rules.yaml / customers.yaml.

Every customer-facing method:
- Resolves `frappe.session.user` -> the linked `Customer`; all reads/writes are
  scoped to that customer (multi-tenant isolation). Staff (Central Ops / System
  Manager) may target another customer via an explicit `customer` argument.
- Returns the project-standard `{success, data, message}` envelope.
- Wraps user-facing strings in `_()`.

See docs/alert-config-api-contract.md in the webApp repo for the wire contract.
"""

from __future__ import annotations

import hmac
from typing import Any

import frappe
from frappe import _

from vehicle_maintenance.fleet_service.doctype.alert_type.alert_type import op_to_symbol

STAFF_ROLES = {"Central Ops", "System Manager", "Administrator"}
CHANNEL_MAP = (
	# (DocType fieldname, engine channel token)
	("notify_in_app", "in_app"),
	("notify_email", "email"),
	("notify_chat", "chat"),
	("notify_webhook", "webhook"),
)


# --------------------------------------------------------------------- helpers


def _ok(data: Any, message: str = "") -> dict:
	return {"success": True, "data": data, "message": message}


def _coerce_payload(payload: Any) -> dict:
	if isinstance(payload, str):
		return frappe.parse_json(payload) or {}
	return dict(payload or {})


def _request_body(payload: Any, kwargs: dict) -> dict:
	"""Resolve the request body whether the client posted a flat JSON object
	(Frappe maps its keys to **kwargs) or wrapped it under a `payload` key.

	The webApp posts the body flat per the API contract, so the kwargs branch is
	the normal path; the `payload` branch keeps server-to-server callers working.
	"""
	if payload is not None:
		return _coerce_payload(payload)
	data = dict(kwargs or {})
	data.pop("cmd", None)  # Frappe's dispatch marker, never part of the body
	return data


def _as_dict(value: Any) -> dict:
	if isinstance(value, str):
		return frappe.parse_json(value) or {}
	return dict(value or {})


def _as_list(value: Any) -> list:
	if isinstance(value, str):
		return frappe.parse_json(value) or []
	return list(value or [])


def _verify_proxy() -> str | None:
	"""If a trusted proxy signed this request, return the customer it asserts
	(possibly an empty string); otherwise return None so callers fall back to
	normal Frappe session auth.

	The webApp talks to api.naarni.com (which validates the customer's naarni JWT),
	and that backend proxies to Frappe with a shared key + the resolved customer.
	The shared key lives in site_config as `alert_proxy_key` (never committed). This
	lets browser users reach these methods without a Frappe login, while staying
	scoped to exactly their customer.
	"""
	proxy_key = frappe.get_request_header("X-Naarni-Proxy-Key")
	expected = frappe.conf.get("alert_proxy_key")
	if proxy_key and expected and hmac.compare_digest(str(proxy_key), str(expected)):
		return frappe.get_request_header("X-Naarni-Customer") or ""
	return None


def _require_access() -> None:
	"""Gate a non-customer-scoped read (the catalog): allow a trusted proxy or any
	logged-in (non-Guest) Frappe session; reject anonymous callers."""
	if _verify_proxy() is not None:
		return
	if frappe.session.user and frappe.session.user != "Guest":
		return
	frappe.throw(_("Authentication required."), frappe.PermissionError)


def _current_customer(customer: str | None = None) -> str:
	"""Resolve the customer this request acts on, in priority order:

	1. Trusted proxy: api.naarni.com asserts the customer via signed headers.
	2. Staff session: Central Ops / System Manager may pass an explicit `customer`.
	3. Customer session: the user's own linked Customer.
	"""
	proxied = _verify_proxy()
	if proxied is not None:
		cust = customer or proxied
		if not cust or not frappe.db.exists("Customer", cust):
			frappe.throw(_("Unknown or missing customer."), frappe.PermissionError)
		return cust

	user = frappe.session.user
	roles = set(frappe.get_roles(user))

	if customer and (roles & STAFF_ROLES):
		if not frappe.db.exists("Customer", customer):
			frappe.throw(_("Customer {0} not found.").format(customer))
		return customer

	linked = frappe.db.get_value("Customer", {"user": user}, "name")
	if not linked:
		frappe.throw(
			_("No customer is linked to your account. Contact Naarni support."),
			frappe.PermissionError,
		)
	return linked


def _channels_to_dict(sub) -> dict:
	return {token: bool(sub.get(field)) for field, token in CHANNEL_MAP}


def _channels_to_list(sub) -> list[str]:
	return [token for field, token in CHANNEL_MAP if sub.get(field)]


def _subscription_row(at: dict, sub) -> dict:
	"""Merge a catalog Alert Type with an optional saved Alert Subscription."""
	if sub:
		channels = _channels_to_dict(sub)
		return {
			"alert_type": at["name"],
			"title": at.get("title") or at.get("alert_name"),
			"parameter": at["parameter"],
			"op": op_to_symbol(at["op"]),
			"unit": at.get("unit"),
			"severity": at["severity"],
			"enabled": bool(sub.enabled),
			"threshold": sub.threshold if sub.threshold is not None else at["default_threshold"],
			"default_threshold": at["default_threshold"],
			"sustained_min": sub.sustained_min or 5,
			"suppression_min": sub.suppression_min or 15,
			"channels": channels,
			"vehicle_scope": "selected" if sub.vehicle_scope == "Selected" else "all",
			"vehicles": [r.device_id for r in (sub.vehicles or []) if r.device_id],
			"subscription_name": sub.name,
		}
	# Unsaved: show catalog defaults so the UI renders a sensible starting state.
	return {
		"alert_type": at["name"],
		"title": at.get("title") or at.get("alert_name"),
		"parameter": at["parameter"],
		"op": at["op"],
		"unit": at.get("unit"),
		"severity": at["severity"],
		"enabled": False,
		"threshold": at["default_threshold"],
		"default_threshold": at["default_threshold"],
		"sustained_min": 5,
		"suppression_min": 15,
		"channels": {"in_app": True, "email": False, "chat": False, "webhook": False},
		"vehicle_scope": "all",
		"vehicles": [],
		"subscription_name": None,
	}


# ----------------------------------------------------------------- catalog/read


@frappe.whitelist(allow_guest=True)
def get_alert_catalog() -> dict:
	"""Naarni-managed catalog of available alert types (read-only)."""
	_require_access()
	rows = frappe.get_all(
		"Alert Type",
		filters={"enabled": 1},
		fields=[
			"name",
			"alert_name",
			"parameter",
			"op",
			"default_threshold",
			"unit",
			"severity",
			"title",
			"description",
		],
		order_by="severity asc, alert_name asc",
		limit_page_length=0,
	)
	for r in rows:
		r["op"] = op_to_symbol(r.get("op"))
	return _ok(rows)


@frappe.whitelist(allow_guest=True)
def get_my_subscriptions(customer: str | None = None) -> dict:
	"""One row per enabled catalog type, merged with this customer's saved config."""
	customer = _current_customer(customer)
	catalog = frappe.get_all(
		"Alert Type",
		filters={"enabled": 1},
		fields=[
			"name",
			"alert_name",
			"parameter",
			"op",
			"default_threshold",
			"unit",
			"severity",
			"title",
		],
		order_by="severity asc, alert_name asc",
		limit_page_length=0,
	)
	sub_names = frappe.get_all(
		"Alert Subscription",
		filters={"customer": customer},
		pluck="name",
		limit_page_length=0,
	)
	subs_by_type: dict[str, Any] = {}
	for name in sub_names:
		doc = frappe.get_doc("Alert Subscription", name)
		subs_by_type[doc.alert_type] = doc

	rows = [_subscription_row(at, subs_by_type.get(at["name"])) for at in catalog]
	return _ok(rows)


@frappe.whitelist(allow_guest=True)
def get_my_vehicles(customer: str | None = None) -> dict:
	"""This customer's vehicles, for the assignment multiselect. Only those with a
	device_id can be alert-routed."""
	customer = _current_customer(customer)
	rows = frappe.get_all(
		"Vehicle",
		filters={"customer": customer},
		fields=["device_id", "registration_number", "make_model"],
		order_by="registration_number asc",
		limit_page_length=0,
	)
	return _ok(rows)


# ----------------------------------------------------------------- subscriptions


@frappe.whitelist(allow_guest=True, methods=["POST"])
def upsert_subscription(payload: Any = None, customer: str | None = None, **kwargs) -> dict:
	"""Create or update this customer's subscription for one alert type."""
	customer = _current_customer(customer)
	data = _request_body(payload, kwargs)

	alert_type = data.get("alert_type")
	if not alert_type or not frappe.db.exists("Alert Type", alert_type):
		frappe.throw(_("Unknown alert type."))

	name = frappe.db.get_value("Alert Subscription", {"customer": customer, "alert_type": alert_type}, "name")
	doc = frappe.get_doc("Alert Subscription", name) if name else frappe.new_doc("Alert Subscription")
	if not name:
		doc.customer = customer
		doc.alert_type = alert_type

	doc.enabled = 1 if data.get("enabled") else 0
	if data.get("threshold") not in (None, ""):
		doc.threshold = data["threshold"]
	doc.sustained_min = data.get("sustained_min") or 5
	doc.suppression_min = data.get("suppression_min") or 15

	channels = _as_dict(data.get("channels"))
	for field, token in CHANNEL_MAP:
		doc.set(field, 1 if channels.get(token) else 0)

	scope = (data.get("vehicle_scope") or "all").lower()
	doc.vehicle_scope = "Selected" if scope == "selected" else "All"
	doc.vehicles = []
	if doc.vehicle_scope == "Selected":
		for device_id in _as_list(data.get("vehicles")):
			vehicle = frappe.db.get_value("Vehicle", {"device_id": device_id, "customer": customer}, "name")
			if not vehicle:
				frappe.throw(_("Vehicle with device id {0} is not yours.").format(device_id))
			doc.append("vehicles", {"vehicle": vehicle, "device_id": device_id})

	doc.save(ignore_permissions=False)
	frappe.db.commit()

	saved = frappe.get_doc("Alert Subscription", doc.name)
	at = frappe.db.get_value(
		"Alert Type",
		alert_type,
		["name", "alert_name", "parameter", "op", "default_threshold", "unit", "severity", "title"],
		as_dict=True,
	)
	return _ok(_subscription_row(at, saved), _("Subscription saved."))


# ----------------------------------------------------------------- channel prefs


def _get_or_new_prefs(customer: str):
	name = frappe.db.get_value("Alert Channel Preference", {"customer": customer}, "name")
	if name:
		return frappe.get_doc("Alert Channel Preference", name)
	doc = frappe.new_doc("Alert Channel Preference")
	doc.customer = customer
	return doc


def _prefs_to_dict(doc) -> dict:
	try:
		secret_set = bool(doc.get_password("webhook_secret", raise_exception=False))
	except Exception:
		secret_set = bool(doc.get("webhook_secret"))
	return {
		"novu_subscriber_id": doc.novu_subscriber_id,
		"email": doc.email,
		"teams_enabled": bool(doc.teams_enabled),
		"slack_enabled": bool(doc.slack_enabled),
		"webhook_url": doc.webhook_url,
		"webhook_secret_set": secret_set,
	}


@frappe.whitelist(allow_guest=True)
def get_channel_prefs(customer: str | None = None) -> dict:
	customer = _current_customer(customer)
	doc = _get_or_new_prefs(customer)
	return _ok(_prefs_to_dict(doc))


@frappe.whitelist(allow_guest=True, methods=["POST"])
def save_channel_prefs(payload: Any = None, customer: str | None = None, **kwargs) -> dict:
	"""Save customer-level recipients/channels. novu_subscriber_id is provisioned by
	Naarni and is NOT customer-editable here."""
	customer = _current_customer(customer)
	data = _request_body(payload, kwargs)
	doc = _get_or_new_prefs(customer)

	doc.email = data.get("email")
	doc.teams_enabled = 1 if data.get("teams_enabled") else 0
	doc.slack_enabled = 1 if data.get("slack_enabled") else 0
	doc.webhook_url = data.get("webhook_url")
	if data.get("webhook_secret"):  # only overwrite when a new secret is supplied
		doc.webhook_secret = data["webhook_secret"]

	doc.save(ignore_permissions=False)
	frappe.db.commit()
	return _ok(_prefs_to_dict(frappe.get_doc("Alert Channel Preference", doc.name)), _("Saved."))


# --------------------------------------------------------------- engine service


@frappe.whitelist(allow_guest=True, methods=["POST"])
def get_engine_config(service_key: str | None = None) -> dict:
	"""Compiled per-customer alert config for the Naarni alert engine.

	Service-authenticated via a shared key (site_config `alert_engine_service_key`).
	NOT for the frontend. Replaces the engine's rules.yaml + customers.yaml.
	"""
	expected = frappe.conf.get("alert_engine_service_key")
	if not expected or not service_key or not hmac.compare_digest(str(service_key), str(expected)):
		frappe.throw(_("Invalid service key."), frappe.PermissionError)

	at_by_name = {
		at["name"]: at
		for at in frappe.get_all(
			"Alert Type",
			fields=[
				"name",
				"parameter",
				"op",
				"default_threshold",
				"match_value",
				"unit",
				"icon",
				"message_template",
				"severity",
				"title",
				"channel",
			],
			limit_page_length=0,
		)
	}

	# Teams channel registry: friendly name -> decrypted webhook URL. The engine
	# posts each alert's card to its rule's channel (or the default when unset).
	teams_channels: dict[str, str] = {}
	default_teams_channel: str | None = None
	for ch in frappe.get_all(
		"Notification Channel",
		filters={"enabled": 1, "channel_type": "Teams"},
		fields=["name", "is_default"],
		limit_page_length=0,
	):
		cdoc = frappe.get_doc("Notification Channel", ch["name"])
		try:
			url = cdoc.get_password("webhook_url", raise_exception=False)
		except Exception:
			url = cdoc.get("webhook_url")
		if not url:
			continue
		teams_channels[ch["name"]] = url
		if ch.get("is_default") and not default_teams_channel:
			default_teams_channel = ch["name"]

	# device_id -> customer, customer -> [device_ids], device_id -> registration_number
	vehicle_rows = frappe.get_all(
		"Vehicle",
		filters=[["device_id", "is", "set"], ["customer", "is", "set"]],
		fields=["device_id", "customer", "registration_number"],
		limit_page_length=0,
	)
	devices_by_customer: dict[str, list[str]] = {}
	registrations: dict[str, str] = {}
	for v in vehicle_rows:
		dev = str(v["device_id"])
		devices_by_customer.setdefault(v["customer"], []).append(dev)
		if v.get("registration_number"):
			registrations[dev] = v["registration_number"]

	customers_out = []
	prefs_names = frappe.get_all("Alert Channel Preference", pluck="customer", limit_page_length=0)
	sub_customers = frappe.get_all(
		"Alert Subscription", filters={"enabled": 1}, pluck="customer", limit_page_length=0
	)
	customer_ids = sorted(set(prefs_names) | set(sub_customers))

	for cust in customer_ids:
		prefs = _get_or_new_prefs(cust)
		webhook = None
		if prefs.webhook_url:
			try:
				secret = prefs.get_password("webhook_secret", raise_exception=False)
			except Exception:
				secret = None
			webhook = {"url": prefs.webhook_url, "secret": secret}

		rules = []
		sub_names = frappe.get_all(
			"Alert Subscription",
			filters={"customer": cust, "enabled": 1},
			pluck="name",
			limit_page_length=0,
		)
		for sn in sub_names:
			sub = frappe.get_doc("Alert Subscription", sn)
			at = at_by_name.get(sub.alert_type)
			if not at:
				continue
			rule_vehicles = (
				[r.device_id for r in (sub.vehicles or []) if r.device_id]
				if sub.vehicle_scope == "Selected"
				else []
			)
			match_value = at.get("match_value")
			rules.append(
				{
					"id": sub.alert_type,
					"parameter": at["parameter"],
					"op": op_to_symbol(at["op"]),
					# Numeric: threshold (customer override or default). Categorical/Boolean:
					# match_value drives it and threshold is ignored by the engine.
					"threshold": sub.threshold if sub.threshold is not None else at["default_threshold"],
					"match_value": match_value or None,
					"unit": at.get("unit"),
					"icon": at.get("icon"),
					"message_template": at.get("message_template"),
					"severity": at["severity"],
					"title": at.get("title"),
					"duration_min": sub.sustained_min or 5,
					"suppression_min": sub.suppression_min or 15,
					"channels": _channels_to_list(sub),
					"channel": at.get("channel") or None,
					"vehicles": rule_vehicles,  # [] = applies to all of the customer's vehicles
				}
			)

		if not rules and not webhook:
			continue

		customers_out.append(
			{
				"id": cust,
				"novu_subscriber_id": prefs.novu_subscriber_id,
				"email": prefs.email,
				"webhook": webhook,
				"vehicles": devices_by_customer.get(cust, []),
				"rules": rules,
			}
		)

	return _ok(
		{
			"customers": customers_out,
			"registrations": registrations,
			"teams_channels": teams_channels,
			"default_teams_channel": default_teams_channel,
		}
	)
