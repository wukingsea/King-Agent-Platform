#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
COMPOSE="$ROOT/docker-compose.yml"
COLLECTOR="$ROOT/otel-collector/config.yml"

[[ -f "$COMPOSE" ]] || { echo "missing $COMPOSE" >&2; exit 1; }
[[ -f "$COLLECTOR" ]] || { echo "missing $COLLECTOR" >&2; exit 1; }

for service in otel-collector jaeger prometheus grafana; do
  grep -q "^  ${service}:" "$COMPOSE" \
    || { echo "missing service: $service" >&2; exit 1; }
done

grep -q 'OTEL_TRACING_EXPORT_ENABLED: "true"' "$COMPOSE" \
  || { echo "api must enable trace export in the overlay" >&2; exit 1; }
grep -q 'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: http://otel-collector:4318/v1/traces' "$COMPOSE" \
  || { echo "api must export to the collector" >&2; exit 1; }
grep -q 'KING_AI_TRACE_SAMPLE: ${KING_AI_TRACE_SAMPLE:-1.0}' "$COMPOSE" \
  || { echo "api must expose KING_AI_TRACE_SAMPLE with a local default" >&2; exit 1; }

grep -q '"127.0.0.1:16686:16686"' "$COMPOSE" \
  || { echo "jaeger UI must bind to localhost" >&2; exit 1; }
grep -q '"127.0.0.1:9090:9090"' "$COMPOSE" \
  || { echo "prometheus must bind to localhost" >&2; exit 1; }
grep -q '"127.0.0.1:3000:3000"' "$COMPOSE" \
  || { echo "grafana must bind to localhost" >&2; exit 1; }
if grep -q '"0.0.0.0:' "$COMPOSE"; then
  echo "observability overlay must not bind host ports to 0.0.0.0" >&2
  exit 1
fi

grep -q 'endpoint: 0.0.0.0:4318' "$COLLECTOR" \
  || { echo "collector must listen on OTLP/HTTP 4318" >&2; exit 1; }
grep -q 'endpoint: jaeger:4317' "$COLLECTOR" \
  || { echo "collector must export to jaeger:4317" >&2; exit 1; }
grep -q 'health_check:' "$COLLECTOR" \
  || { echo "collector must expose a health check" >&2; exit 1; }

grep -q 'GRAFANA_ADMIN_PASSWORD' "$REPO/.env.example" \
  || { echo ".env.example must document GRAFANA_ADMIN_PASSWORD" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || {
  echo "NOT RUN: docker is required for compose and collector validation" >&2
  exit 2
}
docker compose version >/dev/null 2>&1 || {
  echo "NOT RUN: docker compose v2 is required" >&2
  exit 2
}

env \
  MYSQL_ROOT_PASSWORD=verify-root \
  MYSQL_PASSWORD=verify-pass \
  REDIS_PASSWORD=verify-pass \
  MINIO_ROOT_USER=verify \
  MINIO_ROOT_PASSWORD=verify-pass \
  KING_AI_SECRET=0123456789abcdef0123456789abcdef \
  KING_AI_ENCRYPTION_KEY=fedcba9876543210fedcba9876543210 \
  KING_AI_ADMIN_PASSWORD=VerifyPass123 \
  GRAFANA_ADMIN_PASSWORD=VerifyPass123 \
  docker compose -f "$REPO/docker-compose.yml" -f "$COMPOSE" config -q

docker run --rm \
  -v "$COLLECTOR:/etc/otelcol-contrib/config.yml:ro" \
  otel/opentelemetry-collector-contrib:0.115.1 \
  validate --config=/etc/otelcol-contrib/config.yml

echo "observability overlay verified"
