#!/usr/bin/env bash
# Resume provisioning from MySQL step after provider registration fix.
set -euo pipefail

source "$(dirname "$0")/secrets.env"

SUBSCRIPTION_ID="d091bc43-1a97-4a25-b271-a9eb6616bb82"
LOCATION="centralindia"
RG="vehicle-service-prod"
PROJECT="vehicleservice"
VM_NAME="${PROJECT}-vm"
DB_SKU="Standard_B1ms"
DB_TIER="Burstable"
DB_VERSION="8.0.21"
DB_STORAGE_GB=32
STORAGE_CONTAINER_FILES="files"
STORAGE_CONTAINER_BACKUPS="backups"

az account set --subscription "$SUBSCRIPTION_ID"

echo "==> Creating MySQL Flexible Server"
az mysql flexible-server create \
  --resource-group "$RG" --name "$DB_SERVER_NAME" \
  --location "$LOCATION" \
  --admin-user "$DB_ADMIN_USER" --admin-password "$DB_ADMIN_PASS" \
  --sku-name "$DB_SKU" --tier "$DB_TIER" \
  --version "$DB_VERSION" \
  --storage-size "$DB_STORAGE_GB" \
  --public-access "$VM_PUBLIC_IP" \
  --backup-retention 7 \
  --yes \
  -o table

echo "==> Creating database $DB_NAME"
az mysql flexible-server db create \
  --resource-group "$RG" --server-name "$DB_SERVER_NAME" \
  --database-name "$DB_NAME" -o table

echo "==> Creating storage account"
az storage account create \
  --resource-group "$RG" --name "$STORAGE_ACCOUNT" \
  --location "$LOCATION" --sku Standard_LRS --kind StorageV2 \
  --access-tier Hot --allow-blob-public-access false -o table

STORAGE_KEY=$(az storage account keys list -g "$RG" -n "$STORAGE_ACCOUNT" --query '[0].value' -o tsv)
for c in "$STORAGE_CONTAINER_FILES" "$STORAGE_CONTAINER_BACKUPS"; do
  az storage container create \
    --name "$c" --account-name "$STORAGE_ACCOUNT" --account-key "$STORAGE_KEY" -o none
done
echo "STORAGE_KEY=$STORAGE_KEY" >> "$(dirname "$0")/secrets.env"

echo "==> Creating Recovery Services Vault"
VAULT_NAME="${PROJECT}-backup-vault"
az backup vault create --resource-group "$RG" --name "$VAULT_NAME" --location "$LOCATION" -o table
az backup protection enable-for-vm \
  --resource-group "$RG" --vault-name "$VAULT_NAME" \
  --vm "$VM_NAME" --policy-name DefaultPolicy -o table

echo
echo "=========================================="
echo "PROVISIONING COMPLETE"
echo "VM IP:       $VM_PUBLIC_IP"
echo "MySQL Host:  ${DB_SERVER_NAME}.mysql.database.azure.com"
echo "Storage:     $STORAGE_ACCOUNT"
echo "=========================================="
