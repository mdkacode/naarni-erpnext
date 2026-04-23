"""Serve the Vue 3 SPA for all /service-portal/* routes.

Frappe's www resolver calls get_context() to build the page.

We inject `boot.csrf_token` (and a few session hints) into the page so the SPA
can attach the CSRF header to its first POST. Without this, a hard refresh
while already logged in produces:

    POST /api/method/frappe.auth.get_logged_user  → 400 (CSRF Token Missing)

…because frappe-ui's `call()` always POSTs, and Frappe enforces CSRF on POSTs
from authenticated sessions. The token is available on the server side via
`frappe.sessions.get_csrf_token()` and is consumed client-side by frappe-ui's
`frappeRequest`, which reads `window.csrf_token` automatically.
"""

import frappe
import frappe.sessions

no_cache = 1


def get_context(context):
    context.no_cache = 1

    boot: dict = {}
    user = frappe.session.user if hasattr(frappe, "session") else None
    if user and user != "Guest":
        boot["csrf_token"] = frappe.sessions.get_csrf_token()
        boot["user"] = user
        boot["sid"] = frappe.session.sid
    context.boot = boot
