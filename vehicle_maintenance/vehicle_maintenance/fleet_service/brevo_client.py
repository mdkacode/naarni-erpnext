"""Brevo (formerly Sendinblue) transactional email client.

We use the **transactional** endpoint `/v3/smtp/email`, NOT `/v3/emailCampaigns`.
Campaigns are bulk-to-list sends with marketing semantics; our case is
event-driven per-recipient notifications (job card created, SLA breach,
approval request, etc.), which is precisely what the transactional API is for.

Docs: https://developers.brevo.com/reference/sendtransacemail

Required site_config entries — populated via setup_email.configure_brevo.
Never write these to any source file in the repo:
    brevo_api_key             — "xkeysib-..." API key from Brevo dashboard
    brevo_sender_email        — must be a verified sender in Brevo
    brevo_sender_name         — display name ("NaArNi Fleet Service")
    enable_email_notifications — global on/off flag (reused from the M365 era)
"""

from __future__ import annotations

from typing import Any, Iterable

import frappe
import requests

BREVO_SEND_ENDPOINT = "https://api.brevo.com/v3/smtp/email"
BREVO_ACCOUNT_ENDPOINT = "https://api.brevo.com/v3/account"


class BrevoConfigError(RuntimeError):
    """Raised when required Brevo keys are missing or malformed."""


def _require_config() -> dict[str, str]:
    """Pull Brevo config from site_config. Raises if incomplete."""
    conf = frappe.conf or {}
    required = ("brevo_api_key", "brevo_sender_email", "brevo_sender_name")
    missing = [k for k in required if not conf.get(k)]
    if missing:
        raise BrevoConfigError(
            f"Brevo is enabled but site_config is missing: {', '.join(missing)}"
        )
    return {
        "api_key": conf.get("brevo_api_key"),
        "sender_email": conf.get("brevo_sender_email"),
        "sender_name": conf.get("brevo_sender_name"),
    }


def is_enabled() -> bool:
    """True iff `enable_email_notifications` is on AND full Brevo config is set.

    A partial config is treated as disabled so a half-provisioned site doesn't
    leak tracebacks into the notification paths.
    """
    if not frappe.conf.get("enable_email_notifications"):
        return False
    try:
        _require_config()
        return True
    except BrevoConfigError:
        return False


def send_mail(
    *,
    to: Iterable[str | dict],
    subject: str,
    html_body: str,
    text_body: str | None = None,
    cc: Iterable[str | dict] | None = None,
    bcc: Iterable[str | dict] | None = None,
    reply_to: str | None = None,
    tags: Iterable[str] | None = None,
) -> dict[str, Any]:
    """Send a transactional email via Brevo.

    Args:
        to:         Recipients. Each entry may be a bare email string or a
                    dict `{"email": "...", "name": "..."}`.
        subject:    Subject line.
        html_body:  HTML content — use `render_branded_email()`.
        text_body:  Optional plain-text fallback.
        cc / bcc:   Optional — same shape as `to`.
        reply_to:   Optional reply-to address.
        tags:       Optional list of message tags for Brevo analytics.

    Returns:
        {"sent": true, "message_id": "<brevo-id>", "recipients": [...]}

    Raises:
        requests.HTTPError on non-2xx — caller is expected to catch + log so
        notification flows never break.
    """
    cfg = _require_config()
    recipients = _normalise(to)
    if not recipients:
        return {"sent": False, "reason": "no recipients"}

    payload: dict[str, Any] = {
        "sender": {"email": cfg["sender_email"], "name": cfg["sender_name"]},
        "to": recipients,
        "subject": subject[:250],
        "htmlContent": html_body,
    }
    if text_body:
        payload["textContent"] = text_body
    if cc:
        payload["cc"] = _normalise(cc)
    if bcc:
        payload["bcc"] = _normalise(bcc)
    if reply_to:
        payload["replyTo"] = {"email": reply_to}
    if tags:
        payload["tags"] = [t for t in tags if t]

    resp = requests.post(
        BREVO_SEND_ENDPOINT,
        headers={
            "api-key": cfg["api_key"],
            "accept": "application/json",
            "content-type": "application/json",
        },
        json=payload,
        timeout=20,
    )

    if resp.status_code >= 300:
        # Redact the key defensively — it shouldn't appear in responses, but
        # this guards against any upstream echo bug.
        body = resp.text.replace(cfg["api_key"], "<redacted>") if cfg["api_key"] else resp.text
        frappe.log_error(
            title=f"Brevo sendMail failed ({resp.status_code})",
            message=f"recipients={[r['email'] for r in recipients]}\n"
                    f"subject={subject}\nresponse={body[:1000]}",
        )
        resp.raise_for_status()

    data = resp.json() if resp.content else {}
    return {
        "sent": True,
        "message_id": data.get("messageId"),
        "recipients": [r["email"] for r in recipients],
    }


def _normalise(addresses: Iterable[str | dict] | None) -> list[dict]:
    """Accept bare strings and {'email', 'name'} dicts; emit Brevo's shape."""
    if not addresses:
        return []
    out: list[dict] = []
    seen: set[str] = set()
    for a in addresses:
        if isinstance(a, dict):
            email = (a.get("email") or "").strip()
            name = (a.get("name") or "").strip() or None
        else:
            email = str(a or "").strip()
            name = None
        if not email or "@" not in email:
            continue
        if email.lower() in seen:
            continue
        seen.add(email.lower())
        entry: dict[str, str] = {"email": email}
        if name:
            entry["name"] = name
        out.append(entry)
    return out


# ───────────────────────────────────────────────────────────
# Ops helpers — whitelisted for `bench execute`
# ───────────────────────────────────────────────────────────

@frappe.whitelist()
def verify_connection() -> dict:
    """Hit `/v3/account` to validate the API key without sending mail.

    Returns sanitised account summary — plan name, company — never the key.
    """
    frappe.only_for(["Administrator", "System Manager"])
    result: dict[str, Any] = {
        "api_key_set": bool((frappe.conf or {}).get("brevo_api_key")),
        "sender": (frappe.conf or {}).get("brevo_sender_email"),
    }
    try:
        cfg = _require_config()
    except BrevoConfigError as e:
        return {"success": False, "data": result, "message": str(e)}

    try:
        resp = requests.get(
            BREVO_ACCOUNT_ENDPOINT,
            headers={"api-key": cfg["api_key"], "accept": "application/json"},
            timeout=15,
        )
    except Exception as e:
        return {
            "success": False,
            "data": result,
            "message": f"Network error reaching Brevo: {type(e).__name__}: {e}",
        }

    if resp.status_code == 401:
        return {
            "success": False,
            "data": result,
            "message": "Brevo rejected the API key (401). Regenerate it in "
                       "Brevo → SMTP & API → API Keys and re-run configure_brevo.",
        }
    if resp.status_code >= 300:
        body = resp.text.replace(cfg["api_key"], "<redacted>")
        return {
            "success": False,
            "data": result,
            "message": f"Brevo /account returned {resp.status_code}: {body[:400]}",
        }

    data = resp.json()
    result.update({
        "plan": [p.get("type") for p in data.get("plan") or []] or None,
        "company": (data.get("companyName") or "").strip() or None,
        "first_name": (data.get("firstName") or "").strip() or None,
        "email": data.get("email"),
    })
    return {"success": True, "data": result, "message": "Brevo connection OK."}


@frappe.whitelist()
def send_test_email(recipient: str) -> dict:
    """Send a one-off NaArNi-branded test email through Brevo."""
    frappe.only_for(["Administrator", "System Manager"])

    from vehicle_maintenance.fleet_service.email_templates import (
        render_branded_email,
        to_plain_text,
    )

    subject = "[NaArNi] Email pipeline test"
    body_text = (
        "This is a connectivity test from the Fleet Service email pipeline. "
        "If you received it, Brevo + the configured sender + the API key are "
        "all working."
    )
    html = render_branded_email(
        heading="Test email from NaArNi",
        body_html=f"<p style='margin:0 0 12px 0;'>{body_text}</p>",
        preheader="NaArNi email pipeline test",
        priority="Low",
    )
    result = send_mail(
        to=[recipient],
        subject=subject,
        html_body=html,
        text_body=to_plain_text("Test email from NaArNi", body_text),
        tags=["smoke-test"],
    )
    return {"success": True, "data": result}
