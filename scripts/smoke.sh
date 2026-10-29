#!/usr/bin/env bash
#
# End-to-end smoke test: starts the example app as an MCP server and drives it
# with raw JSON-RPC messages, exactly as an MCP client would.
#
# Checks that matter:
#   1. the handshake reports the configured server name
#   2. a READ tool runs and returns data
#   3. a DESTRUCTIVE tool is refused by the risk gate
#   4. a WRITE tool mutates state
#   5. notifications get no reply (5 requests in, 5 responses out)
#   6. logs land on stderr, so stdout stays pure JSON-RPC
#
# Usage: ./scripts/smoke.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="agent-bridge-example-workorder/target/agent-bridge-example-workorder-0.1.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "jar not found, building first..."
  mvn -q -DskipTests package
fi

mkdir -p target
OUT="target/smoke-stdout.log"
ERR="target/smoke-stderr.log"

printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"workorder.list","arguments":{"customerId":"C-1001"},"_meta":{"callerId":"smoke.test","requestId":"smoke-1"}}}' \
  '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"workorder.purge","arguments":{"workOrderId":"WO-2004"}}}' \
  '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"workorder.escalate","arguments":{"workOrderId":"WO-2001","priority":1},"_meta":{"callerId":"smoke.test","requestId":"smoke-2"}}}' \
  | java -jar "$JAR" >"$OUT" 2>"$ERR"

fail() {
  echo "FAIL: $1"
  echo "--- stdout ---"
  cut -c1-400 "$OUT"
  echo "--- stderr ---"
  tail -20 "$ERR"
  exit 1
}

grep -qF '"serverInfo":{"name":"workorder-tools"' "$OUT" || fail "initialize did not report the server name"
grep -qF '"name":"workorder.list"'                   "$OUT" || fail "tools/list does not contain workorder.list"
grep -qF 'WO-2002'                                   "$OUT" || fail "workorder.list returned no data"
grep -qF 'approval required: '"'"'workorder.purge'"'"'' "$OUT" || fail "risk gate did not block the destructive tool"
grep -qF '\"status\" : \"ESCALATED\"'                "$OUT" || fail "workorder.escalate did not change the status"
grep -qF '\"priority\" : 1'                          "$OUT" || fail "workorder.escalate did not change the priority"

LINES=$(wc -l < "$OUT" | tr -d ' ')
[ "$LINES" -eq 5 ] || fail "expected 5 responses (notifications are silent), got $LINES"

grep -qF 'exposed 5 tool(s)' "$ERR" || fail "startup log missing, or logs did not go to stderr"

echo "PASS - 5 requests, 5 responses, risk gate enforced, logs kept off stdout"
