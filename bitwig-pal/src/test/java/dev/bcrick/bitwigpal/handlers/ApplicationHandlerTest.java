package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Action;
import com.bitwig.extension.controller.api.ActionCategory;
import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
class ApplicationHandlerTest {

    @Mock private Application mockApplication;
    @Mock private ControllerHost mockHost;
    @Mock private TrackBank mockTrackBank;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new ApplicationHandler(mockApplication, mockHost, mockTrackBank).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersTwentyFiveMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("app/undo"));
        assertTrue(methods.contains("app/redo"));
        assertTrue(methods.contains("app/getState"));
        assertTrue(methods.contains("app/activateEngine"));
        assertTrue(methods.contains("app/deactivateEngine"));
        assertTrue(methods.contains("app/showNotification"));
        assertTrue(methods.contains("app/setPanelLayout"));
        assertTrue(methods.contains("app/toggleInspector"));
        assertTrue(methods.contains("app/toggleDevices"));
        assertTrue(methods.contains("app/toggleMixer"));
        assertTrue(methods.contains("app/toggleNoteEditor"));
        assertTrue(methods.contains("app/toggleAutomationEditor"));
        assertTrue(methods.contains("app/toggleBrowser"));
        assertTrue(methods.contains("app/toggleFullScreen"));
        assertTrue(methods.contains("app/previousSubPanel"));
        assertTrue(methods.contains("app/nextSubPanel"));
        assertTrue(methods.contains("app/zoomIn"));
        assertTrue(methods.contains("app/zoomOut"));
        assertTrue(methods.contains("app/zoomToFit"));
        assertTrue(methods.contains("app/zoomToSelection"));
        assertTrue(methods.contains("app/navigateIntoTrackGroup"));
        assertTrue(methods.contains("app/navigateToParentTrackGroup"));
        assertTrue(methods.contains("action/list"));
        assertTrue(methods.contains("action/listCategories"));
        assertTrue(methods.contains("action/invoke"));
        assertEquals(25, methods.size());
    }

    // --- showNotification validation ---

    @Test
    void showNotification_missingText_returnsError() {
        String response = dispatcher.handle(rpc("app/showNotification", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "text");
    }

    // --- setPanelLayout validation ---

    @Test
    void setPanelLayout_missingLayout_returnsError() {
        String response = dispatcher.handle(rpc("app/setPanelLayout", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "layout");
    }

    @Test
    void setPanelLayout_invalidLayout_returnsError() {
        String response = dispatcher.handle(rpc("app/setPanelLayout",
            "{\"layout\": \"INVALID\"}"));
        assertContains(response, "-32602");
        assertContains(response, "INVALID");
    }

    // --- Behavioral tests (Mockito) ---

    @Test
    void undo_callsApplicationUndo() {
        dispatcher.handle(rpc("app/undo", "{}"));

        verify(mockApplication).undo();
    }

    @Test
    void redo_callsApplicationRedo() {
        dispatcher.handle(rpc("app/redo", "{}"));

        verify(mockApplication).redo();
    }

    @Test
    void activateEngine_callsApplicationActivateEngine() {
        dispatcher.handle(rpc("app/activateEngine", "{}"));

        verify(mockApplication).activateEngine();
    }

    @Test
    void deactivateEngine_callsApplicationDeactivateEngine() {
        dispatcher.handle(rpc("app/deactivateEngine", "{}"));

        verify(mockApplication).deactivateEngine();
    }

    @Test
    void showNotification_callsHostShowPopupNotification() {
        dispatcher.handle(rpc("app/showNotification", "{\"text\":\"Hello\"}"));

        verify(mockHost).showPopupNotification("Hello");
    }

    @Test
    void setPanelLayout_callsApplicationSetPanelLayout() {
        dispatcher.handle(rpc("app/setPanelLayout", "{\"layout\":\"ARRANGE\"}"));

        verify(mockApplication).setPanelLayout("ARRANGE");
    }

    // --- Panel toggle behavioral tests ---

    @Test
    void toggleInspector_callsApplicationToggleInspector() {
        dispatcher.handle(rpc("app/toggleInspector", "{}"));
        verify(mockApplication).toggleInspector();
    }

    @Test
    void toggleDevices_callsApplicationToggleDevices() {
        dispatcher.handle(rpc("app/toggleDevices", "{}"));
        verify(mockApplication).toggleDevices();
    }

    @Test
    void toggleMixer_callsApplicationToggleMixer() {
        dispatcher.handle(rpc("app/toggleMixer", "{}"));
        verify(mockApplication).toggleMixer();
    }

    @Test
    void toggleNoteEditor_callsApplicationToggleNoteEditor() {
        dispatcher.handle(rpc("app/toggleNoteEditor", "{}"));
        verify(mockApplication).toggleNoteEditor();
    }

    @Test
    void toggleAutomationEditor_callsApplicationToggleAutomationEditor() {
        dispatcher.handle(rpc("app/toggleAutomationEditor", "{}"));
        verify(mockApplication).toggleAutomationEditor();
    }

    @Test
    void toggleBrowser_callsApplicationToggleBrowserVisibility() {
        dispatcher.handle(rpc("app/toggleBrowser", "{}"));
        verify(mockApplication).toggleBrowserVisibility();
    }

    @Test
    void toggleFullScreen_callsApplicationToggleFullScreen() {
        dispatcher.handle(rpc("app/toggleFullScreen", "{}"));
        verify(mockApplication).toggleFullScreen();
    }

    @Test
    void previousSubPanel_callsApplicationPreviousSubPanel() {
        dispatcher.handle(rpc("app/previousSubPanel", "{}"));
        verify(mockApplication).previousSubPanel();
    }

    @Test
    void nextSubPanel_callsApplicationNextSubPanel() {
        dispatcher.handle(rpc("app/nextSubPanel", "{}"));
        verify(mockApplication).nextSubPanel();
    }

    // --- Zoom behavioral tests ---

    @Test
    void zoomIn_callsApplicationZoomIn() {
        dispatcher.handle(rpc("app/zoomIn", "{}"));
        verify(mockApplication).zoomIn();
    }

    @Test
    void zoomOut_callsApplicationZoomOut() {
        dispatcher.handle(rpc("app/zoomOut", "{}"));
        verify(mockApplication).zoomOut();
    }

    @Test
    void zoomToFit_callsApplicationZoomToFit() {
        dispatcher.handle(rpc("app/zoomToFit", "{}"));
        verify(mockApplication).zoomToFit();
    }

    @Test
    void zoomToSelection_callsApplicationZoomToSelection() {
        dispatcher.handle(rpc("app/zoomToSelection", "{}"));
        verify(mockApplication).zoomToSelection();
    }

    // --- Track group navigation ---

    @Test
    void navigateIntoTrackGroup_callsApplicationWithTrack() {
        Track mockTrack = mock(Track.class);
        when(mockTrackBank.getItemAt(2)).thenReturn(mockTrack);

        dispatcher.handle(rpc("app/navigateIntoTrackGroup", "{\"trackIndex\":2}"));

        verify(mockApplication).navigateIntoTrackGroup(mockTrack);
    }

    @Test
    void navigateIntoTrackGroup_missingTrackIndex_returnsError() {
        String response = dispatcher.handle(rpc("app/navigateIntoTrackGroup", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "trackIndex");
    }

    @Test
    void navigateToParentTrackGroup_callsApplication() {
        dispatcher.handle(rpc("app/navigateToParentTrackGroup", "{}"));
        verify(mockApplication).navigateToParentTrackGroup();
    }

    // --- Action discovery & invoke ---

    @Test
    void actionList_returnsActionsAsJsonArray() {
        ActionCategory mockCategory = mock(ActionCategory.class);
        when(mockCategory.getName()).thenReturn("General");

        Action mockAction = mock(Action.class);
        when(mockAction.getId()).thenReturn("select_all");
        when(mockAction.getName()).thenReturn("Select All");
        when(mockAction.getCategory()).thenReturn(mockCategory);
        when(mockAction.getMenuItemText()).thenReturn("Select All");
        when(mockApplication.getActions()).thenReturn(new Action[]{mockAction});

        String response = dispatcher.handle(rpc("action/list", "{}"));
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonArray result = json.getAsJsonArray("result");
        assertEquals(1, result.size());
        JsonObject action = result.get(0).getAsJsonObject();
        assertEquals("select_all", action.get("id").getAsString());
        assertEquals("Select All", action.get("name").getAsString());
        assertEquals("General", action.get("category").getAsString());
    }

    @Test
    void actionList_withCategoryFilter_filtersResults() {
        ActionCategory catA = mock(ActionCategory.class);
        when(catA.getName()).thenReturn("Edit");
        ActionCategory catB = mock(ActionCategory.class);
        when(catB.getName()).thenReturn("View");

        Action action1 = mock(Action.class);
        when(action1.getId()).thenReturn("a1");
        when(action1.getName()).thenReturn("A1");
        when(action1.getCategory()).thenReturn(catA);
        when(action1.getMenuItemText()).thenReturn("A1");

        Action action2 = mock(Action.class);
        when(action2.getId()).thenReturn("a2");
        when(action2.getName()).thenReturn("A2");
        when(action2.getCategory()).thenReturn(catB);
        when(action2.getMenuItemText()).thenReturn("A2");

        when(mockApplication.getActions()).thenReturn(new Action[]{action1, action2});

        String response = dispatcher.handle(rpc("action/list", "{\"category\":\"Edit\"}"));
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonArray result = json.getAsJsonArray("result");
        assertEquals(1, result.size());
        assertEquals("a1", result.get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void actionListCategories_returnsCategoriesAsJsonArray() {
        ActionCategory mockCategory = mock(ActionCategory.class);
        when(mockCategory.getId()).thenReturn("general");
        when(mockCategory.getName()).thenReturn("General");
        when(mockApplication.getActionCategories()).thenReturn(new ActionCategory[]{mockCategory});

        String response = dispatcher.handle(rpc("action/listCategories", "{}"));
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonArray result = json.getAsJsonArray("result");
        assertEquals(1, result.size());
        assertEquals("general", result.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("General", result.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void actionInvoke_validId_invokesAction() {
        Action mockAction = mock(Action.class);
        when(mockApplication.getAction("select_all")).thenReturn(mockAction);

        String response = dispatcher.handle(rpc("action/invoke", "{\"id\":\"select_all\"}"));
        assertContains(response, "\"result\"");
        verify(mockAction).invoke();
    }

    @Test
    void actionInvoke_invalidId_returnsError() {
        when(mockApplication.getAction("nonexistent")).thenReturn(null);

        String response = dispatcher.handle(rpc("action/invoke", "{\"id\":\"nonexistent\"}"));
        assertContains(response, "-32001");
        assertContains(response, "ACTION_NOT_FOUND");
    }

    @Test
    void actionInvoke_missingId_returnsError() {
        String response = dispatcher.handle(rpc("action/invoke", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "id");
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
