#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd mysql
require_cmd curl
require_cmd jq
require_env VERIFY_MYSQL_HOST
require_env VERIFY_MYSQL_DATABASE
require_env VERIFY_MYSQL_USER
require_env VERIFY_MYSQL_PASSWORD
require_env VERIFY_API_URL

MYSQL_PORT="${VERIFY_MYSQL_PORT:-3306}"
MYSQL_SSL_MODE="${VERIFY_MYSQL_SSL_MODE:-REQUIRED}"
REPORT="${VERIFY_REPORT:-$PWD/verify-report.json}"
export MYSQL_PWD="$VERIFY_MYSQL_PASSWORD"
MYSQL_ARGS=(
  --host "$VERIFY_MYSQL_HOST"
  --port "$MYSQL_PORT"
  --user "$VERIFY_MYSQL_USER"
  --ssl-mode="$MYSQL_SSL_MODE"
  --database "$VERIFY_MYSQL_DATABASE"
)

CHECKS='{}'
FAILED=0

add_check() {
  local name=$1 value=$2 status=$3
  CHECKS="$(jq --arg name "$name" --arg value "$value" --arg status "$status" \
    '. + {($name): {value: $value, status: $status}}' <<< "$CHECKS")"
  [[ "$status" == "ok" || "$status" == "skipped" ]] || FAILED=1
}

count() {
  mysql "${MYSQL_ARGS[@]}" -N -B -e "$1" 2>/dev/null || echo "error"
}

add_numeric_check() {
  local name=$1 value=$2
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    add_check "$name" "$value" "ok"
  else
    add_check "$name" "$value" "failed"
  fi
}

FLYWAY="$(count 'SELECT COUNT(*) FROM flyway_schema_history')"
if [[ "$FLYWAY" =~ ^[0-9]+$ && "$FLYWAY" -gt 0 ]]; then
  add_check "flyway" "$FLYWAY" "ok"
else
  add_check "flyway" "$FLYWAY" "failed"
fi
add_numeric_check "users" "$(count 'SELECT COUNT(*) FROM king_ai_user')"
add_numeric_check "spaces" "$(count 'SELECT COUNT(*) FROM king_ai_space')"
add_numeric_check "sessions" "$(count 'SELECT COUNT(*) FROM king_ai_chat_session')"
add_numeric_check "messages" "$(count 'SELECT COUNT(*) FROM king_ai_chat_message')"

BLANK="$(count "SELECT COUNT(*) FROM king_ai_chat_message WHERE role = 'assistant' AND (content IS NULL OR content = '')")"
if [[ "$BLANK" == "0" ]]; then
  add_check "assistant_blank" "$BLANK" "ok"
else
  add_check "assistant_blank" "$BLANK" "failed"
fi

ORPHANS="$(count 'SELECT COUNT(*) FROM king_ai_chat_message m LEFT JOIN king_ai_chat_session s ON s.id = m.session_id WHERE s.id IS NULL')"
if [[ "$ORPHANS" == "0" ]]; then
  add_check "orphan_messages" "$ORPHANS" "ok"
else
  add_check "orphan_messages" "$ORPHANS" "failed"
fi

SAMPLE="$(count "SELECT COUNT(*) FROM (SELECT content FROM king_ai_chat_message WHERE content IS NOT NULL AND content <> '' ORDER BY id DESC LIMIT 5) t")"
if [[ "$SAMPLE" =~ ^[0-9]+$ && "$SAMPLE" -gt 0 ]]; then
  add_check "recent_messages" "$SAMPLE" "ok"
else
  add_check "recent_messages" "$SAMPLE" "failed"
fi

if HEALTH="$(curl -fsS "$VERIFY_API_URL/api/health" 2>/dev/null)"; then
  HEALTH_STATUS="$(jq -r '.data.status // .status // "UNKNOWN"' <<< "$HEALTH")"
else
  HEALTH_STATUS="UNREACHABLE"
fi
if [[ "$HEALTH_STATUS" == "UP" ]]; then
  add_check "api_health" "$HEALTH_STATUS" "ok"
else
  add_check "api_health" "$HEALTH_STATUS" "failed"
fi

if [[ -n "${VERIFY_MILVUS_URL:-}" ]]; then
  INDEX=0
  while read -r COLLECTION; do
    [[ -n "$COLLECTION" ]] || continue
    VECTOR_RESPONSE="$(
      curl -fsS -X POST "$VERIFY_MILVUS_URL/v2/vectordb/entities/query" \
        -H "Content-Type: application/json" \
        ${VERIFY_MILVUS_TOKEN:+-H "Authorization: Bearer $VERIFY_MILVUS_TOKEN"} \
        -d "{\"collectionName\":\"$COLLECTION\",\"filter\":\"\",\"outputFields\":[\"count(*)\"]}" \
        2>/dev/null || true
    )"
    VECTOR_COUNT="$(jq -r '.data[0]["count(*)"] // empty' <<< "${VECTOR_RESPONSE:-{\}}" 2>/dev/null || true)"
    add_numeric_check "vector_count_${INDEX}" "$VECTOR_COUNT"
    INDEX=$((INDEX + 1))
  done < <(count "SELECT vector_collection FROM king_ai_knowledge_base WHERE vector_collection IS NOT NULL AND vector_collection <> ''")
else
  add_check "vector_count" "not-run" "skipped"
fi

jq -n --argjson checks "$CHECKS" --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{finishedAt: $finishedAt, checks: $checks}' > "$REPORT"
cat "$REPORT"

if [[ "$FAILED" != "0" ]]; then
  log "Verification failed; report=$REPORT"
  exit 1
fi
log "Verification passed; report=$REPORT"
