#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULT="${1:-}"
[[ -n "$RESULT" && -f "$RESULT" ]] || {
  echo "usage: render-baseline.sh <k6-summary.json>" >&2
  exit 2
}
command -v jq >/dev/null 2>&1 || {
  echo "NOT RUN: jq is required" >&2
  exit 2
}
jq -e '.metrics' "$RESULT" >/dev/null || {
  echo "not a k6 summary export: $RESULT" >&2
  exit 1
}

LABEL="${LABEL:-local}"
[[ "$LABEL" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "LABEL may only contain letters, digits, dot, underscore and dash" >&2
  exit 1
}
OUT="${OUT:-$ROOT/baselines/$LABEL.md}"
mkdir -p "$(dirname "$OUT")"

metric() {
  jq -r --arg metric "$1" --arg key "$2" \
    '.metrics[$metric].values[$key] // "n/a"' "$RESULT"
}

captured="${BASELINE_DATE:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
target="${KING_AI_BASE_URL:-unknown}"
vus="${VUS:-unknown}"
ramp="${RAMP:-unknown}"
hold="${DURATION:-unknown}"

cat > "$OUT" <<EOF
# Load baseline: $LABEL

- Captured (UTC): $captured
- Target: $target
- Scenario: VUS=$vus, ramp=$ramp, hold=$hold
- k6: ${K6_VERSION:-unknown}
- Source: \`$RESULT\`

| Metric | Value |
| --- | --- |
| Iterations | $(metric iterations count) |
| Error rate | $(metric king_ai_load_error_rate rate) |
| TTFT p95 (ms) | $(metric king_ai_load_ttft_ms 'p(95)') |
| Chat duration p95 (ms) | $(metric king_ai_load_chat_duration_ms 'p(95)') |
| RAG latency p95 (ms) | $(metric king_ai_load_rag_latency_ms 'p(95)') |
| HTTP duration p95 (ms) | $(metric http_req_duration 'p(95)') |
| Checks pass rate | $(metric checks rate) |

> Values are transcribed from the k6 summary export for a single run; missing
> metrics are shown as \`n/a\`, never as zero. This file is a measurement record,
> not a capacity commitment.
>
> The 1000-concurrent-SSE acceptance is **NOT MET** until P2-2 and P2-3 land.
> Do not cite this baseline as proof of that target.

Report copied to \`$OUT\`.
EOF
