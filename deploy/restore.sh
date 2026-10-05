#!/usr/bin/env bash
# =====================================================================
#  Restore the eventdb database from a mysqldump backup.
#  Targets the app instance on port 3307 only - the production instance
#  on 3306 is never contacted.
#
#  Usage:
#      sudo /opt/aiims/deploy/restore.sh                       # list backups
#      sudo /opt/aiims/deploy/restore.sh backups/eventdb-20261003-120000.sql
# =====================================================================
set -euo pipefail

APP_DIR=/opt/aiims
MYSQL_CNF="$APP_DIR/mysql/aiims-mysql.cnf"
ENV_FILE="$APP_DIR/aiims.env"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

FILE="${1:-}"
if [[ -z "$FILE" ]]; then
  echo "Available database backups (newest first):"
  ls -1t "$APP_DIR"/backups/eventdb-*.sql 2>/dev/null || echo "  (none found in $APP_DIR/backups)"
  echo
  echo "Usage: $0 backups/eventdb-<timestamp>.sql"
  exit 1
fi

[[ -f "$FILE" ]] || die "Backup file not found: $FILE"
[[ $EUID -eq 0 ]] || die "Run as root."
[[ -f "$ENV_FILE" ]] || die "$ENV_FILE missing."

DB_PASSWORD=""
while IFS= read -r line; do
  case "$line" in DB_PASSWORD=*) DB_PASSWORD="${line#DB_PASSWORD=}" ;; esac
done < "$ENV_FILE"
DB_PASSWORD="${DB_PASSWORD%\"}"; DB_PASSWORD="${DB_PASSWORD#\"}"
MYSQL="/usr/bin/mysql --defaults-file=$MYSQL_CNF -u root -p$DB_PASSWORD"

printf '\033[1;31m'
echo "This REPLACES the contents of the eventdb schema on port 3307."
echo "Backup file: $FILE"
printf '\033[0m'
read -rp "Type 'yes' to continue: " ANSWER
[[ "$ANSWER" == "yes" ]] || { echo "Aborted."; exit 1; }

log "Taking a safety dump of the current state"
$MYSQL --single-transaction --routines --triggers \
       --default-character-set=utf8mb4 eventdb \
       > "$APP_DIR/backups/pre-restore-$(date +%Y%m%d-%H%M%S).sql"

log "Restoring from $(basename "$FILE")"
$MYSQL eventdb < "$FILE"

log "Row counts after restore"
$MYSQL -N -e "
  SELECT 'events',       COUNT(*) FROM eventdb.events
  UNION ALL SELECT 'event_images', COUNT(*) FROM eventdb.event_images
  UNION ALL SELECT 'users',        COUNT(*) FROM eventdb.users
  UNION ALL SELECT 'admin_users',  COUNT(*) FROM eventdb.admin_users;"

echo
echo "Restore complete. Restart the app so Hibernate re-reads the schema:"
echo "  systemctl restart aiims-app"