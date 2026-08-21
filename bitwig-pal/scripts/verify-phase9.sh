#!/usr/bin/env bash
#
# Phase 9 Manual Verification — Envelope Writing
#
# Prerequisites:
#   - Bitwig running with a project open
#   - At least one instrument track with a device loaded
#   - Extension running on port 8787
#
# Usage:
#   ./scripts/verify-phase9.sh          — run all checks
#   ./scripts/verify-phase9.sh [port]   — custom port
#

set -euo pipefail

PORT="${1:-8787}"
BASE="http://localhost:${PORT}"
PASS=0
FAIL=0
TOTAL=0

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

rpc() {
  curl -s -X POST "${BASE}/rpc" \
    -H "Content-Type: application/json" \
    -d "$1"
}

check() {
  local label="$1" response="$2" expected="$3"
  TOTAL=$((TOTAL + 1))
  if echo "$response" | grep -qF -- "$expected"; then
    echo -e "  ${GREEN}PASS${NC}  $label"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $label"
    echo -e "        expected: ${expected}"
    echo -e "        got:      ${response}"
    FAIL=$((FAIL + 1))
  fi
}

section() {
  echo ""
  echo -e "${CYAN}━━━ $1 ━━━${NC}"
}

# ─── Connectivity check ─────────────────────────────────────────────

echo ""
echo -e "${BOLD}Phase 9 Manual Verification — Envelope Writing${NC}"
echo -e "Target: ${BASE}"
echo ""

RESP=$(rpc '{"jsonrpc":"2.0","method":"api/list","id":1}')
if ! echo "$RESP" | grep -q "result"; then
  echo -e "${RED}Cannot reach extension at ${BASE}. Is Bitwig running?${NC}"
  exit 1
fi
echo -e "${GREEN}Connected to Bitwig Pal${NC}"

# ─── 1. Method Registration ─────────────────────────────────────────

section "1. Method Registration"

check "device/hasAutomation registered" "$RESP" "device/hasAutomation"
check "device/deleteAllAutomation registered" "$RESP" "device/deleteAllAutomation"
check "device/restoreAutomationControl registered" "$RESP" "device/restoreAutomationControl"
check "device/touch registered" "$RESP" "device/touch"
check "device/writeEnvelope registered" "$RESP" "device/writeEnvelope"

METHOD_COUNT=$(echo "$RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']))")
echo -e "  Method count: ${BOLD}${METHOD_COUNT}${NC}"
check "Total methods >= 79" "$METHOD_COUNT" "79"

# ─── 2. Parameter Validation ────────────────────────────────────────

section "2. Parameter Validation (error cases)"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/hasAutomation","params":{},"id":1}')
check "hasAutomation: missing index → error" "$RESP" "-32602"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/hasAutomation","params":{"index":99},"id":1}')
check "hasAutomation: index 99 → out of range" "$RESP" "out of range"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/touch","params":{"index":0},"id":1}')
check "touch: missing touched → error" "$RESP" "-32602"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/writeEnvelope","params":{"index":-1,"points":[{"position":0,"value":0.5}]},"id":1}')
check "writeEnvelope: negative index → error" "$RESP" "out of range"

# Enable automation write so the negative-position check reaches position validation
rpc '{"jsonrpc":"2.0","method":"transport/setArrangerAutomationWrite","params":{"enabled":true},"id":1}' > /dev/null
sleep 0.3

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/writeEnvelope","params":{"index":0,"points":[{"position":-5,"value":0.5}]},"id":1}')
check "writeEnvelope: negative position → error" "$RESP" "position must be"

# ─── 3. Snapshot hasAutomation ──────────────────────────────────────

section "3. Snapshot — hasAutomation field"

RESP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":1}')
check "Snapshot contains hasAutomation field" "$RESP" "hasAutomation"

# ─── 4. Per-Parameter Automation Methods ─────────────────────────────

section "4. Per-Parameter Automation Methods"

echo -e "  ${YELLOW}NOTE: Ensure a track with a device is selected in Bitwig.${NC}"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/hasAutomation","params":{"index":0},"id":1}')
check "hasAutomation returns result" "$RESP" "hasAutomation"
echo -e "  Response: ${RESP}"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/touch","params":{"index":0,"touched":true},"id":1}')
check "touch(true) succeeds" "$RESP" "ok"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/touch","params":{"index":0,"touched":false},"id":1}')
check "touch(false) succeeds" "$RESP" "ok"

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/restoreAutomationControl","params":{"index":0},"id":1}')
check "restoreAutomationControl succeeds" "$RESP" "ok"

# ─── 5. writeEnvelope — Precondition Check ──────────────────────────

section "5. writeEnvelope — Precondition (automation write disabled)"

# Disable automation write
rpc '{"jsonrpc":"2.0","method":"transport/setArrangerAutomationWrite","params":{"enabled":false},"id":1}' > /dev/null
sleep 0.3

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/writeEnvelope","params":{"index":0,"points":[{"position":0,"value":0.5}]},"id":1}')
check "writeEnvelope rejects when automation write disabled" "$RESP" "automation write"

# ─── 6. writeEnvelope — Full End-to-End ──────────────────────────────

section "6. writeEnvelope — Full End-to-End Test"

echo -e "  ${YELLOW}Writing 3 automation points: beat 0→20%, beat 4→80%, beat 8→50%${NC}"
echo -e "  ${YELLOW}Watch the Bitwig arranger for automation lane changes.${NC}"

# Step 1: Clean slate — delete existing automation on param 0
echo ""
echo "  Step 1: Clearing existing automation on param 0..."
rpc '{"jsonrpc":"2.0","method":"device/deleteAllAutomation","params":{"index":0},"id":1}' > /dev/null
sleep 0.3

# Verify clean
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/hasAutomation","params":{"index":0},"id":1}')
echo -e "  hasAutomation after delete: ${RESP}"

# Step 2: Enable automation write
echo "  Step 2: Enabling automation write..."
rpc '{"jsonrpc":"2.0","method":"transport/setArrangerAutomationWrite","params":{"enabled":true},"id":1}' > /dev/null
sleep 0.2
rpc '{"jsonrpc":"2.0","method":"transport/setAutomationWriteMode","params":{"mode":"touch"},"id":1}' > /dev/null
sleep 0.2

# Step 3: Set position to beat 10
echo "  Step 3: Setting transport position to beat 10..."
rpc '{"jsonrpc":"2.0","method":"transport/setPosition","params":{"beats":10},"id":1}' > /dev/null
sleep 0.3

# Capture position before
SNAP_BEFORE=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":1}')
POS_BEFORE=$(echo "$SNAP_BEFORE" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['transport']['playPosition'])")
echo -e "  Position before: ${BOLD}${POS_BEFORE}${NC}"

# Step 4: Write 3-point envelope
echo "  Step 4: Writing 3-point envelope..."
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/writeEnvelope","params":{"index":0,"points":[{"position":0,"value":0.2},{"position":4,"value":0.8},{"position":8,"value":0.5}]},"id":1}')
check "writeEnvelope returns ok" "$RESP" "ok"
check "writeEnvelope reports pointsWritten" "$RESP" "pointsWritten"
echo -e "  Response: ${RESP}"

# Step 5: Wait for async recording
echo "  Step 5: Waiting 2s for async recording to complete..."
sleep 2

# Step 6: Verify results
echo "  Step 6: Verifying results..."

SNAP_AFTER=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":1}')
POS_AFTER=$(echo "$SNAP_AFTER" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['transport']['playPosition'])")
PLAYING=$(echo "$SNAP_AFTER" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['transport']['isPlaying'])")

RESP=$(rpc '{"jsonrpc":"2.0","method":"device/hasAutomation","params":{"index":0},"id":1}')
HAS_AUTO=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['hasAutomation'])")

echo ""
echo -e "  ${BOLD}Results:${NC}"
echo -e "    hasAutomation:   ${BOLD}${HAS_AUTO}${NC}"
echo -e "    Position before: ${POS_BEFORE}"
echo -e "    Position after:  ${BOLD}${POS_AFTER}${NC}"
echo -e "    Playing after:   ${BOLD}${PLAYING}${NC}"

check "hasAutomation is True" "$HAS_AUTO" "True"
check "Transport not playing" "$PLAYING" "False"

# ─── 7. Visual Checklist ─────────────────────────────────────────────

section "7. Visual Checklist"

echo ""
echo -e "  ${YELLOW}Verify in Bitwig arranger:${NC}"
echo ""
echo "    [ ] Automation lane visible on the selected device's param 0"
echo "    [ ] Point near beat 0 at ~20% value"
echo "    [ ] Point near beat 4 at ~80% value"
echo "    [ ] Point near beat 8 at ~50% value"
echo "    [ ] Transport playhead is near beat 10 (may drift ~1 beat)"
echo "    [ ] Transport is stopped"

# ─── 8. Cleanup ─────────────────────────────────────────────────────

section "8. Cleanup"

echo "  Deleting test automation..."
rpc '{"jsonrpc":"2.0","method":"device/deleteAllAutomation","params":{"index":0},"id":1}' > /dev/null
sleep 0.3
RESP=$(rpc '{"jsonrpc":"2.0","method":"device/hasAutomation","params":{"index":0},"id":1}')
check "deleteAllAutomation clears automation" "$RESP" "false"

echo "  Disabling automation write..."
rpc '{"jsonrpc":"2.0","method":"transport/setArrangerAutomationWrite","params":{"enabled":false},"id":1}' > /dev/null

echo "  Restoring transport to beat 0..."
rpc '{"jsonrpc":"2.0","method":"transport/setPosition","params":{"beats":0},"id":1}' > /dev/null

# ─── Summary ─────────────────────────────────────────────────────────

echo ""
echo -e "${CYAN}━━━ Summary ━━━${NC}"
echo ""
if [ "$FAIL" -eq 0 ]; then
  echo -e "  ${GREEN}${BOLD}ALL PASSED${NC}  ${PASS}/${TOTAL} checks"
else
  echo -e "  ${RED}${BOLD}FAILURES${NC}  ${PASS}/${TOTAL} passed, ${FAIL} failed"
fi
echo ""
