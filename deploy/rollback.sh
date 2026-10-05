#!/usr/bin/env bash
# =====================================================================
#  Roll the application back to the previously deployed jar.
#  The database is never touched.
#
#  Usage:
#      sudo /opt/aiims/deploy/rollback.sh                  # newest backup
#      sudo /opt/aiims/deploy/rollback.sh app-20261003-120000.jar
# =====================================================================
set -euo pipefail

APP_DIR=/opt/aiims
SERVICE=aiims-app.service
# Must match deploy.sh - the app serves under the /event context path.
HEALTH_URL=http://127.0.0.1:8080/event/login

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Run as root."

if [[ -n "${1:-}" ]]; then
  TARGET="$APP_DIR/backups/$1"
  [[ -f "$TARGET" ]] || die "Backup jar not found: $TARGET"
else
  TARGET="$(ls -1t "$APP_DIR"/backups/app-*.jar 2>/dev/null | head -1 || true)"
  [[ -n "$TARGET" ]] || die "No jar backups found in $APP_DIR/backups - nothing to roll back to."
fi

log "Rolling back to $(basename "$TARGET")"
# Keep the outgoing jar so this rollback is itself reversible.
cp -p "$APP_DIR/app.jar" "$APP_DIR/backups/app-replaced-$(date +%Y%m%d-%H%M%S).jar"
install -m 640 -o aiims -g aiims "$TARGET" "$APP_DIR/app.jar"

log "Restarting $SERVICE"
systemctl restart "$SERVICE"

log "Health check"
for i in $(seq 1 60); do
  if curl -fsS -o /dev/null --max-time 5 "$HEALTH_URL" 2>/dev/null; then
    echo "    healthy after ${i}s"; exit 0
  fi
  sleep 1
done

warn "Still not healthy after 60s. Inspect: journalctl -u $SERVICE -n 50"