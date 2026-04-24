# Vehicle Service — Azure Deployment Guide

Complete reference for the production deployment of the Frappe/ERPNext + `vehicle_maintenance` app on Azure.

---

## 1. Overview

| Attribute | Value |
|---|---|
| **Site** | `service.naarni.com` |
| **Public IP** | `20.219.136.19` |
| **Region** | Central India (`centralindia`) |
| **Subscription** | Naarni Microsoft AZ Subscription |
| **Resource Group** | `vehicle-service-prod` |
| **Monthly cost (est.)** | ~$36 USD |
| **Frappe version** | v15 |
| **Apps installed** | `frappe`, `erpnext`, `vehicle_maintenance` |

---

## 2. Architecture

```
                    ┌────────────────────────────────────────────┐
                    │       Azure Region: Central India          │
                    │                                            │
   Internet         │   ┌──────────────────────────────────┐     │
     ──────────────►│   │  NSG (22/80/443)                 │     │
                    │   │  Public IP: 20.219.136.19        │     │
                    │   │                                  │     │
                    │   │  ┌──────────────────────────┐    │     │
                    │   │  │  VM (B2s, Ubuntu 22.04)  │    │     │
                    │   │  │                          │    │     │
                    │   │  │  Nginx ─── Frappe (WSGI) │    │     │
                    │   │  │     │     ├── Supervisor │    │     │
                    │   │  │     │     ├── Redis      │    │     │
                    │   │  │     │     └── MariaDB    │    │     │
                    │   │  │     │          │         │    │     │
                    │   │  │  4GB swap      ▼         │    │     │
                    │   │  │           /var/lib/mysql │    │     │
                    │   │  └──────────────────────────┘    │     │
                    │   └──────────────────────────────────┘     │
                    │                   │                        │
                    │                   │ nightly 02:30 UTC      │
                    │                   ▼                        │
                    │   ┌──────────────────────────────────┐     │
                    │   │  Storage Account                 │     │
                    │   │  vsvcd5dbcde392c8                │     │
                    │   │  ├── container: backups (7 days) │     │
                    │   │  └── container: files (reserved) │     │
                    │   └──────────────────────────────────┘     │
                    │                                            │
                    │   ┌──────────────────────────────────┐     │
                    │   │  Recovery Services Vault         │     │
                    │   │  vehicleservice-backup-vault     │     │
                    │   │  (daily VM disk snapshots)       │     │
                    │   └──────────────────────────────────┘     │
                    └────────────────────────────────────────────┘
```

---

## 3. Azure Resources

All resources live in resource group `vehicle-service-prod`.

| Resource | Name | SKU / Config | Cost/mo |
|---|---|---|---|
| VM | `vehicleservice-vm` | Standard_B2s (2 vCPU, 4 GB RAM), Ubuntu 22.04, 32 GB OS disk | ~$30 |
| VNet + Subnet | `vehicleservice-vnet` / `vehicleservice-subnet` | 10.10.0.0/16 | $0 |
| NSG | `vehicleservice-nsg` | Allows 22, 80, 443 inbound | $0 |
| Public IP | `vehicleservice-pip` | Standard, Static | ~$3 |
| Storage Account | `vsvcd5dbcde392c8` | Standard_LRS, Hot tier, private | ~$1 |
| Blob Containers | `backups`, `files` | Private | — |
| Recovery Vault | `vehicleservice-backup-vault` | Daily VM snapshots (default policy) | ~$2 |
| **Total** | | | **~$36** |

> **Deleted in favor of localhost MariaDB:** originally provisioned `vehicleservice-mysql` (managed MySQL Flex) was removed to reduce cost. Data durability is now provided by nightly blob backups with 7-day retention + VM snapshots.

---

## 4. Access

### 4.1 URLs

| Role | URL | Notes |
|---|---|---|
| Admin / Ops Manager | `https://service.naarni.com/app` | Frappe Desk |
| Technicians / SEs / Customers | `https://service.naarni.com/` | Vue SPA |
| Login | `https://service.naarni.com/login` | All users |
| API base | `https://service.naarni.com/api/method/<module>.<function>` | External/mobile |

Until DNS is set, access works via `/etc/hosts` entry:
```bash
sudo sh -c 'echo "20.219.136.19 service.naarni.com" >> /etc/hosts'
```

### 4.2 SSH to the VM

```bash
ssh azureuser@20.219.136.19        # sudo-capable admin
ssh frappe@20.219.136.19           # runs bench, owns the app
```

Both accept the SSH key at `~/.ssh/id_ed25519`. No password auth.

### 4.3 Default credentials

| Account | Username | Password |
|---|---|---|
| Frappe Administrator | `Administrator` | value of `DB_ADMIN_PASS` in `deploy/secrets.env` |
| MariaDB root | `root` | same value |

Change the Frappe Administrator password on first login.

### 4.4 Secrets files (local, gitignored)

| File | Purpose |
|---|---|
| `deploy/secrets.env` | DB password, storage key, VM IP |
| `deploy/github-secrets.txt` | Values to paste into GitHub repo secrets |

---

## 5. Backups

### 5.1 Strategy

Three overlapping layers:

1. **Bench auto-backup** — every 6 hours, local only, kept short-term.
2. **Blob backup + 7-day retention** — this repo's script, runs nightly 02:30 UTC.
3. **VM disk snapshot** — Azure Backup vault, daily.

### 5.2 Blob backup details

**Script:** `/usr/local/bin/backup-to-blob.sh` on the VM (source: [deploy/backup-to-blob.sh](backup-to-blob.sh))

**Cron entry (frappe user):**
```
30 2 * * * /usr/local/bin/backup-to-blob.sh
```

**Log file:** `/home/frappe/backup.log`

**Storage location:**
```
Account:    vsvcd5dbcde392c8
Container:  backups
Path:       <UTC-timestamp>/<file>
URL:        https://vsvcd5dbcde392c8.blob.core.windows.net/backups/YYYYMMDD-HHMMSS/...
```

**What's uploaded each run:**
- `*-database.sql.gz` — gzipped MariaDB dump
- `*-files.tgz` — public uploaded files
- `*-private-files.tgz` — private uploaded files
- `*-site_config_backup.json` — site config

**Retention:**
- Azure Blob: 7 days (enforced by the script, deletes older blobs)
- Local VM: 2 days (older local dumps purged by the script)

### 5.3 Manual backup

```bash
ssh azureuser@20.219.136.19 'sudo -u frappe -H /usr/local/bin/backup-to-blob.sh'
```

### 5.4 List backups

```bash
source deploy/secrets.env
az storage blob list \
  --account-name "$STORAGE_ACCOUNT" --account-key "$STORAGE_KEY" \
  --container-name backups -o table
```

### 5.5 Restore from blob

```bash
source deploy/secrets.env

# 1. Download the desired backup bundle
mkdir -p /tmp/restore && cd /tmp/restore
TS="20260424-051144"   # change to desired timestamp
for f in database.sql.gz files.tgz private-files.tgz site_config_backup.json; do
  az storage blob download \
    --account-name "$STORAGE_ACCOUNT" --account-key "$STORAGE_KEY" \
    --container-name backups \
    --name "$TS/$(ls blobs matching)" \
    --file "./$f"
done

# 2. SCP to VM and run bench restore
scp *.sql.gz *.tgz *.json azureuser@20.219.136.19:/tmp/
ssh frappe@20.219.136.19
cd ~/frappe-bench
bench --site service.naarni.com restore /tmp/*-database.sql.gz \
  --with-public-files /tmp/*-files.tgz \
  --with-private-files /tmp/*-private-files.tgz
```

---

## 6. Provisioning from scratch

All provisioning is reproducible via scripts in this `deploy/` directory.

### 6.1 Files

| File | Purpose |
|---|---|
| [azure-provision.sh](azure-provision.sh) | Creates RG, VNet, NSG, Public IP, VM, Storage, Backup Vault |
| [azure-resume.sh](azure-resume.sh) | Resumes provisioning after provider-registration fix |
| [cloud-init.yaml](cloud-init.yaml) | VM first-boot bootstrap — installs bench prereqs |
| [bench-setup.sh](bench-setup.sh) | Runs on VM as `frappe` user — bench init, apps, site, production |
| [backup-to-blob.sh](backup-to-blob.sh) | Nightly backup to Azure Blob |
| [.github-workflows-deploy.yml](.github-workflows-deploy.yml) | Template for the GitHub Actions workflow |
| [secrets.env](secrets.env) | **Gitignored** — generated secrets |
| [github-secrets.txt](github-secrets.txt) | **Gitignored** — values for GitHub repo secrets |

### 6.2 Full re-provision

```bash
# 1. Login
az login

# 2. Provision Azure resources
bash deploy/azure-provision.sh

# 3. Copy secrets + setup script to VM
scp deploy/secrets.env deploy/bench-setup.sh azureuser@<new_ip>:/tmp/
ssh azureuser@<new_ip> 'sudo mv /tmp/secrets.env /tmp/bench-setup.sh /home/frappe/ && sudo chown frappe:frappe /home/frappe/*'

# 4. Run bench setup
ssh azureuser@<new_ip> 'sudo -u frappe -H /home/frappe/bench-setup.sh'

# 5. Install backup cron
scp deploy/backup-to-blob.sh azureuser@<new_ip>:/tmp/
ssh azureuser@<new_ip> 'sudo mv /tmp/backup-to-blob.sh /usr/local/bin/ && \
  sudo chown frappe:frappe /usr/local/bin/backup-to-blob.sh && \
  sudo chmod +x /usr/local/bin/backup-to-blob.sh && \
  (sudo -u frappe crontab -l 2>/dev/null; echo "30 2 * * * /usr/local/bin/backup-to-blob.sh") | sudo -u frappe crontab -'
```

---

## 7. CI/CD (GitHub Actions)

### 7.1 Workflow

File: [.github/workflows/deploy-azure.yml](../.github/workflows/deploy-azure.yml)

**Trigger:** push to `develop` or `main` that touches `vehicle_maintenance/**` or the workflow itself. Also manual via "Run workflow".

**Steps:**
1. Configures SSH using `VM_SSH_KEY` secret
2. SSHes into `frappe@<VM_HOST>`
3. `bench set-maintenance-mode on`
4. `git pull` inside `apps/vehicle_maintenance`
5. `bench migrate`
6. `bench build --app vehicle_maintenance`
7. `bench clear-cache`
8. `bench set-maintenance-mode off`
9. `sudo supervisorctl restart all`

### 7.2 Required repo secrets

Set at https://github.com/mdkacode/naarni-erpnext/settings/secrets/actions

| Secret | Value |
|---|---|
| `VM_HOST` | `20.219.136.19` |
| `SITE_NAME` | `service.naarni.com` |
| `VM_SSH_KEY` | Full contents of `~/.ssh/id_ed25519` (including `-----BEGIN`/`-----END` lines) |

Convenience file: [github-secrets.txt](github-secrets.txt) contains all three (gitignored).

### 7.3 Local pre-commit hook

Installed via:
```bash
pip3 install --user pre-commit
pre-commit install
```

Hooks run on every `git commit` (config: `.pre-commit-config.yaml` at repo root). Notably:
- Blocks direct commits to `develop` (use feature branches)
- `trailing-whitespace`, `check-yaml`, `check-json`, `prettier` on JS/Vue

---

## 8. Day-2 Operations

### 8.1 SSH into the VM

```bash
ssh frappe@20.219.136.19       # for bench commands
ssh azureuser@20.219.136.19    # for system admin
```

### 8.2 Common bench commands

```bash
cd ~/frappe-bench

bench --site service.naarni.com console             # Python REPL
bench --site service.naarni.com migrate             # run migrations
bench --site service.naarni.com clear-cache         # clear cache
bench --site service.naarni.com backup --with-files # manual local backup
bench build --app vehicle_maintenance               # rebuild JS/CSS
bench restart                                       # reload workers
sudo supervisorctl restart all                      # hard restart
sudo supervisorctl status                           # process health
```

### 8.3 Logs

| Log | Path |
|---|---|
| Nginx access | `/var/log/nginx/access.log` |
| Nginx error | `/var/log/nginx/error.log` |
| Supervisor | `/var/log/supervisor/*.log` |
| Frappe web | `~/frappe-bench/logs/web.log` |
| Frappe worker | `~/frappe-bench/logs/worker.*.log` |
| Frappe scheduler | `~/frappe-bench/logs/scheduler.log` |
| Backup script | `/home/frappe/backup.log` |

### 8.4 Restart services

```bash
sudo supervisorctl restart all    # Frappe workers, web, scheduler
sudo systemctl reload nginx       # Nginx config reload
sudo systemctl restart mariadb    # DB (rare)
sudo systemctl restart redis-server
```

### 8.5 Put site in maintenance

```bash
bench --site service.naarni.com set-maintenance-mode on
# ...
bench --site service.naarni.com set-maintenance-mode off
```

---

## 9. DNS + SSL

### 9.1 DNS

At your domain registrar (for `naarni.com`):

| Type | Host | Value |
|---|---|---|
| A | `service` | `20.219.136.19` |

TTL: 300 seconds for fast rollback.

### 9.2 SSL (Let's Encrypt)

After DNS resolves (`dig +short service.naarni.com` returns the IP):

```bash
ssh azureuser@20.219.136.19 'sudo -H bench setup lets-encrypt service.naarni.com'
```

Certificates auto-renew via the `certbot.timer` systemd unit that comes with the `python3-certbot-nginx` package.

---

## 10. Security

### 10.1 Current posture

- **SSH**: key-only, no passwords. Port 22 open to 0.0.0.0/0.
- **HTTP/HTTPS**: 80/443 open (required for public site).
- **MariaDB**: bound to `127.0.0.1` — no network exposure.
- **Redis**: default config, localhost only.
- **Storage Account**: no public blob access; auth via account key.
- **Firewall**: `ufw` enabled on VM (`OpenSSH`, `Nginx Full` allowed).
- **fail2ban**: installed by cloud-init (default config).

### 10.2 Hardening recommendations (not yet applied)

- Restrict SSH ingress in NSG to your office/admin IPs only
- Rotate `DB_ADMIN_PASS` and regenerate bench site password
- Enable Azure Blob soft-delete (30-day recovery of accidentally deleted backups)
- Switch storage replication to GRS (geo-redundant) for disaster recovery
- Enable Microsoft Defender for Storage + VM
- Add Microsoft Entra ID (AAD) SSO for Frappe Desk

---

## 11. Cost Breakdown

| Resource | SKU | Monthly |
|---|---|---|
| VM `vehicleservice-vm` | Standard_B2s | $30.37 |
| OS Disk | 32 GB Standard SSD | $2.40 |
| Public IP | Standard Static | $3.65 |
| Storage Account | Standard_LRS, Hot, <10 GB | ~$1.00 |
| Blob transactions | Low volume | ~$0.10 |
| Recovery Services Vault | VM snapshots daily, 30-day retention | ~$2.00 |
| Outbound bandwidth | First 100 GB | ~$0–8.00 |
| **Monthly total (est.)** | | **~$40** |

To further reduce: use a **Spot VM** (~70% cheaper) if you tolerate occasional restarts.

---

## 12. Disaster Recovery

### Scenario A: App-level corruption (bad migration, data accident)
1. `bench --site service.naarni.com restore` with the latest blob backup (§5.5)

### Scenario B: VM crashed / compromised
1. Create new VM (same SKU, same SSH key) via Portal or re-run `azure-provision.sh`
2. Restore from Azure Backup Vault (VM disk snapshot from last night)
3. OR: run `bench-setup.sh` fresh and restore DB from blob

### Scenario C: Region outage (Central India down)
- Current setup is single-region; no automatic failover.
- Blob backups are LRS (single region). To survive region loss, change replication to `Standard_GRS`:
  ```bash
  az storage account update -g vehicle-service-prod -n vsvcd5dbcde392c8 --sku Standard_GRS
  ```

### RTO / RPO
- **RPO:** 24 hours (nightly backup) + 6 hours (bench local backup)
- **RTO:** ~30 minutes for app restore; ~2 hours for full VM rebuild

---

## 13. Quick Reference

| I want to... | Command |
|---|---|
| SSH as admin | `ssh azureuser@20.219.136.19` |
| SSH as app user | `ssh frappe@20.219.136.19` |
| Run manual backup | `ssh azureuser@20.219.136.19 'sudo -u frappe -H /usr/local/bin/backup-to-blob.sh'` |
| List blob backups | `az storage blob list --account-name vsvcd5dbcde392c8 --account-key "$STORAGE_KEY" --container-name backups -o table` |
| Restart Frappe | `ssh frappe@20.219.136.19 'sudo supervisorctl restart all'` |
| Tail web logs | `ssh frappe@20.219.136.19 'tail -f ~/frappe-bench/logs/web.log'` |
| Enter bench console | `ssh frappe@20.219.136.19 'cd ~/frappe-bench && bench --site service.naarni.com console'` |
| Deploy latest code | `git push origin develop` (triggers GitHub Actions) |
| Put site in maintenance | `bench --site service.naarni.com set-maintenance-mode on` |

---

## 14. Contacts / Ownership

| Role | Contact |
|---|---|
| Infrastructure owner | mnk7.dwivedi@gmail.com |
| Azure subscription | Naarni Microsoft AZ Subscription |
| GitHub repo | https://github.com/mdkacode/naarni-erpnext |

---

_Last updated: 2026-04-24_
