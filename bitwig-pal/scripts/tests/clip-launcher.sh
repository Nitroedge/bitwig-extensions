#
# Clip launcher automation tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: section 50
#

echo "--- Clip Launcher Automation ---"

# Verify snapshot has new transport fields
SNAP=$(rpc '{"jsonrpc":"2.0","method":"session/snapshot","id":901}')
assert_contains "snapshot has defaultLaunchQuantization" "$SNAP" '"defaultLaunchQuantization"'
assert_contains "snapshot has clipLauncherPostRecordingAction" "$SNAP" '"clipLauncherPostRecordingAction"'
assert_contains "snapshot has fillModeActive" "$SNAP" '"fillModeActive"'
assert_contains "snapshot has clipLauncherOverdubEnabled" "$SNAP" '"clipLauncherOverdubEnabled"'

echo "--- Clip Launcher Settings ---"

# Test transport/getClipLauncherSettings
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/getClipLauncherSettings","id":902}')
assert_contains "transport/getClipLauncherSettings returns defaultLaunchQuantization" "$RESP" '"defaultLaunchQuantization"'
assert_contains "transport/getClipLauncherSettings returns fillModeActive" "$RESP" '"fillModeActive"'

# Test transport/setFillMode
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setFillMode","params":{"enabled":false},"id":903}')
assert_contains "transport/setFillMode returns ok" "$RESP" '"ok"'

echo "--- Clip Launch Settings ---"

# Test clip/getLaunchSettings
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/getLaunchSettings","id":908}')
assert_contains "clip/getLaunchSettings returns launchQuantization" "$RESP" '"launchQuantization"'
assert_contains "clip/getLaunchSettings returns launchMode" "$RESP" '"launchMode"'

echo "--- Clip Launcher Error Validation ---"

# Test transport/setDefaultLaunchQuantization validation
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setDefaultLaunchQuantization","params":{"quantization":"invalid"},"id":904}')
assert_contains "transport/setDefaultLaunchQuantization invalid returns error" "$RESP" '-32602'

# Test clip/setLaunchQuantization validation
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/setLaunchQuantization","params":{"quantization":"bad"},"id":905}')
assert_contains "clip/setLaunchQuantization invalid returns error" "$RESP" '-32602'

# Test clip/setLaunchMode validation
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/setLaunchMode","params":{"launchMode":"invalid"},"id":906}')
assert_contains "clip/setLaunchMode invalid returns error" "$RESP" '-32602'

# Test clip/setAccent out of range
RESP=$(rpc '{"jsonrpc":"2.0","method":"clip/setAccent","params":{"value":2.0},"id":907}')
assert_contains "clip/setAccent out of range returns error" "$RESP" '-32602'
