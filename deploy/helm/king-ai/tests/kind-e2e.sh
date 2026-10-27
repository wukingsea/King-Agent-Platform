#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
CHART_DIR="$ROOT_DIR/deploy/helm/king-ai"
CLUSTER=king-ai-e2e
RELEASE=king-ai
NAMESPACE=king-ai

for cmd in kind docker kubectl helm curl; do
  command -v "$cmd" >/dev/null || { echo "missing $cmd" >&2; exit 127; }
done

cleanup() {
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

kind create cluster --name "$CLUSTER" --wait 120s

docker build -f "$ROOT_DIR/king-ai-api/Dockerfile" -t king-ai-api:kind "$ROOT_DIR"
docker build -f "$ROOT_DIR/king-ai-ui/Dockerfile" -t king-ai-ui:kind "$ROOT_DIR"
kind load docker-image king-ai-api:kind king-ai-ui:kind --name "$CLUSTER"

helm upgrade --install "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" --create-namespace \
  -f "$CHART_DIR/tests/values-kind.yaml" \
  --wait --timeout 10m

kubectl -n "$NAMESPACE" rollout status deployment/king-ai-api --timeout=300s
kubectl -n "$NAMESPACE" rollout status deployment/king-ai-ui --timeout=120s

kubectl -n "$NAMESPACE" port-forward svc/king-ai-api 18080:8080 >/tmp/king-ai-e2e-port-forward.log 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" >/dev/null 2>&1 || true; cleanup' EXIT
for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:18080/api/health | grep -q '"status":"UP"'; then
    break
  fi
  sleep 2
done
curl -fsS http://127.0.0.1:18080/api/health | grep -q '"status":"UP"'

helm upgrade "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  -f "$CHART_DIR/tests/values-kind.yaml" \
  --set api.podAnnotations.e2e-upgrade="$(date +%s)" \
  --wait --timeout 10m
kubectl -n "$NAMESPACE" rollout status deployment/king-ai-api --timeout=300s

helm rollback "$RELEASE" 1 --namespace "$NAMESPACE" --wait --timeout 10m
kubectl -n "$NAMESPACE" rollout status deployment/king-ai-api --timeout=300s

kubectl -n "$NAMESPACE" get pods -o wide
echo "kind e2e passed: install, upgrade and rollback"
