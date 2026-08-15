# Bitwig Extensions — Project Context

Multi-module Gradle project containing Bitwig Studio controller extensions.

## Modules

### gig-maestro
RPC-based Bitwig controller extension + CLI tool.
- Extension source: `gig-maestro/src/main/java/dev/gregross/gig/`
- CLI source: `gig-maestro/src/cli/java/dev/gregross/gig/cli/`
- Tests: `gig-maestro/src/test/java/dev/gregross/gig/`
- Tool schemas: `gig-maestro/tools/claude-tools.json`
- System prompt: `gig-maestro/tools/system-prompt.md`
- Smoke tests: `gig-maestro/scripts/smoke-test.sh` (runner)
- Test scripts: `gig-maestro/scripts/tests/` (per-flow, shared helpers)

### launchpad-mk2
Novation Launchpad MK2 controller extension.
- Source: `launchpad-mk2/src/main/java/com/gregross/bitwig/launchpadmk2/`

## Bitwig API Reference

The full Bitwig Controller API v25 reference is at `gig-maestro/docs/bitwig-api-reference.txt` (18K+ lines).
**Always read this file** (or relevant sections via grep) when:
- Investigating what API methods are available for a feature
- Checking method signatures, parameter types, return types
- Looking up class hierarchies (e.g., Track extends Channel extends DeviceChain)
- Verifying if a capability exists before making decisions

## Build Commands

- `./gradlew :gig-maestro:shadowJar` — build gig-maestro extension (outputs to Bitwig Extensions dir)
- `./gradlew :gig-maestro:cliShadowJar` — build CLI JAR
- `./gradlew :gig-maestro:test` — run gig-maestro unit tests
- `./gradlew :launchpad-mk2:build` — build launchpad-mk2 extension
- `./gradlew :launchpad-mk2:install` — install to Bitwig Extensions dir
- `./gradlew clean build` — build everything
- `gig-maestro/scripts/smoke-test.sh` — full smoke suite (requires Bitwig running)
- `gig-maestro/scripts/smoke-test.sh --offline` — offline tests only

## Testing Requirements

Every phase MUST include all three testing layers during governance:

### 1. Unit Tests (automated, no Bitwig)
- `./gradlew :gig-maestro:test` — run before every commit
- Mock-based, verifies logic and validation in isolation

### 2. Smoke Tests (automated, per-flow scripts)
- `gig-maestro/scripts/smoke-test.sh` — runner (all tests)
- `gig-maestro/scripts/smoke-test.sh --offline` — schema/build checks only (no Bitwig)
- `gig-maestro/scripts/smoke-test.sh --online` — online tests only (requires Bitwig)
- `gig-maestro/scripts/smoke-test.sh --only NAME` — run specific test(s)
- `gig-maestro/scripts/smoke-test.sh --list` — list available test scripts
- Test scripts live in `gig-maestro/scripts/tests/`, each sourcing `_helpers.sh`
- Offline scripts: `offline-schemas.sh` (data-driven), `offline-builds.sh`
- Online scripts: `transport`, `tracks`, `clips`, `notes`, `devices`, `arranger`, `arranger-clip`, `mixer`, `browser`, `project`, `clip-launcher`, `health`, `errors`
  - `arranger` is arranger VIEW control; `arranger-clip` is arranger CLIP CONTENT (the `arrangerClip/*` namespace) and needs a clip selected by hand in the timeline
  - `smoke-test.sh --list` is the authority on what exists — the runner discovers scripts from the directory, so this line is documentation and can go stale; check it against `--list` before trusting it
- **Must be updated** when new RPC methods or tools are added in a phase
- Run during governance to catch integration issues

### 3. Manual Validation (requires Bitwig running)
- Provide `curl` commands to `http://localhost:8787/rpc` for each new feature
- Include expected responses and what to verify in the Bitwig UI
- Run during governance before the approval gate

**During `/gig:govern`:** Run all three layers. Report results. Do not skip manual validation.

## Git Preferences

- **Merge strategy:** Always use regular merge (`--no-ff`), never squash. Do not ask.

## Project History

Module-specific gig phase history is archived at:
- `.gig/modules/gig-maestro/` (25 phases)
- `.gig/modules/launchpad-mk2/` (5 phases)
