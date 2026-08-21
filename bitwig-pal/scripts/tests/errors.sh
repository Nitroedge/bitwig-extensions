#
# Error handling tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: Tests 9, 18
#

# --- 9. Error Handling ---
echo "--- Error Handling ---"

# Malformed JSON
ERR=$(rpc '{bad json}')
assert_contains "malformed JSON returns -32700" "$ERR" '-32700'

# Unknown method
ERR=$(rpc '{"jsonrpc":"2.0","method":"does/not/exist","id":60}')
assert_contains "unknown method returns -32601" "$ERR" '-32601'

# Invalid params
ERR=$(rpc '{"jsonrpc":"2.0","method":"track/setVolume","params":{"index":999,"value":0.5},"id":61}')
assert_contains "invalid params returns -32602" "$ERR" '-32602'

# --- 18. Error Handling (Phase 2) ---
echo "--- Error Handling (Phase 2) ---"

ERR=$(rpc '{"jsonrpc":"2.0","method":"clip/launch","params":{"trackIndex":999,"slotIndex":0},"id":84}')
assert_contains "clip invalid trackIndex returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"scene/launch","params":{"index":99},"id":85}')
assert_contains "scene invalid index returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"device/setParameterValue","params":{"index":99,"value":0.5},"id":86}')
assert_contains "device invalid param index returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"cursor/selectTrack","params":{"direction":"invalid"},"id":87}')
assert_contains "cursor invalid direction returns -32602" "$ERR" '-32602'

# --- Origin and Host refusals (phase 6 plan 06-11, Gap 6) ---
#
# Proves the two header guards from OUTSIDE the JVM, which is the whole point of this layer.
# Status only, never the body: a refusal that leaked method names or state would be a finding
# in its own right. `-o /dev/null -w %{http_code}` is used rather than a bare curl line because
# D6-DEF-04 records bare curl invocations failing on the prescribed shell.
echo "--- Cross-origin refusals ---"

STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE}/rpc" \
  -H "Content-Type: application/json" \
  -H "Origin: https://evil.example" \
  -d '{"jsonrpc":"2.0","method":"transport/getState","id":88}')
assert_equals "browser-shaped Origin is refused with 403" "$STATUS" "403"

STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE}/rpc" \
  -H "Content-Type: application/json" \
  -H "Host: attacker.example.com" \
  -d '{"jsonrpc":"2.0","method":"transport/getState","id":89}')
assert_equals "non-loopback Host is refused with 403 (DNS rebinding)" "$STATUS" "403"

# NO WebSocket assertion here: no script in this suite speaks WebSocket, and inventing a client
# for one assertion would add a dependency this layer does not have. The handshake refusal is
# covered by refusesAWebSocketHandshakeCarryingAForeignOrigin in WsRpcServerTest (layer 1).
# That the smoke layer cannot reach port 8788 at all is a gap in the smoke layer, recorded in
# 06-11-SUMMARY.md rather than papered over here.
