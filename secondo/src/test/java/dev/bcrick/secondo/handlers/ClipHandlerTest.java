package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.ClipLauncherSlot;
import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.InsertionPoint;
import com.bitwig.extension.controller.api.Scene;
import com.bitwig.extension.controller.api.SceneBank;
import com.bitwig.extension.controller.api.SettableBeatTimeValue;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableColorValue;
import com.bitwig.extension.controller.api.SettableEnumValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.bcrick.secondo.extension.StateCacheTestHelper.sceneCountOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClipHandlerTest {

    @Mock private TrackBank mockTrackBank;
    @Mock private SceneBank mockSceneBank;
    @Mock private Clip mockCursorClip;
    @Mock private Track mockTrack;
    @Mock private ClipLauncherSlotBank mockSlotBank;
    @Mock private ClipLauncherSlot mockSlot;
    @Mock private Scene mockScene;

    // Cursor clip chain mocks
    @Mock private SettableEnumValue mockLaunchQuantization;
    @Mock private SettableEnumValue mockLaunchMode;
    @Mock private SettableBooleanValue mockShuffle;
    @Mock private SettableRangedValue mockAccent;
    @Mock private SettableBooleanValue mockUseLoopStartAsQuantRef;
    @Mock private SettableBeatTimeValue mockPlayStart;
    @Mock private SettableBeatTimeValue mockPlayStop;
    @Mock private SettableBeatTimeValue mockLoopStart;
    @Mock private SettableBeatTimeValue mockLoopLength;
    @Mock private SettableBooleanValue mockLoopEnabled;

    // Slot chain mocks
    @Mock private SettableColorValue mockSlotColor;
    @Mock private InsertionPoint mockInsertionPoint;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        // A REAL TrackBankManager over the mock bank, not a mock manager: with nothing observed
        // the canonical mapping is the identity, so every stub below keeps meaning what it meant
        // before CR-03 routed this handler through the resolver.
        new ClipHandler(new TrackBankManager(mockTrackBank, 8), mockSceneBank, mockCursorClip,
            new StateCache()).register(dispatcher);

        // Common stubs: trackBank → track → slotBank → slot
        when(mockTrackBank.getSizeOfBank()).thenReturn(8);
        when(mockTrackBank.getItemAt(0)).thenReturn(mockTrack);
        when(mockTrack.clipLauncherSlotBank()).thenReturn(mockSlotBank);
        when(mockSlotBank.getItemAt(0)).thenReturn(mockSlot);

        // SceneBank → scene
        when(mockSceneBank.getScene(0)).thenReturn(mockScene);
    }

    // --- Registration ---


    /**
     * The first scene index the handler must refuse: exactly one past the ceiling.
     *
     * <p>Derived from ClipHandler's own SCENE_COUNT rather than restated as a
     * literal. A restated literal does not merely go stale when the ceiling moves --
     * it silently stops testing anything, because the old out-of-range index becomes a
     * perfectly valid one and the case then asserts an error that will never come.
     * That is precisely how this file went red when the ceiling moved 5 -> 16.
     */
    private static int firstInvalidSceneIndex() {
        return sceneCountOf(ClipHandler.class);
    }

    @Test
    void registersAllClipMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("clip/launch"));
        assertTrue(methods.contains("clip/stop"));
        assertTrue(methods.contains("clip/record"));
        assertTrue(methods.contains("clip/create"));
        assertTrue(methods.contains("clip/select"));
        assertTrue(methods.contains("clip/delete"));
        assertTrue(methods.contains("clip/rename"));
        assertTrue(methods.contains("clip/duplicate"));
        assertTrue(methods.contains("clip/duplicateToSlot"));
        assertTrue(methods.contains("scene/launch"));
        assertTrue(methods.contains("clip/insertFile"));
        assertTrue(methods.contains("clip/browseToInsert"));
    }

    @Test
    void registersExactlyThirtyTwoMethods() {
        // 10 original + 6 launch settings + 14 grid enhancements + 2 Phase 26 slot routes
        assertEquals(32, dispatcher.getRegisteredMethods().size());
    }

    @Test
    void registersClipLaunchSettingsMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("clip/setLaunchQuantization"));
        assertTrue(methods.contains("clip/setLaunchMode"));
        assertTrue(methods.contains("clip/setShuffle"));
        assertTrue(methods.contains("clip/setAccent"));
        assertTrue(methods.contains("clip/setUseLoopStartAsQuantizationReference"));
        assertTrue(methods.contains("clip/getLaunchSettings"));
    }

    @Test
    void registersGridEnhancementMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("clip/setColor"));
        assertTrue(methods.contains("clip/setPlayStart"));
        assertTrue(methods.contains("clip/setPlayStop"));
        assertTrue(methods.contains("clip/setLoopStart"));
        assertTrue(methods.contains("clip/setLoopLength"));
        assertTrue(methods.contains("clip/setLoopEnabled"));
        assertTrue(methods.contains("clip/getPlaybackSettings"));
        assertTrue(methods.contains("clip/quantize"));
        assertTrue(methods.contains("clip/transpose"));
        assertTrue(methods.contains("clip/duplicateContent"));
        assertTrue(methods.contains("clip/showInEditor"));
        assertTrue(methods.contains("clip/launchAlt"));
        assertTrue(methods.contains("clip/launchRelease"));
        assertTrue(methods.contains("clip/launchReleaseAlt"));
    }

    // --- clip/select validation ---

    @Test
    void clipSelect_emptySlot_returnsError() {
        // StateCache defaults to all slots empty (hasContent=false)
        String response = dispatcher.handle(rpc("clip/select", "{\"trackIndex\": 0, \"slotIndex\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "slot is empty");
    }

    // --- clip/rename validation ---

    @Test
    void clipRename_missingName_returnsError() {
        String response = dispatcher.handle(rpc("clip/rename", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "name");
    }

    // --- clip/duplicate validation ---

    @Test
    void clipDuplicate_missingTrackIndex_returnsError() {
        String response = dispatcher.handle(rpc("clip/duplicate", "{\"slotIndex\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "trackIndex");
    }

    @Test
    void clipDuplicate_missingSlotIndex_returnsError() {
        String response = dispatcher.handle(rpc("clip/duplicate", "{\"trackIndex\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "slotIndex");
    }

    // --- clip/duplicateToSlot validation ---

    @Test
    void clipDuplicateToSlot_missingSrcTrackIndex_returnsError() {
        String response = dispatcher.handle(rpc("clip/duplicateToSlot",
            "{\"srcSlotIndex\": 0, \"destTrackIndex\": 1, \"destSlotIndex\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "srcTrackIndex");
    }

    @Test
    void clipDuplicateToSlot_missingDestSlotIndex_returnsError() {
        String response = dispatcher.handle(rpc("clip/duplicateToSlot",
            "{\"srcTrackIndex\": 0, \"srcSlotIndex\": 0, \"destTrackIndex\": 1}"));
        assertContains(response, "-32602");
        assertContains(response, "destSlotIndex");
    }

    // --- clip/setLaunchQuantization validation ---

    @Test
    void setLaunchQuantization_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("clip/setLaunchQuantization", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "quantization");
    }

    @Test
    void setLaunchQuantization_invalidValue_returnsError() {
        String response = dispatcher.handle(rpc("clip/setLaunchQuantization", "{\"quantization\": \"invalid\"}"));
        assertContains(response, "-32602");
        assertContains(response, "invalid launch quantization");
    }

    // --- clip/setLaunchMode validation ---

    @Test
    void setLaunchMode_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("clip/setLaunchMode", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "launchMode");
    }

    @Test
    void setLaunchMode_invalidValue_returnsError() {
        String response = dispatcher.handle(rpc("clip/setLaunchMode", "{\"launchMode\": \"bad\"}"));
        assertContains(response, "-32602");
        assertContains(response, "invalid launch mode");
    }

    // --- clip/setAccent validation ---

    @Test
    void setAccent_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("clip/setAccent", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "value");
    }

    @Test
    void setAccent_outOfRange_returnsError() {
        String response = dispatcher.handle(rpc("clip/setAccent", "{\"value\": 1.5}"));
        assertContains(response, "-32602");
        assertContains(response, "between 0.0 and 1.0");
    }

    // --- clip/launch with options validation ---

    @Test
    void clipLaunch_partialOptions_returnsError() {
        String response = dispatcher.handle(rpc("clip/launch",
            "{\"trackIndex\": 0, \"slotIndex\": 0, \"quantization\": \"1\"}"));
        assertContains(response, "-32602");
        assertContains(response, "both be provided");
    }

    // --- clip/setColor validation ---

    @Test
    void setColor_missingTrackIndex_returnsError() {
        String response = dispatcher.handle(rpc("clip/setColor",
            "{\"slotIndex\": 0, \"r\": 0.5, \"g\": 0.5, \"b\": 0.5}"));
        assertContains(response, "-32602");
        assertContains(response, "trackIndex");
    }

    @Test
    void setColor_rOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("clip/setColor",
            "{\"trackIndex\": 0, \"slotIndex\": 0, \"r\": 1.5, \"g\": 0.5, \"b\": 0.5}"));
        assertContains(response, "-32602");
        assertContains(response, "between 0.0 and 1.0");
    }

    // --- clip/quantize validation ---

    @Test
    void quantize_missingAmount_returnsError() {
        String response = dispatcher.handle(rpc("clip/quantize", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "amount");
    }

    @Test
    void quantize_outOfRange_returnsError() {
        String response = dispatcher.handle(rpc("clip/quantize", "{\"amount\": 1.5}"));
        assertContains(response, "-32602");
        assertContains(response, "between 0.0 and 1.0");
    }

    // --- clip/transpose validation ---

    @Test
    void transpose_missingSemitones_returnsError() {
        String response = dispatcher.handle(rpc("clip/transpose", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "semitones");
    }

    // --- scene/launch validation ---

    @Test
    void sceneLaunch_indexOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("scene/launch", "{\"index\": " + firstInvalidSceneIndex() + "}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    @Test
    void sceneLaunch_partialOptions_returnsError() {
        String response = dispatcher.handle(rpc("scene/launch",
            "{\"index\": 0, \"quantization\": \"1\"}"));
        assertContains(response, "-32602");
        assertContains(response, "both be provided");
    }

    // --- clip/launch option validation ---

    @Test
    void clipLaunch_invalidQuantization_returnsError() {
        String response = dispatcher.handle(rpc("clip/launch",
            "{\"trackIndex\": 0, \"slotIndex\": 0, \"quantization\": \"invalid\", \"launchMode\": \"from_start\"}"));
        assertContains(response, "-32602");
        assertContains(response, "invalid launch quantization");
    }

    @Test
    void clipLaunch_invalidLaunchMode_returnsError() {
        String response = dispatcher.handle(rpc("clip/launch",
            "{\"trackIndex\": 0, \"slotIndex\": 0, \"quantization\": \"1\", \"launchMode\": \"invalid\"}"));
        assertContains(response, "-32602");
        assertContains(response, "invalid launch mode");
    }

    // --- Behavioral tests (Mockito) — Slot-level operations ---

    @Test
    void clipLaunch_simple_callsSlotBankLaunch() {
        dispatcher.handle(rpc("clip/launch", "{\"trackIndex\":0,\"slotIndex\":0}"));
        verify(mockSlotBank).launch(0);
    }

    @Test
    void clipLaunch_withOptions_callsSlotLaunchWithOptions() {
        dispatcher.handle(rpc("clip/launch",
            "{\"trackIndex\":0,\"slotIndex\":0,\"quantization\":\"1\",\"launchMode\":\"from_start\"}"));
        verify(mockSlot).launchWithOptions("1", "from_start");
    }

    @Test
    void clipStop_callsSlotBankStop() {
        dispatcher.handle(rpc("clip/stop", "{\"trackIndex\":0}"));
        verify(mockSlotBank).stop();
    }

    @Test
    void clipRecord_callsSlotBankRecord() {
        dispatcher.handle(rpc("clip/record", "{\"trackIndex\":0,\"slotIndex\":0}"));
        verify(mockSlotBank).record(0);
    }

    @Test
    void clipCreate_callsSlotBankCreateEmptyClip() {
        dispatcher.handle(rpc("clip/create", "{\"trackIndex\":0,\"slotIndex\":0,\"lengthInBeats\":4}"));
        verify(mockSlotBank).createEmptyClip(0, 4);
    }

    @Test
    void clipSelect_force_callsSlotSelect() {
        dispatcher.handle(rpc("clip/select", "{\"trackIndex\":0,\"slotIndex\":0,\"force\":true}"));
        verify(mockSlot).select();
    }

    @Test
    void clipDelete_callsSlotDeleteObject() {
        dispatcher.handle(rpc("clip/delete", "{\"trackIndex\":0,\"slotIndex\":0}"));
        verify(mockSlot).deleteObject();
    }

    @Test
    void clipDuplicate_callsSlotBankDuplicateClip() {
        dispatcher.handle(rpc("clip/duplicate", "{\"trackIndex\":0,\"slotIndex\":0}"));
        verify(mockSlotBank).duplicateClip(0);
    }

    @Test
    void clipDuplicateToSlot_callsReplaceInsertionPointCopySlotsOrScenes() {
        // Both src and dest use the same track (index 0) for simplicity
        when(mockSlotBank.getItemAt(1)).thenReturn(mockSlot);
        when(mockSlot.replaceInsertionPoint()).thenReturn(mockInsertionPoint);

        // Need separate source slot mock
        ClipLauncherSlot srcSlot = mock(ClipLauncherSlot.class);
        when(mockSlotBank.getItemAt(0)).thenReturn(srcSlot);

        dispatcher.handle(rpc("clip/duplicateToSlot",
            "{\"srcTrackIndex\":0,\"srcSlotIndex\":0,\"destTrackIndex\":0,\"destSlotIndex\":1}"));

        verify(mockSlot).replaceInsertionPoint();
        verify(mockInsertionPoint).copySlotsOrScenes(srcSlot);
    }

    @Test
    void clipShowInEditor_callsSlotShowInEditor() {
        dispatcher.handle(rpc("clip/showInEditor", "{\"trackIndex\":0,\"slotIndex\":0}"));
        verify(mockSlot).showInEditor();
    }

    @Test
    void clipLaunchAlt_callsSlotLaunchAlt() {
        dispatcher.handle(rpc("clip/launchAlt", "{\"trackIndex\":0,\"slotIndex\":0}"));
        verify(mockSlot).launchAlt();
    }

    @Test
    void clipLaunchRelease_callsSlotLaunchRelease() {
        dispatcher.handle(rpc("clip/launchRelease", "{\"trackIndex\":0,\"slotIndex\":0}"));
        verify(mockSlot).launchRelease();
    }

    @Test
    void clipLaunchReleaseAlt_callsSlotLaunchReleaseAlt() {
        dispatcher.handle(rpc("clip/launchReleaseAlt", "{\"trackIndex\":0,\"slotIndex\":0}"));
        verify(mockSlot).launchReleaseAlt();
    }

    @Test
    void clipSetColor_callsSlotColorSet() {
        when(mockSlot.color()).thenReturn(mockSlotColor);
        dispatcher.handle(rpc("clip/setColor",
            "{\"trackIndex\":0,\"slotIndex\":0,\"r\":0.5,\"g\":0.3,\"b\":0.8}"));
        verify(mockSlotColor).set(0.5f, 0.3f, 0.8f);
    }

    // --- Behavioral tests (Mockito) — Scene launch ---

    @Test
    void sceneLaunch_simple_callsSceneBankLaunchScene() {
        dispatcher.handle(rpc("scene/launch", "{\"index\":0}"));
        verify(mockSceneBank).launchScene(0);
    }

    @Test
    void sceneLaunch_withOptions_callsSceneLaunchWithOptions() {
        dispatcher.handle(rpc("scene/launch",
            "{\"index\":0,\"quantization\":\"1/4\",\"launchMode\":\"synced\"}"));
        verify(mockScene).launchWithOptions("1/4", "synced");
    }

    // --- Behavioral tests (Mockito) — Cursor clip operations ---

    @Test
    void clipRename_callsCursorClipSetName() {
        dispatcher.handle(rpc("clip/rename", "{\"name\":\"Verse\"}"));
        verify(mockCursorClip).setName("Verse");
    }

    @Test
    void clipSetLaunchQuantization_callsCursorClipLaunchQuantizationSet() {
        when(mockCursorClip.launchQuantization()).thenReturn(mockLaunchQuantization);
        dispatcher.handle(rpc("clip/setLaunchQuantization", "{\"quantization\":\"1/4\"}"));
        verify(mockLaunchQuantization).set("1/4");
    }

    @Test
    void clipSetLaunchMode_callsCursorClipLaunchModeSet() {
        when(mockCursorClip.launchMode()).thenReturn(mockLaunchMode);
        dispatcher.handle(rpc("clip/setLaunchMode", "{\"launchMode\":\"from_start\"}"));
        verify(mockLaunchMode).set("from_start");
    }

    @Test
    void clipSetShuffle_callsCursorClipGetShuffleSet() {
        when(mockCursorClip.getShuffle()).thenReturn(mockShuffle);
        dispatcher.handle(rpc("clip/setShuffle", "{\"enabled\":true}"));
        verify(mockShuffle).set(true);
    }

    @Test
    void clipSetAccent_callsCursorClipGetAccentSetImmediately() {
        when(mockCursorClip.getAccent()).thenReturn(mockAccent);
        dispatcher.handle(rpc("clip/setAccent", "{\"value\":0.75}"));
        verify(mockAccent).setImmediately(0.75);
    }

    @Test
    void clipSetUseLoopStartAsQuantRef_callsCursorClipSet() {
        when(mockCursorClip.useLoopStartAsQuantizationReference()).thenReturn(mockUseLoopStartAsQuantRef);
        dispatcher.handle(rpc("clip/setUseLoopStartAsQuantizationReference", "{\"enabled\":true}"));
        verify(mockUseLoopStartAsQuantRef).set(true);
    }

    @Test
    void clipSetPlayStart_callsCursorClipGetPlayStartSet() {
        when(mockCursorClip.getPlayStart()).thenReturn(mockPlayStart);
        dispatcher.handle(rpc("clip/setPlayStart", "{\"beats\":4.0}"));
        verify(mockPlayStart).set(4.0);
    }

    @Test
    void clipSetPlayStop_callsCursorClipGetPlayStopSet() {
        when(mockCursorClip.getPlayStop()).thenReturn(mockPlayStop);
        dispatcher.handle(rpc("clip/setPlayStop", "{\"beats\":16.0}"));
        verify(mockPlayStop).set(16.0);
    }

    @Test
    void clipSetLoopStart_callsCursorClipGetLoopStartSet() {
        when(mockCursorClip.getLoopStart()).thenReturn(mockLoopStart);
        dispatcher.handle(rpc("clip/setLoopStart", "{\"beats\":0.0}"));
        verify(mockLoopStart).set(0.0);
    }

    @Test
    void clipSetLoopLength_callsCursorClipGetLoopLengthSet() {
        when(mockCursorClip.getLoopLength()).thenReturn(mockLoopLength);
        dispatcher.handle(rpc("clip/setLoopLength", "{\"beats\":8.0}"));
        verify(mockLoopLength).set(8.0);
    }

    @Test
    void clipSetLoopEnabled_callsCursorClipIsLoopEnabledSet() {
        when(mockCursorClip.isLoopEnabled()).thenReturn(mockLoopEnabled);
        dispatcher.handle(rpc("clip/setLoopEnabled", "{\"enabled\":true}"));
        verify(mockLoopEnabled).set(true);
    }

    @Test
    void clipQuantize_callsCursorClipQuantize() {
        dispatcher.handle(rpc("clip/quantize", "{\"amount\":0.5}"));
        verify(mockCursorClip).quantize(0.5);
    }

    @Test
    void clipTranspose_callsCursorClipTranspose() {
        dispatcher.handle(rpc("clip/transpose", "{\"semitones\":7}"));
        verify(mockCursorClip).transpose(7);
    }

    @Test
    void clipDuplicateContent_callsCursorClipDuplicateContent() {
        dispatcher.handle(rpc("clip/duplicateContent", "{}"));
        verify(mockCursorClip).duplicateContent();
    }

    // --- Phase 26: clip/insertFile and clip/browseToInsert ---

    @TempDir
    Path clipDir;

    /**
     * A non-identity canonical mapping (bank slot 1 observed not existing, so public index 1
     * resolves to bank slot 2) with slot 0 of bank slot 2's track stubbed. Returns that slot.
     */
    private ClipLauncherSlot canonicalSlotUnderNonIdentityMapping(JsonRpcDispatcher local,
                                                                   Track[] slotOneTrackOut) {
        TrackBankManager manager = new TrackBankManager(mockTrackBank, 8);
        manager.observeCanonicalExists(0, true);
        manager.observeCanonicalExists(1, false);
        manager.observeCanonicalExists(2, true);

        Track slotOneTrack = mock(Track.class);
        Track slotTwoTrack = mock(Track.class);
        ClipLauncherSlotBank slotTwoBank = mock(ClipLauncherSlotBank.class);
        ClipLauncherSlot slotTwoSlot = mock(ClipLauncherSlot.class);
        when(mockTrackBank.getItemAt(1)).thenReturn(slotOneTrack);
        when(mockTrackBank.getItemAt(2)).thenReturn(slotTwoTrack);
        when(slotTwoTrack.clipLauncherSlotBank()).thenReturn(slotTwoBank);
        when(slotTwoBank.getItemAt(0)).thenReturn(slotTwoSlot);
        slotOneTrackOut[0] = slotOneTrack;

        new ClipHandler(manager, mockSceneBank, mockCursorClip, new StateCache()).register(local);
        return slotTwoSlot;
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\") + "\"";
    }

    @Test
    void clipInsertFile_existingBwclip_insertsAtTheCanonicalSlotsReplaceInsertionPoint()
            throws IOException {
        Path clip = Files.writeString(clipDir.resolve("fixture.bwclip"), "x");
        JsonRpcDispatcher local = new JsonRpcDispatcher();
        Track[] slotOneTrack = new Track[1];
        ClipLauncherSlot slot = canonicalSlotUnderNonIdentityMapping(local, slotOneTrack);
        InsertionPoint insertionPoint = mock(InsertionPoint.class);
        when(slot.replaceInsertionPoint()).thenReturn(insertionPoint);

        String response = local.handle(rpc("clip/insertFile",
            "{\"trackIndex\":1,\"slotIndex\":0,\"path\":" + jsonString(clip.toString()) + "}"));

        assertContains(response, "\"ok\"");
        verify(insertionPoint).insertFile(clip.toString());
        verify(slotOneTrack[0], never()).clipLauncherSlotBank();
    }

    @Test
    void clipInsertFile_relativePath_refusedBeforeInsertFile() {
        assertInsertFileRefused("clips\\fixture.bwclip", 0, "clip file path is not absolute: ");
    }

    @Test
    void clipInsertFile_twoBackslashPath_refusedAsNetworkPath() {
        assertInsertFileRefused("\\\\server\\share\\fixture.bwclip", 0,
            "clip file path is a network path: ");
    }

    @Test
    void clipInsertFile_twoForwardSlashPath_refusedAsNetworkPath() {
        assertInsertFileRefused("//server/share/fixture.bwclip", 0,
            "clip file path is a network path: ");
    }

    // --- CR-01 engine half (plan 26-15): Windows parses ANY two leading separators, in any mix,
    // as a UNC root, so the network test reads the parsed root, not a string prefix. The cases
    // mirror plan 26-12's CLIP_FILE_PATH_CASES on the Python and harness sides. ---

    @Test
    void clipInsertFile_backslashThenSlashUncPath_refusedAsNetworkPath() {
        assertInsertFileRefused("\\/srv/share/fixture.bwclip", 0,
            "clip file path is a network path: ");
    }

    @Test
    void clipInsertFile_slashThenBackslashUncPath_refusedAsNetworkPath() {
        assertInsertFileRefused("/\\srv\\share\\fixture.bwclip", 0,
            "clip file path is a network path: ");
    }

    @Test
    void clipInsertFile_questionMarkUncPrefix_refusedAsNetworkPath() {
        assertInsertFileRefused("\\\\?\\UNC\\srv\\s\\fixture.bwclip", 0,
            "clip file path is a network path: ");
    }

    @Test
    void clipInsertFile_dotUncPrefix_refusedAsNetworkPath() {
        assertInsertFileRefused("\\\\.\\UNC\\srv\\s\\fixture.bwclip", 0,
            "clip file path is a network path: ");
    }

    @Test
    void clipInsertFile_questionMarkLocalDevicePath_refusedAsNetworkPath() {
        assertInsertFileRefused("\\\\?\\C:\\clips\\fixture.bwclip", 0,
            "clip file path is a network path: ");
    }

    @Test
    void clipInsertFile_fileNamedOnlyTheExtension_refusedForItsExtension() throws IOException {
        Path onlyExtension = Files.writeString(clipDir.resolve(".bwclip"), "x");
        assertInsertFileRefused(onlyExtension.toString(), 0,
            "clip file path does not end in .bwclip: ");
    }

    @Test
    void clipInsertFile_forwardSlashDrivePath_insertsAtTheCanonicalSlot() throws IOException {
        Path clip = Files.writeString(clipDir.resolve("forward.bwclip"), "x");
        String forwardSlashPath = clip.toString().replace('\\', '/');
        when(mockSlot.replaceInsertionPoint()).thenReturn(mockInsertionPoint);

        String response = dispatcher.handle(rpc("clip/insertFile",
            "{\"trackIndex\":0,\"slotIndex\":0,\"path\":" + jsonString(forwardSlashPath) + "}"));

        assertContains(response, "\"ok\"");
        verify(mockInsertionPoint).insertFile(forwardSlashPath);
    }

    @Test
    void clipInsertFile_midPath_refusedForItsExtension() throws IOException {
        Path mid = Files.writeString(clipDir.resolve("fixture.mid"), "x");
        assertInsertFileRefused(mid.toString(), 0, "clip file path does not end in .bwclip: ");
    }

    @Test
    void clipInsertFile_missingFile_refusedAsNotAnExistingFile() {
        assertInsertFileRefused(clipDir.resolve("missing.bwclip").toString(), 0,
            "clip file path is not an existing file: ");
    }

    @Test
    void clipInsertFile_slotIndexSixteen_refusedOutOfRange() throws IOException {
        Path clip = Files.writeString(clipDir.resolve("range.bwclip"), "x");
        assertInsertFileRefused(clip.toString(), 16, "slot index out of range: 16");
    }

    @Test
    void clipBrowseToInsert_callsBrowseToInsertClipOnTheCanonicalSlot() {
        JsonRpcDispatcher local = new JsonRpcDispatcher();
        Track[] slotOneTrack = new Track[1];
        ClipLauncherSlot slot = canonicalSlotUnderNonIdentityMapping(local, slotOneTrack);

        String response = local.handle(rpc("clip/browseToInsert",
            "{\"trackIndex\":1,\"slotIndex\":0}"));

        assertContains(response, "\"ok\"");
        verify(slot).browseToInsertClip();
        verify(slotOneTrack[0], never()).clipLauncherSlotBank();
    }

    @Test
    void clipBrowseToInsert_slotIndexSixteen_refusedOutOfRange() {
        String response = dispatcher.handle(rpc("clip/browseToInsert",
            "{\"trackIndex\":0,\"slotIndex\":16}"));
        assertContains(response, "-32602");
        assertContains(response, "slot index out of range: 16");
        verify(mockSlot, never()).browseToInsertClip();
    }

    private void assertInsertFileRefused(String path, int slotIndex, String expectedMessage) {
        when(mockSlot.replaceInsertionPoint()).thenReturn(mockInsertionPoint);
        String response = dispatcher.handle(rpc("clip/insertFile",
            "{\"trackIndex\":0,\"slotIndex\":" + slotIndex + ",\"path\":" + jsonString(path) + "}"));
        assertContains(response, "-32602");
        assertContains(response, expectedMessage);
        verify(mockInsertionPoint, never()).insertFile(anyString());
    }

    // --- CR-03: one coordinate ---

    /**
     * THE TEST THAT WOULD HAVE CAUGHT CR-03. Under a non-identity canonical mapping -- bank slot
     * 1 observed not existing, so public index 1 resolves to bank slot 2 -- clip/launch with
     * trackIndex 1 must act on the track at bank slot 2. Before the fix this handler subscripted
     * the flat bank raw and would have launched a clip on slot 1's track while the snapshot
     * called slot 2's track index 1.
     */
    @Test
    void clipLaunch_underNonIdentityMapping_addressesTheCanonicalTrack() {
        TrackBankManager manager = new TrackBankManager(mockTrackBank, 8);
        manager.observeCanonicalExists(0, true);
        manager.observeCanonicalExists(1, false);
        manager.observeCanonicalExists(2, true);

        Track slotOneTrack = mock(Track.class);
        Track slotTwoTrack = mock(Track.class);
        ClipLauncherSlotBank slotTwoBank = mock(ClipLauncherSlotBank.class);
        when(mockTrackBank.getItemAt(1)).thenReturn(slotOneTrack);
        when(mockTrackBank.getItemAt(2)).thenReturn(slotTwoTrack);
        when(slotTwoTrack.clipLauncherSlotBank()).thenReturn(slotTwoBank);

        JsonRpcDispatcher local = new JsonRpcDispatcher();
        new ClipHandler(manager, mockSceneBank, mockCursorClip, new StateCache())
            .register(local);

        local.handle(rpc("clip/launch", "{\"trackIndex\":1,\"slotIndex\":0}"));

        verify(slotTwoBank).launch(0);
        verify(slotOneTrack, never()).clipLauncherSlotBank();
    }

    // --- Helpers ---

    private String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}";
    }

    private void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected),
            "Expected '" + expected + "' in: " + actual);
    }
}
