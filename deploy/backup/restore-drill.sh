#!/usr/bin/env bash
# Restores a dump into a throwaway database and checks the schema is there, proving the backup
# is usable. Never touches the live database. Usage: restore-drill.sh /var/backups/caderly/<dump>
set -euo pipefail

dump="${1:?usage: restore-drill.sh <dump file>}"
DB_USER="${DB_USER:-caderly}"
scratch="caderly_restore_drill"

export PGPASSWORD="$DB_PASSWORD"
dropdb -h localhost -U "$DB_USER" --if-exists "$scratch"
createdb -h localhost -U "$DB_USER" "$scratch"
pg_restore -h localhost -U "$DB_USER" -d "$scratch" --no-owner "$dump"

tenants=$(psql -h localhost -U "$DB_USER" -d "$scratch" -Atc "select count(*) from caderly_hr.tenant")
migrations=$(psql -h localhost -U "$DB_USER" -d "$scratch" -Atc "select count(*) from caderly_hr.flyway_schema_history")
echo "restore drill ok: $tenants tenant(s), $migrations migration(s) in $dump"

dropdb -h localhost -U "$DB_USER" "$scratch"
