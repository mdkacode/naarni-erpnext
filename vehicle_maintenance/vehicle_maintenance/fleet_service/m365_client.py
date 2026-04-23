"""Microsoft 365 / Microsoft Graph email sender.

Client-credentials OAuth2 flow + `POST /users/{sender}/sendMail`. No delegated
user login required, and no SMTP AUTH dependency (which Microsoft disabled by
default in 2022). Tokens live ~60 min; we cache in Redis so we don't pay the
token-endpoint round-trip on every email.

Required site_config entries (loaded here, NOT checked into source):
    m365_tenant_id
    m365_client_id
    m365_client_secret
    m365_sender_email        — mailbox the notifications are sent from
    enable_email_notifications — "1" / "0" global kill switch

Grant the app registration `Mail.Send` application permission + admin consent.
Optionally scope to a single mailbox using an ApplicationAccessPolicy.
"""

from __future__ import annotations

import json
from typing import Any, Iterable

import frappe
import requests

GRAPH_API_BASE = "https://graph.microsoft.com/v1.0"
TOKEN_CACHE_KEY = "vm_m365_access_token"
TOKEN_TTL_SECONDS = 50 * 60  # Refresh 10 min before expiry for safety.


class M365ConfigError(RuntimeError):
    """Raised when the required M365 site_config keys are missing."""


def _require_config() -> dict[str, str]:
    """Pull M365 credentials from site_config. Raises if incomplete.

    Returns a dict with tenant_id, client_id, client_secret, sender_email.
    """
    conf = frappe.conf or {}
    required = ("m365_tenant_id", "m365_client_id", "m365_client_secret",
                "m365_sender_email")
    missing = [k for k in required if not conf.get(k)]
    if missing:
        raise M365ConfigError(
            f"M365 email is enabled but site_config is missing: {', '.join(missing)}"
        )
    return {k.replace("m365_", ""): conf.get(k) for k in required}


def is_enabled() -> bool:
    """True iff `enable_email_notifications` is truthy in site_config AND all
    M365 credentials are present. A partial config is treated as disabled so a
    half-provisioned site doesn't leak tracebacks into notification paths.
    """
    if not frappe.conf.get("enable_email_notifications"):
        return False
    try:
        _require_config()
        return True
    except M365ConfigError:
        return False


def get_access_token(force_refresh: bool = False) -> str:
    """Acquire an app-only access token via client_credentials.

    Cached in Redis (`frappe.cache()`) for TOKEN_TTL_SECONDS so bulk email
    fan-outs don't each hit the token endpoint.
    """
    cache = frappe.cache()
    if not force_refresh:
        cached = cache.get_value(TOKEN_CACHE_KEY)
        if cached:
            return cached.decode() if isinstance(cached, bytes) else cached

    cfg = _require_config()
    token_url = f"https://login.microsoftonline.com/{cfg['tenant_id']}/oauth2/v2.0/token"
    resp = requests.post(
        token_url,
        data={
            "client_id": cfg["client_id"],
            "client_secret": cfg["client_secret"],
            "scope": "https://graph.microsoft.com/.default",
            "grant_type": "client_credentials",
        },
        timeout=15,
    )
    if resp.status_code != 200:
        frappe.log_error(
            title="M365 token acquisition failed",
            message=f"status={resp.status_code}\nbody={resp.text}",
        )
        resp.raise_for_status()

    data = resp.json()
    token = data.get("access_token")
    if not token:
        raise M365ConfigError(
            f"Token response missing access_token: {json.dumps(data)[:500]}"
        )
    cache.set_value(TOKEN_CACHE_KEY, token, expires_in_sec=TOKEN_TTL_SECONDS)
    return token


def send_mail(
    *,
    to: Iterable[str],
    subject: str,
    html_body: str,
    text_body: str | None = None,
    cc: Iterable[str] | None = None,
    bcc: Iterable[str] | None = None,
    save_to_sent_items: bool = False,
) -> dict[str, Any]:
    """Send an email via Microsoft Graph `users/{sender}/sendMail`.

    Args:
        to:      Iterable of recipient email addresses.
        subject: Plain text subject line.
        html_body: HTML content. Should be branded via `render_branded_email`.
        text_body: Optional plain-text fallback stored alongside (Graph picks
                   the format by `contentType`; we only set HTML in the request
                   since modern clients render the HTML part).
        cc / bcc: Optional recipients.
        save_to_sent_items: Whether the sending mailbox keeps a copy. Default
                   False for automated mail to avoid inbox clutter.

    Returns:
        {"sent": true, "recipients": [...]} on success.

    Raises:
        requests.HTTPError on non-2xx from Graph (the caller is expected to
        catch + log_error so notification flows never break).
    """
    cfg = _require_config()
    token = get_access_token()

    to_recipients = _as_recipient_list(to)
    cc_recipients = _as_recipient_list(cc)
    bcc_recipients = _as_recipient_list(bcc)

    if not to_recipients:
        return {"sent": False, "reason": "no recipients"}

    message: dict[str, Any] = {
        "subject": subject[:250],
        "body": {"contentType": "HTML", "content": html_body},
        "toRecipients": to_recipients,
    }
    if cc_recipients:
        message["ccRecipients"] = cc_recipients
    if bcc_recipients:
        message["bccRecipients"] = bcc_recipients

    payload = {"message": message, "saveToSentItems": bool(save_to_sent_items)}

    endpoint = f"{GRAPH_API_BASE}/users/{cfg['sender_email']}/sendMail"
    resp = requests.post(
        endpoint,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=20,
    )

    # 401 = token likely expired mid-use; retry once with a fresh token.
    if resp.status_code == 401:
        token = get_access_token(force_refresh=True)
        resp = requests.post(
            endpoint,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            json=payload,
            timeout=20,
        )

    if resp.status_code >= 300:
        frappe.log_error(
            title=f"M365 sendMail failed ({resp.status_code})",
            message=f"to={[r['emailAddress']['address'] for r in to_recipients]}\n"
                    f"subject={subject}\nresponse={resp.text[:1000]}",
        )
        resp.raise_for_status()

    return {
        "sent": True,
        "recipients": [r["emailAddress"]["address"] for r in to_recipients],
    }


def _as_recipient_list(addresses: Iterable[str] | None) -> list[dict]:
    if not addresses:
        return []
    seen: set[str] = set()
    result: list[dict] = []
    for addr in addresses:
        if not addr:
            continue
        addr = addr.strip()
        if not addr or "@" not in addr:
            continue
        if addr.lower() in seen:
            continue
        seen.add(addr.lower())
        result.append({"emailAddress": {"address": addr}})
    return result


# ───────────────────────────────────────────────────────────
# Whitelisted helpers — for ops / smoke-testing from bench execute
# ───────────────────────────────────────────────────────────

@frappe.whitelist()
def diagnose_mailbox() -> dict:
    """Probe `/mailboxSettings` to distinguish license vs RBAC failure modes.

    Unlike `/users/{id}` (needs User.Read.All) this endpoint responds with
    distinct error codes depending on the underlying cause:

      • 200 OK                       → mailbox exists + licensed + accessible
      • 404 + ErrorNonExistentMailbox → mailbox doesn't exist
      • 403 + MailboxNotEnabledForRESTAPI → mailbox unlicensed
      • 403 + ErrorAccessDenied      → RBAC/ApplicationAccessPolicy blocking
      • 403 + Authorization_*        → Missing Mail.Read scope
    """
    frappe.only_for(["Administrator", "System Manager"])
    cfg = _require_config()
    token = get_access_token()
    resp = requests.get(
        f"{GRAPH_API_BASE}/users/{cfg['sender_email']}/mailboxSettings",
        headers={"Authorization": f"Bearer {token}"},
        timeout=15,
    )
    status = resp.status_code
    body = resp.text.replace(token, "<redacted>") if token else resp.text
    err = ""
    try:
        err = resp.json().get("error", {}).get("code", "") or ""
    except Exception:
        pass

    interpretation = ""
    if status == 200:
        interpretation = "mailbox exists and is licensed — problem is the send-side RBAC/policy"
    elif err == "ErrorNonExistentMailbox" or status == 404:
        interpretation = f"mailbox {cfg['sender_email']} does not exist in tenant"
    elif err == "MailboxNotEnabledForRESTAPI":
        interpretation = "mailbox exists but has NO Exchange Online license — assign one in admin.microsoft.com"
    elif err == "ErrorAccessDenied":
        interpretation = (
            "mailbox is accessible at the Exchange layer but Graph is blocked "
            "by a tenant-wide RBAC-for-Applications policy — grant the app "
            "the 'Application Mail.Send' role in Exchange Admin Center"
        )
    elif status == 403:
        interpretation = (
            "Mail.Read scope missing — this probe uses mailboxSettings which "
            "requires Mail.Read or Mail.ReadBasic. This doesn't affect send; "
            "it only means we can't diagnose from here. Proceed with the RBAC "
            "fix and re-run verify_connection."
        )
    else:
        interpretation = f"unexpected response {status}"

    return {
        "success": status == 200,
        "data": {
            "sender": cfg["sender_email"],
            "status_code": status,
            "graph_error_code": err,
            "interpretation": interpretation,
        },
        "message": interpretation,
        "raw": body[:400],
    }


@frappe.whitelist()
def verify_connection() -> dict:
    """Acquire a token + do a Mail.Send-only sanity probe.

    Avoids `User.Read.All` (which requires a broader, less necessary admin
    consent). Instead, we probe `POST /users/{sender}/sendMail` with an
    intentionally invalid empty payload — the response code tells us which
    permission layer failed without sending any real email:

      • 401/403 before the body validation  → Mail.Send NOT granted
      • 400 BadRequest on empty payload     → Mail.Send IS granted (we got
                                               past auth and hit request
                                               schema validation)
      • 404 on mailbox                      → Mailbox doesn't exist
      • 200/202                             → Shouldn't happen; empty payload
                                               is invalid — treat as OK anyway

    Returns a summary with no secrets.
    """
    frappe.only_for(["Administrator", "System Manager"])
    result: dict = {
        "token_acquired": False,
        "mail_send_granted": None,
        "mailbox_reachable": None,
        "sender": (frappe.conf or {}).get("m365_sender_email"),
    }
    try:
        token = get_access_token(force_refresh=True)
        result["token_acquired"] = bool(token and len(token) > 20)
    except Exception as e:
        return {
            "success": False,
            "data": result,
            "message": f"Token acquisition failed: {type(e).__name__}: {e}",
        }

    cfg = _require_config()
    endpoint = f"{GRAPH_API_BASE}/users/{cfg['sender_email']}/sendMail"

    # Empty payload — we're probing auth, not actually sending.
    try:
        resp = requests.post(
            endpoint,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            json={},
            timeout=15,
        )
    except Exception as e:
        return {
            "success": False,
            "data": result,
            "message": f"Mail.Send probe network error: {type(e).__name__}: {e}",
        }

    status = resp.status_code
    # Strip any token from upstream error bodies defensively.
    body = resp.text.replace(token, "<redacted>") if token else resp.text
    err_code = ""
    try:
        err_code = resp.json().get("error", {}).get("code", "") or ""
    except Exception:
        pass

    if status == 400 or "InvalidRequest" in err_code or "BadRequest" in err_code:
        # Graph parsed auth + perms, then rejected our empty body. We still
        # need to verify Exchange-side access by POST'ing a real (minimal)
        # payload addressed to the sender itself — that probes any
        # ApplicationAccessPolicy / RBAC-for-Applications restrictions
        # without sending to an external recipient.
        result["mail_send_granted"] = True
        minimal = {
            "message": {
                "subject": "[NaArNi] Internal connectivity probe",
                "body": {"contentType": "Text", "content": "Probe."},
                "toRecipients": [
                    {"emailAddress": {"address": cfg["sender_email"]}}
                ],
            },
            "saveToSentItems": False,
        }
        probe = requests.post(
            endpoint,
            headers={"Authorization": f"Bearer {token}",
                     "Content-Type": "application/json"},
            json=minimal,
            timeout=15,
        )
        p_status = probe.status_code
        p_body = probe.text.replace(token, "<redacted>") if token else probe.text
        p_err = ""
        try:
            p_err = probe.json().get("error", {}).get("code", "") or ""
        except Exception:
            pass
        if p_status in (200, 202):
            result["mailbox_reachable"] = True
            return {
                "success": True,
                "data": result,
                "message": (
                    "M365 connection OK. Mail.Send + Exchange access both "
                    "verified (a probe email to the sender was accepted)."
                ),
            }
        if p_err == "ErrorAccessDenied" or p_status == 403:
            result["mailbox_reachable"] = False
            result["exchange_blocked"] = True
            hint = (
                "Token + Mail.Send are fine, but Exchange Online is blocking "
                "the app from sending AS this mailbox. Most common causes:\n"
                "  (1) An ApplicationAccessPolicy is restricting the app to a "
                "scope group that does NOT include " + cfg["sender_email"] + ". "
                "Fix: add the mailbox, or drop the policy:\n"
                "      New-ApplicationAccessPolicy -AppId " + cfg["client_id"] + " "
                "-PolicyScopeGroupId <group-with-" + cfg["sender_email"] + "> "
                "-AccessRight RestrictAccess\n"
                "  (2) RBAC for Applications (newer model) requires an "
                "explicit role assignment. Grant the app the 'Application "
                "Mail.Send' role scoped to the sender mailbox via Exchange "
                "Admin Center > Roles > Admin roles.\n"
                "  (3) " + cfg["sender_email"] + " has no Exchange Online "
                "license — assign one in admin.microsoft.com."
            )
            return {
                "success": False,
                "data": result,
                "message": f"Exchange denied ({p_status} {p_err}).\n{hint}\nRaw: {p_body[:400]}",
            }
        return {
            "success": False,
            "data": result,
            "message": f"Unexpected {p_status} during mailbox probe: {p_body[:400]}",
        }

    if status in (401, 403):
        result["mail_send_granted"] = False
        hint = (
            "Azure app is missing Mail.Send application permission (or admin "
            "consent hasn't been granted). Grant Microsoft Graph > Mail.Send "
            "(Application) and click 'Grant admin consent'."
        )
        return {
            "success": False,
            "data": result,
            "message": f"Auth denied ({status} {err_code}). {hint}\nRaw: {body[:300]}",
        }

    if status == 404 or err_code in ("ResourceNotFound", "ErrorNonExistentMailbox"):
        result["mailbox_reachable"] = False
        return {
            "success": False,
            "data": result,
            "message": (
                f"Mailbox '{cfg['sender_email']}' not found in tenant. "
                f"Either the address is wrong or the mailbox isn't provisioned. "
                f"Raw: {body[:300]}"
            ),
        }

    # Anything else — unknown response. Treat as partial so ops can eyeball it.
    return {
        "success": False,
        "data": result,
        "message": f"Unexpected {status} from Graph: {body[:400]}",
    }


@frappe.whitelist()
def send_test_email(recipient: str) -> dict:
    """Send a one-off branded test email. For smoke-tests from `bench execute`.

        bench --site <site> execute \\
            vehicle_maintenance.fleet_service.m365_client.send_test_email \\
            --kwargs '{"recipient": "ops@example.com"}'
    """
    frappe.only_for(["Administrator", "System Manager"])

    from vehicle_maintenance.fleet_service.email_templates import render_branded_email
    html = render_branded_email(
        heading="Test email from NaArNi",
        body_html=(
            "<p>This is a connectivity test from the Fleet Service email pipeline.</p>"
            "<p>If you received it, Microsoft Graph + the app registration + mailbox "
            "permissions are all set up correctly.</p>"
        ),
        preheader="NaArNi email pipeline test",
        priority="Low",
    )
    result = send_mail(
        to=[recipient],
        subject="[NaArNi] Email pipeline test",
        html_body=html,
    )
    return {"success": True, "data": result}
