#!/usr/bin/env bash
#
# Manual verification: Transport
#
# Prerequisites: Bitwig running, bitwig-pal extension loaded.
# No tracks or devices required.
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/_helpers.sh"

STEP_COUNT=9

echo ""
echo "=== Manual Verification: Transport ==="
echo ""
echo "Prerequisites: Bitwig running with bitwig-pal loaded."
echo ""

reset_project
_fetch_time_sig
echo "Detected time signature: ${_TS_NUM}/${_TS_DENOM} (${_BEATS_PER_BAR} beats per bar)"
echo ""

# --- Play / Stop ---

verify \
  "Play" \
  "Is Bitwig's transport now playing? (Play button lit, position moving)" \
  '{"jsonrpc":"2.0","method":"transport/play","id":10}'

verify \
  "Stop" \
  "Has the transport stopped?" \
  '{"jsonrpc":"2.0","method":"transport/stop","id":11}'

# --- Tempo ---

verify \
  "Set tempo to 135 BPM" \
  "Look at Bitwig's tempo display: Does it show 135 BPM?" \
  '{"jsonrpc":"2.0","method":"transport/setTempo","params":{"tempo":135},"id":12}'

# --- Position ---

POS_BEATS=4.0
POS_BAR=$(beats_to_bars "$POS_BEATS")

verify \
  "Set position to ${POS_BAR}" \
  "Look at Bitwig's position display: Is the playhead at ${POS_BAR} (beat ${POS_BEATS})?" \
  "{\"jsonrpc\":\"2.0\",\"method\":\"transport/setPosition\",\"params\":{\"beats\":${POS_BEATS}},\"id\":13}"

# --- Loop ---

verify \
  "Enable loop" \
  "Is the loop toggle now ON in the transport bar?" \
  '{"jsonrpc":"2.0","method":"transport/setLoop","params":{"enabled":true},"id":14}'

verify \
  "Disable loop" \
  "Is the loop toggle now OFF?" \
  '{"jsonrpc":"2.0","method":"transport/setLoop","params":{"enabled":false},"id":15}'

# --- Metronome ---

verify \
  "Enable metronome" \
  "Is the metronome/click button now ON? (If you press play, you should hear the click.)" \
  '{"jsonrpc":"2.0","method":"transport/setMetronome","params":{"enabled":true},"id":16}'

verify \
  "Disable metronome" \
  "Is the metronome/click button now OFF?" \
  '{"jsonrpc":"2.0","method":"transport/setMetronome","params":{"enabled":false},"id":17}'

# --- Restore ---

echo ""
echo "  Restoring transport state..."
rpc '{"jsonrpc":"2.0","method":"transport/setTempo","params":{"tempo":92},"id":90}' > /dev/null
rpc '{"jsonrpc":"2.0","method":"transport/setPosition","params":{"beats":0},"id":91}' > /dev/null

verify \
  "Restored defaults" \
  "Is the tempo back to 92 BPM and playhead at Bar 1?" \
  '{"jsonrpc":"2.0","method":"session/snapshot","id":99}'

# --- Summary ---

print_summary "Transport Manual Verification"
