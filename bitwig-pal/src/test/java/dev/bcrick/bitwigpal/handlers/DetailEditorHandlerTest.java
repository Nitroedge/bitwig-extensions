package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.DetailEditor;
import com.bitwig.extension.controller.api.ScrollbarModel;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetailEditorHandlerTest {

    @Mock private DetailEditor mockDetailEditor;
    @Mock private ScrollbarModel mockScrollbar;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(mockDetailEditor.getHorizontalScrollbarModel()).thenReturn(mockScrollbar);
        dispatcher = new JsonRpcDispatcher();
        new DetailEditorHandler(mockDetailEditor).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersEightMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("detailEditor/zoomIn"));
        assertTrue(methods.contains("detailEditor/zoomOut"));
        assertTrue(methods.contains("detailEditor/zoomToFit"));
        assertTrue(methods.contains("detailEditor/zoomToSelection"));
        assertTrue(methods.contains("detailEditor/zoomToFitSelectionOrAll"));
        assertTrue(methods.contains("detailEditor/zoomInLanes"));
        assertTrue(methods.contains("detailEditor/zoomOutLanes"));
        assertTrue(methods.contains("detailEditor/zoomToRegion"));
        assertEquals(8, methods.size());
    }

    // --- Timeline zoom behavioral tests ---

    @Test
    void zoomIn_callsDetailEditorZoomIn() {
        dispatcher.handle(rpc("detailEditor/zoomIn", "{}"));
        verify(mockDetailEditor).zoomIn();
    }

    @Test
    void zoomOut_callsDetailEditorZoomOut() {
        dispatcher.handle(rpc("detailEditor/zoomOut", "{}"));
        verify(mockDetailEditor).zoomOut();
    }

    @Test
    void zoomToFit_callsDetailEditorZoomToFit() {
        dispatcher.handle(rpc("detailEditor/zoomToFit", "{}"));
        verify(mockDetailEditor).zoomToFit();
    }

    @Test
    void zoomToSelection_callsDetailEditorZoomToSelection() {
        dispatcher.handle(rpc("detailEditor/zoomToSelection", "{}"));
        verify(mockDetailEditor).zoomToSelection();
    }

    @Test
    void zoomToFitSelectionOrAll_callsDetailEditor() {
        dispatcher.handle(rpc("detailEditor/zoomToFitSelectionOrAll", "{}"));
        verify(mockDetailEditor).zoomToFitSelectionOrAll();
    }

    // --- Lane zoom behavioral tests ---

    @Test
    void zoomInLanes_callsDetailEditorZoomInLaneHeights() {
        dispatcher.handle(rpc("detailEditor/zoomInLanes", "{}"));
        verify(mockDetailEditor).zoomInLaneHeights();
    }

    @Test
    void zoomOutLanes_callsDetailEditorZoomOutLaneHeights() {
        dispatcher.handle(rpc("detailEditor/zoomOutLanes", "{}"));
        verify(mockDetailEditor).zoomOutLaneHeights();
    }

    // --- Region zoom ---

    @Test
    void zoomToRegion_callsScrollbarZoomToContentRegion() {
        dispatcher.handle(rpc("detailEditor/zoomToRegion", "{\"from\":4.0,\"to\":16.0}"));
        verify(mockScrollbar).zoomToContentRegion(4.0, 16.0);
    }

    @Test
    void zoomToRegion_missingFrom_returnsError() {
        String response = dispatcher.handle(rpc("detailEditor/zoomToRegion", "{\"to\":16.0}"));
        assertContains(response, "-32602");
        assertContains(response, "from");
    }

    @Test
    void zoomToRegion_missingTo_returnsError() {
        String response = dispatcher.handle(rpc("detailEditor/zoomToRegion", "{\"from\":4.0}"));
        assertContains(response, "-32602");
        assertContains(response, "to");
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
