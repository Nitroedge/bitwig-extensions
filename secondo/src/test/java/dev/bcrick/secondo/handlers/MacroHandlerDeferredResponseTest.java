package dev.bcrick.secondo.handlers;

import com.google.gson.*;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.extension.StateCacheTestHelper;
import dev.bcrick.secondo.rpc.CommandQueue;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.rpc.JsonRpcError;
import dev.bcrick.secondo.rpc.TaskScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deferred {@code macro/writeClip} response (Phase 29, plan 29-01): the phase's whole claim,
 * expressed as assertions that would fail against the handler at pin {@code 61b48d5}.
 *
 * <h2>Why this class exists beside {@link MacroHandlerWrongSlotTest}</h2>
 *
 * <p>For the reason that class gives at its own {@code :20-27} for existing beside
 * {@code MacroHandlerTest}: the harness is different, not the assertion. That class calls
 * {@code dispatcher.handle(...)} directly and reads the returned string, which is exactly what
 * cannot express a deferral -- a deferred request HAS no returned response string, it has an
 * outstanding future. So this class drives {@code queue.enqueue(...)} and
 * {@code queue.drainAndExecute(...)} instead, and asserts on
 * {@link CompletableFuture#isDone()} -- the one observable that distinguishes "answered" from
 * "still owed".
 *
 * <h2>The threading model, and the one deliberately not modelled</h2>
 *
 * <p>Everything here runs on ONE thread, exactly as {@code MacroHandlerWrongSlotTest.java:30-39}
 * insists: the test thread drains the queue, the handler returns, and only THEN does the test
 * tick the parked scheduler. Production imposes that order -- handlers run from
 * {@code CommandQueue.drainAndExecute} inside Bitwig's {@code flush()} on the Control Surface
 * Session thread, and {@code host.scheduleTask} schedules onto that same thread. A test that put
 * the handler on one thread and the scheduler on another would model an arrangement production
 * does not have, and would stay green against a handler that deadlocks in Bitwig -- which, as
 * {@code MacroHandler.java:42} puts it, is a deadlock, not a slow path.
 *
 * <p>Nothing here sleeps, and no assertion can be satisfied by waiting for wall-clock time to
 * pass. Time in this class IS the parked-task list being ticked, and nothing else.
 *
 * <h2>The sentinel is never asserted on</h2>
 *
 * <p>The deferral signal is consumed by {@code drainAndExecute} and must never reach a future.
 * Every assertion below parses the completed future's string as JSON-RPC and reads {@code result}
 * or {@code error}. If the sentinel ever showed up in a future, these tests would fail on the
 * parse, which is the correct way for that regression to announce itself.
 */
class MacroHandlerDeferredResponseTest {

    /** Bounds {@link #drainPending()} so a scheduling bug fails loudly instead of hanging the suite. */
    private static final int MAX_DRAIN_ROUNDS = 50;

    /**
     * Mirrors {@code MacroHandler.FLUSH_DELAY_MS}, which is private there and deliberately stays
     * private -- this class is a consumer of the figure, not a second declaration of it.
     */
    private static final long FLUSH_MS = 100;

    /**
     * Mirrors {@code MacroHandler.DEFERRAL_DEADLINE_MS}. Written as a literal on purpose: the
     * deadline is a PUBLISHED contract (docs/rpc-api-reference.md, the mock, the Python client's
     * own five-second wall), so a test that read the constant back out of the handler could never
     * notice the handler changing it. This literal is one of the two ends it is pinned at.
     */
    private static final long DEADLINE_MS = 3000;

    private CommandQueue queue;
    private JsonRpcDispatcher dispatcher;
    private StateCache stateCache;
    private List<String> callLog;
    private List<String> errorLog;
    private List<ParkedTask> pending;

    /**
     * Virtual time, in milliseconds. Advanced only by running a parked task, never by the wall
     * clock -- see the class comment: time in here IS the parked-task list being ticked.
     */
    private long clock;

    /** Set by a test to make {@code clip/create} fail, so {@code startNextJob}'s own catch fires. */
    private boolean clipCreateThrows;

    /** Set by a test to make {@code clip/setNotes} fail, so the write-failure path (-32012) fires. */
    private boolean setNotesThrows;

    /** Set by a test to make {@code clip/delete} fail, so a removal that was tried can miss. */
    private boolean clipDeleteThrows;

    @BeforeEach
    void setUp() {
        queue = new CommandQueue();
        dispatcher = new JsonRpcDispatcher();
        stateCache = new StateCache();
        callLog = new ArrayList<>();
        errorLog = new ArrayList<>();
        pending = new ArrayList<>();
        clock = 0;
        clipCreateThrows = false;
        setNotesThrows = false;
        clipDeleteThrows = false;

        // A DEFERRING scheduler: the task is parked, not run. The flush window becomes a place
        // the test can stand, which is what makes "the future is not done yet" assertable.
        //
        // It records the DELAY each task was scheduled with, and the virtual instant it comes due,
        // not the runnable alone. Both are load-bearing since plan 29-02. The delay is what lets a
        // test say "run ONLY the task parked at the deadline" without a sleep and without a second
        // thread. The due instant is what keeps the deadline from firing ahead of a verify hop
        // parked at 100 ms: tasks run in due order, so a 3000 ms deadline cannot jump the queue
        // and answer a caller whose write was about to succeed 2.9 seconds earlier.
        TaskScheduler deferring =
            (task, delayMs) -> pending.add(new ParkedTask(task, delayMs, clock + delayMs));

        dispatcher.register("clip/create", params -> {
            if (clipCreateThrows) {
                throw new IllegalStateException("slot is not writable");
            }
            callLog.add("clip/create:t" + params.get("trackIndex").getAsInt()
                + "s" + params.get("slotIndex").getAsInt());
            return new JsonPrimitive("ok");
        });
        // STATEFUL: selecting a clip moves the modelled cursor, and the verify reads it.
        dispatcher.register("clip/select", params -> {
            int trackIndex = params.get("trackIndex").getAsInt();
            int slotIndex = params.get("slotIndex").getAsInt();
            callLog.add("clip/select:t" + trackIndex + "s" + slotIndex);
            StateCacheTestHelper.setClipCursorPosition(stateCache, trackIndex, slotIndex);
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setStepSize", params -> {
            callLog.add("clip/setStepSize:" + params.get("size").getAsDouble());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setNotes", params -> {
            int count = params.getAsJsonArray("notes").size();
            if (setNotesThrows) {
                throw new IllegalStateException("clip is not writable");
            }
            callLog.add("clip/setNotes:" + count);
            return new JsonPrimitive(count);
        });
        // The undo-on-refusal route (plan 29-03). Coordinate-addressed, so the call log records
        // WHICH slot was removed — the point of T-29-06 is that it is never the cursor's.
        dispatcher.register("clip/delete", params -> {
            if (clipDeleteThrows) {
                throw new IllegalStateException("the slot could not be cleared");
            }
            callLog.add("clip/delete:t" + params.get("trackIndex").getAsInt()
                + "s" + params.get("slotIndex").getAsInt());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/rename", params -> {
            callLog.add("clip/rename:" + params.get("name").getAsString());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setChance", params -> {
            callLog.add("clip/setChance:" + params.getAsJsonArray("notes").size());
            return new JsonPrimitive("ok");
        });
        // Only macro/buildSection reaches this, and only to name the scene it was handed. It is
        // registered so the queue-busy case can be driven by a REAL chain macro rather than by a
        // second writeClip — the chain is the case the deadline provably cannot serve.
        dispatcher.register("scene/rename", params -> {
            callLog.add("scene/rename:" + params.get("name").getAsString());
            return new JsonPrimitive("ok");
        });

        new MacroHandler(dispatcher, stateCache, deferring, errorLog::add).register(dispatcher);
    }

    // --- 1. The response is OWED: the claim this phase exists to make ---

    @Test
    void writeClip_leavesTheFutureOutstandingAfterOneDrain() {
        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);

        int count = queue.drainAndExecute(dispatcher);

        // The command WAS executed -- phase 1 ran -- and the caller is still waiting.
        assertEquals(1, count, "the command was not drained");
        assertEquals(List.of("clip/create:t0s7", "clip/select:t0s7"), callLog,
            "phase 1 should have run and nothing more: " + callLog);
        assertFalse(future.isDone(),
            "the future was completed while the write was still in flight — this is the"
                + " accepted-for-dispatch answer Phase 29 exists to replace");
    }

    // --- 2. And it is ANSWERED later, with the real outcome ---

    @Test
    void writeClip_completesWithTheRealCountOnceTheSchedulerTicks() throws Exception {
        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);
        assertFalse(future.isDone());

        // The cursor agrees for the whole verify window; the flush cycles tick by.
        drainPending();

        assertTrue(future.isDone(), "the deferred response was never completed");
        JsonObject result = resultOf(future);
        assertEquals(2, result.get("count").getAsInt(),
            "the answer must report what LANDED, not what was accepted");
        assertTrue(result.get("deferred").getAsBoolean(),
            "a deferred answer must say it is one");
        assertTrue(result.get("deferReason").isJsonNull(),
            "deferReason must be an explicit JSON null, never an omitted key");
        assertTrue(callLog.contains("clip/setNotes:2"),
            "the notes never actually reached the cursor: " + callLog);
    }

    // --- 3. -32011 REACHES THE CALLER. This is the row that was transferred out of Phase 23 ---

    @Test
    void writeClip_completesWithCursorMismatchWhenTheCursorNeverAgrees() throws Exception {
        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);

        // Somebody else moves the cursor inside the window — a user click, or another surface.
        // It stays moved for the whole verify ceiling, so the re-poll cannot rescue it.
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"clip/select\","
            + "\"params\":{\"trackIndex\":0,\"slotIndex\":5,\"force\":true},\"id\":99}");
        queue.drainAndExecute(dispatcher);

        drainPending();

        assertTrue(future.isDone(), "the refused write never answered its caller");
        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.CURSOR_MISMATCH, error.get("code").getAsInt(),
            "-32011 must travel in the RESPONSE, not only in the console marker and the snapshot");
        assertEquals(-32011, error.get("code").getAsInt(), "the code is pinned at both ends");

        JsonObject data = error.getAsJsonObject("data");
        assertEquals(0, data.get("cursorTrack").getAsInt());
        assertEquals(5, data.get("cursorScene").getAsInt());
        assertEquals(0, data.get("requestedTrack").getAsInt());
        assertEquals(7, data.get("requestedScene").getAsInt());

        // Nothing was written, and both pre-Phase-29 announcements still happened.
        assertFalse(callLog.contains("clip/setNotes:2"),
            "notes were written to a slot the caller did not name: " + callLog);
        assertEquals(1, stateCache.getWriteClipRefusals());
        assertTrue(errorLog.get(0).startsWith("SECONDO-CURSOR-MISMATCH"),
            "the greppable console marker must survive the deferral: " + errorLog);
    }

    // --- 4. A notification is NOT a deferral, and the two must stay distinguishable ---

    @Test
    void writeClip_asNotificationCompletesWithNullAndTakesNoDeferral() throws Exception {
        // No id = notification. The deferral signal is an identity sentinel precisely so that
        // this path, which already completes with null and already means HTTP 204, cannot be
        // confused with a response that is still owed.
        CompletableFuture<String> future = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"macro/writeClip\",\"params\":"
                + writeParams(0, 7, TWO_NOTES) + "}");

        queue.drainAndExecute(dispatcher);

        assertTrue(future.isDone(), "a notification must not be left outstanding");
        assertNull(future.get(1, TimeUnit.SECONDS),
            "a notification completes with null, exactly as it did before Phase 29");

        // The write itself still happens; only the answer is absent.
        drainPending();
        assertTrue(callLog.contains("clip/setNotes:2"), "the notification's write never ran");
    }

    // --- 5. A batch answers in its array; deferral is switched off for its duration ---

    @Test
    void writeClip_inABatchAnswersInTheArrayAndTakesNoDeferral() throws Exception {
        CompletableFuture<String> future = queue.enqueue(
            "[{\"jsonrpc\":\"2.0\",\"method\":\"macro/writeClip\",\"params\":"
                + writeParams(0, 7, TWO_NOTES) + ",\"id\":1}]");

        queue.drainAndExecute(dispatcher);

        // A batch builds ONE array and ships it when the loop ends. A deferred element would have
        // no place in it and no second array to travel in, so the batch path must not defer.
        assertTrue(future.isDone(), "a batch element deferred and stranded the whole array");
        JsonArray responses = JsonParser.parseString(future.get(1, TimeUnit.SECONDS))
            .getAsJsonArray();
        assertEquals(1, responses.size());
        JsonObject result = responses.get(0).getAsJsonObject().getAsJsonObject("result");
        assertFalse(result.get("deferred").getAsBoolean(),
            "the batch element must report that it did NOT defer");
        assertEquals("not-deferrable", result.get("deferReason").getAsString());
        assertEquals(2, result.get("count").getAsInt(),
            "the batch element keeps the pre-Phase-29 accepted count");
    }

    // --- 6. handleInternal — the session/transaction path D-29-15 rules on ---

    @Test
    void writeClip_throughHandleInternalDoesNotDeferAndRestoresTheSlot() throws Exception {
        // Reached the way TransactionHandler reaches it. There is no top-level request in flight
        // at all here, and even if there were, handleInternal clears the slot for its duration.
        JsonElement internal = dispatcher.handleInternal(
            "macro/writeClip", JsonParser.parseString(writeParams(0, 7, TWO_NOTES))
                .getAsJsonObject());

        JsonObject result = internal.getAsJsonObject();
        assertFalse(result.get("deferred").getAsBoolean(),
            "an operation inside a transaction must not defer — the transaction's own response"
                + " has already been built around its results");
        assertEquals("not-deferrable", result.get("deferReason").getAsString());
        drainPending();

        // AND the slot is restored: a following top-level request can still defer. Without the
        // save/clear/restore in handleInternal's finally, this second write would answer at once.
        callLog.clear();
        errorLog.clear();
        CompletableFuture<String> future = enqueueWriteClip(1, 2, TWO_NOTES, 2);
        queue.drainAndExecute(dispatcher);
        assertFalse(future.isDone(),
            "the current-request slot was not restored after the internal dispatch, so a later"
                + " top-level request could no longer claim its own response");
    }

    // --- 7. Resolved INSIDE the handler's own call stack, before the queue binds the command ---

    @Test
    void writeClip_completedSynchronouslyBeforeBindStillAnswersOnce() throws Exception {
        // startNextJob's catch calls failJob without leaving the handler's stack, so the
        // deferral is resolved while drainAndExecute has not yet reached bindDeferred. The
        // buffered answer must survive that ordering rather than being dropped on the floor.
        clipCreateThrows = true;

        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);

        assertTrue(future.isDone(),
            "the answer was produced before the command was bound and was then lost");
        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.NOTE_WRITE_FAILED, error.get("code").getAsInt());
        assertEquals(-32012, error.get("code").getAsInt(), "the code is pinned at both ends");
        assertEquals("slot is not writable",
            error.getAsJsonObject("data").get("reason").getAsString());

        // Idempotence: nothing parked can answer a second time and overwrite this.
        drainPending();
        assertEquals(-32012, errorOf(future).get("code").getAsInt(),
            "a second completion overwrote the real answer");
    }

    // --- 8. The partial success that must NOT be reported as a refusal (T-29-07) ---

    @Test
    void writeClip_expressionRefusalCompletesAsASuccessNotACursorMismatch() throws Exception {
        CompletableFuture<String> future = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"macro/writeClip\",\"params\":{"
                + "\"trackIndex\":0,\"sceneIndex\":7,\"lengthBeats\":8,\"stepSize\":0.25,"
                + "\"notes\":[{\"x\":0,\"y\":60,\"velocity\":100,\"duration\":1,\"chance\":0.5}]"
                + "},\"id\":1}");
        queue.drainAndExecute(dispatcher);

        // Let the notes land, then move the cursor before the expression hop runs.
        runPendingOnce();
        assertTrue(callLog.contains("clip/setNotes:1"), "the notes never landed: " + callLog);
        StateCacheTestHelper.setClipCursorPosition(stateCache, 0, 5);
        drainPending();

        assertTrue(future.isDone());
        // A SUCCESS, deliberately. The notes ARE on the slot the caller named; only the
        // expressions were refused. Answering -32011 here would make write_clip.py tell the user
        // nothing was written and to re-issue — and a re-issue of a creating write puts a second
        // copy of the notes in the owner's own music.
        JsonElement parsed = JsonParser.parseString(future.get(1, TimeUnit.SECONDS));
        assertFalse(parsed.getAsJsonObject().has("error"),
            "a partial success was reported as a refusal: "
                + future.get(1, TimeUnit.SECONDS));
        JsonObject result = parsed.getAsJsonObject().getAsJsonObject("result");
        assertEquals("refused", result.get("expressions").getAsString(),
            "the partiality must be explicit, not inferred from the landed array");
        assertEquals(1, result.get("count").getAsInt(), "the notes did land and must be counted");
        assertFalse(callLog.contains("clip/setChance:1"),
            "the expressions were applied to a clip the caller did not name: " + callLog);
    }

    // --- 9. The deadline fires on a write that never resolved (plan 29-02, T-29-03) ---

    @Test
    void writeClip_deadlineAnswersWithWriteUnresolvedWhenTheVerifyNeverRan() throws Exception {
        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);
        assertFalse(future.isDone());

        // Only the task parked at the deadline delay. The verify hop stays parked, modelling the
        // case the deadline exists for: something in the flush chain never came back. Nothing
        // sleeps — "three seconds later" is a selection over the parked list, not wall time.
        runPendingAt(DEADLINE_MS);

        assertTrue(future.isDone(),
            "the deferred response was left outstanding past its own deadline — on the WebSocket"
                + " path nothing else would ever have bounded it");
        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.WRITE_UNRESOLVED, error.get("code").getAsInt());
        assertEquals(-32013, error.get("code").getAsInt(), "the code is pinned at both ends");

        // The message is what the Python generic fall-through prints verbatim until plan 29-07's
        // branch lands, so it has to carry the one instruction a caller must not get wrong.
        String message = error.get("message").getAsString();
        assertTrue(message.contains("MAY STILL HAVE LANDED"),
            "the message must not imply nothing was written: " + message);
        assertTrue(message.contains("Read the slot back"),
            "the message must name the next step: " + message);

        JsonObject data = error.getAsJsonObject("data");
        assertEquals(DEADLINE_MS, data.get("deadlineMs").getAsLong(),
            "the answer must say which deadline it was measured against");
        assertEquals(0, data.get("requestedTrack").getAsInt());
        assertEquals(7, data.get("requestedScene").getAsInt());
        assertEquals(2, data.get("noteCount").getAsInt());
        // Present, and POPULATED since plan 29-03: phase 1 did create the clip, and this path
        // removes nothing. Plan 29-02 declared them as explicit nulls and said 29-03 would fill
        // them; leaving them null now would say "the engine did not observe this" about a fact
        // the engine observes, which is the same false claim this phase exists to remove.
        assertTrue(data.has("clipCreated") && data.get("clipCreated").getAsBoolean(),
            "the deadline answer must say the clip was created — it was: " + data);
        assertTrue(data.has("clipRemoved") && !data.get("clipRemoved").getAsBoolean(),
            "the deadline removes nothing and must not claim it did: " + data);
        assertFalse(callLog.contains("clip/delete:t0s7"),
            "an unresolved write deleted a slot it could not prove anything about: " + callLog);
    }

    // --- 10. The deadline fires BEHIND a real answer and changes nothing ---

    @Test
    void writeClip_deadlineArrivingAfterASuccessDoesNotOverwriteIt() throws Exception {
        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);

        // The verify hop runs and the write succeeds, well inside the deadline.
        runPendingOnce();
        assertTrue(future.isDone(), "the write should have completed on its first verify");
        String answered = future.get(1, TimeUnit.SECONDS);
        assertFalse(JsonParser.parseString(answered).getAsJsonObject().has("error"));

        // Now the deadline task, still parked, comes due. PendingResponse's completion is
        // one-shot AND the task re-reads isCompleted(): two independent reasons this is a no-op.
        runPendingAt(DEADLINE_MS);

        assertEquals(answered, future.get(1, TimeUnit.SECONDS),
            "the deadline overwrote an answer the caller had already been given");
        assertEquals(2, resultOf(future).get("count").getAsInt());
    }

    // --- 11. The case the deadline cannot serve declines the deferral at the front door ---

    @Test
    void writeClip_behindABusyQueueDeclinesTheDeferralAndSaysWhy() throws Exception {
        // A chain macro leaves a job in flight. macro/buildSection places no upper bound on its
        // clips array, so a write behind one cannot be promised an answer inside the deadline.
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"macro/buildSection\",\"params\":{"
            + "\"sceneName\":\"Verse\",\"sceneIndex\":3,\"clips\":["
            + "{\"trackIndex\":0,\"lengthBeats\":8,\"stepSize\":0.25,\"notes\":" + TWO_NOTES + "},"
            + "{\"trackIndex\":1,\"lengthBeats\":8,\"stepSize\":0.25,\"notes\":" + TWO_NOTES + "}"
            + "]},\"id\":10}");
        queue.drainAndExecute(dispatcher);

        CompletableFuture<String> future = enqueueWriteClip(1, 2, TWO_NOTES, 11);
        queue.drainAndExecute(dispatcher);

        assertTrue(future.isDone(),
            "a write queued behind an unbounded chain waited for a deadline it could not meet;"
                + " the caller learns the same fact 2.7 seconds sooner by being told at once");
        JsonObject result = resultOf(future);
        assertFalse(result.get("deferred").getAsBoolean());
        assertEquals("queue-busy", result.get("deferReason").getAsString(),
            "the two declining paths must be told apart by the VALUE, not by prose");
        assertEquals(2, result.get("count").getAsInt(),
            "the declining answer keeps the pre-Phase-29 accepted count");
        assertEquals(0, countParkedAt(DEADLINE_MS),
            "a declined request armed a deadline it has no response to answer");

        // And the write really was queued — declining the DEFERRAL is not declining the WRITE.
        drainPending();
        assertTrue(callLog.contains("clip/select:t1s2"), "the queued write never ran: " + callLog);
        assertTrue(callLog.contains("clip/setNotes:2"), "the queued write never ran: " + callLog);
    }

    // --- 12. A deadline is armed for a deferral, and for nothing else ---

    @Test
    void deadlineIsArmedWhenAndOnlyWhenADeferralWasTaken() throws Exception {
        CompletableFuture<String> deferred = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);

        assertFalse(deferred.isDone());
        assertEquals(List.of(FLUSH_MS, DEADLINE_MS), parkedDelays(),
            "a deferred write parks its verify hop and exactly one deadline: " + parkedDelays());

        drainPending();
        assertTrue(deferred.isDone(), "the deferred write never answered");

        // A notification has no id to answer, so it cannot defer — and must therefore arm nothing.
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"macro/writeClip\",\"params\":"
            + writeParams(1, 2, TWO_NOTES) + "}");
        queue.drainAndExecute(dispatcher);

        assertEquals(0, countParkedAt(DEADLINE_MS),
            "a request that could not defer still armed a deadline: " + parkedDelays());
        assertEquals(List.of(FLUSH_MS), parkedDelays(),
            "the write itself must still be scheduled — only the deadline is absent");
    }

    // --- 13. THE UNDO: a refusal leaves no clip behind at a slot it proved empty (23-UAT test 1) ---

    @Test
    void refusedWriteRemovesTheClipItCreatedIntoASlotProvenEmpty() throws Exception {
        // The slot is OBSERVED, and observed EMPTY, before the write — the only state in which
        // the engine is allowed to delete anything.
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 7, false);
        assertTrue(stateCache.clipHasContentObserved(0, 7),
            "the precondition is stated in the engine's own vocabulary, not assumed from the"
                + " helper: this slot IS observed");

        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);

        // The cursor is stolen and stays stolen for the whole verify window.
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"clip/select\","
            + "\"params\":{\"trackIndex\":0,\"slotIndex\":5,\"force\":true},\"id\":99}");
        queue.drainAndExecute(dispatcher);
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertTrue(data.get("clipCreated").getAsBoolean(),
            "phase 1 created a clip at the named slot and the answer must say so: " + data);
        assertTrue(data.get("clipRemoved").getAsBoolean(),
            "the refusal left the empty clip it created behind — this is 23-UAT.md test 1,"
                + " where a refused write left an empty 16-beat clip and still answered ok: "
                + data);
        assertTrue(data.get("leftoverReason").isJsonNull(),
            "nothing was left over, so there is no reason to give: " + data);

        // Removed at the CALLER's coordinate, never at the cursor's — the refusal IS that the
        // cursor is somewhere else, so deleting where the cursor points would delete t0s5.
        assertTrue(callLog.contains("clip/delete:t0s7"),
            "the clip this write created was not removed: " + callLog);
        assertFalse(callLog.contains("clip/delete:t0s5"),
            "the undo deleted the slot the CURSOR was on, not the one the caller named: "
                + callLog);
    }

    // --- 14. And it withholds the undo when the slot already held the owner's material ---

    @Test
    void refusedWriteLeavesTheClipWhenTheSlotAlreadyHeldContent() throws Exception {
        // Observed, and observed to HOLD CONTENT. Deleting here would destroy something the owner
        // made, which is the one outcome this whole mechanism exists to make impossible.
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 7, true);
        assertTrue(stateCache.clipHasContentObserved(0, 7));
        assertTrue(stateCache.clipHasContent(0, 7));

        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"clip/select\","
            + "\"params\":{\"trackIndex\":0,\"slotIndex\":5,\"force\":true},\"id\":99}");
        queue.drainAndExecute(dispatcher);
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertFalse(data.get("clipRemoved").getAsBoolean(),
            "the undo deleted a slot that already held content: " + data);
        assertTrue(data.get("leftoverReason").getAsString().contains("already held content"),
            "a clip left behind must say WHY, in a field a program can read: " + data);
        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/delete")),
            "clip/delete was dispatched against an occupied slot: " + callLog);
    }

    // --- 15. And when nothing ever observed the slot — the branch the flag made reachable ---

    @Test
    void refusedWriteLeavesTheClipWhenTheSlotWasNeverObserved() throws Exception {
        // No setClipSlotContent call: no has-content observer has ever fired for t0s7. Before the
        // observed flag existed this state was INDISTINGUISHABLE from "observed empty", because
        // the backing array is primitive and reads false — so this branch could not be reached,
        // and an unprovable slot would have been deleted on a default.
        assertFalse(stateCache.clipHasContentObserved(0, 7),
            "this test's whole premise is that nothing has observed this slot");
        assertFalse(stateCache.clipHasContent(0, 7),
            "and that the value getter cannot tell that apart from an empty slot — which is why"
                + " the flag had to exist");

        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"clip/select\","
            + "\"params\":{\"trackIndex\":0,\"slotIndex\":5,\"force\":true},\"id\":99}");
        queue.drainAndExecute(dispatcher);
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertTrue(data.get("clipCreated").getAsBoolean());
        assertFalse(data.get("clipRemoved").getAsBoolean(),
            "the undo ran on a slot whose emptiness was never observed: " + data);
        assertTrue(data.get("leftoverReason").getAsString().contains("never observed"),
            "an unprovable slot must say that it was unprovable: " + data);
        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/delete")),
            "clip/delete was dispatched on an unproven observation: " + callLog);
    }

    // --- 16. The write-failure path removes nothing, even from a slot proven empty ---

    @Test
    void writeFailureRemovesNothingEvenWhenTheSlotWasProvenEmpty() throws Exception {
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 7, false);
        setNotesThrows = true;

        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);
        drainPending();

        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.NOTE_WRITE_FAILED, error.get("code").getAsInt());

        // A write that FAILED may have written something — half the notes, the step size. The
        // slot's contents are no longer this job's to delete, and the caller reads it back.
        JsonObject data = error.getAsJsonObject("data");
        assertTrue(data.get("clipCreated").getAsBoolean());
        assertFalse(data.get("clipRemoved").getAsBoolean(),
            "a failed write deleted the slot it may have half-written: " + data);
        assertFalse(data.get("leftoverReason").isJsonNull(),
            "the clip was left and the answer must say why: " + data);
        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/delete")),
            "the write-failure path dispatched a removal: " + callLog);
    }

    // --- 17. A removal that was right to try and did not work says so, rather than claiming it ---

    @Test
    void aRemovalThatFailsIsReportedAsFailedRatherThanAssumed() throws Exception {
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 7, false);
        clipDeleteThrows = true;

        CompletableFuture<String> future = enqueueWriteClip(0, 7, TWO_NOTES, 1);
        queue.drainAndExecute(dispatcher);
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"clip/select\","
            + "\"params\":{\"trackIndex\":0,\"slotIndex\":5,\"force\":true},\"id\":99}");
        queue.drainAndExecute(dispatcher);
        drainPending();

        // An "ok" is never proof, and neither is a dispatch: clipRemoved reports the outcome of
        // the removal, not the fact that one was attempted.
        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertFalse(data.get("clipRemoved").getAsBoolean(),
            "a removal that threw was reported as a removal that happened: " + data);
        assertTrue(data.get("leftoverReason").getAsString().contains("did not succeed"),
            "the failed removal must name itself, not borrow one of the withheld reasons: "
                + data);
        assertEquals(1, errorLog.size(),
            "a failed removal must not add a third console marker — it is in the answer: "
                + errorLog);
    }

    // --- 18. The partial success carries the slot pair too, and is still not an error ---

    @Test
    void expressionRefusalCarriesTheSlotPairAndRemovesNothing() throws Exception {
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 7, false);

        CompletableFuture<String> future = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"macro/writeClip\",\"params\":{"
                + "\"trackIndex\":0,\"sceneIndex\":7,\"lengthBeats\":8,\"stepSize\":0.25,"
                + "\"notes\":[{\"x\":0,\"y\":60,\"velocity\":100,\"duration\":1,\"chance\":0.5}]"
                + "},\"id\":1}");
        queue.drainAndExecute(dispatcher);

        // Let the notes land, then move the cursor before the expression hop runs.
        runPendingOnce();
        assertTrue(callLog.contains("clip/setNotes:1"), "the notes never landed: " + callLog);
        StateCacheTestHelper.setClipCursorPosition(stateCache, 0, 5);
        drainPending();

        JsonElement parsed = JsonParser.parseString(future.get(1, TimeUnit.SECONDS));
        assertFalse(parsed.getAsJsonObject().has("error"),
            "a partial success was reported as a refusal: " + parsed);
        JsonObject result = parsed.getAsJsonObject().getAsJsonObject("result");
        assertEquals("refused", result.get("expressions").getAsString());
        assertTrue(result.get("clipCreated").getAsBoolean(),
            "a reader asks the same question of a success and must not have to ask it a"
                + " different way: " + result);
        assertFalse(result.get("clipRemoved").getAsBoolean(),
            "the notes LANDED here — removing the clip would delete them: " + result);
        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/delete")),
            "the partial-success path removed the clip its own notes are in: " + callLog);
    }

    // --- Helpers ---

    private static final String TWO_NOTES =
        "[{\"x\":0,\"y\":60,\"velocity\":100,\"duration\":1},"
            + "{\"x\":4,\"y\":64,\"velocity\":80,\"duration\":1}]";

    private static String writeParams(int trackIndex, int sceneIndex, String notes) {
        return "{\"trackIndex\":" + trackIndex + ",\"sceneIndex\":" + sceneIndex
            + ",\"lengthBeats\":8,\"stepSize\":0.25,\"notes\":" + notes + "}";
    }

    private CompletableFuture<String> enqueueWriteClip(int trackIndex, int sceneIndex,
                                                       String notes, int id) {
        return queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"macro/writeClip\",\"params\":"
            + writeParams(trackIndex, sceneIndex, notes) + ",\"id\":" + id + "}");
    }

    private JsonObject resultOf(CompletableFuture<String> future) throws Exception {
        String response = future.get(1, TimeUnit.SECONDS);
        assertNotNull(response, "a deferred response must never complete with null");
        return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result");
    }

    private JsonObject errorOf(CompletableFuture<String> future) throws Exception {
        String response = future.get(1, TimeUnit.SECONDS);
        assertNotNull(response, "a deferred response must never complete with null");
        return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("error");
    }

    /**
     * One parked task: the runnable, the delay it was scheduled with, and the virtual instant it
     * comes due.
     *
     * <p>The delay is kept beside the due instant rather than derived from it because they answer
     * different questions. "Was a deadline armed?" is a question about the DELAY and must stay
     * true no matter how much virtual time has already passed; "what runs next?" is a question
     * about the DUE INSTANT.
     */
    private static final class ParkedTask {
        final Runnable task;
        final long delayMs;
        final long dueAt;

        ParkedTask(Runnable task, long delayMs, long dueAt) {
            this.task = task;
            this.delayMs = delayMs;
            this.dueAt = dueAt;
        }
    }

    /**
     * Run the earliest-due batch of parked tasks; anything they schedule waits for a later round.
     *
     * <p>EARLIEST-DUE, not "everything parked right now", and the difference is the whole reason
     * this class can hold both the flush chain and the deadline at once. A write's verify hop is
     * due at 100 ms and its deadline at 3000 ms; running them together would let the deadline
     * answer a caller whose write was about to succeed, and every refusal test in this class
     * would start failing with -32013 for a reason that has nothing to do with what it asserts.
     */
    private void runPendingOnce() {
        if (pending.isEmpty()) {
            return;
        }
        long earliest = Long.MAX_VALUE;
        for (ParkedTask task : pending) {
            earliest = Math.min(earliest, task.dueAt);
        }
        List<ParkedTask> round = new ArrayList<>();
        for (ParkedTask task : pending) {
            if (task.dueAt == earliest) {
                round.add(task);
            }
        }
        pending.removeAll(round);
        clock = Math.max(clock, earliest);
        for (ParkedTask task : round) {
            task.task.run();
        }
    }

    /** Run parked tasks until none are left — the flush cycles ticking by with nothing else going on. */
    private void drainPending() {
        for (int round = 0; round < MAX_DRAIN_ROUNDS && !pending.isEmpty(); round++) {
            runPendingOnce();
        }
        assertTrue(pending.isEmpty(),
            "tasks were still being scheduled after " + MAX_DRAIN_ROUNDS + " rounds");
    }

    /**
     * Run ONLY the tasks scheduled with the given delay, leaving everything else parked.
     *
     * <p>This is what makes "the deadline fires and the verify never ran" expressible. Virtual
     * time is deliberately NOT advanced: this is a selection over the parked list, not a claim
     * that three seconds passed.
     */
    private void runPendingAt(long delayMs) {
        List<ParkedTask> round = new ArrayList<>();
        for (ParkedTask task : pending) {
            if (task.delayMs == delayMs) {
                round.add(task);
            }
        }
        assertFalse(round.isEmpty(),
            "no task was parked at " + delayMs + " ms; parked delays were " + parkedDelays());
        pending.removeAll(round);
        for (ParkedTask task : round) {
            task.task.run();
        }
    }

    /** How many parked tasks were scheduled with the given delay. */
    private long countParkedAt(long delayMs) {
        return pending.stream().filter(task -> task.delayMs == delayMs).count();
    }

    /** The delay of every parked task, in the order they were scheduled. */
    private List<Long> parkedDelays() {
        List<Long> delays = new ArrayList<>();
        for (ParkedTask task : pending) {
            delays.add(task.delayMs);
        }
        return delays;
    }
}
