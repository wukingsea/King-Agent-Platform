#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for test in "$DIR"/*_test.sh; do
  bash "$test"
done
