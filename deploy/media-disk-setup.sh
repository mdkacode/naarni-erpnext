#!/usr/bin/env bash
# Move Frappe's file storage onto the dedicated 128 GB media disk.
#
# Why: chat attachments are written via get_files_path() into
# sites/<site>/{public,private}/files, which live on the 32 GB OS disk shared
# with the OS and the bench itself. chat_upload.py permits 500 MB per file
# (DEFAULT_MAX_UPLOAD_BYTES), so roughly forty videos fill the OS disk and take
# the site down. This relocates both files directories onto their own disk.
#
# The disk is addressed by LUN, never by /dev/sdX. Device letters shift when a
# disk is attached or the VM is resized -- mariadb-setup.sh's /dev/sdc guess is
# exactly the assumption this script refuses to repeat.
#
# Run as the `frappe` user on the VM. Idempotent: safe to re-run.
set -euo pipefail

LUN_DEV="/dev/disk/azure/scsi1/lun1"
MOUNT="/opt/frappe-media"
BENCH="${HOME}/frappe-bench"

echo "==> Resolving the LUN 1 disk"
test -e "$LUN_DEV" || { echo "No disk at $LUN_DEV. Is the media disk attached?"; lsblk; exit 1; }
DEV=$(readlink -f "$LUN_DEV")
echo "    $LUN_DEV -> $DEV"

# Refuse to touch a disk that already holds a filesystem we did not create,
# so a re-run can never reformat live data.
if sudo blkid "$DEV" >/dev/null 2>&1; then
  echo "    Already formatted; skipping mkfs."
else
  echo "==> Formatting $DEV as ext4"
  sudo mkfs.ext4 -F -L frappe-media "$DEV"
fi

echo "==> Mounting at $MOUNT"
sudo mkdir -p "$MOUNT"
UUID=$(sudo blkid -s UUID -o value "$DEV")
sudo sed -i "\|[[:space:]]${MOUNT}[[:space:]]|d" /etc/fstab
echo "UUID=$UUID $MOUNT ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab >/dev/null
mountpoint -q "$MOUNT" || sudo mount "$MOUNT"

SITE=$(cat "${BENCH}/sites/currentsite.txt")
echo "==> Site: $SITE"

echo "==> Stopping bench so nothing writes mid-copy"
sudo supervisorctl stop all

for KIND in public private; do
  SRC="${BENCH}/sites/${SITE}/${KIND}/files"
  DST="${MOUNT}/${KIND}/files"
  echo "==> ${KIND}: $SRC -> $DST"
  sudo mkdir -p "$DST"
  sudo rsync -aHAX --info=stats2 "${SRC}/" "$DST/"

  # Bind-mount the new location over the original path. Transparent to both
  # Frappe and nginx -- no symlinks, so no disable_symlinks surprises.
  sudo sed -i "\|[[:space:]]${SRC}[[:space:]]|d" /etc/fstab
  echo "$DST $SRC none bind,nofail 0 0" | sudo tee -a /etc/fstab >/dev/null
  mountpoint -q "$SRC" || sudo mount --bind "$DST" "$SRC"
done

sudo chown -R frappe:frappe "$MOUNT"

echo "==> Restarting bench"
sudo supervisorctl start all

echo
echo "=========================================="
df -h "$MOUNT" /
echo
echo "Media now on its own 128 GB disk."
echo
echo "NOTE: the pre-move copies still sit on the OS disk *underneath* the bind"
echo "mounts, so they cost space until reclaimed. Verify uploads and downloads"
echo "work first, then reclaim with:"
echo
echo "  sudo umount ${BENCH}/sites/${SITE}/public/files"
echo "  sudo rm -rf ${BENCH}/sites/${SITE}/public/files/*"
echo "  sudo mount --bind ${MOUNT}/public/files ${BENCH}/sites/${SITE}/public/files"
echo "  (repeat for private)"
echo "=========================================="
