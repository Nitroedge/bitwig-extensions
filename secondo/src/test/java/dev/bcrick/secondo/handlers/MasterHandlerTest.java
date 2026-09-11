package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.MasterTrack;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableColorValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.controller.api.SoloValue;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterHandlerTest {

    @Mock private MasterTrack mockMasterTrack;
    @Mock private Parameter mockVolumeParam;
    @Mock private Parameter mockPanParam;
    @Mock private SettableRangedValue mockVolumeValue;
    @Mock private SettableRangedValue mockPanValue;
    @Mock private SettableBooleanValue mockMuteValue;
    @Mock private SoloValue mockSoloValue;
    @Mock private SettableColorValue mockColorValue;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new MasterHandler(mockMasterTrack).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersFiveMasterMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("master/setVolume"));
        assertTrue(methods.contains("master/setPan"));
        assertTrue(methods.contains("master/setMute"));
        assertTrue(methods.contains("master/setSolo"));
        assertTrue(methods.contains("master/setColor"));
        assertEquals(5, methods.size());
    }

    // --- Validation tests (no mocks needed) ---

    @Test
    void masterSetMute_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("master/setMute", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "value");
    }

    @Test
    void masterSetSolo_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("master/setSolo", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "value");
    }

    @Test
    void masterSetColor_missingParams_returnsError() {
        String response = dispatcher.handle(rpc("master/setColor", "{}"));
        assertContains(response, "error");
    }

    // --- Behavioral tests (Mockito) ---

    @Test
    void setVolume_callsMasterTrackVolume() {
        when(mockMasterTrack.volume()).thenReturn(mockVolumeParam);
        when(mockVolumeParam.value()).thenReturn(mockVolumeValue);

        dispatcher.handle(rpc("master/setVolume", "{\"value\": 0.75}"));

        verify(mockVolumeValue).setImmediately(0.75);
    }

    @Test
    void setPan_callsMasterTrackPan() {
        when(mockMasterTrack.pan()).thenReturn(mockPanParam);
        when(mockPanParam.value()).thenReturn(mockPanValue);

        dispatcher.handle(rpc("master/setPan", "{\"value\": 0.5}"));

        verify(mockPanValue).setImmediately(0.5);
    }

    @Test
    void setMute_callsMasterTrackMute() {
        when(mockMasterTrack.mute()).thenReturn(mockMuteValue);

        dispatcher.handle(rpc("master/setMute", "{\"value\": true}"));

        verify(mockMuteValue).set(true);
    }

    @Test
    void setSolo_callsMasterTrackSolo() {
        when(mockMasterTrack.solo()).thenReturn(mockSoloValue);

        dispatcher.handle(rpc("master/setSolo", "{\"value\": true}"));

        verify(mockSoloValue).set(true);
    }

    @Test
    void setColor_callsMasterTrackColor() {
        when(mockMasterTrack.color()).thenReturn(mockColorValue);

        dispatcher.handle(rpc("master/setColor", "{\"r\": 0.5, \"g\": 0.3, \"b\": 0.8}"));

        verify(mockColorValue).set(0.5f, 0.3f, 0.8f);
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
