#!/usr/bin/env bash
#
# Manual verification: Notes
#
# Prerequisites: Bitwig running, bitwig-pal extension loaded.
# Script creates its own track and clip.
# Open the piano roll / detail editor to see notes.
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/_helpers.sh"

STEP_COUNT=7

echo ""
echo "=== Manual Verification: Notes ==="
echo ""
echo "Prerequisites: Bitwig running with bitwig-pal loaded."
echo ""

reset_project

# --- Setup: create track + clip ---

echo "  Creating an instrument track with a clip..."
rpc '{"jsonrpc":"2.0","method":"track/createInstrument","params":{},"id":60}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":61}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"clip/create","params":{"trackIndex":0,"slotIndex":0,"lengthInBeats":8},"id":62}' > /dev/null
sleep 1.0
rpc '{"jsonrpc":"2.0","method":"clip/select","params":{"trackIndex":0,"slotIndex":0},"id":63}' > /dev/null
sleep 1.0
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"EDIT"},"id":64}' > /dev/null
sleep 0.5
echo "  Done. Switched to Edit view — you should see the piano roll."
echo ""

# --- Write Notes ---

verify \
  "Write 2 notes (C4 at beat 1, E4 at beat 5)" \
  "Do you see two notes in the piano roll? C4 (MIDI 60) at the start and E4 (MIDI 64) at beat 5?" \
  '{"jsonrpc":"2.0","method":"clip/setNotes","params":{"notes":[{"x":0,"y":60,"velocity":0.8,"duration":1.0},{"x":4,"y":64,"velocity":0.6,"duration":1.0}]},"id":112}'

# --- Add More Notes ---

verify \
  "Add a G4 note at beat 3" \
  "Do you now see three notes? C4, G4 (MIDI 67) at beat 3, and E4?" \
  '{"jsonrpc":"2.0","method":"clip/setNotes","params":{"notes":[{"x":2,"y":67,"velocity":0.7,"duration":1.0}]},"id":113}'

# --- Clear Single Note ---

verify \
  "Clear the C4 note (beat 1, MIDI 60)" \
  "Is the C4 note at beat 1 gone? Only G4 and E4 should remain." \
  '{"jsonrpc":"2.0","method":"clip/clearNote","params":{"x":0,"y":60},"id":114}'

# --- Clear All ---

verify \
  "Clear all notes" \
  "Is the piano roll now empty?" \
  '{"jsonrpc":"2.0","method":"clip/clearAllNotes","id":116}'

# --- Write a chord ---

verify \
  "Write a C major chord (C4+E4+G4 at beat 1)" \
  "Do you see three stacked notes forming a C major chord at beat 1?" \
  '{"jsonrpc":"2.0","method":"clip/setNotes","params":{"notes":[{"x":0,"y":60,"velocity":0.8,"duration":2.0},{"x":0,"y":64,"velocity":0.8,"duration":2.0},{"x":0,"y":67,"velocity":0.8,"duration":2.0}]},"id":117}'

# --- Launch to hear it ---

verify \
  "Launch the clip" \
  "Can you hear the chord playing? (You need a synth loaded on the instrument track)" \
  '{"jsonrpc":"2.0","method":"clip/launch","params":{"trackIndex":0,"slotIndex":0},"id":118}'

rpc '{"jsonrpc":"2.0","method":"transport/stop","id":119}' > /dev/null
sleep 0.5

# --- Cleanup ---

echo ""
echo "  Cleaning up..."
rpc '{"jsonrpc":"2.0","method":"clip/delete","params":{"trackIndex":0,"slotIndex":0},"id":120}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":121}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":122}' > /dev/null
sleep 0.3

verify \
  "Cleanup complete" \
  "Is the project back to clean state?" \
  '{"jsonrpc":"2.0","method":"session/snapshot","id":99}'

# --- Summary ---

print_summary "Notes Manual Verification"
