#!/usr/bin/env bash
# Nightly logical backup of the Caderly database. Run from cron as a user that can read
# /etc/caderly/backup.env (DB_PASSWORD and optional S3_URI). Keeps 14 local dumps.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/caderly}"
DB_NAME="${DB_NAME:-caderly}"
DB_USER="${DB_USER:-caderly}"
KEEP="${KEEP:-14}"

umask 077
mkdir -p "$BACKUP_DIR"
file="$BACKUP_DIR/${DB_NAME}-$(date +%Y%m%d-%H%M%S).dump"

# Custom format: compressed, and pg_restore can restore it selectively.
PGPASSWORD="$DB_PASSWORD" pg_dump -h localhost -U "$DB_USER" -Fc -f "$file" "$DB_NAME"

# An empty or truncated dump must fail loudly, not sit in the rotation as a "backup".
pg_restore --list "$file" > /dev/null

# Optional off-site copy (needs the AWS CLI configured for S3_URI's bucket).
if [[ -n "${S3_URI:-}" ]]; then
    aws s3 cp "$file" "$S3_URI/"
fi

ls -1t "$BACKUP_DIR"/${DB_NAME}-*.dump | tail -n +"$((KEEP + 1))" | xargs -r rm --
echo "backup ok: $file"
