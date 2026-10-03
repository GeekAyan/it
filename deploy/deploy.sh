#!/usr/bin/env bash
# =====================================================================
#  Deploy the AIIMS IT Event Portal (Docker Compose stack).
#
#  Order of operations is deliberate:
#    1. back up the database + uploads FIRST
#    2. then pull new code, rebuild the image, restart only the app
#
#  The database container and its volume are never touched, so redeploying
#  cannot lose data. Roll back with deploy/rollback.sh if the app misbehaves.
#
#  Usage:
#    ./deploy/deploy.sh              # backup, git pull, rebuild, restart app
#    ./deploy/deploy.sh --no-pull    # backup, rebuild from current checkout
# =====================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
APP_UID=10001          # must match the uid created in the Dockerfile
mkdir -p backups

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33mWARNING: %s\033[0m\n' "$1"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker is not installed on this host."
docker compose version >/dev/null 2>&1 || die "The 'docker compose' plugin is missing (apt install docker-compose-plugin)."
[[ -f .env ]] || die ".env not found. Run: cp .env.example .env && nano .env && chmod 600 .env"

# A backup is only meaningful if there is a live database to back up.
docker compose ps --status running --services 2>/dev/null | grep -qx db \
  || die "The 'db' container is not running. Start it first: docker compose up -d db"

# --- preflight ---------------------------------------------------------
# The container runs as uid 10001 and must be able to WRITE new uploads.
if [[ -d uploads ]]; then
  OWNER="$(stat -c %u uploads 2>/dev/null || echo 0)"
  if [[ "$OWNER" != "$APP_UID" ]]; then
    warn "uploads/ is owned by uid $OWNER but the container runs as uid $APP_UID."
    warn "New image uploads will fail until you run: sudo chown -R ${APP_UID}:${APP_UID} uploads"
  fi
else
  warn "uploads/ does not exist yet - the uploads backup will be skipped."
fi

# --- 1. back up BEFORE anything else -----------------------------------
log "Backing up the database to backups/eventdb-${STAMP}.sql"
docker compose exec -T db sh -c \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction \
   --routines --triggers --default-character-set=utf8mb4 eventdb' \
  > "backups/eventdb-${STAMP}.sql"
[[ -s "backups/eventdb-${STAMP}.sql" ]] || die "Database backup is empty - ABORTING without deploying."

if [[ -d uploads ]]; then
  log "Backing up uploads to backups/uploads-${STAMP}.tar.gz"
  tar -czf "backups/uploads-${STAMP}.tar.gz" uploads
fi

# keep the 10 most recent backup pairs, so the disk cannot fill up
ls -1t backups/eventdb-*.sql 2>/dev/null | tail -n +11 | xargs -r rm -f --
ls -1t backups/uploads-*.tar.gz 2>/dev/null | tail -n +11 | xargs -r rm -f --

# --- 2. fetch new code --------------------------------------------------
if [[ "${1:-}" != "--no-pull" ]]; then
  log "Pulling latest code from git"
  git pull --ff-only
fi

# --- 3. rebuild the app image (the db is never rebuilt or restarted) ----
log "Building the app image"
docker compose build app

log "Restarting the app container (database untouched)"
docker compose up -d app

# --- 4. report ---------------------------------------------------------
log "Current stack status"
docker compose ps

cat <<EOF

Next steps:
  * Watch startup :  docker compose logs -f --tail=100 app
  * Check Apache  :  curl -I http://127.0.0.1/login
  * Roll back     :  ./deploy/rollback.sh
  * Backups       :  ls -1t backups/

EOF