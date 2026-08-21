#
# Offline OpenAPI spec validation tests.
# Sourced by the runner — do NOT add shebang or set -euo pipefail.
#

OPENAPI_FILE="${PROJECT_ROOT}/docs/openapi.json"

echo "--- OpenAPI Spec Validation ---"

# --- file exists ---

TOTAL=$((TOTAL + 1))
if [ -f "$OPENAPI_FILE" ]; then
  echo "  PASS  openapi.json exists"
  PASS=$((PASS + 1))
else
  echo "  FAIL  openapi.json does not exist — run: ./gradlew :bitwig-pal:generateOpenApi"
  FAIL=$((FAIL + 1))
  return 0  # skip remaining tests
fi

# --- valid JSON ---

TOTAL=$((TOTAL + 1))
if jq empty "$OPENAPI_FILE" 2>/dev/null; then
  echo "  PASS  openapi.json is valid JSON"
  PASS=$((PASS + 1))
else
  echo "  FAIL  openapi.json is not valid JSON"
  FAIL=$((FAIL + 1))
  return 0
fi

# --- OpenAPI version ---

OPENAPI_VERSION=$(jq -r '.openapi' "$OPENAPI_FILE")
assert_equals "OpenAPI version is 3.1.0" "$OPENAPI_VERSION" "3.1.0"

# --- method count matches claude-tools.json ---

TOOL_COUNT=$(jq length "$TOOLS_FILE")
PATH_COUNT=$(jq '.paths | length' "$OPENAPI_FILE")
assert_equals "openapi.json path count ($PATH_COUNT) matches claude-tools.json ($TOOL_COUNT)" "$PATH_COUNT" "$TOOL_COUNT"

# --- has tags ---

TAG_COUNT=$(jq '.tags | length' "$OPENAPI_FILE")
TOTAL=$((TOTAL + 1))
if [ "$TAG_COUNT" -ge 20 ]; then
  echo "  PASS  openapi.json has >= 20 tags (found $TAG_COUNT)"
  PASS=$((PASS + 1))
else
  echo "  FAIL  openapi.json has >= 20 tags — found $TAG_COUNT"
  FAIL=$((FAIL + 1))
fi

# --- has server URL ---

SERVER_URL=$(jq -r '.servers[0].url' "$OPENAPI_FILE")
assert_equals "server URL is http://localhost:8787" "$SERVER_URL" "http://localhost:8787"
