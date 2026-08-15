package dev.gregross.gig.extension;

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

import java.util.ArrayList;
import java.util.List;

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
    private volatile boolean trackCanScrollForwards;
    private volatile boolean trackCanScrollBackwards;

    // Master state
    private volatile double masterVolume;
    private volatile double masterPan;
    private volatile boolean masterMute;
    private volatile boolean masterSolo;
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
    private volatile boolean browserExists;
    private volatile String browserTitle = "";
    private volatile String browserSelectedContentType = "";
    private volatile String[] browserContentTypeNames = new String[0];
    private volatile boolean browserCanAudition;
    private volatile boolean browserShouldAudition;
    private volatile String browserResultName = "";
    private volatile boolean browserResultIsSelected;

    // Browser filter state — 8 named columns
    static final int FILTER_COLUMN_COUNT = 8;
    static final String[] FILTER_COLUMN_NAMES = {
        "category", "tag", "creator", "device",
        "deviceType", "fileType", "location", "smartCollection"
    };
    private final boolean[] filterExists = new boolean[FILTER_COLUMN_COUNT];
    private final String[] filterNames = new String[FILTER_COLUMN_COUNT];
    private final int[] filterHitCounts = new int[FILTER_COLUMN_COUNT];
    private final int[] filterEntryCounts = new int[FILTER_COLUMN_COUNT];
    private CursorBrowserFilterItem[] filterCursors;
    private BrowserFilterColumn[] filterColumns;

    // Browser result bank state
    private static final int RESULT_BANK_SIZE = 8;
    private final String[] resultBankNames = new String[RESULT_BANK_SIZE];
    private final boolean[] resultBankSelected = new boolean[RESULT_BANK_SIZE];
    private volatile int resultsEntryCount;
    private BrowserResultsItemBank resultBank;

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
        trackBank.itemCount().addValueObserver((IntegerValueChangedCallback) v -> trackItemCount = v);

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
            slotBank.addHasContentObserver((IndexedBooleanValueChangedCallback) (slotIndex, value) ->
                clipHasContent[trackIdx][slotIndex] = value);

            slotBank.addIsPlayingObserver((IndexedBooleanValueChangedCallback) (slotIndex, value) ->
                clipIsPlaying[trackIdx][slotIndex] = value);

            slotBank.addIsRecordingObserver((IndexedBooleanValueChangedCallback) (slotIndex, value) ->
                clipIsRecording[trackIdx][slotIndex] = value);

            slotBank.addNameObserver((IndexedStringValueChangedCallback) (slotIndex, value) ->
                clipNames[trackIdx][slotIndex] = value);

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
     * {@code GigMaestroExtension.init()} -- and not from a track, so it does not follow the
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
        // Initialize arrays
        for (int i = 0; i < FILTER_COLUMN_COUNT; i++) {
            filterNames[i] = "";
        }

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
        }
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
        return obj;
    }

    public double getClipStepSize() {
        return clipStepSize;
    }

    public boolean clipHasContent(int trackIndex, int slotIndex) {
        if (trackIndex < 0 || trackIndex >= TRACK_COUNT || slotIndex < 0 || slotIndex >= SCENE_COUNT) {
            return false;
        }
        return clipHasContent[trackIndex][slotIndex];
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

    private JsonObject getTracksState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("bankSize", TRACK_COUNT);
        obj.addProperty("scrollPosition", trackScrollPosition);
        obj.addProperty("itemCount", trackItemCount);
        obj.addProperty("canScrollBackwards", trackCanScrollBackwards);
        obj.addProperty("canScrollForwards", trackCanScrollForwards);

        JsonArray arr = new JsonArray();
        for (int i = 0; i < TRACK_COUNT; i++) {
            JsonObject track = new JsonObject();
            track.addProperty("index", i);
            track.addProperty("name", trackNames[i] != null ? trackNames[i] : "");
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
            track.addProperty("trackType", trackTypes[i] != null ? trackTypes[i] : "");
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
        obj.addProperty("exists", arrangerClipExists);
        obj.addProperty("trackName", arrangerClipTrackName);
        obj.addProperty("playingStep", arrangerClipPlayingStep);
        obj.addProperty("loopStart", arrangerClipLoopStart);
        obj.addProperty("loopLength", arrangerClipLoopLength);
        obj.addProperty("playStart", arrangerClipPlayStart);
        obj.addProperty("playStop", arrangerClipPlayStop);
        obj.addProperty("stepSize", arrangerClipStepSize);
        float[] rgb = arrangerClipColor;
        if (rgb == null) {
            obj.add("color", JsonNull.INSTANCE);
        } else {
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

        JsonArray contentTypes = new JsonArray();
        String[] names = browserContentTypeNames;
        if (names != null) {
            for (String name : names) {
                contentTypes.add(name != null ? name : "");
            }
        }
        obj.add("contentTypeNames", contentTypes);

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
            col.addProperty("name", filterNames[i] != null ? filterNames[i] : "");
            col.addProperty("hitCount", filterHitCounts[i]);
            col.addProperty("entryCount", filterEntryCounts[i]);
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
