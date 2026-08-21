package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.SendBank;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableEnumValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
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
class SendHandlerTest {

    @Mock private TrackBank mockTrackBank;
    @Mock private Track mockTrack;
    @Mock private SendBank mockSendBank;
    @Mock private Send mockSend;
    @Mock private SettableRangedValue mockSendValue;
    @Mock private SettableEnumValue mockSendMode;
    @Mock private SettableBooleanValue mockSendEnabled;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new SendHandler(mockTrackBank, 4).register(dispatcher);

        // Common stub: trackBank returns track, track returns sendBank, sendBank returns send
        when(mockTrackBank.getSizeOfBank()).thenReturn(8);
        when(mockTrackBank.getItemAt(0)).thenReturn(mockTrack);
        when(mockTrack.sendBank()).thenReturn(mockSendBank);
        when(mockSendBank.getItemAt(0)).thenReturn(mockSend);
    }

    // --- Registration ---

    @Test
    void registersThreeSendMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("send/setLevel"));
        assertTrue(methods.contains("send/setMode"));
        assertTrue(methods.contains("send/setEnabled"));
        assertEquals(3, methods.size());
    }

    // --- send/setLevel validation ---

    @Test
    void sendSetLevel_missingTrackIndex_returnsError() {
        String response = dispatcher.handle(rpc("send/setLevel", "{\"sendIndex\":0,\"value\":0.5}"));
        assertContains(response, "-32602");
    }

    @Test
    void sendSetLevel_missingSendIndex_returnsError() {
        String response = dispatcher.handle(rpc("send/setLevel", "{\"trackIndex\":0,\"value\":0.5}"));
        assertContains(response, "-32602");
    }

    // --- send/setEnabled validation ---

    @Test
    void sendSetEnabled_missingTrackIndex_returnsError() {
        String response = dispatcher.handle(rpc("send/setEnabled", "{\"sendIndex\":0,\"enabled\":true}"));
        assertContains(response, "-32602");
        assertContains(response, "trackIndex");
    }

    // --- Behavioral tests (Mockito) ---

    @Test
    void setLevel_callsSendValueSetImmediately() {
        when(mockSend.value()).thenReturn(mockSendValue);

        dispatcher.handle(rpc("send/setLevel", "{\"trackIndex\":0,\"sendIndex\":0,\"value\":0.8}"));

        verify(mockSendValue).setImmediately(0.8);
    }

    @Test
    void setMode_callsSendModeSet() {
        when(mockSend.sendMode()).thenReturn(mockSendMode);

        dispatcher.handle(rpc("send/setMode", "{\"trackIndex\":0,\"sendIndex\":0,\"mode\":\"PRE\"}"));

        verify(mockSendMode).set("PRE");
    }

    @Test
    void setEnabled_callsSendIsEnabledSet() {
        when(mockSend.isEnabled()).thenReturn(mockSendEnabled);

        dispatcher.handle(rpc("send/setEnabled", "{\"trackIndex\":0,\"sendIndex\":0,\"enabled\":true}"));

        verify(mockSendEnabled).set(true);
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
