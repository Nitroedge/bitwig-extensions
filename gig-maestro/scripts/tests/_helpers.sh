#!/usr/bin/env bash
#
# Shared test helpers for Gig Maestro smoke tests.
# Sourced by individual test scripts and the runner.
#

# --- connection ---

PORT="${PORT:-8787}"
BASE="${BASE:-http://localhost:${PORT}}"

# --- paths ---

# Resolve from this file's location: scripts/tests/_helpers.sh → gig-maestro/
if [ -z "${PROJECT_ROOT:-}" ]; then
  PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi
REPO_ROOT="$(cd "$PROJECT_ROOT/.." && pwd)"
TOOLS_FILE="${PROJECT_ROOT}/tools/claude-tools.json"
PROMPT_FILE="${PROJECT_ROOT}/tools/system-prompt.md"

# --- counters ---

PASS=${PASS:-0}
FAIL=${FAIL:-0}
SKIP=${SKIP:-0}
TOTAL=${TOTAL:-0}

# --- helpers ---

rpc() {
  curl -s -X POST "${BASE}/rpc" \
    -H "Content-Type: application/json" \
    -d "$1"
}

assert_contains() {
  local label="$1" response="$2" expected="$3"
  TOTAL=$((TOTAL + 1))
  if [[ "$response" == *"$expected"* ]]; then
    echo "  PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $label — expected '$expected' in response"
    echo "        got: ${response:0:200}..."
    FAIL=$((FAIL + 1))
  fi
}

assert_equals() {
  local label="$1" actual="$2" expected="$3"
  TOTAL=$((TOTAL + 1))
  if [ "$actual" = "$expected" ]; then
    echo "  PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $label — expected '$expected', got '$actual'"
    FAIL=$((FAIL + 1))
  fi
}

assert_skip() {
  local label="$1" reason="$2"
  TOTAL=$((TOTAL + 1))
  SKIP=$((SKIP + 1))
  echo "  SKIP  $label — $reason"
}

# The interpreter snapshot_field runs on. Resolved rather than hardcoded: the project's
# prescribed Windows environment ships `python` and NOT `python3` (the Python.org installer
# creates python.exe/pythonw.exe only; `python3` exists on POSIX and inside a Windows Store
# install, neither of which is what the validation machine has). A hardcoded `python3` made
# every snapshot_field call emit nothing to stdout and a not-found message to stderr, so the
# assertion that consumed it compared an empty string and failed for a reason that had nothing
# to do with the engine.
if [ -z "${PYTHON_BIN:-}" ]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
  elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
  else
    PYTHON_BIN=""
  fi
fi

snapshot_field() {
  local path="$1"
  if [ -z "$PYTHON_BIN" ]; then
    echo "PYTHON_NOT_FOUND"
    return 0
  fi
  rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":99}' | \
    "$PYTHON_BIN" -c "import sys,json; print(json.load(sys.stdin)['result']${path})"
}

# --- summary ---

print_summary() {
  local label="${1:-Tests}"
  echo ""
  echo "=== ${label}: ${PASS} passed, ${FAIL} failed, ${SKIP} skipped, ${TOTAL} total ==="
  echo ""
}
