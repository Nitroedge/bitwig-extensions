#
# Project & session management tests (online — requires Bitwig running)
#
# Tests extracted from legacy smoke-test.sh: section 46
#

echo "--- Project State ---"

# Test project bulk operations (safe to call even with no soloed/muted/armed tracks)
RESP=$(rpc '{"jsonrpc":"2.0","method":"project/unsoloAll","id":603}')
assert_contains "project/unsoloAll returns ok" "$RESP" '"ok"'
RESP=$(rpc '{"jsonrpc":"2.0","method":"project/unmuteAll","id":604}')
assert_contains "project/unmuteAll returns ok" "$RESP" '"ok"'
RESP=$(rpc '{"jsonrpc":"2.0","method":"project/unarmAll","id":605}')
assert_contains "project/unarmAll returns ok" "$RESP" '"ok"'

echo "--- App Notifications & Panel Layout ---"

# Test app/showNotification
RESP=$(rpc '{"jsonrpc":"2.0","method":"app/showNotification","params":{"text":"Smoke test"},"id":606}')
assert_contains "app/showNotification returns ok" "$RESP" '"ok"'

# Test app/setPanelLayout (set to ARRANGE — safe default)
RESP=$(rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"ARRANGE"},"id":607}')
assert_contains "app/setPanelLayout returns ok" "$RESP" '"ok"'

# Test app/setPanelLayout with invalid layout
RESP=$(rpc '{"jsonrpc":"2.0","method":"app/setPanelLayout","params":{"layout":"INVALID"},"id":608}')
assert_contains "app/setPanelLayout invalid layout returns error" "$RESP" '-32602'

echo "--- Transport Navigation ---"

# Test transport/returnToArrangement
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/returnToArrangement","id":609}')
assert_contains "transport/returnToArrangement returns ok" "$RESP" '"ok"'

echo "--- Pre-Roll & Metronome Volume ---"

# Test transport/setPreRoll
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setPreRoll","params":{"value":"one_bar"},"id":610}')
assert_contains "transport/setPreRoll returns ok" "$RESP" '"ok"'

# Test transport/setPreRoll with invalid value
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setPreRoll","params":{"value":"three_bars"},"id":611}')
assert_contains "transport/setPreRoll invalid value returns error" "$RESP" '-32602'

# Test transport/setMetronomeVolume
RESP=$(rpc '{"jsonrpc":"2.0","method":"transport/setMetronomeVolume","params":{"value":0.5},"id":612}')
assert_contains "transport/setMetronomeVolume returns ok" "$RESP" '"ok"'

# Restore pre-roll to none
rpc '{"jsonrpc":"2.0","method":"transport/setPreRoll","params":{"value":"none"},"id":613}' > /dev/null
