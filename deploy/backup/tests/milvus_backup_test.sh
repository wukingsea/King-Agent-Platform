#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(
  MILVUS_HOST=127.0.0.1 \
  MILVUS_TOKEN=super-secret-token \
  MILVUS_BACKUP_MINIO_ADDRESS=127.0.0.1 \
  MILVUS_BACKUP_MINIO_ACCESS_KEY=minio-user \
  MILVUS_BACKUP_MINIO_SECRET_KEY=minio-secret \
  MILVUS_BACKUP_MINIO_BUCKET=backup \
  BACKUP_LABEL=test-milvus \
  BACKUP_DRY_RUN=1 \
  "$ROOT/milvus-backup.sh" 2>&1
)"

printf '%s\n' "$OUT" | grep -q 'DRY-RUN milvus-backup create -n test-milvus'
if printf '%s\n' "$OUT" | grep -q 'super-secret-token'; then
  echo "secret leaked to backup output" >&2
  exit 1
fi
if printf '%s\n' "$OUT" | grep -q 'minio-secret'; then
  echo "minio secret leaked to backup output" >&2
  exit 1
fi

echo "milvus backup test passed"
