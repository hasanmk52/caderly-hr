# Installing Caderly on a Debian server

Caderly runs as two containers (`app` and `caddy`) from `docker-compose.yml`. PostgreSQL is
installed natively on the host. Examples use `caderly.app`; substitute your own domain.

## 1. Prerequisites

- Debian 12 with `sudo`, ports 80 and 443 free.
- Docker Engine with the Compose plugin.
- PostgreSQL 17 (`apt install postgresql-17`). Caderly's tests run on 17 and production should
  too. PostgreSQL 16 also works (no migration uses 17-only SQL) and is acceptable for a demo.
- A domain whose DNS you control. This guide assumes Cloudflare for DNS, because Caddy needs a
  DNS API to get the wildcard certificate.

## 2. DNS

Two `A` records, both pointing at the server's IP and **not proxied** (grey cloud in Cloudflare):

| Name | Type | Value |
|---|---|---|
| `@` (caderly.app) | A | server IP |
| `*` (*.caderly.app) | A | server IP |

Check: `dig +short acme.caderly.app` returns the server IP. Moving to another server later means
restoring a backup there (section 8) and editing these two records.

Create a Cloudflare API token from the "Edit zone DNS" template, limited to this one zone.

## 3. Database

Create the role and database, and let the Docker network reach Postgres:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE caderly LOGIN PASSWORD '<db password>';
CREATE DATABASE caderly OWNER caderly;
SQL
```

In `postgresql.conf` set `listen_addresses = '*'` (the default is localhost only, which a
container cannot reach). `pg_hba.conf` is what limits who may connect, so add exactly one line
for the compose subnet and nothing broader:

```
host    caderly    caderly    172.30.0.0/24    scram-sha-256
```

Then `sudo systemctl restart postgresql`. Make sure the firewall does not expose 5432 publicly
(section 5). The subnet matches the `edge` network in `docker-compose.yml`.

## 4. Configuration

```bash
sudo mkdir -p /var/lib/caderly/files && sudo chown 65532:65532 /var/lib/caderly/files
git clone <repo> caderly && cd caderly
cp .env.production.example .env && chmod 600 .env
```

Edit `.env`. Generate the secrets on the server so they never travel through chat or email:

```bash
openssl rand -base64 32      # CADERLY_EMPLOYEE_ENCRYPTION_KEY, back this up off the server
openssl rand -base64 24      # DB_PASSWORD
```

Set `CADERLY_SUPERADMIN_IP_ALLOWLIST` to the addresses that may open `/superadmin`. Leave it empty
and the console is unreachable.

## 5. Firewall

Only SSH, 80 and 443 should be reachable from outside. With `firewalld`:

```bash
sudo firewall-cmd --permanent --add-service=http --add-service=https
sudo firewall-cmd --reload
sudo firewall-cmd --list-all        # 5432 and 8080 must not appear
```

The app container publishes no port at all; only Caddy reaches it over the compose network.
That matters: the app trusts the first `X-Forwarded-For` hop as the client address, and Caddy
sets that header from the real peer. A directly reachable app port would let a client forge it
and bypass the Super Admin IP allowlist and login rate limits.

## 6. Start

```bash
docker compose up -d --build
docker compose logs -f caddy        # wait for "certificate obtained successfully"
```

Open `https://caderly.app`. Flyway creates the schema on first start.

## 7. Backups

`deploy/backup/backup.sh` writes a compressed `pg_dump`, checks it can be read back, keeps 14,
and optionally copies it to S3.

```bash
sudo install -d -m 700 /etc/caderly
printf 'DB_PASSWORD=<db password>\n' | sudo tee /etc/caderly/backup.env >/dev/null
sudo chmod 600 /etc/caderly/backup.env
# 02:30 every night
echo '30 2 * * * root set -a; . /etc/caderly/backup.env; set +a; /opt/caderly/deploy/backup/backup.sh >> /var/log/caderly-backup.log 2>&1' | sudo tee /etc/cron.d/caderly-backup
```

Uploaded files live in `/var/lib/caderly/files` and are not in the dump; back that directory up
too. The encryption key in `.env` is also not in the dump, and the encrypted columns are
unreadable without it.

## 8. Restore drill

Run this once after the first backup, and again after any change to the backup setup:

```bash
set -a; . /etc/caderly/backup.env; set +a
deploy/backup/restore-drill.sh /var/backups/caderly/<dump file>
```

It restores into a throwaway database, counts tenants and migrations, then drops it. To restore
for real on a new server: install Postgres, create the role and database (section 3), run
`pg_restore --no-owner -d caderly <dump>`, copy `.env` and the files directory, and start the
stack.

## 9. Operations

- Update: `git pull && docker compose up -d --build`.
- Logs: `docker compose logs app` (structured JSON, with request, tenant and actor IDs).
- Certificates renew automatically; keep the `caddy_data` volume, because it holds them.
- Self-serve sign-up stays off until `CADERLY_SIGNUP_ENABLED=true`.
