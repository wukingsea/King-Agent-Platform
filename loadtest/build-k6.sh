#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/bin/k6-sse"
EXT="github.com/phymbert/xk6-sse@v0.2.0"
XK6="go.k6.io/xk6/cmd/xk6@v1.4.15"

mkdir -p "$ROOT/bin"
if [[ -x "$OUT" ]]; then
  echo "using existing $OUT"
  "$OUT" version
  exit 0
fi

if command -v xk6 >/dev/null 2>&1; then
  (cd "$ROOT" && xk6 build --output "$OUT" --with "$EXT")
elif command -v docker >/dev/null 2>&1; then
  docker run --rm -v "$ROOT:/work" -w /work \
    -e GOFLAGS=-buildvcs=false \
    golang:1.25-bookworm bash -lc "
      set -euo pipefail
      go install ${XK6}
      export PATH=\"\$PATH:\$(go env GOPATH)/bin\"
      xk6 build --output bin/k6-sse --with ${EXT}
    "
else
  echo "NOT RUN: xk6 and docker are unavailable" >&2
  exit 2
fi

"$OUT" version
