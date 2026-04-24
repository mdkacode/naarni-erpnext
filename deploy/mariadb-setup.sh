#!/usr/bin/env bash
# Install MariaDB 10.6 on the VM, with its data dir on a separately attached
# Azure managed data disk (mounted at /var/lib/mysql). Run as the `frappe`
# user (it uses sudo internally).
#
# Prerequisite: a data disk has been attached to the VM. This script will
# auto-detect it as the first unformatted block device.
set -euo pipefail

SECRETS="${HOME}/secrets.env"
test -f "$SECRETS" || { echo "secrets.env missing at $SECRETS"; exit 1; }
# shellcheck disable=SC1090
source "$SECRETS"

DB_NAME="${DB_NAME:-vehicleservice}"
DB_USER="${DB_ADMIN_USER:-frappeadmin}"
DB_PASS="${DB_ADMIN_PASS:?DB_ADMIN_PASS missing}"

echo "==> Detecting the data disk"
# The OS disk is /dev/sda, ephemeral scratch is /dev/sdb on Azure, so the
# attached managed data disk should be /dev/sdc. Confirm it's unformatted.
DATA_DEV=""
for dev in /dev/sdc /dev/sdd; do
  if [ -b "$dev" ] && ! sudo blkid "$dev" >/dev/null 2>&1; then
    DATA_DEV="$dev"
    break
  fi
done
if [ -z "$DATA_DEV" ]; then
  echo "Could not find an unformatted block device. Current layout:"
  lsblk
  echo
  echo "If the disk is already formatted and mounted, skip this script."
  exit 1
fi
echo "   Using $DATA_DEV"

echo "==> Formatting $DATA_DEV as ext4"
sudo mkfs.ext4 -F "$DATA_DEV"

echo "==> Installing MariaDB server"
sudo DEBIAN_FRONTEND=noninteractive apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y mariadb-server mariadb-client

echo "==> Stopping MariaDB to relocate data dir"
sudo systemctl stop mariadb

echo "==> Copying existing /var/lib/mysql to the new disk"
sudo mkdir -p /mnt/dbdisk
sudo mount "$DATA_DEV" /mnt/dbdisk
sudo rsync -aHAX /var/lib/mysql/ /mnt/dbdisk/
sudo umount /mnt/dbdisk

echo "==> Mounting $DATA_DEV at /var/lib/mysql"
UUID=$(sudo blkid -s UUID -o value "$DATA_DEV")
# Replace any existing entry for /var/lib/mysql, then append ours.
sudo sed -i '\|[[:space:]]/var/lib/mysql[[:space:]]|d' /etc/fstab
echo "UUID=$UUID /var/lib/mysql ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab
sudo mount -a
sudo chown -R mysql:mysql /var/lib/mysql

echo "==> Starting MariaDB"
sudo systemctl enable --now mariadb

echo "==> Verifying MariaDB version (Frappe v15 requires >= 10.6)"
sudo mysql -e "SELECT VERSION();"

echo "==> Creating database and Frappe admin user"
sudo mysql <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON *.* TO '${DB_USER}'@'localhost' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL

echo "==> Applying Frappe-recommended server settings"
CNF=/etc/mysql/mariadb.conf.d/99-frappe.cnf
sudo tee "$CNF" >/dev/null <<'EOF'
[mysqld]
character-set-client-handshake = FALSE
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci

[mysql]
default-character-set = utf8mb4
EOF
sudo systemctl restart mariadb

echo
echo "=========================================="
echo "MariaDB ready."
echo "Host:   localhost"
echo "Port:   3306"
echo "DB:     ${DB_NAME}"
echo "User:   ${DB_USER}"
echo "Data:   /var/lib/mysql (on $DATA_DEV)"
echo "=========================================="
