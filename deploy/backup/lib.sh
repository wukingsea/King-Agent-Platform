#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

require_env() {
  local name=$1
  [[ -n "${!name:-}" ]] || die "missing required environment variable: $name"
}

timestamp() {
  date -u +%Y%m%dT%H%M%SZ
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

s3_upload() {
  local file=$1
  local key=$2
  [[ -n "${BACKUP_S3_URI:-}" ]] || return 0
  require_cmd aws
  local args=(s3 cp "$file" "${BACKUP_S3_URI%/}/${key}" --only-show-errors)
  if [[ -n "${BACKUP_S3_SSE:-}" ]]; then
    args+=(--sse "$BACKUP_S3_SSE")
  fi
  aws "${args[@]}"
}

prune_dirs() {
  local dir=$1
  local prefix=$2
  local days=${3:-30}
  [[ -d "$dir" ]] || return 0
  find "$dir" -mindepth 1 -maxdepth 1 -type d -name "${prefix}*" -mtime +"$days" -print -exec rm -rf {} +
}

prune_files() {
  local dir=$1
  local pattern=$2
  local days=${3:-30}
  [[ -d "$dir" ]] || return 0
  find "$dir" -maxdepth 1 -type f -name "$pattern" -mtime +"$days" -print -delete
}
