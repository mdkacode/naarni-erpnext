#!/usr/bin/env bash
# Azure provisioning for Vehicle Service (Frappe + managed MariaDB + Blob Storage)
# Review carefully before running. Each paid resource is flagged with [PAID].
set -euo pipefail

# ============================================================
# CONFIGURATION — edit before running
# ============================================================
SUBSCRIPTION_ID="d091bc43-1a97-4a25-b271-a9eb6616bb82"
LOCATION="centralindia"
RG="vehicle-service-prod"
PROJECT="vehicleservice"                    # used as prefix (no hyphens, lowercase)

# Networking
VNET_NAME="${PROJECT}-vnet"
SUBNET_NAME="${PROJECT}-subnet"
NSG_NAME="${PROJECT}-nsg"
PIP_NAME="${PROJECT}-pip"

# VM
VM_NAME="${PROJECT}-vm"
VM_SIZE="Standard_B2s"                      # 2 vCPU, 4GB — ~$30/mo [PAID]
VM_IMAGE="Ubuntu2204"
VM_ADMIN="azureuser"
SSH_PUBKEY_PATH="$HOME/.ssh/id_ed25519.pub"

# MariaDB Flexible Server  [PAID ~$25/mo]
DB_SERVER_NAME="${PROJECT}-mysql"
DB_SKU="Standard_B1ms"
DB_TIER="Burstable"
DB_VERSION="8.0.21"
DB_ADMIN_USER="frappeadmin"
DB_ADMIN_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 28)Aa1!"
DB_NAME="vehicleservice"
DB_STORAGE_GB=32

# Storage (Blob)  [PAID ~$1/mo]
STORAGE_ACCOUNT="${PROJECT}storage$(openssl rand -hex 3)"
STORAGE_CONTAINER_FILES="files"
STORAGE_CONTAINER_BACKUPS="backups"

# Admin contact
ADMIN_EMAIL="mnk7.dwivedi@gmail.com"

# ============================================================
# PRE-FLIGHT
# ============================================================
echo "==> Setting subscription"
az account set --subscription "$SUBSCRIPTION_ID"

echo "==> Using SSH key: $SSH_PUBKEY_PATH"
test -f "$SSH_PUBKEY_PATH" || { echo "SSH key not found"; exit 1; }

# Save secrets locally (gitignored)
SECRETS_FILE="$(dirname "$0")/secrets.env"
cat > "$SECRETS_FILE" <<EOF
# Generated $(date) — DO NOT COMMIT
DB_ADMIN_USER=$DB_ADMIN_USER
DB_ADMIN_PASS=$DB_ADMIN_PASS
DB_SERVER_NAME=$DB_SERVER_NAME
DB_NAME=$DB_NAME
STORAGE_ACCOUNT=$STORAGE_ACCOUNT
EOF
chmod 600 "$SECRETS_FILE"
echo "Secrets saved to $SECRETS_FILE (permissions 600)"

# ============================================================
# 1. Resource Group
# ============================================================
echo "==> Creating resource group"
az group create --name "$RG" --location "$LOCATION" -o table

# ============================================================
# 2. Networking (free)
# ============================================================
echo "==> Creating VNet + subnet"
az network vnet create \
  --resource-group "$RG" --name "$VNET_NAME" \
  --address-prefix 10.10.0.0/16 \
  --subnet-name "$SUBNET_NAME" --subnet-prefix 10.10.1.0/24 -o table

echo "==> Creating NSG with rules (22/80/443)"
az network nsg create --resource-group "$RG" --name "$NSG_NAME" -o table
for rule in "SSH:22:100" "HTTP:80:110" "HTTPS:443:120"; do
  IFS=: read -r NAME PORT PRIO <<< "$rule"
  az network nsg rule create \
    --resource-group "$RG" --nsg-name "$NSG_NAME" \
    --name "Allow-$NAME" --priority "$PRIO" \
    --destination-port-ranges "$PORT" --access Allow --protocol Tcp -o none
done

echo "==> Creating static public IP  [PAID ~\$3/mo]"
az network public-ip create \
  --resource-group "$RG" --name "$PIP_NAME" \
  --sku Standard --allocation-method Static -o table

# ============================================================
# 3. VM  [PAID ~$30/mo]
# ============================================================
echo "==> Creating VM $VM_NAME ($VM_SIZE)  [PAID]"
az vm create \
  --resource-group "$RG" --name "$VM_NAME" \
  --image "$VM_IMAGE" --size "$VM_SIZE" \
  --admin-username "$VM_ADMIN" \
  --ssh-key-values "$SSH_PUBKEY_PATH" \
  --vnet-name "$VNET_NAME" --subnet "$SUBNET_NAME" \
  --nsg "$NSG_NAME" --public-ip-address "$PIP_NAME" \
  --os-disk-size-gb 32 \
  --custom-data "$(dirname "$0")/cloud-init.yaml" \
  -o table

VM_IP=$(az network public-ip show -g "$RG" -n "$PIP_NAME" --query ipAddress -o tsv)
echo "VM public IP: $VM_IP"

# ============================================================
# 4. MySQL Flexible Server  [PAID ~$25/mo]
# ============================================================
echo "==> Creating MySQL Flexible Server  [PAID]"
az mysql flexible-server create \
  --resource-group "$RG" --name "$DB_SERVER_NAME" \
  --location "$LOCATION" \
  --admin-user "$DB_ADMIN_USER" --admin-password "$DB_ADMIN_PASS" \
  --sku-name "$DB_SKU" --tier "$DB_TIER" \
  --version "$DB_VERSION" \
  --storage-size "$DB_STORAGE_GB" \
  --public-access "$VM_IP" \
  --backup-retention 7 \
  --yes \
  -o table

echo "==> Creating database $DB_NAME"
az mysql flexible-server db create \
  --resource-group "$RG" --server-name "$DB_SERVER_NAME" \
  --database-name "$DB_NAME" -o table

# ============================================================
# 5. Blob Storage  [PAID ~$1/mo]
# ============================================================
echo "==> Creating storage account  [PAID]"
az storage account create \
  --resource-group "$RG" --name "$STORAGE_ACCOUNT" \
  --location "$LOCATION" --sku Standard_LRS --kind StorageV2 \
  --access-tier Hot --allow-blob-public-access false -o table

STORAGE_KEY=$(az storage account keys list -g "$RG" -n "$STORAGE_ACCOUNT" --query '[0].value' -o tsv)

for c in "$STORAGE_CONTAINER_FILES" "$STORAGE_CONTAINER_BACKUPS"; do
  az storage container create \
    --name "$c" --account-name "$STORAGE_ACCOUNT" --account-key "$STORAGE_KEY" -o none
done

# Append storage creds to secrets
cat >> "$SECRETS_FILE" <<EOF
STORAGE_KEY=$STORAGE_KEY
VM_PUBLIC_IP=$VM_IP
EOF

# ============================================================
# 6. Backup vault for VM snapshots  [PAID ~$2/mo]
# ============================================================
VAULT_NAME="${PROJECT}-backup-vault"
echo "==> Creating Recovery Services Vault  [PAID]"
az backup vault create --resource-group "$RG" --name "$VAULT_NAME" --location "$LOCATION" -o table
az backup protection enable-for-vm \
  --resource-group "$RG" --vault-name "$VAULT_NAME" \
  --vm "$VM_NAME" --policy-name DefaultPolicy -o table

# ============================================================
# SUMMARY
# ============================================================
cat <<EOF

============================================================
PROVISIONING COMPLETE
============================================================
VM SSH:             ssh $VM_ADMIN@$VM_IP
MariaDB Host:       ${DB_SERVER_NAME}.mariadb.database.azure.com
MariaDB User:       $DB_ADMIN_USER
Storage Account:    $STORAGE_ACCOUNT
Secrets file:       $SECRETS_FILE

NEXT STEPS:
  1. SSH into VM — cloud-init has installed bench + Frappe prerequisites
  2. Run: bash deploy/bench-setup.sh (on the VM)
  3. Configure GitHub Actions with secrets from $SECRETS_FILE
  4. Point service.naarni.com DNS A record to $VM_IP
  5. Run Let's Encrypt: sudo bench setup lets-encrypt service.naarni.com
============================================================
EOF
