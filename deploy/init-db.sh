#!/usr/bin/env bash
# =====================================================================
#  One-time bootstrap of the AIIMS MySQL instance (port 3307).
#
#  Creates a second, fully independent MySQL instance using the mysqld
#  binary already installed on this host. It does NOT touch the production
#  instance on port 3306 in any way.
#
#  Prerequisite: /opt/aiims/aiims.env exists and DB_PASSWORD is filled in.
#  That file is the single source of truth for the password, so the app and
#  the database can never disagree.
#
#  Usage: sudo /opt/aiims/deploy/init-db.sh [path/to/eventdb.sql]
# =====================================================================
set -euo pipefail

APP_DIR=/opt/aiims
MYSQL_DIR="$APP_DIR/mysql"
# NOTE: Ubuntu confines mysqld with AppArmor to /etc/mysql/** and /var/lib/mysql/**.
# The config therefore has to live in /etc/mysql, and init-db.sh installs a local
# AppArmor override granting mysqld access to this instance's own directory.
CNF=/etc/mysql/aiims.cnf
ENV_FILE="$APP_DIR/aiims.env"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33mWARNING: %s\033[0m\n' "$1"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Run this as root (it starts a system service)."
[[ -f "$APP_DIR/deploy/aiims-mysql.cnf" ]] || die "Missing deploy/aiims-mysql.cnf in $APP_DIR/deploy."
[[ -f "$ENV_FILE" ]] || die "Missing $ENV_FILE - copy deploy/aiims.env.example there and fill in DB_PASSWORD."

# Install the config where AppArmor permits mysqld to read it.
log "Installing the MySQL config to $CNF"
install -m 644 -o root -g root "$APP_DIR/deploy/aiims-mysql.cnf" "$CNF"

# Grant mysqld access to this instance's own directory (datadir, socket, pid, log).
# Ubuntu's profile only permits /etc/mysql/** and /var/lib/mysql/**, so without
# this the server aborts with "Failed to open required defaults file" and then
# cannot write its datadir.
log "Installing the AppArmor local override for mysqld"
AA_LOCAL=/etc/apparmor.d/local/usr.sbin.mysqld
if [[ ! -f "$AA_LOCAL" ]] || ! grep -q "/opt/aiims/mysql/" "$AA_LOCAL"; then
  cat > "$AA_LOCAL" <<'AAEOF'
# Local override for the AIIMS IT Event Portal's second MySQL instance (port 3307).
# Scoped strictly to that application's own directory.
/etc/mysql/aiims.cnf r,
/opt/aiims/mysql/ r,
/opt/aiims/mysql/** rwk,
AAEOF
  if command -v apparmor_parser >/dev/null 2>&1 && aa-enabled 2>/dev/null; then
    apparmor_parser -r /etc/apparmor.d/usr.sbin.mysqld
    echo "    reloaded the mysqld profile"
  else
    warn "AppArmor not active - skipping profile reload."
  fi
fi

# Read the password from the env file (systemd EnvironmentFile syntax).
DB_PASSWORD=""
while IFS= read -r line; do
  case "$line" in
    DB_PASSWORD=*) DB_PASSWORD="${line#DB_PASSWORD=}" ;;
  esac
done < "$ENV_FILE"
DB_PASSWORD="${DB_PASSWORD%\"}"; DB_PASSWORD="${DB_PASSWORD#\"}"
[[ -n "$DB_PASSWORD" ]] || die "DB_PASSWORD is empty in $ENV_FILE."

MYSQLD=/usr/sbin/mysqld
MYSQL=/usr/bin/mysql
[[ -x "$MYSQLD" ]] || die "$MYSQLD not found - MySQL server package is missing."

# If the instance is already up we must NOT abort here: the schema, user and
# data import below still need to run. Only the init/start steps are skipped.
ALREADY_RUNNING=0
if ss -lnt 2>/dev/null | grep -q ":3307 "; then
  warn "Port 3307 is already in use - skipping datadir init and service start."
  ALREADY_RUNNING=1
fi

# --- create the datadir -------------------------------------------------
mkdir -p "$MYSQL_DIR/data"
chown -R aiims:aiims "$MYSQL_DIR"
chmod 750 "$MYSQL_DIR/data"

if [[ "$ALREADY_RUNNING" -eq 0 ]]; then
  if [[ -n "$(ls -A "$MYSQL_DIR/data" 2>/dev/null)" ]]; then
    warn "Datadir is not empty but nothing is on 3307. Continuing without re-initialising."
  else
    log "Initialising the data directory (this takes a minute)"
    "$MYSQLD" --defaults-file="$CNF" --initialize-insecure
    chown -R aiims:aiims "$MYSQL_DIR"
  fi

  # --- start the service ------------------------------------------------
  log "Starting aiims-mysql.service"
  install -m 644 -o root -g root "$APP_DIR/deploy/aiims-mysql.service" \
    /etc/systemd/system/aiims-mysql.service
  systemctl daemon-reload
  systemctl enable aiims-mysql.service >/dev/null
  systemctl restart aiims-mysql.service

  # Wait for it to accept connections.
  log "Waiting for MySQL to accept connections on 3307"
  READY=0
  for i in $(seq 1 90); do
    if "$MYSQL" --defaults-file="$CNF" -u root -e "SELECT 1" >/dev/null 2>&1; then
      echo "    ready after ${i}s"; READY=1; break
    fi
    sleep 1
  done
  [[ "$READY" -eq 1 ]] || die "MySQL did not become ready within 90s. Check: journalctl -u aiims-mysql -n 50"
fi

# --- secure root and create the application schema ---------------------
log "Securing root and creating the eventdb schema"
"$MYSQL" --defaults-file="$CNF" -u root <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
CREATE DATABASE IF NOT EXISTS eventdb
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'aiims'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'aiims'@'127.0.0.1' IDENTIFIED BY '${DB_PASSWORD}';
ALTER USER 'aiims'@'127.0.0.1' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON eventdb.* TO 'aiims'@'localhost';
GRANT ALL PRIVILEGES ON eventdb.* TO 'aiims'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

# --- optional data import ----------------------------------------------
DUMP="${1:-}"
if [[ -n "$DUMP" && -f "$DUMP" ]]; then
  log "Importing $DUMP"
  "$MYSQL" --defaults-file="$CNF" -u root -p"$DB_PASSWORD" eventdb < "$DUMP"
  log "Truncating otp_codes (short-lived codes, no value after migration)"
  "$MYSQL" --defaults-file="$CNF" -u root -p"$DB_PASSWORD" eventdb -e "TRUNCATE TABLE otp_codes;" 2>/dev/null || true
  log "Row counts after import"
  "$MYSQL" --defaults-file="$CNF" -u root -p"$DB_PASSWORD" -N -e "
    SELECT 'events',       COUNT(*) FROM eventdb.events
    UNION ALL SELECT 'event_images', COUNT(*) FROM eventdb.event_images
    UNION ALL SELECT 'users',        COUNT(*) FROM eventdb.users
    UNION ALL SELECT 'admin_users',  COUNT(*) FROM eventdb.admin_users;"
else
  warn "No dump file given - eventdb created empty."
  warn "Hibernate will build the schema on first start (ddl-auto=update)."
fi

log "Done. Verify with:"
echo "  systemctl status aiims-mysql"
echo "  ss -lnt | grep 3307"