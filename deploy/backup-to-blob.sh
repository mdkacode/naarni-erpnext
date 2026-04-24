#!/usr/bin/env bash
# Daily Frappe site backup → Azure Blob Storage with 7-day retention.
# Install: sudo cp to /usr/local/bin/backup-to-blob.sh; cron runs it as frappe user.
set -euo pipefail

source /home/frappe/secrets.env

SITE_NAME="${SITE_NAME:-service.naarni.com}"
BENCH_DIR="/home/frappe/frappe-bench"
CONTAINER="backups"
DATE="$(date -u +%Y%m%d-%H%M%S)"
LOG="/home/frappe/backup.log"

exec >> "$LOG" 2>&1
echo "=== $(date -u) Starting backup ==="

cd "$BENCH_DIR"
export PATH="/home/frappe/.local/bin:$PATH"

# Create SQL + files backup (bench handles compression)
bench --site "$SITE_NAME" backup --with-files --compress

# Find newest backup files in site's private/backups
BACKUP_DIR="$BENCH_DIR/sites/$SITE_NAME/private/backups"
for f in $(ls -t "$BACKUP_DIR" | head -4); do
  echo "Uploading $f"
  az storage blob upload \
    --account-name "$STORAGE_ACCOUNT" --account-key "$STORAGE_KEY" \
    --container-name "$CONTAINER" \
    --name "$DATE/$f" \
    --file "$BACKUP_DIR/$f" \
    --overwrite --only-show-errors
done

# Delete local backups older than 2 days (keep small local cache)
find "$BACKUP_DIR" -type f -mtime +2 -delete

# Delete blobs older than 7 days (7-day retention)
CUTOFF=$(date -u -d '7 days ago' +%Y-%m-%dT%H:%M:%SZ)
az storage blob list \
  --account-name "$STORAGE_ACCOUNT" --account-key "$STORAGE_KEY" \
  --container-name "$CONTAINER" \
  --query "[?properties.lastModified < '$CUTOFF'].name" -o tsv | while read -r blob; do
    [ -n "$blob" ] && az storage blob delete \
      --account-name "$STORAGE_ACCOUNT" --account-key "$STORAGE_KEY" \
      --container-name "$CONTAINER" --name "$blob" --only-show-errors
done

echo "=== $(date -u) Backup complete ==="
