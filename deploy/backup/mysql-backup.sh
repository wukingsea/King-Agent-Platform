#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd mysqldump
require_cmd gzip
require_cmd mysql
require_env MYSQL_HOST
require_env MYSQL_DATABASE
require_env MYSQL_USER
require_env MYSQL_PASSWORD

MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_SSL_MODE="${MYSQL_SSL_MODE:-REQUIRED}"
BACKUP_OUT_DIR="${BACKUP_OUT_DIR:-/backup}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
LABEL="${BACKUP_LABEL:-mysql-$(timestamp)}"
TARGET_DIR="$BACKUP_OUT_DIR/$LABEL"
DUMP_FILE="$TARGET_DIR/$MYSQL_DATABASE.sql.gz"
MANIFEST_FILE="$TARGET_DIR/manifest.json"
export MYSQL_PWD="$MYSQL_PASSWORD"
umask 077
mkdir -p "$TARGET_DIR"

MYSQL_ARGS=(
  --host "$MYSQL_HOST"
  --port "$MYSQL_PORT"
  --user "$MYSQL_USER"
  --ssl-mode="$MYSQL_SSL_MODE"
)

FLYWAY_VERSION="$(mysql "${MYSQL_ARGS[@]}" -N -B \
  -e "SELECT CONCAT(version, ':', description) FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1" \
  "$MYSQL_DATABASE" 2>/dev/null || true)"

if [[ "${BACKUP_DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN mysqldump --single-transaction --source-data=2 ${MYSQL_ARGS[*]} $MYSQL_DATABASE | gzip -9 > $DUMP_FILE"
  exit 0
fi

# jq 仅在真实备份路径需要（生成 manifest.json）；放在 dry-run 退出之后，
# 使 BACKUP_DRY_RUN=1 的冒烟验证在无 jq 环境也能执行。
require_cmd jq

# 不使用 --databases：该选项会写入 CREATE DATABASE/USE 源库名，导致恢复到
# king_ai_restore 时仍然写回源库。去掉后 dump 只含表定义与数据，可按目标库名恢复。
mysqldump \
  --single-transaction \
  --quick \
  --routines \
  --triggers \
  --events \
  --set-gtid-purged=OFF \
  --source-data=2 \
  --default-character-set=utf8mb4 \
  "${MYSQL_ARGS[@]}" \
  "$MYSQL_DATABASE" | gzip -9 > "$DUMP_FILE"

SHA256="$(sha256 "$DUMP_FILE")"
SIZE_BYTES="$(wc -c < "$DUMP_FILE" | tr -d ' ')"
BINLOG_HEADER="$(gzip -dc "$DUMP_FILE" | grep -m1 '^-- CHANGE REPLICATION SOURCE TO' || true)"
FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
  --arg label "$LABEL" \
  --arg database "$MYSQL_DATABASE" \
  --arg file "$(basename "$DUMP_FILE")" \
  --arg sha256 "$SHA256" \
  --arg flywayVersion "$FLYWAY_VERSION" \
  --arg binlog "$BINLOG_HEADER" \
  --arg finishedAt "$FINISHED_AT" \
  --argjson sizeBytes "${SIZE_BYTES:-0}" \
  '{
    label: $label,
    database: $database,
    file: $file,
    sha256: $sha256,
    sizeBytes: $sizeBytes,
    flywayVersion: $flywayVersion,
    binlog: $binlog,
    finishedAt: $finishedAt
  }' > "$MANIFEST_FILE"

if [[ -n "${BACKUP_S3_URI:-}" ]]; then
  s3_upload "$DUMP_FILE" "$LABEL/$(basename "$DUMP_FILE")"
  s3_upload "$MANIFEST_FILE" "$LABEL/manifest.json"
fi

prune_dirs "$BACKUP_OUT_DIR" 'mysql-' "$BACKUP_RETENTION_DAYS"
log "MySQL backup completed: $DUMP_FILE (${SIZE_BYTES} bytes, sha256=$SHA256)"
