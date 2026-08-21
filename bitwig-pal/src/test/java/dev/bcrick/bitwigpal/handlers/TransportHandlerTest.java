package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.SettableBeatTimeValue;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableEnumValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
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
class TransportHandlerTest {

    @Mock private Transport mockTransport;

    // 3-level chain mocks (tempo)
    @Mock private Parameter mockTempoParam;
    @Mock private SettableRangedValue mockTempoValue;

    // 2-level chain mocks
    @Mock private SettableBeatTimeValue mockPosition;
    @Mock private SettableBooleanValue mockLoopEnabled;
    @Mock private SettableBooleanValue mockMetronomeEnabled;
    @Mock private SettableBeatTimeValue mockLoopStart;
    @Mock private SettableBeatTimeValue mockLoopDuration;
    @Mock private SettableBeatTimeValue mockInPosition;
    @Mock private SettableBooleanValue mockPunchInEnabled;
    @Mock private SettableBeatTimeValue mockOutPosition;
    @Mock private SettableBooleanValue mockPunchOutEnabled;
    @Mock private SettableEnumValue mockAutomationWriteMode;
    @Mock private SettableBooleanValue mockArrangerAutomationWrite;
    @Mock private SettableBooleanValue mockClipLauncherAutomationWrite;
    @Mock private SettableEnumValue mockPreRoll;
    @Mock private SettableRangedValue mockMetronomeVolume;
    @Mock private SettableEnumValue mockDefaultLaunchQuantization;
    @Mock private SettableEnumValue mockPostRecordingAction;
    @Mock private SettableBeatTimeValue mockPostRecordingTimeOffset;
    @Mock private SettableBooleanValue mockClipLauncherOverdub;
    @Mock private SettableBooleanValue mockFillMode;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new TransportHandler(mockTransport, new StateCache()).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersLoopRangeMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("transport/setLoopRange"));
        assertTrue(methods.contains("transport/getLoopRange"));
    }

    @Test
    void registersPunchMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("transport/setPunchIn"));
        assertTrue(methods.contains("transport/setPunchOut"));
    }

    @Test
    void registersAutomationMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("transport/setAutomationWriteMode"));
        assertTrue(methods.contains("transport/setArrangerAutomationWrite"));
        assertTrue(methods.contains("transport/setClipLauncherAutomationWrite"));
        assertTrue(methods.contains("transport/resetAutomationOverrides"));
    }

    @Test
    void registersNavigationMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("transport/continuePlayback"));
        assertTrue(methods.contains("transport/restart"));
        assertTrue(methods.contains("transport/returnToArrangement"));
        assertTrue(methods.contains("transport/jumpToPreviousCueMarker"));
        assertTrue(methods.contains("transport/jumpToNextCueMarker"));
    }

    @Test
    void registersMetronomeAndPreRollMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("transport/setPreRoll"));
        assertTrue(methods.contains("transport/setMetronomeVolume"));
    }

    @Test
    void registersClipLauncherSettingsMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("transport/setDefaultLaunchQuantization"));
        assertTrue(methods.contains("transport/setPostRecordingAction"));
        assertTrue(methods.contains("transport/setPostRecordingTimeOffset"));
        assertTrue(methods.contains("transport/setClipLauncherOverdub"));
        assertTrue(methods.contains("transport/setFillMode"));
        assertTrue(methods.contains("transport/getClipLauncherSettings"));
    }

    @Test
    void registersThirtyTwoMethodsTotal() {
        assertEquals(32, dispatcher.getRegisteredMethods().size());
    }

    // --- setLoopRange validation ---

    @Test
    void setLoopRange_missingStart_returnsError() {
        String response = dispatcher.handle(rpc("transport/setLoopRange",
            "{\"duration\": 16.0, \"enabled\": true}"));
        assertContains(response, "-32602");
        assertContains(response, "start");
    }

    @Test
    void setLoopRange_missingDuration_returnsError() {
        String response = dispatcher.handle(rpc("transport/setLoopRange",
            "{\"start\": 0.0, \"enabled\": true}"));
        assertContains(response, "-32602");
        assertContains(response, "duration");
    }

    @Test
    void setLoopRange_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("transport/setLoopRange",
            "{\"start\": 0.0, \"duration\": 16.0}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- setPunchIn validation ---

    @Test
    void setPunchIn_missingPosition_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPunchIn",
            "{\"enabled\": true}"));
        assertContains(response, "-32602");
        assertContains(response, "position");
    }

    @Test
    void setPunchIn_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPunchIn",
            "{\"position\": 4.0}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- setPunchOut validation ---

    @Test
    void setPunchOut_missingPosition_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPunchOut",
            "{\"enabled\": true}"));
        assertContains(response, "-32602");
        assertContains(response, "position");
    }

    @Test
    void setPunchOut_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPunchOut",
            "{\"position\": 8.0}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- setAutomationWriteMode validation ---

    @Test
    void setAutomationWriteMode_missingMode_returnsError() {
        String response = dispatcher.handle(rpc("transport/setAutomationWriteMode", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "mode");
    }

    @Test
    void setAutomationWriteMode_invalidMode_returnsError() {
        String response = dispatcher.handle(rpc("transport/setAutomationWriteMode",
            "{\"mode\": \"invalid\"}"));
        assertContains(response, "-32602");
        assertContains(response, "invalid");
    }

    @Test
    void setAutomationWriteMode_emptyString_returnsError() {
        String response = dispatcher.handle(rpc("transport/setAutomationWriteMode",
            "{\"mode\": \"\"}"));
        assertContains(response, "-32602");
    }

    // --- setArrangerAutomationWrite validation ---

    @Test
    void setArrangerAutomationWrite_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("transport/setArrangerAutomationWrite", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- setClipLauncherAutomationWrite validation ---

    @Test
    void setClipLauncherAutomationWrite_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("transport/setClipLauncherAutomationWrite", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- setPreRoll validation ---

    @Test
    void setPreRoll_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPreRoll", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "value");
    }

    @Test
    void setPreRoll_invalidValue_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPreRoll",
            "{\"value\": \"three_bars\"}"));
        assertContains(response, "-32602");
        assertContains(response, "three_bars");
    }

    // --- setMetronomeVolume validation ---

    @Test
    void setMetronomeVolume_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("transport/setMetronomeVolume", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "value");
    }

    // --- setDefaultLaunchQuantization validation ---

    @Test
    void setDefaultLaunchQuantization_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("transport/setDefaultLaunchQuantization", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "quantization");
    }

    @Test
    void setDefaultLaunchQuantization_invalidValue_returnsError() {
        String response = dispatcher.handle(rpc("transport/setDefaultLaunchQuantization",
            "{\"quantization\": \"bad\"}"));
        assertContains(response, "-32602");
        assertContains(response, "bad");
    }

    // --- setPostRecordingAction validation ---

    @Test
    void setPostRecordingAction_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPostRecordingAction", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "action");
    }

    @Test
    void setPostRecordingAction_invalidValue_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPostRecordingAction",
            "{\"action\": \"invalid\"}"));
        assertContains(response, "-32602");
        assertContains(response, "invalid");
    }

    // --- setPostRecordingTimeOffset validation ---

    @Test
    void setPostRecordingTimeOffset_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("transport/setPostRecordingTimeOffset", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "beats");
    }

    // --- setClipLauncherOverdub validation ---

    @Test
    void setClipLauncherOverdub_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("transport/setClipLauncherOverdub", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- setFillMode validation ---

    @Test
    void setFillMode_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("transport/setFillMode", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- Behavioral tests (Mockito) — 1-level calls ---

    @Test
    void play_callsTransportPlay() {
        dispatcher.handle(rpc("transport/play", "{}"));
        verify(mockTransport).play();
    }

    @Test
    void stop_callsTransportStop() {
        dispatcher.handle(rpc("transport/stop", "{}"));
        verify(mockTransport).stop();
    }

    @Test
    void record_callsTransportRecord() {
        dispatcher.handle(rpc("transport/record", "{}"));
        verify(mockTransport).record();
    }

    @Test
    void togglePlay_callsTransportTogglePlay() {
        dispatcher.handle(rpc("transport/togglePlay", "{}"));
        verify(mockTransport).togglePlay();
    }

    @Test
    void rewind_callsTransportRewind() {
        dispatcher.handle(rpc("transport/rewind", "{}"));
        verify(mockTransport).rewind();
    }

    @Test
    void fastForward_callsTransportFastForward() {
        dispatcher.handle(rpc("transport/fastForward", "{}"));
        verify(mockTransport).fastForward();
    }

    @Test
    void tapTempo_callsTransportTapTempo() {
        dispatcher.handle(rpc("transport/tapTempo", "{}"));
        verify(mockTransport).tapTempo();
    }

    @Test
    void resetAutomationOverrides_callsTransportResetAutomationOverrides() {
        dispatcher.handle(rpc("transport/resetAutomationOverrides", "{}"));
        verify(mockTransport).resetAutomationOverrides();
    }

    @Test
    void continuePlayback_callsTransportContinuePlayback() {
        dispatcher.handle(rpc("transport/continuePlayback", "{}"));
        verify(mockTransport).continuePlayback();
    }

    @Test
    void restart_callsTransportRestart() {
        dispatcher.handle(rpc("transport/restart", "{}"));
        verify(mockTransport).restart();
    }

    @Test
    void returnToArrangement_callsTransportReturnToArrangement() {
        dispatcher.handle(rpc("transport/returnToArrangement", "{}"));
        verify(mockTransport).returnToArrangement();
    }

    @Test
    void jumpToPreviousCueMarker_callsTransport() {
        dispatcher.handle(rpc("transport/jumpToPreviousCueMarker", "{}"));
        verify(mockTransport).jumpToPreviousCueMarker();
    }

    @Test
    void jumpToNextCueMarker_callsTransport() {
        dispatcher.handle(rpc("transport/jumpToNextCueMarker", "{}"));
        verify(mockTransport).jumpToNextCueMarker();
    }

    // --- Behavioral tests (Mockito) — 3-level chain ---

    @Test
    void setTempo_callsTransportTempoValueSetRaw() {
        when(mockTransport.tempo()).thenReturn(mockTempoParam);
        when(mockTempoParam.value()).thenReturn(mockTempoValue);

        dispatcher.handle(rpc("transport/setTempo", "{\"tempo\":120.0}"));

        verify(mockTempoValue).setRaw(120.0);
    }

    // --- Behavioral tests (Mockito) — 2-level chains ---

    @Test
    void setPosition_callsTransportGetPositionSet() {
        when(mockTransport.getPosition()).thenReturn(mockPosition);

        dispatcher.handle(rpc("transport/setPosition", "{\"beats\":4.0}"));

        verify(mockPosition).set(4.0);
    }

    @Test
    void setLoop_callsTransportIsArrangerLoopEnabledSet() {
        when(mockTransport.isArrangerLoopEnabled()).thenReturn(mockLoopEnabled);

        dispatcher.handle(rpc("transport/setLoop", "{\"enabled\":true}"));

        verify(mockLoopEnabled).set(true);
    }

    @Test
    void setMetronome_callsTransportIsMetronomeEnabledSet() {
        when(mockTransport.isMetronomeEnabled()).thenReturn(mockMetronomeEnabled);

        dispatcher.handle(rpc("transport/setMetronome", "{\"enabled\":true}"));

        verify(mockMetronomeEnabled).set(true);
    }

    @Test
    void setLoopRange_callsTransportLoopStartAndDurationAndEnabled() {
        when(mockTransport.arrangerLoopStart()).thenReturn(mockLoopStart);
        when(mockTransport.arrangerLoopDuration()).thenReturn(mockLoopDuration);
        when(mockTransport.isArrangerLoopEnabled()).thenReturn(mockLoopEnabled);

        dispatcher.handle(rpc("transport/setLoopRange",
            "{\"start\":4.0,\"duration\":16.0,\"enabled\":true}"));

        verify(mockLoopStart).set(4.0);
        verify(mockLoopDuration).set(16.0);
        verify(mockLoopEnabled).set(true);
    }

    @Test
    void setPunchIn_callsTransportInPositionAndPunchInEnabled() {
        when(mockTransport.getInPosition()).thenReturn(mockInPosition);
        when(mockTransport.isPunchInEnabled()).thenReturn(mockPunchInEnabled);

        dispatcher.handle(rpc("transport/setPunchIn", "{\"position\":4.0,\"enabled\":true}"));

        verify(mockInPosition).set(4.0);
        verify(mockPunchInEnabled).set(true);
    }

    @Test
    void setPunchOut_callsTransportOutPositionAndPunchOutEnabled() {
        when(mockTransport.getOutPosition()).thenReturn(mockOutPosition);
        when(mockTransport.isPunchOutEnabled()).thenReturn(mockPunchOutEnabled);

        dispatcher.handle(rpc("transport/setPunchOut", "{\"position\":8.0,\"enabled\":true}"));

        verify(mockOutPosition).set(8.0);
        verify(mockPunchOutEnabled).set(true);
    }

    @Test
    void setAutomationWriteMode_callsTransportAutomationWriteModeSet() {
        when(mockTransport.automationWriteMode()).thenReturn(mockAutomationWriteMode);

        dispatcher.handle(rpc("transport/setAutomationWriteMode", "{\"mode\":\"touch\"}"));

        verify(mockAutomationWriteMode).set("touch");
    }

    @Test
    void setArrangerAutomationWrite_callsTransportSet() {
        when(mockTransport.isArrangerAutomationWriteEnabled()).thenReturn(mockArrangerAutomationWrite);

        dispatcher.handle(rpc("transport/setArrangerAutomationWrite", "{\"enabled\":true}"));

        verify(mockArrangerAutomationWrite).set(true);
    }

    @Test
    void setClipLauncherAutomationWrite_callsTransportSet() {
        when(mockTransport.isClipLauncherAutomationWriteEnabled()).thenReturn(mockClipLauncherAutomationWrite);

        dispatcher.handle(rpc("transport/setClipLauncherAutomationWrite", "{\"enabled\":true}"));

        verify(mockClipLauncherAutomationWrite).set(true);
    }

    @Test
    void setPreRoll_callsTransportPreRollSet() {
        when(mockTransport.preRoll()).thenReturn(mockPreRoll);

        dispatcher.handle(rpc("transport/setPreRoll", "{\"value\":\"one_bar\"}"));

        verify(mockPreRoll).set("one_bar");
    }

    @Test
    void setMetronomeVolume_callsTransportMetronomeVolumeSetImmediately() {
        when(mockTransport.metronomeVolume()).thenReturn(mockMetronomeVolume);

        dispatcher.handle(rpc("transport/setMetronomeVolume", "{\"value\":0.75}"));

        verify(mockMetronomeVolume).setImmediately(0.75);
    }

    @Test
    void setDefaultLaunchQuantization_callsTransportSet() {
        when(mockTransport.defaultLaunchQuantization()).thenReturn(mockDefaultLaunchQuantization);

        dispatcher.handle(rpc("transport/setDefaultLaunchQuantization", "{\"quantization\":\"1/4\"}"));

        verify(mockDefaultLaunchQuantization).set("1/4");
    }

    @Test
    void setPostRecordingAction_callsTransportSet() {
        when(mockTransport.clipLauncherPostRecordingAction()).thenReturn(mockPostRecordingAction);

        dispatcher.handle(rpc("transport/setPostRecordingAction", "{\"action\":\"play_recorded\"}"));

        verify(mockPostRecordingAction).set("play_recorded");
    }

    @Test
    void setPostRecordingTimeOffset_callsTransportSet() {
        when(mockTransport.getClipLauncherPostRecordingTimeOffset()).thenReturn(mockPostRecordingTimeOffset);

        dispatcher.handle(rpc("transport/setPostRecordingTimeOffset", "{\"beats\":2.0}"));

        verify(mockPostRecordingTimeOffset).set(2.0);
    }

    @Test
    void setClipLauncherOverdub_callsTransportSet() {
        when(mockTransport.isClipLauncherOverdubEnabled()).thenReturn(mockClipLauncherOverdub);

        dispatcher.handle(rpc("transport/setClipLauncherOverdub", "{\"enabled\":true}"));

        verify(mockClipLauncherOverdub).set(true);
    }

    @Test
    void setFillMode_callsTransportSet() {
        when(mockTransport.isFillModeActive()).thenReturn(mockFillMode);

        dispatcher.handle(rpc("transport/setFillMode", "{\"enabled\":true}"));

        verify(mockFillMode).set(true);
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
