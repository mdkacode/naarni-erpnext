"""One-shot configuration helpers for the M365 email pipeline.

Use exactly once per site to lift the Microsoft credentials out of a local,
gitignored text file and into Frappe's site_config (which is stored on the
server, not the repo). Subsequent code reads them via `frappe.conf`.

Typical first-run:

    bench --site dev.localhost execute \\
        vehicle_maintenance.fleet_service.setup_email.configure_from_file \\
        --kwargs '{"path": "/Users/mayank/Documents/frappe/m365.txt", "sender": "admin@naarni.com"}'

After this, the M365 client will pick up the creds from `frappe.conf` and
`enable_email_notifications` will be toggled on.
"""

from __future__ import annotations

import os
import re

import frappe

# Matches UUID v4 (tenant id / client id) on its own line or in prose.
_UUID_RE = re.compile(
    r"\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\b",
    re.IGNORECASE,
)


def _parse_credentials_text(text: str) -> dict[str, str]:
    """Extract credentials from a free-form text file.

    Expected content — any combination of:
      • 'Application id - <guid>'  or  'Client id - <guid>'
      • 'seceret - <value>'        or  'Client Secret - <value>'
      • 'seceter id - <guid>'      (metadata only, ignored)
      • An openid-configuration URL containing the tenant id in its path.

    The parser is intentionally forgiving because the file was hand-typed.
    """
    tenant_id: str | None = None
    client_id: str | None = None
    client_secret: str | None = None

    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]

    for line in lines:
        low = line.lower()
        if "login.microsoftonline.com" in low:
            m = _UUID_RE.search(line)
            if m:
                tenant_id = m.group(1)
            continue

        if "application id" in low or "client id" in low or "app id" in low:
            m = _UUID_RE.search(line)
            if m:
                client_id = m.group(1)
            continue

        # "seceter id" in the source file is metadata; skip so it doesn't
        # shadow client_id.
        if "seceter id" in low or "secret id" in low:
            continue

        if ("secret" in low and "id" not in low) or "seceret" in low:
            # Strip any leading "... - " prefix and take the last non-space token.
            parts = re.split(r"\s*[-–=:]\s*", line, maxsplit=1)
            candidate = parts[-1].strip() if len(parts) > 1 else line
            # Microsoft secrets look like `ABC1~…~XYZ`; strip stray quotes.
            candidate = candidate.strip(" '\"")
            if candidate and 10 < len(candidate) < 200:
                client_secret = candidate

    return {
        "tenant_id": tenant_id or "",
        "client_id": client_id or "",
        "client_secret": client_secret or "",
    }


@frappe.whitelist()
def configure_from_file(path: str, sender: str,
                        enable: bool = True) -> dict:
    """Load M365 credentials from a local text file into site_config.

    Args:
        path:   Absolute path to the credentials file. Parsed leniently.
        sender: Mailbox the notification emails originate from
                (e.g. 'admin@naarni.com'). Must match a real mailbox in the
                tenant with granted Mail.Send permission.
        enable: Whether to flip `enable_email_notifications` on after writing
                the creds. Defaults to True.

    Writes to site_config (via `frappe.installer.update_site_config`), NOT to
    any file in the repo. The script itself never keeps the secrets in
    memory beyond this call and never logs them.

    Returns:
        Envelope with the non-sensitive values and an `is_enabled` flag.
    """
    frappe.only_for(["Administrator", "System Manager"])

    if not path or not os.path.exists(path):
        frappe.throw(f"Credentials file not found: {path}")
    if not sender or "@" not in sender:
        frappe.throw("A valid sender email is required (e.g., admin@naarni.com).")

    with open(path, "r", encoding="utf-8") as f:
        raw = f.read()

    creds = _parse_credentials_text(raw)
    missing = [k for k, v in creds.items() if not v]
    if missing:
        frappe.throw(
            "Could not locate the following fields in the credentials file: "
            + ", ".join(missing)
        )

    from frappe.installer import update_site_config
    update_site_config("m365_tenant_id", creds["tenant_id"])
    update_site_config("m365_client_id", creds["client_id"])
    update_site_config("m365_client_secret", creds["client_secret"])
    update_site_config("m365_sender_email", sender.strip())
    if enable:
        update_site_config("enable_email_notifications", 1)

    # Reload frappe.conf so the same process sees the new values immediately.
    frappe.conf.update({
        "m365_tenant_id": creds["tenant_id"],
        "m365_client_id": creds["client_id"],
        "m365_client_secret": creds["client_secret"],
        "m365_sender_email": sender.strip(),
        "enable_email_notifications": 1 if enable else 0,
    })

    # Drop any cached access token so the next send uses the new creds.
    try:
        frappe.cache().delete_value("vm_m365_access_token")
    except Exception:
        pass

    from vehicle_maintenance.fleet_service import m365_client

    return {
        "success": True,
        "data": {
            "tenant_id": creds["tenant_id"],
            "client_id": creds["client_id"],
            "sender_email": sender.strip(),
            "client_secret_set": True,
            "is_enabled": m365_client.is_enabled(),
        },
        "message": "M365 email pipeline configured.",
    }


@frappe.whitelist()
def disable_email_notifications() -> dict:
    """Kill switch — flips `enable_email_notifications` off. The credentials
    stay in site_config so re-enabling is a single-line flip later.
    """
    frappe.only_for(["Administrator", "System Manager"])
    from frappe.installer import update_site_config
    update_site_config("enable_email_notifications", 0)
    frappe.conf["enable_email_notifications"] = 0
    return {"success": True, "message": "Email notifications disabled."}


@frappe.whitelist()
def email_status() -> dict:
    """Non-sensitive status check — reports which M365 config keys are present,
    without revealing the secret.
    """
    frappe.only_for(["Administrator", "System Manager"])
    conf = frappe.conf or {}
    return {
        "success": True,
        "data": {
            "enabled": bool(conf.get("enable_email_notifications")),
            "tenant_id_set": bool(conf.get("m365_tenant_id")),
            "client_id_set": bool(conf.get("m365_client_id")),
            "client_secret_set": bool(conf.get("m365_client_secret")),
            "sender_email": conf.get("m365_sender_email") or None,
        },
    }
