#
# Track action and management tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: Test 5, Test 28, Test 29
#

echo "--- Track Actions ---"

# Save baseline
ORIG_VOL=$(snapshot_field "['tracks']['tracks'][0]['volume']")
ORIG_MUTE=$(snapshot_field "['tracks']['tracks'][0]['mute']")

# setVolume
RESP=$(rpc '{"jsonrpc":"2.0","method":"track/setVolume","params":{"index":0,"value":0.3},"id":20}')
assert_contains "track/setVolume returns ok" "$RESP" '"ok"'
sleep 1.0
VOL=$(snapshot_field "['tracks']['tracks'][0]['volume']")
assert_equals "track 0 volume is 0.3 after setVolume" "$VOL" "0.3"

# setMute
RESP=$(rpc '{"jsonrpc":"2.0","method":"track/setMute","params":{"index":0,"muted":true},"id":21}')
assert_contains "track/setMute returns ok" "$RESP" '"ok"'
sleep 1.0
MUTE=$(snapshot_field "['tracks']['tracks'][0]['mute']")
assert_equals "track 0 mute is True after setMute" "$MUTE" "True"

# Restore track state
ORIG_MUTE_LC=$(echo "$ORIG_MUTE" | tr '[:upper:]' '[:lower:]')
rpc "{\"jsonrpc\":\"2.0\",\"method\":\"track/setVolume\",\"params\":{\"index\":0,\"value\":${ORIG_VOL}},\"id\":94}" > /dev/null
rpc "{\"jsonrpc\":\"2.0\",\"method\":\"track/setMute\",\"params\":{\"index\":0,\"muted\":${ORIG_MUTE_LC}},\"id\":95}" > /dev/null
sleep 0.5

echo "--- Track Management Workflow ---"

# Create an audio track
RESP=$(rpc '{"jsonrpc":"2.0","method":"track/createAudio","params":{"position":-1},"id":141}')
assert_contains "track/createAudio returns ok" "$RESP" '"ok":true'
assert_contains "track/createAudio returns cursorTrackName" "$RESP" '"cursorTrackName"'
sleep 0.5

# Select by index (track 0)
RESP=$(rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":143}')
assert_contains "track/select returns ok" "$RESP" '"ok":true'
assert_contains "track/select returns trackName" "$RESP" '"trackName"'
sleep 0.5

# Rename the selected track
RESP=$(rpc '{"jsonrpc":"2.0","method":"track/rename","params":{"name":"SmokeTestTrack"},"id":144}')
assert_contains "track/rename returns ok" "$RESP" '"ok":true'
assert_contains "track/rename returns cursorTrackName" "$RESP" '"cursorTrackName"'
sleep 0.5

# Verify rename in snapshot
NAME=$(snapshot_field "['tracks']['tracks'][0]['name']")
assert_equals "track 0 renamed to SmokeTestTrack" "$NAME" "SmokeTestTrack"

# Duplicate the track
RESP=$(rpc '{"jsonrpc":"2.0","method":"track/duplicate","params":{},"id":145}')
assert_contains "track/duplicate returns ok" "$RESP" '"ok":true'
sleep 0.5

# Create instrument track
RESP=$(rpc '{"jsonrpc":"2.0","method":"track/createInstrument","params":{},"id":146}')
assert_contains "track/createInstrument returns ok" "$RESP" '"ok":true'
sleep 0.5

# Delete the instrument track we just created
RESP=$(rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":147}')
assert_contains "track/deleteSelected returns ok" "$RESP" '"ok":true'
sleep 0.5

echo "--- Track Select Error ---"

ERR=$(rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":999},"id":148}')
assert_contains "track/select out-of-range returns -32602" "$ERR" '-32602'
assert_contains "track/select error mentions bank width" "$ERR" '8'

ERR=$(rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":-1},"id":149}')
assert_contains "track/select negative index returns -32602" "$ERR" '-32602'
