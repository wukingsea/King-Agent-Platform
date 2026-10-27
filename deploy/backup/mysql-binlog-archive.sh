#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd mysql
require_cmd mysqlbinlog
require_cmd gzip
require_env MYSQL_HOST
require_env MYSQL_USER
require_env MYSQL_PASSWORD

MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_SSL_MODE="${MYSQL_SSL_MODE:-REQUIRED}"
BACKUP_OUT_DIR="${BACKUP_OUT_DIR:-/backup}"
BINLOG_DIR="$BACKUP_OUT_DIR/binlog"
STATE_FILE="$BINLOG_DIR/.last-binlog"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
export MYSQL_PWD="$MYSQL_PASSWORD"
umask 077
mkdir -p "$BINLOG_DIR"

LAST="$(cat "$STATE_FILE" 2>/dev/null || true)"
MYSQL_ARGS=(
  --host "$MYSQL_HOST"
  --port "$MYSQL_PORT"
  --user "$MYSQL_USER"
  --ssl-mode="$MYSQL_SSL_MODE"
)

if [[ "${BACKUP_DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN would archive binlogs after '${LAST}' from $MYSQL_HOST:$MYSQL_PORT to $BINLOG_DIR"
  exit 0
fi

# 先取列表再处理：进程替换里的 mysql 失败不会触发 set -e，会静默变成"无日志可归档"。
LOG_LIST="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SHOW BINARY LOGS")" \
  || die "cannot list binary logs from $MYSQL_HOST:$MYSQL_PORT"

while read -r LOG_NAME _REST; do
  [[ -n "$LOG_NAME" ]] || continue
  [[ "$LOG_NAME" =~ ^mysql-bin\.[0-9]+$ ]] || continue
  if [[ -n "$LAST" ]]; then
    LAST_SEQ="${LAST#mysql-bin.}"
    SEQ="${LOG_NAME#mysql-bin.}"
    [[ "$LAST_SEQ" =~ ^[0-9]+$ && "$SEQ" =~ ^[0-9]+$ ]] \
      || die "unexpected binlog sequence: last='$LAST' current='$LOG_NAME'"
    # 按数值比较，避免序号从 999999 进位到 1000000 后字典序判断失效。
    if (( 10#$SEQ <= 10#$LAST_SEQ )); then
      continue
    fi
  fi
  TARGET="$BINLOG_DIR/$LOG_NAME.sql.gz"
  mysqlbinlog \
    --read-from-remote-server \
    "${MYSQL_ARGS[@]}" \
    "$LOG_NAME" | gzip -9 > "$TARGET"
  SHA256="$(sha256 "$TARGET")"
  if [[ -n "${BACKUP_S3_URI:-}" ]]; then
    s3_upload "$TARGET" "binlog/$LOG_NAME.sql.gz"
  fi
  printf '%s\n' "$LOG_NAME" > "$STATE_FILE"
  log "Archived binlog $LOG_NAME (sha256=$SHA256)"
done <<< "$LOG_LIST"

prune_files "$BINLOG_DIR" 'mysql-bin.*.sql.gz' "$RETENTION_DAYS"
log "Binlog archive completed; last=$LAST"
