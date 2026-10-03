#!/usr/bin/env bash
# =====================================================================
#  Restore the eventdb database from a mysqldump backup.
#
#  Usage:
#    ./deploy/restore.sh                        # list available backups
#    ./deploy/restore.sh backups/eventdb-20261003-120000.sql
#
#  NOTE: this OVERWRITES the contents of the database. A safety dump of the
#  current (pre-restore) state is taken automatically before restoring.
# =====================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"
mkdir -p backups

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

FILE="${1:-}"
if [[ -z "$FILE" ]]; then
  echo "Available backups (newest first):"
  ls -1t backups/eventdb-*.sql 2>/dev/null || echo "  (none found in ./backups)"
  echo
  echo "Usage: ./deploy/restore.sh backups/eventdb-<timestamp>.sql"
  exit 1
fi

[[ -f "$FILE" ]] || die "Backup file not found: $FILE"
command -v docker >/dev/null 2>&1 || die "docker is not installed on this host."

printf '\033[1;31m'
echo "This will REPLACE the contents of the eventdb database."
echo "Source backup : $FILE"
printf '\033[0m'
read -rp "Type 'yes' to continue: " ANSWER
[[ "$ANSWER" == "yes" ]] || { echo "Aborted."; exit 1; }

# safety dump of the state we are about to overwrite
log "Taking a safety dump of the current database"
docker compose exec -T db sh -c \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction \
   --routines --triggers --default-character-set=utf8mb4 eventdb' \
  > "backups/pre-restore-$(date +%Y%m%d-%H%M%S).sql"

log "Restoring from $FILE"
docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" eventdb' < "$FILE"

log "Verifying row counts"
docker compose exec -T db sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"
     SELECT 'events',       COUNT(*) FROM events
     UNION ALL SELECT 'event_images', COUNT(*) FROM event_images
     UNION ALL SELECT 'users',        COUNT(*) FROM users
     UNION ALL SELECT 'admin_users',  COUNT(*) FROM admin_users;\""

echo
echo "Restore complete. The app may need a restart to re-read the schema:"
echo "  docker compose restart app"