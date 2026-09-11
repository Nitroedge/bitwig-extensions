#!/usr/bin/env bash
#
# Manual verification: End-to-end workflow
#
# Simulates a real user session:
#   1. Create a track
#   2. Add an instrument device
#   3. Add an FX device
#   4. Create a clip
#   5. Write notes (a melody)
#   6. Play it back
#   7. Adjust volume and tempo
#   8. Duplicate the track
#   9. Change track colors
#  10. Clean up
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../manual/_helpers.sh"

STEP_COUNT=20

echo ""
echo "=== Manual Verification: End-to-End Workflow ==="
echo ""
echo "This script walks through a realistic production workflow."
echo ""

reset_project
_fetch_time_sig
echo "Time signature: ${_TS_NUM}/${_TS_DENOM}"
echo ""

# ============================================================
# 1. Create an instrument track
# ============================================================

verify \
  "Create instrument track" \
  "Do you see a new instrument track in the track list?" \
  '{"jsonrpc":"2.0","method":"track/createInstrument","params":{},"id":1}'

sleep 0.5
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":2}' > /dev/null
sleep 0.5

verify \
  "Rename track to 'Lead Synth'" \
  "Does the track now show the name 'Lead Synth'?" \
  '{"jsonrpc":"2.0","method":"track/rename","params":{"name":"Lead Synth"},"id":3}'

# ============================================================
# 2. Add devices (ensure device panel is visible)
# ============================================================

rpc '{"jsonrpc":"2.0","method":"app/toggleDevices","id":4}' > /dev/null
sleep 0.3

verify \
  "Add Polymer synthesizer" \
  "Do you see a Polymer device appear in the device chain?" \
  '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"Polymer"},"id":5}'

verify \
  "Add Delay-2 effect" \
  "Do you see a Delay-2 added after the Polymer?" \
  '{"jsonrpc":"2.0","method":"device/insertBitwigDevice","params":{"name":"Delay-2"},"id":6}'

# ============================================================
# 3. Create a clip
# ============================================================

CLIP_LEN=16
CLIP_BARS=$(beats_to_duration "$CLIP_LEN")

verify \
  "Create a ${CLIP_BARS} clip in slot 1" \
  "Do you see a new clip appear in the first clip slot (clip launcher)?" \
  "{\"jsonrpc\":\"2.0\",\"method\":\"clip/create\",\"params\":{\"trackIndex\":0,\"slotIndex\":0,\"lengthInBeats\":${CLIP_LEN}},\"id\":7}"

# ============================================================
# 4. Write a melody (switch to edit view for piano roll)
# ============================================================

sleep 1.0
rpc '{"jsonrpc":"2.0","method":"clip/select","params":{"trackIndex":0,"slotIndex":0},"id":8}' > /dev/null
sleep 1.0
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"EDIT"},"id":4}' > /dev/null
sleep 0.5

verify \
  "Write a 4-note melody" \
  "Do you see 4 notes in the piano roll? C4 (bar 1), E4 (bar 2), G4 (bar 3), C5 (bar 4)?" \
  '{"jsonrpc":"2.0","method":"clip/setNotes","params":{"notes":[{"x":0,"y":60,"velocity":0.8,"duration":3.0},{"x":4,"y":64,"velocity":0.7,"duration":3.0},{"x":8,"y":67,"velocity":0.75,"duration":3.0},{"x":12,"y":72,"velocity":0.85,"duration":3.0}]},"id":9}'

# ============================================================
# 5. Play it back (switch to arrange view for full overview)
# ============================================================

rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":4}' > /dev/null
sleep 0.5

verify \
  "Set tempo to 110 BPM" \
  "Does the tempo display show 110 BPM?" \
  '{"jsonrpc":"2.0","method":"transport/setTempo","params":{"tempo":110},"id":10}'

verify \
  "Launch the clip" \
  "Can you hear the melody playing through Polymer + Delay-2?" \
  '{"jsonrpc":"2.0","method":"clip/launch","params":{"trackIndex":0,"slotIndex":0},"id":11}'

pause "Listen for a moment, then press Enter to continue..."

rpc '{"jsonrpc":"2.0","method":"transport/stop","id":12}' > /dev/null
sleep 0.5

# ============================================================
# 6. Adjust mix (switch to mix view)
# ============================================================

rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"MIX"},"id":4}' > /dev/null
sleep 0.5

VOL_VAL=$(db_to_vol -6)
VOL_DB=$(vol_to_db "$VOL_VAL")

verify \
  "Set track volume to ${VOL_DB}" \
  "Does the volume fader show ${VOL_DB}?" \
  "{\"jsonrpc\":\"2.0\",\"method\":\"track/setVolume\",\"params\":{\"index\":0,\"value\":${VOL_VAL}},\"id\":13}"

verify \
  "Set track color to orange" \
  "Did the track color change to orange?" \
  '{"jsonrpc":"2.0","method":"track/setColor","params":{"index":0,"r":1.0,"g":0.5,"b":0.0},"id":14}'

# ============================================================
# 6. Duplicate and modify
# ============================================================

verify \
  "Duplicate track" \
  "Do you see a second 'Lead Synth' track appear?" \
  '{"jsonrpc":"2.0","method":"track/duplicate","params":{},"id":15}'

verify \
  "Rename duplicate to 'Lead Synth 2'" \
  "Does the selected track now show 'Lead Synth 2'?" \
  '{"jsonrpc":"2.0","method":"track/rename","params":{"name":"Lead Synth 2"},"id":16}'

verify \
  "Set duplicate color to purple" \
  "Is the second track now purple?" \
  '{"jsonrpc":"2.0","method":"track/setColor","params":{"index":1,"r":0.6,"g":0.2,"b":0.8},"id":17}'

# Modify the duplicate's clip — transpose up an octave for harmony
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"clip/select","params":{"trackIndex":1,"slotIndex":0},"id":50}' > /dev/null
sleep 1.0
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"EDIT"},"id":4}' > /dev/null
sleep 0.5

# Clear the duplicated notes first, then write the transposed ones
rpc '{"jsonrpc":"2.0","method":"clip/clearAllNotes","params":{},"id":55}' > /dev/null
sleep 0.5

verify \
  "Transpose Lead Synth 2 melody up one octave" \
  "Do you see only 4 notes in Lead Synth 2? C5 (bar 1), E5 (bar 2), G5 (bar 3), C6 (bar 4)?" \
  '{"jsonrpc":"2.0","method":"clip/setNotes","params":{"notes":[{"x":0,"y":72,"velocity":0.8,"duration":3.0},{"x":4,"y":76,"velocity":0.7,"duration":3.0},{"x":8,"y":79,"velocity":0.75,"duration":3.0},{"x":12,"y":84,"velocity":0.85,"duration":3.0}]},"id":51}'

# ============================================================
# 7. Set up a loop and play both tracks
# ============================================================

rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":4}' > /dev/null
sleep 0.5

LOOP_START_BAR=$(beats_to_bars 0)
LOOP_END_BAR=$(beats_to_bars "$CLIP_LEN")
LOOP_BARS=$(beats_to_duration "$CLIP_LEN")

verify \
  "Enable loop (${LOOP_START_BAR} to ${LOOP_END_BAR}, ${LOOP_BARS})" \
  "Do you see the loop bracket on the timeline?" \
  "{\"jsonrpc\":\"2.0\",\"method\":\"transport/setLoopRange\",\"params\":{\"start\":0,\"duration\":${CLIP_LEN},\"enabled\":true},\"id\":18}"

# Launch scene to play both clips in sync
rpc '{"jsonrpc":"2.0","method":"scene/launch","params":{"index":0},"id":52}' > /dev/null

verify \
  "Play both tracks" \
  "Can you hear both Lead Synth and Lead Synth 2 playing in harmony?" \
  ''

rpc '{"jsonrpc":"2.0","method":"transport/stop","id":54}' > /dev/null
sleep 0.5

# ============================================================
# 8. Clean up
# ============================================================

echo ""
echo "  Cleaning up everything..."
# Delete both tracks
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":1},"id":21}' > /dev/null
sleep 0.3
rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":22}' > /dev/null
sleep 0.5
rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":23}' > /dev/null
sleep 0.3
rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":24}' > /dev/null
sleep 0.5
# Reset transport
rpc '{"jsonrpc":"2.0","method":"transport/setTempo","params":{"tempo":92},"id":25}' > /dev/null
rpc '{"jsonrpc":"2.0","method":"transport/setPosition","params":{"beats":0},"id":26}' > /dev/null
rpc '{"jsonrpc":"2.0","method":"transport/setLoop","params":{"enabled":false},"id":27}' > /dev/null
rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":28}' > /dev/null
sleep 0.3

verify \
  "Cleanup complete" \
  "Is the project back to clean state (no tracks, arrange view, 92 BPM)?" \
  '{"jsonrpc":"2.0","method":"session/snapshot","id":99}'

# --- Summary ---

print_summary "End-to-End Workflow"
