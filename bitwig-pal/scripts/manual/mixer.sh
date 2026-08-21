#!/usr/bin/env bash
#
# Manual verification: Mixer
#
# Prerequisites: Bitwig running, bitwig-pal extension loaded.
# Starting state: Clean project (reset_project handles this).
# Script creates its own track for testing.
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/_helpers.sh"

STEP_COUNT=7

echo ""
echo "=== Manual Verification: Mixer ==="
echo ""
echo "Prerequisites: Bitwig running with bitwig-pal loaded."
echo ""

reset_project

# --- Setup: create a track, switch to mix view ---

echo "  Creating an audio track..."
rpc '{"jsonrpc":"2.0","method":"track/createAudio","params":{"position":-1},"id":60}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":61}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"MIX"},"id":62}' > /dev/null
sleep 0.5
echo "  Done. Switched to Mix view."
echo ""

# --- Track Color ---

verify \
  "Set track color to red (r=0.8, g=0.2, b=0.2)" \
  "Does the track header/color strip turn red?" \
  '{"jsonrpc":"2.0","method":"track/setColor","params":{"index":0,"r":0.8,"g":0.2,"b":0.2},"id":302}'

verify \
  "Set track color to blue (r=0.2, g=0.2, b=0.8)" \
  "Does the track color change to blue?" \
  '{"jsonrpc":"2.0","method":"track/setColor","params":{"index":0,"r":0.2,"g":0.2,"b":0.8},"id":303}'

# --- Master Mute/Solo ---

verify \
  "Mute Master" \
  "Is the Master track now muted? (Mute button lit, audio silenced)" \
  '{"jsonrpc":"2.0","method":"master/setMute","params":{"value":true},"id":304}'

verify \
  "Unmute Master" \
  "Is the Master track unmuted again?" \
  '{"jsonrpc":"2.0","method":"master/setMute","params":{"value":false},"id":305}'

verify \
  "Solo Master" \
  "Is the Master track now soloed? (Solo button lit)" \
  '{"jsonrpc":"2.0","method":"master/setSolo","params":{"value":true},"id":306}'

verify \
  "Unsolo Master" \
  "Is the Master track unsoloed?" \
  '{"jsonrpc":"2.0","method":"master/setSolo","params":{"value":false},"id":307}'

# --- Cleanup ---

echo ""
echo "  Cleaning up..."
rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":310}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":311}' > /dev/null
sleep 0.3

verify \
  "Cleanup complete" \
  "Is the project back to clean state?" \
  '{"jsonrpc":"2.0","method":"session/snapshot","id":99}'

# --- Summary ---

print_summary "Mixer Manual Verification"
