"""One-shot configuration helpers for the Brevo email pipeline.

Reads the `BREVO_EMAIL_KEY` (and optional sender overrides) from a gitignored
`.env` file and persists them into Frappe's site_config — the runtime source
of truth. The `.env` itself is never read by the app at runtime.

Typical first-run:

    bench --site dev.localhost execute \\
        vehicle_maintenance.fleet_service.setup_email.configure_brevo_from_env \\
        --kwargs "{'env_path': '/Users/mayank/Documents/frappe/vehicle_maintenance/.env', 'sender_email': 'mayank.dwivedi@naarni.com', 'sender_name': 'NaArNi Fleet Service'}"

Or directly with a key (for CI / scripts):

    bench --site dev.localhost execute \\
        vehicle_maintenance.fleet_service.setup_email.configure_brevo \\
        --kwargs "{'api_key': 'xkeysib-...', 'sender_email': '...', 'sender_name': '...'}"
"""

from __future__ import annotations

import os
import re

import frappe

# Keys we recognise in the .env file (case-insensitive).
_API_KEY_NAMES = ("BREVO_EMAIL_KEY", "BREVO_API_KEY", "BREVO_KEY")
_SENDER_EMAIL_NAMES = ("BREVO_SENDER_EMAIL", "SENDER_EMAIL")
_SENDER_NAME_NAMES = ("BREVO_SENDER_NAME", "SENDER_NAME")


def _parse_env_file(path: str) -> dict[str, str]:
    """Minimal .env parser: KEY=VALUE per line, `#` comments, `export` ok.

    Does not execute the file. Does not log values.
    """
    if not os.path.exists(path):
        raise FileNotFoundError(f".env not found: {path}")
    result: dict[str, str] = {}
    with open(path, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("export "):
                line = line[len("export "):].strip()
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip()
            # Strip matching quotes but preserve embedded content.
            value = value.strip().strip("'\"")
            if key:
                result[key] = value
    return result


def _first_set(env: dict[str, str], names: tuple[str, ...]) -> str | None:
    """Return the first non-empty value among `names` in the env dict."""
    for n in names:
        v = env.get(n)
        if v:
            return v
    return None


@frappe.whitelist()
def configure_brevo(
    api_key: str,
    sender_email: str,
    sender_name: str = "NaArNi Fleet Service",
    enable: bool = True,
) -> dict:
    """Write Brevo credentials to site_config and toggle email on.

    Only site_config is touched — never any repo file. The API key is
    accepted via the arg; the caller (`configure_brevo_from_env` below) is
    where the .env read happens.
    """
    frappe.only_for(["Administrator", "System Manager"])

    if not (api_key or "").strip():
        frappe.throw("api_key is required.")
    if not sender_email or "@" not in sender_email:
        frappe.throw("A valid sender_email is required (must be verified in Brevo).")

    # Light sanity check — Brevo v3 keys start with 'xkeysib-'.
    key = api_key.strip()
    if not re.match(r"^xkeysib-[A-Za-z0-9]{40,}", key):
        # Not fatal, but flag so typos are caught.
        frappe.msgprint(
            "API key format doesn't look like a Brevo v3 key (expected "
            "'xkeysib-…'). Proceeding anyway — verify with verify_connection.",
            alert=True, indicator="orange",
        )

    from frappe.installer import update_site_config
    update_site_config("brevo_api_key", key)
    update_site_config("brevo_sender_email", sender_email.strip())
    update_site_config("brevo_sender_name", sender_name.strip())
    if enable:
        update_site_config("enable_email_notifications", 1)

    # Reload frappe.conf so the current process sees the new values.
    frappe.conf.update({
        "brevo_api_key": key,
        "brevo_sender_email": sender_email.strip(),
        "brevo_sender_name": sender_name.strip(),
        "enable_email_notifications": 1 if enable else 0,
    })

    from vehicle_maintenance.fleet_service import brevo_client
    return {
        "success": True,
        "data": {
            "sender_email": sender_email.strip(),
            "sender_name": sender_name.strip(),
            "api_key_set": True,
            "is_enabled": brevo_client.is_enabled(),
        },
        "message": "Brevo email pipeline configured.",
    }


@frappe.whitelist()
def configure_brevo_from_env(
    env_path: str,
    sender_email: str | None = None,
    sender_name: str | None = None,
    enable: bool = True,
) -> dict:
    """Load Brevo config from a local `.env` file into site_config.

    The API key is read from `BREVO_EMAIL_KEY` (or `BREVO_API_KEY`). Sender
    overrides may come from `BREVO_SENDER_EMAIL` / `BREVO_SENDER_NAME` in the
    .env, or from the kwargs (kwargs take precedence). Kwargs are required if
    the .env doesn't carry a sender.
    """
    frappe.only_for(["Administrator", "System Manager"])

    env = _parse_env_file(env_path)
    api_key = _first_set(env, _API_KEY_NAMES)
    if not api_key:
        frappe.throw(
            "Brevo API key not found in .env. Expected one of: "
            + ", ".join(_API_KEY_NAMES)
        )
    final_sender = (sender_email or _first_set(env, _SENDER_EMAIL_NAMES) or "").strip()
    final_name = (
        sender_name or _first_set(env, _SENDER_NAME_NAMES) or "NaArNi Fleet Service"
    ).strip()

    if not final_sender:
        frappe.throw(
            "Sender email missing. Pass `sender_email` as a kwarg OR add "
            "BREVO_SENDER_EMAIL to the .env file."
        )

    return configure_brevo(
        api_key=api_key,
        sender_email=final_sender,
        sender_name=final_name,
        enable=enable,
    )


@frappe.whitelist()
def disable_email_notifications() -> dict:
    """Kill switch — keeps credentials in place but stops outbound email."""
    frappe.only_for(["Administrator", "System Manager"])
    from frappe.installer import update_site_config
    update_site_config("enable_email_notifications", 0)
    frappe.conf["enable_email_notifications"] = 0
    return {"success": True, "message": "Email notifications disabled."}


@frappe.whitelist()
def email_status() -> dict:
    """Non-sensitive status — reports which Brevo keys are present."""
    frappe.only_for(["Administrator", "System Manager"])
    conf = frappe.conf or {}
    return {
        "success": True,
        "data": {
            "provider": "brevo",
            "enabled": bool(conf.get("enable_email_notifications")),
            "api_key_set": bool(conf.get("brevo_api_key")),
            "sender_email": conf.get("brevo_sender_email") or None,
            "sender_name": conf.get("brevo_sender_name") or None,
        },
    }


@frappe.whitelist()
def purge_m365_config() -> dict:
    """Clean M365 keys out of site_config after the Brevo switch.

    Idempotent. Does not delete the m365_client.py file — do that manually
    with `git rm` once you're confident the switch is stable.
    """
    frappe.only_for(["Administrator", "System Manager"])
    from frappe.installer import update_site_config
    removed = []
    for key in ("m365_tenant_id", "m365_client_id", "m365_client_secret",
                "m365_sender_email"):
        if frappe.conf.get(key):
            update_site_config(key, None)
            frappe.conf.pop(key, None)
            removed.append(key)
    try:
        frappe.cache().delete_value("vm_m365_access_token")
    except Exception:
        pass
    return {
        "success": True,
        "data": {"removed_keys": removed},
        "message": f"Purged {len(removed)} M365 key(s) from site_config.",
    }
