#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

[[ "${MILVUS_RESTORE_CONFIRM:-}" == "yes" ]] || die "set MILVUS_RESTORE_CONFIRM=yes to confirm restore"
require_env MILVUS_HOST
require_env MILVUS_BACKUP_NAME
require_env MILVUS_BACKUP_MINIO_ADDRESS
require_env MILVUS_BACKUP_MINIO_ACCESS_KEY
require_env MILVUS_BACKUP_MINIO_SECRET_KEY
require_env MILVUS_BACKUP_MINIO_BUCKET

# 与 milvus-backup.sh 共用同一套 YAML 标量编码，避免注入与断行。
quote() { jq -Rn --arg value "$1" '$value'; }

BACKUP_BIN="${MILVUS_BACKUP_BIN:-milvus-backup}"
# 注意用 ${VAR-default} 而不是 ${VAR:-default}：演练需要显式传空串，
# 表示"恢复到原集合名"，不能用时间戳后缀生成一套对不上元数据的新集合。
SUFFIX="${MILVUS_RESTORE_SUFFIX-_restore_$(timestamp)}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
CONFIG="$TMP/milvus-backup.yaml"
umask 077

cat > "$CONFIG" <<EOF
log:
  level: info
  console: true
milvus:
  address: $(quote "$MILVUS_HOST")
  port: ${MILVUS_PORT:-19530}
  authorizationEnabled: $([[ -n "${MILVUS_TOKEN:-}" ]] && echo true || echo false)
  token: $(quote "${MILVUS_TOKEN:-}")
minio:
  storageType: minio
  address: $(quote "$MILVUS_BACKUP_MINIO_ADDRESS")
  port: ${MILVUS_BACKUP_MINIO_PORT:-9000}
  accessKeyID: $(quote "$MILVUS_BACKUP_MINIO_ACCESS_KEY")
  secretAccessKey: $(quote "$MILVUS_BACKUP_MINIO_SECRET_KEY")
  bucketName: $(quote "$MILVUS_BACKUP_MINIO_BUCKET")
  rootPath: $(quote "${MILVUS_BACKUP_MINIO_ROOT_PATH:-backup}")
  useSSL: ${MILVUS_BACKUP_MINIO_USE_SSL:-false}
EOF

if [[ "${MILVUS_RESTORE_DRY_RUN:-0}" == "1" ]]; then
  if [[ -n "$SUFFIX" ]]; then
    log "DRY-RUN milvus-backup restore -n $MILVUS_BACKUP_NAME --restore_collection_suffix $SUFFIX"
  else
    log "DRY-RUN milvus-backup restore -n $MILVUS_BACKUP_NAME (keep collection names)"
  fi
  exit 0
fi

# jq 仅在真实恢复路径需要（quote 编码与配置生成）；dry-run 不生成配置文件，无需 jq。
require_cmd jq

command -v "$BACKUP_BIN" >/dev/null 2>&1 || die "missing milvus-backup binary: $BACKUP_BIN"
RESTORE_ARGS=(--config "$CONFIG" restore -n "$MILVUS_BACKUP_NAME")
if [[ -n "$SUFFIX" ]]; then
  RESTORE_ARGS+=(--restore_collection_suffix "$SUFFIX")
fi
"$BACKUP_BIN" "${RESTORE_ARGS[@]}"
log "Milvus restore completed: backup=$MILVUS_BACKUP_NAME suffix=${SUFFIX:-<none>}"
