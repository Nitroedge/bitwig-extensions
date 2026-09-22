package dev.bcrick.secondo.extension;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.ClipLauncherSlotBankPlaybackStateChangedCallback;
import com.bitwig.extension.callback.ColorValueChangedCallback;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.callback.EnumValueChangedCallback;
import com.bitwig.extension.callback.IndexedBooleanValueChangedCallback;
import com.bitwig.extension.callback.IndexedStringValueChangedCallback;
import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.callback.StepDataChangedCallback;
import com.bitwig.extension.callback.StringArrayValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.bcrick.secondo.handlers.TrackBankManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StateCache {

    private static final int TRACK_COUNT = 16;
    private static final int SCENE_COUNT = 16;
    private static final int DEFAULT_SEND_COUNT = 4;

    // Transport state
    private volatile boolean isPlaying;
    private volatile boolean isRecording;
    private volatile double tempo;
    private volatile double playPosition;
    private volatile int timeSignatureNumerator;
    private volatile int timeSignatureDenominator;
    private volatile boolean isLoopEnabled;
    private volatile boolean isMetronomeEnabled;

    // Track state
    private final String[] trackNames = new String[TRACK_COUNT];
    private final double[] trackVolumes = new double[TRACK_COUNT];
    private final double[] trackPans = new double[TRACK_COUNT];
    private final boolean[] trackMutes = new boolean[TRACK_COUNT];
    private final boolean[] trackSolos = new boolean[TRACK_COUNT];
    private final boolean[] trackArms = new boolean[TRACK_COUNT];
    private final float[][] trackColors = new float[TRACK_COUNT][3]; // r, g, b
    private final String[] trackCrossfadeModes = new String[TRACK_COUNT];
    private final String[] trackMonitorModes = new String[TRACK_COUNT];
    private final String[] trackTypes = new String[TRACK_COUNT];
    private final boolean[] trackIsGroup = new boolean[TRACK_COUNT];
    private final boolean[] trackIsGroupExpanded = new boolean[TRACK_COUNT];
    private final Boolean[] trackExists = new Boolean[TRACK_COUNT];
    private final Integer[] trackPositions = new Integer[TRACK_COUNT];
    private final Boolean[] trackParentExists = new Boolean[TRACK_COUNT];
    private final Integer[] trackParentPositions = new Integer[TRACK_COUNT];
    // Parent position is not in the flattened bank coordinate. Compare Bitwig proxies instead.
    private final Boolean[][] trackParentEquals = new Boolean[TRACK_COUNT][TRACK_COUNT];
    private final Boolean[] trackActivations = new Boolean[TRACK_COUNT];
    private final boolean[] trackIdentityRequired = new boolean[TRACK_COUNT];
    private final String[] legacyTrackNames = new String[TRACK_COUNT];
    private final Integer[] legacyTrackPositions = new Integer[TRACK_COUNT];
    private volatile boolean[] trackCanHoldNoteData = new boolean[TRACK_COUNT];
    private volatile boolean[] trackCanHoldAudioData = new boolean[TRACK_COUNT];
    private volatile boolean[] trackMutedBySolo = new boolean[TRACK_COUNT];
    private volatile int[] trackVuMeter = new int[TRACK_COUNT];
    private volatile int[][] trackPlayingNotes = new int[TRACK_COUNT][0]; // [trackIdx] -> {pitch0, vel0, pitch1, vel1, ...}

    // Send state — [trackIndex][sendIndex]
    private volatile int sendCount = DEFAULT_SEND_COUNT;
    private final String[][] sendNames = new String[TRACK_COUNT][DEFAULT_SEND_COUNT];
    private final double[][] sendLevels = new double[TRACK_COUNT][DEFAULT_SEND_COUNT];
    private final boolean[][] sendIsPreFader = new boolean[TRACK_COUNT][DEFAULT_SEND_COUNT];
    private final boolean[][] sendEnabled = new boolean[TRACK_COUNT][DEFAULT_SEND_COUNT];
    private final float[][][] sendColors = new float[TRACK_COUNT][DEFAULT_SEND_COUNT][3];

    // Clip state — [trackIndex][slotIndex]
    private final boolean[][] clipHasContent = new boolean[TRACK_COUNT][SCENE_COUNT];

    // Has the has-content observer above EVER fired for this slot? (Phase 29, plan 29-03.)
    //
    // The array beside it is primitive, so it has no unobserved state: before any observer has
    // fired every slot reads as empty, and clipHasContent() returns false for an out-of-range
    // coordinate too. That makes "was this slot empty before we created a clip in it?" a question
    // with no answer to read — which is fine while the answer is only displayed, and NOT fine now
    // that a refusal DELETES on it. This parallel array is what makes "never observed"
    // distinguishable from "observed empty", so the undo can withhold rather than guess.
    private final boolean[][] clipHasContentObserved = new boolean[TRACK_COUNT][SCENE_COUNT];

    /**
     * WHEN each slot was last observed, as a monotonic sequence rather than as a latch.
     *
     * <p>The flag above answers "has this slot EVER been observed?", which is the question plan
     * 29-03 needed and NOT the question D-29-11 asked. Bitwig fires every slot's observers at
     * init, so after startup that flag is permanently true for every in-range slot, and
     * "the observation is confidently fresh" was therefore never actually implemented
     * (29-REVIEW.md, WR-01). A number can answer it where a latch cannot: a caller records
     * {@link #currentObservationTick()} at the moment it acts, and an observation is NEWER than
     * that act only when this slot's entry has since passed it. Zero means never observed, and
     * cannot collide with a real observation because the tick is pre-incremented.
     *
     * <p>Written ONLY inside the two observer lambdas in {@link #registerClipObservers}, in the
     * same statement group that assigns the value, for the reason the comment there already
     * gives. A second writer in a parallel bookkeeping path can drift from the value it claims to
     * date, and a freshness stamp that has drifted is worse than no freshness stamp at all.
     */
    private final long[][] clipObservationSeq = new long[TRACK_COUNT][SCENE_COUNT];

    /**
     * The source of the sequence above, pre-incremented by every clip observer callback that
     * lands.
     *
     * <p>{@code volatile} like the other cross-read scalars in this class, though every write and
     * every read of it happens on the one Control Surface Session thread.
     */
    private volatile long observationTick;

    /**
     * The one resolver that turns a caller's PUBLIC track index into the PHYSICAL flat-bank slot
     * every clip array in this class is keyed on, or null until {@link #setTrackBankManager} has
     * run.
     *
     * <p>{@link #registerClipObservers} is handed the canonical FLAT bank, so
     * {@code clipNames[i][j]} and {@code clipHasContent[i][j]} are subscripted by bank slot, while
     * {@code clip/create}, {@code clip/delete} and {@code clip/select} resolve the caller's public
     * index through {@code TrackBankManager}. Whenever the two differ -- a collapsed group, a
     * scrolled bank -- a reader that skips the resolution is asking about a different track from
     * the one the write touched (29-REVIEW.md, WR-02).
     *
     * <p>It is held HERE rather than passed into each reader so that the resolution sits beside
     * the arrays it resolves for, which is where the next reader will look for it, and so that
     * {@code MacroHandler}'s two constructors -- and therefore three test classes' setups -- stay
     * as they are.
     */
    private volatile TrackBankManager trackBankManager;

    private final boolean[][] clipIsPlaying = new boolean[TRACK_COUNT][SCENE_COUNT];
    private final boolean[][] clipIsRecording = new boolean[TRACK_COUNT][SCENE_COUNT];
    private final boolean[][] clipIsPlaybackQueued = new boolean[TRACK_COUNT][SCENE_COUNT];
    private final boolean[][] clipIsRecordingQueued = new boolean[TRACK_COUNT][SCENE_COUNT];
    private final boolean[][] clipIsStopQueued = new boolean[TRACK_COUNT][SCENE_COUNT];
    private final String[][] clipNames = new String[TRACK_COUNT][SCENE_COUNT];
    private final float[][][] clipColors = new float[TRACK_COUNT][SCENE_COUNT][3];

    // Scene state
    private final String[] sceneNames = new String[SCENE_COUNT];
    private final int[] sceneClipCounts = new int[SCENE_COUNT];
    private final float[][] sceneColors = new float[SCENE_COUNT][3];
    private volatile int sceneBankOffset;
    private volatile int sceneItemCount;
    private volatile boolean sceneCanScrollForwards;
    private volatile boolean sceneCanScrollBackwards;

    // Track bank scroll state
    private volatile int trackScrollPosition;
    private volatile int trackItemCount;
    private volatile boolean trackItemCountObserved;
    private volatile boolean trackCanScrollForwards;
    private volatile boolean trackCanScrollBackwards;

    // Master state
    private volatile double masterVolume;
    private volatile double masterPan;
    private volatile boolean masterMute;
    private volatile boolean masterSolo;
    private volatile Boolean masterActivated;
    private final float[] masterColor = new float[3];

    // Device state (cursor device)
    private static final int PARAM_COUNT = 8;
    private volatile String cursorTrackName = "";
    private volatile String deviceName = "";
    private volatile boolean deviceEnabled;
    private volatile boolean deviceIsPlugin;
    private volatile int devicePosition;
    private volatile String presetName = "";
    private volatile String presetCategory = "";
    private volatile String presetCreator = "";
    private volatile boolean isWindowOpen;
    private volatile boolean isExpanded;

    // Device nesting state
    private volatile boolean deviceIsNested;
    private volatile boolean deviceHasSlots;
    private volatile String[] deviceSlotNames = new String[0];
    private volatile boolean deviceHasLayers;
    private volatile boolean deviceHasDrumPads;

    // Remote controls state
    private volatile int pageIndex;
    private volatile int pageCount;
    private volatile String[] devicePageNames = new String[0];
    private final String[] paramNames = new String[PARAM_COUNT];
    private final double[] paramValues = new double[PARAM_COUNT];
    private final String[] paramDisplayedValues = new String[PARAM_COUNT];
    private final boolean[] paramHasAutomation = new boolean[PARAM_COUNT];
    private final double[] paramModulatedValues = new double[PARAM_COUNT];
    private final boolean[] paramIsBeingMapped = new boolean[PARAM_COUNT];

    // Master device state (master cursor device)
    private volatile String masterDeviceName = "";
    private volatile boolean masterDeviceEnabled;
    private volatile boolean masterDeviceIsPlugin;
    private volatile int masterDevicePosition;
    private volatile String masterPresetName = "";
    private volatile String masterPresetCategory = "";
    private volatile String masterPresetCreator = "";

    // Master device nesting state
    private volatile boolean masterDeviceIsNested;
    private volatile boolean masterDeviceHasSlots;
    private volatile String[] masterDeviceSlotNames = new String[0];
    private volatile boolean masterDeviceHasLayers;
    private volatile boolean masterDeviceHasDrumPads;

    // Master remote controls state
    private volatile int masterPageIndex;
    private volatile int masterPageCount;
    private volatile String[] masterDevicePageNames = new String[0];
    private final String[] masterParamNames = new String[PARAM_COUNT];
    private final double[] masterParamValues = new double[PARAM_COUNT];
    private final String[] masterParamDisplayedValues = new String[PARAM_COUNT];
    private final double[] masterParamModulatedValues = new double[PARAM_COUNT];
    private final boolean[] masterParamIsBeingMapped = new boolean[PARAM_COUNT];

    // Cursor clip state
    private volatile int clipPlayingStep = -1;
    private volatile double clipLoopLength;
    private volatile double clipPlayStart;
    private volatile double clipPlayStop;
    private volatile boolean clipHasNotes;
    private volatile String clipTrackName = "";
    private volatile double clipStepSize = 0.25; // default 1/16
    private volatile String clipLaunchQuantization = "";
    private volatile String clipLaunchMode = "";
    private volatile boolean clipShuffle;
    private volatile double clipAccent;
    private volatile boolean clipUseLoopStartAsQuantizationReference;
    private volatile boolean clipLoopEnabled;
    private volatile double clipLoopStart;
    private final float[] clipColor = new float[3];

    // Where the cursor clip actually IS, absolutely (Phase 23, plan 23-08, TODO-WRONG-SLOT).
    //
    // Both are ABSOLUTE project coordinates, not bank-relative: Track#position() is "the position
    // of the track within the list of Bitwig Studio tracks" and ClipLauncherSlotOrScene#sceneIndex()
    // is "the position of the scene within the list of Bitwig Studio scenes". That is what makes
    // them comparable to the (trackIndex, sceneIndex) a macro/writeClip caller names.
    //
    // -1 means NEVER OBSERVED, and is deliberately not 0: 0 is a real slot, and a fix whose
    // "unknown" reads as a valid coordinate would write to track 0 scene 0 whenever the observers
    // had not fired yet. MacroHandler treats -1 as a mismatch and re-polls.
    private volatile int clipCursorTrackPosition = -1;
    private volatile int clipCursorSceneIndex = -1;

    // The refusal counter, published in the clip snapshot section.
    //
    // WHAT WAS TRUE FROM THE SIXTH PIN MOVE UNTIL THE FOURTEENTH: this counter stood beside a
    // per-refusal detail object, because at those pins the refusal COULD NOT travel in
    // the RPC response (see JsonRpcError.CURSOR_MISMATCH). host.errorln alone would put it in a
    // console the owner has to have been watching; a detail in the snapshot was retrievable after
    // the fact, which is what "a bounded result has to announce its boundary" needed to mean when
    // the boundary was hit asynchronously.
    //
    // From Phase 29 (plan 29-03) the detail is RETIRED and only the tally remains. The refusal
    // now travels in the response for macro/writeClip; the four chain macros still do not defer,
    // and this counter is their only machine-readable refusal signal. See
    // recordWriteClipRefusal's own comment for the whole of D-29-13.
    private volatile int writeClipRefusals;

    // Arranger cursor clip state (D-06). Deliberately NOT a copy of the launcher block above:
    //
    //  * `exists` is FIRST and is the load-bearing field. It is the only member of the interface
    //    that can distinguish "nothing is selected in the arranger" from "the selected clip is
    //    empty", which is what D-02's refusal contract rests on.
    //  * There is NO `hasContent` twin. The launcher's `clipHasNotes` is a write-once latch --
    //    the step-data observer sets it true and nothing anywhere sets it false -- so a parallel
    //    field would answer true forever and would be useless as a discriminator. `exists()` plus
    //    the note count from arrangerClip/getNotes answers emptiness on this surface instead.
    //  * Every field below is a BOXED type left null until its observer fires, not a primitive
    //    sitting at 0.0 / "" / false. An observer that has not fired must stay distinguishable
    //    from one that fired with a zero value: `loopStart == 0.0` is a real clip starting at the
    //    first bar, so recording "nobody has read this yet" as 0.0 would be precisely the
    //    absent-field-as-zero failure this surface exists to avoid. `playingStep` is the one
    //    exception and carries the launcher's own declared unset sentinel, -1.
    //  * There is no observable step size: the API publishes two setStepSize setters and no
    //    getter, so this holds the last value written through arrangerClip/setStepSize and stays
    //    null until something writes one.
    private volatile Boolean arrangerClipExists;
    private volatile String arrangerClipTrackName;
    private volatile int arrangerClipPlayingStep = -1;
    private volatile Double arrangerClipLoopStart;
    private volatile Double arrangerClipLoopLength;
    private volatile Double arrangerClipPlayStart;
    private volatile Double arrangerClipPlayStop;
    private volatile Double arrangerClipStepSize;
    private volatile float[] arrangerClipColor;

    // Application state
    private volatile String projectName = "";
    private volatile boolean canUndo;
    private volatile boolean canRedo;
    private volatile boolean hasActiveEngine;
    private volatile String panelLayout = "";

    // Project state
    private volatile boolean hasSoloedTracks;
    private volatile boolean hasMutedTracks;
    private volatile boolean hasArmedTracks;
    private volatile boolean isModified;
    private volatile double cueVolume;
    private volatile double cueMix;

    // Transport — metronome & pre-roll
    private volatile double metronomeVolume;
    private volatile String preRoll = "";

    // Transport — clip launcher settings
    private volatile String defaultLaunchQuantization = "";
    private volatile String clipLauncherPostRecordingAction = "";
    private volatile double clipLauncherPostRecordingTimeOffset;
    private volatile boolean clipLauncherOverdubEnabled;
    private volatile boolean fillModeActive;

    // Arranger visibility state
    private volatile boolean arrangerPlaybackFollow;
    private volatile boolean arrangerClipLauncherVisible;
    private volatile boolean arrangerTimelineVisible;
    private volatile boolean arrangerCueMarkersVisible;
    private volatile boolean arrangerEffectTracksVisible;
    private volatile boolean arrangerIoSectionVisible;
    private volatile boolean arrangerDoubleRowTrackHeight;

    // Arrangement state — loop range
    private volatile boolean arrangerLoopEnabled;
    private volatile double arrangerLoopStart;
    private volatile double arrangerLoopDuration;

    // Arrangement state — punch
    private volatile boolean punchInEnabled;
    private volatile boolean punchOutEnabled;
    private volatile double punchInPosition;
    private volatile double punchOutPosition;

    // Arrangement state — automation
    private volatile String automationWriteMode = "";
    private volatile boolean arrangerAutomationWriteEnabled;
    private volatile boolean clipLauncherAutomationWriteEnabled;
    private volatile boolean automationOverrideActive;

    // Browser state
    //
    // NULL UNTIL OBSERVED (Phase 26, D-26-12 / D-25-15). The fields below that used to start at a
    // Java default ("" / 0 / an empty array) are boxed and start at null, and getBrowserState /
    // getResultBankState publish that null as JSON null. Stage 1 (26-LIVE-STAGE-1.md) read a
    // resultsEntryCount of 0 at open and a deviceType hitCount of 0 in every context, and the
    // record could not tell Bitwig's zero from a default nobody had observed. A null is that
    // distinction. Every reader treats null as unknown.
    private volatile boolean browserExists;
    private volatile String browserTitle = "";
    private volatile String browserSelectedContentType;
    private volatile Integer browserSelectedContentTypeIndex;
    private volatile String[] browserContentTypeNames;
    private volatile boolean browserCanAudition;
    private volatile boolean browserShouldAudition;
    private volatile String browserResultName;
    private volatile boolean browserResultIsSelected;

    // Browser filter state — 8 named columns
    static final int FILTER_COLUMN_COUNT = 8;
    static final String[] FILTER_COLUMN_NAMES = {
        "category", "tag", "creator", "device",
        "deviceType", "fileType", "location", "smartCollection"
    };
    private final boolean[] filterExists = new boolean[FILTER_COLUMN_COUNT];
    private final String[] filterNames = new String[FILTER_COLUMN_COUNT];
    private final Integer[] filterHitCounts = new Integer[FILTER_COLUMN_COUNT];
    private final Integer[] filterEntryCounts = new Integer[FILTER_COLUMN_COUNT];
    // Phase 26 (D-26-12): the column cursor's position flags and the wildcard item's hit count,
    // which separates "this entry matches nothing" from an unobserved hit count (D-5 / S-09).
    private final Boolean[] filterHasNext = new Boolean[FILTER_COLUMN_COUNT];
    private final Boolean[] filterHasPrevious = new Boolean[FILTER_COLUMN_COUNT];
    private final Integer[] filterWildcardHitCounts = new Integer[FILTER_COLUMN_COUNT];
    private CursorBrowserFilterItem[] filterCursors;
    private BrowserFilterColumn[] filterColumns;

    // Browser result bank state
    private static final int RESULT_BANK_SIZE = 8;
    private final String[] resultBankNames = new String[RESULT_BANK_SIZE];
    private final boolean[] resultBankSelected = new boolean[RESULT_BANK_SIZE];
    private volatile Integer resultsEntryCount;
    private BrowserResultsItemBank resultBank;
    // Phase 26 (D-26-14): the result bank's own scroll info, so an end of list is PROVEN by a
    // canScroll flag reading false rather than inferred from eight names not moving.
    private volatile Integer resultBankScrollPosition;
    private volatile Integer resultBankItemCount;
    private volatile Boolean resultBankCanScrollBackwards;
    private volatile Boolean resultBankCanScrollForwards;

    // Master chain (Phase 26, D-26-20): an init-time DeviceBank on the master track, so the
    // master chain's device count and names are readable without moving the master cursor.
    public static final int MASTER_CHAIN_BANK_WIDTH = 16;
    private volatile Integer masterChainDeviceCount;
    private final Boolean[] masterChainExists = new Boolean[MASTER_CHAIN_BANK_WIDTH];
    private final String[] masterChainNames = new String[MASTER_CHAIN_BANK_WIDTH];

    // Cue marker state
    private static final int CUE_MARKER_COUNT = 16;
    private final String[] cueMarkerNames = new String[CUE_MARKER_COUNT];
    private final double[] cueMarkerPositions = new double[CUE_MARKER_COUNT];
    private final float[][] cueMarkerColors = new float[CUE_MARKER_COUNT][3];
    private volatile int cueMarkerScrollPosition;
    private volatile int cueMarkerItemCount;
    private volatile boolean cueMarkerCanScrollForwards;
    private volatile boolean cueMarkerCanScrollBackwards;

    // Arpeggiator state
    private volatile boolean arpEnabled;
    private volatile String arpMode = "up";
    private volatile int arpOctaves;
    private volatile double arpRate;
    private volatile double arpGateLength;
    private volatile boolean arpShuffle;
    private volatile double arpHumanize;
    private volatile boolean arpFreeRunning;
    private volatile boolean arpOverlappingNotes;
    private volatile boolean arpUsePressureToVelocity;
    private volatile boolean arpTerminateNotesImmediately;

    // NoteLatch state
    private volatile boolean noteLatchEnabled;
    private volatile String noteLatchMode = "chord";
    private volatile boolean noteLatchMono;
    private volatile int noteLatchVelocityThreshold;
    private volatile int noteLatchActiveNotes;

    // Groove state
    private volatile boolean grooveEnabled;
    private volatile double grooveShuffleAmount;
    private volatile double grooveShuffleRate;
    private volatile double grooveAccentAmount;
    private volatile double grooveAccentRate;
    private volatile double grooveAccentPhase;

    // Delta detection — previous section hashes
    private int prevGrooveHash;
    private int prevTransportHash;
    private int prevTracksHash;
    private int prevScenesHash;
    private int prevDeviceHash;
    private int prevMasterHash;
    private int prevClipHash;
    private int prevApplicationHash;
    private int prevArrangerHash;
    private int prevArrangementHash;
    private int prevMasterDeviceHash;
    private int prevBrowserHash;
    private int prevMasterChainHash;
    private int prevArpeggiatorHash;
    private int prevNoteLatchHash;

    public void registerObservers(Transport transport, TrackBank trackBank,
                                   MasterTrack masterTrack, Application application,
                                   Project project) {
        // Transport observers
        transport.isPlaying().markInterested();
        transport.isPlaying().addValueObserver((BooleanValueChangedCallback) v -> isPlaying = v);

        transport.isArrangerRecordEnabled().markInterested();
        transport.isArrangerRecordEnabled().addValueObserver((BooleanValueChangedCallback) v -> isRecording = v);

        transport.tempo().value().markInterested();
        transport.tempo().value().addRawValueObserver((DoubleValueChangedCallback) v -> tempo = v);

        transport.playPosition().markInterested();
        transport.playPosition().addValueObserver((DoubleValueChangedCallback) v -> playPosition = v);

        transport.timeSignature().numerator().markInterested();
        transport.timeSignature().numerator().addValueObserver((IntegerValueChangedCallback) v -> timeSignatureNumerator = v);

        transport.timeSignature().denominator().markInterested();
        transport.timeSignature().denominator().addValueObserver((IntegerValueChangedCallback) v -> timeSignatureDenominator = v);

        transport.isArrangerLoopEnabled().markInterested();
        transport.isArrangerLoopEnabled().addValueObserver((BooleanValueChangedCallback) v -> isLoopEnabled = v);

        transport.isMetronomeEnabled().markInterested();
        transport.isMetronomeEnabled().addValueObserver((BooleanValueChangedCallback) v -> isMetronomeEnabled = v);

        // Clip launcher settings
        transport.defaultLaunchQuantization().markInterested();
        transport.defaultLaunchQuantization().addValueObserver((EnumValueChangedCallback) v -> defaultLaunchQuantization = (String) v);

        transport.clipLauncherPostRecordingAction().markInterested();
        transport.clipLauncherPostRecordingAction().addValueObserver((EnumValueChangedCallback) v -> clipLauncherPostRecordingAction = (String) v);

        transport.getClipLauncherPostRecordingTimeOffset().markInterested();
        transport.getClipLauncherPostRecordingTimeOffset().addValueObserver((DoubleValueChangedCallback) v -> clipLauncherPostRecordingTimeOffset = v);

        transport.isClipLauncherOverdubEnabled().markInterested();
        transport.isClipLauncherOverdubEnabled().addValueObserver((BooleanValueChangedCallback) v -> clipLauncherOverdubEnabled = v);

        transport.isFillModeActive().markInterested();
        transport.isFillModeActive().addValueObserver((BooleanValueChangedCallback) v -> fillModeActive = v);

        // Track observers
        for (int i = 0; i < TRACK_COUNT; i++) {
            final int idx = i;
            Track track = (Track) trackBank.getItemAt(i);

            track.exists().markInterested();
            track.exists().addValueObserver((BooleanValueChangedCallback) v -> trackExists[idx] = v);

            track.position().markInterested();
            track.position().addValueObserver((IntegerValueChangedCallback) v -> trackPositions[idx] = v);

            track.isActivated().markInterested();
            track.isActivated().addValueObserver((BooleanValueChangedCallback) v -> trackActivations[idx] = v);

            Track parentTrack = track.createParentTrack(0, 0);
            parentTrack.exists().markInterested();
            parentTrack.exists().addValueObserver((BooleanValueChangedCallback) v -> trackParentExists[idx] = v);
            parentTrack.position().markInterested();
            parentTrack.position().addValueObserver(
                (IntegerValueChangedCallback) v -> trackParentPositions[idx] = v
            );
            for (int candidate = 0; candidate < TRACK_COUNT; candidate++) {
                final int parentCandidate = candidate;
                Track candidateTrack = (Track) trackBank.getItemAt(candidate);
                BooleanValue sameTarget = parentTrack.createEqualsValue(candidateTrack);
                sameTarget.markInterested();
                sameTarget.addValueObserver((BooleanValueChangedCallback) value ->
                    trackParentEquals[idx][parentCandidate] = value);
            }

            track.name().markInterested();
            track.name().addValueObserver((StringValueChangedCallback) v -> trackNames[idx] = (String) v);
            trackNames[i] = "";

            track.volume().value().markInterested();
            track.volume().value().addValueObserver((DoubleValueChangedCallback) v -> trackVolumes[idx] = v);

            track.pan().value().markInterested();
            track.pan().value().addValueObserver((DoubleValueChangedCallback) v -> trackPans[idx] = v);

            track.mute().markInterested();
            track.mute().addValueObserver((BooleanValueChangedCallback) v -> trackMutes[idx] = v);

            track.solo().markInterested();
            track.solo().addValueObserver((BooleanValueChangedCallback) v -> trackSolos[idx] = v);

            track.arm().markInterested();
            track.arm().addValueObserver((BooleanValueChangedCallback) v -> trackArms[idx] = v);

            track.isMutedBySolo().markInterested();
            track.isMutedBySolo().addValueObserver((BooleanValueChangedCallback) v -> trackMutedBySolo[idx] = v);

            track.addVuMeterObserver(128, -1, false, v -> trackVuMeter[idx] = v);

            track.playingNotes().markInterested();
            track.playingNotes().addValueObserver(notes -> {
                int[] packed = new int[notes.length * 2];
                for (int n = 0; n < notes.length; n++) {
                    packed[n * 2] = notes[n].pitch();
                    packed[n * 2 + 1] = notes[n].velocity();
                }
                trackPlayingNotes[idx] = packed;
            });

            track.color().markInterested();
            track.color().addValueObserver((ColorValueChangedCallback) (r, g, b) -> {
                trackColors[idx][0] = r;
                trackColors[idx][1] = g;
                trackColors[idx][2] = b;
            });
        }

        // Master track observers
        masterTrack.volume().value().markInterested();
        masterTrack.volume().value().addValueObserver((DoubleValueChangedCallback) v -> masterVolume = v);

        masterTrack.pan().value().markInterested();
        masterTrack.pan().value().addValueObserver((DoubleValueChangedCallback) v -> masterPan = v);

        masterTrack.mute().markInterested();
        masterTrack.mute().addValueObserver((BooleanValueChangedCallback) v -> masterMute = v);

        masterTrack.solo().markInterested();
        masterTrack.solo().addValueObserver((BooleanValueChangedCallback) v -> masterSolo = v);

        masterTrack.isActivated().markInterested();
        masterTrack.isActivated().addValueObserver(
            (BooleanValueChangedCallback) v -> masterActivated = v
        );

        masterTrack.color().markInterested();
        masterTrack.color().addValueObserver((ColorValueChangedCallback) (r, g, b) -> {
            masterColor[0] = r;
            masterColor[1] = g;
            masterColor[2] = b;
        });

        // Application observers
        application.projectName().markInterested();
        application.projectName().addValueObserver((StringValueChangedCallback) v -> projectName = (String) v);

        application.canUndo().markInterested();
        application.canUndo().addValueObserver((BooleanValueChangedCallback) v -> canUndo = v);

        application.canRedo().markInterested();
        application.canRedo().addValueObserver((BooleanValueChangedCallback) v -> canRedo = v);

        application.hasActiveEngine().markInterested();
        application.hasActiveEngine().addValueObserver((BooleanValueChangedCallback) v -> hasActiveEngine = v);

        application.panelLayout().markInterested();
        application.panelLayout().addValueObserver((StringValueChangedCallback) v -> panelLayout = (String) v);

        // Project observers
        project.hasSoloedTracks().markInterested();
        project.hasSoloedTracks().addValueObserver((BooleanValueChangedCallback) v -> hasSoloedTracks = v);

        project.hasMutedTracks().markInterested();
        project.hasMutedTracks().addValueObserver((BooleanValueChangedCallback) v -> hasMutedTracks = v);

        project.hasArmedTracks().markInterested();
        project.hasArmedTracks().addValueObserver((BooleanValueChangedCallback) v -> hasArmedTracks = v);

        project.isModified().markInterested();
        project.isModified().addValueObserver((BooleanValueChangedCallback) v -> isModified = v);

        project.cueVolume().markInterested();
        project.cueVolume().value().addValueObserver((DoubleValueChangedCallback) v -> cueVolume = v);

        project.cueMix().markInterested();
        project.cueMix().value().addValueObserver((DoubleValueChangedCallback) v -> cueMix = v);

        // Transport — metronome volume & pre-roll
        transport.metronomeVolume().markInterested();
        transport.metronomeVolume().addRawValueObserver((DoubleValueChangedCallback) v -> metronomeVolume = v);

        transport.preRoll().markInterested();
        transport.preRoll().addValueObserver((EnumValueChangedCallback) v -> preRoll = (String) v);

        // Track bank scroll state
        trackBank.scrollPosition().markInterested();
        trackBank.scrollPosition().addValueObserver((IntegerValueChangedCallback) v -> trackScrollPosition = v);

        trackBank.itemCount().markInterested();
        trackBank.itemCount().addValueObserver((IntegerValueChangedCallback) v -> {
            trackItemCount = v;
            trackItemCountObserved = true;
        });

        trackBank.canScrollForwards().markInterested();
        trackBank.canScrollForwards().addValueObserver((BooleanValueChangedCallback) v -> trackCanScrollForwards = v);

        trackBank.canScrollBackwards().markInterested();
        trackBank.canScrollBackwards().addValueObserver((BooleanValueChangedCallback) v -> trackCanScrollBackwards = v);
    }

    public void registerClipObservers(TrackBank trackBank) {
        for (int i = 0; i < TRACK_COUNT; i++) {
            final int trackIdx = i;
            Track track = (Track) trackBank.getItemAt(i);
            ClipLauncherSlotBank slotBank = track.clipLauncherSlotBank();

            // Bank-level indexed observers
            // The observed flag is set in the SAME lambda that assigns the value, so the two
            // cannot drift: anything that reports a has-content value has, by definition,
            // observed that slot.
            slotBank.addHasContentObserver((IndexedBooleanValueChangedCallback) (slotIndex, value) -> {
                clipObservationSeq[trackIdx][slotIndex] = ++observationTick;
                clipHasContent[trackIdx][slotIndex] = value;
                clipHasContentObserved[trackIdx][slotIndex] = true;
            });

            slotBank.addIsPlayingObserver((IndexedBooleanValueChangedCallback) (slotIndex, value) ->
                clipIsPlaying[trackIdx][slotIndex] = value);

            slotBank.addIsRecordingObserver((IndexedBooleanValueChangedCallback) (slotIndex, value) ->
                clipIsRecording[trackIdx][slotIndex] = value);

            // The witness an identity proof is taken through: this slot's OWN published name,
            // reported by the launcher rather than by the cursor. The sequence is raised BEFORE
            // the value it dates, in both lambdas, so that no reader can see a value newer than
            // the number licensing it; on this one thread the two statements are indivisible
            // anyway.
            slotBank.addNameObserver((IndexedStringValueChangedCallback) (slotIndex, value) -> {
                clipObservationSeq[trackIdx][slotIndex] = ++observationTick;
                clipNames[trackIdx][slotIndex] = value;
            });

            // Playback state observer for queued states
            slotBank.addPlaybackStateObserver((ClipLauncherSlotBankPlaybackStateChangedCallback)
                (slotIndex, playbackState, isQueued) -> {
                    clipIsPlaybackQueued[trackIdx][slotIndex] = (playbackState == 1 && isQueued);
                    clipIsRecordingQueued[trackIdx][slotIndex] = (playbackState == 2 && isQueued);
                    clipIsStopQueued[trackIdx][slotIndex] = (playbackState == 0 && isQueued);
                });

            // Per-slot color observers
            for (int j = 0; j < SCENE_COUNT; j++) {
                final int slotIdx = j;
                ClipLauncherSlot slot = (ClipLauncherSlot) slotBank.getItemAt(j);
                slot.color().markInterested();
                slot.color().addValueObserver((ColorValueChangedCallback) (r, g, b) -> {
                    clipColors[trackIdx][slotIdx][0] = r;
                    clipColors[trackIdx][slotIdx][1] = g;
                    clipColors[trackIdx][slotIdx][2] = b;
                });
            }
        }

        // Scene observers
        SceneBank sceneBank = trackBank.sceneBank();
        sceneBank.scrollPosition().markInterested();
        sceneBank.scrollPosition().addValueObserver((IntegerValueChangedCallback) v -> sceneBankOffset = v);

        sceneBank.itemCount().markInterested();
        sceneBank.itemCount().addValueObserver((IntegerValueChangedCallback) v -> sceneItemCount = v);

        sceneBank.canScrollForwards().markInterested();
        sceneBank.canScrollForwards().addValueObserver((BooleanValueChangedCallback) v -> sceneCanScrollForwards = v);

        sceneBank.canScrollBackwards().markInterested();
        sceneBank.canScrollBackwards().addValueObserver((BooleanValueChangedCallback) v -> sceneCanScrollBackwards = v);

        for (int i = 0; i < SCENE_COUNT; i++) {
            final int sceneIdx = i;
            Scene scene = sceneBank.getScene(sceneIdx);
            sceneNames[sceneIdx] = "";
            scene.name().markInterested();
            scene.name().addValueObserver((StringValueChangedCallback) v -> sceneNames[sceneIdx] = (String) v);
            scene.clipCount().markInterested();
            scene.clipCount().addValueObserver((IntegerValueChangedCallback) v -> sceneClipCounts[sceneIdx] = v);
            scene.color().markInterested();
            scene.color().addValueObserver((ColorValueChangedCallback) (r, g, b) -> {
                sceneColors[sceneIdx][0] = r;
                sceneColors[sceneIdx][1] = g;
                sceneColors[sceneIdx][2] = b;
            });
        }
    }

    public void registerDeviceObservers(CursorTrack cursorTrack, CursorDevice cursorDevice,
                                         CursorRemoteControlsPage remoteControlsPage) {
        // Initialize param arrays
        for (int i = 0; i < PARAM_COUNT; i++) {
            paramNames[i] = "";
            paramDisplayedValues[i] = "";
        }

        // Cursor track name
        cursorTrack.name().markInterested();
        cursorTrack.name().addValueObserver((StringValueChangedCallback) v -> cursorTrackName = (String) v);

        // Device properties
        cursorDevice.name().markInterested();
        cursorDevice.name().addValueObserver((StringValueChangedCallback) v -> deviceName = (String) v);

        cursorDevice.isEnabled().markInterested();
        cursorDevice.isEnabled().addValueObserver((BooleanValueChangedCallback) v -> deviceEnabled = v);

        cursorDevice.isPlugin().markInterested();
        cursorDevice.isPlugin().addValueObserver((BooleanValueChangedCallback) v -> deviceIsPlugin = v);

        cursorDevice.position().markInterested();
        cursorDevice.position().addValueObserver((IntegerValueChangedCallback) v -> devicePosition = v);

        cursorDevice.presetName().markInterested();
        cursorDevice.presetName().addValueObserver((StringValueChangedCallback) v -> presetName = (String) v);

        cursorDevice.presetCategory().markInterested();
        cursorDevice.presetCategory().addValueObserver((StringValueChangedCallback) v -> presetCategory = (String) v);

        cursorDevice.presetCreator().markInterested();
        cursorDevice.presetCreator().addValueObserver((StringValueChangedCallback) v -> presetCreator = (String) v);

        cursorDevice.isWindowOpen().markInterested();
        cursorDevice.isWindowOpen().addValueObserver((BooleanValueChangedCallback) v -> isWindowOpen = v);

        cursorDevice.isExpanded().markInterested();
        cursorDevice.isExpanded().addValueObserver((BooleanValueChangedCallback) v -> isExpanded = v);

        // Device nesting state
        cursorDevice.isNested().markInterested();
        cursorDevice.isNested().addValueObserver((BooleanValueChangedCallback) v -> deviceIsNested = v);

        cursorDevice.hasSlots().markInterested();
        cursorDevice.hasSlots().addValueObserver((BooleanValueChangedCallback) v -> deviceHasSlots = v);

        cursorDevice.slotNames().markInterested();
        cursorDevice.slotNames().addValueObserver((StringArrayValueChangedCallback) v -> deviceSlotNames = (String[]) v);

        cursorDevice.hasLayers().markInterested();
        cursorDevice.hasLayers().addValueObserver((BooleanValueChangedCallback) v -> deviceHasLayers = v);

        cursorDevice.hasDrumPads().markInterested();
        cursorDevice.hasDrumPads().addValueObserver((BooleanValueChangedCallback) v -> deviceHasDrumPads = v);

        // Remote controls page state
        remoteControlsPage.selectedPageIndex().markInterested();
        remoteControlsPage.selectedPageIndex().addValueObserver((IntegerValueChangedCallback) v -> pageIndex = v);

        remoteControlsPage.pageCount().markInterested();
        remoteControlsPage.pageCount().addValueObserver((IntegerValueChangedCallback) v -> pageCount = v);

        remoteControlsPage.pageNames().markInterested();
        remoteControlsPage.pageNames().addValueObserver((StringArrayValueChangedCallback) v -> devicePageNames = (String[]) v);

        // Per-parameter observers
        for (int i = 0; i < PARAM_COUNT; i++) {
            final int idx = i;
            RemoteControl param = remoteControlsPage.getParameter(i);

            param.name().markInterested();
            param.name().addValueObserver((StringValueChangedCallback) v -> paramNames[idx] = (String) v);

            param.value().markInterested();
            param.value().addValueObserver((DoubleValueChangedCallback) v -> paramValues[idx] = v);

            param.value().displayedValue().markInterested();
            param.value().displayedValue().addValueObserver((StringValueChangedCallback) v -> paramDisplayedValues[idx] = (String) v);

            param.hasAutomation().markInterested();
            param.hasAutomation().addValueObserver((BooleanValueChangedCallback) v -> paramHasAutomation[idx] = v);

            param.modulatedValue().markInterested();
            param.modulatedValue().addValueObserver((DoubleValueChangedCallback) v -> paramModulatedValues[idx] = v);

            param.isBeingMapped().markInterested();
            param.isBeingMapped().addValueObserver((BooleanValueChangedCallback) v -> paramIsBeingMapped[idx] = v);
        }
    }

    public void registerClipCursorObservers(Clip cursorClip, CursorTrack cursorTrack) {
        // Cursor track name (reuse existing field)
        cursorTrack.name().addValueObserver((StringValueChangedCallback) v -> clipTrackName = (String) v);

        // Where this cursor clip IS -- the absolute (track, scene) it currently sits on.
        //
        // Same shape as registerArrangerClipCursorObservers' getTrack().name() below, one clip
        // object over: Clip#getTrack() (API v1) -> Track#position() (API v2), and
        // Clip#clipLauncherSlot() (API v10) -> ClipLauncherSlotOrScene#sceneIndex() (API v2).
        // Nothing observed these before; MacroHandler's verify-before-write is their only reader.
        //
        // They are subject to the same ~flush-cycle latency as every other observer here -- that
        // latency IS the wrong-slot bug -- which is why the reader re-polls to a ceiling rather
        // than trusting one read.
        Track cursorClipTrack = cursorClip.getTrack();
        cursorClipTrack.position().markInterested();
        cursorClipTrack.position().addValueObserver((IntegerValueChangedCallback) v -> clipCursorTrackPosition = v);

        cursorClip.clipLauncherSlot().sceneIndex().markInterested();
        cursorClip.clipLauncherSlot().sceneIndex().addValueObserver((IntegerValueChangedCallback) v -> clipCursorSceneIndex = v);

        // Playing step
        cursorClip.playingStep().markInterested();
        cursorClip.playingStep().addValueObserver((IntegerValueChangedCallback) v -> clipPlayingStep = v);

        // Loop/play boundaries
        cursorClip.getLoopLength().markInterested();
        cursorClip.getLoopLength().addValueObserver((DoubleValueChangedCallback) v -> clipLoopLength = v);

        cursorClip.getPlayStart().markInterested();
        cursorClip.getPlayStart().addValueObserver((DoubleValueChangedCallback) v -> clipPlayStart = v);

        cursorClip.getPlayStop().markInterested();
        cursorClip.getPlayStop().addValueObserver((DoubleValueChangedCallback) v -> clipPlayStop = v);

        // Step data observer for hasContent detection
        cursorClip.addStepDataObserver((StepDataChangedCallback) (x, y, state) -> {
            // state: 0=empty, 2=noteOn — any noteOn means clip has notes
            if (state == 2) {
                clipHasNotes = true;
            }
        });

        // Clip launch settings
        cursorClip.launchQuantization().markInterested();
        cursorClip.launchQuantization().addValueObserver((EnumValueChangedCallback) v -> clipLaunchQuantization = (String) v);

        cursorClip.launchMode().markInterested();
        cursorClip.launchMode().addValueObserver((EnumValueChangedCallback) v -> clipLaunchMode = (String) v);

        cursorClip.getShuffle().markInterested();
        cursorClip.getShuffle().addValueObserver((BooleanValueChangedCallback) v -> clipShuffle = v);

        cursorClip.getAccent().markInterested();
        cursorClip.getAccent().addRawValueObserver((DoubleValueChangedCallback) v -> clipAccent = v);

        cursorClip.useLoopStartAsQuantizationReference().markInterested();
        cursorClip.useLoopStartAsQuantizationReference().addValueObserver((BooleanValueChangedCallback) v -> clipUseLoopStartAsQuantizationReference = v);

        // Loop enabled and loop start
        cursorClip.isLoopEnabled().markInterested();
        cursorClip.isLoopEnabled().addValueObserver((BooleanValueChangedCallback) v -> clipLoopEnabled = v);

        cursorClip.getLoopStart().markInterested();
        cursorClip.getLoopStart().addValueObserver((DoubleValueChangedCallback) v -> clipLoopStart = v);

        // Clip color
        cursorClip.color().markInterested();
        cursorClip.color().addValueObserver((ColorValueChangedCallback) (r, g, b) -> {
            clipColor[0] = r;
            clipColor[1] = g;
            clipColor[2] = b;
        });
    }

    /**
     * Arranger cursor clip observers (D-06).
     *
     * <p>Takes ONE parameter, deliberately, unlike
     * {@link #registerClipCursorObservers(Clip, CursorTrack)}. The arranger cursor clip is
     * created from the ControllerHost -- by the arranger cursor-clip factory called once in
     * {@code SecondoExtension.init()} -- and not from a track, so it does not follow the
     * cursor track and there is no CursorTrack to take a name
     * from. The track name comes from {@code Clip.getTrack()} instead, which is also the closest
     * thing to a clip name this surface has: at API v25 the Clip interface's only name member is
     * {@code void setName(String)}, and the launcher's clip names come from the slot bank, which
     * an arranger clip has no equivalent of.
     */
    public void registerArrangerClipCursorObservers(Clip arrangerClip) {
        // Exists first, and it is the load-bearing one: it is what separates "nothing is selected
        // in the arranger" from "the selected clip is empty".
        arrangerClip.exists().markInterested();
        arrangerClip.exists().addValueObserver((BooleanValueChangedCallback) v -> arrangerClipExists = v);

        // The containing track's name -- what the user can confirm against the Bitwig window.
        Track arrangerClipTrack = arrangerClip.getTrack();
        arrangerClipTrack.name().markInterested();
        arrangerClipTrack.name().addValueObserver((StringValueChangedCallback) v -> arrangerClipTrackName = (String) v);

        // Loop/play boundaries
        arrangerClip.getLoopStart().markInterested();
        arrangerClip.getLoopStart().addValueObserver((DoubleValueChangedCallback) v -> arrangerClipLoopStart = v);

        arrangerClip.getLoopLength().markInterested();
        arrangerClip.getLoopLength().addValueObserver((DoubleValueChangedCallback) v -> arrangerClipLoopLength = v);

        arrangerClip.getPlayStart().markInterested();
        arrangerClip.getPlayStart().addValueObserver((DoubleValueChangedCallback) v -> arrangerClipPlayStart = v);

        arrangerClip.getPlayStop().markInterested();
        arrangerClip.getPlayStop().addValueObserver((DoubleValueChangedCallback) v -> arrangerClipPlayStop = v);

        // Playing step -- the modern IntegerValue form, not the deprecated addPlayingStepObserver.
        arrangerClip.playingStep().markInterested();
        arrangerClip.playingStep().addValueObserver((IntegerValueChangedCallback) v -> arrangerClipPlayingStep = v);

        // Clip color -- the modern SettableColorValue form, not the deprecated addColorObserver.
        // The array is replaced rather than mutated in place so that "no observer has fired yet"
        // stays representable as null instead of collapsing into an indistinguishable black.
        arrangerClip.color().markInterested();
        arrangerClip.color().addValueObserver((ColorValueChangedCallback) (r, g, b) ->
            arrangerClipColor = new float[] {r, g, b});

        // NO step-data observer here, and no hasContent field. See the field block above.
    }

    public void registerArrangerObservers(Arranger arranger) {
        arranger.isPlaybackFollowEnabled().markInterested();
        arranger.isPlaybackFollowEnabled().addValueObserver((BooleanValueChangedCallback) v -> arrangerPlaybackFollow = v);

        arranger.isClipLauncherVisible().markInterested();
        arranger.isClipLauncherVisible().addValueObserver((BooleanValueChangedCallback) v -> arrangerClipLauncherVisible = v);

        arranger.isTimelineVisible().markInterested();
        arranger.isTimelineVisible().addValueObserver((BooleanValueChangedCallback) v -> arrangerTimelineVisible = v);

        arranger.areCueMarkersVisible().markInterested();
        arranger.areCueMarkersVisible().addValueObserver((BooleanValueChangedCallback) v -> arrangerCueMarkersVisible = v);

        arranger.areEffectTracksVisible().markInterested();
        arranger.areEffectTracksVisible().addValueObserver((BooleanValueChangedCallback) v -> arrangerEffectTracksVisible = v);

        arranger.isIoSectionVisible().markInterested();
        arranger.isIoSectionVisible().addValueObserver((BooleanValueChangedCallback) v -> arrangerIoSectionVisible = v);

        arranger.hasDoubleRowTrackHeight().markInterested();
        arranger.hasDoubleRowTrackHeight().addValueObserver((BooleanValueChangedCallback) v -> arrangerDoubleRowTrackHeight = v);
    }

    public void registerArrangementObservers(Transport transport, CueMarkerBank cueMarkerBank) {
        // Loop range
        transport.isArrangerLoopEnabled().markInterested();
        transport.isArrangerLoopEnabled().addValueObserver((BooleanValueChangedCallback) v -> arrangerLoopEnabled = v);

        transport.arrangerLoopStart().markInterested();
        transport.arrangerLoopStart().addValueObserver((DoubleValueChangedCallback) v -> arrangerLoopStart = v);

        transport.arrangerLoopDuration().markInterested();
        transport.arrangerLoopDuration().addValueObserver((DoubleValueChangedCallback) v -> arrangerLoopDuration = v);

        // Punch
        transport.isPunchInEnabled().markInterested();
        transport.isPunchInEnabled().addValueObserver((BooleanValueChangedCallback) v -> punchInEnabled = v);

        transport.isPunchOutEnabled().markInterested();
        transport.isPunchOutEnabled().addValueObserver((BooleanValueChangedCallback) v -> punchOutEnabled = v);

        transport.getInPosition().markInterested();
        transport.getInPosition().addValueObserver((DoubleValueChangedCallback) v -> punchInPosition = v);

        transport.getOutPosition().markInterested();
        transport.getOutPosition().addValueObserver((DoubleValueChangedCallback) v -> punchOutPosition = v);

        // Automation
        transport.automationWriteMode().markInterested();
        transport.automationWriteMode().addValueObserver((EnumValueChangedCallback) v -> automationWriteMode = (String) v);

        transport.isArrangerAutomationWriteEnabled().markInterested();
        transport.isArrangerAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback) v -> arrangerAutomationWriteEnabled = v);

        transport.isClipLauncherAutomationWriteEnabled().markInterested();
        transport.isClipLauncherAutomationWriteEnabled().addValueObserver((BooleanValueChangedCallback) v -> clipLauncherAutomationWriteEnabled = v);

        transport.isAutomationOverrideActive().markInterested();
        transport.isAutomationOverrideActive().addValueObserver((BooleanValueChangedCallback) v -> automationOverrideActive = v);

        // Cue marker bank scroll state
        cueMarkerBank.scrollPosition().markInterested();
        cueMarkerBank.scrollPosition().addValueObserver((IntegerValueChangedCallback) v -> cueMarkerScrollPosition = v);

        cueMarkerBank.itemCount().markInterested();
        cueMarkerBank.itemCount().addValueObserver((IntegerValueChangedCallback) v -> cueMarkerItemCount = v);

        cueMarkerBank.canScrollForwards().markInterested();
        cueMarkerBank.canScrollForwards().addValueObserver((BooleanValueChangedCallback) v -> cueMarkerCanScrollForwards = v);

        cueMarkerBank.canScrollBackwards().markInterested();
        cueMarkerBank.canScrollBackwards().addValueObserver((BooleanValueChangedCallback) v -> cueMarkerCanScrollBackwards = v);

        // Cue markers
        for (int i = 0; i < CUE_MARKER_COUNT; i++) {
            final int idx = i;
            CueMarker marker = (CueMarker) cueMarkerBank.getItemAt(idx);
            cueMarkerNames[idx] = "";

            marker.exists().markInterested();

            marker.name().markInterested();
            marker.name().addValueObserver((StringValueChangedCallback) v -> cueMarkerNames[idx] = (String) v);

            marker.position().markInterested();
            marker.position().addValueObserver((DoubleValueChangedCallback) v -> cueMarkerPositions[idx] = v);

            marker.getColor().markInterested();
            marker.getColor().addValueObserver((ColorValueChangedCallback) (r, g, b) -> {
                cueMarkerColors[idx][0] = r;
                cueMarkerColors[idx][1] = g;
                cueMarkerColors[idx][2] = b;
            });
        }
    }

    public void registerSendObservers(TrackBank trackBank, int numSends) {
        this.sendCount = numSends;
        for (int i = 0; i < TRACK_COUNT; i++) {
            final int trackIdx = i;
            Track track = (Track) trackBank.getItemAt(i);
            SendBank bank = track.sendBank();
            for (int s = 0; s < numSends; s++) {
                final int sendIdx = s;
                Send send = (Send) bank.getItemAt(s);
                sendNames[trackIdx][sendIdx] = "";

                send.name().markInterested();
                send.name().addValueObserver((StringValueChangedCallback) v -> sendNames[trackIdx][sendIdx] = (String) v);

                send.value().markInterested();
                send.value().addValueObserver((DoubleValueChangedCallback) v -> sendLevels[trackIdx][sendIdx] = v);

                send.isPreFader().markInterested();
                send.isPreFader().addValueObserver((BooleanValueChangedCallback) v -> sendIsPreFader[trackIdx][sendIdx] = v);

                send.isEnabled().markInterested();
                send.isEnabled().addValueObserver((BooleanValueChangedCallback) v -> sendEnabled[trackIdx][sendIdx] = v);

                send.sendChannelColor().markInterested();
                send.sendChannelColor().addValueObserver((ColorValueChangedCallback) (r, g, b) -> {
                    sendColors[trackIdx][sendIdx][0] = r;
                    sendColors[trackIdx][sendIdx][1] = g;
                    sendColors[trackIdx][sendIdx][2] = b;
                });
            }
        }
    }

    public void registerMixerObservers(TrackBank trackBank) {
        for (int i = 0; i < TRACK_COUNT; i++) {
            final int idx = i;
            Track track = (Track) trackBank.getItemAt(i);
            trackCrossfadeModes[idx] = "";
            trackMonitorModes[idx] = "";

            track.crossFadeMode().markInterested();
            track.crossFadeMode().addValueObserver((EnumValueChangedCallback) v -> trackCrossfadeModes[idx] = (String) v);

            track.monitorMode().markInterested();
            track.monitorMode().addValueObserver((EnumValueChangedCallback) v -> trackMonitorModes[idx] = (String) v);
        }
    }

    public void registerGroupObservers(TrackBank trackBank) {
        for (int i = 0; i < TRACK_COUNT; i++) {
            final int idx = i;
            Track track = (Track) trackBank.getItemAt(i);
            trackTypes[idx] = "";

            track.trackType().markInterested();
            track.trackType().addValueObserver((StringValueChangedCallback) v -> trackTypes[idx] = (String) v);

            track.isGroup().markInterested();
            track.isGroup().addValueObserver((BooleanValueChangedCallback) v -> trackIsGroup[idx] = v);

            track.isGroupExpanded().markInterested();
            track.isGroupExpanded().addValueObserver((BooleanValueChangedCallback) v -> trackIsGroupExpanded[idx] = v);

            track.canHoldNoteData().markInterested();
            track.canHoldNoteData().addValueObserver(v -> trackCanHoldNoteData[idx] = v);
            track.canHoldAudioData().markInterested();
            track.canHoldAudioData().addValueObserver(v -> trackCanHoldAudioData[idx] = v);
        }
    }

    public void registerMasterDeviceObservers(CursorDevice masterCursorDevice,
                                               CursorRemoteControlsPage masterRemoteControlsPage) {
        // Initialize param arrays
        for (int i = 0; i < PARAM_COUNT; i++) {
            masterParamNames[i] = "";
            masterParamDisplayedValues[i] = "";
        }

        // Device properties
        masterCursorDevice.name().markInterested();
        masterCursorDevice.name().addValueObserver((StringValueChangedCallback) v -> masterDeviceName = (String) v);

        masterCursorDevice.isEnabled().markInterested();
        masterCursorDevice.isEnabled().addValueObserver((BooleanValueChangedCallback) v -> masterDeviceEnabled = v);

        masterCursorDevice.isPlugin().markInterested();
        masterCursorDevice.isPlugin().addValueObserver((BooleanValueChangedCallback) v -> masterDeviceIsPlugin = v);

        masterCursorDevice.position().markInterested();
        masterCursorDevice.position().addValueObserver((IntegerValueChangedCallback) v -> masterDevicePosition = v);

        masterCursorDevice.presetName().markInterested();
        masterCursorDevice.presetName().addValueObserver((StringValueChangedCallback) v -> masterPresetName = (String) v);

        masterCursorDevice.presetCategory().markInterested();
        masterCursorDevice.presetCategory().addValueObserver((StringValueChangedCallback) v -> masterPresetCategory = (String) v);

        masterCursorDevice.presetCreator().markInterested();
        masterCursorDevice.presetCreator().addValueObserver((StringValueChangedCallback) v -> masterPresetCreator = (String) v);

        // Master device nesting state
        masterCursorDevice.isNested().markInterested();
        masterCursorDevice.isNested().addValueObserver((BooleanValueChangedCallback) v -> masterDeviceIsNested = v);

        masterCursorDevice.hasSlots().markInterested();
        masterCursorDevice.hasSlots().addValueObserver((BooleanValueChangedCallback) v -> masterDeviceHasSlots = v);

        masterCursorDevice.slotNames().markInterested();
        masterCursorDevice.slotNames().addValueObserver((StringArrayValueChangedCallback) v -> masterDeviceSlotNames = (String[]) v);

        masterCursorDevice.hasLayers().markInterested();
        masterCursorDevice.hasLayers().addValueObserver((BooleanValueChangedCallback) v -> masterDeviceHasLayers = v);

        masterCursorDevice.hasDrumPads().markInterested();
        masterCursorDevice.hasDrumPads().addValueObserver((BooleanValueChangedCallback) v -> masterDeviceHasDrumPads = v);

        // Remote controls page state
        masterRemoteControlsPage.selectedPageIndex().markInterested();
        masterRemoteControlsPage.selectedPageIndex().addValueObserver((IntegerValueChangedCallback) v -> masterPageIndex = v);

        masterRemoteControlsPage.pageCount().markInterested();
        masterRemoteControlsPage.pageCount().addValueObserver((IntegerValueChangedCallback) v -> masterPageCount = v);

        masterRemoteControlsPage.pageNames().markInterested();
        masterRemoteControlsPage.pageNames().addValueObserver((StringArrayValueChangedCallback) v -> masterDevicePageNames = (String[]) v);

        // Per-parameter observers
        for (int i = 0; i < PARAM_COUNT; i++) {
            final int idx = i;
            RemoteControl param = masterRemoteControlsPage.getParameter(i);

            param.name().markInterested();
            param.name().addValueObserver((StringValueChangedCallback) v -> masterParamNames[idx] = (String) v);

            param.value().markInterested();
            param.value().addValueObserver((DoubleValueChangedCallback) v -> masterParamValues[idx] = v);

            param.value().displayedValue().markInterested();
            param.value().displayedValue().addValueObserver((StringValueChangedCallback) v -> masterParamDisplayedValues[idx] = (String) v);

            param.modulatedValue().markInterested();
            param.modulatedValue().addValueObserver((DoubleValueChangedCallback) v -> masterParamModulatedValues[idx] = v);

            param.isBeingMapped().markInterested();
            param.isBeingMapped().addValueObserver((BooleanValueChangedCallback) v -> masterParamIsBeingMapped[idx] = v);
        }
    }

    public void registerBrowserObservers(PopupBrowser popupBrowser) {
        popupBrowser.exists().markInterested();
        popupBrowser.exists().addValueObserver((BooleanValueChangedCallback) v -> browserExists = v);

        popupBrowser.title().markInterested();
        popupBrowser.title().addValueObserver((StringValueChangedCallback) v -> browserTitle = (String) v);

        popupBrowser.selectedContentTypeName().markInterested();
        popupBrowser.selectedContentTypeName().addValueObserver((StringValueChangedCallback) v -> browserSelectedContentType = (String) v);

        // Phase 26 (E6, D-26-10): the index read back, so a setContentType step is attributable.
        popupBrowser.selectedContentTypeIndex().markInterested();
        popupBrowser.selectedContentTypeIndex().addValueObserver((IntegerValueChangedCallback) v -> browserSelectedContentTypeIndex = v);

        popupBrowser.contentTypeNames().markInterested();
        popupBrowser.contentTypeNames().addValueObserver((StringArrayValueChangedCallback) v -> browserContentTypeNames = (String[]) v);

        popupBrowser.canAudition().markInterested();
        popupBrowser.canAudition().addValueObserver((BooleanValueChangedCallback) v -> browserCanAudition = v);

        popupBrowser.shouldAudition().markInterested();
        popupBrowser.shouldAudition().addValueObserver((BooleanValueChangedCallback) v -> browserShouldAudition = v);

        // Results entry count
        popupBrowser.resultsColumn().entryCount().markInterested();
        popupBrowser.resultsColumn().entryCount().addValueObserver((IntegerValueChangedCallback) v -> resultsEntryCount = v);

        // Create result item bank (8 items)
        for (int i = 0; i < RESULT_BANK_SIZE; i++) {
            resultBankNames[i] = "";
        }
        resultBank = (BrowserResultsItemBank) popupBrowser.resultsColumn().createItemBank(RESULT_BANK_SIZE);

        // Phase 26 (D-26-14): the bank's scroll info, registered here at initialization only.
        resultBank.scrollPosition().markInterested();
        resultBank.scrollPosition().addValueObserver((IntegerValueChangedCallback) v -> resultBankScrollPosition = v);
        resultBank.itemCount().markInterested();
        resultBank.itemCount().addValueObserver((IntegerValueChangedCallback) v -> resultBankItemCount = v);
        resultBank.canScrollForwards().markInterested();
        resultBank.canScrollForwards().addValueObserver((BooleanValueChangedCallback) v -> resultBankCanScrollForwards = v);
        resultBank.canScrollBackwards().markInterested();
        resultBank.canScrollBackwards().addValueObserver((BooleanValueChangedCallback) v -> resultBankCanScrollBackwards = v);
        for (int i = 0; i < RESULT_BANK_SIZE; i++) {
            final int idx = i;
            BrowserResultsItem item = (BrowserResultsItem) resultBank.getItemAt(i);
            item.name().markInterested();
            item.name().addValueObserver((StringValueChangedCallback) v -> resultBankNames[idx] = (String) v);
            item.isSelected().markInterested();
            item.isSelected().addValueObserver((BooleanValueChangedCallback) v -> resultBankSelected[idx] = v);
        }

        // Create cursor item for result tracking
        BrowserResultsItem resultCursor = popupBrowser.resultsColumn().createCursorItem();
        resultCursor.name().markInterested();
        resultCursor.name().addValueObserver((StringValueChangedCallback) v -> browserResultName = (String) v);

        resultCursor.isSelected().markInterested();
        resultCursor.isSelected().addValueObserver((BooleanValueChangedCallback) v -> browserResultIsSelected = v);
    }

    public void registerFilterObservers(PopupBrowser popupBrowser) {
        // No pre-fill: every per-column value stays null until its observer fires (D-26-12).

        // Map named columns to array indices matching FILTER_COLUMN_NAMES order
        BrowserFilterColumn[] columns = {
            popupBrowser.categoryColumn(),    // 0: category
            popupBrowser.tagColumn(),          // 1: tag
            popupBrowser.creatorColumn(),      // 2: creator
            popupBrowser.deviceColumn(),       // 3: device
            popupBrowser.deviceTypeColumn(),   // 4: deviceType
            popupBrowser.fileTypeColumn(),     // 5: fileType
            popupBrowser.locationColumn(),     // 6: location
            popupBrowser.smartCollectionColumn() // 7: smartCollection
        };
        filterColumns = columns;
        filterCursors = new CursorBrowserFilterItem[FILTER_COLUMN_COUNT];

        for (int i = 0; i < FILTER_COLUMN_COUNT; i++) {
            final int idx = i;
            BrowserFilterColumn col = columns[i];

            col.exists().markInterested();
            col.exists().addValueObserver((BooleanValueChangedCallback) v -> filterExists[idx] = v);

            col.entryCount().markInterested();
            col.entryCount().addValueObserver((IntegerValueChangedCallback) v -> filterEntryCounts[idx] = v);

            CursorBrowserFilterItem cursor = (CursorBrowserFilterItem) col.createCursorItem();
            filterCursors[i] = cursor;

            cursor.name().markInterested();
            cursor.name().addValueObserver((StringValueChangedCallback) v -> filterNames[idx] = (String) v);

            cursor.hitCount().markInterested();
            cursor.hitCount().addValueObserver((IntegerValueChangedCallback) v -> filterHitCounts[idx] = v);

            // Phase 26 (D-26-12, F-17-12 / D-5)
            cursor.hasNext().markInterested();
            cursor.hasNext().addValueObserver((BooleanValueChangedCallback) v -> filterHasNext[idx] = v);

            cursor.hasPrevious().markInterested();
            cursor.hasPrevious().addValueObserver((BooleanValueChangedCallback) v -> filterHasPrevious[idx] = v);

            col.getWildcardItem().hitCount().markInterested();
            col.getWildcardItem().hitCount().addValueObserver((IntegerValueChangedCallback) v -> filterWildcardHitCounts[idx] = v);
        }
    }

    /**
     * Phase 26 (D-26-20). Registered from SecondoExtension.init with the master track's init-time
     * DeviceBank of MASTER_CHAIN_BANK_WIDTH; never from a request (D-25-20). While a popup
     * browser with Live Preview is open, the previewed device is in the chain and is counted.
     */
    public void registerMasterChainObservers(DeviceBank masterDeviceBank) {
        masterDeviceBank.itemCount().markInterested();
        masterDeviceBank.itemCount().addValueObserver((IntegerValueChangedCallback) v -> masterChainDeviceCount = v);

        for (int i = 0; i < MASTER_CHAIN_BANK_WIDTH; i++) {
            final int idx = i;
            Device device = masterDeviceBank.getItemAt(i);

            device.exists().markInterested();
            device.exists().addValueObserver((BooleanValueChangedCallback) v -> masterChainExists[idx] = v);

            device.name().markInterested();
            device.name().addValueObserver((StringValueChangedCallback) v -> masterChainNames[idx] = (String) v);
        }
    }

    /**
     * The session/snapshot masterChain section: {deviceCount:int|null, bankSize:16,
     * deviceNames:[string|null x 16]}. A slot's name is published only when that slot's exists
     * was observed true; otherwise null.
     */
    public JsonObject getMasterChainState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("deviceCount", masterChainDeviceCount);
        obj.addProperty("bankSize", MASTER_CHAIN_BANK_WIDTH);
        JsonArray names = new JsonArray();
        for (int i = 0; i < MASTER_CHAIN_BANK_WIDTH; i++) {
            if (Boolean.TRUE.equals(masterChainExists[i]) && masterChainNames[i] != null) {
                names.add(masterChainNames[i]);
            } else {
                names.add(JsonNull.INSTANCE);
            }
        }
        obj.add("deviceNames", names);
        return obj;
    }

    public void registerNoteInputObservers(Arpeggiator arpeggiator, NoteLatch noteLatch) {
        // Arpeggiator observers
        arpeggiator.isEnabled().markInterested();
        arpeggiator.isEnabled().addValueObserver((BooleanValueChangedCallback) v -> arpEnabled = v);

        arpeggiator.mode().markInterested();
        arpeggiator.mode().addValueObserver((EnumValueChangedCallback) v -> arpMode = (String) v);

        arpeggiator.octaves().markInterested();
        arpeggiator.octaves().addValueObserver((IntegerValueChangedCallback) v -> arpOctaves = v);

        arpeggiator.rate().markInterested();
        arpeggiator.rate().addValueObserver((DoubleValueChangedCallback) v -> arpRate = v);

        arpeggiator.gateLength().markInterested();
        arpeggiator.gateLength().addValueObserver((DoubleValueChangedCallback) v -> arpGateLength = v);

        arpeggiator.shuffle().markInterested();
        arpeggiator.shuffle().addValueObserver((BooleanValueChangedCallback) v -> arpShuffle = v);

        arpeggiator.humanize().markInterested();
        arpeggiator.humanize().addValueObserver((DoubleValueChangedCallback) v -> arpHumanize = v);

        arpeggiator.isFreeRunning().markInterested();
        arpeggiator.isFreeRunning().addValueObserver((BooleanValueChangedCallback) v -> arpFreeRunning = v);

        arpeggiator.enableOverlappingNotes().markInterested();
        arpeggiator.enableOverlappingNotes().addValueObserver((BooleanValueChangedCallback) v -> arpOverlappingNotes = v);

        arpeggiator.usePressureToVelocity().markInterested();
        arpeggiator.usePressureToVelocity().addValueObserver((BooleanValueChangedCallback) v -> arpUsePressureToVelocity = v);

        arpeggiator.terminateNotesImmediately().markInterested();
        arpeggiator.terminateNotesImmediately().addValueObserver((BooleanValueChangedCallback) v -> arpTerminateNotesImmediately = v);

        // NoteLatch observers
        noteLatch.isEnabled().markInterested();
        noteLatch.isEnabled().addValueObserver((BooleanValueChangedCallback) v -> noteLatchEnabled = v);

        noteLatch.mode().markInterested();
        noteLatch.mode().addValueObserver((EnumValueChangedCallback) v -> noteLatchMode = (String) v);

        noteLatch.mono().markInterested();
        noteLatch.mono().addValueObserver((BooleanValueChangedCallback) v -> noteLatchMono = v);

        noteLatch.velocityThreshold().markInterested();
        noteLatch.velocityThreshold().addValueObserver((IntegerValueChangedCallback) v -> noteLatchVelocityThreshold = v);

        noteLatch.activeNotes().markInterested();
        noteLatch.activeNotes().addValueObserver((IntegerValueChangedCallback) v -> noteLatchActiveNotes = v);
    }

    public void registerGrooveObservers(Groove groove) {
        groove.getEnabled().markInterested();
        groove.getEnabled().value().addValueObserver((DoubleValueChangedCallback) v -> grooveEnabled = v > 0.5);

        groove.getShuffleAmount().markInterested();
        groove.getShuffleAmount().value().addValueObserver((DoubleValueChangedCallback) v -> grooveShuffleAmount = v);

        groove.getShuffleRate().markInterested();
        groove.getShuffleRate().value().addValueObserver((DoubleValueChangedCallback) v -> grooveShuffleRate = v);

        groove.getAccentAmount().markInterested();
        groove.getAccentAmount().value().addValueObserver((DoubleValueChangedCallback) v -> grooveAccentAmount = v);

        groove.getAccentRate().markInterested();
        groove.getAccentRate().value().addValueObserver((DoubleValueChangedCallback) v -> grooveAccentRate = v);

        groove.getAccentPhase().markInterested();
        groove.getAccentPhase().value().addValueObserver((DoubleValueChangedCallback) v -> grooveAccentPhase = v);
    }

    public CursorBrowserFilterItem[] getFilterCursors() {
        return filterCursors;
    }

    public BrowserFilterColumn[] getFilterColumns() {
        return filterColumns;
    }

    public BrowserResultsItemBank getResultBank() {
        return resultBank;
    }

    public JsonObject getResultBankState() {
        JsonObject obj = new JsonObject();
        JsonArray items = new JsonArray();
        for (int i = 0; i < RESULT_BANK_SIZE; i++) {
            JsonObject item = new JsonObject();
            item.addProperty("index", i);
            item.addProperty("name", resultBankNames[i] != null ? resultBankNames[i] : "");
            item.addProperty("isSelected", resultBankSelected[i]);
            items.add(item);
        }
        obj.add("items", items);
        obj.addProperty("entryCount", resultsEntryCount);
        obj.addProperty("bankSize", RESULT_BANK_SIZE);
        obj.addProperty("scrollPosition", resultBankScrollPosition);
        obj.addProperty("itemCount", resultBankItemCount);
        obj.addProperty("canScrollBackwards", resultBankCanScrollBackwards);
        obj.addProperty("canScrollForwards", resultBankCanScrollForwards);
        return obj;
    }

    public double getClipStepSize() {
        return clipStepSize;
    }

    /**
     * The absolute track position the launcher cursor clip is currently observed on, or
     * {@code -1} if the observer has never fired.
     *
     * <p>Callers must treat {@code -1} as "do not write" rather than as track 0. See the field's
     * own comment for why the sentinel is not 0.
     */
    public int getClipCursorTrackPosition() {
        return clipCursorTrackPosition;
    }

    /**
     * The absolute scene index the launcher cursor clip is currently observed on, or {@code -1}
     * if the observer has never fired.
     */
    public int getClipCursorSceneIndex() {
        return clipCursorSceneIndex;
    }

    /** How many launcher writes have been refused this session. */
    public int getWriteClipRefusals() {
        return writeClipRefusals;
    }

    /**
     * Count one refused or failed launcher write, so a session tally survives a console nobody
     * was watching.
     *
     * <p>WHAT WAS NARROWED HERE, AND WHY (Phase 29, plan 29-03, D-29-13). This method used to
     * build a nine-key detail object and publish it beside the counter, in the clip snapshot
     * section, under a key naming the last refusal. That detail is RETIRED. It existed only because at pin {@code 3b53206} a refusal
     * could not travel in the RPC response at all, so the snapshot was the only place a caller
     * could retrieve one after the fact. From Phase 29 the refusal travels in the response for
     * {@code macro/writeClip} — with the caller's own request id, the code, and a {@code data}
     * object that also says what happened to the slot — and a second copy in the snapshot is a
     * second source of truth that can disagree with the first.
     *
     * <p>The COUNTER is deliberately kept rather than retired with it. The four chain macros —
     * {@code macro/buildSection}, {@code macro/buildSong}, {@code macro/setupScenes},
     * {@code macro/writeAutomation} — keep accepted-and-queued semantics by D-29-05: they do not
     * defer, so their refusals reach no response, and this counter is their ONLY machine-readable
     * refusal signal. A before-and-after read of it is what a caller of those four has instead.
     *
     * <p>Both the mismatch case ({@code CURSOR_MISMATCH}) and the write-failure case
     * ({@code NOTE_WRITE_FAILED}) still land here and are counted together: a tally that only
     * counted mismatches would under-report exactly the writes that silently lost notes. The two
     * are told apart by the marked {@code host.errorln} line and, for {@code macro/writeClip}, by
     * the response's own error code.
     */
    public void recordWriteClipRefusal() {
        this.writeClipRefusals = writeClipRefusals + 1;
    }

    public boolean clipHasContent(int trackIndex, int slotIndex) {
        if (!clipSlotInRange(trackIndex, slotIndex)) {
            return false;
        }
        return clipHasContent[trackIndex][slotIndex];
    }

    /**
     * Has the launcher's has-content observer ever reported this slot?
     *
     * <p>This exists because {@link #clipHasContent(int, int)} cannot answer "was this slot
     * empty?" on its own. Its backing array is primitive: before any observer has fired every
     * slot reads as empty, and an out-of-range coordinate reads as empty too. Those two are
     * indistinguishable from a genuinely empty slot, which is harmless while the answer is only
     * reported and dangerous the moment something DELETES on it — {@code MacroHandler}'s
     * undo-on-refusal does, and withholds the delete whenever this returns false (D-29-11).
     *
     * <p>Out of range returns false, using the same guard the value getter uses: a coordinate
     * that cannot be observed has not been observed.
     */
    public boolean clipHasContentObserved(int trackIndex, int slotIndex) {
        if (!clipSlotInRange(trackIndex, slotIndex)) {
            return false;
        }
        return clipHasContentObserved[trackIndex][slotIndex];
    }

    /**
     * Is this (track, slot) pair inside the launcher window this cache models?
     *
     * <p>Public, and separate from the two getters that use it, because a caller deciding whether
     * to DELETE a clip must be able to name the in-range check as its own fact. The alternative —
     * inferring it from a getter that answers false both for "out of range" and for "not
     * observed" — is exactly the conflation this plan exists to remove. It also keeps
     * {@code MacroHandler} from becoming another declaration of the two ceilings; it is a
     * consumer of them, through here.
     */
    public boolean clipSlotInRange(int trackIndex, int slotIndex) {
        return trackIndex >= 0 && trackIndex < TRACK_COUNT
            && slotIndex >= 0 && slotIndex < SCENE_COUNT;
    }

    /**
     * Wire in the one resolver this cache resolves public track indexes through. Called once,
     * from the extension's initialization, where both objects already exist.
     */
    public void setTrackBankManager(TrackBankManager manager) {
        this.trackBankManager = manager;
    }

    /**
     * Resolve a caller's PUBLIC track index to the PHYSICAL bank slot every clip array here is
     * keyed on, or {@code -1} when it cannot be resolved.
     *
     * <p>Minus one is the UNPROVEN answer, and a caller's job is to treat it as unproven rather
     * than as slot zero. It means either that no resolver has been wired in, or that the resolver
     * refused the index as out of range -- a refusal that arrives as an exception from
     * {@code TrackBankManager#canonicalBankSlot} and is caught here rather than allowed to escape
     * into a Bitwig flush, where it would take the whole callback down with it.
     */
    public int resolveCanonicalBankSlot(int publicTrackIndex) {
        TrackBankManager manager = this.trackBankManager;
        if (manager == null) {
            return -1;
        }
        try {
            return manager.canonicalBankSlot(publicTrackIndex);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * The offset between a caller's BANK-WINDOW slot subscript and a PROJECT-ABSOLUTE scene index:
     * add it to the subscript to get the absolute index, which is what every cursor observation
     * here is already in.
     *
     * <p>The value is the scene bank's own observed scroll position, published by the value
     * observer registered on {@code sceneBank.scrollPosition()} in {@link #registerObservers}. It
     * is an offset rather than a nullable reading -- the observer fires at initialization like every
     * other bank observer, and a bank that has never scrolled sits at 0 -- so this accessor has no
     * unproven answer to return.
     */
    public int getSceneBankOffset() {
        return sceneBankOffset;
    }

    /**
     * The PROJECT-ABSOLUTE track position for a caller's PUBLIC track index, or {@code -1} when it
     * cannot be proven.
     *
     * <p>The argument is a public track index -- the caller's own coordinate, master excluded and
     * missing rows closed up. The return value is a track position in the sense the Bitwig API
     * reference defines it ({@code bitwig-api-reference.txt:3147}, "the position of the track
     * within the list of Bitwig Studio tracks"), which is the same space
     * {@link #getClipCursorTrackPosition()} observes in. This is the FORWARD conversion: the public
     * index goes through {@link #resolveCanonicalBankSlot(int)} -- the one canonical resolver --
     * and the resolved PHYSICAL BANK SLOT then subscripts the position array the per-slot
     * {@code track.position()} observers fill. Both address the same flat bank object, so the two
     * steps compose.
     *
     * <p>Minus one is the UNPROVEN answer, and a caller's job is to treat it as unproven rather
     * than as track zero. It is returned in exactly three cases: the canonical resolver could not
     * resolve the public index (no resolver wired in, or the index is out of range); the resolved
     * bank slot falls outside the position array this cache models; and the resolved slot's
     * {@code position()} observer has never fired, which the array represents as an absent entry
     * rather than as a number.
     */
    public int absoluteTrackPositionForPublicIndex(int publicTrackIndex) {
        // The second half is {@link #trackPositionAtBankSlot(int)}, and it is CALLED rather than
        // copied (plan 31-17): the same array read, the same two unproven cases and the same
        // sentinel now have one spelling, so a caller that already holds a bank slot and a caller
        // that holds a public index cannot end up disagreeing about what an absent entry means.
        return trackPositionAtBankSlot(resolveCanonicalBankSlot(publicTrackIndex));
    }

    /**
     * The PROJECT-ABSOLUTE track position observed for a PHYSICAL BANK SLOT -- the flat bank
     * subscript -- or {@code -1} when it cannot be proven.
     *
     * <p>THE ARGUMENT IS A BANK SLOT AND NEVER A PUBLIC INDEX. That is the whole difference
     * between this and {@link #absoluteTrackPositionForPublicIndex(int)}: that one starts from
     * the caller's coordinate and runs it through {@link #resolveCanonicalBankSlot(int)} first,
     * while this one starts where that resolution ENDS. A caller who already holds a bank slot --
     * because it located something in one of the clip arrays, every one of which is keyed that
     * way -- has nothing to resolve, and resolving a bank slot as though it were a public index
     * is the class of defect 29-REVIEW.md WR-02 is about.
     *
     * <p>Minus one is the UNPROVEN answer, and a caller's job is to treat it as unproven rather
     * than as track zero. Two cases produce it, the same two the other accessor's last two cases
     * are: the slot falls outside the position array this cache models, and the slot's
     * {@code position()} observer has never fired, which the array represents as an absent entry
     * rather than as a number.
     */
    public int trackPositionAtBankSlot(int bankSlot) {
        if (bankSlot < 0 || bankSlot >= TRACK_COUNT) {
            return -1;
        }
        Integer position = trackPositions[bankSlot];
        if (position == null) {
            return -1;
        }
        return position;
    }

    /**
     * The name this slot's OWN launcher name observer last published, addressed by PHYSICAL BANK
     * SLOT -- the flat bank subscript -- and never by public track index.
     *
     * <p>Null when the coordinate is out of range, and null when no name observer has ever fired
     * for it. The absence is exposed rather than replaced with an empty string because an empty
     * string is a real name a slot can have, and the two must not share a spelling. The snapshot
     * builder does substitute {@code ""}, deliberately, for a reader that only displays it; a
     * reader that PROVES something on the answer needs the difference.
     */
    public String getClipNameAtBankSlot(int bankSlot, int sceneIndex) {
        if (!clipSlotInRange(bankSlot, sceneIndex)) {
            return null;
        }
        return clipNames[bankSlot][sceneIndex];
    }

    /**
     * How fresh this slot's cached facts are, addressed by PHYSICAL BANK SLOT -- the flat bank
     * subscript -- and never by public track index: the tick at the last observer callback that
     * landed on it, or {@code 0} when none ever has.
     */
    public long getClipObservationSeqAtBankSlot(int bankSlot, int sceneIndex) {
        if (!clipSlotInRange(bankSlot, sceneIndex)) {
            return 0;
        }
        return clipObservationSeq[bankSlot][sceneIndex];
    }

    /**
     * The observation counter as it stands now. It takes no coordinate: it is the number a caller
     * records BEFORE it acts, so that "this slot has been observed since" becomes a comparison
     * against a slot's own entry rather than a guess about staleness.
     */
    public long currentObservationTick() {
        return observationTick;
    }

    /**
     * Every slot's cached name as it stands now, as a copy nothing else holds a reference to,
     * subscripted by PHYSICAL BANK SLOT in its first dimension and scene index in its second.
     *
     * <p>For the caller that has to know what every slot was called a moment ago -- the one that
     * is about to mutate a name and may have to put it back. The copy is defensive in both
     * dimensions: mutating the returned array cannot reach the cache.
     */
    public String[][] snapshotClipNames() {
        String[][] copy = new String[TRACK_COUNT][];
        for (int i = 0; i < TRACK_COUNT; i++) {
            copy[i] = java.util.Arrays.copyOf(clipNames[i], SCENE_COUNT);
        }
        return copy;
    }

    public void setClipStepSize(double stepSize) {
        this.clipStepSize = stepSize;
    }

    /**
     * The last step size written through {@code arrangerClip/setStepSize}, or null if nothing has
     * written one. Null rather than a plausible default because the API publishes no getter for
     * this value: a default here would be a number nobody read presented as one somebody did.
     */
    public Double getArrangerClipStepSize() {
        return arrangerClipStepSize;
    }

    public void setArrangerClipStepSize(double stepSize) {
        this.arrangerClipStepSize = stepSize;
    }

    public JsonObject getClipLaunchSettings() {
        JsonObject obj = new JsonObject();
        obj.addProperty("launchQuantization", clipLaunchQuantization);
        obj.addProperty("launchMode", clipLaunchMode);
        obj.addProperty("shuffle", clipShuffle);
        obj.addProperty("accent", clipAccent);
        obj.addProperty("useLoopStartAsQuantizationReference", clipUseLoopStartAsQuantizationReference);
        return obj;
    }

    public JsonObject getClipPlaybackSettings() {
        JsonObject obj = new JsonObject();
        obj.addProperty("playStart", clipPlayStart);
        obj.addProperty("playStop", clipPlayStop);
        obj.addProperty("loopStart", clipLoopStart);
        obj.addProperty("loopLength", clipLoopLength);
        obj.addProperty("isLoopEnabled", clipLoopEnabled);
        return obj;
    }

    public JsonObject getClipLauncherSettings() {
        JsonObject obj = new JsonObject();
        obj.addProperty("defaultLaunchQuantization", defaultLaunchQuantization);
        obj.addProperty("clipLauncherPostRecordingAction", clipLauncherPostRecordingAction);
        obj.addProperty("clipLauncherPostRecordingTimeOffset", clipLauncherPostRecordingTimeOffset);
        obj.addProperty("clipLauncherOverdubEnabled", clipLauncherOverdubEnabled);
        obj.addProperty("fillModeActive", fillModeActive);
        return obj;
    }

    public int[] getVuMeters() {
        return trackVuMeter.clone();
    }

    public int[] getPlayingNotes(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= TRACK_COUNT) {
            return new int[0];
        }
        return trackPlayingNotes[trackIndex].clone();
    }

    public boolean[] getParamIsBeingMapped() {
        return paramIsBeingMapped.clone();
    }

    public boolean[] getMasterParamIsBeingMapped() {
        return masterParamIsBeingMapped.clone();
    }

    public JsonObject getSnapshot() {
        JsonObject snapshot = new JsonObject();
        snapshot.add("transport", getTransportState());
        snapshot.add("tracks", getTracksState());
        snapshot.add("scenes", getScenesState());
        snapshot.add("device", getDeviceState());
        snapshot.add("clip", getClipState());
        // The second Clip object (D-05): `clip` above means the LAUNCHER cursor clip, this one is
        // the arranger cursor clip. Snapshot only, deliberately -- it is not added to getDelta()
        // because WsRpcServer.VALID_TOPICS is the published subscribe vocabulary and adding an
        // unsubscribable section name to the delta would emit a topic no client can ask for.
        snapshot.add("arrangerClip", getArrangerClipState());
        snapshot.add("master", getMasterState());
        snapshot.add("application", getApplicationState());
        snapshot.add("arranger", getArrangerState());
        snapshot.add("arrangement", getArrangementState());
        snapshot.add("masterDevice", getMasterDeviceState());
        snapshot.add("masterChain", getMasterChainState());
        snapshot.add("browser", getBrowserState());
        snapshot.add("arpeggiator", getArpeggiatorState());
        snapshot.add("noteLatch", getNoteLatchState());
        snapshot.add("groove", getGrooveState());
        return snapshot;
    }

    /**
     * Compare current section hashes against previous flush.
     * Returns a list of section names that changed, or empty if nothing changed.
     * Updates the stored hashes for the next comparison.
     */
    public List<String> getChangedSections() {
        JsonObject delta = getDelta();
        if (delta == null) {
            return List.of();
        }
        List<String> changed = new ArrayList<>();
        for (var entry : delta.getAsJsonObject("data").entrySet()) {
            changed.add(entry.getKey());
        }
        return changed;
    }

    /**
     * Compare current section hashes against previous flush.
     * Returns a JsonObject with "changed" (section names) and "data" (section state)
     * for sections that changed, or null if nothing changed.
     * Updates the stored hashes for the next comparison.
     */
    public JsonObject getDelta() {
        JsonArray changed = new JsonArray();
        JsonObject data = new JsonObject();

        checkSection("transport", getTransportState(), changed, data);
        checkSection("tracks", getTracksState(), changed, data);
        checkSection("scenes", getScenesState(), changed, data);
        checkSection("device", getDeviceState(), changed, data);
        checkSection("clip", getClipState(), changed, data);
        checkSection("master", getMasterState(), changed, data);
        checkSection("application", getApplicationState(), changed, data);
        checkSection("arranger", getArrangerState(), changed, data);
        checkSection("arrangement", getArrangementState(), changed, data);
        checkSection("masterDevice", getMasterDeviceState(), changed, data);
        // In the delta AND in WsRpcServer.VALID_TOPICS, so a subscriber can ask for it (D6-DEF-03).
        checkSection("masterChain", getMasterChainState(), changed, data);
        checkSection("browser", getBrowserState(), changed, data);
        checkSection("arpeggiator", getArpeggiatorState(), changed, data);
        checkSection("noteLatch", getNoteLatchState(), changed, data);
        checkSection("groove", getGrooveState(), changed, data);

        if (changed.isEmpty()) {
            return null;
        }

        JsonObject delta = new JsonObject();
        delta.add("changed", changed);
        delta.add("data", data);
        return delta;
    }

    private void checkSection(String name, JsonObject state, JsonArray changed, JsonObject data) {
        int h = state.toString().hashCode();
        int prev = getSectionHash(name);
        if (h != prev) {
            changed.add(name);
            data.add(name, state);
            setSectionHash(name, h);
        }
    }

    private int getSectionHash(String name) {
        return switch (name) {
            case "transport" -> prevTransportHash;
            case "tracks" -> prevTracksHash;
            case "scenes" -> prevScenesHash;
            case "device" -> prevDeviceHash;
            case "clip" -> prevClipHash;
            case "master" -> prevMasterHash;
            case "application" -> prevApplicationHash;
            case "arranger" -> prevArrangerHash;
            case "arrangement" -> prevArrangementHash;
            case "masterDevice" -> prevMasterDeviceHash;
            case "masterChain" -> prevMasterChainHash;
            case "browser" -> prevBrowserHash;
            case "arpeggiator" -> prevArpeggiatorHash;
            case "noteLatch" -> prevNoteLatchHash;
            case "groove" -> prevGrooveHash;
            default -> 0;
        };
    }

    private void setSectionHash(String name, int hash) {
        switch (name) {
            case "transport" -> prevTransportHash = hash;
            case "tracks" -> prevTracksHash = hash;
            case "scenes" -> prevScenesHash = hash;
            case "device" -> prevDeviceHash = hash;
            case "clip" -> prevClipHash = hash;
            case "master" -> prevMasterHash = hash;
            case "application" -> prevApplicationHash = hash;
            case "arranger" -> prevArrangerHash = hash;
            case "arrangement" -> prevArrangementHash = hash;
            case "masterDevice" -> prevMasterDeviceHash = hash;
            case "masterChain" -> prevMasterChainHash = hash;
            case "browser" -> prevBrowserHash = hash;
            case "arpeggiator" -> prevArpeggiatorHash = hash;
            case "noteLatch" -> prevNoteLatchHash = hash;
            case "groove" -> prevGrooveHash = hash;
        }
    }

    private JsonObject getTransportState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("isPlaying", isPlaying);
        obj.addProperty("isRecording", isRecording);
        obj.addProperty("tempo", tempo);
        obj.addProperty("playPosition", playPosition);
        obj.addProperty("timeSignatureNumerator", timeSignatureNumerator);
        obj.addProperty("timeSignatureDenominator", timeSignatureDenominator);
        obj.addProperty("isLoopEnabled", isLoopEnabled);
        obj.addProperty("isMetronomeEnabled", isMetronomeEnabled);
        obj.addProperty("metronomeVolume", metronomeVolume);
        obj.addProperty("preRoll", preRoll);
        obj.addProperty("defaultLaunchQuantization", defaultLaunchQuantization);
        obj.addProperty("clipLauncherPostRecordingAction", clipLauncherPostRecordingAction);
        obj.addProperty("clipLauncherPostRecordingTimeOffset", clipLauncherPostRecordingTimeOffset);
        obj.addProperty("clipLauncherOverdubEnabled", clipLauncherOverdubEnabled);
        obj.addProperty("fillModeActive", fillModeActive);
        return obj;
    }


    /**
     * Immutable request-time view of one canonical, non-master track row.
     *
     * <p>Observer callbacks update the backing arrays independently. Building records for each
     * request prevents a handler from retaining a mutable array view across Bitwig flushes.</p>
     */
    public record CanonicalTrackRow(
            int trackIndex,
            int bankSlot,
            String name,
            String type,
            Integer parentIndex,
            int depth,
            Boolean activated,
            Boolean effectiveActivated,
            boolean identityRequired,
            String legacyName,
            Integer legacyPosition,
            Integer position,
            List<Integer> unobservedActivationIndices) {
        public CanonicalTrackRow {
            unobservedActivationIndices = List.copyOf(unobservedActivationIndices);
        }
    }

    /** A bounded canonical track snapshot with item-count observation kept explicit. */
    public record CanonicalTrackSnapshot(
            List<CanonicalTrackRow> rows,
            boolean itemCountObserved,
            Integer itemCount,
            int bankSize,
            boolean complete,
            Integer lastReturnedTrackIndex) {
        public CanonicalTrackSnapshot {
            rows = List.copyOf(rows);
        }
    }

    private record ActivationFold(Boolean effective, List<Integer> unobservedIndices) {}

    /**
     * Receives the comparison-bank evidence computed by {@code TrackBankManager}.
     *
     * <p>The evidence is stored against the physical flat-bank slot. The immutable snapshot maps
     * it to the public non-master index, so master placement cannot shift the risk flag onto the
     * wrong row.</p>
     */
    public void updateTrackMigrationObservation(
            int bankSlot,
            boolean identityRequired,
            String legacyName,
            Integer legacyPosition) {
        if (bankSlot < 0 || bankSlot >= TRACK_COUNT) {
            return;
        }
        trackIdentityRequired[bankSlot] = identityRequired;
        legacyTrackNames[bankSlot] = legacyName;
        legacyTrackPositions[bankSlot] = legacyPosition;
    }

    /** Build the complete bounded non-master view in Bitwig's FLATTEN preorder. */
    public CanonicalTrackSnapshot getCanonicalTrackSnapshot() {
        List<Integer> slots = new ArrayList<>(TRACK_COUNT);
        for (int slot = 0; slot < TRACK_COUNT; slot++) {
            if (Boolean.FALSE.equals(trackExists[slot])
                    || "Master".equals(trackTypes[slot])) {
                continue;
            }
            slots.add(slot);
        }

        ParentLink[] parentLinks = new ParentLink[TRACK_COUNT];
        for (int slot : slots) {
            parentLinks[slot] = resolveParentLink(slot, slots);
        }

        Map<Integer, Integer> slotToPublic = new HashMap<>();
        for (int publicIndex = 0; publicIndex < slots.size(); publicIndex++) {
            slotToPublic.put(slots.get(publicIndex), publicIndex);
        }

        List<CanonicalTrackRow> rows = new ArrayList<>(slots.size());
        for (int publicIndex = 0; publicIndex < slots.size(); publicIndex++) {
            int slot = slots.get(publicIndex);
            Integer parentIndex = canonicalParentIndex(
                slot, parentLinks, slotToPublic
            );
            ActivationFold activation = foldEffectiveActivation(
                slot, parentLinks, slotToPublic
            );
            rows.add(new CanonicalTrackRow(
                publicIndex,
                slot,
                trackNames[slot] != null ? trackNames[slot] : "",
                trackTypes[slot] != null ? trackTypes[slot] : "",
                parentIndex,
                canonicalDepth(slot, parentLinks),
                trackActivations[slot],
                activation.effective(),
                trackIdentityRequired[slot],
                legacyTrackNames[slot],
                legacyTrackPositions[slot],
                trackPositions[slot],
                activation.unobservedIndices()
            ));
        }

        boolean hierarchyObserved = slots.stream()
            .allMatch(slot -> parentLinks[slot].observed());
        boolean complete = trackItemCountObserved
            && trackItemCount <= TRACK_COUNT && hierarchyObserved;
        Integer last = rows.isEmpty() ? null : rows.get(rows.size() - 1).trackIndex();
        return new CanonicalTrackSnapshot(
            rows,
            trackItemCountObserved,
            trackItemCountObserved ? trackItemCount : null,
            TRACK_COUNT,
            complete,
            last
        );
    }

    /**
     * Return one row and every recursively linked descendant, retaining flat preorder.
     *
     * <p>Membership follows parent links rather than adjacency or names, so nested descendants
     * remain discoverable even when unrelated rows are interleaved in the observed bank.</p>
     */
    public CanonicalTrackSnapshot getCanonicalTrackSubtree(int rootTrackIndex) {
        CanonicalTrackSnapshot all = getCanonicalTrackSnapshot();
        if (rootTrackIndex < 0 || rootTrackIndex >= all.rows().size()) {
            return new CanonicalTrackSnapshot(
                List.of(),
                all.itemCountObserved(),
                all.itemCount(),
                all.bankSize(),
                all.complete(),
                null
            );
        }

        Map<Integer, CanonicalTrackRow> byIndex = new HashMap<>();
        for (CanonicalTrackRow row : all.rows()) {
            byIndex.put(row.trackIndex(), row);
        }
        List<CanonicalTrackRow> subtree = new ArrayList<>();
        for (CanonicalTrackRow row : all.rows()) {
            if (row.trackIndex() == rootTrackIndex
                    || descendsFrom(row, rootTrackIndex, byIndex)) {
                subtree.add(row);
            }
        }
        Integer last = subtree.isEmpty() ? null : subtree.get(subtree.size() - 1).trackIndex();
        return new CanonicalTrackSnapshot(
            subtree,
            all.itemCountObserved(),
            all.itemCount(),
            all.bankSize(),
            all.complete(),
            last
        );
    }

    private static boolean descendsFrom(
            CanonicalTrackRow row,
            int rootTrackIndex,
            Map<Integer, CanonicalTrackRow> byIndex) {
        Set<Integer> visited = new HashSet<>();
        Integer parent = row.parentIndex();
        while (parent != null && visited.add(parent)) {
            if (parent == rootTrackIndex) {
                return true;
            }
            CanonicalTrackRow parentRow = byIndex.get(parent);
            parent = parentRow == null ? null : parentRow.parentIndex();
        }
        return false;
    }

    private record ParentLink(Integer slot, boolean observed) {}

    /**
     * Bitwig parent position may use a group-local or main-bank coordinate. ObjectProxy equality
     * is the only direct comparison against the flattened canonical row, so never infer a parent
     * from a matching numeric position or name.
     */
    private ParentLink resolveParentLink(int slot, List<Integer> visibleSlots) {
        if (Boolean.FALSE.equals(trackParentExists[slot])) {
            return new ParentLink(null, true);
        }
        if (!Boolean.TRUE.equals(trackParentExists[slot])) {
            return new ParentLink(null, false);
        }
        Integer match = null;
        for (int candidate : visibleSlots) {
            if (Boolean.TRUE.equals(trackParentEquals[slot][candidate])) {
                if (match != null) {
                    return new ParentLink(null, false);
                }
                match = candidate;
            }
        }
        if (match == null) {
            return new ParentLink(null, false);
        }
        // Some root group proxies report their own track as parent. That is not a hierarchy edge.
        return new ParentLink(match.equals(slot) ? null : match, true);
    }

    private Integer canonicalParentIndex(
            int slot,
            ParentLink[] links,
            Map<Integer, Integer> slotToPublic) {
        ParentLink link = links[slot];
        return link == null || link.slot() == null ? null : slotToPublic.get(link.slot());
    }

    private int canonicalDepth(int slot, ParentLink[] links) {
        int depth = 0;
        int current = slot;
        Set<Integer> visited = new HashSet<>();
        while (visited.add(current)) {
            ParentLink link = links[current];
            if (link == null || !link.observed() || link.slot() == null) break;
            depth++;
            current = link.slot();
        }
        return depth;
    }

    private ActivationFold foldEffectiveActivation(
            int slot,
            ParentLink[] links,
            Map<Integer, Integer> slotToPublic) {
        Boolean effective = Boolean.TRUE;
        List<Integer> unobserved = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        int current = slot;
        boolean relationshipUnobserved = false;

        while (true) {
            if (!visited.add(current)) {
                relationshipUnobserved = true;
                break;
            }
            Boolean own = trackActivations[current];
            if (Boolean.FALSE.equals(own)) {
                effective = Boolean.FALSE;
            } else if (own == null) {
                Integer publicIndex = slotToPublic.get(current);
                if (publicIndex != null) {
                    unobserved.add(publicIndex);
                }
                if (!Boolean.FALSE.equals(effective)) {
                    effective = null;
                }
            }

            ParentLink link = links[current];
            if (link == null || !link.observed()) {
                relationshipUnobserved = true;
                break;
            }
            if (link.slot() == null) break;
            current = link.slot();
        }

        if (relationshipUnobserved && !Boolean.FALSE.equals(effective)) {
            effective = null;
        }
        Collections.sort(unobserved);
        return new ActivationFold(effective, List.copyOf(unobserved));
    }

    private JsonObject getTracksState() {
        CanonicalTrackSnapshot snapshot = getCanonicalTrackSnapshot();
        JsonObject obj = new JsonObject();
        obj.addProperty("bankSize", snapshot.bankSize());
        obj.addProperty("scrollPosition", trackScrollPosition);
        if (snapshot.itemCountObserved()) {
            obj.addProperty("itemCount", snapshot.itemCount());
        } else {
            obj.add("itemCount", JsonNull.INSTANCE);
        }
        obj.addProperty("itemCountObserved", snapshot.itemCountObserved());
        obj.addProperty("complete", snapshot.complete());
        obj.addProperty("returnedCount", snapshot.rows().size());
        if (snapshot.lastReturnedTrackIndex() == null) {
            obj.add("lastReturnedPath", JsonNull.INSTANCE);
        } else {
            JsonArray path = new JsonArray();
            JsonObject segment = new JsonObject();
            segment.addProperty("trackIndex", snapshot.lastReturnedTrackIndex());
            path.add(segment);
            obj.add("lastReturnedPath", path);
        }
        obj.addProperty("canScrollBackwards", trackCanScrollBackwards);
        obj.addProperty("canScrollForwards", trackCanScrollForwards);

        JsonArray arr = new JsonArray();
        for (CanonicalTrackRow row : snapshot.rows()) {
            int i = row.bankSlot();
            JsonObject track = new JsonObject();
            // ONE KEY PER FACT (25-REVIEW WR-15). Each row used to carry index AND trackIndex,
            // type AND trackType, trackIdentityRequired AND identityRequired, all three pairs
            // with the same value on both sides: six declarations of three facts, nothing
            // pinning the pairs equal, and a snapshot that session/snapshot now reads on EVERY
            // indexed mutation. The aliases trackIndex, type and identityRequired were removed
            // on 2026-09-16, after plans 25-24 and 25-25 moved every Python and harness reader
            // onto the survivors. uiNumber stays: it is a different fact (the 1-based number the
            // user sees), not an alias of index. track/setActivated's activationResponse keeps
            // its own trackIndex member, which is a RESPONSE field and not a snapshot row.
            track.addProperty("index", row.trackIndex());
            track.addProperty("uiNumber", row.trackIndex() + 1);
            track.addProperty("name", row.name());
            track.addProperty("trackType", row.type());
            if (row.parentIndex() == null) {
                track.add("parentIndex", JsonNull.INSTANCE);
            } else {
                track.addProperty("parentIndex", row.parentIndex());
            }
            track.addProperty("depth", row.depth());
            if (row.activated() == null) {
                track.add("activated", JsonNull.INSTANCE);
            } else {
                track.addProperty("activated", row.activated());
            }
            if (row.effectiveActivated() == null) {
                track.add("effectiveActivated", JsonNull.INSTANCE);
            } else {
                track.addProperty("effectiveActivated", row.effectiveActivated());
            }
            track.addProperty("trackIdentityRequired", row.identityRequired());
            if (row.position() == null) {
                track.add("position", JsonNull.INSTANCE);
            } else {
                track.addProperty("position", row.position());
            }
            if (row.legacyName() == null) {
                track.add("legacyName", JsonNull.INSTANCE);
            } else {
                track.addProperty("legacyName", row.legacyName());
            }
            if (row.legacyPosition() == null) {
                track.add("legacyPosition", JsonNull.INSTANCE);
            } else {
                track.addProperty("legacyPosition", row.legacyPosition());
            }
            JsonArray unobservedActivationIndices = new JsonArray();
            for (Integer unobservedIndex : row.unobservedActivationIndices()) {
                unobservedActivationIndices.add(unobservedIndex);
            }
            track.add("unobservedActivationIndices", unobservedActivationIndices);

            track.addProperty("volume", trackVolumes[i]);
            track.addProperty("pan", trackPans[i]);
            track.addProperty("mute", trackMutes[i]);
            track.addProperty("solo", trackSolos[i]);
            track.addProperty("arm", trackArms[i]);
            track.addProperty("isMutedBySolo", trackMutedBySolo[i]);

            JsonObject color = new JsonObject();
            color.addProperty("r", trackColors[i][0]);
            color.addProperty("g", trackColors[i][1]);
            color.addProperty("b", trackColors[i][2]);
            track.add("color", color);

            track.addProperty("crossfadeMode", trackCrossfadeModes[i] != null ? trackCrossfadeModes[i] : "");
            track.addProperty("monitorMode", trackMonitorModes[i] != null ? trackMonitorModes[i] : "");
            track.addProperty("isGroup", trackIsGroup[i]);
            track.addProperty("isGroupExpanded", trackIsGroupExpanded[i]);
            track.addProperty("canHoldNoteData", trackCanHoldNoteData[i]);
            track.addProperty("canHoldAudioData", trackCanHoldAudioData[i]);

            JsonArray sends = new JsonArray();
            for (int s = 0; s < sendCount; s++) {
                JsonObject send = new JsonObject();
                send.addProperty("index", s);
                send.addProperty("name", sendNames[i][s] != null ? sendNames[i][s] : "");
                send.addProperty("level", sendLevels[i][s]);
                send.addProperty("isPreFader", sendIsPreFader[i][s]);
                send.addProperty("enabled", sendEnabled[i][s]);
                JsonObject sendColor = new JsonObject();
                sendColor.addProperty("r", sendColors[i][s][0]);
                sendColor.addProperty("g", sendColors[i][s][1]);
                sendColor.addProperty("b", sendColors[i][s][2]);
                send.add("color", sendColor);
                sends.add(send);
            }
            track.add("sends", sends);

            JsonArray clips = new JsonArray();
            for (int j = 0; j < SCENE_COUNT; j++) {
                JsonObject clip = new JsonObject();
                clip.addProperty("slotIndex", j);
                clip.addProperty("hasContent", clipHasContent[i][j]);
                clip.addProperty("isPlaying", clipIsPlaying[i][j]);
                clip.addProperty("isRecording", clipIsRecording[i][j]);
                clip.addProperty("isPlaybackQueued", clipIsPlaybackQueued[i][j]);
                clip.addProperty("isRecordingQueued", clipIsRecordingQueued[i][j]);
                clip.addProperty("isStopQueued", clipIsStopQueued[i][j]);
                clip.addProperty("name", clipNames[i][j] != null ? clipNames[i][j] : "");

                JsonObject clipColor = new JsonObject();
                clipColor.addProperty("r", clipColors[i][j][0]);
                clipColor.addProperty("g", clipColors[i][j][1]);
                clipColor.addProperty("b", clipColors[i][j][2]);
                clip.add("color", clipColor);

                clips.add(clip);
            }
            track.add("clips", clips);
            arr.add(track);
        }
        obj.add("tracks", arr);
        return obj;
    }

    private JsonObject getScenesState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("bankSize", SCENE_COUNT);
        obj.addProperty("scrollPosition", sceneBankOffset);
        obj.addProperty("itemCount", sceneItemCount);
        obj.addProperty("canScrollBackwards", sceneCanScrollBackwards);
        obj.addProperty("canScrollForwards", sceneCanScrollForwards);
        JsonArray scenes = new JsonArray();
        for (int i = 0; i < SCENE_COUNT; i++) {
            JsonObject scene = new JsonObject();
            scene.addProperty("index", i);
            scene.addProperty("name", sceneNames[i] != null ? sceneNames[i] : "");
            scene.addProperty("clipCount", sceneClipCounts[i]);
            JsonObject color = new JsonObject();
            color.addProperty("r", sceneColors[i][0]);
            color.addProperty("g", sceneColors[i][1]);
            color.addProperty("b", sceneColors[i][2]);
            scene.add("color", color);
            scenes.add(scene);
        }
        obj.add("scenes", scenes);
        return obj;
    }

    private JsonObject getDeviceState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("cursorTrackName", cursorTrackName);
        obj.addProperty("name", deviceName);
        obj.addProperty("isEnabled", deviceEnabled);
        obj.addProperty("isPlugin", deviceIsPlugin);
        obj.addProperty("position", devicePosition);
        obj.addProperty("presetName", presetName);
        obj.addProperty("presetCategory", presetCategory);
        obj.addProperty("presetCreator", presetCreator);
        obj.addProperty("isWindowOpen", isWindowOpen);
        obj.addProperty("isExpanded", isExpanded);
        obj.addProperty("isNested", deviceIsNested);
        obj.addProperty("hasSlots", deviceHasSlots);
        JsonArray slotNamesArr = new JsonArray();
        String[] slots = deviceSlotNames;
        if (slots != null) {
            for (String s : slots) {
                slotNamesArr.add(s != null ? s : "");
            }
        }
        obj.add("slotNames", slotNamesArr);
        obj.addProperty("hasLayers", deviceHasLayers);
        obj.addProperty("hasDrumPads", deviceHasDrumPads);

        JsonObject remoteControls = new JsonObject();
        remoteControls.addProperty("pageIndex", pageIndex);
        remoteControls.addProperty("pageCount", pageCount);

        JsonArray pageNamesArr = new JsonArray();
        String[] names = devicePageNames;
        if (names != null) {
            for (String name : names) {
                pageNamesArr.add(name != null ? name : "");
            }
        }
        remoteControls.add("pageNames", pageNamesArr);

        JsonArray params = new JsonArray();
        for (int i = 0; i < PARAM_COUNT; i++) {
            JsonObject param = new JsonObject();
            param.addProperty("index", i);
            param.addProperty("name", paramNames[i] != null ? paramNames[i] : "");
            param.addProperty("value", paramValues[i]);
            param.addProperty("modulatedValue", paramModulatedValues[i]);
            param.addProperty("displayedValue", paramDisplayedValues[i] != null ? paramDisplayedValues[i] : "");
            param.addProperty("hasAutomation", paramHasAutomation[i]);
            param.addProperty("isBeingMapped", paramIsBeingMapped[i]);
            params.add(param);
        }
        remoteControls.add("parameters", params);

        obj.add("remoteControls", remoteControls);
        return obj;
    }

    private JsonObject getMasterDeviceState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", masterDeviceName);
        obj.addProperty("isEnabled", masterDeviceEnabled);
        obj.addProperty("isPlugin", masterDeviceIsPlugin);
        obj.addProperty("position", masterDevicePosition);
        obj.addProperty("presetName", masterPresetName);
        obj.addProperty("presetCategory", masterPresetCategory);
        obj.addProperty("presetCreator", masterPresetCreator);
        obj.addProperty("isNested", masterDeviceIsNested);
        obj.addProperty("hasSlots", masterDeviceHasSlots);
        JsonArray masterSlotNamesArr = new JsonArray();
        String[] masterSlots = masterDeviceSlotNames;
        if (masterSlots != null) {
            for (String s : masterSlots) {
                masterSlotNamesArr.add(s != null ? s : "");
            }
        }
        obj.add("slotNames", masterSlotNamesArr);
        obj.addProperty("hasLayers", masterDeviceHasLayers);
        obj.addProperty("hasDrumPads", masterDeviceHasDrumPads);

        JsonObject remoteControls = new JsonObject();
        remoteControls.addProperty("pageIndex", masterPageIndex);
        remoteControls.addProperty("pageCount", masterPageCount);

        JsonArray pageNamesArr = new JsonArray();
        String[] names = masterDevicePageNames;
        if (names != null) {
            for (String name : names) {
                pageNamesArr.add(name != null ? name : "");
            }
        }
        remoteControls.add("pageNames", pageNamesArr);

        JsonArray params = new JsonArray();
        for (int i = 0; i < PARAM_COUNT; i++) {
            JsonObject param = new JsonObject();
            param.addProperty("index", i);
            param.addProperty("name", masterParamNames[i] != null ? masterParamNames[i] : "");
            param.addProperty("value", masterParamValues[i]);
            param.addProperty("modulatedValue", masterParamModulatedValues[i]);
            param.addProperty("displayedValue", masterParamDisplayedValues[i] != null ? masterParamDisplayedValues[i] : "");
            param.addProperty("isBeingMapped", masterParamIsBeingMapped[i]);
            params.add(param);
        }
        remoteControls.add("parameters", params);

        obj.add("remoteControls", remoteControls);
        return obj;
    }

    private JsonObject getClipState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("trackName", clipTrackName);
        // Existing cursor observers use -1 until read; expose null instead of a fake address.
        if (clipCursorTrackPosition < 0) {
            obj.add("cursorTrackPosition", JsonNull.INSTANCE);
        } else {
            obj.addProperty("cursorTrackPosition", clipCursorTrackPosition);
        }
        if (clipCursorSceneIndex < 0) {
            obj.add("cursorSceneIndex", JsonNull.INSTANCE);
        } else {
            obj.addProperty("cursorSceneIndex", clipCursorSceneIndex);
        }
        obj.addProperty("playingStep", clipPlayingStep);
        obj.addProperty("loopLength", clipLoopLength);
        obj.addProperty("playStart", clipPlayStart);
        obj.addProperty("playStop", clipPlayStop);
        obj.addProperty("stepSize", clipStepSize);
        obj.addProperty("hasContent", clipHasNotes);
        obj.addProperty("launchQuantization", clipLaunchQuantization);
        obj.addProperty("launchMode", clipLaunchMode);
        obj.addProperty("shuffle", clipShuffle);
        obj.addProperty("accent", clipAccent);
        obj.addProperty("useLoopStartAsQuantizationReference", clipUseLoopStartAsQuantizationReference);
        obj.addProperty("isLoopEnabled", clipLoopEnabled);
        obj.addProperty("loopStart", clipLoopStart);
        // Refused launcher writes, this session (Phase 23, plan 23-08; narrowed by Phase 29, plan
        // 29-03). The count is KEPT and the per-refusal detail that stood beside it is RETIRED:
        // the detail now travels in the RPC response for macro/writeClip, and the four chain
        // macros — which do not defer — still have this counter. D-29-13, and the narrowing is
        // stated by name in recordWriteClipRefusal's comment and in the API reference rather than
        // being made silently.
        obj.addProperty("writeClipRefusals", writeClipRefusals);
        JsonObject color = new JsonObject();
        color.addProperty("r", clipColor[0]);
        color.addProperty("g", clipColor[1]);
        color.addProperty("b", clipColor[2]);
        obj.add("color", color);
        return obj;
    }

    /**
     * The arranger cursor clip's snapshot section. Public because
     * {@code arrangerClip/getState} delegates straight to it.
     *
     * <p>A null value anywhere in this object means "no observer has reported this yet", never
     * zero and never the empty string. That distinction is the whole point of the section: on
     * this surface a boundary of 0.0 is a real clip at the first bar, so a field defaulted to
     * 0.0 would be indistinguishable from one nobody has read.
     */
    public JsonObject getArrangerClipState() {
        JsonObject obj = new JsonObject();
        // F1: unwritten arranger fields are absent, even when the dispatcher retains
        // explicit JsonNull values for other RPC contracts.
        if (arrangerClipExists != null) obj.addProperty("exists", arrangerClipExists);
        if (arrangerClipTrackName != null) obj.addProperty("trackName", arrangerClipTrackName);
        obj.addProperty("playingStep", arrangerClipPlayingStep);
        if (arrangerClipLoopStart != null) obj.addProperty("loopStart", arrangerClipLoopStart);
        if (arrangerClipLoopLength != null) obj.addProperty("loopLength", arrangerClipLoopLength);
        if (arrangerClipPlayStart != null) obj.addProperty("playStart", arrangerClipPlayStart);
        if (arrangerClipPlayStop != null) obj.addProperty("playStop", arrangerClipPlayStop);
        if (arrangerClipStepSize != null) obj.addProperty("stepSize", arrangerClipStepSize);
        float[] rgb = arrangerClipColor;
        if (rgb != null) {
            JsonObject color = new JsonObject();
            color.addProperty("r", rgb[0]);
            color.addProperty("g", rgb[1]);
            color.addProperty("b", rgb[2]);
            obj.add("color", color);
        }
        return obj;
    }
    private JsonObject getMasterState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("volume", masterVolume);
        obj.addProperty("pan", masterPan);
        obj.addProperty("mute", masterMute);
        obj.addProperty("solo", masterSolo);
        if (masterActivated == null) {
            obj.add("activated", JsonNull.INSTANCE);
        } else {
            obj.addProperty("activated", masterActivated);
        }
        JsonObject color = new JsonObject();
        color.addProperty("r", masterColor[0]);
        color.addProperty("g", masterColor[1]);
        color.addProperty("b", masterColor[2]);
        obj.add("color", color);
        return obj;
    }

    private JsonObject getApplicationState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("projectName", projectName);
        obj.addProperty("canUndo", canUndo);
        obj.addProperty("canRedo", canRedo);
        obj.addProperty("hasActiveEngine", hasActiveEngine);
        obj.addProperty("panelLayout", panelLayout);
        obj.addProperty("hasSoloedTracks", hasSoloedTracks);
        obj.addProperty("hasMutedTracks", hasMutedTracks);
        obj.addProperty("hasArmedTracks", hasArmedTracks);
        obj.addProperty("isModified", isModified);
        obj.addProperty("cueVolume", cueVolume);
        obj.addProperty("cueMix", cueMix);
        return obj;
    }

    private JsonObject getArrangerState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("playbackFollow", arrangerPlaybackFollow);
        obj.addProperty("clipLauncherVisible", arrangerClipLauncherVisible);
        obj.addProperty("timelineVisible", arrangerTimelineVisible);
        obj.addProperty("cueMarkersVisible", arrangerCueMarkersVisible);
        obj.addProperty("effectTracksVisible", arrangerEffectTracksVisible);
        obj.addProperty("ioSectionVisible", arrangerIoSectionVisible);
        obj.addProperty("doubleRowTrackHeight", arrangerDoubleRowTrackHeight);
        return obj;
    }

    private JsonObject getArrangementState() {
        JsonObject obj = new JsonObject();

        // Loop range
        JsonObject loop = new JsonObject();
        loop.addProperty("start", arrangerLoopStart);
        loop.addProperty("duration", arrangerLoopDuration);
        loop.addProperty("enabled", arrangerLoopEnabled);
        obj.add("loop", loop);

        // Punch
        JsonObject punch = new JsonObject();
        punch.addProperty("inPosition", punchInPosition);
        punch.addProperty("inEnabled", punchInEnabled);
        punch.addProperty("outPosition", punchOutPosition);
        punch.addProperty("outEnabled", punchOutEnabled);
        obj.add("punch", punch);

        // Automation
        JsonObject automation = new JsonObject();
        automation.addProperty("writeMode", automationWriteMode);
        automation.addProperty("arrangerWriteEnabled", arrangerAutomationWriteEnabled);
        automation.addProperty("clipLauncherWriteEnabled", clipLauncherAutomationWriteEnabled);
        automation.addProperty("overrideActive", automationOverrideActive);
        obj.add("automation", automation);

        // Cue markers — bank-window object
        JsonObject cueMarkerObj = new JsonObject();
        cueMarkerObj.addProperty("bankSize", CUE_MARKER_COUNT);
        cueMarkerObj.addProperty("scrollPosition", cueMarkerScrollPosition);
        cueMarkerObj.addProperty("itemCount", cueMarkerItemCount);
        cueMarkerObj.addProperty("canScrollBackwards", cueMarkerCanScrollBackwards);
        cueMarkerObj.addProperty("canScrollForwards", cueMarkerCanScrollForwards);
        JsonArray markers = new JsonArray();
        for (int i = 0; i < CUE_MARKER_COUNT; i++) {
            JsonObject marker = new JsonObject();
            marker.addProperty("index", i);
            marker.addProperty("name", cueMarkerNames[i] != null ? cueMarkerNames[i] : "");
            marker.addProperty("position", cueMarkerPositions[i]);
            JsonObject color = new JsonObject();
            color.addProperty("r", cueMarkerColors[i][0]);
            color.addProperty("g", cueMarkerColors[i][1]);
            color.addProperty("b", cueMarkerColors[i][2]);
            marker.add("color", color);
            markers.add(marker);
        }
        cueMarkerObj.add("items", markers);
        obj.add("cueMarkers", cueMarkerObj);

        return obj;
    }

    public JsonObject getBrowserState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("exists", browserExists);
        obj.addProperty("title", browserTitle);
        obj.addProperty("selectedContentType", browserSelectedContentType);
        obj.addProperty("selectedContentTypeIndex", browserSelectedContentTypeIndex);

        String[] names = browserContentTypeNames;
        if (names != null) {
            JsonArray contentTypes = new JsonArray();
            for (String name : names) {
                contentTypes.add(name != null ? name : "");
            }
            obj.add("contentTypeNames", contentTypes);
        } else {
            obj.add("contentTypeNames", JsonNull.INSTANCE);
        }

        obj.addProperty("canAudition", browserCanAudition);
        obj.addProperty("shouldAudition", browserShouldAudition);
        obj.addProperty("resultName", browserResultName);
        obj.addProperty("resultIsSelected", browserResultIsSelected);
        obj.addProperty("resultsEntryCount", resultsEntryCount);

        // Filter columns
        JsonObject filters = new JsonObject();
        for (int i = 0; i < FILTER_COLUMN_COUNT; i++) {
            JsonObject col = new JsonObject();
            col.addProperty("exists", filterExists[i]);
            col.addProperty("name", filterNames[i]);
            col.addProperty("hitCount", filterHitCounts[i]);
            col.addProperty("entryCount", filterEntryCounts[i]);
            col.addProperty("hasNext", filterHasNext[i]);
            col.addProperty("hasPrevious", filterHasPrevious[i]);
            col.addProperty("wildcardHitCount", filterWildcardHitCounts[i]);
            filters.add(FILTER_COLUMN_NAMES[i], col);
        }
        obj.add("filters", filters);

        return obj;
    }

    // ── Public getters for bank scroll info (used by handlers) ──

    public JsonObject getSceneBankScrollInfo() {
        JsonObject obj = new JsonObject();
        obj.addProperty("scrollPosition", sceneBankOffset);
        obj.addProperty("itemCount", sceneItemCount);
        obj.addProperty("bankSize", SCENE_COUNT);
        obj.addProperty("canScrollBackwards", sceneCanScrollBackwards);
        obj.addProperty("canScrollForwards", sceneCanScrollForwards);
        return obj;
    }

    public int getSceneItemCount() { return sceneItemCount; }

    public JsonObject getCueMarkerBankScrollInfo() {
        JsonObject obj = new JsonObject();
        obj.addProperty("scrollPosition", cueMarkerScrollPosition);
        obj.addProperty("itemCount", cueMarkerItemCount);
        obj.addProperty("bankSize", CUE_MARKER_COUNT);
        obj.addProperty("canScrollBackwards", cueMarkerCanScrollBackwards);
        obj.addProperty("canScrollForwards", cueMarkerCanScrollForwards);
        return obj;
    }

    public int getCueMarkerItemCount() { return cueMarkerItemCount; }

    public JsonObject getTrackBankScrollInfo() {
        JsonObject obj = new JsonObject();
        obj.addProperty("scrollPosition", trackScrollPosition);
        obj.addProperty("itemCount", trackItemCount);
        obj.addProperty("bankSize", TRACK_COUNT);
        obj.addProperty("canScrollBackwards", trackCanScrollBackwards);
        obj.addProperty("canScrollForwards", trackCanScrollForwards);
        return obj;
    }

    public int getTrackItemCount() { return trackItemCount; }

    public String getTrackName(int index) {
        if (index < 0 || index >= TRACK_COUNT) return "";
        return trackNames[index] != null ? trackNames[index] : "";
    }

    public boolean hasSoloedTracks() { return hasSoloedTracks; }
    public boolean hasMutedTracks() { return hasMutedTracks; }
    public boolean hasArmedTracks() { return hasArmedTracks; }
    public boolean isModified() { return isModified; }
    public double getCueVolume() { return cueVolume; }
    public double getCueMix() { return cueMix; }

    public JsonObject getArpeggiatorState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("isEnabled", arpEnabled);
        obj.addProperty("mode", arpMode);
        obj.addProperty("octaves", arpOctaves);
        obj.addProperty("rate", arpRate);
        obj.addProperty("gateLength", arpGateLength);
        obj.addProperty("shuffle", arpShuffle);
        obj.addProperty("humanize", arpHumanize);
        obj.addProperty("isFreeRunning", arpFreeRunning);
        obj.addProperty("enableOverlappingNotes", arpOverlappingNotes);
        obj.addProperty("usePressureToVelocity", arpUsePressureToVelocity);
        obj.addProperty("terminateNotesImmediately", arpTerminateNotesImmediately);
        return obj;
    }

    public JsonObject getNoteLatchState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("isEnabled", noteLatchEnabled);
        obj.addProperty("mode", noteLatchMode);
        obj.addProperty("mono", noteLatchMono);
        obj.addProperty("velocityThreshold", noteLatchVelocityThreshold);
        obj.addProperty("activeNotes", noteLatchActiveNotes);
        return obj;
    }

    public JsonObject getGrooveState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", grooveEnabled);
        obj.addProperty("shuffleAmount", grooveShuffleAmount);
        obj.addProperty("shuffleRate", grooveShuffleRate);
        obj.addProperty("accentAmount", grooveAccentAmount);
        obj.addProperty("accentRate", grooveAccentRate);
        obj.addProperty("accentPhase", grooveAccentPhase);
        return obj;
    }
}
