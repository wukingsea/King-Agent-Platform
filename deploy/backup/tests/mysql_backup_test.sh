#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/out"

cat > "$TMP/bin/mysqldump" <<'EOF'
#!/usr/bin/env bash
echo "-- MySQL dump 10.13"
echo "-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='mysql-bin.000042', SOURCE_LOG_POS=1234;"
echo "CREATE TABLE sample(id int primary key);"
EOF
chmod +x "$TMP/bin/mysqldump"

cat > "$TMP/bin/mysql" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *flyway_schema_history*) echo "8:access-review";;
  *) echo "1";;
esac
EOF
chmod +x "$TMP/bin/mysql"

PATH="$TMP/bin:$PATH" \
MYSQL_HOST=127.0.0.1 MYSQL_DATABASE=king_ai MYSQL_USER=king MYSQL_PASSWORD=secret \
BACKUP_OUT_DIR="$TMP/out" BACKUP_LABEL=test-run \
"$ROOT/mysql-backup.sh" >/dev/null

test -f "$TMP/out/test-run/king_ai.sql.gz"
gzip -dc "$TMP/out/test-run/king_ai.sql.gz" | grep -q 'CREATE TABLE sample'
test -f "$TMP/out/test-run/manifest.json"
jq -e '
  .label == "test-run" and
  .database == "king_ai" and
  .sha256 != "" and
  .flywayVersion == "8:access-review" and
  (.binlog | contains("mysql-bin.000042"))
' "$TMP/out/test-run/manifest.json" >/dev/null

echo "mysql backup test passed"
