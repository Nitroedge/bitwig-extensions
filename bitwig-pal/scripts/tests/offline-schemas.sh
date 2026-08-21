#
# Offline schema & system prompt validation tests.
# Sourced by the runner — do NOT add shebang or set -euo pipefail.
#

PROMPT=$(cat "$PROMPT_FILE")

# =============================================
# O1. Tool Schema Validation
# =============================================

echo "--- O1. Tool Schema Validation ---"

# --- structural checks ---

TOOL_COUNT=$(jq length "$TOOLS_FILE")
TOTAL=$((TOTAL + 1))
if [ "$TOOL_COUNT" -ge 171 ]; then
  echo "  PASS  claude-tools.json has >= 171 tools (found $TOOL_COUNT)"
  PASS=$((PASS + 1))
else
  echo "  FAIL  claude-tools.json has >= 171 tools — found $TOOL_COUNT"
  FAIL=$((FAIL + 1))
fi

MISSING_FIELDS=$(jq '[.[] | select(.name == null or .description == null or .input_schema == null)] | length' "$TOOLS_FILE")
assert_equals "all tools have name, description, input_schema" "$MISSING_FIELDS" "0"

UNIQUE_NAMES=$(jq '[.[].name] | unique | length' "$TOOLS_FILE")
assert_equals "all tool names are unique" "$UNIQUE_NAMES" "$TOOL_COUNT"

SLASH_NAMES=$(jq '[.[].name | select(contains("/"))] | length' "$TOOLS_FILE")
assert_equals "no tool names contain slashes" "$SLASH_NAMES" "0"

# --- tool existence (data-driven) ---

ALL_TOOLS=(
  # core (phases 1-3)
  session_snapshot
  transport_play
  transport_stop
  transport_record
  transport_setTempo
  transport_setPosition
  transport_setLoop
  transport_setMetronome
  track_setVolume
  track_setPan
  track_setMute
  track_setSolo
  track_setArm
  clip_launch
  clip_stop
  clip_create
  device_selectNext
  device_selectPrevious
  device_setEnabled
  device_selectPage
  device_nextPage
  device_previousPage
  device_setParameterValue
  cursor_selectTrack
  master_setVolume
  master_setPan
  master_setMute
  master_setSolo
  master_setColor
  scene_launch

  # phase 4 — note editing
  clip_select
  clip_setNotes
  clip_clearNote
  clip_clearAllNotes
  clip_getNotes
  clip_setStepSize
  clip_scrollSteps
  clip_delete

  # phase 5 — device management
  device_insertBitwigDevice
  device_insertPluginDevice
  device_listBitwigDevices
  device_remove

  # phase 6 — track management
  track_createAudio
  track_createInstrument
  track_createEffect
  track_select
  track_rename
  track_deleteSelected
  track_duplicate

  # phase 8 — arranger
  arranger_setPlaybackFollow
  arranger_setClipLauncherVisible
  arranger_setTimelineVisible
  arranger_setCueMarkersVisible
  arranger_setEffectTracksVisible
  arranger_setIoSectionVisible
  arranger_setDoubleRowTrackHeight
  transport_setLoopRange
  transport_getLoopRange
  transport_setPunchIn
  transport_setPunchOut
  transport_setAutomationWriteMode
  transport_setArrangerAutomationWrite
  transport_setClipLauncherAutomationWrite
  transport_resetAutomationOverrides
  cueMarker_addAtPlayhead
  cueMarker_list
  cueMarker_launch
  cueMarker_delete

  # phase 9 — envelope writing
  device_hasAutomation
  device_deleteAllAutomation
  device_restoreAutomationControl
  device_touch
  device_writeEnvelope

  # phase 10 — lifecycle
  scene_create
  scene_createFromPlaying
  scene_duplicate
  scene_rename
  scene_delete
  clip_rename
  clip_duplicate
  clip_duplicateToSlot
  cueMarker_rename
  cueMarker_setPosition
  cueMarker_duplicate

  # phase 11 — bank scrolling
  sceneBank_scrollTo
  sceneBank_scrollBy
  sceneBank_getScrollInfo
  cueMarkerBank_scrollTo
  cueMarkerBank_scrollBy
  cueMarkerBank_getScrollInfo
  trackBank_scrollTo
  trackBank_scrollBy
  trackBank_getScrollInfo

  # phase 12 — transactions & macros
  session_transaction
  macro_createTrack
  macro_createClip
  macro_writeClip
  macro_buildSection
  macro_buildSong
  macro_writeAutomation

  # phase 13 — mixer & routing
  send_setLevel
  send_setMode
  send_setEnabled
  track_setColor
  track_setCrossfade
  track_setMonitor

  # phase 14 — master device
  masterDevice_selectNext
  masterDevice_selectPrevious
  masterDevice_setEnabled
  masterDevice_insertBitwigDevice
  masterDevice_insertPluginDevice
  masterDevice_remove
  masterDevice_selectPage
  masterDevice_nextPage
  masterDevice_previousPage
  masterDevice_setParameterValue

  # phase 15 — chain navigation
  device_enterSlot
  device_exitToParent
  masterDevice_enterSlot
  masterDevice_exitToParent

  # phase 16 — project & session management
  app_activateEngine
  app_deactivateEngine
  app_showNotification
  app_setPanelLayout
  app_toggleInspector
  app_toggleDevices
  app_toggleMixer
  app_toggleNoteEditor
  app_toggleAutomationEditor
  app_toggleBrowser
  app_toggleFullScreen
  app_previousSubPanel
  app_nextSubPanel
  project_unsoloAll
  project_unmuteAll
  project_unarmAll
  project_getState
  transport_continuePlayback
  transport_restart
  transport_returnToArrangement
  transport_jumpToPreviousCueMarker
  transport_jumpToNextCueMarker
  transport_setPreRoll
  transport_setMetronomeVolume

  # phase 17 — browser
  browser_browsePresets
  browser_browseInsertDevice
  browser_selectNextFile
  browser_selectPreviousFile
  browser_selectFirstFile
  browser_selectLastFile
  browser_commit
  browser_cancel
  browser_setContentType
  browser_setShouldAudition
  browser_getState

  # phase 18 — browser filters
  browser_filterSelectNext
  browser_filterSelectPrevious
  browser_filterSelectFirst
  browser_filterSelectLast
  browser_filterSelectParent
  browser_filterSelectFirstChild
  browser_filterReset
  browser_getFilters
  browser_getResults
  browser_scrollResults

  # phase 19 — clip launcher automation
  clip_setLaunchQuantization
  clip_setLaunchMode
  clip_setShuffle
  clip_setAccent
  clip_setUseLoopStartAsQuantizationReference
  clip_getLaunchSettings
  transport_setDefaultLaunchQuantization
  transport_setPostRecordingAction
  transport_setPostRecordingTimeOffset
  transport_setClipLauncherOverdub
  transport_setFillMode
  transport_getClipLauncherSettings

  # phase 20 — clip grid enhancements
  clip_setColor
  clip_setPlayStart
  clip_setPlayStop
  clip_setLoopStart
  clip_setLoopLength
  clip_setLoopEnabled
  clip_getPlaybackSettings
  clip_quantize
  clip_transpose
  clip_duplicateContent
  clip_showInEditor
  clip_launchAlt
  clip_launchRelease
  clip_launchReleaseAlt
  scene_setColor
  scene_launchAlt
  scene_launchRelease
  scene_launchReleaseAlt

  # phase 22 — expressive note properties
  clip_setNoteExpressions
  clip_setNoteRepeat
  clip_setNoteOccurrence
  clip_setNoteRecurrence

  # phase 23 — NoteInput & Arpeggiator
  noteInput_sendNote
  noteInput_sendMidi
  arpeggiator_configure
  arpeggiator_setEnabled
  arpeggiator_releaseNotes
  arpeggiator_getState
  noteLatch_configure
  noteLatch_setEnabled
  noteLatch_releaseNotes
  noteLatch_getState

  # phase 24 — device sound design navigation
  device_enterLayer
  device_enterKeyPad
  device_selectPageByTag
  masterDevice_enterLayer
  masterDevice_enterKeyPad
  masterDevice_selectPageByTag

  # phase 25 — track routing & groups
  track_setGroupExpanded
  track_navigateInto
  track_navigateToParent
  track_createGroup
  track_addNoteSource
  track_removeNoteSource

  # gig phase 16 — batch parameter setters
  device_setParameters
  masterDevice_setParameters

  # gig phase 18 — macro createSound
  macro_createSound

  # gig phase 22 — device discovery
  device_discoverAll
  device_getDiscoveryResult

  # gig phase 30 — groove, exclusive solo, zoom
  groove_getState
  groove_setEnabled
  groove_setParameter
  track_toggleSolo
  app_zoomIn
  app_zoomOut
  app_zoomToFit
  app_zoomToSelection

  # gig phase 39 — websocket subscriptions
  state_subscribe
  state_unsubscribe
  state_subscribeAll
  state_getTopics

  # gig phase 38 — modulation mapping & clip key scrolling
  device_setParameterMapping
  device_getParameterMapping
  masterDevice_setParameterMapping
  masterDevice_getParameterMapping
  note_scrollToKey
  note_scrollKeysPageUp
  note_scrollKeysPageDown
  note_scrollKeysStepUp
  note_scrollKeysStepDown

  # gig phase 37 — detail editor panel control
  detailEditor_zoomIn
  detailEditor_zoomOut
  detailEditor_zoomToFit
  detailEditor_zoomToSelection
  detailEditor_zoomToFitSelectionOrAll
  detailEditor_zoomInLanes
  detailEditor_zoomOutLanes
  detailEditor_zoomToRegion

  # gig phase 36 — action discovery & invoke
  app_navigateIntoTrackGroup
  app_navigateToParentTrackGroup
  action_list
  action_listCategories
  action_invoke

  # gig phase 31 — mixer visibility & track navigation
  mixer_getState
  mixer_setSection
  mixer_zoomInAll
  mixer_zoomOutAll
  mixer_zoomInSelected
  mixer_zoomOutSelected
  track_selectInMixer
  track_makeVisibleInMixer

  # gig phase 32 — track queries & cursor navigation
  cursor_selectParent
  cursor_selectFirstChild
  cursor_setPinned
  cursor_getInfo

  # gig phase 33 — VU metering & activity feedback
  track_getVuMeters
  track_getPlayingNotes

  # gig phase 34 — cue mix
  project_setCueVolume
  project_setCueMix

  # gig phase 35 — arranger lane zoom & timeline navigation
  arranger_zoomInLanes
  arranger_zoomOutLanes
  arranger_zoomInSelectedLanes
  arranger_zoomOutSelectedLanes
  arranger_zoomToRegion
  arranger_zoomToFitSelectionOrAll
)

for tool in "${ALL_TOOLS[@]}"; do
  TOTAL=$((TOTAL + 1))
  if grep -qF "\"$tool\"" "$TOOLS_FILE"; then
    echo "  PASS  tool exists: $tool"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  tool missing: $tool"
    FAIL=$((FAIL + 1))
  fi
done

# --- tool schema spot-checks (~20 representative assertions) ---

echo "--- O1b. Tool Schema Spot-Checks ---"

# Parameter type checks (tool, jq_path, expected)
PARAM_CHECKS=(
  "transport_setTempo|.input_schema.properties.tempo.type|number"
  "track_setVolume|.input_schema.properties.index.type|integer"
  "track_setVolume|.input_schema.properties.value.type|number"
  "clip_create|.input_schema.properties.lengthInBeats.type|integer"
  "clip_setNotes|.input_schema.properties.notes.type|array"
  "clip_setStepSize|.input_schema.properties.size.type|number"
  "clip_scrollSteps|.input_schema.properties.offset.type|integer"
  "device_insertBitwigDevice|.input_schema.properties.name.type|string"
  "track_select|.input_schema.properties.index.type|integer"
  "track_rename|.input_schema.properties.name.type|string"
  "track_createAudio|.input_schema.properties.position.type|integer"
  "transport_setLoopRange|.input_schema.properties.start.type|number"
  "transport_setLoopRange|.input_schema.properties.duration.type|number"
  "transport_setPunchIn|.input_schema.properties.position.type|number"
  "cueMarker_launch|.input_schema.properties.index.type|integer"
  "cueMarker_delete|.input_schema.properties.index.type|integer"
  "device_writeEnvelope|.input_schema.properties.index.type|integer"
  "device_writeEnvelope|.input_schema.properties.points.type|array"
  "device_writeEnvelope|.input_schema.properties.points.items.properties.position.type|number"
  "device_writeEnvelope|.input_schema.properties.points.items.properties.value.type|number"
  "device_touch|.input_schema.properties.index.type|integer"
  "device_touch|.input_schema.properties.touched.type|boolean"
  "device_hasAutomation|.input_schema.properties.index.type|integer"
  "scene_rename|.input_schema.properties.index.type|integer"
  "scene_rename|.input_schema.properties.name.type|string"
  "clip_rename|.input_schema.properties.name.type|string"
  "clip_duplicateToSlot|.input_schema.properties.srcTrackIndex.type|integer"
  "clip_duplicateToSlot|.input_schema.properties.destSlotIndex.type|integer"
  "cueMarker_rename|.input_schema.properties.name.type|string"
  "cueMarker_setPosition|.input_schema.properties.beats.type|number"
  "cueMarker_duplicate|.input_schema.properties.index.type|integer"
  "sceneBank_scrollTo|.input_schema.properties.position.type|integer"
  "trackBank_scrollBy|.input_schema.properties.amount.type|integer"
  "cueMarkerBank_scrollTo|.input_schema.properties.position.type|integer"
  "send_setLevel|.input_schema.properties.trackIndex.type|integer"
  "send_setLevel|.input_schema.properties.value.type|number"
  "track_setColor|.input_schema.properties.r.type|number"
  "masterDevice_setEnabled|.input_schema.properties.enabled.type|boolean"
  "masterDevice_setParameterValue|.input_schema.properties.index.type|integer"
  "masterDevice_setParameterValue|.input_schema.properties.value.type|number"
  "device_enterSlot|.input_schema.properties.name.type|string"
  "masterDevice_enterSlot|.input_schema.properties.name.type|string"
  "clip_quantize|.input_schema.properties.amount.type|number"
  "clip_transpose|.input_schema.properties.semitones.type|integer"
  "device_setParameters|.input_schema.properties.pages.type|array"
  "device_setParameters|.input_schema.properties.pages.items.properties.params.type|array"
  "macro_createSound|.input_schema.properties.device.type|string"
  "device_getDiscoveryResult|.input_schema.properties.format.type|string"
  "macro_createTrack|.input_schema.properties.pages.type|array"
  "macro_createTrack|.input_schema.properties.pages.items.properties.pageIndex.type|integer"
  "macro_createTrack|.input_schema.properties.pages.items.properties.params.type|array"
  "macro_createTrack|.input_schema.properties.color.type|object"
  "macro_createTrack|.input_schema.properties.color.properties.r.type|number"
  "macro_buildSong|.input_schema.properties.tracks.type|array"
  "macro_buildSong|.input_schema.properties.sections.type|array"
  "macro_buildSong|.input_schema.properties.tracks.items.properties.type.type|string"
  "macro_buildSong|.input_schema.properties.tracks.items.properties.color.type|object"
  "macro_createTrack|.input_schema.properties.plugin.type|object"
  "macro_createTrack|.input_schema.properties.plugin.properties.type.type|string"
  "macro_createTrack|.input_schema.properties.plugin.properties.id.type|string"
  "macro_createSound|.input_schema.properties.plugin.type|object"
  "macro_buildSong|.input_schema.properties.tracks.items.properties.plugin.type|object"
  "macro_writeClip|.input_schema.properties.notes.items.properties.chance.type|number"
  "macro_writeClip|.input_schema.properties.notes.items.properties.expressions.type|object"
  "macro_writeClip|.input_schema.properties.notes.items.properties.repeat.type|object"
  "macro_writeClip|.input_schema.properties.notes.items.properties.occurrence.type|string"
  "macro_writeClip|.input_schema.properties.notes.items.properties.recurrence.type|object"
  "macro_buildSection|.input_schema.properties.clips.items.properties.notes.items.properties.chance.type|number"
  "macro_buildSection|.input_schema.properties.clips.items.properties.notes.items.properties.expressions.type|object"
  "macro_writeAutomation|.input_schema.properties.envelopes.type|array"
  "macro_writeAutomation|.input_schema.properties.envelopes.items.properties.paramIndex.type|integer"
  "macro_writeAutomation|.input_schema.properties.envelopes.items.properties.pageIndex.type|integer"
  "macro_writeAutomation|.input_schema.properties.envelopes.items.properties.points.type|array"
  "macro_writeAutomation|.input_schema.properties.envelopes.items.properties.points.items.properties.position.type|number"
  "groove_setEnabled|.input_schema.properties.enabled.type|boolean"
  "groove_setParameter|.input_schema.properties.name.type|string"
  "groove_setParameter|.input_schema.properties.value.type|number"
  "track_toggleSolo|.input_schema.properties.index.type|integer"
  "track_toggleSolo|.input_schema.properties.exclusive.type|boolean"
  "device_setParameterMapping|.input_schema.properties.index.type|integer"
  "device_setParameterMapping|.input_schema.properties.enabled.type|boolean"
  "note_scrollToKey|.input_schema.properties.key.type|integer"
  "detailEditor_zoomToRegion|.input_schema.properties.from.type|number"
  "detailEditor_zoomToRegion|.input_schema.properties.to.type|number"
  "app_navigateIntoTrackGroup|.input_schema.properties.trackIndex.type|integer"
  "action_invoke|.input_schema.properties.id.type|string"
  "mixer_setSection|.input_schema.properties.section.type|string"
  "mixer_setSection|.input_schema.properties.visible.type|boolean"
  "track_selectInMixer|.input_schema.properties.index.type|integer"
  "track_makeVisibleInMixer|.input_schema.properties.index.type|integer"
  "cursor_setPinned|.input_schema.properties.pinned.type|boolean"
  "track_getPlayingNotes|.input_schema.properties.index.type|integer"
  "project_setCueVolume|.input_schema.properties.value.type|number"
  "project_setCueMix|.input_schema.properties.value.type|number"
  "arranger_zoomToRegion|.input_schema.properties.from.type|number"
  "arranger_zoomToRegion|.input_schema.properties.to.type|number"
)

for entry in "${PARAM_CHECKS[@]}"; do
  IFS='|' read -r tool jq_path expected <<< "$entry"
  actual=$(jq -r ".[] | select(.name==\"$tool\") | $jq_path" "$TOOLS_FILE")
  assert_equals "$tool ${jq_path##*.} is $expected" "$actual" "$expected"
done

# Enum checks (tool;jq_path;expected_value) — semicolon-delimited to avoid jq pipe conflicts
ENUM_CHECKS=(
  "cursor_selectTrack;.input_schema.properties.direction.enum | join(\",\");next,previous"
  "device_insertBitwigDevice;.input_schema.properties.position.enum | join(\",\");end,before,after"
  "device_insertPluginDevice;.input_schema.properties.type.enum | join(\",\");vst2,vst3,clap"
  "transport_setAutomationWriteMode;.input_schema.properties.mode.enum | join(\",\");latch,touch,write"
  "send_setMode;.input_schema.properties.mode.enum | join(\",\");AUTO,PRE,POST"
  "track_setCrossfade;.input_schema.properties.mode.enum | join(\",\");A,B,AB"
  "track_setMonitor;.input_schema.properties.mode.enum | join(\",\");ON,OFF,AUTO"
  "masterDevice_insertBitwigDevice;.input_schema.properties.position.enum | join(\",\");end,before,after"
  "groove_setParameter;.input_schema.properties.name.enum | join(\",\");accentAmount,accentPhase,accentRate,shuffleAmount,shuffleRate"
  "mixer_setSection;.input_schema.properties.section.enum | join(\",\");meter,io,sends,clipLauncher,devices,crossFade"
)

for entry in "${ENUM_CHECKS[@]}"; do
  IFS=';' read -r tool jq_path expected <<< "$entry"
  actual=$(jq -r ".[] | select(.name==\"$tool\") | $jq_path" "$TOOLS_FILE")
  assert_equals "$tool enum is $expected" "$actual" "$expected"
done

# Enum length checks (tool;jq_path;expected_count) — semicolon-delimited
ENUM_LEN_CHECKS=(
  "app_setPanelLayout;.input_schema.properties.layout.enum | length;3"
  "transport_setPreRoll;.input_schema.properties.value.enum | length;4"
  "browser_filterSelectNext;.input_schema.properties.column.enum | length;8"
  "browser_scrollResults;.input_schema.properties.direction.enum | length;4"
  "clip_launch;.input_schema.properties.quantization.enum | length;10"
  "clip_launch;.input_schema.properties.launchMode.enum | length;5"
  "scene_launch;.input_schema.properties.quantization.enum | length;10"
  "clip_setLaunchQuantization;.input_schema.properties.quantization.enum | length;10"
  "clip_setLaunchMode;.input_schema.properties.launchMode.enum | length;5"
  "transport_setDefaultLaunchQuantization;.input_schema.properties.quantization.enum | length;9"
  "transport_setPostRecordingAction;.input_schema.properties.action.enum | length;7"
)

for entry in "${ENUM_LEN_CHECKS[@]}"; do
  IFS=';' read -r tool jq_path expected <<< "$entry"
  actual=$(jq -r ".[] | select(.name==\"$tool\") | $jq_path" "$TOOLS_FILE")
  assert_equals "$tool enum length is $expected" "$actual" "$expected"
done

# Required field checks (tool, jq_path, expected)
REQUIRED_CHECKS=(
  "device_enterSlot|.input_schema.required[0]|name"
  "app_showNotification|.input_schema.required[0]|text"
  "browser_setContentType|.input_schema.required[0]|index"
  "browser_setShouldAudition|.input_schema.required[0]|enabled"
  "browser_filterReset|.input_schema.required[0]|column"
)

for entry in "${REQUIRED_CHECKS[@]}"; do
  IFS='|' read -r tool jq_path expected <<< "$entry"
  actual=$(jq -r ".[] | select(.name==\"$tool\") | $jq_path" "$TOOLS_FILE")
  assert_equals "$tool requires $expected" "$actual" "$expected"
done

# --- tool description spot-checks ---

echo "--- O1c. Tool Description Spot-Checks ---"

DEVICE_REMOVE_DESC=$(jq -r '.[] | select(.name=="device_remove") | .description' "$TOOLS_FILE")
assert_contains "device_remove warns about loses selection" "$DEVICE_REMOVE_DESC" "loses selection"

TRACK_CREATE_AUDIO_DESC=$(jq -r '.[] | select(.name=="track_createAudio") | .description' "$TOOLS_FILE")
assert_contains "track_createAudio warns snapshot to verify" "$TRACK_CREATE_AUDIO_DESC" "snapshot"

CURSOR_SELECT_DESC=$(jq -r '.[] | select(.name=="cursor_selectTrack") | .description' "$TOOLS_FILE")
assert_contains "cursor_selectTrack mentions track_select" "$CURSOR_SELECT_DESC" "track_select"

CLIP_LAUNCH_DESC=$(jq -r '.[] | select(.name=="clip_launch") | .description' "$TOOLS_FILE")
assert_contains "clip_launch warns about transport" "$CLIP_LAUNCH_DESC" "transport"

SNAP_DESC=$(jq -r '.[] | select(.name=="session_snapshot") | .description' "$TOOLS_FILE")
assert_contains "session_snapshot mentions nesting info" "$SNAP_DESC" "nesting info"
assert_contains "session_snapshot mentions project state" "$SNAP_DESC" "hasSoloedTracks"
assert_contains "session_snapshot mentions metronomeVolume" "$SNAP_DESC" "metronomeVolume"
assert_contains "session_snapshot mentions browser" "$SNAP_DESC" "browser state"
assert_contains "session_snapshot mentions filters" "$SNAP_DESC" "filters"
assert_contains "session_snapshot mentions trackType" "$SNAP_DESC" "trackType"
assert_contains "session_snapshot mentions isGroup" "$SNAP_DESC" "isGroup"
assert_contains "session_snapshot mentions isGroupExpanded" "$SNAP_DESC" "isGroupExpanded"
assert_contains "snapshot mentions arpeggiator" "$SNAP_DESC" "arpeggiator"
assert_contains "snapshot mentions noteLatch" "$SNAP_DESC" "noteLatch"
assert_contains "snapshot mentions groove" "$SNAP_DESC" "groove state"
assert_contains "snapshot mentions canHoldNoteData" "$SNAP_DESC" "canHoldNoteData"
assert_contains "snapshot mentions isMutedBySolo" "$SNAP_DESC" "isMutedBySolo"

GETNOTES_DESC=$(jq -r '.[] | select(.name=="clip_getNotes") | .description' "$TOOLS_FILE")
assert_contains "clip_getNotes describes pan" "$GETNOTES_DESC" "pan"
assert_contains "clip_getNotes describes transpose" "$GETNOTES_DESC" "transpose"
assert_contains "clip_getNotes describes occurrence" "$GETNOTES_DESC" "occurrence"
assert_contains "clip_getNotes describes recurrence" "$GETNOTES_DESC" "recurrence"

# --- phase 22 complex schema checks ---

EXPR_PROPS=$(jq -r '.[] | select(.name=="clip_setNoteExpressions") | .input_schema.properties.notes.items.properties.property.enum | join(",")' "$TOOLS_FILE")
assert_contains "setNoteExpressions has pan in enum" "$EXPR_PROPS" "pan"
assert_contains "setNoteExpressions has gain in enum" "$EXPR_PROPS" "gain"
assert_contains "setNoteExpressions has mute in enum" "$EXPR_PROPS" "mute"

OCC_ENUM=$(jq -r '.[] | select(.name=="clip_setNoteOccurrence") | .input_schema.properties.notes.items.properties.condition.enum | join(",")' "$TOOLS_FILE")
assert_contains "setNoteOccurrence has ALWAYS" "$OCC_ENUM" "ALWAYS"
assert_contains "setNoteOccurrence has FILL" "$OCC_ENUM" "FILL"
assert_contains "setNoteOccurrence has NOT_FIRST" "$OCC_ENUM" "NOT_FIRST"

REPEAT_REQ=$(jq -r '.[] | select(.name=="clip_setNoteRepeat") | .input_schema.properties.notes.items.required | join(",")' "$TOOLS_FILE")
assert_contains "setNoteRepeat requires count" "$REPEAT_REQ" "count"
assert_contains "setNoteRepeat requires curve" "$REPEAT_REQ" "curve"
assert_contains "setNoteRepeat requires velocityEnd" "$REPEAT_REQ" "velocityEnd"
assert_contains "setNoteRepeat requires velocityCurve" "$REPEAT_REQ" "velocityCurve"

# --- phase 23 complex schema checks ---

ARP_MODE_ENUM=$(jq -r '.[] | select(.name=="arpeggiator_configure") | .input_schema.properties.mode.enum | join(",")' "$TOOLS_FILE")
assert_contains "arp mode enum has 'up'" "$ARP_MODE_ENUM" "up"
assert_contains "arp mode enum has 'random'" "$ARP_MODE_ENUM" "random"
assert_contains "arp mode enum has 'converge-up'" "$ARP_MODE_ENUM" "converge-up"
assert_contains "arp mode enum has 'pinky-down'" "$ARP_MODE_ENUM" "pinky-down"

SENDNOTE_REQ=$(jq -r '.[] | select(.name=="noteInput_sendNote") | .input_schema.required | join(",")' "$TOOLS_FILE")
assert_contains "sendNote requires note" "$SENDNOTE_REQ" "note"
assert_contains "sendNote requires velocity" "$SENDNOTE_REQ" "velocity"

LATCH_MODE_ENUM=$(jq -r '.[] | select(.name=="noteLatch_configure") | .input_schema.properties.mode.enum | join(",")' "$TOOLS_FILE")
assert_contains "latch mode enum has 'chord'" "$LATCH_MODE_ENUM" "chord"
assert_contains "latch mode enum has 'toggle'" "$LATCH_MODE_ENUM" "toggle"
assert_contains "latch mode enum has 'velocity'" "$LATCH_MODE_ENUM" "velocity"

# --- phase 24 complex schema checks ---

TAG_ENUM=$(jq -r '.[] | select(.name=="device_selectPageByTag") | .input_schema.properties.tag.enum | join(",")' "$TOOLS_FILE")
assert_contains "page tag enum has 'osc'" "$TAG_ENUM" "osc"
assert_contains "page tag enum has 'filter'" "$TAG_ENUM" "filter"
assert_contains "page tag enum has 'env'" "$TAG_ENUM" "env"
assert_contains "page tag enum has 'lfo'" "$TAG_ENUM" "lfo"

LAYER_PROPS=$(jq -r '.[] | select(.name=="device_enterLayer") | .input_schema.properties | keys | join(",")' "$TOOLS_FILE")
assert_contains "enterLayer has index prop" "$LAYER_PROPS" "index"
assert_contains "enterLayer has name prop" "$LAYER_PROPS" "name"

KEYPAD_REQ=$(jq -r '.[] | select(.name=="device_enterKeyPad") | .input_schema.required | join(",")' "$TOOLS_FILE")
assert_contains "enterKeyPad requires key" "$KEYPAD_REQ" "key"

# --- phase 25 complex schema checks ---

GRP_PROPS=$(jq -r '.[] | select(.name=="track_setGroupExpanded") | .input_schema.properties | keys | join(",")' "$TOOLS_FILE")
assert_contains "setGroupExpanded has expanded prop" "$GRP_PROPS" "expanded"
assert_contains "setGroupExpanded has toggle prop" "$GRP_PROPS" "toggle"

# --- phase 20 required field checks ---

CLIP_COLOR_REQ=$(jq -r '.[] | select(.name=="clip_setColor") | .input_schema.required | join(",")' "$TOOLS_FILE")
assert_contains "clip_setColor requires trackIndex" "$CLIP_COLOR_REQ" "trackIndex"
assert_contains "clip_setColor requires r" "$CLIP_COLOR_REQ" "r"

SCENE_COLOR_REQ=$(jq -r '.[] | select(.name=="scene_setColor") | .input_schema.required | join(",")' "$TOOLS_FILE")
assert_contains "scene_setColor requires index" "$SCENE_COLOR_REQ" "index"
assert_contains "scene_setColor requires r" "$SCENE_COLOR_REQ" "r"

# --- gig phase 18 required field checks ---

CREATE_SOUND_REQ=$(jq -r '.[] | select(.name=="macro_createSound") | .input_schema.required | join(",")' "$TOOLS_FILE")
assert_contains "macro_createSound requires pages" "$CREATE_SOUND_REQ" "pages"


# =============================================
# O2. System Prompt Validation
# =============================================

echo "--- O2. System Prompt Validation ---"

# Data-driven system prompt checks: (label, expected_text)
# Each entry is "label|expected_text" — grep against the file to avoid SIGPIPE.
PROMPT_CHECKS=(
  # core sections
  "system prompt covers viewport model|Track Bank"
  "system prompt covers perception-action loop|Perception-Action Loop"
  "system prompt covers value ranges|Value Ranges"
  "system prompt covers cursor model|Cursor Model"
  "system prompt covers index conventions|Index Conventions"
  "system prompt has example workflow|Example Workflow"
  "system prompt mentions 0.0 to 1.0 range|0.0 to 1.0"
  "system prompt mentions session_snapshot|session_snapshot"

  # note editing
  "system prompt covers note editing|Note Editing"
  "system prompt mentions MIDI note numbers|MIDI note"
  "system prompt mentions clip_setNotes|clip_setNotes"

  # device insertion
  "system prompt covers device insertion|Device Insertion"
  "system prompt mentions device_listBitwigDevices|device_listBitwigDevices"
  "system prompt mentions device_insertBitwigDevice|device_insertBitwigDevice"

  # track management
  "system prompt covers track management|Track Management"
  "system prompt mentions track_select|track_select"
  "system prompt mentions track_createInstrument|track_createInstrument"

  # phase 7 — known behaviors, error recovery, music reference
  "system prompt has Known Behaviors section|Known Behaviors"
  "system prompt has Error Recovery section|Error Recovery"
  "system prompt has Music Reference section|Music Reference"
  "system prompt has Song Building section|Song Building"
  "system prompt documents flush cycle|flush cycle"
  "system prompt documents error code -32602|-32602"
  "system prompt has snapshot before retry rule|Snapshot Before Retry"
  "system prompt has semitone offsets|semitone offsets"
  "system prompt has GM drum map|Kick"
  "system prompt has velocity bands|Ghost"
  "system prompt has default tempo|120 BPM"
  "system prompt has recommended call sequences|Recommended Call Sequences"

  # phase 8 — arrangement & automation
  "system prompt has Arrangement & Automation section|Arrangement & Automation"
  "system prompt covers arranger visibility|Arranger Visibility"
  "system prompt covers loop and punch range|Loop & Punch Range"
  "system prompt covers automation|Automation"
  "system prompt covers cue markers|Cue Markers"
  "system prompt has arrangement setup workflow|Arrangement Setup Workflow"
  "system prompt mentions arranger_setPlaybackFollow|arranger_setPlaybackFollow"
  "system prompt mentions transport_setLoopRange|transport_setLoopRange"
  "system prompt mentions cueMarker_addAtPlayhead|cueMarker_addAtPlayhead"
  "system prompt mentions automation write modes|latch"

  # phase 9 — envelope writing
  "system prompt has Envelope Writing section|Envelope Writing"
  "system prompt mentions device_writeEnvelope|device_writeEnvelope"
  "system prompt mentions device_hasAutomation|device_hasAutomation"
  "system prompt mentions device_touch|device_touch"
  "system prompt mentions write-only limitation|Write-only"
  "system prompt mentions async execution|Async execution"
  "system prompt mentions prerequisites|Prerequisites"

  # phase 10 — lifecycle operations
  "system prompt has Lifecycle Operations section|Lifecycle Operations"
  "system prompt covers Clip Lifecycle|Clip Lifecycle"
  "system prompt covers Scene Lifecycle|Scene Lifecycle"
  "system prompt covers Cue Marker Lifecycle|Cue Marker Lifecycle"
  "system prompt mentions clip_rename|clip_rename"
  "system prompt mentions scene_create|scene_create"
  "system prompt mentions scene_createFromPlaying|scene_createFromPlaying"
  "system prompt mentions cueMarker_rename|cueMarker_rename"
  "system prompt mentions cueMarker_setPosition|cueMarker_setPosition"

  # phase 11 — bank navigation
  "system prompt has Bank Navigation section|Bank Navigation"
  "system prompt mentions sceneBank tools|sceneBank_"
  "system prompt mentions trackBank tools|trackBank_"
  "system prompt mentions cueMarkerBank tools|cueMarkerBank_"
  "system prompt documents v0.11 migration|v0.11"

  # phase 12 — transactions & macros
  "system prompt has Transactions & Macros section|Transactions & Macros"
  "system prompt mentions session_transaction|session_transaction"
  "system prompt mentions macro_createTrack|macro_createTrack"
  "system prompt mentions macro_writeClip|macro_writeClip"
  "system prompt mentions macro_buildSection|macro_buildSection"
  "system prompt documents rollback|undoAll"
  "system prompt documents pre/post snapshots|pre/post snapshots"
  "system prompt documents postSnapshot option|postSnapshot"

  # phase 13 — mixer & routing
  "system prompt has Mixer & Routing section|Mixer & Routing"
  "system prompt mentions send_setLevel|send_setLevel"
  "system prompt mentions track_setColor|track_setColor"
  "system prompt mentions crossfade modes|crossfadeMode"
  "system prompt mentions monitor mode|monitorMode"
  "system prompt mentions master_setMute|master_setMute"

  # phase 14 — master bus FX
  "system prompt has Master Bus FX section|Master Bus FX"
  "system prompt mentions masterDevice_insertBitwigDevice|masterDevice_insertBitwigDevice"
  "system prompt mentions masterDevice vs device distinction|masterDevice_"

  # phase 15 — chain navigation
  "system prompt has Chain Navigation section|Chain Navigation"
  "system prompt mentions enterSlot|enterSlot"
  "system prompt mentions exitToParent|exitToParent"
  "system prompt mentions hasSlots|hasSlots"

  # phase 16 — project & session management
  "system prompt has Project & Session Management section|Project & Session Management"
  "system prompt mentions activateEngine|activateEngine"
  "system prompt mentions returnToArrangement|returnToArrangement"
  "system prompt mentions setPreRoll|setPreRoll"

  # phase 17 — browser
  "system prompt has Browser section|Browser & Preset Navigation"
  "system prompt mentions browsePresets|browsePresets"
  "system prompt mentions commit|browser_commit"
  "system prompt mentions audition|setShouldAudition"

  # phase 18 — browser filters
  "system prompt has Filter Navigation section|Browser Filter Navigation"
  "system prompt mentions filterSelectNext|filterSelectNext"
  "system prompt mentions filterReset|filterReset"
  "system prompt mentions scrollResults|scrollResults"

  # phase 19 — clip launcher automation
  "system prompt has Clip Launcher Automation section|Clip Launcher Automation"
  "system prompt mentions per-clip settings|clip_setLaunchQuantization"
  "system prompt mentions transport settings|transport_setDefaultLaunchQuantization"
  "system prompt mentions per-launch overrides|Per-Launch Overrides"

  # phase 20 — clip grid enhancements
  "system prompt has Clip Grid Enhancements section|Clip Grid Enhancements"
  "system prompt mentions clip_setColor|clip_setColor"
  "system prompt mentions clip_quantize|clip_quantize"
  "system prompt mentions scene_setColor|scene_setColor"
  "system prompt has Song Persistence section|Song Persistence"
  "system prompt mentions song dump|gig song dump"
  "system prompt mentions song rebuild|gig song rebuild"

  # phase 22 — expressive note properties
  "system prompt has Expressive Note Properties section|Expressive Note Properties"
  "system prompt mentions clip_setNoteExpressions|clip_setNoteExpressions"
  "system prompt mentions clip_setNoteRepeat|clip_setNoteRepeat"
  "system prompt mentions clip_setNoteOccurrence|clip_setNoteOccurrence"

  # phase 23 — NoteInput & Arpeggiator
  "system prompt has NoteInput & Arpeggiator section|NoteInput & Arpeggiator"
  "system prompt mentions noteInput_sendNote|noteInput_sendNote"
  "system prompt mentions arpeggiator_configure|arpeggiator_configure"
  "system prompt mentions noteLatch_configure|noteLatch_configure"

  # phase 24 — device sound design navigation
  "system prompt has Device Sound Design Navigation section|Device Sound Design Navigation"
  "system prompt mentions device_enterLayer|device_enterLayer"
  "system prompt mentions device_selectPageByTag|device_selectPageByTag"
  "system prompt mentions page tag table|osc"

  # phase 25 — track routing & groups
  "system prompt has Track Routing section|Track Routing"
  "system prompt mentions navigateInto|track_navigateInto"
  "system prompt mentions addNoteSource|track_addNoteSource"
  "system prompt mentions track types|trackType"

  # gig phase 16 — batch parameter setters
  "system prompt covers batch operations|Batch Operations"

  # gig phase 18 — macro createSound
  "system prompt mentions macro_createSound|macro_createSound"
  "system prompt mentions Creating Sounds From Scratch|Creating Sounds From Scratch"
  # gig phase 21 — panel visibility toggles
  "system prompt has panel visibility section|Panel visibility toggles"
  "system prompt mentions app_toggleDevices|app_toggleDevices"
  "system prompt mentions app_toggleNoteEditor|app_toggleNoteEditor"
  "system prompt mentions app_previousSubPanel|app_previousSubPanel"

  # gig phase 22 — device discovery & presets
  "system prompt mentions device_discoverAll|device_discoverAll"
  "system prompt mentions device_getDiscoveryResult|device_getDiscoveryResult"
  "system prompt has Device Reference Files section|Device Reference Files"
  "system prompt has Sound Presets section|Sound Presets"
  "system prompt mentions polymer.json|polymer.json"

  # gig phase 23 — live preset export
  "system prompt has Capturing Presets section|Capturing Presets"
  "system prompt mentions format preset|format: \"preset\""
  "system prompt mentions preset-compatible|preset-compatible"

  # gig phase 24 — macro improvements
  "system prompt documents createTrack with pages|inserts the device, and applies sound parameters"
  "system prompt documents track creation with sound design|Track creation with sound design"

  # gig phase 25 — macro composition chains
  "system prompt mentions macro_buildSong|macro_buildSong"
  "system prompt documents one-call alternative|One-call alternative"
  "system prompt documents sequential track delays|Tracks are created sequentially with proper delays"

  # gig phase 26 — plugin device support
  "system prompt documents plugin in macros|Macros also support plugins via"
  "system prompt documents plugin track creation|Track creation with plugin"

  # gig phase 27 — clip expression macros
  "system prompt documents inline expressions|inline expression properties"
  "system prompt documents expression grouping|grouped by type and applied in deferred flush"

  # gig phase 28 — macro automation curves
  "system prompt mentions macro_writeAutomation|macro_writeAutomation"
  "system prompt documents arranger automation macro|Arranger automation"

  # gig phase 30 — groove, exclusive solo, zoom
  "system prompt has Global Groove section|Global Groove"
  "system prompt mentions groove_getState|groove_getState"
  "system prompt mentions groove_setEnabled|groove_setEnabled"
  "system prompt mentions groove_setParameter|groove_setParameter"
  "system prompt has Exclusive Solo section|Exclusive Solo"
  "system prompt mentions track_toggleSolo|track_toggleSolo"
  "system prompt has zoom controls|app_zoomIn"
  "system prompt mentions zoomToFit|app_zoomToFit"
  "system prompt mentions zoomToSelection|app_zoomToSelection"

  # gig phase 39 — websocket subscriptions
  "system prompt has WebSocket Subscriptions section|WebSocket Subscriptions"
  "system prompt mentions state_subscribe|state_subscribe"
  "system prompt mentions state_getTopics|state_getTopics"
  "system prompt lists valid topics|transport, tracks, scenes"

  # gig phase 38 — modulation mapping & clip key scrolling
  "system prompt has Remote Control Mapping section|Remote Control Mapping"
  "system prompt mentions device_setParameterMapping|device_setParameterMapping"
  "system prompt mentions isBeingMapped|isBeingMapped"
  "system prompt has Clip Key Scrolling section|Clip Key Scrolling"
  "system prompt mentions note_scrollToKey|note_scrollToKey"
  "system prompt mentions note_scrollKeysPageUp|note_scrollKeysPageUp"

  # gig phase 37 — detail editor panel control
  "system prompt has DetailEditor section|DetailEditor Panel Control"
  "system prompt mentions detailEditor_zoomIn|detailEditor_zoomIn"
  "system prompt mentions detailEditor_zoomToRegion|detailEditor_zoomToRegion"
  "system prompt mentions detailEditor_zoomInLanes|detailEditor_zoomInLanes"

  # gig phase 36 — action discovery & invoke
  "system prompt has Action Discovery section|Action Discovery & Invoke"
  "system prompt mentions action_list|action_list"
  "system prompt mentions action_listCategories|action_listCategories"
  "system prompt mentions action_invoke|action_invoke"
  "system prompt mentions navigateIntoTrackGroup|app_navigateIntoTrackGroup"
  "system prompt mentions navigateToParentTrackGroup|app_navigateToParentTrackGroup"

  # gig phase 31 — mixer visibility & track navigation
  "system prompt has Mixer Panel Control section|Mixer Panel Control"
  "system prompt mentions mixer_getState|mixer_getState"
  "system prompt mentions mixer_setSection|mixer_setSection"
  "system prompt mentions mixer_zoomInAll|mixer_zoomInAll"
  "system prompt mentions mixer_zoomOutSelected|mixer_zoomOutSelected"
  "system prompt mentions track_selectInMixer|track_selectInMixer"
  "system prompt mentions track_makeVisibleInMixer|track_makeVisibleInMixer"

  # gig phase 32 — track queries & cursor navigation
  "system prompt has Track Capabilities section|Track Capabilities"
  "system prompt mentions canHoldNoteData|canHoldNoteData"
  "system prompt has Cursor Track Navigation section|Cursor Track Navigation"
  "system prompt mentions cursor_selectParent|cursor_selectParent"
  "system prompt mentions cursor_selectFirstChild|cursor_selectFirstChild"
  "system prompt mentions cursor_setPinned|cursor_setPinned"
  "system prompt mentions cursor_getInfo|cursor_getInfo"

  # gig phase 33 — VU metering & activity feedback
  "system prompt has Activity Feedback section|Activity Feedback"
  "system prompt mentions track_getVuMeters|track_getVuMeters"
  "system prompt mentions track_getPlayingNotes|track_getPlayingNotes"
  "system prompt mentions isMutedBySolo|isMutedBySolo"

  # gig phase 34 — cue mix
  "system prompt has Cue Mix Control section|Cue Mix Control"
  "system prompt mentions project_setCueVolume|project_setCueVolume"
  "system prompt mentions project_setCueMix|project_setCueMix"

  # gig phase 35 — arranger lane zoom & timeline navigation
  "system prompt has Arranger Lane Zoom section|Arranger Lane Zoom"
  "system prompt mentions arranger_zoomInLanes|arranger_zoomInLanes"
  "system prompt mentions arranger_zoomToRegion|arranger_zoomToRegion"
  "system prompt mentions arranger_zoomToFitSelectionOrAll|arranger_zoomToFitSelectionOrAll"
)

for entry in "${PROMPT_CHECKS[@]}"; do
  IFS='|' read -r label expected_text <<< "$entry"
  TOTAL=$((TOTAL + 1))
  if grep -qF -- "$expected_text" "$PROMPT_FILE"; then
    echo "  PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $label — expected '$expected_text' in system prompt"
    FAIL=$((FAIL + 1))
  fi
done
