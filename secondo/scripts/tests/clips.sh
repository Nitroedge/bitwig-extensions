#
# Clip and scene tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: Test 11, Test 12, Test 13
#

echo "--- Clip Snapshot ---"

SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":70}')
assert_contains "snapshot has scenes" "$SNAP" '"scenes"'
CLIP_COUNT=$(echo "$SNAP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']['tracks']['tracks'][0]['clips']))")
assert_equals "track 0 has 5 clip slots" "$CLIP_COUNT" "5"
SCENE_COUNT=$(echo "$SNAP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']['scenes']['scenes']))")
assert_equals "snapshot has 5 scenes" "$SCENE_COUNT" "5"
assert_contains "clips have hasContent" "$SNAP" '"hasContent"'
assert_contains "clips have isPlaying" "$SNAP" '"isPlaying"'

echo "--- Clip Actions ---"

# Create a clip on track 0, slot 4 (last visible slot to avoid conflicts)
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/create","params":{"trackIndex":0,"slotIndex":4,"lengthInBeats":4},"id":71}')
assert_contains "clip/create returns ok" "$RESP" '"ok"'
sleep 1.0
HAS=$(snapshot_field "['tracks']['tracks'][0]['clips'][4]['hasContent']")
assert_equals "slot 4 hasContent after create" "$HAS" "True"

# Launch the clip
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/launch","params":{"trackIndex":0,"slotIndex":4},"id":72}')
assert_contains "clip/launch returns ok" "$RESP" '"ok"'
sleep 1.0
PLAYING=$(snapshot_field "['tracks']['tracks'][0]['clips'][4]['isPlaying']")
assert_equals "slot 4 isPlaying after launch" "$PLAYING" "True"

# Stop
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/stop","params":{"trackIndex":0},"id":73}')
assert_contains "clip/stop returns ok" "$RESP" '"ok"'
sleep 0.5

# Stop transport (clip launcher may have started it)
rpc '{"jsonrpc":"2.0","method":"transport/stop","id":74}' > /dev/null
sleep 0.5

# Delete the clip we created on slot 4
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/delete","params":{"trackIndex":0,"slotIndex":4},"id":174}')
assert_contains "clip/delete returns ok" "$RESP" '"ok"'
sleep 0.5
HAS=$(snapshot_field "['tracks']['tracks'][0]['clips'][4]['hasContent']")
assert_equals "slot 4 hasContent after delete" "$HAS" "False"

echo "--- Scene Actions ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"scene/launch","params":{"index":0},"id":75}')
assert_contains "scene/launch returns ok" "$RESP" '"ok"'
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"transport/stop","id":76}' > /dev/null
sleep 0.5
