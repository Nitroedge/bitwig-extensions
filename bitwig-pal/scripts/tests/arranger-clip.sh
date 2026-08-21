#
# Arranger clip content tests (online — requires Bitwig running AND an arranger clip
# selected by hand in the arranger timeline).
#
# Sourced by the runner — do NOT add a shebang or `set -euo pipefail`.
#
# This is NOT an extension of arranger.sh. That file covers arranger VIEW control —
# visibility, zoom, cue markers, playback follow — matching ArrangerHandler. None of it is
# clip content. This file covers the arrangerClip/* namespace: the notes, transforms,
# boundaries and identity of the one clip the user has selected on the timeline.
#
# Three properties every assertion below is written to hold:
#
#   1. A JSON-RPC "ok" is an acknowledgement that a call was accepted for dispatch. It is
#      never evidence that Bitwig did anything. Every mutation here is therefore followed by
#      `sleep 0.5` and a read-back, and where the API publishes no read-back for a mutation
#      that fact is recorded as a SKIP naming what cannot be checked, rather than the "ok"
#      being quietly promoted to proof.
#   2. Refusals are asserted as refusals. A namespace that only ever gets tested on its happy
#      path has no evidence its bounds exist.
#   3. The excluded launch-shaped group is asserted ABSENT by name. A method that compiled but
#      resolved a ClipLauncherSlot would answer "ok" and do nothing, which is the exact failure
#      the arrangerClip/* split exists to prevent.
#
# The script leaves the clip as it found it: each transform is inverted and the viewport is
# restored.
#
# JSON-RPC ids: 1001-1030, chosen to sit above every id already in use in this directory
# (highest previously was 908) and well clear of arranger.sh's 161-173.
#

echo "--- Arranger Clip Snapshot ---"

SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":1001}')
assert_contains "snapshot has arrangerClip section" "$SNAP" '"arrangerClip"'

# `exists` is the discriminator every other field must be read through: on deselect the
# siblings report real zeros, and a playStart of 0.0 is indistinguishable from a clip that
# genuinely sits at bar 1.
ARRCLIP_EXISTS=$(snapshot_field ".get('arrangerClip',{}).get('exists','ABSENT')")
assert_equals "arrangerClip section carries exists" \
  "$(if [ "$ARRCLIP_EXISTS" = "ABSENT" ]; then echo missing; else echo present; fi)" "present"

echo "--- Arranger Clip State ---"

STATE=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/getState","id":1002}')
assert_contains "getState has exists" "$STATE" '"exists"'
assert_contains "getState has trackName" "$STATE" '"trackName"'
assert_contains "getState has loopLength" "$STATE" '"loopLength"'
assert_contains "getState has playStart" "$STATE" '"playStart"'
assert_contains "getState has color" "$STATE" '"color"'

# loopLength is the clip's internal LOOP REGION; playStop is the end of its PLAY RANGE. They
# are different numbers on the same clip — 9.17 against 16.0 was observed on an ordinary
# four-bar clip — so both are published and neither stands in for the other.
assert_contains "getState publishes the play range separately from the loop region" "$STATE" '"playStop"'

# --- selection gate -----------------------------------------------------------------------
#
# Everything below this line acts on the clip the user selected. With nothing selected there
# is no clip to read or transform, and a FAIL would be a report about the session rather than
# about the engine. Those assertions are SKIPped with the reason named instead.

arrclip_notes() {
  # The notes array only, with the JSON-RPC envelope and its id stripped, so two reads of an
  # unchanged clip compare equal.
  if [ -z "${PYTHON_BIN:-}" ]; then
    echo "PYTHON_NOT_FOUND"
    return 0
  fi
  rpc "{\"jsonrpc\":\"2.0\",\"method\":\"arrangerClip/getNotes\",\"id\":$1}" | \
    "$PYTHON_BIN" -c "import sys,json; print(json.dumps(json.load(sys.stdin).get('result',[])))"
}

arrclip_assert_differs() {
  local label="$1" actual="$2" unexpected="$3"
  TOTAL=$((TOTAL + 1))
  if [ "$actual" != "$unexpected" ]; then
    echo "  PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $label — expected a change, got the same payload back"
    FAIL=$((FAIL + 1))
  fi
}

ARRCLIP_SELECTED="no"
[ "$ARRCLIP_EXISTS" = "True" ] && ARRCLIP_SELECTED="yes"

echo "--- Arranger Clip Read ---"

if [ "$ARRCLIP_SELECTED" = "yes" ]; then
  NOTES=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/getNotes","id":1003}')
  assert_contains "getNotes answers an array" "$NOTES" '"result":['
  NOTES_BEFORE=$(arrclip_notes 1004)
else
  assert_skip "getNotes answers an array" "no arranger clip selected (exists=$ARRCLIP_EXISTS)"
  NOTES_BEFORE="[]"
fi

echo "--- Arranger Clip Transform ---"

if [ "$ARRCLIP_SELECTED" = "yes" ] && [ "$NOTES_BEFORE" != "[]" ] && [ "$NOTES_BEFORE" != "PYTHON_NOT_FOUND" ]; then
  RESP=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/transpose","params":{"semitones":12},"id":1005}')
  assert_contains "transpose +12 is accepted for dispatch" "$RESP" '"ok"'
  sleep 0.5
  # The read-back, not the "ok", is the assertion. The "ok" above says only that the call was
  # accepted; this says the notes actually moved.
  NOTES_AFTER=$(arrclip_notes 1006)
  arrclip_assert_differs "getNotes differs after transpose (read back, not the reply)" \
    "$NOTES_AFTER" "$NOTES_BEFORE"

  rpc '{"jsonrpc":"2.0","method":"arrangerClip/transpose","params":{"semitones":-12},"id":1007}' > /dev/null
  sleep 0.5
  NOTES_RESTORED=$(arrclip_notes 1008)
  assert_equals "inverse transpose returns the clip to as-found" "$NOTES_RESTORED" "$NOTES_BEFORE"
else
  assert_skip "getNotes differs after transpose (read back, not the reply)" \
    "no arranger clip with notes selected"
  assert_skip "inverse transpose returns the clip to as-found" \
    "no arranger clip with notes selected"
fi

echo "--- Arranger Clip Viewport ---"

STEP_BEFORE=$(snapshot_field ".get('arrangerClip',{}).get('stepSize','ABSENT')")

RESP=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/setStepSize","params":{"size":0.25},"id":1009}')
assert_contains "setStepSize is accepted for dispatch" "$RESP" '"ok"'
sleep 0.5
STEP_NOW=$(snapshot_field "['arrangerClip']['stepSize']")
assert_equals "stepSize reads back 0.25 from the snapshot" "$STEP_NOW" "0.25"

# scrollSteps and scrollToKey have NO published read-back: the Controller API exposes no getter
# for the step or key window, so the snapshot carries neither. Their replies are asserted as
# what they are — acknowledgements of dispatch — and the missing verification is recorded here
# rather than papered over by treating "ok" as proof.
RESP=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/scrollSteps","params":{"offset":0},"id":1010}')
assert_contains "scrollSteps is accepted for dispatch" "$RESP" '"ok"'
assert_skip "scrollSteps moved the step window" "no published read-back — the API exposes no step-window getter"

RESP=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/scrollToKey","params":{"key":60},"id":1011}')
assert_contains "scrollToKey is accepted for dispatch" "$RESP" '"ok"'
assert_skip "scrollToKey moved the key window" "no published read-back — the API exposes no key-window getter"

# Restore the viewport if there was one to restore.
if [ "$STEP_BEFORE" != "ABSENT" ] && [ "$STEP_BEFORE" != "PYTHON_NOT_FOUND" ] && [ -n "$STEP_BEFORE" ]; then
  rpc "{\"jsonrpc\":\"2.0\",\"method\":\"arrangerClip/setStepSize\",\"params\":{\"size\":${STEP_BEFORE}},\"id\":1012}" > /dev/null
  sleep 0.5
fi

echo "--- Arranger Clip Error Paths ---"

ERR=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/scrollToKey","params":{"key":200},"id":1013}')
assert_contains "scrollToKey out-of-range returns -32602" "$ERR" '-32602'
assert_contains "scrollToKey refusal names the bound" "$ERR" 'key must be 0-127'

ERR=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/quantize","params":{"amount":5.0},"id":1014}')
assert_contains "quantize out-of-range returns -32602" "$ERR" '-32602'
assert_contains "quantize refusal names the bound" "$ERR" 'between 0.0 and 1.0'

ERR=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/setNotes","params":{"notes":[{"x":256,"y":60}]},"id":1015}')
assert_contains "setNotes x out-of-range returns -32602" "$ERR" '-32602'
assert_contains "setNotes refusal names the grid bound" "$ERR" 'out of range (0-255)'

ERR=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/setColor","params":{"r":1.5,"g":0.0,"b":0.0},"id":1016}')
assert_contains "setColor component out-of-range returns -32602" "$ERR" '-32602'

ERR=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/setPlaybackSettings","params":{},"id":1017}')
assert_contains "setPlaybackSettings with no fields returns -32602" "$ERR" '-32602'
assert_contains "setPlaybackSettings refusal names every field it accepts" "$ERR" 'loopStart'
assert_contains "setPlaybackSettings refusal names playStop too" "$ERR" 'playStop'

echo "--- Arranger Clip Excluded Group ---"

# The proof that the launch-shaped and slot-resolving group is absent BY CONSTRUCTION rather
# than present-and-inert. -32601 is method-not-found: the name reaches no handler at all. An
# "ok" here would mean the opposite of what it says — a registration that resolved a
# ClipLauncherSlot an arranger clip does not have, accepting the call and doing nothing.
ERR=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/launch","params":{"trackIndex":0,"slotIndex":0},"id":1018}')
assert_contains "arrangerClip/launch is not registered (-32601)" "$ERR" '-32601'

ERR=$(rpc '{"jsonrpc":"2.0","method":"arrangerClip/setLaunchQuantization","params":{"quantization":"1"},"id":1019}')
assert_contains "arrangerClip/setLaunchQuantization is not registered (-32601)" "$ERR" '-32601'
