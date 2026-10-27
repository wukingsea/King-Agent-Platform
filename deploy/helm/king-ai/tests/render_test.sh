#!/usr/bin/env bash
# Render tests for the king-ai chart. Extended by Task 7 (static checks in CI).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if ! command -v helm >/dev/null 2>&1; then
  echo "helm not found in PATH" >&2
  exit 127
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

helm lint --strict "$CHART_DIR" >/dev/null

helm template king-ai "$CHART_DIR" --namespace king-ai > "$WORK_DIR/rendered.yaml"

assert_contains() {
  local needle="$1"
  if ! grep -qF -- "$needle" "$WORK_DIR/rendered.yaml"; then
    echo "ASSERT FAILED: missing $needle" >&2
    exit 1
  fi
}

assert_contains 'kind: Deployment'
assert_contains 'readOnlyRootFilesystem: true'
assert_contains 'runAsNonRoot: true'
assert_contains '/actuator/health/liveness'
assert_contains '/actuator/health/readiness'
assert_contains 'name: API_UPSTREAM'
assert_contains 'helm.sh/hook: pre-install,pre-upgrade'
assert_contains 'helm.sh/hook-weight: "-20"'
assert_contains 'value: prod,migrate'
assert_contains 'kind: PersistentVolumeClaim'
assert_contains 'kind: HorizontalPodAutoscaler'
assert_contains 'king_ai_chat_active'
assert_contains 'kind: PodDisruptionBudget'
assert_contains 'kind: NetworkPolicy'
assert_contains 'policyTypes:'

# With migration disabled and exports on emptyDir neither the Job nor the
# PVC may render (controller ruling: fold the plan's negative check in here).
helm template king-ai "$CHART_DIR" --namespace king-ai \
  --set migration.enabled=false \
  --set persistence.exports.mode=emptyDir > "$WORK_DIR/rendered-disabled.yaml"
if grep -qE 'kind: (Job|PersistentVolumeClaim)' "$WORK_DIR/rendered-disabled.yaml"; then
  echo "ASSERT FAILED: Job or PVC rendered with migration.enabled=false and exports.mode=emptyDir" >&2
  exit 1
fi

# Ingress render: SSE proxy tuning and cookie affinity annotations (Task 5).
helm template king-ai "$CHART_DIR" --namespace king-ai \
  --set ingress.enabled=true --set ingress.host=ai.example.com > "$WORK_DIR/ingress.yaml"
for needle in \
  'nginx.ingress.kubernetes.io/proxy-buffering: "off"' \
  'nginx.ingress.kubernetes.io/proxy-read-timeout: "600"' \
  'nginx.ingress.kubernetes.io/affinity: "cookie"'; do
  if ! grep -qF -- "$needle" "$WORK_DIR/ingress.yaml"; then
    echo "ASSERT FAILED: missing $needle in ingress render" >&2
    exit 1
  fi
done

echo "render tests passed"
