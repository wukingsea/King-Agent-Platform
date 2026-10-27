#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
printf 'not-a-real-dump' | gzip > "$TMP/dump.sql.gz"

expect_fail() {
  if "$@" >/dev/null 2>&1; then
    echo "expected command to fail: $*" >&2
    exit 1
  fi
}

expect_fail env MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_prod BACKUP_FILE="$TMP/dump.sql.gz" \
  "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai BACKUP_FILE="$TMP/dump.sql.gz" \
  "$ROOT/restore-mysql.sh"

env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh" >/dev/null

echo "restore guard test passed"
