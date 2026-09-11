package dev.bcrick.secondo.extension;

import java.lang.reflect.Field;

/**
 * Reflection utilities for injecting test data into StateCache's private fields.
 */
public class StateCacheTestHelper {

    /**
     * Reads a private static {@code TRACK_COUNT} from the given class.
     *
     * <p>Both {@link SecondoExtension} and {@link StateCache} declare their own
     * {@code private static final int TRACK_COUNT}. Tests read the constant through
     * this accessor rather than restating its value as a literal, so a future change
     * to the ceiling cannot leave an assertion behind asserting the old number.
     */
    static int trackCountOf(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("TRACK_COUNT");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read TRACK_COUNT from: " + owner.getName(), e);
        }
    }

    /**
     * Reads a private static {@code SCENE_COUNT} from the given class.
     *
     * <p>Five classes declare their own {@code private static final int SCENE_COUNT}:
     * {@link SecondoExtension}, {@link StateCache}, and the {@code SceneHandler},
     * {@code ClipHandler} and {@code TrackHandler} in {@code dev.bcrick.secondo.handlers}.
     * {@code setAccessible(true)} is what makes the three handler declarations readable
     * from a test in this package, so {@code SceneCountConsistencyTest} does not have to
     * move or be split in two.
     *
     * <p>This one reader is {@code public} (unlike its siblings here) because the handler
     * tests in {@code dev.bcrick.secondo.handlers} need it too: their out-of-range cases must
     * derive the first invalid scene index from the constant rather than restate it, for the
     * same reason {@code trackCountOf} exists. Restated literals are what made this change
     * red in six places.
     */
    public static int sceneCountOf(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("SCENE_COUNT");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read SCENE_COUNT from: " + owner.getName(), e);
        }
    }

    /**
     * Reads a private static {@code FLUSH_DELAY_MS} from the given class.
     *
     * <p>Three classes in {@code dev.bcrick.secondo.handlers} declare their own
     * {@code private static final long FLUSH_DELAY_MS}: {@code MacroHandler},
     * {@code DeviceHandler} and {@code MasterDeviceHandler}. Nothing in the compiler links
     * them. The read goes through here rather than being inlined in the test for the reason
     * {@link #trackCountOf(Class)} and {@link #sceneCountOf(Class)} exist -- a test that
     * restates the value as a literal is a test that can be left behind asserting the old
     * number, and {@code setAccessible(true)} is what lets a test in package
     * {@code dev.bcrick.secondo.extension} read three declarations that live in another package.
     *
     * <p>It returns {@code long}, not {@code int}: the constant is declared {@code long}
     * because it is passed to {@code TaskScheduler.schedule(Runnable, long)}, and reading it
     * as an int would throw rather than fail with the message this guard is built to give.
     */
    static long flushDelayOf(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("FLUSH_DELAY_MS");
            field.setAccessible(true);
            return field.getLong(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read FLUSH_DELAY_MS from: " + owner.getName(), e);
        }
    }

    static void setField(StateCache cache, String fieldName, Object value) {
        try {
            Field field = StateCache.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(cache, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    static void setArrayElement(StateCache cache, String fieldName, int index, Object value) {
        try {
            Field field = StateCache.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object array = field.get(cache);
            java.lang.reflect.Array.set(array, index, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set array element: " + fieldName + "[" + index + "]", e);
        }
    }

    static void set2DArrayElement(StateCache cache, String fieldName, int i, int j, Object value) {
        try {
            Field field = StateCache.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object array = field.get(cache);
            Object innerArray = java.lang.reflect.Array.get(array, i);
            java.lang.reflect.Array.set(innerArray, j, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set 2D array element: " + fieldName + "[" + i + "][" + j + "]", e);
        }
    }

    static void populateTransport(StateCache cache) {
        setField(cache, "isPlaying", true);
        setField(cache, "isRecording", false);
        setField(cache, "tempo", 120.0);
        setField(cache, "playPosition", 4.5);
        setField(cache, "timeSignatureNumerator", 4);
        setField(cache, "timeSignatureDenominator", 4);
        setField(cache, "isLoopEnabled", true);
        setField(cache, "isMetronomeEnabled", false);
        setField(cache, "metronomeVolume", 0.8);
        setField(cache, "preRoll", "one_bar");
        setField(cache, "defaultLaunchQuantization", "default");
        setField(cache, "clipLauncherPostRecordingAction", "play_recorded");
        setField(cache, "clipLauncherPostRecordingTimeOffset", 0.0);
        setField(cache, "clipLauncherOverdubEnabled", false);
        setField(cache, "fillModeActive", false);
    }

    static void populateTrack(StateCache cache, int index) {
        setArrayElement(cache, "trackNames", index, "Bass");
        setArrayElement(cache, "trackVolumes", index, 0.75);
        setArrayElement(cache, "trackPans", index, -0.2);
        setArrayElement(cache, "trackMutes", index, false);
        setArrayElement(cache, "trackSolos", index, true);
        setArrayElement(cache, "trackArms", index, true);
        // trackColors is float[TRACK_COUNT][3]
        try {
            Field field = StateCache.class.getDeclaredField("trackColors");
            field.setAccessible(true);
            float[][] colors = (float[][]) field.get(cache);
            colors[index][0] = 1.0f;
            colors[index][1] = 0.5f;
            colors[index][2] = 0.0f;
        } catch (Exception e) {
            throw new RuntimeException("Failed to set track color", e);
        }
        setArrayElement(cache, "trackCrossfadeModes", index, "AB");
        setArrayElement(cache, "trackMonitorModes", index, "AUTO");
        setArrayElement(cache, "trackTypes", index, "Instrument");
        setArrayElement(cache, "trackIsGroup", index, false);
        setArrayElement(cache, "trackIsGroupExpanded", index, false);
    }

    static void populateScene(StateCache cache, int index) {
        setArrayElement(cache, "sceneNames", index, "Intro");
        setArrayElement(cache, "sceneClipCounts", index, 3);
        try {
            Field field = StateCache.class.getDeclaredField("sceneColors");
            field.setAccessible(true);
            float[][] colors = (float[][]) field.get(cache);
            colors[index][0] = 0.0f;
            colors[index][1] = 1.0f;
            colors[index][2] = 0.0f;
        } catch (Exception e) {
            throw new RuntimeException("Failed to set scene color", e);
        }
    }

    static void populateDevice(StateCache cache) {
        setField(cache, "cursorTrackName", "Lead Synth");
        setField(cache, "deviceName", "Polymer");
        setField(cache, "deviceEnabled", true);
        setField(cache, "deviceIsPlugin", false);
        setField(cache, "devicePosition", 0);
        setField(cache, "presetName", "Init");
        setField(cache, "presetCategory", "Synth");
        setField(cache, "presetCreator", "Bitwig");
        setField(cache, "isWindowOpen", false);
        setField(cache, "isExpanded", true);
        setField(cache, "deviceIsNested", false);
        setField(cache, "deviceHasSlots", false);
        setField(cache, "deviceSlotNames", new String[0]);
        setField(cache, "deviceHasLayers", false);
        setField(cache, "deviceHasDrumPads", false);
        setField(cache, "pageIndex", 0);
        setField(cache, "pageCount", 2);
        setField(cache, "devicePageNames", new String[]{"Main", "Modulation"});
        setArrayElement(cache, "paramNames", 0, "Cutoff");
        setArrayElement(cache, "paramValues", 0, 0.75);
        setArrayElement(cache, "paramModulatedValues", 0, 0.62);
        setArrayElement(cache, "paramDisplayedValues", 0, "75%");
        setArrayElement(cache, "paramHasAutomation", 0, true);
    }

    static void populateClip(StateCache cache) {
        setField(cache, "clipTrackName", "Bass");
        setField(cache, "clipPlayingStep", 8);
        setField(cache, "clipLoopLength", 16.0);
        setField(cache, "clipPlayStart", 0.0);
        setField(cache, "clipPlayStop", 16.0);
        setField(cache, "clipStepSize", 0.25);
        setField(cache, "clipHasNotes", true);
        setField(cache, "clipLaunchQuantization", "default");
        setField(cache, "clipLaunchMode", "play_with_quantization");
        setField(cache, "clipShuffle", false);
        setField(cache, "clipAccent", 0.5);
        setField(cache, "clipUseLoopStartAsQuantizationReference", false);
        setField(cache, "clipLoopEnabled", true);
        setField(cache, "clipLoopStart", 0.0);
        try {
            Field field = StateCache.class.getDeclaredField("clipColor");
            field.setAccessible(true);
            float[] color = (float[]) field.get(cache);
            color[0] = 0.2f;
            color[1] = 0.4f;
            color[2] = 0.8f;
        } catch (Exception e) {
            throw new RuntimeException("Failed to set clip color", e);
        }
    }

    static void populateMaster(StateCache cache) {
        setField(cache, "masterVolume", 0.9);
        setField(cache, "masterPan", 0.0);
        setField(cache, "masterMute", false);
        setField(cache, "masterSolo", false);
        try {
            Field field = StateCache.class.getDeclaredField("masterColor");
            field.setAccessible(true);
            float[] color = (float[]) field.get(cache);
            color[0] = 0.5f;
            color[1] = 0.5f;
            color[2] = 0.5f;
        } catch (Exception e) {
            throw new RuntimeException("Failed to set master color", e);
        }
    }

    static void populateApplication(StateCache cache) {
        setField(cache, "projectName", "My Song");
        setField(cache, "canUndo", true);
        setField(cache, "canRedo", false);
        setField(cache, "hasActiveEngine", true);
        setField(cache, "panelLayout", "MIX");
        setField(cache, "hasSoloedTracks", true);
        setField(cache, "hasMutedTracks", false);
        setField(cache, "hasArmedTracks", true);
        setField(cache, "isModified", true);
    }

    static void populateArranger(StateCache cache) {
        setField(cache, "arrangerPlaybackFollow", true);
        setField(cache, "arrangerClipLauncherVisible", true);
        setField(cache, "arrangerTimelineVisible", false);
        setField(cache, "arrangerCueMarkersVisible", true);
        setField(cache, "arrangerEffectTracksVisible", false);
        setField(cache, "arrangerIoSectionVisible", true);
        setField(cache, "arrangerDoubleRowTrackHeight", false);
    }

    static void populateArrangement(StateCache cache) {
        setField(cache, "arrangerLoopEnabled", true);
        setField(cache, "arrangerLoopStart", 4.0);
        setField(cache, "arrangerLoopDuration", 16.0);
        setField(cache, "punchInEnabled", true);
        setField(cache, "punchOutEnabled", false);
        setField(cache, "punchInPosition", 2.0);
        setField(cache, "punchOutPosition", 32.0);
        setField(cache, "automationWriteMode", "latch");
        setField(cache, "arrangerAutomationWriteEnabled", true);
        setField(cache, "clipLauncherAutomationWriteEnabled", false);
        setField(cache, "automationOverrideActive", false);
    }

    static void populateBrowser(StateCache cache) {
        setField(cache, "browserExists", true);
        setField(cache, "browserTitle", "Presets");
        setField(cache, "browserSelectedContentType", "Presets");
        setField(cache, "browserContentTypeNames", new String[]{"Presets", "Samples"});
        setField(cache, "browserCanAudition", true);
        setField(cache, "browserShouldAudition", false);
        setField(cache, "browserResultName", "Init");
        setField(cache, "browserResultIsSelected", true);
        setField(cache, "resultsEntryCount", 42);
        setArrayElement(cache, "filterExists", 0, true);
        setArrayElement(cache, "filterNames", 0, "Synth");
        setArrayElement(cache, "filterHitCounts", 0, 10);
        setArrayElement(cache, "filterEntryCounts", 0, 25);
    }

    static void populateArpeggiator(StateCache cache) {
        setField(cache, "arpEnabled", true);
        setField(cache, "arpMode", "up_down");
        setField(cache, "arpOctaves", 2);
        setField(cache, "arpRate", 0.25);
        setField(cache, "arpGateLength", 0.8);
        setField(cache, "arpShuffle", true);
        setField(cache, "arpHumanize", 0.1);
        setField(cache, "arpFreeRunning", false);
        setField(cache, "arpOverlappingNotes", true);
        setField(cache, "arpUsePressureToVelocity", false);
        setField(cache, "arpTerminateNotesImmediately", false);
    }

    static void populateNoteLatch(StateCache cache) {
        setField(cache, "noteLatchEnabled", true);
        setField(cache, "noteLatchMode", "hold");
        setField(cache, "noteLatchMono", false);
        setField(cache, "noteLatchVelocityThreshold", 64);
        setField(cache, "noteLatchActiveNotes", 3);
    }

    static void populateMasterDevice(StateCache cache) {
        setField(cache, "masterDeviceName", "EQ-5");
        setField(cache, "masterDeviceEnabled", true);
        setField(cache, "masterDeviceIsPlugin", false);
        setField(cache, "masterDevicePosition", 1);
        setField(cache, "masterPresetName", "Flat");
        setField(cache, "masterPresetCategory", "EQ");
        setField(cache, "masterPresetCreator", "Bitwig");
        setField(cache, "masterDeviceIsNested", false);
        setField(cache, "masterDeviceHasSlots", false);
        setField(cache, "masterDeviceSlotNames", new String[0]);
        setField(cache, "masterDeviceHasLayers", false);
        setField(cache, "masterDeviceHasDrumPads", false);
        setField(cache, "masterPageIndex", 0);
        setField(cache, "masterPageCount", 1);
        setField(cache, "masterDevicePageNames", new String[]{"Main"});
        setArrayElement(cache, "masterParamNames", 0, "Gain");
        setArrayElement(cache, "masterParamValues", 0, 0.5);
        setArrayElement(cache, "masterParamModulatedValues", 0, 0.5);
        setArrayElement(cache, "masterParamDisplayedValues", 0, "0 dB");
    }
}
