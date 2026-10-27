#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K6="$ROOT/k6"

required=(
  "$K6/lib/api.js"
  "$K6/chat-sse.js"
  "$K6/rag-query.js"
  "$ROOT/build-k6.sh"
  "$ROOT/render-baseline.sh"
  "$ROOT/README.md"
)
for file in "${required[@]}"; do
  [[ -f "$file" ]] || { echo "missing $file" >&2; exit 1; }
done

command -v node >/dev/null 2>&1 || {
  echo "NOT RUN: node is required for JavaScript syntax checks" >&2
  exit 2
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for file in "$K6"/*.js "$K6"/lib/*.js; do
  cp "$file" "$tmp/$(basename "${file%.js}").mjs"
  node --check "$tmp/$(basename "${file%.js}").mjs"
done

bash -n "$ROOT/build-k6.sh"
bash -n "$ROOT/render-baseline.sh"

grep -q "sse.open" "$K6/chat-sse.js" || {
  echo "chat-sse.js must use sse.open" >&2
  exit 1
}
grep -q "X-Space-Id" "$K6/lib/api.js" || {
  echo "api.js must send X-Space-Id" >&2
  exit 1
}
grep -q "Authorization" "$K6/lib/api.js" || {
  echo "api.js must send Authorization" >&2
  exit 1
}
grep -q "king_ai_load_error_rate" "$K6/chat-sse.js" || {
  echo "chat-sse.js must expose error rate" >&2
  exit 1
}
grep -q "king_ai_load_error_rate" "$K6/rag-query.js" || {
  echo "rag-query.js must expose error rate" >&2
  exit 1
}
grep -q "P2-2" "$ROOT/README.md" || {
  echo "README must document the 1000-concurrency blocker" >&2
  exit 1
}

if grep -R -n -E 'tags:[[:space:]]*\{[^}]*(userId|sessionId|requestId)' "$K6"; then
  echo "metric tags must not contain user/session/request identifiers" >&2
  exit 1
fi
if grep -R -n 'KING_AI_PASSWORD=' "$K6" "$ROOT"/*.sh; then
  echo "credentials must come from the environment, not literals" >&2
  exit 1
fi

if "$ROOT/render-baseline.sh" >/dev/null 2>&1; then
  echo "render-baseline.sh must reject a missing summary file" >&2
  exit 1
fi

echo "loadtest static checks passed; runtime load tests NOT RUN"
