#
# Arranger tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: sections 31-37
#

echo "--- Arranger Snapshot ---"

SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":161}')
assert_contains "snapshot has arranger section" "$SNAP" '"arranger"'
assert_contains "arranger has playbackFollow" "$SNAP" '"playbackFollow"'
assert_contains "arranger has clipLauncherVisible" "$SNAP" '"clipLauncherVisible"'
assert_contains "arranger has timelineVisible" "$SNAP" '"timelineVisible"'
assert_contains "arranger has cueMarkersVisible" "$SNAP" '"cueMarkersVisible"'
assert_contains "arranger has effectTracksVisible" "$SNAP" '"effectTracksVisible"'
assert_contains "arranger has ioSectionVisible" "$SNAP" '"ioSectionVisible"'
assert_contains "arranger has doubleRowTrackHeight" "$SNAP" '"doubleRowTrackHeight"'

echo "--- Arrangement Snapshot ---"

assert_contains "snapshot has arrangement section" "$SNAP" '"arrangement"'
assert_contains "arrangement has loop" "$SNAP" '"loop"'
assert_contains "arrangement has punch" "$SNAP" '"punch"'
assert_contains "arrangement has automation" "$SNAP" '"automation"'
assert_contains "arrangement has cueMarkers" "$SNAP" '"cueMarkers"'

echo "--- Arranger Visibility Toggle ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"arranger/setPlaybackFollow","params":{"enabled":true},"id":162}')
assert_contains "arranger/setPlaybackFollow returns ok" "$RESP" '"ok":true'
sleep 0.5
PF=$(snapshot_field "['arranger']['playbackFollow']")
assert_equals "playbackFollow is True after set" "$PF" "True"

RESP=$(rpc '{"jsonrpc":"2.0","method":"arranger/setPlaybackFollow","params":{"enabled":false},"id":163}')
assert_contains "arranger/setPlaybackFollow false returns ok" "$RESP" '"ok":true'
sleep 0.5
PF=$(snapshot_field "['arranger']['playbackFollow']")
assert_equals "playbackFollow is False after unset" "$PF" "False"

echo "--- Loop Range ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setLoopRange","params":{"start":8.0,"duration":16.0,"enabled":true},"id":164}')
assert_contains "transport/setLoopRange returns ok" "$RESP" '"ok":true'
sleep 0.5

RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/getLoopRange","id":165}')
assert_contains "getLoopRange has loopStart" "$RESP" '"loopStart"'
assert_contains "getLoopRange has loopDuration" "$RESP" '"loopDuration"'
assert_contains "getLoopRange has loopEnabled" "$RESP" '"loopEnabled"'

# Restore loop
rpc '{"jsonrpc":"2.0","method":"transport/setLoopRange","params":{"start":0.0,"duration":4.0,"enabled":false},"id":166}' > /dev/null
sleep 0.5

echo "--- Automation Write Mode ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setAutomationWriteMode","params":{"mode":"touch"},"id":167}')
assert_contains "setAutomationWriteMode touch returns ok" "$RESP" '"ok":true'
sleep 0.5
MODE=$(snapshot_field "['arrangement']['automation']['writeMode']")
assert_equals "automation writeMode is touch" "$MODE" "touch"

RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setAutomationWriteMode","params":{"mode":"latch"},"id":168}')
assert_contains "setAutomationWriteMode latch returns ok" "$RESP" '"ok":true'
sleep 0.5
MODE=$(snapshot_field "['arrangement']['automation']['writeMode']")
assert_equals "automation writeMode is latch" "$MODE" "latch"

# Invalid mode
ERR=$(rpc '{"jsonrpc":"2.0","method":"transport/setAutomationWriteMode","params":{"mode":"invalid"},"id":169}')
assert_contains "setAutomationWriteMode invalid returns -32602" "$ERR" '-32602'

echo "--- Reset Automation Overrides ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/resetAutomationOverrides","id":170}')
assert_contains "resetAutomationOverrides returns ok" "$RESP" '"ok":true'

echo "--- Cue Marker Errors ---"

ERR=$(rpc '{"jsonrpc":"2.0","method":"cueMarker/launch","params":{"index":99},"id":171}')
assert_contains "cueMarker/launch out-of-range returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"cueMarker/delete","params":{"index":-1},"id":172}')
assert_contains "cueMarker/delete negative index returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"cueMarker/launch","params":{},"id":173}')
assert_contains "cueMarker/launch missing index returns -32602" "$ERR" '-32602'
