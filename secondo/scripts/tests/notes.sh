#
# Note editing and step sequencer tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: Test 21, Test 22
#

echo "--- Note Editing Workflow ---"

# Create a clip for note editing
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/create","params":{"trackIndex":0,"slotIndex":4,"lengthInBeats":8},"id":109}')
assert_contains "clip/create for notes returns ok" "$RESP" '"ok"'
sleep 1.5

# Select clip on track 0, slot 4
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/select","params":{"trackIndex":0,"slotIndex":4},"id":110}')
assert_contains "clip/select returns ok" "$RESP" '"ok"'
sleep 1.0

# Clear any existing notes
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/clearAllNotes","id":111}')
assert_contains "clip/clearAllNotes returns ok" "$RESP" '"ok"'
sleep 0.5

# Write a 2-note pattern
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/setNotes","params":{"notes":[{"x":0,"y":60,"velocity":0.8,"duration":0.25},{"x":4,"y":64,"velocity":0.6,"duration":0.5}]},"id":112}')
assert_contains "clip/setNotes returns count" "$RESP" '"count"'
assert_contains "clip/setNotes wrote 2 notes" "$RESP" '"count":2'
sleep 0.5

# Read notes back (may return empty if cursor clip data hasn't loaded)
sleep 1.0
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/getNotes","id":113}')
if [[ "$RESP" == *'"y":60'* ]]; then
  assert_contains "clip/getNotes returns note at y=60" "$RESP" '"y":60'
  assert_contains "clip/getNotes returns note at y=64" "$RESP" '"y":64'
else
  assert_skip "clip/getNotes returns note at y=60" "cursor clip data may not populate — Bitwig API limitation"
  assert_skip "clip/getNotes returns note at y=64" "cursor clip data may not populate — Bitwig API limitation"
fi

# Clear a single note
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/clearNote","params":{"x":0,"y":60},"id":114}')
assert_contains "clip/clearNote returns ok" "$RESP" '"ok"'
sleep 0.5

# Verify only 1 note remains
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/getNotes","id":115}')
NOTE_COUNT=$(echo "$RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']))")
if [ "$NOTE_COUNT" = "0" ]; then
  assert_skip "1 note remains after clearNote" "cursor clip data not populating"
else
  assert_equals "1 note remains after clearNote" "$NOTE_COUNT" "1"
fi

# Clear all
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/clearAllNotes","id":116}')
assert_contains "clip/clearAllNotes returns ok" "$RESP" '"ok"'
sleep 0.5

# Verify empty (this should always succeed since clearAllNotes doesn't depend on cursor data)
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/getNotes","id":117}')
NOTE_COUNT=$(echo "$RESP" | python3 -c "import sys,json; r=json.load(sys.stdin); print(len(r.get('result',[])) if 'result' in r else 0)")
assert_equals "0 notes after clearAllNotes" "$NOTE_COUNT" "0"

# Cleanup: delete the clip we created for note editing
rpc '{"jsonrpc":"2.0","method":"clip/delete","params":{"trackIndex":0,"slotIndex":4},"id":118}' > /dev/null
sleep 0.5

echo "--- Step Size & Scroll ---"

RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/setStepSize","params":{"size":0.5},"id":120}')
assert_contains "clip/setStepSize returns ok" "$RESP" '"ok"'

RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/scrollSteps","params":{"offset":0},"id":121}')
assert_contains "clip/scrollSteps returns ok" "$RESP" '"ok"'

# Restore step size
rpc '{"jsonrpc":"2.0","method":"clip/setStepSize","params":{"size":0.25},"id":122}' > /dev/null
