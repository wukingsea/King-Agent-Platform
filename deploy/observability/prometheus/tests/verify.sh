#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULES="$ROOT/alerts.yml"
CONFIG="$ROOT/prometheus.yml"

[[ -f "$RULES" ]] || { echo "missing $RULES" >&2; exit 1; }
[[ -f "$CONFIG" ]] || { echo "missing $CONFIG" >&2; exit 1; }

if command -v promtool >/dev/null 2>&1; then
  promtool check rules "$RULES"
  (cd "$ROOT" && promtool check config prometheus.yml)
elif command -v docker >/dev/null 2>&1; then
  docker run --rm -w /work -v "$ROOT:/work:ro" --entrypoint promtool \
    prom/prometheus:v2.55.1 check rules /work/alerts.yml
  docker run --rm -w /work -v "$ROOT:/work:ro" --entrypoint promtool \
    prom/prometheus:v2.55.1 check config prometheus.yml
else
  echo "NOT RUN: promtool and docker are unavailable" >&2
  exit 2
fi

required=(
  KingAiApiDown
  KingAiApiTargetMissing
  KingAiHttp5xxHigh
  KingAiChatTtftSlow
  KingAiSseCapacityHigh
  KingAiChatQuotaRejectionHigh
  KingAiIndexFailures
  KingAiIndexLag
  KingAiMysqlPoolHigh
  KingAiRedisErrors
  KingAiMilvusSearchErrors
  KingAiAuditWriteFailures
)
for alert in "${required[@]}"; do
  grep -q "alert: $alert" "$RULES" || { echo "missing alert: $alert" >&2; exit 1; }
done

alert_count="$(grep -c '^      - alert:' "$RULES")"
runbook_count="$(grep -c 'runbook_url:' "$RULES")"
[[ "$alert_count" -eq "$runbook_count" ]] || {
  echo "every alert must have a runbook_url (alerts=$alert_count runbooks=$runbook_count)" >&2
  exit 1
}

echo "prometheus rules verified: $alert_count alerts"
