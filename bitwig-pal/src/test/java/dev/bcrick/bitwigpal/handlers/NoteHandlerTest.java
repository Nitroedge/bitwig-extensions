package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteOccurrence;
import com.bitwig.extension.controller.api.NoteStep;
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
class NoteHandlerTest {

    @Mock private Clip mockCursorClip;
    @Mock private NoteStep mockNoteStep;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new NoteHandler(mockCursorClip, new StateCache()).register(dispatcher);

        // Common stub: cursorClip.getStep(0, x, y) returns mockNoteStep
        when(mockCursorClip.getStep(0, 0, 60)).thenReturn(mockNoteStep);
    }

    // --- Registration ---

    @Test
    void registersAllNoteBaseMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("clip/setNotes"));
        assertTrue(methods.contains("clip/clearNote"));
        assertTrue(methods.contains("clip/clearAllNotes"));
        assertTrue(methods.contains("clip/getNotes"));
        assertTrue(methods.contains("clip/setChance"));
        assertTrue(methods.contains("clip/setStepSize"));
        assertTrue(methods.contains("clip/scrollSteps"));
    }

    @Test
    void registersSetNoteExpressions() {
        assertTrue(dispatcher.getRegisteredMethods().contains("clip/setNoteExpressions"));
    }

    @Test
    void registersSetNoteRepeat() {
        assertTrue(dispatcher.getRegisteredMethods().contains("clip/setNoteRepeat"));
    }

    @Test
    void registersSetNoteOccurrence() {
        assertTrue(dispatcher.getRegisteredMethods().contains("clip/setNoteOccurrence"));
    }

    @Test
    void registersSetNoteRecurrence() {
        assertTrue(dispatcher.getRegisteredMethods().contains("clip/setNoteRecurrence"));
    }

    @Test
    void registersExactlySixteenMethods() {
        assertEquals(16, dispatcher.getRegisteredMethods().size());
    }

    // --- clip/setNotes validation ---

    @Test
    void setNotes_missingNotes_returnsError() {
        String response = dispatcher.handle(rpc("clip/setNotes", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "notes");
    }

    @Test
    void setNotes_xOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("clip/setNotes",
            "{\"notes\":[{\"x\":256,\"y\":60,\"velocity\":100,\"duration\":1}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    @Test
    void setNotes_yOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("clip/setNotes",
            "{\"notes\":[{\"x\":0,\"y\":128,\"velocity\":100,\"duration\":1}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- clip/setChance validation ---

    @Test
    void setChance_chanceOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("clip/setChance",
            "{\"notes\":[{\"x\":0,\"y\":60,\"chance\":1.5}]}"));
        assertContains(response, "-32602");
        assertContains(response, "between 0.0 and 1.0");
    }

    // --- clip/setNoteOccurrence validation ---

    @Test
    void setNoteOccurrence_unknownCondition_returnsError() {
        String response = dispatcher.handle(rpc("clip/setNoteOccurrence",
            "{\"notes\":[{\"x\":0,\"y\":60,\"condition\":\"INVALID\"}]}"));
        assertContains(response, "-32602");
        assertContains(response, "unknown occurrence");
    }

    // --- clip/setNoteRepeat validation ---

    @Test
    void setNoteRepeat_countOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("clip/setNoteRepeat",
            "{\"notes\":[{\"x\":0,\"y\":60,\"count\":200,\"curve\":0.0,\"velocityEnd\":0.0,\"velocityCurve\":0.0}]}"));
        assertContains(response, "-32602");
        assertContains(response, "between -127 and 127");
    }

    // --- clip/setNoteRecurrence validation ---

    @Test
    void setNoteRecurrence_lengthOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("clip/setNoteRecurrence",
            "{\"notes\":[{\"x\":0,\"y\":60,\"length\":10,\"mask\":1}]}"));
        assertContains(response, "-32602");
        assertContains(response, "between 1 and 8");
    }

    // --- clip/setNoteExpressions validation ---

    @Test
    void setNoteExpressions_missingNotes_returnsError() {
        String response = dispatcher.handle(rpc("clip/setNoteExpressions", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "notes");
    }

    // --- NoteOccurrence enum coverage ---

    @Test
    void noteOccurrenceEnumHasElevenValues() {
        var values = NoteOccurrence.values();
        assertEquals(11, values.length, "NoteOccurrence should have 11 enum values");
    }

    @Test
    void noteOccurrenceEnumContainsExpectedValues() {
        var names = java.util.Arrays.stream(NoteOccurrence.values())
            .map(Enum::name)
            .toList();
        assertTrue(names.contains("ALWAYS"));
        assertTrue(names.contains("FIRST"));
        assertTrue(names.contains("NOT_FIRST"));
        assertTrue(names.contains("PREV"));
        assertTrue(names.contains("NOT_PREV"));
        assertTrue(names.contains("PREV_CHANNEL"));
        assertTrue(names.contains("NOT_PREV_CHANNEL"));
        assertTrue(names.contains("PREV_KEY"));
        assertTrue(names.contains("NOT_PREV_KEY"));
        assertTrue(names.contains("FILL"));
        assertTrue(names.contains("NOT_FILL"));
    }

    // --- Behavioral tests (Mockito) — Cursor clip direct calls ---

    @Test
    void setNotes_callsCursorClipSetStep() {
        dispatcher.handle(rpc("clip/setNotes",
            "{\"notes\":[{\"x\":0,\"y\":60,\"velocity\":0.8,\"duration\":0.5}]}"));
        // velocity 0.8 * 127 = 101 (int cast)
        verify(mockCursorClip).setStep(0, 0, 60, 101, 0.5);
    }

    @Test
    void clearNote_callsCursorClipClearStep() {
        dispatcher.handle(rpc("clip/clearNote", "{\"x\":0,\"y\":60}"));
        verify(mockCursorClip).clearStep(0, 0, 60);
    }

    @Test
    void clearAllNotes_callsCursorClipClearSteps() {
        dispatcher.handle(rpc("clip/clearAllNotes", "{}"));
        verify(mockCursorClip).setStepSize(4.0);
        verify(mockCursorClip).clearSteps();
    }

    @Test
    void setStepSize_callsCursorClipSetStepSize() {
        dispatcher.handle(rpc("clip/setStepSize", "{\"size\":0.25}"));
        verify(mockCursorClip).setStepSize(0.25);
    }

    @Test
    void scrollSteps_callsCursorClipScrollToStep() {
        dispatcher.handle(rpc("clip/scrollSteps", "{\"offset\":16}"));
        verify(mockCursorClip).scrollToStep(16);
    }

    // --- Behavioral tests (Mockito) — NoteStep operations ---

    @Test
    void setChance_callsNoteStepSetChance() {
        dispatcher.handle(rpc("clip/setChance",
            "{\"notes\":[{\"x\":0,\"y\":60,\"chance\":0.75}]}"));
        verify(mockNoteStep).setChance(0.75);
        verify(mockNoteStep).setIsChanceEnabled(true);
    }

    @Test
    void setNoteExpressions_pan_callsNoteStepSetPan() {
        dispatcher.handle(rpc("clip/setNoteExpressions",
            "{\"notes\":[{\"x\":0,\"y\":60,\"property\":\"pan\",\"value\":0.5}]}"));
        verify(mockNoteStep).setPan(0.5);
    }

    @Test
    void setNoteRepeat_callsNoteStepSetRepeatFields() {
        dispatcher.handle(rpc("clip/setNoteRepeat",
            "{\"notes\":[{\"x\":0,\"y\":60,\"count\":4,\"curve\":0.5,\"velocityEnd\":-0.3,\"velocityCurve\":0.2}]}"));
        verify(mockNoteStep).setRepeatCount(4);
        verify(mockNoteStep).setRepeatCurve(0.5);
        verify(mockNoteStep).setRepeatVelocityEnd(-0.3);
        verify(mockNoteStep).setRepeatVelocityCurve(0.2);
        verify(mockNoteStep).setIsRepeatEnabled(true);
    }

    @Test
    void setNoteOccurrence_callsNoteStepSetOccurrence() {
        dispatcher.handle(rpc("clip/setNoteOccurrence",
            "{\"notes\":[{\"x\":0,\"y\":60,\"condition\":\"FILL\"}]}"));
        verify(mockNoteStep).setOccurrence(NoteOccurrence.FILL);
        verify(mockNoteStep).setIsOccurrenceEnabled(true);
    }

    @Test
    void setNoteRecurrence_callsNoteStepSetRecurrence() {
        dispatcher.handle(rpc("clip/setNoteRecurrence",
            "{\"notes\":[{\"x\":0,\"y\":60,\"length\":4,\"mask\":5}]}"));
        verify(mockNoteStep).setRecurrence(4, 5);
        verify(mockNoteStep).setIsRecurrenceEnabled(true);
    }

    // --- Clip key scrolling ---

    @Test
    void scrollToKey_callsCursorClipScrollToKey() {
        dispatcher.handle(rpc("note/scrollToKey", "{\"key\":60}"));
        verify(mockCursorClip).scrollToKey(60);
    }

    @Test
    void scrollToKey_invalidKey_returnsError() {
        String response = dispatcher.handle(rpc("note/scrollToKey", "{\"key\":128}"));
        assertContains(response, "-32602");
    }

    @Test
    void scrollKeysPageUp_callsCursorClip() {
        dispatcher.handle(rpc("note/scrollKeysPageUp", "{}"));
        verify(mockCursorClip).scrollKeysPageUp();
    }

    @Test
    void scrollKeysPageDown_callsCursorClip() {
        dispatcher.handle(rpc("note/scrollKeysPageDown", "{}"));
        verify(mockCursorClip).scrollKeysPageDown();
    }

    @Test
    void scrollKeysStepUp_callsCursorClip() {
        dispatcher.handle(rpc("note/scrollKeysStepUp", "{}"));
        verify(mockCursorClip).scrollKeysStepUp();
    }

    @Test
    void scrollKeysStepDown_callsCursorClip() {
        dispatcher.handle(rpc("note/scrollKeysStepDown", "{}"));
        verify(mockCursorClip).scrollKeysStepDown();
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
