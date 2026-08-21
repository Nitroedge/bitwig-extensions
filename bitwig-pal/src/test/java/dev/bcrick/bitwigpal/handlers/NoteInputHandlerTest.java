package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Arpeggiator;
import com.bitwig.extension.controller.api.NoteLatch;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableDoubleValue;
import com.bitwig.extension.controller.api.SettableEnumValue;
import com.bitwig.extension.controller.api.SettableIntegerValue;
import dev.bcrick.bitwigpal.extension.StateCache;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteInputHandlerTest {

    @Mock private NoteInput mockNoteInput;
    @Mock private Arpeggiator mockArpeggiator;
    @Mock private NoteLatch mockNoteLatch;

    // Arpeggiator chain mocks
    @Mock private SettableEnumValue mockArpMode;
    @Mock private SettableIntegerValue mockArpOctaves;
    @Mock private SettableDoubleValue mockArpRate;
    @Mock private SettableDoubleValue mockArpGateLength;
    @Mock private SettableBooleanValue mockArpShuffle;
    @Mock private SettableDoubleValue mockArpHumanize;
    @Mock private SettableBooleanValue mockArpFreeRunning;
    @Mock private SettableBooleanValue mockArpOverlappingNotes;
    @Mock private SettableBooleanValue mockArpPressureToVelocity;
    @Mock private SettableBooleanValue mockArpTerminateImmediately;
    @Mock private SettableBooleanValue mockArpEnabled;

    // NoteLatch chain mocks
    @Mock private SettableEnumValue mockLatchMode;
    @Mock private SettableBooleanValue mockLatchMono;
    @Mock private SettableIntegerValue mockLatchVelocityThreshold;
    @Mock private SettableBooleanValue mockLatchEnabled;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new NoteInputHandler(mockNoteInput, mockArpeggiator, mockNoteLatch, new StateCache()).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersNoteInputMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("noteInput/sendNote"));
        assertTrue(methods.contains("noteInput/sendMidi"));
    }

    @Test
    void registersArpeggiatorMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("arpeggiator/configure"));
        assertTrue(methods.contains("arpeggiator/setEnabled"));
        assertTrue(methods.contains("arpeggiator/releaseNotes"));
        assertTrue(methods.contains("arpeggiator/getState"));
    }

    @Test
    void registersNoteLatchMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("noteLatch/configure"));
        assertTrue(methods.contains("noteLatch/setEnabled"));
        assertTrue(methods.contains("noteLatch/releaseNotes"));
        assertTrue(methods.contains("noteLatch/getState"));
    }

    @Test
    void registersExactlyTenMethods() {
        assertEquals(10, dispatcher.getRegisteredMethods().size());
    }

    // --- Arpeggiator mode validation ---

    @Test
    @SuppressWarnings("unchecked")
    void validArpModesContainsAllSeventeenModes() throws Exception {
        Field field = NoteInputHandler.class.getDeclaredField("VALID_ARP_MODES");
        field.setAccessible(true);
        Set<String> modes = (Set<String>) field.get(null);
        assertEquals(17, modes.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void validArpModesContainsExpectedValues() throws Exception {
        Field field = NoteInputHandler.class.getDeclaredField("VALID_ARP_MODES");
        field.setAccessible(true);
        Set<String> modes = (Set<String>) field.get(null);
        assertTrue(modes.contains("all"));
        assertTrue(modes.contains("up"));
        assertTrue(modes.contains("up-down"));
        assertTrue(modes.contains("up-then-down"));
        assertTrue(modes.contains("down"));
        assertTrue(modes.contains("down-up"));
        assertTrue(modes.contains("down-then-up"));
        assertTrue(modes.contains("flow"));
        assertTrue(modes.contains("random"));
        assertTrue(modes.contains("converge-up"));
        assertTrue(modes.contains("converge-down"));
        assertTrue(modes.contains("diverge-up"));
        assertTrue(modes.contains("diverge-down"));
        assertTrue(modes.contains("thumb-up"));
        assertTrue(modes.contains("thumb-down"));
        assertTrue(modes.contains("pinky-up"));
        assertTrue(modes.contains("pinky-down"));
    }

    // --- Note latch mode validation ---

    @Test
    @SuppressWarnings("unchecked")
    void validLatchModesContainsThreeModes() throws Exception {
        Field field = NoteInputHandler.class.getDeclaredField("VALID_LATCH_MODES");
        field.setAccessible(true);
        Set<String> modes = (Set<String>) field.get(null);
        assertEquals(3, modes.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void validLatchModesContainsExpectedValues() throws Exception {
        Field field = NoteInputHandler.class.getDeclaredField("VALID_LATCH_MODES");
        field.setAccessible(true);
        Set<String> modes = (Set<String>) field.get(null);
        assertTrue(modes.contains("chord"));
        assertTrue(modes.contains("toggle"));
        assertTrue(modes.contains("velocity"));
    }

    // --- Behavioral tests (Mockito) — NoteInput ---

    @Test
    void sendNote_callsNoteInputSendRawMidiEvent() {
        dispatcher.handle(rpc("noteInput/sendNote", "{\"note\":60,\"velocity\":100}"));

        verify(mockNoteInput).sendRawMidiEvent(0x90, 60, 100);
    }

    @Test
    void sendMidi_callsNoteInputSendRawMidiEvent() {
        dispatcher.handle(rpc("noteInput/sendMidi", "{\"status\":176,\"data0\":1,\"data1\":64}"));

        verify(mockNoteInput).sendRawMidiEvent(176, 1, 64);
    }

    // --- Behavioral tests (Mockito) — Arpeggiator ---

    @Test
    void arpeggiatorConfigure_callsArpeggiatorSetters() {
        when(mockArpeggiator.mode()).thenReturn(mockArpMode);
        when(mockArpeggiator.octaves()).thenReturn(mockArpOctaves);
        when(mockArpeggiator.rate()).thenReturn(mockArpRate);
        when(mockArpeggiator.gateLength()).thenReturn(mockArpGateLength);
        when(mockArpeggiator.shuffle()).thenReturn(mockArpShuffle);

        dispatcher.handle(rpc("arpeggiator/configure",
            "{\"mode\":\"up\",\"octaves\":3,\"rate\":0.25,\"gateLength\":0.5,\"shuffle\":true}"));

        verify(mockArpMode).set("up");
        verify(mockArpOctaves).set(3);
        verify(mockArpRate).set(0.25);
        verify(mockArpGateLength).set(0.5);
        verify(mockArpShuffle).set(true);
    }

    @Test
    void arpeggiatorSetEnabled_callsArpeggiatorIsEnabledSet() {
        when(mockArpeggiator.isEnabled()).thenReturn(mockArpEnabled);

        dispatcher.handle(rpc("arpeggiator/setEnabled", "{\"enabled\":true}"));

        verify(mockArpEnabled).set(true);
    }

    @Test
    void arpeggiatorReleaseNotes_callsArpeggiatorReleaseNotes() {
        dispatcher.handle(rpc("arpeggiator/releaseNotes", "{}"));

        verify(mockArpeggiator).releaseNotes();
    }

    // --- Behavioral tests (Mockito) — NoteLatch ---

    @Test
    void noteLatchConfigure_callsNoteLatchSetters() {
        when(mockNoteLatch.mode()).thenReturn(mockLatchMode);
        when(mockNoteLatch.mono()).thenReturn(mockLatchMono);
        when(mockNoteLatch.velocityThreshold()).thenReturn(mockLatchVelocityThreshold);

        dispatcher.handle(rpc("noteLatch/configure",
            "{\"mode\":\"toggle\",\"mono\":true,\"velocityThreshold\":64}"));

        verify(mockLatchMode).set("toggle");
        verify(mockLatchMono).set(true);
        verify(mockLatchVelocityThreshold).set(64);
    }

    @Test
    void noteLatchSetEnabled_callsNoteLatchIsEnabledSet() {
        when(mockNoteLatch.isEnabled()).thenReturn(mockLatchEnabled);

        dispatcher.handle(rpc("noteLatch/setEnabled", "{\"enabled\":true}"));

        verify(mockLatchEnabled).set(true);
    }

    @Test
    void noteLatchReleaseNotes_callsNoteLatchReleaseNotes() {
        dispatcher.handle(rpc("noteLatch/releaseNotes", "{}"));

        verify(mockNoteLatch).releaseNotes();
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
