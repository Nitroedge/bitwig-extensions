#!/usr/bin/env bash
#
# Bitwig Pal — Smoke Test Runner
#
# Usage:
#   ./scripts/smoke-test.sh                — run all tests (requires Bitwig running)
#   ./scripts/smoke-test.sh --offline      — run only offline tests (no Bitwig needed)
#   ./scripts/smoke-test.sh --online       — run only online tests (requires Bitwig)
#   ./scripts/smoke-test.sh --only NAME    — run specific test(s), comma-separated
#   ./scripts/smoke-test.sh --list         — list available test scripts
#   ./scripts/smoke-test.sh --port PORT    — use a custom port (default: 8787)
#
# Test scripts are DISCOVERED from scripts/tests/ rather than listed here: anything matching
# offline-*.sh is offline, anything else that does not start with _ is online. Dropping a file
# in that directory registers it, which is why --list is the authority on what exists and no
# name is restated below.
#
# Scripts that need session state beyond "Bitwig is running", and what they need:
#
#   arranger-clip — an arranger clip selected BY HAND in the Bitwig timeline. Its content
#                   assertions SKIP with the reason named when nothing is selected, rather
#                   than FAILing about the session instead of about the engine.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TESTS_DIR="${SCRIPT_DIR}/tests"

# --- parse args ---

MODE="all"       # all | offline | online | only
ONLY_NAMES=""
export PORT="${PORT:-8787}"

while [ $# -gt 0 ]; do
  case "$1" in
    --offline)  MODE="offline"; shift ;;
    --online)   MODE="online"; shift ;;
    --only)     MODE="only"; ONLY_NAMES="$2"; shift 2 ;;
    --list)     MODE="list"; shift ;;
    --port)     PORT="$2"; shift 2 ;;
    *)
      # Legacy: bare number is port
      if [[ "$1" =~ ^[0-9]+$ ]]; then
        PORT="$1"; shift
      else
        echo "Unknown option: $1" >&2; exit 1
      fi
      ;;
  esac
done

export BASE="http://localhost:${PORT}"

# --- list mode ---

if [ "$MODE" = "list" ]; then
  echo "Available test scripts:"
  echo ""
  echo "  Offline:"
  for f in "$TESTS_DIR"/offline-*.sh; do
    [ -f "$f" ] && echo "    $(basename "$f" .sh)"
  done
  echo ""
  echo "  Online (require Bitwig running):"
  for f in "$TESTS_DIR"/*.sh; do
    local_name="$(basename "$f" .sh)"
    [[ "$local_name" == _* ]] && continue
    [[ "$local_name" == offline-* ]] && continue
    echo "    $local_name"
  done
  exit 0
fi

# --- source helpers ---

source "${TESTS_DIR}/_helpers.sh"

# --- discover test scripts ---

OFFLINE_SCRIPTS=()
ONLINE_SCRIPTS=()

for f in "$TESTS_DIR"/*.sh; do
  name="$(basename "$f" .sh)"
  [[ "$name" == _* ]] && continue
  if [[ "$name" == offline-* ]]; then
    OFFLINE_SCRIPTS+=("$f")
  else
    ONLINE_SCRIPTS+=("$f")
  fi
done

# Sort for consistent ordering
IFS=$'\n' OFFLINE_SCRIPTS=($(sort <<<"${OFFLINE_SCRIPTS[*]}")); unset IFS
IFS=$'\n' ONLINE_SCRIPTS=($(sort <<<"${ONLINE_SCRIPTS[*]}")); unset IFS

# --- filter by mode ---

SCRIPTS_TO_RUN=()

case "$MODE" in
  all)
    SCRIPTS_TO_RUN=("${OFFLINE_SCRIPTS[@]}" "${ONLINE_SCRIPTS[@]}")
    ;;
  offline)
    SCRIPTS_TO_RUN=("${OFFLINE_SCRIPTS[@]}")
    ;;
  online)
    SCRIPTS_TO_RUN=("${ONLINE_SCRIPTS[@]}")
    ;;
  only)
    IFS=',' read -ra NAMES <<< "$ONLY_NAMES"
    for name in "${NAMES[@]}"; do
      name="$(echo "$name" | xargs)"  # trim whitespace
      target="${TESTS_DIR}/${name}.sh"
      if [ -f "$target" ]; then
        SCRIPTS_TO_RUN+=("$target")
      else
        echo "ERROR: Test script not found: $name" >&2
        exit 1
      fi
    done
    ;;
esac

if [ ${#SCRIPTS_TO_RUN[@]} -eq 0 ]; then
  echo "No test scripts found to run."
  exit 0
fi

# --- run tests ---

echo ""
echo "=== Bitwig Pal Smoke Tests ==="
echo ""

TOTAL_PASS=0
TOTAL_FAIL=0
TOTAL_SKIP=0
TOTAL_COUNT=0
SCRIPT_RESULTS=()

for script in "${SCRIPTS_TO_RUN[@]}"; do
  name="$(basename "$script" .sh)"

  # Reset counters for each script
  export PASS=0 FAIL=0 SKIP=0 TOTAL=0

  echo "--- ${name} ---"

  # Source the script (runs in this shell, shares counters)
  source "$script"

  TOTAL_PASS=$((TOTAL_PASS + PASS))
  TOTAL_FAIL=$((TOTAL_FAIL + FAIL))
  TOTAL_SKIP=$((TOTAL_SKIP + SKIP))
  TOTAL_COUNT=$((TOTAL_COUNT + TOTAL))

  status="PASS"
  [ "$FAIL" -gt 0 ] && status="FAIL"
  SCRIPT_RESULTS+=("  ${status}  ${name}: ${PASS} passed, ${FAIL} failed, ${SKIP} skipped")
done

# --- summary ---

echo ""
echo "=== Per-Script Results ==="
for result in "${SCRIPT_RESULTS[@]}"; do
  echo "$result"
done

echo ""
echo "=== Total: ${TOTAL_PASS} passed, ${TOTAL_FAIL} failed, ${TOTAL_SKIP} skipped, ${TOTAL_COUNT} total ==="
echo ""

if [ "$TOTAL_FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
