package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.Project;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
class ProjectHandlerTest {

    @Mock private Project mockProject;
    @Mock private Parameter mockCueVolumeParam;
    @Mock private Parameter mockCueMixParam;
    @Mock private SettableRangedValue mockCueVolumeValue;
    @Mock private SettableRangedValue mockCueMixValue;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(mockProject.cueVolume()).thenReturn(mockCueVolumeParam);
        when(mockProject.cueMix()).thenReturn(mockCueMixParam);
        when(mockCueVolumeParam.value()).thenReturn(mockCueVolumeValue);
        when(mockCueMixParam.value()).thenReturn(mockCueMixValue);

        dispatcher = new JsonRpcDispatcher();
        new ProjectHandler(mockProject, new StateCache()).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersSixMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("project/unsoloAll"));
        assertTrue(methods.contains("project/unmuteAll"));
        assertTrue(methods.contains("project/unarmAll"));
        assertTrue(methods.contains("project/getState"));
        assertTrue(methods.contains("project/setCueVolume"));
        assertTrue(methods.contains("project/setCueMix"));
        assertEquals(6, methods.size());
    }

    // --- project/getState ---

    @Test
    void getState_returnsStateCacheData() {
        String response = dispatcher.handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"project/getState\",\"params\":{},\"id\":1}");
        JsonObject result = JsonParser.parseString(response).getAsJsonObject()
            .getAsJsonObject("result");
        assertNotNull(result);
        assertTrue(result.has("hasSoloedTracks"));
        assertTrue(result.has("hasMutedTracks"));
        assertTrue(result.has("hasArmedTracks"));
        assertTrue(result.has("isModified"));
        assertTrue(result.has("cueVolume"));
        assertTrue(result.has("cueMix"));
    }

    // --- Behavioral tests (Mockito) ---

    @Test
    void unsoloAll_callsProjectUnsolo() {
        dispatcher.handle(rpc("project/unsoloAll", "{}"));
        verify(mockProject).unsoloAll();
    }

    @Test
    void unmuteAll_callsProjectUnmute() {
        dispatcher.handle(rpc("project/unmuteAll", "{}"));
        verify(mockProject).unmuteAll();
    }

    @Test
    void unarmAll_callsProjectUnarm() {
        dispatcher.handle(rpc("project/unarmAll", "{}"));
        verify(mockProject).unarmAll();
    }

    // --- Cue mix behavioral tests ---

    @Test
    void setCueVolume_callsProjectCueVolumeSet() {
        dispatcher.handle(rpc("project/setCueVolume", "{\"value\":0.75}"));
        verify(mockCueVolumeValue).set(0.75);
    }

    @Test
    void setCueMix_callsProjectCueMixSet() {
        dispatcher.handle(rpc("project/setCueMix", "{\"value\":0.5}"));
        verify(mockCueMixValue).set(0.5);
    }

    @Test
    void setCueVolume_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("project/setCueVolume", "{}"));
        assertTrue(response.contains("value"));
    }

    @Test
    void setCueMix_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("project/setCueMix", "{}"));
        assertTrue(response.contains("value"));
    }

    // --- Helpers ---

    private String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}";
    }
}
