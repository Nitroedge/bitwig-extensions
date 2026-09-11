#
# Transport, batch, and HTTP tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: Test 4, Test 8, Test 10
#

echo "--- Transport Actions ---"

# Save baseline
ORIG_TEMPO=$(snapshot_field "['transport']['tempo']")
ORIG_LOOP=$(snapshot_field "['transport']['isLoopEnabled']")
ORIG_METRO=$(snapshot_field "['transport']['isMetronomeEnabled']")

# play
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/play","id":10}')
assert_contains "transport/play returns ok" "$RESP" '"ok"'
sleep 0.5
PLAYING=$(snapshot_field "['transport']['isPlaying']")
assert_equals "isPlaying is True after play" "$PLAYING" "True"

# stop
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/stop","id":11}')
assert_contains "transport/stop returns ok" "$RESP" '"ok"'
sleep 0.5
PLAYING=$(snapshot_field "['transport']['isPlaying']")
assert_equals "isPlaying is False after stop" "$PLAYING" "False"

# setTempo
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setTempo","params":{"tempo":135},"id":12}')
assert_contains "transport/setTempo returns ok" "$RESP" '"ok"'
sleep 0.5
TEMPO=$(snapshot_field "['transport']['tempo']")
assert_equals "tempo is 135.0 after setTempo" "$TEMPO" "135.0"

# setPosition
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setPosition","params":{"beats":4.0},"id":13}')
assert_contains "transport/setPosition returns ok" "$RESP" '"ok"'
sleep 0.5
POS=$(snapshot_field "['transport']['playPosition']")
assert_equals "playPosition is 4.0 after setPosition" "$POS" "4.0"

# setLoop
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setLoop","params":{"enabled":true},"id":14}')
assert_contains "transport/setLoop returns ok" "$RESP" '"ok"'
sleep 0.5
LOOP=$(snapshot_field "['transport']['isLoopEnabled']")
assert_equals "isLoopEnabled is True after setLoop" "$LOOP" "True"

# setMetronome
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setMetronome","params":{"enabled":true},"id":15}')
assert_contains "transport/setMetronome returns ok" "$RESP" '"ok"'
sleep 0.5
METRO=$(snapshot_field "['transport']['isMetronomeEnabled']")
assert_equals "isMetronomeEnabled is True after setMetronome" "$METRO" "True"

# Restore transport state
ORIG_LOOP_LC=$(echo "$ORIG_LOOP" | tr '[:upper:]' '[:lower:]')
ORIG_METRO_LC=$(echo "$ORIG_METRO" | tr '[:upper:]' '[:lower:]')
rpc "{\"jsonrpc\":\"2.0\",\"method\":\"transport/setTempo\",\"params\":{\"tempo\":${ORIG_TEMPO}},\"id\":90}" > /dev/null
rpc '{"jsonrpc":"2.0","method":"transport/setPosition","params":{"beats":0},"id":91}' > /dev/null
rpc "{\"jsonrpc\":\"2.0\",\"method\":\"transport/setLoop\",\"params\":{\"enabled\":${ORIG_LOOP_LC}},\"id\":92}" > /dev/null
rpc "{\"jsonrpc\":\"2.0\",\"method\":\"transport/setMetronome\",\"params\":{\"enabled\":${ORIG_METRO_LC}},\"id\":93}" > /dev/null

echo "--- Batch Request ---"

BATCH=$(rpc '[{"jsonrpc":"2.0","method":"api/list","id":50},{"jsonrpc":"2.0","method":"app/getState","id":51}]')
assert_contains "batch response has id 50" "$BATCH" '"id":50'
assert_contains "batch response has id 51" "$BATCH" '"id":51'

echo "--- HTTP Method Enforcement ---"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/rpc")
assert_equals "GET /rpc returns 405" "$HTTP_CODE" "405"
