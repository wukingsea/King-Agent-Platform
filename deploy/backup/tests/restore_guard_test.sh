#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../lib.sh
source "$ROOT/lib.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
printf 'not-a-real-dump' | gzip > "$TMP/dump.sql.gz"
DUMP_SHA="$(sha256 "$TMP/dump.sql.gz")"
printf '{\n  "file": "dump.sql.gz",\n  "sha256": "%s"\n}\n' "$DUMP_SHA" > "$TMP/manifest.json"

expect_fail() {
  if "$@" >/dev/null 2>&1; then
    echo "expected command to fail: $*" >&2
    exit 1
  fi
}

expect_fail env MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_prod BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_PROD BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore RESTORE_MYSQL_PASSWORD=other-pass \
  BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh"

expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_MANIFEST_FILE="$TMP/missing-manifest.json" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh"

printf '{\n  "file": "dump.sql.gz",\n  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"\n}\n' \
  > "$TMP/bad-checksum.json"
expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_MANIFEST_FILE="$TMP/bad-checksum.json" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh"

printf '{\n  "file": "another-dump.sql.gz",\n  "sha256": "%s"\n}\n' "$DUMP_SHA" \
  > "$TMP/wrong-file.json"
expect_fail env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_MANIFEST_FILE="$TMP/wrong-file.json" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh"

env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh" >/dev/null

env RESTORE_CONFIRM=RESTORE MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
  RESTORE_TARGET_DATABASE=king_ai_restore BACKUP_FILE="$TMP/dump.sql.gz" \
  RESTORE_MANIFEST_FILE="$TMP/missing-manifest.json" RESTORE_SKIP_CHECKSUM=1 \
  RESTORE_DRY_RUN=1 "$ROOT/restore-mysql.sh" >/dev/null

echo "restore guard test passed"
