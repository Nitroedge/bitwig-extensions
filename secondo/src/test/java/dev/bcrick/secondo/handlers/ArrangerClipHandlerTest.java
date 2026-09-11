package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteOccurrence;
import com.bitwig.extension.controller.api.NoteStep;
import com.bitwig.extension.controller.api.SettableBeatTimeValue;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableColorValue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit coverage of the {@code arrangerClip/*} namespace.
 *
 * <p>Two properties this class exists to hold that a per-method test cannot:
 *
 * <ol>
 *   <li>The registration set is asserted as a <b>set</b>, not as a count and not as a list of
 *       {@code contains} checks. A stray port into {@code ArrangerClipHandler} — the one failure
 *       the phase's threat register calls elevation of privilege — fails a test here rather than
 *       passing review, because a name nobody approved makes the set unequal.</li>
 *   <li>{@code arrangerClip/getNotes} answers structurally identically to {@code clip/getNotes}
 *       for the same mocked {@code NoteStep}. The ~50-line grid walk now exists twice in the
 *       engine (deferred item D6-DEF-02) and the Python side has a single reader for both; the
 *       parity test is what stops the two drifting apart silently.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrangerClipHandlerTest {

    /**
     * The approved membership, as a set. Deliberately written out name by name rather than
     * derived from the handler: a test that derives its expectation from the thing under test
     * asserts nothing. This list is the D-05 checkpoint's answer transcribed, and it is the only
     * place in the repository where the membership is restated — the published count is derived
     * from the dispatcher by {@code api/list}, never typed.
     */
    private static final Set<String> APPROVED_METHODS = Set.of(
        "arrangerClip/getState",
        "arrangerClip/getNotes",
        "arrangerClip/setNotes",
        "arrangerClip/clearNote",
        "arrangerClip/clearAllNotes",
        "arrangerClip/setChance",
        "arrangerClip/setNoteExpressions",
        "arrangerClip/setNoteRepeat",
        "arrangerClip/setNoteOccurrence",
        "arrangerClip/setNoteRecurrence",
        "arrangerClip/setStepSize",
        "arrangerClip/scrollSteps",
        "arrangerClip/scrollToKey",
        "arrangerClip/scrollKeysPageUp",
        "arrangerClip/scrollKeysPageDown",
        "arrangerClip/transpose",
        "arrangerClip/quantize",
        "arrangerClip/rename",
        "arrangerClip/setColor",
        "arrangerClip/setPlaybackSettings"
    );

    /**
     * Names that must NOT be reachable under this prefix. Every one resolves a
     * {@code ClipLauncherSlot} or asserts launch semantics an arranger clip does not have, and
     * would therefore accept a call and do nothing. COVERAGE.md carries the reason for each.
     */
    private static final List<String> FORBIDDEN_METHODS = List.of(
        "arrangerClip/launch",
        "arrangerClip/launchAlt",
        "arrangerClip/launchRelease",
        "arrangerClip/launchReleaseAlt",
        "arrangerClip/stop",
        "arrangerClip/record",
        "arrangerClip/create",
        "arrangerClip/select",
        "arrangerClip/delete",
        "arrangerClip/duplicate",
        "arrangerClip/duplicateToSlot",
        "arrangerClip/showInEditor",
        "arrangerClip/setLaunchQuantization",
        "arrangerClip/setLaunchMode",
        "arrangerClip/setUseLoopStartAsQuantizationReference",
        "arrangerClip/getLaunchSettings",
        "arrangerClip/setShuffle",
        "arrangerClip/setAccent"
    );

    @Mock private Clip mockArrangerClip;
    @Mock private NoteStep mockNoteStep;
    @Mock private SettableColorValue mockColor;
    @Mock private SettableBeatTimeValue mockLoopStart;
    @Mock private SettableBeatTimeValue mockLoopLength;
    @Mock private SettableBeatTimeValue mockPlayStart;
    @Mock private SettableBeatTimeValue mockPlayStop;
    @Mock private SettableBooleanValue mockLoopEnabled;

    private StateCache stateCache;
    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        stateCache = new StateCache();
        dispatcher = new JsonRpcDispatcher();
        new ArrangerClipHandler(mockArrangerClip, stateCache).register(dispatcher);

        when(mockArrangerClip.getStep(0, 0, 60)).thenReturn(mockNoteStep);
        when(mockArrangerClip.color()).thenReturn(mockColor);
        when(mockArrangerClip.getLoopStart()).thenReturn(mockLoopStart);
        when(mockArrangerClip.getLoopLength()).thenReturn(mockLoopLength);
        when(mockArrangerClip.getPlayStart()).thenReturn(mockPlayStart);
        when(mockArrangerClip.getPlayStop()).thenReturn(mockPlayStop);
        when(mockArrangerClip.isLoopEnabled()).thenReturn(mockLoopEnabled);
    }

    // --- Registration set ---

    @Test
    void registersExactlyTheApprovedMembershipAndNothingElse() {
        assertEquals(APPROVED_METHODS, dispatcher.getRegisteredMethods(),
            "the registration set must equal the approved membership exactly — "
            + "an extra name is a method nobody decided to publish, "
            + "a missing name is a capability the namespace claims and does not have");
    }

    @Test
    void registersNoLaunchShapedOrSlotResolvingName() {
        Set<String> registered = dispatcher.getRegisteredMethods();
        for (String forbidden : FORBIDDEN_METHODS) {
            assertFalse(registered.contains(forbidden),
                forbidden + " must be absent by construction — an arranger clip has no "
                + "ClipLauncherSlot, so this would accept a call and do nothing");
        }
    }

    @Test
    void everyRegisteredNameCarriesTheArrangerClipPrefix() {
        for (String method : dispatcher.getRegisteredMethods()) {
            assertTrue(method.startsWith("arrangerClip/"),
                "this handler must not publish outside its own namespace, but registered: " + method);
        }
    }

    // --- arrangerClip/getState ---

    @Test
    void getState_delegatesToTheCacheAndOmitsWhatNoObserverHasWritten() {
        String response = dispatcher.handle(rpc("arrangerClip/getState", "{}"));
        JsonObject result = JsonParser.parseString(response).getAsJsonObject()
            .getAsJsonObject("result");

        // Finding F1, pinned. The dispatcher's Gson is a bare `new Gson()` with no
        // serializeNulls(), so a field no observer has written does not arrive as
        // `"exists": null` — the key VANISHES from the wire entirely. That is the strongest
        // available form of "absent means absent", and it is the contract: a consumer must ask
        // whether the key is present, not whether its value is null, and must never be handed a
        // 0.0 or "" standing in for a value nobody read.
        assertFalse(result.has("exists"), "an unobserved field must be absent, not null, not zero");
        assertFalse(result.has("loopLength"));
        assertFalse(result.has("playStart"));
        assertFalse(result.has("color"));
        assertFalse(result.has("stepSize"));

        // playingStep is the one field that is not nullable: it carries the launcher's own
        // declared -1 sentinel, so it is present even before anything observes it.
        assertEquals(-1, result.get("playingStep").getAsInt());
    }

    @Test
    void getState_publishesAFieldOnceSomethingHasWrittenIt() {
        // The other half of F1: omission is not the handler refusing to publish, it is the
        // absence of a writer. Write one and the key appears, in its declared position.
        //
        // This test cannot make `exists` lead, because only a live Bitwig observer writes it and
        // there is no observer in a unit test. That `exists` is first on the wire is settled by
        // live evidence instead (06-01 Task 3, engine SHA d30822d) and by the declaration order
        // in StateCache.getArrangerClipState() — it is deliberately NOT claimed here.
        stateCache.setArrangerClipStepSize(0.25);
        String response = dispatcher.handle(rpc("arrangerClip/getState", "{}"));
        JsonObject result = JsonParser.parseString(response).getAsJsonObject()
            .getAsJsonObject("result");
        assertTrue(result.has("stepSize"), "a field that HAS been written must appear");
        assertEquals(0.25, result.get("stepSize").getAsDouble());
        assertEquals(List.of("playingStep", "stepSize"), List.copyOf(result.keySet()),
            "surviving keys keep their declared order; only the unwritten ones drop out");
    }

    // --- arrangerClip/setNotes ---

    @Test
    void setNotes_callsSetStepOnTheArrangerClip() {
        dispatcher.handle(rpc("arrangerClip/setNotes",
            "{\"notes\":[{\"x\":0,\"y\":60,\"velocity\":0.8,\"duration\":0.5}]}"));
        // velocity 0.8 * 127 = 101 (int cast), matching the launcher's conversion exactly
        verify(mockArrangerClip).setStep(0, 0, 60, 101, 0.5);
    }

    @Test
    void setNotes_reportsTheDispatchedCount() {
        String response = dispatcher.handle(rpc("arrangerClip/setNotes",
            "{\"notes\":[{\"x\":0,\"y\":60},{\"x\":4,\"y\":64}]}"));
        assertContains(response, "\"count\":2");
    }

    @Test
    void setNotes_missingNotes_isRefused() {
        String response = dispatcher.handle(rpc("arrangerClip/setNotes", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "notes");
    }

    @Test
    void setNotes_xOutOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setNotes",
            "{\"notes\":[{\"x\":256,\"y\":60}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range (0-255)");
        verify(mockArrangerClip, never()).setStep(anyInt(), anyInt(), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void setNotes_yOutOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setNotes",
            "{\"notes\":[{\"x\":0,\"y\":128}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range (0-127)");
    }

    // --- arrangerClip/clearNote ---

    @Test
    void clearNote_callsClearStepOnTheArrangerClip() {
        dispatcher.handle(rpc("arrangerClip/clearNote", "{\"x\":0,\"y\":60}"));
        verify(mockArrangerClip).clearStep(0, 0, 60);
    }

    @Test
    void clearNote_xOutOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/clearNote", "{\"x\":999,\"y\":60}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range (0-255)");
        verify(mockArrangerClip, never()).clearStep(anyInt(), anyInt(), anyInt());
    }

    @Test
    void clearNote_yOutOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/clearNote", "{\"x\":0,\"y\":-1}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range (0-127)");
        verify(mockArrangerClip, never()).clearStep(anyInt(), anyInt(), anyInt());
    }

    @Test
    void clearNote_missingCoordinate_isRefused() {
        String response = dispatcher.handle(rpc("arrangerClip/clearNote", "{\"x\":0}"));
        assertContains(response, "-32602");
        assertMissingParam(response, "y");
    }

    // --- arrangerClip/clearAllNotes ---

    @Test
    void clearAllNotes_widensTheViewportBeforeClearing() {
        dispatcher.handle(rpc("arrangerClip/clearAllNotes", "{}"));
        // INVERTED, not deleted (06-VERIFICATION.md Gap 3, engine half). This used to assert
        // setStepSize(4.0) and scrollToStep(0) TWICE, with a comment saying "the restore is
        // unconditional rather than branching on whether there was anything to restore" — which
        // is precisely the defect: the unconditional restore also wrote 4.0 into the cache, and
        // getState publishes the cache. The restore now branches, so on the null path the widen
        // happens ONCE and nothing is written back.
        verify(mockArrangerClip, times(1)).setStepSize(4.0);
        verify(mockArrangerClip).clearSteps();
        verify(mockArrangerClip, times(1)).scrollToStep(0);
    }

    @Test
    void clearAllNotes_restoresTheStepSizeThatWasActuallyWritten() {
        // This is the point of the whole sequence, and the point at which the launcher twin
        // fails (finding O-12): its cache is never written, so it "restores" a default.
        dispatcher.handle(rpc("arrangerClip/setStepSize", "{\"size\":0.125}"));
        String response = dispatcher.handle(rpc("arrangerClip/clearAllNotes", "{}"));

        // Twice: once from the caller's own setStepSize, once from the restore.
        verify(mockArrangerClip, times(2)).setStepSize(0.125);
        verify(mockArrangerClip).setStepSize(4.0);     // the widen
        assertContains(response, "\"stepSize\":0.125");
        assertContains(response, "\"stepSizeRestored\":true");
        assertEquals(Double.valueOf(0.125), stateCache.getArrangerClipStepSize());
    }

    @Test
    void clearAllNotes_saysSoWhenThereWasNothingToRestore() {
        // INVERTED, not deleted. This asserted `stateCache.getArrangerClipStepSize()` was 4.0
        // after the call, under a comment claiming "the widened size is kept and reported,
        // rather than a default being invented and presented as a restoration". The reply field
        // was honest; the CACHE was not. getState publishes the cache, so writing 4.0 there made
        // getState report a resolution nobody chose — the invention the comment disclaimed,
        // one field over. The assertion is inverted to null; the wording is kept as the record
        // of what the defect was.
        String response = dispatcher.handle(rpc("arrangerClip/clearAllNotes", "{}"));
        assertContains(response, "\"stepSizeRestored\":false");
        assertContains(response, "\"stepSize\":4.0");
        assertNull(stateCache.getArrangerClipStepSize(),
            "with no prior value the cache must be left untouched, so getState omits stepSize "
            + "entirely rather than publishing 4.0 as something somebody set");
    }

    @Test
    void clearAllNotesLeavesTheCacheNullWhenNothingEverSetAStepSize() {
        String response = dispatcher.handle(rpc("arrangerClip/clearAllNotes", "{}"));

        assertNull(stateCache.getArrangerClipStepSize(),
            "nothing chose this value, so nothing may be cached under it");
        assertContains(response, "\"stepSizeRestored\":false");
        // The reply still reports the widen, because the widen genuinely happened on the Bitwig
        // clip. The reply describes a dispatch; the cache describes what somebody chose.
        assertContains(response, "\"stepSize\":4.0");
        // And the absence is visible where it matters. JsonRpcDispatcher builds a bare
        // new Gson() with no serializeNulls(), so a null member is DROPPED from the payload —
        // getState omits stepSize entirely rather than publishing 4.0 or null.
        JsonObject state = stateCache.getArrangerClipState();
        assertTrue(state.get("stepSize").isJsonNull(),
            "getState must not carry a stepSize value nobody set");
        assertFalse(new com.google.gson.Gson().toJson(state).contains("stepSize"),
            "the serialised getState payload must omit stepSize entirely, so a caller can tell "
            + "'nobody set a grid' apart from 'somebody set 4.0'");
    }

    @Test
    void clearAllNotesRestoresAndCachesAPreviouslySetStepSize() {
        dispatcher.handle(rpc("arrangerClip/setStepSize", "{\"size\":0.5}"));
        String response = dispatcher.handle(rpc("arrangerClip/clearAllNotes", "{}"));

        verify(mockArrangerClip).setStepSize(4.0);                 // the widen
        verify(mockArrangerClip, times(2)).setStepSize(0.5);       // caller's own, then restore
        assertEquals(Double.valueOf(0.5), stateCache.getArrangerClipStepSize(),
            "a value somebody DID set is restored and cached, unchanged by the null-path fix");
        assertContains(response, "\"stepSize\":0.5");
        assertContains(response, "\"stepSizeRestored\":true");
    }

    // --- arrangerClip/getNotes ---

    @Test
    void getNotes_answersAnArray() {
        stubEmptyGrid(mockArrangerClip);
        String response = dispatcher.handle(rpc("arrangerClip/getNotes", "{}"));
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(json.get("result").isJsonArray());
        assertEquals(0, json.getAsJsonArray("result").size());
    }

    /**
     * D6-DEF-02: the grid walk exists twice in the engine, once per {@code Clip}, with nothing
     * else enforcing that the two stay identical. The Python side has ONE reader
     * (`read_all_note_entries`) for both namespaces, so a drift would show up as two different
     * note shapes depending on which surface was read — and would agree with every other test
     * while disagreeing in production.
     */
    @Test
    void getNotes_serialisesIdenticallyToTheLauncherNamespace() {
        Clip launcherClip = mock(Clip.class);
        NoteStep sharedStep = mock(NoteStep.class);

        stubEmptyGrid(mockArrangerClip);
        stubEmptyGrid(launcherClip);
        when(mockArrangerClip.getStep(0, 0, 60)).thenReturn(sharedStep);
        when(launcherClip.getStep(0, 0, 60)).thenReturn(sharedStep);

        // One note carrying every optional block the walk knows how to emit, so a divergence in
        // any branch — not just the always-present fields — fails this test.
        when(sharedStep.state()).thenReturn(NoteStep.State.NoteOn);
        when(sharedStep.velocity()).thenReturn(0.83);
        when(sharedStep.duration()).thenReturn(1.0);
        when(sharedStep.isChanceEnabled()).thenReturn(true);
        when(sharedStep.chance()).thenReturn(0.75);
        when(sharedStep.pan()).thenReturn(0.25);
        when(sharedStep.timbre()).thenReturn(-0.5);
        when(sharedStep.pressure()).thenReturn(0.6);
        when(sharedStep.gain()).thenReturn(0.9);
        when(sharedStep.transpose()).thenReturn(2.0);
        when(sharedStep.releaseVelocity()).thenReturn(0.78);
        when(sharedStep.velocitySpread()).thenReturn(0.1);
        when(sharedStep.isMuted()).thenReturn(true);
        when(sharedStep.isOccurrenceEnabled()).thenReturn(true);
        when(sharedStep.occurrence()).thenReturn(NoteOccurrence.FILL);
        when(sharedStep.isRecurrenceEnabled()).thenReturn(true);
        when(sharedStep.recurrenceLength()).thenReturn(4);
        when(sharedStep.recurrenceMask()).thenReturn(5);
        when(sharedStep.isRepeatEnabled()).thenReturn(true);
        when(sharedStep.repeatCount()).thenReturn(3);
        when(sharedStep.repeatCurve()).thenReturn(0.4);
        when(sharedStep.repeatVelocityEnd()).thenReturn(-0.2);
        when(sharedStep.repeatVelocityCurve()).thenReturn(0.3);

        JsonRpcDispatcher launcherDispatcher = new JsonRpcDispatcher();
        new NoteHandler(launcherClip, new StateCache()).register(launcherDispatcher);

        String arrangerNotes = resultOf(dispatcher.handle(rpc("arrangerClip/getNotes", "{}")));
        String launcherNotes = resultOf(launcherDispatcher.handle(rpc("clip/getNotes", "{}")));

        assertEquals(launcherNotes, arrangerNotes,
            "the two grid walks must serialise a NoteStep identically — the Python side has one "
            + "reader for both namespaces and would silently see two note shapes if they drift");
    }

    // --- NoteStep property setters ---

    @Test
    void setChance_callsNoteStepSetChance() {
        dispatcher.handle(rpc("arrangerClip/setChance",
            "{\"notes\":[{\"x\":0,\"y\":60,\"chance\":0.75}]}"));
        verify(mockNoteStep).setChance(0.75);
        verify(mockNoteStep).setIsChanceEnabled(true);
    }

    @Test
    void setChance_outOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setChance",
            "{\"notes\":[{\"x\":0,\"y\":60,\"chance\":1.5}]}"));
        assertContains(response, "-32602");
        assertContains(response, "between 0.0 and 1.0");
    }

    @Test
    void setChance_coordinateOutOfRange_isRefused() {
        String response = dispatcher.handle(rpc("arrangerClip/setChance",
            "{\"notes\":[{\"x\":300,\"y\":60,\"chance\":0.5}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range (0-255)");
    }

    @Test
    void setNoteExpressions_pan_callsNoteStepSetPan() {
        dispatcher.handle(rpc("arrangerClip/setNoteExpressions",
            "{\"notes\":[{\"x\":0,\"y\":60,\"property\":\"pan\",\"value\":0.5}]}"));
        verify(mockNoteStep).setPan(0.5);
    }

    @Test
    void setNoteExpressions_unknownProperty_isRefusedWithTheValidSetNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setNoteExpressions",
            "{\"notes\":[{\"x\":0,\"y\":60,\"property\":\"loudness\",\"value\":0.5}]}"));
        assertContains(response, "-32602");
        assertContains(response, "unknown property");
        assertContains(response, "pan, timbre, pressure, gain");
    }

    @Test
    void setNoteExpressions_valueOutOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setNoteExpressions",
            "{\"notes\":[{\"x\":0,\"y\":60,\"property\":\"pan\",\"value\":2.0}]}"));
        assertContains(response, "-32602");
        assertContains(response, "between -1.0 and 1.0");
    }

    @Test
    void setNoteRepeat_callsNoteStepSetRepeatFields() {
        dispatcher.handle(rpc("arrangerClip/setNoteRepeat",
            "{\"notes\":[{\"x\":0,\"y\":60,\"count\":4,\"curve\":0.5,\"velocityEnd\":-0.3,\"velocityCurve\":0.2}]}"));
        verify(mockNoteStep).setRepeatCount(4);
        verify(mockNoteStep).setRepeatCurve(0.5);
        verify(mockNoteStep).setRepeatVelocityEnd(-0.3);
        verify(mockNoteStep).setRepeatVelocityCurve(0.2);
        verify(mockNoteStep).setIsRepeatEnabled(true);
    }

    @Test
    void setNoteRepeat_countOutOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setNoteRepeat",
            "{\"notes\":[{\"x\":0,\"y\":60,\"count\":200,\"curve\":0.0,\"velocityEnd\":0.0,\"velocityCurve\":0.0}]}"));
        assertContains(response, "-32602");
        assertContains(response, "between -127 and 127");
    }

    @Test
    void setNoteOccurrence_callsNoteStepSetOccurrence() {
        dispatcher.handle(rpc("arrangerClip/setNoteOccurrence",
            "{\"notes\":[{\"x\":0,\"y\":60,\"condition\":\"FILL\"}]}"));
        verify(mockNoteStep).setOccurrence(NoteOccurrence.FILL);
        verify(mockNoteStep).setIsOccurrenceEnabled(true);
    }

    @Test
    void setNoteOccurrence_unknownCondition_isRefusedWithTheValidSetNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setNoteOccurrence",
            "{\"notes\":[{\"x\":0,\"y\":60,\"condition\":\"SOMETIMES\"}]}"));
        assertContains(response, "-32602");
        assertContains(response, "unknown occurrence");
        assertContains(response, "ALWAYS");
    }

    @Test
    void setNoteRecurrence_callsNoteStepSetRecurrence() {
        dispatcher.handle(rpc("arrangerClip/setNoteRecurrence",
            "{\"notes\":[{\"x\":0,\"y\":60,\"length\":4,\"mask\":5}]}"));
        verify(mockNoteStep).setRecurrence(4, 5);
        verify(mockNoteStep).setIsRecurrenceEnabled(true);
    }

    @Test
    void setNoteRecurrence_lengthOutOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setNoteRecurrence",
            "{\"notes\":[{\"x\":0,\"y\":60,\"length\":10,\"mask\":1}]}"));
        assertContains(response, "-32602");
        assertContains(response, "between 1 and 8");
    }

    // --- Viewport ---

    @Test
    void setStepSize_callsSetStepSizeAndRecordsItInTheCache() {
        dispatcher.handle(rpc("arrangerClip/setStepSize", "{\"size\":0.25}"));
        verify(mockArrangerClip).setStepSize(0.25);
        assertEquals(Double.valueOf(0.25), stateCache.getArrangerClipStepSize(),
            "the API publishes no step-size getter, so the cache is the only record there is");
    }

    @Test
    void scrollSteps_callsScrollToStep() {
        dispatcher.handle(rpc("arrangerClip/scrollSteps", "{\"offset\":16}"));
        verify(mockArrangerClip).scrollToStep(16);
    }

    @Test
    void scrollToKey_callsScrollToKey() {
        dispatcher.handle(rpc("arrangerClip/scrollToKey", "{\"key\":60}"));
        verify(mockArrangerClip).scrollToKey(60);
    }

    @Test
    void scrollToKey_outOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/scrollToKey", "{\"key\":200}"));
        assertContains(response, "-32602");
        assertContains(response, "key must be 0-127");
        verify(mockArrangerClip, never()).scrollToKey(anyInt());
    }

    @Test
    void scrollKeysPageUp_callsScrollKeysPageUp() {
        dispatcher.handle(rpc("arrangerClip/scrollKeysPageUp", "{}"));
        verify(mockArrangerClip).scrollKeysPageUp();
    }

    @Test
    void scrollKeysPageDown_callsScrollKeysPageDown() {
        dispatcher.handle(rpc("arrangerClip/scrollKeysPageDown", "{}"));
        verify(mockArrangerClip).scrollKeysPageDown();
    }

    // --- Transforms ---

    @Test
    void transpose_callsTranspose() {
        dispatcher.handle(rpc("arrangerClip/transpose", "{\"semitones\":12}"));
        verify(mockArrangerClip).transpose(12);
    }

    @Test
    void transpose_appliesNoEngineSideBound() {
        // Deliberate, and asserted so a later reader does not "fix" it. The launcher's
        // clip/transpose has no bound either; the guard lives above both surfaces. A second,
        // different bound here would make the two surfaces silently disagree.
        dispatcher.handle(rpc("arrangerClip/transpose", "{\"semitones\":9999}"));
        verify(mockArrangerClip).transpose(9999);
    }

    @Test
    void quantize_callsQuantize() {
        dispatcher.handle(rpc("arrangerClip/quantize", "{\"amount\":0.5}"));
        verify(mockArrangerClip).quantize(0.5);
    }

    @Test
    void quantize_outOfRange_isRefusedWithTheBoundNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/quantize", "{\"amount\":5.0}"));
        assertContains(response, "-32602");
        assertContains(response, "between 0.0 and 1.0");
        verify(mockArrangerClip, never()).quantize(anyDouble());
    }

    @Test
    void quantize_missingAmount_isRefused() {
        String response = dispatcher.handle(rpc("arrangerClip/quantize", "{}"));
        assertContains(response, "-32602");
        assertMissingParam(response, "amount");
    }

    // --- Identity ---

    @Test
    void rename_callsSetNameOnTheClipRatherThanAnySlot() {
        dispatcher.handle(rpc("arrangerClip/rename", "{\"name\":\"Chorus\"}"));
        verify(mockArrangerClip).setName("Chorus");
    }

    @Test
    void rename_missingName_isRefused() {
        String response = dispatcher.handle(rpc("arrangerClip/rename", "{}"));
        assertContains(response, "-32602");
        assertMissingParam(response, "name");
        verify(mockArrangerClip, never()).setName(anyString());
    }

    @Test
    void setColor_setsTheClipsOwnColourNotASlots() {
        dispatcher.handle(rpc("arrangerClip/setColor", "{\"r\":1.0,\"g\":0.5,\"b\":0.0}"));
        verify(mockColor).set(1.0f, 0.5f, 0.0f);
    }

    @Test
    void setColor_componentOutOfRange_isRefusedWithTheComponentNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setColor", "{\"r\":1.5,\"g\":0.5,\"b\":0.0}"));
        assertContains(response, "-32602");
        assertContains(response, "r must be between 0.0 and 1.0");
        verify(mockColor, never()).set(anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    void setColor_missingComponent_isRefused() {
        String response = dispatcher.handle(rpc("arrangerClip/setColor", "{\"r\":1.0,\"g\":0.5}"));
        assertContains(response, "-32602");
        assertMissingParam(response, "b");
    }

    // --- The folded playback settings ---

    @Test
    void setPlaybackSettings_appliesEveryFieldItWasGiven() {
        String response = dispatcher.handle(rpc("arrangerClip/setPlaybackSettings",
            "{\"loopStart\":1.0,\"loopLength\":8.0,\"loopEnabled\":true,\"playStart\":0.0,\"playStop\":16.0}"));
        verify(mockLoopStart).set(1.0);
        verify(mockLoopLength).set(8.0);
        verify(mockLoopEnabled).set(true);
        verify(mockPlayStart).set(0.0);
        verify(mockPlayStop).set(16.0);
        assertContains(response, "\"applied\"");
    }

    @Test
    void setPlaybackSettings_leavesAbsentFieldsAlone() {
        String response = dispatcher.handle(rpc("arrangerClip/setPlaybackSettings",
            "{\"loopLength\":8.0}"));
        verify(mockLoopLength).set(8.0);
        verify(mockLoopStart, never()).set(anyDouble());
        verify(mockPlayStart, never()).set(anyDouble());
        verify(mockPlayStop, never()).set(anyDouble());
        verify(mockLoopEnabled, never()).set(anyBoolean());
        assertContains(response, "[\"loopLength\"]");
    }

    @Test
    void setPlaybackSettings_treatsAnExplicitNullAsAbsent() {
        String response = dispatcher.handle(rpc("arrangerClip/setPlaybackSettings",
            "{\"loopLength\":8.0,\"playStop\":null}"));
        verify(mockLoopLength).set(8.0);
        verify(mockPlayStop, never()).set(anyDouble());
        assertContains(response, "[\"loopLength\"]");
    }

    @Test
    void setPlaybackSettings_keepsTheLoopRegionAndPlayRangeApart() {
        // Finding F3: loopStart/loopLength are the clip's internal LOOP REGION and
        // playStart/playStop are its PLAY RANGE. They were observed at 9.17 against 16.0 on an
        // ordinary 4-bar clip, so a handler that routed one pair to the other's setters would
        // be wrong by whatever the user's loop region happens to be.
        dispatcher.handle(rpc("arrangerClip/setPlaybackSettings", "{\"loopLength\":2.0}"));
        verify(mockLoopLength).set(2.0);
        verify(mockPlayStop, never()).set(anyDouble());

        dispatcher.handle(rpc("arrangerClip/setPlaybackSettings", "{\"playStop\":16.0}"));
        verify(mockPlayStop).set(16.0);
        verify(mockLoopLength, never()).set(16.0);
    }

    @Test
    void setPlaybackSettings_withNoFields_isRefusedWithAllFiveNamed() {
        String response = dispatcher.handle(rpc("arrangerClip/setPlaybackSettings", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "loopStart");
        assertContains(response, "loopLength");
        assertContains(response, "loopEnabled");
        assertContains(response, "playStart");
        assertContains(response, "playStop");
    }

    // --- Helpers ---

    /**
     * Every coordinate answers an empty step, so the grid walk can run without stubbing 32768
     * of them individually. Specific coordinates are stubbed over this afterwards.
     */
    private void stubEmptyGrid(Clip clip) {
        NoteStep empty = mock(NoteStep.class);
        when(empty.state()).thenReturn(NoteStep.State.Empty);
        when(clip.getStep(anyInt(), anyInt(), anyInt())).thenReturn(empty);
    }

    private String resultOf(String response) {
        return JsonParser.parseString(response).getAsJsonObject().get("result").toString();
    }

    /**
     * A single quote AS IT REACHES THE WIRE. The dispatcher serialises with a bare
     * {@code new Gson()}, which HTML-escapes by default, so the apostrophes
     * {@code JsonParamValidator} writes into "missing 'x' parameter" arrive as the six
     * characters {@code '}. Asserted in wire form because that is what a consumer matching
     * on the message actually sees; built by concatenation so the Java source itself carries no
     * escape a reader has to decode twice.
     */
    private static final String WIRE_QUOTE = "\\" + "u0027";

    private void assertMissingParam(String response, String param) {
        assertContains(response, "missing " + WIRE_QUOTE + param + WIRE_QUOTE + " parameter");
    }

    private String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}";
    }

    private void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected),
            "Expected '" + expected + "' in: " + actual);
    }
}
