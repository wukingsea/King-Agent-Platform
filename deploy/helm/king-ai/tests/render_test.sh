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

# With migration disabled and exports on emptyDir neither the Job nor the
# PVC may render (controller ruling: fold the plan's negative check in here).
helm template king-ai "$CHART_DIR" --namespace king-ai \
  --set migration.enabled=false \
  --set persistence.exports.mode=emptyDir > "$WORK_DIR/rendered-disabled.yaml"
if grep -qE 'kind: (Job|PersistentVolumeClaim)' "$WORK_DIR/rendered-disabled.yaml"; then
  echo "ASSERT FAILED: Job or PVC rendered with migration.enabled=false and exports.mode=emptyDir" >&2
  exit 1
fi

echo "render tests passed"
