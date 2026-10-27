#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DASH="$ROOT/dashboards"

if ! command -v jq >/dev/null 2>&1; then
  echo "NOT RUN: jq is required to validate dashboards" >&2
  exit 2
fi

expected=(
  chat-overview.json
  rag-knowledge.json
  platform-health.json
)
for name in "${expected[@]}"; do
  [[ -f "$DASH/$name" ]] || { echo "missing $DASH/$name" >&2; exit 1; }
done

if grep -R -q '__inputs' "$DASH"; then
  echo "dashboards must not contain __inputs" >&2
  exit 1
fi
if grep -R -q '\${DS_' "$DASH"; then
  echo "dashboards must not reference \${DS_ template variables" >&2
  exit 1
fi

for name in "${expected[@]}"; do
  file="$DASH/$name"
  jq empty "$file" || { echo "invalid JSON: $file" >&2; exit 1; }
  jq -e '.uid | type == "string" and length > 0' "$file" >/dev/null \
    || { echo "missing uid: $file" >&2; exit 1; }
  jq -e '.title | type == "string" and length > 0' "$file" >/dev/null \
    || { echo "missing title: $file" >&2; exit 1; }
  jq -e '(.panels | length) >= 6' "$file" >/dev/null \
    || { echo "needs at least 6 panels: $file" >&2; exit 1; }
  jq -e '([.panels[].id] | length) == ([.panels[].id] | unique | length)' "$file" >/dev/null \
    || { echo "duplicate panel ids: $file" >&2; exit 1; }
  jq -e 'all(.panels[]; .datasource.uid == "prometheus")' "$file" >/dev/null \
    || { echo "panel datasource must be prometheus: $file" >&2; exit 1; }
  jq -e 'all(.panels[]; (.targets | length) > 0 and all(.targets[]; (.expr | length) > 0))' \
    "$file" >/dev/null \
    || { echo "panel target without expr: $file" >&2; exit 1; }
done

uids="$(jq -r '.uid' "$DASH"/*.json | sort)"
[[ "$(printf '%s\n' "$uids" | sort -u | wc -l)" -eq 3 ]] \
  || { echo "dashboard uids must be unique" >&2; exit 1; }

grep -q 'uid: prometheus' "$ROOT/provisioning/datasources/prometheus.yml" \
  || { echo "datasource uid must be prometheus" >&2; exit 1; }
grep -q 'url: http://prometheus:9090' "$ROOT/provisioning/datasources/prometheus.yml" \
  || { echo "datasource url must be http://prometheus:9090" >&2; exit 1; }
grep -q 'path: /var/lib/grafana/dashboards' "$ROOT/provisioning/dashboards/dashboards.yml" \
  || { echo "dashboard provider path mismatch" >&2; exit 1; }

require_expr() {
  local file="$1"
  shift
  local needle
  for needle in "$@"; do
    grep -q -- "$needle" "$file" \
      || { echo "missing expression fragment: $needle (in $file)" >&2; exit 1; }
  done
}

require_expr "$DASH/chat-overview.json" \
  'king_ai_chat_active' \
  'king_ai_sse_capacity' \
  'king_ai_chat_requests_total' \
  'king_ai_chat_ttft_seconds_bucket' \
  'king_ai_chat_duration_seconds_bucket' \
  'king_ai_chat_rejected_total' \
  'king_ai_chat_tokens_total' \
  'king_ai_chat_tool_calls_total'

require_expr "$DASH/rag-knowledge.json" \
  'king_ai_rag_retrieval_seconds_bucket' \
  'king_ai_rag_empty_total' \
  'king_ai_index_jobs_total' \
  'king_ai_index_pending' \
  'king_ai_index_failed' \
  'king_ai_index_lag_seconds' \
  'king_ai_milvus_latency_seconds_bucket' \
  'king_ai_mcp_calls_total'

require_expr "$DASH/platform-health.json" \
  'king_ai:http_5xx_ratio5m' \
  'http_server_requests_seconds_bucket' \
  'jvm_memory_used_bytes' \
  'king_ai:mysql_pool_usage' \
  'king_ai_redis_up' \
  'king_ai_audit_write_failures_total' \
  'up{job=\"king-ai-api\"}'

panels="$(for name in "${expected[@]}"; do jq '.panels | length' "$DASH/$name"; done \
  | awk '{sum += $1} END {print sum}')"
echo "grafana dashboards verified: 3 dashboards, $panels panels"
