#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_env MILVUS_HOST
require_env MILVUS_BACKUP_MINIO_ADDRESS
require_env MILVUS_BACKUP_MINIO_ACCESS_KEY
require_env MILVUS_BACKUP_MINIO_SECRET_KEY
require_env MILVUS_BACKUP_MINIO_BUCKET

# 配置值是 YAML 标量，统一走 JSON 引号编码，避免特殊字符破坏结构。
quote() { jq -Rn --arg value "$1" '$value'; }

MILVUS_PORT="${MILVUS_PORT:-19530}"
MILVUS_TOKEN="${MILVUS_TOKEN:-}"
BACKUP_BIN="${MILVUS_BACKUP_BIN:-milvus-backup}"
LABEL="${BACKUP_LABEL:-milvus-$(timestamp)}"
BACKUP_OUT_DIR="${BACKUP_OUT_DIR:-/backup}"
MANIFEST_DIR="$BACKUP_OUT_DIR/$LABEL"

if [[ "${BACKUP_DRY_RUN:-0}" == "1" ]]; then
  log "DRY-RUN milvus-backup create -n $LABEL (milvus=$MILVUS_HOST:$MILVUS_PORT, bucket=$MILVUS_BACKUP_MINIO_BUCKET)"
  exit 0
fi

# jq 仅在真实备份路径需要（quote 编码与 manifest 生成）；dry-run 不生成配置文件，无需 jq。
require_cmd jq

command -v "$BACKUP_BIN" >/dev/null 2>&1 || die "missing milvus-backup binary: $BACKUP_BIN"
umask 077
mkdir -p "$MANIFEST_DIR"
CONFIG_FILE="$MANIFEST_DIR/milvus-backup.yaml"
cleanup() {
  rm -f "$CONFIG_FILE"
}
trap cleanup EXIT

cat > "$CONFIG_FILE" <<EOF
log:
  level: info
  console: true
milvus:
  address: $(quote "$MILVUS_HOST")
  port: ${MILVUS_PORT}
  authorizationEnabled: $([[ -n "$MILVUS_TOKEN" ]] && echo true || echo false)
  token: $(quote "$MILVUS_TOKEN")
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

"$BACKUP_BIN" --config "$CONFIG_FILE" create -n "$LABEL"
"$BACKUP_BIN" --config "$CONFIG_FILE" get -n "$LABEL" > "$MANIFEST_DIR/backup-info.txt"

jq -n \
  --arg label "$LABEL" \
  --arg milvus "$MILVUS_HOST:$MILVUS_PORT" \
  --arg bucket "$MILVUS_BACKUP_MINIO_BUCKET" \
  --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{label: $label, milvus: $milvus, bucket: $bucket, finishedAt: $finishedAt}' \
  > "$MANIFEST_DIR/manifest.json"

if [[ -n "${BACKUP_S3_URI:-}" ]]; then
  s3_upload "$MANIFEST_DIR/manifest.json" "$LABEL/milvus-manifest.json"
  s3_upload "$MANIFEST_DIR/backup-info.txt" "$LABEL/milvus-backup-info.txt"
fi

log "Milvus backup completed: $LABEL"
