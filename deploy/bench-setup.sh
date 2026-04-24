#!/usr/bin/env bash
# Run on the VM as the 'frappe' user, after cloud-init has completed.
# Requires: deploy/secrets.env scp'd to ~/secrets.env on the VM.
set -euo pipefail

SECRETS="${HOME}/secrets.env"
test -f "$SECRETS" || { echo "Upload secrets.env to $SECRETS first"; exit 1; }
# shellcheck disable=SC1090
source "$SECRETS"

SITE_NAME="${SITE_NAME:-service.naarni.com}"
FRAPPE_BRANCH="version-15"
ERPNEXT_REPO="https://github.com/frappe/erpnext"
ERPNEXT_BRANCH="version-15"
VEHICLE_REPO="https://github.com/mdkacode/naarni-erpnext.git"
VEHICLE_BRANCH="develop"
APP_NAME="vehicle_maintenance"  # custom app lives at VEHICLE_REPO root as subdir

export PATH="$HOME/.local/bin:$PATH"

cd "$HOME"

# Init bench
if [ ! -d "frappe-bench" ]; then
  bench init frappe-bench --frappe-branch "$FRAPPE_BRANCH" --python python3.11
fi
cd frappe-bench

# Install ERPNext (the fork contains both erpnext + vehicle_maintenance)
if [ ! -d "apps/erpnext" ]; then
  bench get-app --branch "$ERPNEXT_BRANCH" erpnext "$ERPNEXT_REPO"
fi

# Extract vehicle_maintenance subdir as its own bench app
if [ ! -d "apps/$APP_NAME" ]; then
  TMPDIR=$(mktemp -d)
  git clone --depth 1 --branch "$VEHICLE_BRANCH" "$VEHICLE_REPO" "$TMPDIR/repo"
  cp -r "$TMPDIR/repo/$APP_NAME" "apps/$APP_NAME"
  # Initialize git so bench recognizes it
  (cd "apps/$APP_NAME" && git init -q && git add -A && git commit -qm "initial" || true)
  ./env/bin/pip install -e "apps/$APP_NAME"
  # Ensure apps.txt ends with a newline before appending, otherwise the new
  # app name gets concatenated onto the previous line (e.g. "erpnextvehicle_maintenance").
  [ -s sites/apps.txt ] && [ "$(tail -c1 sites/apps.txt)" != "" ] && echo >> sites/apps.txt
  echo "$APP_NAME" >> sites/apps.txt
  rm -rf "$TMPDIR"
fi

# Create site against local MariaDB (data on attached Azure managed disk).
DB_HOST="localhost"
if ! bench --site "$SITE_NAME" list-apps >/dev/null 2>&1; then
  bench new-site "$SITE_NAME" \
    --db-host "$DB_HOST" \
    --db-port 3306 \
    --db-name "$DB_NAME" \
    --db-root-username root \
    --db-root-password "$DB_ADMIN_PASS" \
    --admin-password "$DB_ADMIN_PASS" \
    --no-mariadb-socket \
    --force
fi

bench --site "$SITE_NAME" install-app erpnext
bench --site "$SITE_NAME" install-app "$APP_NAME"
bench use "$SITE_NAME"

# Production setup (nginx + supervisor)
sudo bench setup production frappe --yes
sudo bench setup nginx --yes
sudo systemctl reload nginx

# SSL (requires DNS A record pointing to this VM)
# sudo -H bench setup lets-encrypt "$SITE_NAME" --custom-domain

echo "==> Bench setup complete. Site: https://$SITE_NAME"
