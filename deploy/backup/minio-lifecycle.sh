#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_env MC_ALIAS
require_env BACKUP_BUCKET
require_env EXPORT_BUCKET
require_cmd mc

run() {
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    printf 'mc %s\n' "$*"
  else
    mc "$@"
  fi
}

run version enable "$MC_ALIAS/$BACKUP_BUCKET"
run version enable "$MC_ALIAS/$EXPORT_BUCKET"
run ilm rule add --expire-days 30 "$MC_ALIAS/$BACKUP_BUCKET"
run ilm rule add --expire-days 7 --prefix exports/ "$MC_ALIAS/$EXPORT_BUCKET"
run ilm rule ls "$MC_ALIAS/$BACKUP_BUCKET"
run ilm rule ls "$MC_ALIAS/$EXPORT_BUCKET"
