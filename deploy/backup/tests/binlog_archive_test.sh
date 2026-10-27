#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/out"

cat > "$TMP/bin/mysql" <<'EOF'
#!/usr/bin/env bash
printf 'mysql-bin.000001 120\nmysql-bin.000002 240\n'
EOF
chmod +x "$TMP/bin/mysql"

cat > "$TMP/bin/mysqlbinlog" <<'EOF'
#!/usr/bin/env bash
echo "/* binlog: $* */"
echo "CREATE TABLE sample(id int);"
EOF
chmod +x "$TMP/bin/mysqlbinlog"

PATH="$TMP/bin:$PATH" \
MYSQL_HOST=127.0.0.1 MYSQL_USER=king MYSQL_PASSWORD=secret \
BACKUP_OUT_DIR="$TMP/out" \
"$ROOT/mysql-binlog-archive.sh" >/dev/null

test -f "$TMP/out/binlog/mysql-bin.000001.sql.gz"
test -f "$TMP/out/binlog/mysql-bin.000002.sql.gz"
test "$(cat "$TMP/out/binlog/.last-binlog")" = "mysql-bin.000002"
gzip -dc "$TMP/out/binlog/mysql-bin.000002.sql.gz" | grep -q 'CREATE TABLE sample'

echo "binlog archive test passed"
