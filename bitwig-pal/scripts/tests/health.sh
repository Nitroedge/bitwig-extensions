#
# Health, snapshot, and API introspection tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: Tests 1, 2, 3, 7, 14, 17, 19, 20, 23, 27, 30, 39
#

# --- 1. Health Check ---
echo "--- Health Check ---"
HEALTH=$(curl -s "${BASE}/health")
assert_contains "GET /health returns status ok" "$HEALTH" '"status":"ok"'
assert_contains "GET /health returns version" "$HEALTH" '"version":"0.1.0"'

# --- 2. Session Snapshot ---
echo "--- Session Snapshot ---"
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":1}')
assert_contains "snapshot has transport" "$SNAP" '"transport"'
assert_contains "snapshot has tracks" "$SNAP" '"tracks"'
assert_contains "snapshot has master" "$SNAP" '"master"'
assert_contains "snapshot has application" "$SNAP" '"application"'
assert_contains "snapshot has isPlaying" "$SNAP" '"isPlaying"'
assert_contains "snapshot has tempo" "$SNAP" '"tempo"'

# --- 3. Introspection — api/list (core) ---
echo "--- API List (Core) ---"
LIST=$(rpc '{"jsonrpc":"2.0","method":"api/list","id":2}')
assert_contains "api/list has session/snapshot" "$LIST" '"session/snapshot"'
assert_contains "api/list has transport/play" "$LIST" '"transport/play"'
assert_contains "api/list has track/setVolume" "$LIST" '"track/setVolume"'
assert_contains "api/list has master/setVolume" "$LIST" '"master/setVolume"'
assert_contains "api/list has app/undo" "$LIST" '"app/undo"'

# --- 7. Application Actions ---
echo "--- Application Actions ---"
RESP=$(rpc '{"jsonrpc":"2.0","method":"app/getState","id":40}')
assert_contains "app/getState has projectName" "$RESP" '"projectName"'
assert_contains "app/getState has canUndo" "$RESP" '"canUndo"'
assert_contains "app/getState has hasActiveEngine" "$RESP" '"hasActiveEngine"'

# --- 14. Clip + Scene API List ---
echo "--- Clip + Scene API List ---"
assert_contains "api/list has clip/launch" "$LIST" '"clip/launch"'
assert_contains "api/list has clip/stop" "$LIST" '"clip/stop"'
assert_contains "api/list has clip/record" "$LIST" '"clip/record"'
assert_contains "api/list has clip/create" "$LIST" '"clip/create"'
assert_contains "api/list has clip/delete" "$LIST" '"clip/delete"'
assert_contains "api/list has scene/launch" "$LIST" '"scene/launch"'

# --- 17. Device + Cursor API List ---
echo "--- Device + Cursor API List ---"
assert_contains "api/list has device/selectNext" "$LIST" '"device/selectNext"'
assert_contains "api/list has device/selectPrevious" "$LIST" '"device/selectPrevious"'
assert_contains "api/list has device/setEnabled" "$LIST" '"device/setEnabled"'
assert_contains "api/list has device/selectPage" "$LIST" '"device/selectPage"'
assert_contains "api/list has device/nextPage" "$LIST" '"device/nextPage"'
assert_contains "api/list has device/previousPage" "$LIST" '"device/previousPage"'
assert_contains "api/list has device/setParameterValue" "$LIST" '"device/setParameterValue"'
assert_contains "api/list has cursor/selectTrack" "$LIST" '"cursor/selectTrack"'

# --- 19. Note + Transaction API List ---
echo "--- Note + Transaction API List ---"
assert_contains "api/list has clip/select" "$LIST" '"clip/select"'
assert_contains "api/list has clip/setNotes" "$LIST" '"clip/setNotes"'
assert_contains "api/list has clip/clearNote" "$LIST" '"clip/clearNote"'
assert_contains "api/list has clip/clearAllNotes" "$LIST" '"clip/clearAllNotes"'
assert_contains "api/list has clip/getNotes" "$LIST" '"clip/getNotes"'
assert_contains "api/list has clip/setStepSize" "$LIST" '"clip/setStepSize"'
assert_contains "api/list has clip/scrollSteps" "$LIST" '"clip/scrollSteps"'
assert_contains "api/list has session/transaction" "$LIST" '"session/transaction"'
assert_contains "api/list has macro/createTrack" "$LIST" '"macro/createTrack"'
assert_contains "api/list has macro/createClip" "$LIST" '"macro/createClip"'
assert_contains "api/list has macro/writeClip" "$LIST" '"macro/writeClip"'
assert_contains "api/list has macro/buildSection" "$LIST" '"macro/buildSection"'

# Verify total method count
METHOD_COUNT=$(echo "$LIST" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']))")
TOTAL=$((TOTAL + 1))
if [ "$METHOD_COUNT" -ge 113 ]; then
  echo "  PASS  api/list has >= 113 methods (found $METHOD_COUNT)"
  PASS=$((PASS + 1))
else
  echo "  FAIL  api/list has >= 113 methods — found $METHOD_COUNT"
  FAIL=$((FAIL + 1))
fi

# --- 20. Clip Snapshot Section ---
echo "--- Clip Snapshot Section ---"
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":101}')
assert_contains "snapshot has clip section" "$SNAP" '"clip"'
assert_contains "clip has trackName" "$SNAP" '"trackName"'
assert_contains "clip has playingStep" "$SNAP" '"playingStep"'
assert_contains "clip has loopLength" "$SNAP" '"loopLength"'

# --- 23. Device Insertion API List ---
echo "--- Device Insertion API List ---"
assert_contains "api/list has device/insertBitwigDevice" "$LIST" '"device/insertBitwigDevice"'
assert_contains "api/list has device/insertPluginDevice" "$LIST" '"device/insertPluginDevice"'
assert_contains "api/list has device/listBitwigDevices" "$LIST" '"device/listBitwigDevices"'
assert_contains "api/list has device/remove" "$LIST" '"device/remove"'

# --- 27. Track Management API List ---
echo "--- Track Management API List ---"
assert_contains "api/list has track/createAudio" "$LIST" '"track/createAudio"'
assert_contains "api/list has track/createInstrument" "$LIST" '"track/createInstrument"'
assert_contains "api/list has track/createEffect" "$LIST" '"track/createEffect"'
assert_contains "api/list has track/select" "$LIST" '"track/select"'
assert_contains "api/list has track/rename" "$LIST" '"track/rename"'
assert_contains "api/list has track/deleteSelected" "$LIST" '"track/deleteSelected"'
assert_contains "api/list has track/duplicate" "$LIST" '"track/duplicate"'

# --- 30. Arranger API List ---
echo "--- Arranger API List ---"
assert_contains "api/list has arranger/setPlaybackFollow" "$LIST" '"arranger/setPlaybackFollow"'
assert_contains "api/list has arranger/setClipLauncherVisible" "$LIST" '"arranger/setClipLauncherVisible"'
assert_contains "api/list has arranger/setTimelineVisible" "$LIST" '"arranger/setTimelineVisible"'
assert_contains "api/list has arranger/setCueMarkersVisible" "$LIST" '"arranger/setCueMarkersVisible"'
assert_contains "api/list has arranger/setEffectTracksVisible" "$LIST" '"arranger/setEffectTracksVisible"'
assert_contains "api/list has arranger/setIoSectionVisible" "$LIST" '"arranger/setIoSectionVisible"'
assert_contains "api/list has arranger/setDoubleRowTrackHeight" "$LIST" '"arranger/setDoubleRowTrackHeight"'
assert_contains "api/list has cueMarker/addAtPlayhead" "$LIST" '"cueMarker/addAtPlayhead"'
assert_contains "api/list has cueMarker/list" "$LIST" '"cueMarker/list"'
assert_contains "api/list has cueMarker/launch" "$LIST" '"cueMarker/launch"'
assert_contains "api/list has cueMarker/delete" "$LIST" '"cueMarker/delete"'
assert_contains "api/list has transport/setLoopRange" "$LIST" '"transport/setLoopRange"'
assert_contains "api/list has transport/getLoopRange" "$LIST" '"transport/getLoopRange"'
assert_contains "api/list has transport/setPunchIn" "$LIST" '"transport/setPunchIn"'
assert_contains "api/list has transport/setPunchOut" "$LIST" '"transport/setPunchOut"'
assert_contains "api/list has transport/setAutomationWriteMode" "$LIST" '"transport/setAutomationWriteMode"'
assert_contains "api/list has transport/setArrangerAutomationWrite" "$LIST" '"transport/setArrangerAutomationWrite"'
assert_contains "api/list has transport/setClipLauncherAutomationWrite" "$LIST" '"transport/setClipLauncherAutomationWrite"'
assert_contains "api/list has transport/resetAutomationOverrides" "$LIST" '"transport/resetAutomationOverrides"'

# --- 39. Mixer & Routing API List ---
echo "--- Mixer & Routing API List ---"
assert_contains "api/list has send/setLevel" "$LIST" '"send/setLevel"'
assert_contains "api/list has send/setMode" "$LIST" '"send/setMode"'
assert_contains "api/list has send/setEnabled" "$LIST" '"send/setEnabled"'
assert_contains "api/list has track/setColor" "$LIST" '"track/setColor"'
assert_contains "api/list has track/setCrossfade" "$LIST" '"track/setCrossfade"'
assert_contains "api/list has track/setMonitor" "$LIST" '"track/setMonitor"'
assert_contains "api/list has master/setMute" "$LIST" '"master/setMute"'
assert_contains "api/list has master/setSolo" "$LIST" '"master/setSolo"'
assert_contains "api/list has master/setColor" "$LIST" '"master/setColor"'
