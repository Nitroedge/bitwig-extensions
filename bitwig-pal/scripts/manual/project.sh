#!/usr/bin/env bash
#
# Manual verification: Project & App
#
# Prerequisites: Bitwig running, bitwig-pal extension loaded.
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/_helpers.sh"

STEP_COUNT=6

echo ""
echo "=== Manual Verification: Project & App ==="
echo ""
echo "Prerequisites: Bitwig running with bitwig-pal loaded."
echo ""

reset_project

# --- Notification ---

verify \
  "Show notification" \
  "Do you see a notification popup in Bitwig saying 'Hello from bitwig-pal'?" \
  '{"jsonrpc":"2.0","method":"app/showNotification","params":{"text":"Hello from bitwig-pal"},"id":606}'

# --- Panel Layout ---

verify \
  "Set panel layout to MIX" \
  "Did Bitwig switch to the Mix panel layout?" \
  '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"MIX"},"id":607}'

verify \
  "Set panel layout to ARRANGE" \
  "Did Bitwig switch back to the Arrange layout?" \
  '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":608}'

verify \
  "Set panel layout to EDIT" \
  "Did Bitwig switch to the Edit (detail editor) layout?" \
  '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"EDIT"},"id":609}'

# Restore
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":610}' > /dev/null

# --- Pre-Roll ---

verify \
  "Set pre-roll to one bar" \
  "Look at the transport pre-roll setting: Does it show '1 Bar' pre-roll?" \
  '{"jsonrpc":"2.0","method":"transport/setPreRoll","params":{"value":"one_bar"},"id":611}'

# Restore
rpc '{"jsonrpc":"2.0","method":"transport/setPreRoll","params":{"value":"none"},"id":612}' > /dev/null

verify \
  "Restore pre-roll to none" \
  "Is the pre-roll setting back to 'None'?" \
  '{"jsonrpc":"2.0","method":"session/snapshot","id":99}'

# --- Summary ---

print_summary "Project & App Manual Verification"
