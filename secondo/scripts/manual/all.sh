#!/usr/bin/env bash
#
# Run all manual verification scripts in sequence.
#
# Usage:
#   ./scripts/manual/all.sh              — run all
#   ./scripts/manual/all.sh --only NAME  — run specific script(s), comma-separated
#   ./scripts/manual/all.sh --list       — list available scripts
#

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Script execution order
ALL_SCRIPTS=(
  transport
  arranger
  project
  tracks
  mixer
  clips
  devices
  notes
)

# --- parse args ---

MODE="all"
ONLY_NAMES=""

while [ $# -gt 0 ]; do
  case "$1" in
    --only)  MODE="only"; ONLY_NAMES="$2"; shift 2 ;;
    --list)  MODE="list"; shift ;;
    *)       echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

if [ "$MODE" = "list" ]; then
  echo "Available manual verification scripts:"
  echo ""
  for name in "${ALL_SCRIPTS[@]}"; do
    echo "    $name"
  done
  exit 0
fi

# --- resolve scripts to run ---

SCRIPTS_TO_RUN=()

if [ "$MODE" = "only" ]; then
  IFS=',' read -ra NAMES <<< "$ONLY_NAMES"
  for name in "${NAMES[@]}"; do
    name="$(echo "$name" | xargs)"
    target="${SCRIPT_DIR}/${name}.sh"
    if [ -f "$target" ]; then
      SCRIPTS_TO_RUN+=("$name")
    else
      echo "ERROR: Script not found: $name" >&2
      exit 1
    fi
  done
else
  SCRIPTS_TO_RUN=("${ALL_SCRIPTS[@]}")
fi

# --- run ---

GRAND_PASS=0
GRAND_FAIL=0
GRAND_SKIP=0
GRAND_COUNT=0
SCRIPT_RESULTS=()
COMPLETED=0

# Temp file for passing results back from child scripts
export MANUAL_RESULTS_FILE
MANUAL_RESULTS_FILE=$(mktemp)
trap "rm -f '$MANUAL_RESULTS_FILE'" EXIT

echo ""
echo "=== Secondo — Full Manual Verification ==="
echo "=== ${#SCRIPTS_TO_RUN[@]} scripts to run ==="

for name in "${SCRIPTS_TO_RUN[@]}"; do
  script="${SCRIPT_DIR}/${name}.sh"
  COMPLETED=$((COMPLETED + 1))

  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "  [${COMPLETED}/${#SCRIPTS_TO_RUN[@]}] ${name}"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  # Clear results file
  echo "" > "$MANUAL_RESULTS_FILE"

  # Run the script (interactive — needs terminal)
  "$script" || true

  # Read results
  if [ -s "$MANUAL_RESULTS_FILE" ]; then
    read -r s_pass s_fail s_skip < "$MANUAL_RESULTS_FILE"
    s_pass=${s_pass:-0}
    s_fail=${s_fail:-0}
    s_skip=${s_skip:-0}
  else
    s_pass=0; s_fail=0; s_skip=0
  fi

  GRAND_PASS=$((GRAND_PASS + s_pass))
  GRAND_FAIL=$((GRAND_FAIL + s_fail))
  GRAND_SKIP=$((GRAND_SKIP + s_skip))
  GRAND_COUNT=$((GRAND_COUNT + s_pass + s_fail + s_skip))

  status="PASS"
  [ "$s_fail" -gt 0 ] && status="FAIL"
  SCRIPT_RESULTS+=("  ${status}  ${name}: ${s_pass} passed, ${s_fail} failed, ${s_skip} skipped")
done

# --- final summary ---

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  FINAL RESULTS"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
for result in "${SCRIPT_RESULTS[@]}"; do
  echo "$result"
done
echo ""
echo "=== Total: ${GRAND_PASS} passed, ${GRAND_FAIL} failed, ${GRAND_SKIP} skipped, ${GRAND_COUNT} total ==="
echo ""

if [ "$GRAND_FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
