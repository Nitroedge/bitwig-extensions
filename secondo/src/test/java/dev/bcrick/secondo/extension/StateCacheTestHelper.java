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
     *
     * <p>Widened to {@code public} by plan 31-04, for the reason {@link #sceneCountOf(Class)} and
     * {@link #flushDelayOf(Class)} are public: a caller that needs it lives in
     * {@code dev.bcrick.secondo.handlers}. The three {@code MacroHandler} test classes size the
     * {@code TrackBankManager} they install on the cache's own ceiling rather than restating 16,
     * which is the whole purpose of this reader.
     */
    public static int trackCountOf(Class<?> owner) {
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
     *
     * <p>Widened to {@code public} by plan 31-03, for the reason {@link #sceneCountOf(Class)} and
     * {@link #setClipCursorPosition} are public: a caller that needs it lives in
     * {@code dev.bcrick.secondo.handlers}. {@code MacroHandlerWrongSlotTest} sizes its virtual
     * drain round on the flush hop {@code MacroHandler} actually schedules against, and the whole
     * purpose of this reader is that a test does not restate that number as a literal.
     */
    public static long flushDelayOf(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("FLUSH_DELAY_MS");
            field.setAccessible(true);
            return field.getLong(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read FLUSH_DELAY_MS from: " + owner.getName(), e);
        }
    }

    /**
     * Sets the two fields that record where the launcher cursor clip IS -- what
     * {@code registerClipCursorObservers} writes from {@code Clip#getTrack().position()} and
     * {@code Clip#clipLauncherSlot().sceneIndex()} in a running Bitwig.
     *
     * <p>A test in this package sets what an observer would because there is no other way to
     * express the bug these fields exist to stop: the wrong-slot write happens when the cursor is
     * somewhere other than the slot a write named, and without a settable cursor position a test
     * can only ever exercise the case where they agree. Public, unlike most of this class, because
     * the handler tests that need it live in {@code dev.bcrick.secondo.handlers} -- the same
     * reason {@link #sceneCountOf(Class)} is public.
     *
     * <p>Pass {@code -1} for either to model "never observed", which is the state a fresh
     * {@link StateCache} is in and which the handler must treat as "do not write".
     */
    public static void setClipCursorPosition(StateCache cache, int trackPosition, int sceneIndex) {
        setField(cache, "clipCursorTrackPosition", trackPosition);
        setField(cache, "clipCursorSceneIndex", sceneIndex);
    }

    /**
     * Sets what the launcher's has-content observer would have reported for ONE slot: the value,
     * and the fact that it was observed at all.
     *
     * <p>Both, together, because they are one event in production — {@code StateCache}'s
     * has-content lambda writes the flag in the same statement block that writes the value, so a
     * test that could set one without the other could model a state the engine cannot reach.
     *
     * <p>Calling this is how a test says "this slot IS observed". NOT calling it is how a test
     * says "no observer has fired here", which is the third state
     * {@code MacroHandler.ClipWrite#slotWasEmpty} exists for and the one branch that had no way
     * of being reached before plan 29-03 added the flag: the undo-on-refusal must withhold there
     * rather than delete on a default-false reading of a primitive array.
     *
     * <p>Public, like {@link #setClipCursorPosition}, and for the same reason: the handler tests
     * that need it live in {@code dev.bcrick.secondo.handlers}.
     */
    public static void setClipSlotContent(StateCache cache, int trackIndex, int slotIndex,
                                          boolean hasContent) {
        set2DArrayElement(cache, "clipHasContent", trackIndex, slotIndex, hasContent);
        set2DArrayElement(cache, "clipHasContentObserved", trackIndex, slotIndex, true);
    }

    /**
     * Sets the name the launcher's per-slot name observer would have reported for ONE slot,
     * addressed by PHYSICAL BANK SLOT — the flat {@code trackBank} subscript — and not by public
     * track index.
     *
     * <p>The coordinate is named in that sentence rather than left to the reader because the whole
     * class of defect Phase 31 closes is a public index used where a bank slot belongs: the two
     * agree until a group track is collapsed or the bank is scrolled, and then they silently do
     * not. {@code clipNames} is keyed on the bank subscript, so that is what this takes.
     *
     * <p>Public, like {@link #setClipCursorPosition} and {@link #setClipSlotContent}, because the
     * handler tests that need it live in {@code dev.bcrick.secondo.handlers}.
     */
    public static void setClipSlotName(StateCache cache, int bankSlot, int sceneIndex, String name) {
        set2DArrayElement(cache, "clipNames", bankSlot, sceneIndex, name);
    }

    /**
     * Installs the resolver {@link StateCache#resolveCanonicalBankSlot(int)} answers through.
     *
     * <p>It exists so a test can DRIVE the canonical resolution rather than bypass it. Without a
     * resolver the cache answers {@code -1} -- the unproven answer -- to every public track index,
     * which is a state production is never in: the extension wires one in during initialization,
     * at the line that registers the clip observers. A handler test that left it absent would be
     * asserting against a coordinate space the engine does not run in, and
     * {@code new TrackBankManager(null, 16)} resolves every in-range index to itself, which is
     * what the tests whose banks are unscrolled want.
     *
     * <p>Public, like {@link #setClipCursorPosition} and {@link #setClipSlotContent}, because the
     * handler tests that need it live in {@code dev.bcrick.secondo.handlers}.
     */
    public static void installTrackBankManager(StateCache cache,
                                               dev.bcrick.secondo.handlers.TrackBankManager manager) {
        setField(cache, "trackBankManager", manager);
    }

    /**
     * Advances the cache's observation tick and stamps it on ONE slot, addressed by PHYSICAL BANK
     * SLOT — the flat {@code trackBank} subscript — and not by public track index.
     *
     * <p>This models one observer callback landing on that slot: in production the counter is
     * bumped inside the same lambda that assigns the value, so a test that could stamp a slot
     * without moving the tick would be modelling a state the engine cannot reach.
     *
     * <p>The two fields it writes — {@code clipObservationSeq} and {@code observationTick} — are
     * added to {@link StateCache} by plan 31-04. This helper is written at 31-03 so the harness
     * side is ready, and it says so plainly rather than throwing a bare reflection failure if it is
     * called before that plan lands.
     */
    public static void bumpClipObservationSeq(StateCache cache, int bankSlot, int sceneIndex) {
        try {
            Field tickField = StateCache.class.getDeclaredField("observationTick");
            tickField.setAccessible(true);
            long next = tickField.getLong(cache) + 1;
            tickField.setLong(cache, next);

            Field seqField = StateCache.class.getDeclaredField("clipObservationSeq");
            seqField.setAccessible(true);
            long[][] seq = (long[][]) seqField.get(cache);
            seq[bankSlot][sceneIndex] = next;
        } catch (NoSuchFieldException e) {
            throw new UnsupportedOperationException(
                "StateCache has no freshness counter yet: `clipObservationSeq` and"
                    + " `observationTick` are added by plan 31-04, and nothing before that plan can"
                    + " bump a field that does not exist.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to bump clipObservationSeq[" + bankSlot + "]["
                + sceneIndex + "]", e);
        }
    }

    /**
     * Sets the scene bank's scroll position -- the offset that turns a BANK-WINDOW slot subscript
     * into a PROJECT-ABSOLUTE scene index, and back.
     *
     * <p>The coordinate space is named in that sentence rather than left to the reader because
     * mixing two of them is the whole defect this helper exists to make expressible
     * (31-REVIEW.md CR-01). {@code clipCursorSceneIndex} is bound to
     * {@code cursorClip.clipLauncherSlot().sceneIndex()}, which the API reference defines as the
     * position of the scene within the list of Bitwig Studio scenes -- PROJECT-ABSOLUTE -- while a
     * caller's {@code sceneIndex} is a subscript into the sixteen-wide {@code ClipLauncherSlotBank}
     * WINDOW. At a scroll of zero the two are the same number, which is why every live run to date
     * has been at zero and the defect has never been observed.
     *
     * <p>In production this field is written by {@code sceneBank.scrollPosition()}'s observer
     * ({@code StateCache.java:722}) and has NO public accessor -- adding one belongs to the fix,
     * not to the harness. Seeding it here is what gives that fix something to read.
     */
    public static void setSceneBankOffset(StateCache cache, int scrollPosition) {
        setField(cache, "sceneBankOffset", scrollPosition);
    }

    /**
     * Sets the PROJECT-ABSOLUTE track position observed for ONE physical bank slot -- what
     * {@code track.position()}'s observer writes per bank subscript ({@code StateCache.java:519}).
     *
     * <p>Two coordinate spaces meet in this one call and both are named, because they are different
     * numbers the moment a group track is collapsed or the track bank is scrolled. The argument
     * {@code bankSlot} is a PHYSICAL BANK SUBSCRIPT into the flat {@code trackBank}; the value
     * {@code position} is the track's PROJECT-ABSOLUTE position in Bitwig's own track list. A
     * caller's {@code trackIndex} is neither -- it is a PUBLIC INDEX, which is what
     * {@link StateCache#resolveCanonicalBankSlot(int)} exists to convert into the first of these.
     *
     * <p>{@code trackPositions} is an {@code Integer[]}, so an unset slot reads {@code null} -- the
     * honest "no observer has fired here" -- and this helper is how a test says one has.
     */
    public static void setTrackPositionAtBankSlot(StateCache cache, int bankSlot, int position) {
        setArrayElement(cache, "trackPositions", bankSlot, position);
    }

    /**
     * Says that NO position observer has ever fired for one physical bank slot, which
     * {@code trackPositions} represents as an absent entry rather than as a number.
     *
     * <p>The sibling above cannot express this: it takes an {@code int}, and every {@code int} is
     * a position some track could genuinely have. This is the third of the three unresolvable
     * cases {@code StateCache#absoluteTrackPositionForPublicIndex} names -- the one where the
     * public index RESOLVES to a real bank slot and the slot still has no absolute position to
     * convert to -- and it is the only one of the three in which the emptiness read in front of it
     * is itself proven, so it is the only one that can assert what the undo does. Added by plan
     * 31-16 for that case.
     */
    public static void clearTrackPositionAtBankSlot(StateCache cache, int bankSlot) {
        setArrayElement(cache, "trackPositions", bankSlot, null);
    }

    /**
     * Reads a private static {@code STAMP_PREFIX} from the given class.
     *
     * <p>{@code MacroHandler} declares {@code private static final String STAMP_PREFIX}
     * ({@code :198}), and every token an identity proof is taken with starts with it. The read goes
     * through here rather than being inlined in a test as {@code "SECONDO-STAMP-"} for exactly the
     * reason {@link #flushDelayOf(Class)} and {@link #trackCountOf(Class)} exist: a test that
     * restates a constant is a test that can be left behind asserting the old value.
     */
    public static String stampPrefixOf(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("STAMP_PREFIX");
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read STAMP_PREFIX from: " + owner.getName(), e);
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
