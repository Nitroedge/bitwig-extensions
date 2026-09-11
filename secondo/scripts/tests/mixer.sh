#
# Mixer tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: sections 40-43
#

echo "--- Mixer Snapshot Fields ---"

SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":301}')
assert_contains "snapshot track has sends array" "$SNAP" '"sends"'
assert_contains "snapshot track has crossfadeMode" "$SNAP" '"crossfadeMode"'
assert_contains "snapshot track has monitorMode" "$SNAP" '"monitorMode"'
assert_contains "snapshot send has isPreFader" "$SNAP" '"isPreFader"'
SEND_COUNT=$(echo "$SNAP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']['tracks']['tracks'][0]['sends']))")
assert_equals "track 0 has 4 sends" "$SEND_COUNT" "4"

echo "--- Track Color ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"track/setColor","params":{"index":0,"r":0.8,"g":0.2,"b":0.4},"id":302}')
assert_contains "track/setColor returns ok" "$RESP" '"ok":true'
sleep 1.0
R=$(echo "$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":303}')" | python3 -c "import sys,json; c = json.load(sys.stdin)['result']['tracks']['tracks'][0]['color']; print(round(c['r'], 1))")
assert_equals "track 0 color.r is 0.8 after setColor" "$R" "0.8"

echo "--- Master Mute/Solo ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"master/setMute","params":{"value":true},"id":304}')
assert_contains "master/setMute returns ok" "$RESP" '"ok"'
sleep 0.5
RESP=$(rpc '{"jsonrpc":"2.0","method":"master/setMute","params":{"value":false},"id":305}')
assert_contains "master/setMute false returns ok" "$RESP" '"ok"'

RESP=$(rpc '{"jsonrpc":"2.0","method":"master/setSolo","params":{"value":true},"id":306}')
assert_contains "master/setSolo returns ok" "$RESP" '"ok"'
sleep 0.5
RESP=$(rpc '{"jsonrpc":"2.0","method":"master/setSolo","params":{"value":false},"id":307}')
assert_contains "master/setSolo false returns ok" "$RESP" '"ok"'

echo "--- Send Errors ---"

ERR=$(rpc '{"jsonrpc":"2.0","method":"send/setLevel","params":{"sendIndex":0,"value":0.5},"id":308}')
assert_contains "send/setLevel missing trackIndex returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"send/setMode","params":{"trackIndex":0,"sendIndex":0,"mode":"INVALID"},"id":309}')
assert_contains "send/setMode invalid mode returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"track/setCrossfade","params":{"index":0,"mode":"INVALID"},"id":310}')
assert_contains "track/setCrossfade invalid mode returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"track/setMonitor","params":{"index":0,"mode":"INVALID"},"id":311}')
assert_contains "track/setMonitor invalid mode returns -32602" "$ERR" '-32602'
