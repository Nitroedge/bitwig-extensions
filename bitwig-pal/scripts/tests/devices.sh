#
# Device tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: sections 15, 16, 24, 25, 26, 44, 45
#

echo "--- Device Snapshot ---"

SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":80}')
assert_contains "snapshot has device" "$SNAP" '"device"'
assert_contains "device has cursorTrackName" "$SNAP" '"cursorTrackName"'
assert_contains "device has remoteControls" "$SNAP" '"remoteControls"'
assert_contains "remoteControls has pageIndex" "$SNAP" '"pageIndex"'
assert_contains "remoteControls has parameters" "$SNAP" '"parameters"'
PARAM_COUNT=$(echo "$SNAP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']['device']['remoteControls']['parameters']))")
assert_equals "device has 8 parameters" "$PARAM_COUNT" "8"

echo "--- Cursor Track Navigation ---"

ORIG_TRACK=$(snapshot_field "['device']['cursorTrackName']")
RESP=$(rpc '{"jsonrpc":"2.0","method":"cursor/selectTrack","params":{"direction":"next"},"id":81}')
assert_contains "cursor/selectTrack next returns ok" "$RESP" '"ok"'
sleep 0.5
NEW_TRACK=$(snapshot_field "['device']['cursorTrackName']")
if [ "$NEW_TRACK" != "$ORIG_TRACK" ]; then
  TOTAL=$((TOTAL + 1))
  echo "  PASS  cursor track changed after selectTrack next"
  PASS=$((PASS + 1))
  # Navigate back
  rpc '{"jsonrpc":"2.0","method":"cursor/selectTrack","params":{"direction":"previous"},"id":82}' > /dev/null
  sleep 0.5
else
  assert_skip "cursor track changed after selectTrack next" "only 1 track in project"
fi

echo "--- List Bitwig Devices ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/listBitwigDevices","id":131}')
assert_contains "listBitwigDevices returns Polymer" "$RESP" '"Polymer"'
assert_contains "listBitwigDevices returns EQ-5" "$RESP" '"EQ-5"'
assert_contains "listBitwigDevices returns Compressor" "$RESP" '"Compressor"'

echo "--- Insert + Remove Device ---"

# Insert a device
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"EQ-5"},"id":132}')
assert_contains "insertBitwigDevice returns ok" "$RESP" '"ok"'
sleep 0.5

# Insert with case-insensitive name
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"compressor"},"id":133}')
assert_contains "insertBitwigDevice case-insensitive returns ok" "$RESP" '"ok"'
sleep 0.5

# Remove the device (removes cursor device)
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/remove","id":134}')
assert_contains "device/remove returns ok" "$RESP" '"ok"'
sleep 0.5

# Remove the other device we inserted
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/remove","id":135}')
assert_contains "device/remove second returns ok" "$RESP" '"ok"'
sleep 0.5

echo "--- Device Insertion Errors ---"

ERR=$(rpc '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"NonexistentDevice12345"},"id":136}')
assert_contains "insertBitwigDevice unknown name returns -32602" "$ERR" '-32602'
assert_contains "insertBitwigDevice unknown name has error message" "$ERR" 'Unknown device'

ERR=$(rpc '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"Polymorph"},"id":137}')
assert_contains "insertBitwigDevice close match suggests Polymer" "$ERR" 'Polymer'

ERR=$(rpc '{"jsonrpc":"2.0","method":"device/insertPluginDevice","params":{"type":"invalid","id":"123"},"id":138}')
assert_contains "insertPluginDevice invalid type returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{},"id":139}')
assert_contains "insertBitwigDevice missing name returns -32602" "$ERR" '-32602'

echo "--- Master Device ---"

# Verify masterDevice methods in api/list
RESP=$(rpc '{"jsonrpc":"2.0","method":"api/list","id":400}')
assert_contains "api/list includes masterDevice/selectNext" "$RESP" 'masterDevice/selectNext'
assert_contains "api/list includes masterDevice/insertBitwigDevice" "$RESP" 'masterDevice/insertBitwigDevice'
assert_contains "api/list includes masterDevice/setParameterValue" "$RESP" 'masterDevice/setParameterValue'

# Verify snapshot has masterDevice section
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":401}')
assert_contains "snapshot has masterDevice section" "$SNAP" '"masterDevice"'
assert_contains "snapshot masterDevice has remoteControls" "$SNAP" '"remoteControls"'

# Insert a device on master
RESP=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/insertBitwigDevice","params":{"name":"EQ-5"},"id":402}')
assert_contains "masterDevice/insertBitwigDevice returns ok" "$RESP" '"ok"'
sleep 0.5

# Verify device shows in snapshot
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":403}')
assert_contains "masterDevice snapshot shows EQ-5" "$SNAP" 'EQ-5'

# Set parameter
RESP=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/setParameterValue","params":{"index":0,"value":0.75},"id":404}')
assert_contains "masterDevice/setParameterValue returns ok" "$RESP" '"ok"'

# Enable/disable
RESP=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/setEnabled","params":{"enabled":false},"id":405}')
assert_contains "masterDevice/setEnabled returns ok" "$RESP" '"ok"'
RESP=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/setEnabled","params":{"enabled":true},"id":406}')
assert_contains "masterDevice/setEnabled re-enable returns ok" "$RESP" '"ok"'

# Remove master device
RESP=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/remove","id":407}')
assert_contains "masterDevice/remove returns ok" "$RESP" '"ok"'
sleep 0.5

# Error handling
ERR=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/setParameterValue","params":{"index":8,"value":0.5},"id":408}')
assert_contains "masterDevice/setParameterValue index out of range returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/insertBitwigDevice","params":{},"id":409}')
assert_contains "masterDevice/insertBitwigDevice missing name returns -32602" "$ERR" '-32602'

echo "--- Device Chain Navigation ---"

# Verify new methods in api/list
RESP=$(rpc '{"jsonrpc":"2.0","method":"api/list","id":500}')
assert_contains "api/list includes device/enterSlot" "$RESP" 'device/enterSlot'
assert_contains "api/list includes device/exitToParent" "$RESP" 'device/exitToParent'
assert_contains "api/list includes masterDevice/enterSlot" "$RESP" 'masterDevice/enterSlot'
assert_contains "api/list includes masterDevice/exitToParent" "$RESP" 'masterDevice/exitToParent'

# Verify snapshot has nesting fields
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":501}')
assert_contains "device snapshot has isNested field" "$SNAP" '"isNested"'
assert_contains "device snapshot has hasSlots field" "$SNAP" '"hasSlots"'
assert_contains "device snapshot has slotNames field" "$SNAP" '"slotNames"'
assert_contains "device snapshot has hasLayers field" "$SNAP" '"hasLayers"'
assert_contains "device snapshot has hasDrumPads field" "$SNAP" '"hasDrumPads"'

# Insert Instrument Layer on track to test chain navigation
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"Instrument Layer"},"id":502}')
assert_contains "insert Instrument Layer returns ok" "$RESP" '"ok"'
sleep 0.5

# Verify Instrument Layer shows in snapshot
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":503}')
assert_contains "Instrument Layer shows in snapshot" "$SNAP" 'Instrument Layer'

# Enter slot and exit
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/enterSlot","params":{"name":"Chain 1"},"id":504}')
assert_contains "device/enterSlot returns ok" "$RESP" '"ok"'
sleep 0.5

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/exitToParent","id":505}')
assert_contains "device/exitToParent returns ok" "$RESP" '"ok"'
sleep 0.5

# Remove test device
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/remove","id":506}')
assert_contains "remove Instrument Layer returns ok" "$RESP" '"ok"'
sleep 0.5

# enterSlot missing name returns error
ERR=$(rpc '{"jsonrpc":"2.0","method":"device/enterSlot","params":{},"id":507}')
assert_contains "device/enterSlot missing name returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/enterSlot","params":{},"id":508}')
assert_contains "masterDevice/enterSlot missing name returns -32602" "$ERR" '-32602'

echo "--- Device Discovery + Preset Export ---"

# Insert Polymer for discovery testing
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"Polymer"},"id":600}')
assert_contains "insert Polymer for discovery returns ok" "$RESP" '"ok"'
sleep 0.5

# Start discovery scan
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/discoverAll","params":{},"id":601}')
assert_contains "discoverAll returns scanning" "$RESP" '"scanning":true'
assert_contains "discoverAll returns pageCount" "$RESP" '"pageCount"'

# Wait for scan to complete (7 pages × 100ms + buffer)
sleep 1.5

# Get result in full format (default)
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/getDiscoveryResult","params":{},"id":602}')
assert_contains "getDiscoveryResult full has deviceName" "$RESP" '"deviceName"'
assert_contains "getDiscoveryResult full has parameters" "$RESP" '"parameters"'
assert_contains "getDiscoveryResult full has displayedValue" "$RESP" '"displayedValue"'
assert_contains "getDiscoveryResult full has Polymer" "$RESP" '"Polymer"'

# Run discovery again for preset format test
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/discoverAll","params":{},"id":603}')
assert_contains "discoverAll second scan returns scanning" "$RESP" '"scanning":true'
sleep 1.5

# Get result in preset format
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/getDiscoveryResult","params":{"format":"preset"},"id":604}')
assert_contains "getDiscoveryResult preset has deviceName" "$RESP" '"deviceName"'
assert_contains "getDiscoveryResult preset has pageIndex" "$RESP" '"pageIndex"'
assert_contains "getDiscoveryResult preset has params" "$RESP" '"params"'

# Preset format should NOT contain full format fields
TOTAL=$((TOTAL + 1))
if echo "$RESP" | grep -q '"displayedValue"'; then
  echo "  FAIL  getDiscoveryResult preset should not contain displayedValue"
  FAIL=$((FAIL + 1))
else
  echo "  PASS  getDiscoveryResult preset omits displayedValue"
  PASS=$((PASS + 1))
fi

# Verify no discovery returns error
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/getDiscoveryResult","params":{},"id":605}')
assert_contains "getDiscoveryResult with no scan returns error" "$RESP" 'No discovery in progress'

# Clean up — remove the Polymer we inserted
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/remove","id":606}')
assert_contains "remove Polymer after discovery returns ok" "$RESP" '"ok"'
sleep 0.5
