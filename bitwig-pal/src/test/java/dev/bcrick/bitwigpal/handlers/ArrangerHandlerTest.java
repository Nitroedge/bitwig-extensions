package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Arranger;
import com.bitwig.extension.controller.api.CueMarker;
import com.bitwig.extension.controller.api.CueMarkerBank;
import com.bitwig.extension.controller.api.ScrollbarModel;
import com.bitwig.extension.controller.api.SettableBeatTimeValue;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableIntegerValue;
import com.bitwig.extension.controller.api.SettableStringValue;
import com.bitwig.extension.controller.api.Transport;
import dev.bcrick.bitwigpal.extension.StateCache;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
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
class ArrangerHandlerTest {

    @Mock private Arranger mockArranger;
    @Mock private Transport mockTransport;
    @Mock private CueMarkerBank mockCueMarkerBank;
    @Mock private ScrollbarModel mockScrollbar;
    @Mock private CueMarker mockCueMarker;

    // Arranger 2-level chain mocks
    @Mock private SettableBooleanValue mockPlaybackFollow;
    @Mock private SettableBooleanValue mockClipLauncherVisible;
    @Mock private SettableBooleanValue mockTimelineVisible;
    @Mock private SettableBooleanValue mockCueMarkersVisible;
    @Mock private SettableBooleanValue mockEffectTracksVisible;
    @Mock private SettableBooleanValue mockIoSectionVisible;
    @Mock private SettableBooleanValue mockDoubleRowTrackHeight;

    // CueMarker chain mocks
    @Mock private SettableStringValue mockCueMarkerName;
    @Mock private SettableBeatTimeValue mockCueMarkerPosition;

    // CueMarkerBank scroll mock
    @Mock private SettableIntegerValue mockScrollPosition;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new ArrangerHandler(mockArranger, mockTransport, mockCueMarkerBank, mockScrollbar, new StateCache()).register(dispatcher);

        // Common stub: cueMarkerBank returns cueMarker at index 0
        when(mockCueMarkerBank.getItemAt(0)).thenReturn(mockCueMarker);
    }

    // --- Registration ---

    @Test
    void registersAllSevenVisibilityMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("arranger/setPlaybackFollow"));
        assertTrue(methods.contains("arranger/setClipLauncherVisible"));
        assertTrue(methods.contains("arranger/setTimelineVisible"));
        assertTrue(methods.contains("arranger/setCueMarkersVisible"));
        assertTrue(methods.contains("arranger/setEffectTracksVisible"));
        assertTrue(methods.contains("arranger/setIoSectionVisible"));
        assertTrue(methods.contains("arranger/setDoubleRowTrackHeight"));
    }

    @Test
    void registersSevenCueMarkerMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("cueMarker/addAtPlayhead"));
        assertTrue(methods.contains("cueMarker/list"));
        assertTrue(methods.contains("cueMarker/launch"));
        assertTrue(methods.contains("cueMarker/delete"));
        assertTrue(methods.contains("cueMarker/rename"));
        assertTrue(methods.contains("cueMarker/setPosition"));
        assertTrue(methods.contains("cueMarker/duplicate"));
    }

    @Test
    void registersLaneZoomAndTimelineNavMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("arranger/zoomInLanes"));
        assertTrue(methods.contains("arranger/zoomOutLanes"));
        assertTrue(methods.contains("arranger/zoomInSelectedLanes"));
        assertTrue(methods.contains("arranger/zoomOutSelectedLanes"));
        assertTrue(methods.contains("arranger/zoomToRegion"));
        assertTrue(methods.contains("arranger/zoomToFitSelectionOrAll"));
    }

    @Test
    void registersExactlyTwentyThreeMethods() {
        assertEquals(23, dispatcher.getRegisteredMethods().size());
    }

    // --- Visibility toggle validation ---

    @Test
    void setPlaybackFollow_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("arranger/setPlaybackFollow", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    @Test
    void setClipLauncherVisible_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("arranger/setClipLauncherVisible", "{}"));
        assertContains(response, "-32602");
    }

    @Test
    void setTimelineVisible_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("arranger/setTimelineVisible", "{}"));
        assertContains(response, "-32602");
    }

    @Test
    void setCueMarkersVisible_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("arranger/setCueMarkersVisible", "{}"));
        assertContains(response, "-32602");
    }

    @Test
    void setEffectTracksVisible_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("arranger/setEffectTracksVisible", "{}"));
        assertContains(response, "-32602");
    }

    @Test
    void setIoSectionVisible_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("arranger/setIoSectionVisible", "{}"));
        assertContains(response, "-32602");
    }

    @Test
    void setDoubleRowTrackHeight_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("arranger/setDoubleRowTrackHeight", "{}"));
        assertContains(response, "-32602");
    }

    // --- Cue marker validation ---

    @Test
    void cueMarkerLaunch_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/launch", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void cueMarkerLaunch_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/launch", "{\"index\": -1}"));
        assertContains(response, "-32602");
    }

    @Test
    void cueMarkerLaunch_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/launch", "{\"index\": 16}"));
        assertContains(response, "-32602");
    }

    @Test
    void cueMarkerDelete_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/delete", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void cueMarkerDelete_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/delete", "{\"index\": -1}"));
        assertContains(response, "-32602");
    }

    @Test
    void cueMarkerDelete_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/delete", "{\"index\": 16}"));
        assertContains(response, "-32602");
    }

    // --- Cue marker rename validation ---

    @Test
    void cueMarkerRename_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/rename", "{\"name\": \"Test\"}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void cueMarkerRename_missingName_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/rename", "{\"index\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "name");
    }

    @Test
    void cueMarkerRename_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/rename", "{\"index\": -1, \"name\": \"Test\"}"));
        assertContains(response, "-32602");
    }

    @Test
    void cueMarkerRename_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/rename", "{\"index\": 16, \"name\": \"Test\"}"));
        assertContains(response, "-32602");
    }

    // --- Cue marker setPosition validation ---

    @Test
    void cueMarkerSetPosition_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/setPosition", "{\"beats\": 4.0}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void cueMarkerSetPosition_missingBeats_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/setPosition", "{\"index\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "beats");
    }

    @Test
    void cueMarkerSetPosition_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/setPosition", "{\"index\": -1, \"beats\": 4.0}"));
        assertContains(response, "-32602");
    }

    @Test
    void cueMarkerSetPosition_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/setPosition", "{\"index\": 16, \"beats\": 4.0}"));
        assertContains(response, "-32602");
    }

    // --- Cue marker duplicate validation ---

    @Test
    void cueMarkerDuplicate_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/duplicate", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void cueMarkerDuplicate_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/duplicate", "{\"index\": -1}"));
        assertContains(response, "-32602");
    }

    @Test
    void cueMarkerDuplicate_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("cueMarker/duplicate", "{\"index\": 16}"));
        assertContains(response, "-32602");
    }

    // --- cueMarkerBank scroll registration ---

    @Test
    void registersThreeCueMarkerBankScrollMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("cueMarkerBank/scrollTo"));
        assertTrue(methods.contains("cueMarkerBank/scrollBy"));
        assertTrue(methods.contains("cueMarkerBank/getScrollInfo"));
    }

    // --- cueMarkerBank/scrollTo validation ---

    @Test
    void cueMarkerBankScrollTo_missingPosition_returnsError() {
        String response = dispatcher.handle(rpc("cueMarkerBank/scrollTo", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "position");
    }

    @Test
    void cueMarkerBankScrollTo_negativePosition_returnsOutOfRange() {
        String response = dispatcher.handle(rpc("cueMarkerBank/scrollTo", "{\"position\": -1}"));
        assertContains(response, "-32001");
        assertContains(response, "POSITION_OUT_OF_RANGE");
    }

    @Test
    void cueMarkerBankScrollTo_positionBeyondItemCount_returnsOutOfRange() {
        String response = dispatcher.handle(rpc("cueMarkerBank/scrollTo", "{\"position\": 0}"));
        assertContains(response, "-32001");
        assertContains(response, "itemCount");
        assertContains(response, "requestedPosition");
    }

    // --- cueMarkerBank/scrollBy validation ---

    @Test
    void cueMarkerBankScrollBy_missingAmount_returnsError() {
        String response = dispatcher.handle(rpc("cueMarkerBank/scrollBy", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "amount");
    }

    // --- cueMarkerBank/getScrollInfo ---

    @Test
    void cueMarkerBankGetScrollInfo_returnsAllFields() {
        String response = dispatcher.handle(rpc("cueMarkerBank/getScrollInfo", "{}"));
        assertContains(response, "scrollPosition");
        assertContains(response, "itemCount");
        assertContains(response, "bankSize");
        assertContains(response, "canScrollForwards");
        assertContains(response, "canScrollBackwards");
    }

    // --- Behavioral tests (Mockito) — Arranger setters ---

    @Test
    void setPlaybackFollow_callsArrangerSet() {
        when(mockArranger.isPlaybackFollowEnabled()).thenReturn(mockPlaybackFollow);
        dispatcher.handle(rpc("arranger/setPlaybackFollow", "{\"enabled\":true}"));
        verify(mockPlaybackFollow).set(true);
    }

    @Test
    void setClipLauncherVisible_callsArrangerSet() {
        when(mockArranger.isClipLauncherVisible()).thenReturn(mockClipLauncherVisible);
        dispatcher.handle(rpc("arranger/setClipLauncherVisible", "{\"enabled\":true}"));
        verify(mockClipLauncherVisible).set(true);
    }

    @Test
    void setTimelineVisible_callsArrangerSet() {
        when(mockArranger.isTimelineVisible()).thenReturn(mockTimelineVisible);
        dispatcher.handle(rpc("arranger/setTimelineVisible", "{\"enabled\":false}"));
        verify(mockTimelineVisible).set(false);
    }

    @Test
    void setCueMarkersVisible_callsArrangerSet() {
        when(mockArranger.areCueMarkersVisible()).thenReturn(mockCueMarkersVisible);
        dispatcher.handle(rpc("arranger/setCueMarkersVisible", "{\"enabled\":true}"));
        verify(mockCueMarkersVisible).set(true);
    }

    @Test
    void setEffectTracksVisible_callsArrangerSet() {
        when(mockArranger.areEffectTracksVisible()).thenReturn(mockEffectTracksVisible);
        dispatcher.handle(rpc("arranger/setEffectTracksVisible", "{\"enabled\":true}"));
        verify(mockEffectTracksVisible).set(true);
    }

    @Test
    void setIoSectionVisible_callsArrangerSet() {
        when(mockArranger.isIoSectionVisible()).thenReturn(mockIoSectionVisible);
        dispatcher.handle(rpc("arranger/setIoSectionVisible", "{\"enabled\":true}"));
        verify(mockIoSectionVisible).set(true);
    }

    @Test
    void setDoubleRowTrackHeight_callsArrangerSet() {
        when(mockArranger.hasDoubleRowTrackHeight()).thenReturn(mockDoubleRowTrackHeight);
        dispatcher.handle(rpc("arranger/setDoubleRowTrackHeight", "{\"enabled\":true}"));
        verify(mockDoubleRowTrackHeight).set(true);
    }

    // --- Behavioral tests (Mockito) — Cue marker operations ---

    @Test
    void addAtPlayhead_callsTransportAddCueMarker() {
        dispatcher.handle(rpc("cueMarker/addAtPlayhead", "{}"));
        verify(mockTransport).addCueMarkerAtPlaybackPosition();
    }

    @Test
    void cueMarkerLaunch_callsCueMarkerLaunch() {
        dispatcher.handle(rpc("cueMarker/launch", "{\"index\":0,\"quantized\":true}"));
        verify(mockCueMarker).launch(true);
    }

    @Test
    void cueMarkerRename_callsCueMarkerNameSet() {
        when(mockCueMarker.name()).thenReturn(mockCueMarkerName);
        dispatcher.handle(rpc("cueMarker/rename", "{\"index\":0,\"name\":\"Chorus\"}"));
        verify(mockCueMarkerName).set("Chorus");
    }

    @Test
    void cueMarkerSetPosition_callsCueMarkerPositionSet() {
        when(mockCueMarker.position()).thenReturn(mockCueMarkerPosition);
        dispatcher.handle(rpc("cueMarker/setPosition", "{\"index\":0,\"beats\":8.0}"));
        verify(mockCueMarkerPosition).set(8.0);
    }

    @Test
    void cueMarkerDuplicate_callsCueMarkerDuplicateObject() {
        dispatcher.handle(rpc("cueMarker/duplicate", "{\"index\":0}"));
        verify(mockCueMarker).duplicateObject();
    }

    @Test
    void cueMarkerDelete_callsCueMarkerDeleteObject() {
        dispatcher.handle(rpc("cueMarker/delete", "{\"index\":0}"));
        verify(mockCueMarker).deleteObject();
    }

    // --- Behavioral tests (Mockito) — CueMarkerBank scroll ---

    @Test
    void cueMarkerBankScrollBy_callsCueMarkerBankScrollBy() {
        dispatcher.handle(rpc("cueMarkerBank/scrollBy", "{\"amount\":4}"));
        verify(mockCueMarkerBank).scrollBy(4);
    }

    // --- Lane zoom behavioral tests ---

    @Test
    void zoomInLanes_callsArrangerZoomInLaneHeightsAll() {
        dispatcher.handle(rpc("arranger/zoomInLanes", "{}"));
        verify(mockArranger).zoomInLaneHeightsAll();
    }

    @Test
    void zoomOutLanes_callsArrangerZoomOutLaneHeightsAll() {
        dispatcher.handle(rpc("arranger/zoomOutLanes", "{}"));
        verify(mockArranger).zoomOutLaneHeightsAll();
    }

    @Test
    void zoomInSelectedLanes_callsArrangerZoomInLaneHeightsSelected() {
        dispatcher.handle(rpc("arranger/zoomInSelectedLanes", "{}"));
        verify(mockArranger).zoomInLaneHeightsSelected();
    }

    @Test
    void zoomOutSelectedLanes_callsArrangerZoomOutLaneHeightsSelected() {
        dispatcher.handle(rpc("arranger/zoomOutSelectedLanes", "{}"));
        verify(mockArranger).zoomOutLaneHeightsSelected();
    }

    // --- Timeline navigation behavioral tests ---

    @Test
    void zoomToRegion_callsScrollbarZoomToContentRegion() {
        dispatcher.handle(rpc("arranger/zoomToRegion", "{\"from\":16.0,\"to\":32.0}"));
        verify(mockScrollbar).zoomToContentRegion(16.0, 32.0);
    }

    @Test
    void zoomToRegion_missingFrom_returnsError() {
        String response = dispatcher.handle(rpc("arranger/zoomToRegion", "{\"to\":32.0}"));
        assertContains(response, "from");
    }

    @Test
    void zoomToRegion_missingTo_returnsError() {
        String response = dispatcher.handle(rpc("arranger/zoomToRegion", "{\"from\":16.0}"));
        assertContains(response, "to");
    }

    @Test
    void zoomToFitSelectionOrAll_callsScrollbar() {
        dispatcher.handle(rpc("arranger/zoomToFitSelectionOrAll", "{}"));
        verify(mockScrollbar).zoomToFitSelectionOrAll();
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
