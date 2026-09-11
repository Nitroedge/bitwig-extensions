package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Mixer;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
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
class MixerHandlerTest {

    @Mock private Mixer mockMixer;
    @Mock private SettableBooleanValue mockMeter;
    @Mock private SettableBooleanValue mockIo;
    @Mock private SettableBooleanValue mockSends;
    @Mock private SettableBooleanValue mockClipLauncher;
    @Mock private SettableBooleanValue mockDevices;
    @Mock private SettableBooleanValue mockCrossFade;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(mockMixer.isMeterSectionVisible()).thenReturn(mockMeter);
        when(mockMixer.isIoSectionVisible()).thenReturn(mockIo);
        when(mockMixer.isSendSectionVisible()).thenReturn(mockSends);
        when(mockMixer.isClipLauncherSectionVisible()).thenReturn(mockClipLauncher);
        when(mockMixer.isDeviceSectionVisible()).thenReturn(mockDevices);
        when(mockMixer.isCrossFadeSectionVisible()).thenReturn(mockCrossFade);

        dispatcher = new JsonRpcDispatcher();
        new MixerHandler(mockMixer).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersSixMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("mixer/getState"));
        assertTrue(methods.contains("mixer/setSection"));
        assertTrue(methods.contains("mixer/zoomInAll"));
        assertTrue(methods.contains("mixer/zoomOutAll"));
        assertTrue(methods.contains("mixer/zoomInSelected"));
        assertTrue(methods.contains("mixer/zoomOutSelected"));
        assertEquals(6, methods.size());
    }

    // --- mixer/getState ---

    @Test
    void getState_returnsAllSixFields() {
        when(mockMeter.get()).thenReturn(true);
        when(mockIo.get()).thenReturn(false);
        when(mockSends.get()).thenReturn(true);
        when(mockClipLauncher.get()).thenReturn(false);
        when(mockDevices.get()).thenReturn(true);
        when(mockCrossFade.get()).thenReturn(false);

        String response = dispatcher.handle(rpc("mixer/getState", "{}"));
        assertContains(response, "\"meter\":true");
        assertContains(response, "\"io\":false");
        assertContains(response, "\"sends\":true");
        assertContains(response, "\"clipLauncher\":false");
        assertContains(response, "\"devices\":true");
        assertContains(response, "\"crossFade\":false");
    }

    // --- mixer/setSection ---

    @Test
    void setSection_meter() {
        dispatcher.handle(rpc("mixer/setSection", "{\"section\":\"meter\",\"visible\":true}"));
        verify(mockMeter).set(true);
    }

    @Test
    void setSection_io() {
        dispatcher.handle(rpc("mixer/setSection", "{\"section\":\"io\",\"visible\":false}"));
        verify(mockIo).set(false);
    }

    @Test
    void setSection_sends() {
        dispatcher.handle(rpc("mixer/setSection", "{\"section\":\"sends\",\"visible\":true}"));
        verify(mockSends).set(true);
    }

    @Test
    void setSection_clipLauncher() {
        dispatcher.handle(rpc("mixer/setSection", "{\"section\":\"clipLauncher\",\"visible\":true}"));
        verify(mockClipLauncher).set(true);
    }

    @Test
    void setSection_devices() {
        dispatcher.handle(rpc("mixer/setSection", "{\"section\":\"devices\",\"visible\":false}"));
        verify(mockDevices).set(false);
    }

    @Test
    void setSection_crossFade() {
        dispatcher.handle(rpc("mixer/setSection", "{\"section\":\"crossFade\",\"visible\":true}"));
        verify(mockCrossFade).set(true);
    }

    @Test
    void setSection_invalidName_returnsError() {
        String response = dispatcher.handle(rpc("mixer/setSection", "{\"section\":\"invalid\",\"visible\":true}"));
        assertContains(response, "invalid section");
    }

    @Test
    void setSection_missingSection_returnsError() {
        String response = dispatcher.handle(rpc("mixer/setSection", "{\"visible\":true}"));
        assertContains(response, "section");
    }

    @Test
    void setSection_missingVisible_returnsError() {
        String response = dispatcher.handle(rpc("mixer/setSection", "{\"section\":\"meter\"}"));
        assertContains(response, "visible");
    }

    // --- Zoom methods ---

    @Test
    void zoomInAll_callsMixer() {
        dispatcher.handle(rpc("mixer/zoomInAll", "{}"));
        verify(mockMixer).zoomInTrackWidthsAll();
    }

    @Test
    void zoomOutAll_callsMixer() {
        dispatcher.handle(rpc("mixer/zoomOutAll", "{}"));
        verify(mockMixer).zoomOutTrackWidthsAll();
    }

    @Test
    void zoomInSelected_callsMixer() {
        dispatcher.handle(rpc("mixer/zoomInSelected", "{}"));
        verify(mockMixer).zoomInTrackWidthsSelected();
    }

    @Test
    void zoomOutSelected_callsMixer() {
        dispatcher.handle(rpc("mixer/zoomOutSelected", "{}"));
        verify(mockMixer).zoomOutTrackWidthsSelected();
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
