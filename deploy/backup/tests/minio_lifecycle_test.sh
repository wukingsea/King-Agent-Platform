#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/mc" <<'EOF'
#!/usr/bin/env bash
echo "mc $*"
EOF
chmod +x "$TMP/bin/mc"

OUT="$(PATH="$TMP/bin:$PATH" MC_ALIAS=minio \
  BACKUP_BUCKET=king-ai-backups EXPORT_BUCKET=king-ai-exports DRY_RUN=1 \
  "$ROOT/minio-lifecycle.sh" 2>&1)"

printf '%s\n' "$OUT" | grep -q 'mc version enable minio/king-ai-backups'
printf '%s\n' "$OUT" | grep -q 'mc ilm rule add --expire-days 30 minio/king-ai-backups'
printf '%s\n' "$OUT" | grep -q 'mc ilm rule add --expire-days 7 --prefix exports/ minio/king-ai-exports'
echo "minio lifecycle test passed"
