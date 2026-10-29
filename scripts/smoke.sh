#!/usr/bin/env bash
#
# End-to-end smoke test: starts the example app as an MCP server and drives it
# with raw JSON-RPC messages, exactly as an MCP client would.
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

OUT="$(mktemp)"
ERR="$(mktemp)"
trap 'rm -f "$OUT" "$ERR"' EXIT

printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"workorder.list","arguments":{"customerId":"C-1001"},"_meta":{"callerId":"smoke.test","requestId":"smoke-1"}}}' \
  '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"workorder.purge","arguments":{"workOrderId":"WO-2004"}}}' \
  '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"workorder.escalate","arguments":{"workOrderId":"WO-2001","priority":1},"_meta":{"callerId":"smoke.test","requestId":"smoke-2"}}}' \
  | java -jar "$JAR" >"$OUT" 2>"$ERR"

fail() { echo "FAIL: $1"; echo "--- stdout ---"; cat "$OUT"; exit 1; }

grep -q '"serverInfo":{"name":"workorder-tools"' "$OUT" || fail "initialize did not report the server name"
grep -q '"name":"workorder.list"' "$OUT"            || fail "tools/list does not contain workorder.list"
grep -q 'WO-2002' "$OUT"                             || fail "workorder.list returned no data"
grep -q "approval required: 'workorder.purge'" "$OUT" || fail "risk gate did not block the destructive tool"
grep -q '"status" : "ESCALATED"' "$OUT"              || fail "workorder.escalate did not change the status"

LINES=$(wc -l < "$OUT" | tr -d ' ')
[ "$LINES" -eq 5 ] || fail "expected 5 responses (notifications are silent), got $LINES"

echo "PASS - 5 requests, 5 responses, risk gate enforced, logs kept off stdout"
