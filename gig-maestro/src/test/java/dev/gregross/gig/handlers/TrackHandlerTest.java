package dev.gregross.gig.handlers;

import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableColorValue;
import com.bitwig.extension.controller.api.SettableEnumValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.controller.api.SettableStringValue;
import com.bitwig.extension.controller.api.SoloValue;
import com.bitwig.extension.controller.api.StringValue;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrackHandlerTest {

    @Mock private TrackBank mockTrackBank;
    @Mock private Application mockApplication;
    @Mock private CursorTrack mockCursorTrack;
    @Mock private TrackBankManager mockTrackBankManager;
    @Mock private NoteInput mockNoteInput;
    @Mock private Track mockTrack;

    // Chain mocks
    @Mock private Parameter mockVolumeParam;
    @Mock private Parameter mockPanParam;
    @Mock private SettableRangedValue mockVolumeValue;
    @Mock private SettableRangedValue mockPanValue;
    @Mock private SettableBooleanValue mockMuteValue;
    @Mock private SoloValue mockSoloValue;
    @Mock private SettableBooleanValue mockArmValue;
    @Mock private SettableColorValue mockColorValue;
    @Mock private SettableEnumValue mockCrossfadeMode;
    @Mock private SettableEnumValue mockMonitorMode;
    @Mock private SettableStringValue mockNameValue;
    @Mock private SettableBooleanValue mockGroupExpanded;
    @Mock private SettableBooleanValue mockIsPinned;
    @Mock private StringValue mockTrackType;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // Stub cursor properties needed by constructor markInterested() calls
        when(mockCursorTrack.isPinned()).thenReturn(mockIsPinned);
        when(mockCursorTrack.trackType()).thenReturn(mockTrackType);

        dispatcher = new JsonRpcDispatcher();
        new TrackHandler(mockTrackBank, mockApplication, mockCursorTrack,
            mockTrackBankManager, new StateCache(), mockNoteInput).register(dispatcher);

        // Common stub: trackBank returns track at index 0
        when(mockTrackBank.getSizeOfBank()).thenReturn(8);
        when(mockTrackBank.getItemAt(0)).thenReturn(mockTrack);

        // Common stub: cursorTrack.name() for cursorResponse()
        when(mockCursorTrack.name()).thenReturn(mockNameValue);
        when(mockNameValue.get()).thenReturn("TestTrack");
    }

    // --- Registration ---

    @Test
    void registersTrackMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("track/setVolume"));
        assertTrue(methods.contains("track/setPan"));
        assertTrue(methods.contains("track/setMute"));
        assertTrue(methods.contains("track/setSolo"));
        assertTrue(methods.contains("track/setArm"));
        assertTrue(methods.contains("track/createAudio"));
        assertTrue(methods.contains("track/createInstrument"));
        assertTrue(methods.contains("track/createEffect"));
        assertTrue(methods.contains("track/select"));
        assertTrue(methods.contains("track/rename"));
        assertTrue(methods.contains("track/deleteSelected"));
        assertTrue(methods.contains("track/duplicate"));
    }

    @Test
    void registersThreeScrollMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("trackBank/scrollTo"));
        assertTrue(methods.contains("trackBank/scrollBy"));
        assertTrue(methods.contains("trackBank/getScrollInfo"));
    }

    @Test
    void registersMixerMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("track/setColor"));
        assertTrue(methods.contains("track/setCrossfade"));
        assertTrue(methods.contains("track/setMonitor"));
    }

    @Test
    void registersGroupAndRoutingMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("track/setGroupExpanded"));
        assertTrue(methods.contains("track/navigateInto"));
        assertTrue(methods.contains("track/navigateToParent"));
        assertTrue(methods.contains("track/createGroup"));
        assertTrue(methods.contains("track/addNoteSource"));
        assertTrue(methods.contains("track/removeNoteSource"));
    }

    @Test
    void registersToggleSoloMethod() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("track/toggleSolo"));
    }

    @Test
    void registersMixerNavigationMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("track/selectInMixer"));
        assertTrue(methods.contains("track/makeVisibleInMixer"));
    }

    @Test
    void registersCursorNavigationMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("cursor/selectParent"));
        assertTrue(methods.contains("cursor/selectFirstChild"));
        assertTrue(methods.contains("cursor/setPinned"));
        assertTrue(methods.contains("cursor/getInfo"));
    }

    @Test
    void registersActivityFeedbackMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("track/getVuMeters"));
        assertTrue(methods.contains("track/getPlayingNotes"));
    }

    @Test
    void registersExactlyThirtyFourMethods() {
        assertEquals(34, dispatcher.getRegisteredMethods().size());
    }

    // --- trackBank/scrollTo validation ---

    @Test
    void trackBankScrollTo_missingPosition_returnsError() {
        String response = dispatcher.handle(rpc("trackBank/scrollTo", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "position");
    }

    @Test
    void trackBankScrollTo_negativePosition_returnsOutOfRange() {
        String response = dispatcher.handle(rpc("trackBank/scrollTo", "{\"position\": -1}"));
        assertContains(response, "-32001");
        assertContains(response, "POSITION_OUT_OF_RANGE");
    }

    @Test
    void trackBankScrollTo_positionBeyondItemCount_returnsOutOfRange() {
        // StateCache defaults to itemCount=0, so position 0 is out of range
        String response = dispatcher.handle(rpc("trackBank/scrollTo", "{\"position\": 0}"));
        assertContains(response, "-32001");
        assertContains(response, "itemCount");
        assertContains(response, "requestedPosition");
    }

    // --- trackBank/scrollBy validation ---

    @Test
    void trackBankScrollBy_missingAmount_returnsError() {
        String response = dispatcher.handle(rpc("trackBank/scrollBy", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "amount");
    }

    // --- trackBank/getScrollInfo ---

    @Test
    void trackBankGetScrollInfo_returnsAllFields() {
        String response = dispatcher.handle(rpc("trackBank/getScrollInfo", "{}"));
        assertContains(response, "scrollPosition");
        assertContains(response, "itemCount");
        assertContains(response, "bankSize");
        assertContains(response, "canScrollForwards");
        assertContains(response, "canScrollBackwards");
    }

    // --- track/setGroupExpanded validation ---

    @Test
    void setGroupExpanded_missingBothParams_returnsError() {
        String response = dispatcher.handle(rpc("track/setGroupExpanded", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "must provide");
    }

    @Test
    void setGroupExpanded_bothParams_returnsError() {
        String response = dispatcher.handle(rpc("track/setGroupExpanded", "{\"expanded\":true,\"toggle\":true}"));
        assertContains(response, "-32602");
        assertContains(response, "mutually exclusive");
    }

    // --- track/select validation ---

    @Test
    void select_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("track/select", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    // --- track/setCrossfade validation ---

    @Test
    void setCrossfade_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("track/setCrossfade", "{\"mode\":\"A\"}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    // --- track/setMonitor validation ---

    @Test
    void setMonitor_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("track/setMonitor", "{\"mode\":\"ON\"}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    // --- Behavioral tests (Mockito) — 3-level chains ---

    @Test
    void setVolume_callsTrackVolume() {
        when(mockTrack.volume()).thenReturn(mockVolumeParam);
        when(mockVolumeParam.value()).thenReturn(mockVolumeValue);

        dispatcher.handle(rpc("track/setVolume", "{\"index\":0,\"value\":0.75}"));

        verify(mockVolumeValue).setImmediately(0.75);
    }

    @Test
    void setPan_callsTrackPan() {
        when(mockTrack.pan()).thenReturn(mockPanParam);
        when(mockPanParam.value()).thenReturn(mockPanValue);

        dispatcher.handle(rpc("track/setPan", "{\"index\":0,\"value\":0.5}"));

        verify(mockPanValue).setImmediately(0.5);
    }

    // --- Behavioral tests — 2-level chains ---

    @Test
    void setMute_callsTrackMute() {
        when(mockTrack.mute()).thenReturn(mockMuteValue);

        dispatcher.handle(rpc("track/setMute", "{\"index\":0,\"muted\":true}"));

        verify(mockMuteValue).set(true);
    }

    @Test
    void setSolo_callsTrackSolo() {
        when(mockTrack.solo()).thenReturn(mockSoloValue);

        dispatcher.handle(rpc("track/setSolo", "{\"index\":0,\"soloed\":true}"));

        verify(mockSoloValue).set(true);
    }

    @Test
    void setArm_callsTrackArm() {
        when(mockTrack.arm()).thenReturn(mockArmValue);

        dispatcher.handle(rpc("track/setArm", "{\"index\":0,\"armed\":true}"));

        verify(mockArmValue).set(true);
    }

    @Test
    void setColor_callsTrackColor() {
        when(mockTrack.color()).thenReturn(mockColorValue);

        dispatcher.handle(rpc("track/setColor", "{\"index\":0,\"r\":0.5,\"g\":0.3,\"b\":0.8}"));

        verify(mockColorValue).set(0.5f, 0.3f, 0.8f);
    }

    @Test
    void setCrossfade_callsTrackCrossfadeMode() {
        when(mockTrack.crossFadeMode()).thenReturn(mockCrossfadeMode);

        dispatcher.handle(rpc("track/setCrossfade", "{\"index\":0,\"mode\":\"A\"}"));

        verify(mockCrossfadeMode).set("A");
    }

    // --- Behavioral tests — 1-level Application calls ---

    @Test
    void createAudio_callsApplicationCreateAudioTrack() {
        dispatcher.handle(rpc("track/createAudio", "{}"));

        verify(mockApplication).createAudioTrack(-1);
    }

    @Test
    void createInstrument_callsApplicationCreateInstrumentTrack() {
        dispatcher.handle(rpc("track/createInstrument", "{}"));

        verify(mockApplication).createInstrumentTrack(-1);
    }

    @Test
    void createEffect_callsApplicationCreateEffectTrack() {
        dispatcher.handle(rpc("track/createEffect", "{}"));

        verify(mockApplication).createEffectTrack(-1);
    }

    // --- Behavioral tests — 1-level CursorTrack calls ---

    @Test
    void rename_callsCursorTrackNameSet() {
        dispatcher.handle(rpc("track/rename", "{\"name\":\"NewName\"}"));

        verify(mockNameValue).set("NewName");
    }

    @Test
    void deleteSelected_callsCursorTrackDeleteObject() {
        dispatcher.handle(rpc("track/deleteSelected", "{}"));

        verify(mockCursorTrack).deleteObject();
    }

    @Test
    void duplicate_callsCursorTrackDuplicate() {
        dispatcher.handle(rpc("track/duplicate", "{}"));

        verify(mockCursorTrack).duplicate();
    }

    /**
     * THIS TEST WAS INVERTED, DELIBERATELY, AND THE OLD ASSERTION IS RECORDED HERE RATHER THAN
     * DELETED. It read:
     *
     * <pre>
     *     dispatcher.handle(rpc("track/createGroup", "{}"));
     *     verify(mockCursorTrack).createParentTrack(4, sceneCountOf(TrackHandler.class));
     * </pre>
     *
     * <p>It passed for the whole life of the method and proved nothing that mattered. Against a
     * MOCK {@code CursorTrack}, {@code createParentTrack} is just a recorded interaction; against
     * the real host it is an OBJECT-PROXY FACTORY whose complete javadoc reads "Creates an object
     * that represent the parent track", may only be called during driver initialization, and has
     * never made a group track at any pin, on any platform (finding O-33). A green test asserting
     * that we call it was a green test asserting we do the wrong thing correctly -- which is the
     * silent-wrong-output class this project exists against.
     *
     * <p>Decided at plan 06-08's Task 3 checkpoint (option {@code refuse-with-message}).
     */
    @Test
    void createGroup_refusesAndNamesWhatTheApiActuallyOffers() {
        String response = dispatcher.handle(rpc("track/createGroup", "{}"));

        assertContains(response, "-32001");
        assertContains(response, "GROUP_CREATION_NOT_SUPPORTED_BY_API");
        // The refusal must carry the platform limit and the route that does work, not a bare
        // apology -- that is the entire difference between this and the -32603 it replaces.
        assertContains(response, "Create Group Track");
        assertContains(response, "group-track creation method");

        verify(mockCursorTrack, never()).createParentTrack(anyInt(), anyInt());
    }

    @Test
    void setGroupExpanded_withExpanded_callsIsGroupExpandedSet() {
        when(mockCursorTrack.isGroupExpanded()).thenReturn(mockGroupExpanded);

        dispatcher.handle(rpc("track/setGroupExpanded", "{\"expanded\":true}"));

        verify(mockGroupExpanded).set(true);
    }

    // --- Behavioral tests — monitor mode ---

    @Test
    void setMonitor_callsTrackMonitorMode() {
        when(mockTrack.monitorMode()).thenReturn(mockMonitorMode);

        dispatcher.handle(rpc("track/setMonitor", "{\"index\":0,\"mode\":\"AUTO\"}"));

        verify(mockMonitorMode).set("AUTO");
    }

    // --- Behavioral tests — activity feedback ---

    @Test
    void getVuMeters_returnsArray() {
        String response = dispatcher.handle(rpc("track/getVuMeters", "{}"));
        assertContains(response, "[");
    }

    @Test
    void getPlayingNotes_returnsArray() {
        String response = dispatcher.handle(rpc("track/getPlayingNotes", "{\"index\":0}"));
        assertContains(response, "[");
    }

    // --- Behavioral tests — cursor navigation ---

    @Test
    void selectParent_callsCursorTrackSelectParent() {
        dispatcher.handle(rpc("cursor/selectParent", "{}"));

        verify(mockCursorTrack).selectParent();
    }

    @Test
    void selectFirstChild_callsCursorTrackSelectFirstChild() {
        dispatcher.handle(rpc("cursor/selectFirstChild", "{}"));

        verify(mockCursorTrack).selectFirstChild();
    }

    @Test
    void setPinned_true_callsCursorTrackIsPinnedSet() {
        dispatcher.handle(rpc("cursor/setPinned", "{\"pinned\":true}"));

        verify(mockIsPinned).set(true);
    }

    @Test
    void setPinned_false_callsCursorTrackIsPinnedSet() {
        dispatcher.handle(rpc("cursor/setPinned", "{\"pinned\":false}"));

        verify(mockIsPinned).set(false);
    }

    @Test
    void setPinned_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("cursor/setPinned", "{}"));
        assertContains(response, "pinned");
    }

    @Test
    void getInfo_returnsExpectedFields() {
        when(mockNameValue.get()).thenReturn("Lead Synth");
        when(mockTrackType.get()).thenReturn("Instrument");
        when(mockIsPinned.get()).thenReturn(false);

        String response = dispatcher.handle(rpc("cursor/getInfo", "{}"));
        assertContains(response, "\"name\":\"Lead Synth\"");
        assertContains(response, "\"trackType\":\"Instrument\"");
        assertContains(response, "\"isPinned\":false");
    }

    // --- Behavioral tests — mixer navigation ---

    @Test
    void selectInMixer_callsTrackSelectInMixer() {
        dispatcher.handle(rpc("track/selectInMixer", "{\"index\":0}"));

        verify(mockTrack).selectInMixer();
    }

    @Test
    void makeVisibleInMixer_callsTrackMakeVisibleInMixer() {
        dispatcher.handle(rpc("track/makeVisibleInMixer", "{\"index\":0}"));

        verify(mockTrack).makeVisibleInMixer();
    }

    // --- Behavioral tests — toggleSolo ---

    @Test
    void toggleSolo_defaultNonExclusive() {
        when(mockTrack.solo()).thenReturn(mockSoloValue);

        dispatcher.handle(rpc("track/toggleSolo", "{\"index\":0}"));

        verify(mockSoloValue).toggle(false);
    }

    @Test
    void toggleSolo_exclusiveTrue() {
        when(mockTrack.solo()).thenReturn(mockSoloValue);

        dispatcher.handle(rpc("track/toggleSolo", "{\"index\":0,\"exclusive\":true}"));

        verify(mockSoloValue).toggle(true);
    }

    @Test
    void toggleSolo_exclusiveFalse() {
        when(mockTrack.solo()).thenReturn(mockSoloValue);

        dispatcher.handle(rpc("track/toggleSolo", "{\"index\":0,\"exclusive\":false}"));

        verify(mockSoloValue).toggle(false);
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
