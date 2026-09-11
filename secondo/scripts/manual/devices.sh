#!/usr/bin/env bash
#
# Manual verification: Devices
#
# Prerequisites: Bitwig running, secondo extension loaded.
# Starting state: Clean project (reset_project handles this).
# Script creates its own track and devices, cleans up after.
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/_helpers.sh"

STEP_COUNT=10

echo ""
echo "=== Manual Verification: Devices ==="
echo ""
echo "Prerequisites: Bitwig running with secondo loaded."
echo ""

reset_project

# --- Setup: create a track so we have a device chain to work with ---

echo "  Creating an instrument track..."
rpc '{"jsonrpc":"2.0","method":"track/createInstrument","params":{},"id":60}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":61}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"EDIT"},"id":62}' > /dev/null
sleep 0.5
echo "  Done. Switched to Edit view — device panel should be visible."
echo ""

# --- Insert Devices ---

verify \
  "Insert EQ-5 on track" \
  "Do you see an EQ-5 device appear in the track's device chain?" \
  '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"EQ-5"},"id":132}'

verify \
  "Insert Compressor (case-insensitive)" \
  "Do you see a Compressor device added to the chain? (Two devices total now)" \
  '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"compressor"},"id":133}'

# --- Enable/Disable ---

verify \
  "Disable device" \
  "Is the current device now bypassed/disabled? (Should appear dimmed or greyed out)" \
  '{"jsonrpc":"2.0","method":"device/setEnabled","params":{"enabled":false},"id":134}'

verify \
  "Re-enable device" \
  "Is the device active again? (No longer dimmed)" \
  '{"jsonrpc":"2.0","method":"device/setEnabled","params":{"enabled":true},"id":135}'

# --- Remove Devices (one at a time) ---

verify \
  "Remove first device (cursor should recover to remaining device)" \
  "Did one device disappear? Does the cursor land on the remaining device?" \
  '{"jsonrpc":"2.0","method":"device/remove","id":136}'

verify \
  "Remove second device (ISS-3 fix: cursor recovery after remove)" \
  "Is the device chain now completely empty? This previously failed before the cursor fix." \
  '{"jsonrpc":"2.0","method":"device/remove","id":137}'

# --- Master Device ---

echo ""
echo "  Now testing devices on the Master track."
pause "Click the Master track in Bitwig so you can see its device chain, then press Enter..."

verify \
  "Insert EQ-5 on Master" \
  "Do you see an EQ-5 appear in the Master track's device chain?" \
  '{"jsonrpc":"2.0","method":"masterDevice/insertBitwigDevice","params":{"name":"EQ-5"},"id":402}'

verify \
  "Disable Master device" \
  "Is the EQ-5 on Master now bypassed/disabled? (Dimmed)" \
  '{"jsonrpc":"2.0","method":"masterDevice/setEnabled","params":{"enabled":false},"id":405}'

verify \
  "Re-enable Master device" \
  "Is the EQ-5 on Master active again?" \
  '{"jsonrpc":"2.0","method":"masterDevice/setEnabled","params":{"enabled":true},"id":406}'

verify \
  "Remove Master device" \
  "Is the Master track's device chain now empty?" \
  '{"jsonrpc":"2.0","method":"masterDevice/remove","id":407}'

# --- Cleanup ---

echo ""
echo "  Cleaning up..."
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":500}' > /dev/null
sleep 0.3
rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":507}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":508}' > /dev/null
sleep 0.3

verify \
  "Cleanup complete" \
  "Is the project back to clean state (no tracks, no devices on master)?" \
  '{"jsonrpc":"2.0","method":"session/snapshot","id":99}'

# --- Summary ---

print_summary "Devices Manual Verification"
