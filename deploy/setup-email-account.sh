#!/usr/bin/env bash
# Creates/updates a default outgoing Email Account in Frappe using Brevo SMTP.
# Usage:
#   deploy/setup-email-account.sh '<smtp_login>' '<smtp_key>' '<sender_email>'
#
# Example:
#   deploy/setup-email-account.sh 'mayank.dwivedi@naarni.com' 'xsmtpsib-...' 'mayank.dwivedi@naarni.com'
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "Usage: $0 <smtp_login> <smtp_key> <sender_email>" >&2
  exit 1
fi

SMTP_LOGIN="$1"
SMTP_KEY="$2"
SENDER_EMAIL="$3"
SITE="service.naarni.com"
VM="frappe@20.219.136.19"

# Base64 pass creds through env to avoid shell-escaping issues with special chars
ssh "$VM" \
  SMTP_LOGIN_B64="$(printf %s "$SMTP_LOGIN" | base64)" \
  SMTP_KEY_B64="$(printf %s "$SMTP_KEY" | base64)" \
  SENDER_B64="$(printf %s "$SENDER_EMAIL" | base64)" \
  SITE="$SITE" \
  bash <<'EOF'
set -e
export PATH="$HOME/.local/bin:$PATH"
cd ~/frappe-bench

# Decode inside Python to avoid shell quoting issues
bench --site "$SITE" execute frappe.utils.execute_in_shell --args '["true"]' >/dev/null 2>&1 || true

cd ~/frappe-bench/sites
python_code=$(cat <<PY
import base64, frappe
frappe.init(site="$SITE", sites_path=".")
frappe.connect()
smtp_login = base64.b64decode("$SMTP_LOGIN_B64").decode()
smtp_key   = base64.b64decode("$SMTP_KEY_B64").decode()
sender     = base64.b64decode("$SENDER_B64").decode()

name = "Brevo Outgoing"
if frappe.db.exists("Email Account", name):
    doc = frappe.get_doc("Email Account", name)
else:
    doc = frappe.new_doc("Email Account")
    doc.email_account_name = name

doc.email_id = sender
doc.enable_outgoing = 1
doc.default_outgoing = 1
doc.smtp_server = "smtp-relay.brevo.com"
doc.smtp_port = 587
doc.use_tls = 1
doc.login_id_is_different = 1
doc.login_id = smtp_login
doc.password = smtp_key
doc.enable_incoming = 0
doc.always_use_account_email_id_as_sender = 1
doc.flags.ignore_validate = True
doc.save(ignore_permissions=True)
frappe.db.commit()

# Verify SMTP works by actually sending via Frappe's transport
from frappe.email.smtp import SMTPServer
srv = doc.get_smtp_server()
srv.session  # opens+authenticates
print("OK: default outgoing Email Account configured & SMTP auth verified ->", sender)
PY
)

~/frappe-bench/env/bin/python -c "$python_code"
EOF

echo "Done. Test with: bench --site $SITE execute frappe.core.doctype.communication.email.make --kwargs \"{'recipients': ['you@example.com'], 'subject': 'Test', 'content': 'Hello'}\""
