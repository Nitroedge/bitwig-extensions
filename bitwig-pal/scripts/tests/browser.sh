#
# Browser tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: sections 47, 48
#

echo "--- Browser API ---"

# Verify snapshot includes browser section
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":701}')
assert_contains "snapshot has browser.exists" "$SNAP" '"exists"'
assert_contains "snapshot has browser.title" "$SNAP" '"title"'
assert_contains "snapshot has browser.resultName" "$SNAP" '"resultName"'
assert_contains "snapshot has browser.shouldAudition" "$SNAP" '"shouldAudition"'
assert_contains "snapshot has browser.canAudition" "$SNAP" '"canAudition"'
assert_contains "snapshot has browser.contentTypeNames" "$SNAP" '"contentTypeNames"'
assert_contains "snapshot has browser.selectedContentType" "$SNAP" '"selectedContentType"'
assert_contains "snapshot has browser.resultIsSelected" "$SNAP" '"resultIsSelected"'

# Test browser/getState returns state object
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/getState","id":702}')
assert_contains "browser/getState returns exists field" "$RESP" '"exists"'
assert_contains "browser/getState returns resultName field" "$RESP" '"resultName"'

# Test browser/setShouldAudition
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/setShouldAudition","params":{"enabled":true},"id":703}')
assert_contains "browser/setShouldAudition returns ok" "$RESP" '"ok"'
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/setShouldAudition","params":{"enabled":false},"id":704}')
assert_contains "browser/setShouldAudition false returns ok" "$RESP" '"ok"'

# Test browser/setContentType (index 0 should be safe)
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/setContentType","params":{"index":0},"id":705}')
assert_contains "browser/setContentType returns ok" "$RESP" '"ok"'

# Test browser/selectNextFile (no-op when browser closed, but should not error)
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/selectNextFile","id":706}')
assert_contains "browser/selectNextFile returns ok" "$RESP" '"ok"'

# Test browser/selectPreviousFile
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/selectPreviousFile","id":707}')
assert_contains "browser/selectPreviousFile returns ok" "$RESP" '"ok"'

echo "--- Deep Browser Filters ---"

# Verify snapshot includes filters in browser section
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":801}')
assert_contains "snapshot has browser.filters" "$SNAP" '"filters"'
assert_contains "snapshot has browser.resultsEntryCount" "$SNAP" '"resultsEntryCount"'
assert_contains "snapshot has filter column category" "$SNAP" '"category"'
assert_contains "snapshot has filter column tag" "$SNAP" '"tag"'

# Test browser/getFilters
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/getFilters","id":802}')
assert_contains "browser/getFilters returns category" "$RESP" '"category"'
assert_contains "browser/getFilters returns entryCount" "$RESP" '"entryCount"'

# Test browser/getResults
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/getResults","id":803}')
assert_contains "browser/getResults returns items" "$RESP" '"items"'
assert_contains "browser/getResults returns entryCount" "$RESP" '"entryCount"'

# Test browser/filterSelectNext with valid column
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/filterSelectNext","params":{"column":"category"},"id":804}')
assert_contains "browser/filterSelectNext returns ok" "$RESP" '"ok"'

# Test browser/filterSelectNext with invalid column
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/filterSelectNext","params":{"column":"invalid"},"id":805}')
assert_contains "browser/filterSelectNext invalid column returns error" "$RESP" '-32602'

# Test browser/scrollResults with valid direction
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/scrollResults","params":{"direction":"forward"},"id":806}')
assert_contains "browser/scrollResults returns ok" "$RESP" '"ok"'

# Test browser/scrollResults with invalid direction
RESP=$(rpc '{"jsonrpc":"2.0","method":"browser/scrollResults","params":{"direction":"sideways"},"id":807}')
assert_contains "browser/scrollResults invalid direction returns error" "$RESP" '-32602'
