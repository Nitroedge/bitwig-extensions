package dev.gregross.gig.handlers;

import com.bitwig.extension.controller.api.Project;
import com.bitwig.extension.controller.api.Scene;
import com.bitwig.extension.controller.api.SceneBank;
import com.bitwig.extension.controller.api.SettableColorValue;
import com.bitwig.extension.controller.api.SettableIntegerValue;
import com.bitwig.extension.controller.api.SettableStringValue;
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
class SceneHandlerTest {

    @Mock private SceneBank mockSceneBank;
    @Mock private Project mockProject;
    @Mock private Scene mockScene;

    // Chain mocks
    @Mock private SettableStringValue mockSceneName;
    @Mock private SettableColorValue mockSceneColor;
    @Mock private SettableIntegerValue mockScrollPosition;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new SceneHandler(mockSceneBank, mockProject, new StateCache()).register(dispatcher);

        // Common stub: sceneBank returns scene at index 0
        when(mockSceneBank.getScene(0)).thenReturn(mockScene);
    }

    // --- Registration ---


    /**
     * The first scene index the handler must refuse: exactly one past the ceiling.
     *
     * <p>Derived from SceneHandler's own SCENE_COUNT rather than restated as a
     * literal. A restated literal does not merely go stale when the ceiling moves --
     * it silently stops testing anything, because the old out-of-range index becomes a
     * perfectly valid one and the case then asserts an error that will never come.
     * That is precisely how this file went red when the ceiling moved 5 -> 16.
     */
    private static int firstInvalidSceneIndex() {
        return sceneCountOf(SceneHandler.class);
    }

    @Test
    void registersFiveSceneMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("scene/create"));
        assertTrue(methods.contains("scene/createFromPlaying"));
        assertTrue(methods.contains("scene/duplicate"));
        assertTrue(methods.contains("scene/rename"));
        assertTrue(methods.contains("scene/delete"));
    }

    @Test
    void registersThreeScrollMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("sceneBank/scrollTo"));
        assertTrue(methods.contains("sceneBank/scrollBy"));
        assertTrue(methods.contains("sceneBank/getScrollInfo"));
    }

    @Test
    void registersExactlyTwelveMethods() {
        assertEquals(12, dispatcher.getRegisteredMethods().size());
    }

    @Test
    void registersGridEnhancementMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("scene/setColor"));
        assertTrue(methods.contains("scene/launchAlt"));
        assertTrue(methods.contains("scene/launchRelease"));
        assertTrue(methods.contains("scene/launchReleaseAlt"));
    }

    // --- scene/duplicate validation ---

    @Test
    void sceneDuplicate_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("scene/duplicate", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void sceneDuplicate_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("scene/duplicate", "{\"index\": -1}"));
        assertContains(response, "-32602");
    }

    @Test
    void sceneDuplicate_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("scene/duplicate", "{\"index\": " + firstInvalidSceneIndex() + "}"));
        assertContains(response, "-32602");
    }

    // --- scene/rename validation ---

    @Test
    void sceneRename_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("scene/rename", "{\"name\": \"Test\"}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void sceneRename_missingName_returnsError() {
        String response = dispatcher.handle(rpc("scene/rename", "{\"index\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "name");
    }

    @Test
    void sceneRename_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("scene/rename", "{\"index\": -1, \"name\": \"Test\"}"));
        assertContains(response, "-32602");
    }

    @Test
    void sceneRename_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("scene/rename", "{\"index\": " + firstInvalidSceneIndex() + ", \"name\": \"Test\"}"));
        assertContains(response, "-32602");
    }

    // --- scene/delete validation ---

    @Test
    void sceneDelete_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("scene/delete", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void sceneDelete_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("scene/delete", "{\"index\": -1}"));
        assertContains(response, "-32602");
    }

    @Test
    void sceneDelete_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("scene/delete", "{\"index\": " + firstInvalidSceneIndex() + "}"));
        assertContains(response, "-32602");
    }

    // --- scene/setColor validation ---

    @Test
    void sceneSetColor_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("scene/setColor",
            "{\"r\": 0.5, \"g\": 0.5, \"b\": 0.5}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void sceneSetColor_rOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("scene/setColor",
            "{\"index\": 0, \"r\": 1.5, \"g\": 0.5, \"b\": 0.5}"));
        assertContains(response, "-32602");
        assertContains(response, "between 0.0 and 1.0");
    }

    // --- scene/launchAlt validation ---

    @Test
    void sceneLaunchAlt_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("scene/launchAlt", "{\"index\": -1}"));
        assertContains(response, "-32602");
    }

    @Test
    void sceneLaunchAlt_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("scene/launchAlt", "{\"index\": " + firstInvalidSceneIndex() + "}"));
        assertContains(response, "-32602");
    }

    // --- sceneBank/scrollTo validation ---

    @Test
    void sceneBankScrollTo_missingPosition_returnsError() {
        String response = dispatcher.handle(rpc("sceneBank/scrollTo", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "position");
    }

    @Test
    void sceneBankScrollTo_negativePosition_returnsOutOfRange() {
        String response = dispatcher.handle(rpc("sceneBank/scrollTo", "{\"position\": -1}"));
        assertContains(response, "-32001");
        assertContains(response, "POSITION_OUT_OF_RANGE");
    }

    @Test
    void sceneBankScrollTo_positionBeyondItemCount_returnsOutOfRange() {
        String response = dispatcher.handle(rpc("sceneBank/scrollTo", "{\"position\": 0}"));
        assertContains(response, "-32001");
        assertContains(response, "itemCount");
        assertContains(response, "requestedPosition");
    }

    // --- sceneBank/scrollBy validation ---

    @Test
    void sceneBankScrollBy_missingAmount_returnsError() {
        String response = dispatcher.handle(rpc("sceneBank/scrollBy", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "amount");
    }

    // --- sceneBank/getScrollInfo ---

    @Test
    void sceneBankGetScrollInfo_returnsAllFields() {
        String response = dispatcher.handle(rpc("sceneBank/getScrollInfo", "{}"));
        assertContains(response, "scrollPosition");
        assertContains(response, "itemCount");
        assertContains(response, "bankSize");
        assertContains(response, "canScrollForwards");
        assertContains(response, "canScrollBackwards");
    }

    // --- Behavioral tests (Mockito) — Project calls ---

    @Test
    void create_callsProjectCreateScene() {
        dispatcher.handle(rpc("scene/create", "{}"));
        verify(mockProject).createScene();
    }

    @Test
    void createFromPlaying_callsProjectCreateSceneFromPlayingLauncherClips() {
        dispatcher.handle(rpc("scene/createFromPlaying", "{}"));
        verify(mockProject).createSceneFromPlayingLauncherClips();
    }

    // --- Behavioral tests (Mockito) — Scene operations ---

    @Test
    void duplicate_callsSceneDuplicateObject() {
        dispatcher.handle(rpc("scene/duplicate", "{\"index\":0}"));
        verify(mockScene).duplicateObject();
    }

    @Test
    void rename_callsSceneNameSet() {
        when(mockScene.name()).thenReturn(mockSceneName);
        dispatcher.handle(rpc("scene/rename", "{\"index\":0,\"name\":\"Verse\"}"));
        verify(mockSceneName).set("Verse");
    }

    @Test
    void delete_callsSceneDeleteObject() {
        dispatcher.handle(rpc("scene/delete", "{\"index\":0}"));
        verify(mockScene).deleteObject();
    }

    @Test
    void setColor_callsSceneColorSet() {
        when(mockScene.color()).thenReturn(mockSceneColor);
        dispatcher.handle(rpc("scene/setColor", "{\"index\":0,\"r\":0.5,\"g\":0.3,\"b\":0.8}"));
        verify(mockSceneColor).set(0.5f, 0.3f, 0.8f);
    }

    @Test
    void launchAlt_callsSceneLaunchAlt() {
        dispatcher.handle(rpc("scene/launchAlt", "{\"index\":0}"));
        verify(mockScene).launchAlt();
    }

    @Test
    void launchRelease_callsSceneLaunchRelease() {
        dispatcher.handle(rpc("scene/launchRelease", "{\"index\":0}"));
        verify(mockScene).launchRelease();
    }

    @Test
    void launchReleaseAlt_callsSceneLaunchReleaseAlt() {
        dispatcher.handle(rpc("scene/launchReleaseAlt", "{\"index\":0}"));
        verify(mockScene).launchReleaseAlt();
    }

    // --- Behavioral tests (Mockito) — SceneBank scroll ---

    @Test
    void sceneBankScrollBy_callsSceneBankScrollBy() {
        dispatcher.handle(rpc("sceneBank/scrollBy", "{\"amount\":2}"));
        verify(mockSceneBank).scrollBy(2);
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
