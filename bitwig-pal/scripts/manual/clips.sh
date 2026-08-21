#!/usr/bin/env bash
#
# Manual verification: Clips & Scenes
#
# Prerequisites: Bitwig running, bitwig-pal extension loaded.
# Starting state: Any project (empty is fine — script creates its own track).
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/_helpers.sh"

STEP_COUNT=8

echo ""
echo "=== Manual Verification: Clips & Scenes ==="
echo ""
echo "Prerequisites: Bitwig running with bitwig-pal loaded."
echo ""

reset_project
_fetch_time_sig
CLIP_LEN=4
CLIP_BARS=$(beats_to_duration "$CLIP_LEN")
echo "Detected time signature: ${_TS_NUM}/${_TS_DENOM} (${_BEATS_PER_BAR} beats per bar)"
echo ""

# --- Setup: create a track ---

echo "  Creating an instrument track for clip testing..."
rpc '{"jsonrpc":"2.0","method":"track/createInstrument","params":{},"id":60}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":61}' > /dev/null
sleep 0.5
echo "  Done. Look at the clip launcher for the new track."
echo ""

# --- Create Clip ---

verify \
  "Create clip (${CLIP_BARS}) on slot 1" \
  "Do you see a new empty clip appear in slot 1 of the track's clip launcher?" \
  "{\"jsonrpc\":\"2.0\",\"method\":\"clip/create\",\"params\":{\"trackIndex\":0,\"slotIndex\":0,\"lengthInBeats\":${CLIP_LEN}},\"id\":71}"

# --- Launch Clip ---

verify \
  "Launch clip" \
  "Is the clip now playing? (Clip should show a play indicator, transport starts)" \
  '{"jsonrpc":"2.0","method":"clip/launch","params":{"trackIndex":0,"slotIndex":0},"id":72}'

# --- Stop Clip ---

verify \
  "Stop clip" \
  "Has the clip stopped playing? (Play indicator gone)" \
  '{"jsonrpc":"2.0","method":"clip/stop","params":{"trackIndex":0},"id":73}'

# Stop transport too
rpc '{"jsonrpc":"2.0","method":"transport/stop","id":74}' > /dev/null
sleep 0.5

# --- Create a second clip ---

verify \
  "Create clip on slot 2" \
  "Do you see a second clip appear in slot 2?" \
  "{\"jsonrpc\":\"2.0\",\"method\":\"clip/create\",\"params\":{\"trackIndex\":0,\"slotIndex\":1,\"lengthInBeats\":${CLIP_LEN}},\"id\":75}"

# --- Scene Launch ---

verify \
  "Launch scene 1" \
  "Did scene 1 launch? (All clips in row 1 should trigger, transport starts)" \
  '{"jsonrpc":"2.0","method":"scene/launch","params":{"index":0},"id":76}'

rpc '{"jsonrpc":"2.0","method":"transport/stop","id":77}' > /dev/null
sleep 0.5

# --- Delete Clips ---

verify \
  "Delete clip from slot 1" \
  "Is slot 1 now empty in the clip launcher?" \
  '{"jsonrpc":"2.0","method":"clip/delete","params":{"trackIndex":0,"slotIndex":0},"id":78}'

verify \
  "Delete clip from slot 2" \
  "Is slot 2 now empty?" \
  '{"jsonrpc":"2.0","method":"clip/delete","params":{"trackIndex":0,"slotIndex":1},"id":79}'

# --- Cleanup ---

echo ""
echo "  Cleaning up test track..."
rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":80}' > /dev/null
sleep 0.5

verify \
  "Cleanup complete" \
  "Is the test track gone? Project back to original state?" \
  '{"jsonrpc":"2.0","method":"session/snapshot","id":99}'

# --- Summary ---

print_summary "Clips & Scenes Manual Verification"
