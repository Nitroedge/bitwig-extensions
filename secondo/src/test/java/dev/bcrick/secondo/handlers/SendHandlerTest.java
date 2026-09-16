package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.SendBank;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableEnumValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
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
        // A REAL TrackBankManager over the mock bank: with nothing observed the canonical
        // mapping is the identity, so the stubs below keep meaning what they meant before CR-03
        // routed this handler through the resolver.
        new SendHandler(new TrackBankManager(mockTrackBank, 8), 4).register(dispatcher);

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

    // --- CR-03: one coordinate ---

    /**
     * THE TEST THAT WOULD HAVE CAUGHT CR-03 for sends. Bank slot 1 is observed not existing, so
     * public index 1 resolves to bank slot 2. Before the fix this handler subscripted the flat
     * bank raw and would have changed a send level on the wrong track while the verification
     * read confirmed the right one.
     */
    @Test
    void setLevel_underNonIdentityMapping_addressesTheCanonicalTrack() {
        TrackBankManager manager = new TrackBankManager(mockTrackBank, 8);
        manager.observeCanonicalExists(0, true);
        manager.observeCanonicalExists(1, false);
        manager.observeCanonicalExists(2, true);

        Track slotOneTrack = mock(Track.class);
        Track slotTwoTrack = mock(Track.class);
        SendBank slotTwoSendBank = mock(SendBank.class);
        Send slotTwoSend = mock(Send.class);
        SettableRangedValue slotTwoValue = mock(SettableRangedValue.class);
        when(mockTrackBank.getItemAt(1)).thenReturn(slotOneTrack);
        when(mockTrackBank.getItemAt(2)).thenReturn(slotTwoTrack);
        when(slotTwoTrack.sendBank()).thenReturn(slotTwoSendBank);
        when(slotTwoSendBank.getItemAt(0)).thenReturn(slotTwoSend);
        when(slotTwoSend.value()).thenReturn(slotTwoValue);

        JsonRpcDispatcher local = new JsonRpcDispatcher();
        new SendHandler(manager, 4).register(local);

        local.handle(rpc("send/setLevel", "{\"trackIndex\":1,\"sendIndex\":0,\"value\":0.3}"));

        verify(slotTwoValue).setImmediately(0.3);
        verify(slotOneTrack, never()).sendBank();
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
