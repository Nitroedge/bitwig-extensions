#!/usr/bin/env bash
#
# Manual verification: Tracks
#
# Prerequisites: Bitwig running, bitwig-pal extension loaded.
# Starting state: Any project (FX1 + Master is fine).
# NOTE: This script creates and deletes tracks — it cleans up after itself.
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/_helpers.sh"

STEP_COUNT=11

echo ""
echo "=== Manual Verification: Tracks ==="
echo ""
echo "Prerequisites: Bitwig running with bitwig-pal loaded."
echo ""

reset_project

# --- Create Tracks ---

verify \
  "Create audio track" \
  "Do you see a new Audio track appear in the track list?" \
  '{"jsonrpc":"2.0","method":"track/createAudio","params":{"position":-1},"id":141}'

verify \
  "Create instrument track" \
  "Do you see a new Instrument track appear?" \
  '{"jsonrpc":"2.0","method":"track/createInstrument","params":{},"id":142}'

# --- Volume (on the audio track we just created) ---

echo ""
echo "  Selecting the audio track (index 0)..."
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":143}' > /dev/null
sleep 0.5

MAX_DB=$(vol_to_db 1.0)

verify \
  "Set track volume to ${MAX_DB} (max)" \
  "Is the volume fader all the way up at ${MAX_DB}?" \
  '{"jsonrpc":"2.0","method":"track/setVolume","params":{"index":0,"value":1.0},"id":20}'

UNITY_VAL=$(db_to_vol 0)
UNITY_DB=$(vol_to_db "$UNITY_VAL")

verify \
  "Set track volume to ${UNITY_DB} (unity/default)" \
  "Is the volume fader back to ${UNITY_DB}?" \
  "{\"jsonrpc\":\"2.0\",\"method\":\"track/setVolume\",\"params\":{\"index\":0,\"value\":${UNITY_VAL}},\"id\":21}"

# --- Mute ---

verify \
  "Mute track" \
  "Is the track now muted? (Mute button lit / track dimmed)" \
  '{"jsonrpc":"2.0","method":"track/setMute","params":{"index":0,"muted":true},"id":22}'

verify \
  "Unmute track" \
  "Is the track unmuted again?" \
  '{"jsonrpc":"2.0","method":"track/setMute","params":{"index":0,"muted":false},"id":23}'

# --- Rename ---

verify \
  "Rename track to 'SmokeTestTrack'" \
  "Does the selected track now show the name 'SmokeTestTrack'?" \
  '{"jsonrpc":"2.0","method":"track/rename","params":{"name":"SmokeTestTrack"},"id":144}'

# --- Duplicate ---

verify \
  "Duplicate track" \
  "Do you see a second copy of 'SmokeTestTrack' appear?" \
  '{"jsonrpc":"2.0","method":"track/duplicate","params":{},"id":145}'

# --- Delete ---

verify \
  "Delete selected track" \
  "Did the duplicated track disappear?" \
  '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":147}'

# --- Cleanup ---

echo ""
echo "  Cleaning up all test tracks..."
# Delete remaining tracks we created (SmokeTestTrack, instrument track)
rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":150}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":151}' > /dev/null
sleep 0.5

verify \
  "Cleanup complete" \
  "Are all test tracks gone? Is your project back to its original state (FX1 + Master)?" \
  '{"jsonrpc":"2.0","method":"session/snapshot","id":99}'

# --- Summary ---

print_summary "Tracks Manual Verification"
