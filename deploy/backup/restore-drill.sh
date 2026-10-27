#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_env DRILL_NAMESPACE
require_env MYSQL_DUMP
if [[ "${SKIP_MILVUS:-0}" != "1" ]]; then
  require_env MILVUS_BACKUP_NAME
  require_env MILVUS_BACKUP_MINIO_ADDRESS
  require_env MILVUS_BACKUP_MINIO_ACCESS_KEY
  require_env MILVUS_BACKUP_MINIO_SECRET_KEY
  require_env MILVUS_BACKUP_MINIO_BUCKET
fi

CHART_DIR="${CHART_DIR:-$SCRIPT_DIR/../helm/king-ai}"
RELEASE=king-ai-drill
# chart 的 fullname 在 release 名包含 chart 名时取 release 名，因此资源前缀等于 RELEASE。
PREFIX="$RELEASE"
NAMESPACE="$DRILL_NAMESPACE"
MYSQL_LOCAL_PORT="${DRILL_MYSQL_PORT:-13306}"
MILVUS_LOCAL_PORT="${DRILL_MILVUS_PORT:-19531}"
API_LOCAL_PORT="${DRILL_API_PORT:-18080}"
START_EPOCH="$(date +%s)"
KEEP_NAMESPACE="${KEEP_NAMESPACE:-0}"
SKIP_MILVUS="${SKIP_MILVUS:-0}"

for cmd in kubectl helm curl jq mysql base64; do
  require_cmd "$cmd"
done

kill_pf() {
  local pid
  for pid in "$@"; do
    [[ -z "$pid" ]] || kill "$pid" >/dev/null 2>&1 || true
  done
}

cleanup() {
  kill_pf "${MYSQL_PF:-}" "${MILVUS_PF:-}" "${API_PF:-}"
  if [[ "$KEEP_NAMESPACE" != "1" ]]; then
    kubectl delete namespace "$NAMESPACE" --wait=false >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_tcp() {
  local host=$1 port=$2 label=$3
  local attempt
  for attempt in $(seq 1 60); do
    if (echo > "/dev/tcp/$host/$port") 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  die "timeout waiting for $label on $host:$port after $attempt attempts"
}

wait_http() {
  local url=$1 label=$2
  local attempt
  for attempt in $(seq 1 90); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  die "timeout waiting for $label: $url after $attempt attempts"
}

wait_milvus() {
  local url="http://127.0.0.1:$MILVUS_LOCAL_PORT/v2/vectordb/collections/list"
  local attempt
  for attempt in $(seq 1 90); do
    # Milvus 的 /healthz 在 9091 端口；这里走 19530 的 RESTful API，
    # 确认 REST 网关和元数据都已就绪后再恢复。
    if curl -fsS -X POST "$url" -H 'Content-Type: application/json' -d '{}' \
        >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  die "timeout waiting for milvus REST API on 127.0.0.1:$MILVUS_LOCAL_PORT after $attempt attempts"
}

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
helm upgrade --install "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  -f "$CHART_DIR/tests/values-drill.yaml" \
  --wait --timeout 15m

MYSQL_ROOT_PASSWORD="$(
  kubectl -n "$NAMESPACE" get secret "$PREFIX-db" -o jsonpath='{.data.root-password}' 2>/dev/null \
    | base64 -d 2>/dev/null || true
)"
[[ -n "$MYSQL_ROOT_PASSWORD" ]] || die "cannot read drill mysql root password from secret $PREFIX-db"

kubectl -n "$NAMESPACE" port-forward "svc/$PREFIX-mysql" "$MYSQL_LOCAL_PORT:3306" \
  >/tmp/drill-mysql-pf.log 2>&1 &
MYSQL_PF=$!
wait_tcp 127.0.0.1 "$MYSQL_LOCAL_PORT" mysql

if [[ "$SKIP_MILVUS" != "1" ]]; then
  kubectl -n "$NAMESPACE" port-forward "svc/$PREFIX-milvus" "$MILVUS_LOCAL_PORT:19530" \
    >/tmp/drill-milvus-pf.log 2>&1 &
  MILVUS_PF=$!
  wait_tcp 127.0.0.1 "$MILVUS_LOCAL_PORT" milvus
  wait_milvus
fi

MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD" \
RESTORE_CONFIRM=RESTORE \
MYSQL_HOST=127.0.0.1 MYSQL_PORT="$MYSQL_LOCAL_PORT" MYSQL_DATABASE=king_ai \
MYSQL_USER=root MYSQL_SSL_MODE=DISABLED \
RESTORE_TARGET_DATABASE=king_ai RESTORE_ALLOW_SAME_DB=1 \
BACKUP_FILE="$MYSQL_DUMP" \
"$SCRIPT_DIR/restore-mysql.sh"

if [[ "$SKIP_MILVUS" != "1" ]]; then
  MILVUS_HOST=127.0.0.1 MILVUS_PORT="$MILVUS_LOCAL_PORT" \
  MILVUS_BACKUP_NAME="$MILVUS_BACKUP_NAME" \
  MILVUS_RESTORE_CONFIRM=yes \
  MILVUS_RESTORE_SUFFIX="" \
  "$SCRIPT_DIR/restore-milvus.sh"
fi

kubectl -n "$NAMESPACE" scale "deployment/$PREFIX-api" --replicas=1
kubectl -n "$NAMESPACE" rollout status "deployment/$PREFIX-api" --timeout=300s
kubectl -n "$NAMESPACE" port-forward "svc/$PREFIX-api" "$API_LOCAL_PORT:8080" \
  >/tmp/drill-api-pf.log 2>&1 &
API_PF=$!
wait_http "http://127.0.0.1:$API_LOCAL_PORT/api/health" api

VERIFY_MYSQL_HOST=127.0.0.1 VERIFY_MYSQL_PORT="$MYSQL_LOCAL_PORT" \
VERIFY_MYSQL_DATABASE=king_ai VERIFY_MYSQL_USER=root \
VERIFY_MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD" VERIFY_MYSQL_SSL_MODE=DISABLED \
VERIFY_API_URL="http://127.0.0.1:$API_LOCAL_PORT" \
VERIFY_MILVUS_URL=$([[ "$SKIP_MILVUS" == "1" ]] && echo "" || echo "http://127.0.0.1:$MILVUS_LOCAL_PORT") \
VERIFY_REPORT="$PWD/drill-verify-report.json" \
"$SCRIPT_DIR/verify.sh"

END_EPOCH="$(date +%s)"
RTO_SECONDS=$((END_EPOCH - START_EPOCH))
jq -n \
  --arg namespace "$DRILL_NAMESPACE" \
  --arg mysqlDump "$MYSQL_DUMP" \
  --arg milvusBackup "${MILVUS_BACKUP_NAME:-}" \
  --argjson rtoSeconds "$RTO_SECONDS" \
  --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{namespace: $namespace, mysqlDump: $mysqlDump, milvusBackup: $milvusBackup, rtoSeconds: $rtoSeconds, finishedAt: $finishedAt}' \
  > "$PWD/drill-report.json"

if (( RTO_SECONDS > 3600 )); then
  die "RTO ${RTO_SECONDS}s exceeds 3600s"
fi
log "Drill passed; RTO=${RTO_SECONDS}s; report=$PWD/drill-report.json"
