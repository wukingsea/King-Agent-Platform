#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"

cat > "$TMP/bin/mysql" <<'EOF'
#!/usr/bin/env bash
# 分支按 verify.sh 实际 SQL 文本匹配，顺序从最具体到最宽泛。
case "$*" in
  *"flyway_schema_history"*) echo "12";;
  *"role = 'assistant'"*) echo "0";;
  *"LEFT JOIN king_ai_chat_session"*) echo "0";;
  *"ORDER BY id DESC LIMIT 5"*) echo "5";;
  *"king_ai_user"*) echo "3";;
  *"king_ai_space"*) echo "1";;
  *"king_ai_chat_session"*) echo "4";;
  *"king_ai_chat_message"*) echo "9";;
  *"vector_collection"*) echo "kb_collection_1";;
  *) echo "0";;
esac
EOF
chmod +x "$TMP/bin/mysql"

cat > "$TMP/bin/curl" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *"/api/health"* ]]; then
  echo '{"code":200,"message":"OK","data":{"status":"UP"}}'
elif [[ "$*" == *"entities/query"* ]]; then
  echo '{"code":0,"data":[{"count(*)":42}]}'
else
  echo '{"code":0,"data":[]}'
fi
EOF
chmod +x "$TMP/bin/curl"

PATH="$TMP/bin:$PATH" \
VERIFY_MYSQL_HOST=127.0.0.1 VERIFY_MYSQL_DATABASE=king_ai \
VERIFY_MYSQL_USER=king VERIFY_MYSQL_PASSWORD=secret \
VERIFY_API_URL=http://127.0.0.1:18080 \
VERIFY_MILVUS_URL=http://127.0.0.1:19531 \
VERIFY_REPORT="$TMP/report.json" \
"$ROOT/verify.sh" >/dev/null

jq -e '
  .checks.flyway.status == "ok" and
  .checks.assistant_blank.status == "ok" and
  .checks.orphan_messages.status == "ok" and
  .checks.messages.value == "9" and
  .checks.vector_count_0.value == "42"
' "$TMP/report.json" >/dev/null

# 空 Flyway 历史必须让校验失败并落盘 failed 状态，而不是永远标记 ok。
mkdir -p "$TMP/bin-empty"
cat > "$TMP/bin-empty/mysql" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"flyway_schema_history"*) echo "0";;
  *"role = 'assistant'"*) echo "0";;
  *"LEFT JOIN king_ai_chat_session"*) echo "0";;
  *"ORDER BY id DESC LIMIT 5"*) echo "5";;
  *"king_ai_chat_message"*) echo "9";;
  *"vector_collection"*) echo "kb_collection_1";;
  *) echo "1";;
esac
EOF
chmod +x "$TMP/bin-empty/mysql"

set +e
PATH="$TMP/bin-empty:$TMP/bin:$PATH" \
VERIFY_MYSQL_HOST=127.0.0.1 VERIFY_MYSQL_DATABASE=king_ai \
VERIFY_MYSQL_USER=king VERIFY_MYSQL_PASSWORD=secret \
VERIFY_API_URL=http://127.0.0.1:18080 \
VERIFY_MILVUS_URL=http://127.0.0.1:19531 \
VERIFY_REPORT="$TMP/report-empty.json" \
"$ROOT/verify.sh" >/dev/null 2>&1
STATUS=$?
set -e
[[ "$STATUS" -eq 1 ]] || { echo "expected verify.sh to exit 1 when flyway history is empty" >&2; exit 1; }
jq -e '.checks.flyway.status == "failed"' "$TMP/report-empty.json" >/dev/null

echo "verify test passed"
