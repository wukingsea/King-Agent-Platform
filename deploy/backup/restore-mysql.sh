#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

[[ "${RESTORE_CONFIRM:-}" == "RESTORE" ]] || die "set RESTORE_CONFIRM=RESTORE to confirm restore"
require_env MYSQL_HOST
require_env MYSQL_USER
require_env MYSQL_PASSWORD
require_env RESTORE_TARGET_DATABASE
require_env BACKUP_FILE

TARGET_DB="$RESTORE_TARGET_DATABASE"
[[ "$TARGET_DB" =~ ^[A-Za-z0-9_]+$ ]] || die "invalid target database name: $TARGET_DB"
# prod 名守卫按大小写不敏感匹配，防止 king_ai_PROD 之类变体绕过。
shopt -s nocasematch
[[ "$TARGET_DB" != *prod* || "${RESTORE_ALLOW_PROD_NAME:-0}" == "1" ]] \
  || die "refusing target database containing 'prod': $TARGET_DB"
if [[ "$TARGET_DB" == "${MYSQL_DATABASE:-}" && "${RESTORE_ALLOW_SAME_DB:-0}" != "1" ]]; then
  die "refusing to restore into the source database; set RESTORE_ALLOW_SAME_DB=1 for an isolated drill"
fi
[[ -f "$BACKUP_FILE" ]] || die "backup file not found: $BACKUP_FILE"

MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_SSL_MODE="${MYSQL_SSL_MODE:-REQUIRED}"
# 备份账号只有只读权限；恢复需要建库/建表/写入权限。默认沿用 MYSQL_USER，
# 正式演练必须显式提供 RESTORE_MYSQL_USER/RESTORE_MYSQL_PASSWORD。
RESTORE_USER="${RESTORE_MYSQL_USER:-$MYSQL_USER}"
RESTORE_PASSWORD="${RESTORE_MYSQL_PASSWORD:-$MYSQL_PASSWORD}"
[[ (-n "${RESTORE_MYSQL_USER:-}" && -n "${RESTORE_MYSQL_PASSWORD:-}") || (-z "${RESTORE_MYSQL_USER:-}" && -z "${RESTORE_MYSQL_PASSWORD:-}") ]] \
  || die "RESTORE_MYSQL_USER and RESTORE_MYSQL_PASSWORD must be set together"
export MYSQL_PWD="$RESTORE_PASSWORD"
MYSQL_ARGS=(
  --host "$MYSQL_HOST"
  --port "$MYSQL_PORT"
  --user "$RESTORE_USER"
  --ssl-mode="$MYSQL_SSL_MODE"
)

if [[ "${RESTORE_DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN create database $TARGET_DB and load $BACKUP_FILE"
  exit 0
fi

require_cmd mysql
mysql "${MYSQL_ARGS[@]}" -e "CREATE DATABASE IF NOT EXISTS \`$TARGET_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

case "$BACKUP_FILE" in
  *.gz)
    gzip -dc "$BACKUP_FILE" | mysql "${MYSQL_ARGS[@]}" "$TARGET_DB"
    ;;
  *)
    mysql "${MYSQL_ARGS[@]}" "$TARGET_DB" < "$BACKUP_FILE"
    ;;
esac

FLYWAY_ROWS="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SELECT COUNT(*) FROM flyway_schema_history" "$TARGET_DB")"
[[ "$FLYWAY_ROWS" =~ ^[0-9]+$ && "$FLYWAY_ROWS" -gt 0 ]] || die "restored database has no flyway history"
log "Restore completed into $TARGET_DB; flyway history rows=$FLYWAY_ROWS"
