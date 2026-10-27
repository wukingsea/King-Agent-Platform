# Load tests

This directory contains the reproducible baseline for chat SSE and RAG search.
It records measurements only. It does not prove capacity targets.

## Prerequisites

- A dedicated test space with a working agent and at least one ready knowledge
  document. Never point these scripts at production.
- Node.js 22 for static checks.
- `jq` for baseline rendering.
- k6 with the `k6/x/sse` module. `build-k6.sh` pins
  `phymbert/xk6-sse@v0.2.0` and `xk6@v1.4.15`; the Docker fallback uses Go 1.25
  because the extension requires k6 v2 and Go 1.25.1. If the local k6 already
  exposes the module, substitute its path for `loadtest/bin/k6-sse` in the
  commands below and skip the build.
- Docker is optional and is used only when `xk6` is not installed.

## Credentials

All credentials come from the environment. Do not commit them.

```bash
export KING_AI_BASE_URL=http://127.0.0.1:8080
export KING_AI_USERNAME=loadtest-admin
export KING_AI_PASSWORD='<from your secret manager>'
export KING_AI_AGENT_ID=1
export KING_AI_KB_ID=1
```

`KING_AI_SPACE_ID` is optional; the login response `currentSpaceId` is used when
it is absent.

## Static checks

```bash
loadtest/tests/static_test.sh
```

The script prints `loadtest static checks passed; runtime load tests NOT RUN`.
That means syntax and guardrails passed; it does not mean a load test ran.

## Build

```bash
loadtest/build-k6.sh
```

If the script prints `NOT RUN`, install Docker or Go plus xk6 and rerun. A
`NOT RUN` result is not a pass.

## Chat SSE baseline

```bash
mkdir -p loadtest/results
K6_VERSION="$(loadtest/bin/k6-sse version)" \
VUS=10 RAMP=30s DURATION=2m \
SUMMARY_OUT=loadtest/results/chat-local.json \
  loadtest/bin/k6-sse run loadtest/k6/chat-sse.js
```

TTFT is measured from request start to the first `content` event. A request
fails when the stream emits `error`, never reaches `done`, or returns non-200.

## RAG baseline

```bash
mkdir -p loadtest/results
K6_VERSION="$(loadtest/bin/k6-sse version)" \
VUS=10 RAMP=30s DURATION=2m SLEEP=0.5 \
SUMMARY_OUT=loadtest/results/rag-local.json \
  loadtest/bin/k6-sse run loadtest/k6/rag-query.js
```

## Render a baseline

```bash
LABEL=local-chat-10vu \
OUT=loadtest/baselines/local-chat-10vu.md \
KING_AI_BASE_URL="$KING_AI_BASE_URL" VUS=10 RAMP=30s DURATION=2m \
  loadtest/render-baseline.sh loadtest/results/chat-local.json
```

Only measured values are copied. Missing metrics appear as `n/a`.

## 1000 concurrent SSE

Do not run 1000 VUs as an acceptance test yet. The current executor is core 4 /
max 16 / queue 100 per instance, and resumable SSE is not implemented. The
1000-concurrent target is blocked on P2-2 (executor/queue isolation) and P2-3
(resumable SSE). A 1000-VU run before those changes is expected to produce
rejections and is not evidence of readiness.

## Results hygiene

`loadtest/bin/` and `loadtest/results/` are ignored. `loadtest/baselines/` is
committed as measurement evidence, but only after reviewing the report for host
names, tokens and other environment detail.
