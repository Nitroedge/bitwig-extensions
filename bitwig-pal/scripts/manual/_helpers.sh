#!/usr/bin/env bash
#
# Shared helpers for interactive manual verification scripts.
#

# --- connection ---

PORT="${PORT:-8787}"
BASE="${BASE:-http://localhost:${PORT}}"

# --- counters ---

PASS=0
FAIL=0
SKIP=0
TOTAL=0
STEP=0
STEP_COUNT="${STEP_COUNT:-0}"

# --- time signature (fetched once on load) ---

_TS_NUM=""
_TS_DENOM=""
_BEATS_PER_BAR=""

_fetch_time_sig() {
  if [ -z "$_TS_NUM" ]; then
    local snap
    snap=$(curl -s -X POST "${BASE}/rpc" \
      -H "Content-Type: application/json" \
      -d '{"jsonrpc":"2.0","method":"session/snapshot","id":0}')
    _TS_NUM=$(echo "$snap" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['transport']['timeSignatureNumerator'])")
    _TS_DENOM=$(echo "$snap" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['transport']['timeSignatureDenominator'])")
    _BEATS_PER_BAR="$_TS_NUM"
  fi
}

# beats_to_bars <beats> — convert beat position to "Bar X, Beat Y" string
beats_to_bars() {
  _fetch_time_sig
  local beats="$1"
  python3 -c "
b = $beats
bpb = $_BEATS_PER_BAR
bar = int(b // bpb) + 1
beat = (b % bpb) + 1
if beat == 1.0:
    print(f'Bar {bar}')
else:
    print(f'Bar {bar}, Beat {beat:.0f}')
"
}

# beats_to_duration <beats> — convert beat count to "X bars" or "X bars, Y beats"
beats_to_duration() {
  _fetch_time_sig
  local beats="$1"
  python3 -c "
b = $beats
bpb = $_BEATS_PER_BAR
bars = int(b // bpb)
rem = b % bpb
if rem == 0:
    print(f'{bars} bar{\"s\" if bars != 1 else \"\"}')
else:
    print(f'{bars} bar{\"s\" if bars != 1 else \"\"}, {rem:.0f} beat{\"s\" if rem != 1 else \"\"}')
"
}

# --- volume conversion ---

# vol_to_db <0.0-1.0> — convert API volume to Bitwig's dB display value
# Calibrated: 0.501 = -12 dB, 0.794 = 0 dB, 1.0 = +6 dB
# Formula: dB = 60 * log10(value) + 6
vol_to_db() {
  python3 -c "
import math
v = $1
if v <= 0:
    print('-inf dB')
else:
    db = 60 * math.log10(v) + 6
    if db <= -60:
        print('-inf dB')
    else:
        print(f'{db:+.1f} dB')
"
}

# db_to_vol <dB> — convert Bitwig dB to API volume value
db_to_vol() {
  python3 -c "
db = $1
print(round(10 ** ((db - 6) / 60.0), 4))
"
}

# --- helpers ---

rpc() {
  curl -s -X POST "${BASE}/rpc" \
    -H "Content-Type: application/json" \
    -d "$1"
}

# verify "label" "what to look for" [rpc_payload]
#   - Runs the RPC if provided (prints response summary)
#   - Tells the user what to check in Bitwig
#   - Waits for y/n/s (yes/no/skip)
verify() {
  local label="$1"
  local instruction="$2"
  local payload="${3:-}"

  STEP=$((STEP + 1))
  TOTAL=$((TOTAL + 1))

  echo ""
  if [ "$STEP_COUNT" -gt 0 ]; then
    echo "[$STEP/$STEP_COUNT] $label"
  else
    echo "[$STEP] $label"
  fi

  if [ -n "$payload" ]; then
    local resp
    resp=$(rpc "$payload")
    echo "  RPC response: ${resp:0:200}"
  fi

  echo ""
  echo "  → $instruction"
  echo ""

  while true; do
    printf "  [y/n/s/r] (yes=pass, no=fail, s=skip, r=re-run): "
    read -r answer
    case "$answer" in
      y|Y|yes)
        echo "  ✓ PASS"
        PASS=$((PASS + 1))
        break
        ;;
      n|N|no)
        echo "  ✗ FAIL"
        FAIL=$((FAIL + 1))
        break
        ;;
      s|S|skip)
        echo "  — SKIP"
        SKIP=$((SKIP + 1))
        break
        ;;
      r|R|rerun)
        if [ -n "$payload" ]; then
          echo ""
          printf "  Edit params? Paste new JSON payload (or Enter to re-run same): "
          read -r new_payload
          if [ -n "$new_payload" ]; then
            payload="$new_payload"
          fi
          resp=$(rpc "$payload")
          echo "  RPC response: ${resp:0:200}"
          echo ""
          echo "  → $instruction"
          echo ""
        else
          echo "  No RPC to re-run for this step."
        fi
        ;;
      *)
        echo "  Please enter y, n, s, or r"
        ;;
    esac
  done
}

# reset_project — restore Bitwig to clean baseline state
# Deletes all tracks in the bank, stops transport, resets position/loop/metronome
reset_project() {
  echo "  Resetting project to clean state..."

  # Stop transport
  rpc '{"jsonrpc":"2.0","method":"transport/stop","id":900}' > /dev/null
  sleep 0.3

  # Delete any tracks in the bank (select + delete, up to 8 slots)
  local track_count
  track_count=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":901}' | \
    python3 -c "import sys,json; tracks=json.load(sys.stdin)['result']['tracks']['tracks']; print(sum(1 for t in tracks if t['name'].strip()))")

  if [ "$track_count" -gt 0 ]; then
    for i in $(seq 1 "$track_count"); do
      rpc '{"jsonrpc":"2.0","method":"track/select","params":{"index":0},"id":902}' > /dev/null
      sleep 0.3
      rpc '{"jsonrpc":"2.0","method":"track/deleteSelected","params":{},"id":903}' > /dev/null
      sleep 0.3
    done
  fi

  # Remove devices from master track (up to 8 attempts)
  for i in $(seq 1 8); do
    local resp
    resp=$(rpc '{"jsonrpc":"2.0","method":"masterDevice/remove","id":910}')
    # Stop if there's an error (no device to remove)
    if [[ "$resp" == *'"error"'* ]]; then
      break
    fi
    sleep 0.3
  done

  # Reset transport state
  rpc '{"jsonrpc":"2.0","method":"transport/setPosition","params":{"beats":0},"id":904}' > /dev/null
  rpc '{"jsonrpc":"2.0","method":"transport/setLoop","params":{"enabled":false},"id":905}' > /dev/null
  rpc '{"jsonrpc":"2.0","method":"transport/setMetronome","params":{"enabled":false},"id":906}' > /dev/null
  sleep 0.3

  # Unsolo/unmute/unarm all
  rpc '{"jsonrpc":"2.0","method":"project/unsoloAll","id":907}' > /dev/null
  rpc '{"jsonrpc":"2.0","method":"project/unmuteAll","id":908}' > /dev/null
  rpc '{"jsonrpc":"2.0","method":"project/unarmAll","id":909}' > /dev/null

  echo "  Done. Project state: no tracks, no master devices, transport stopped, position at Bar 1."
  echo ""
}

# pause "message" — simple pause without pass/fail tracking
pause() {
  local msg="${1:-Press Enter to continue...}"
  echo ""
  echo "  $msg"
  read -r
}

# print summary (also writes to MANUAL_RESULTS_FILE if set by runner)
print_summary() {
  local label="${1:-Manual Verification}"
  echo ""
  echo "=== ${label}: ${PASS} passed, ${FAIL} failed, ${SKIP} skipped, ${TOTAL} total ==="
  echo ""
  if [ -n "${MANUAL_RESULTS_FILE:-}" ]; then
    echo "${PASS} ${FAIL} ${SKIP}" > "$MANUAL_RESULTS_FILE"
  fi
  if [ "$FAIL" -gt 0 ]; then
    return 1
  fi
  return 0
}
