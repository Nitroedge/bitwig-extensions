#
# Offline build validation tests (CLI + extension).
# Sourced by the runner — do NOT add shebang or set -euo pipefail.
#

# =============================================
# O3. CLI Build & Help
# =============================================

echo "--- O3. CLI Build & Help ---"

CLI_JAR="${PROJECT_ROOT}/build/libs/secondo-cli.jar"

# Build the CLI JAR
(cd "$REPO_ROOT" && ./gradlew :secondo:cliShadowJar -q 2>/dev/null)
TOTAL=$((TOTAL + 1))
if [ -f "$CLI_JAR" ]; then
  echo "  PASS  secondo-cli.jar exists after build"
  PASS=$((PASS + 1))
else
  echo "  FAIL  secondo-cli.jar not found after build"
  FAIL=$((FAIL + 1))
fi

# Help output
HELP=$(java -jar "$CLI_JAR" --help 2>&1)
assert_contains "CLI help shows transport command" "$HELP" "transport"
assert_contains "CLI help shows track command" "$HELP" "track"
assert_contains "CLI help shows snapshot command" "$HELP" "snapshot"
assert_contains "CLI help shows device command" "$HELP" "device"
assert_contains "CLI help shows note command" "$HELP" "note"
assert_contains "CLI help shows rpc command" "$HELP" "rpc"
assert_contains "CLI help shows song command" "$HELP" "song"
assert_contains "CLI help shows --pretty option" "$HELP" "--pretty"
assert_contains "CLI help shows --port option" "$HELP" "--port"

# Transport subcommand help
TRANSPORT_HELP=$(java -jar "$CLI_JAR" transport --help 2>&1)
assert_contains "transport help shows play" "$TRANSPORT_HELP" "play"
assert_contains "transport help shows stop" "$TRANSPORT_HELP" "stop"
assert_contains "transport help shows tempo" "$TRANSPORT_HELP" "tempo"
assert_contains "transport help shows loop" "$TRANSPORT_HELP" "loop"

# Track subcommand help
TRACK_HELP=$(java -jar "$CLI_JAR" track --help 2>&1)
assert_contains "track help shows set-volume" "$TRACK_HELP" "set-volume"
assert_contains "track help shows set-mute" "$TRACK_HELP" "set-mute"
assert_contains "track help shows set-solo" "$TRACK_HELP" "set-solo"
assert_contains "track help shows create-audio" "$TRACK_HELP" "create-audio"
assert_contains "track help shows create-instrument" "$TRACK_HELP" "create-instrument"
assert_contains "track help shows create-effect" "$TRACK_HELP" "create-effect"
assert_contains "track help shows select" "$TRACK_HELP" "select"
assert_contains "track help shows rename" "$TRACK_HELP" "rename"
assert_contains "track help shows delete-selected" "$TRACK_HELP" "delete-selected"
assert_contains "track help shows duplicate" "$TRACK_HELP" "duplicate"

# Device subcommand help
DEVICE_HELP=$(java -jar "$CLI_JAR" device --help 2>&1)
assert_contains "device help shows insert-bitwig" "$DEVICE_HELP" "insert-bitwig"
assert_contains "device help shows insert-plugin" "$DEVICE_HELP" "insert-plugin"
assert_contains "device help shows list-bitwig" "$DEVICE_HELP" "list-bitwig"
assert_contains "device help shows remove" "$DEVICE_HELP" "remove"

# Note subcommand help
NOTE_HELP=$(java -jar "$CLI_JAR" note --help 2>&1)
assert_contains "note help shows select" "$NOTE_HELP" "select"
assert_contains "note help shows set-notes" "$NOTE_HELP" "set-notes"
assert_contains "note help shows clear-note" "$NOTE_HELP" "clear-note"
assert_contains "note help shows clear-all" "$NOTE_HELP" "clear-all"
assert_contains "note help shows get-notes" "$NOTE_HELP" "get-notes"
assert_contains "note help shows set-step-size" "$NOTE_HELP" "set-step-size"
assert_contains "note help shows scroll-steps" "$NOTE_HELP" "scroll-steps"
assert_contains "note help shows delete" "$NOTE_HELP" "delete"

# Song subcommand help
SONG_HELP=$(java -jar "$CLI_JAR" song --help 2>&1)
assert_contains "song help shows dump" "$SONG_HELP" "dump"
assert_contains "song help shows rebuild" "$SONG_HELP" "rebuild"

# Song dump subcommand help
DUMP_HELP=$(java -jar "$CLI_JAR" song dump --help 2>&1)
assert_contains "song dump help shows --output" "$DUMP_HELP" "--output"
assert_contains "song dump help shows -o shorthand" "$DUMP_HELP" "-o"

# Song rebuild subcommand help
REBUILD_HELP=$(java -jar "$CLI_JAR" song rebuild --help 2>&1)
assert_contains "song rebuild help shows filePath" "$REBUILD_HELP" "<filePath>"

# Version
VERSION=$(java -jar "$CLI_JAR" --version 2>&1)
assert_contains "CLI version output" "$VERSION" "secondo-cli"

# =============================================
# O4. Extension Build
# =============================================

echo "--- O4. Extension Build ---"

(cd "$REPO_ROOT" && ./gradlew :secondo:build -q 2>/dev/null)
EXT_FILE="$HOME/Documents/Bitwig Studio/Extensions/Secondo.bwextension"
TOTAL=$((TOTAL + 1))
if [ -f "$EXT_FILE" ]; then
  echo "  PASS  Secondo.bwextension exists"
  PASS=$((PASS + 1))
else
  echo "  FAIL  Secondo.bwextension not found"
  FAIL=$((FAIL + 1))
fi

# No Picocli in extension JAR
PICOCLI_COUNT=$(jar tf "$EXT_FILE" | grep -c picocli || true)
assert_equals "extension JAR has no Picocli classes" "$PICOCLI_COUNT" "0"

# No CLI classes in extension JAR
CLI_CLASSES=$(jar tf "$EXT_FILE" | grep -c "gig/cli" || true)
assert_equals "extension JAR has no CLI classes" "$CLI_CLASSES" "0"
