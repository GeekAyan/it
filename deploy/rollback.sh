#!/usr/bin/env bash
# =====================================================================
#  Roll the application back to the previous release.
#  The database is never touched by a rollback.
#
#  Usage: ./deploy/rollback.sh [<git-ref>]
# =====================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

TARGET="${1:-}"
[[ -n "$TARGET" ]] || die "Specify the commit to roll back to: ./deploy/rollback.sh <git-ref>"

command -v docker >/dev/null 2>&1 || die "docker is not installed on this host."
command -v git   >/dev/null 2>&1 || die "git is not installed on this host."

git rev-parse --verify "$TARGET" >/dev/null 2>&1 \
  || die "'$TARGET' is not a valid git ref here."

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
CURRENT_COMMIT="$(git rev-parse --short HEAD)"

# stash any local edits so the checkout cannot fail mid-way
if ! git diff --quiet || ! git diff --cached --quiet; then
  log "Stashing local changes"
  git stash push -u -m "pre-rollback $(date +%Y%m%d-%H%M%S)"
fi

log "Rolling back from $CURRENT_COMMIT to $(git rev-parse --short "$TARGET")"
git checkout "$TARGET"

log "Rebuilding the app image"
docker compose build app

log "Restarting the app container (database untouched)"
docker compose up -d app

cat <<EOF

Rolled back to $(git rev-parse --short "$TARGET").

To return to the previous version later:
  git checkout ${CURRENT_BRANCH}
  git revert HEAD              # or: git cherry-pick the deploy commit
  ./deploy/deploy.sh --no-pull

EOF