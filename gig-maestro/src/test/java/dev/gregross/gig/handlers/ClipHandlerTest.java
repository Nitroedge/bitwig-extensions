package dev.gregross.gig.handlers;

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
import dev.gregross.gig.extension.StateCache;
import dev.gregross.gig.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static dev.gregross.gig.extension.StateCacheTestHelper.sceneCountOf;
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
        new ClipHandler(mockTrackBank, mockSceneBank, mockCursorClip, new StateCache()).register(dispatcher);

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
    }

    @Test
    void registersExactlyThirtyMethods() {
        // 10 original + 6 launch settings + 14 grid enhancements
        assertEquals(30, dispatcher.getRegisteredMethods().size());
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

    // --- Helpers ---

    private String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}";
    }

    private void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected),
            "Expected '" + expected + "' in: " + actual);
    }
}
