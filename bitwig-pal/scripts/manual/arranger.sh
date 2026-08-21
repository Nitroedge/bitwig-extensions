#!/usr/bin/env bash
#
# Manual verification: Arranger
#
# Prerequisites: Bitwig running, bitwig-pal extension loaded.
# No tracks or devices required.
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/_helpers.sh"

STEP_COUNT=6

echo ""
echo "=== Manual Verification: Arranger ==="
echo ""
echo "Prerequisites: Bitwig running with bitwig-pal loaded."
echo ""

reset_project
_fetch_time_sig
echo "Detected time signature: ${_TS_NUM}/${_TS_DENOM} (${_BEATS_PER_BAR} beats per bar)"
echo ""

# --- Playback Follow ---

verify \
  "Enable playback follow" \
  "Look at Bitwig's arranger toolbar: Is the 'Follow Playback' button now ON/highlighted?" \
  '{"jsonrpc":"2.0","method":"arranger/setPlaybackFollow","params":{"enabled":true},"id":2}'

verify \
  "Disable playback follow" \
  "Is the 'Follow Playback' button now OFF?" \
  '{"jsonrpc":"2.0","method":"arranger/setPlaybackFollow","params":{"enabled":false},"id":3}'

# --- Loop Range ---

LOOP_START=8.0
LOOP_DUR=16.0
LOOP_END=$(python3 -c "print($LOOP_START + $LOOP_DUR)")
LOOP_START_BAR=$(beats_to_bars "$LOOP_START")
LOOP_END_BAR=$(beats_to_bars "$LOOP_END")
LOOP_DUR_BARS=$(beats_to_duration "$LOOP_DUR")

verify \
  "Set loop range (${LOOP_START_BAR} to ${LOOP_END_BAR}, ${LOOP_DUR_BARS})" \
  "Look at the timeline ruler: Do you see a loop bracket from ${LOOP_START_BAR} to ${LOOP_END_BAR} (${LOOP_DUR_BARS})? Is the loop toggle enabled?" \
  "{\"jsonrpc\":\"2.0\",\"method\":\"transport/setLoopRange\",\"params\":{\"start\":${LOOP_START},\"duration\":${LOOP_DUR},\"enabled\":true},\"id\":4}"

verify \
  "Disable loop" \
  "Is the loop bracket gone from the timeline ruler?" \
  '{"jsonrpc":"2.0","method":"transport/setLoopRange","params":{"start":0.0,"duration":4.0,"enabled":false},"id":6}'

# --- Automation Write Mode ---

verify \
  "Set automation write mode to TOUCH" \
  "Look at the automation section (arranger toolbar): Does it show 'Touch' as the write mode?" \
  '{"jsonrpc":"2.0","method":"transport/setAutomationWriteMode","params":{"mode":"touch"},"id":7}'

verify \
  "Set automation write mode to LATCH" \
  "Does the automation write mode now show 'Latch'?" \
  '{"jsonrpc":"2.0","method":"transport/setAutomationWriteMode","params":{"mode":"latch"},"id":8}'

# --- Summary ---

print_summary "Arranger Manual Verification"
