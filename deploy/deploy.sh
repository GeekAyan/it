#!/usr/bin/env bash
# =====================================================================
#  Deploy a new application jar to the AIIMS IT Event Portal.
#
#      sudo /opt/aiims/deploy/deploy.sh /tmp/it-0.0.1-SNAPSHOT.jar
#
#  Sequence (deliberate):
#      1. back up the database, uploads and the CURRENT jar
#      2. install the new jar
#      3. restart the service
#      4. health check - automatically roll back if the app does not come up
#
#  The database service is never touched, so this cannot lose data.
# =====================================================================
set -euo pipefail

APP_DIR=/opt/aiims
# AppArmor confines mysqld to /etc/mysql, so the instance config lives there
# (init-db.sh installs it). See deploy/README.md.
MYSQL_CNF=/etc/mysql/aiims.cnf
ENV_FILE="$APP_DIR/aiims.env"
SERVICE=aiims-app.service
# The app runs under the /event context path, so the health endpoint is
# /event/login, not /login. Getting this wrong makes every deploy fail its
# health check and trigger a pointless rollback.
HEALTH_URL=http://127.0.0.1:8080/event/login

STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$APP_DIR/backups" "$APP_DIR/logs"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33mWARNING: %s\033[0m\n' "$1"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

NEW_JAR="${1:-}"
[[ -n "$NEW_JAR" ]] || die "Usage: $0 /path/to/it-0.0.1-SNAPSHOT.jar"
[[ -f "$NEW_JAR" ]]  || die "Jar not found: $NEW_JAR"

[[ $EUID -eq 0 ]]                || die "Run as root (it restarts a system service)."
[[ -f "$MYSQL_CNF" ]]            || die "MySQL config missing - has init-db.sh been run?"
[[ -f "$ENV_FILE" ]]            || die "$ENV_FILE missing."
[[ -f "$APP_DIR/application-prod.properties" ]] || die "application-prod.properties missing."

DB_PASSWORD=""
while IFS= read -r line; do
  case "$line" in DB_PASSWORD=*) DB_PASSWORD="${line#DB_PASSWORD=}" ;; esac
done < "$ENV_FILE"
DB_PASSWORD="${DB_PASSWORD%\"}"; DB_PASSWORD="${DB_PASSWORD#\"}"
MYSQL="/usr/bin/mysql --defaults-file=$MYSQL_CNF -u root -p$DB_PASSWORD"
# Backups need mysqldump, NOT the mysql client --single-transaction and
# --routines/--triggers are mysqldump options and the client rejects them.
MYSQLDUMP="/usr/bin/mysqldump --defaults-file=$MYSQL_CNF -u root -p$DB_PASSWORD"

# --- 1. back up everything BEFORE changing anything --------------------
log "Backing up the database"
$MYSQLDUMP --single-transaction --routines --triggers \
           --default-character-set=utf8mb4 eventdb \
           > "$APP_DIR/backups/eventdb-${STAMP}.sql"
[[ -s "$APP_DIR/backups/eventdb-${STAMP}.sql" ]] || die "Database backup is empty - ABORTING."

if [[ -d "$APP_DIR/uploads" ]]; then
  log "Backing up uploads"
  tar -czf "$APP_DIR/backups/uploads-${STAMP}.tar.gz" -C "$APP_DIR" uploads
fi

CURRENT_JAR_BACKUP=""
if [[ -f "$APP_DIR/app.jar" ]]; then
  log "Saving the current jar"
  cp -p "$APP_DIR/app.jar" "$APP_DIR/backups/app-${STAMP}.jar"
  CURRENT_JAR_BACKUP="$APP_DIR/backups/app-${STAMP}.jar"
fi

# keep the 10 most recent of each
ls -1t "$APP_DIR"/backups/eventdb-*.sql    2>/dev/null | tail -n +11 | xargs -r rm -f --
ls -1t "$APP_DIR"/backups/uploads-*.tar.gz 2>/dev/null | tail -n +11 | xargs -r rm -f --
ls -1t "$APP_DIR"/backups/app-*.jar       2>/dev/null | tail -n +11 | xargs -r rm -f --

# --- 2. install the new jar --------------------------------------------
log "Installing $(basename "$NEW_JAR")"
install -m 640 -o aiims -g aiims "$NEW_JAR" "$APP_DIR/app.jar.new"
mv -f "$APP_DIR/app.jar.new" "$APP_DIR/app.jar"

# --- 3. restart --------------------------------------------------------
log "Restarting $SERVICE"
systemctl restart "$SERVICE"

# --- 4. health check with automatic rollback ---------------------------
log "Waiting for the application to become healthy"
HEALTHY=0
for i in $(seq 1 60); do
  if curl -fsS -o /dev/null --max-time 5 "$HEALTH_URL" 2>/dev/null; then
    HEALTHY=1; echo "    healthy after ${i}s"; break
  fi
  sleep 1
done

if [[ "$HEALTHY" -ne 1 ]]; then
  warn "The application did not become healthy within 60 seconds."
  echo "--- last 30 log lines ---"
  journalctl -u "$SERVICE" -n 30 --no-pager || true
  if [[ -n "$CURRENT_JAR_BACKUP" ]]; then
    warn "Rolling back to the previous jar."
    install -m 640 -o aiims -g aiims "$CURRENT_JAR_BACKUP" "$APP_DIR/app.jar"
    systemctl restart "$SERVICE"
    sleep 10
    warn "Rolled back. The database was not modified."
  fi
  exit 1
fi

log "Deployment succeeded"
systemctl --no-pager --lines=0 status "$SERVICE" | head -3 || true
cat <<EOF

  Backups : ls -1t $APP_DIR/backups/
  Logs    : journalctl -u $SERVICE -f
  Rollback: $APP_DIR/deploy/rollback.sh

EOF