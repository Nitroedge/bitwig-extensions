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
 * The wrong-slot launcher write (TODO-WRONG-SLOT / P-C-05), and the refusal that stops it.
 *
 * <h2>Why this class exists beside {@link MacroHandlerTest}</h2>
 *
 * <p>That class drives an {@code IMMEDIATE_SCHEDULER} — {@code (task, delayMs) -> task.run()} —
 * which collapses the deferral window to zero. Nothing can happen inside a window of zero width,
 * so the bug is not expressible there, and a test written there would have passed all the way
 * through the two live rounds this defect survived. This class gives the window a width and
 * stands inside it.
 *
 * <h2>The threading model this models, and the one it deliberately does not</h2>
 *
 * <p>Everything here runs on ONE thread, because that is what production does. An RPC handler is
 * executed by {@code CommandQueue.drainAndExecute} from inside Bitwig's {@code flush()} on the
 * Control Surface Session thread ({@code CommandQueue.java:20-22}), and {@code host.scheduleTask}
 * schedules onto that same thread. The four HTTP threads never run a handler; they block on a
 * future the session thread completes.
 *
 * <p>So: the test thread calls the handler, the handler returns, and only THEN does the test
 * drain the scheduler — exactly the order the single session thread imposes. A test that put the
 * handler on one thread and the scheduler on another would be modelling an arrangement production
 * does not have, and would stay green against a handler that deadlocks in Bitwig.
 *
 * <h2>The cursor is stateful here</h2>
 *
 * <p>{@code clip/select} moves the modelled cursor, so a competing select between phase 1 and
 * phase 2 is expressible — reproduction C of P-C-05 in miniature. Without that, a test can only
 * ever exercise the case where the cursor and the request already agree, which is the case that
 * never broke.
 *
 * <h2>Where a refusal is READ, since Phase 29 (plan 29-03)</h2>
 *
 * <p>Every assertion here that used to read the snapshot's per-refusal detail now reads the
 * deferred RESPONSE instead, and that is a strictly better test rather than a lateral move: the
 * response is the fact the caller actually receives, and the snapshot detail was a second copy of
 * it that could disagree. The detail is retired (D-29-13); the COUNTER is kept, because the chain
 * macros do not defer and it is their only machine-readable refusal signal — so both the
 * deferring method ({@code macro/writeClip}) and a non-deferring one
 * ({@code macro/buildSection}) still assert it here.
 *
 * <p>That is also why {@code macro/writeClip} is driven through {@code queue.enqueue} /
 * {@code queue.drainAndExecute} below while {@code macro/buildSection} keeps the direct
 * {@code dispatcher.handle} call: a deferred request HAS no returned response string, it has an
 * outstanding future, and a chain macro has no deferral to wait for. The harness follows what the
 * method does, and neither assertion was relaxed to make the re-point easier.
 */
class MacroHandlerWrongSlotTest {

    /** Bounds {@link #drainPending()} so a scheduling bug fails loudly instead of hanging the suite. */
    private static final int MAX_DRAIN_ROUNDS = 50;

    private CommandQueue queue;
    private JsonRpcDispatcher dispatcher;
    private StateCache stateCache;
    private List<String> callLog;
    private List<String> errorLog;
    private List<ParkedTask> pending;

    /** Virtual time, in milliseconds. Advanced by running a parked task, never by the wall clock. */
    private long clock;

    /** Set by a test to make {@code clip/setNotes} fail, modelling a write that breaks on its own. */
    private boolean setNotesThrows;

    @BeforeEach
    void setUp() {
        queue = new CommandQueue();
        dispatcher = new JsonRpcDispatcher();
        stateCache = new StateCache();
        callLog = new ArrayList<>();
        errorLog = new ArrayList<>();
        pending = new ArrayList<>();
        clock = 0;
        setNotesThrows = false;

        // A DEFERRING scheduler. The task is parked, not run: the 100 ms window becomes a place
        // the test can stand, which is the whole point of this class.
        //
        // Each task carries the virtual instant it comes due, so tasks run in DUE order. Without
        // that, the 3000 ms deferral deadline plan 29-02 arms would run in the same round as a
        // 100 ms verify hop and answer -32013 to a write that was about to be refused 2.8 seconds
        // earlier — every refusal assertion below would fail for a reason having nothing to do
        // with what it asserts.
        TaskScheduler deferring =
            (task, delayMs) -> pending.add(new ParkedTask(task, delayMs, clock + delayMs));

        dispatcher.register("clip/create", params -> {
            callLog.add("clip/create:t" + params.get("trackIndex").getAsInt()
                + "s" + params.get("slotIndex").getAsInt()
                + "l" + params.get("lengthInBeats").getAsInt());
            return new JsonPrimitive("ok");
        });
        // STATEFUL: selecting a clip moves the cursor, and the fix reads where the cursor is.
        dispatcher.register("clip/select", params -> {
            int trackIndex = params.get("trackIndex").getAsInt();
            int slotIndex = params.get("slotIndex").getAsInt();
            callLog.add("clip/select:t" + trackIndex + "s" + slotIndex);
            StateCacheTestHelper.setClipCursorPosition(stateCache, trackIndex, slotIndex);
            return new JsonPrimitive("ok");
        });
        // The undo-on-refusal route (plan 29-03). Registered so a removal that SHOULD happen can,
        // and so a removal that should NOT happen is visible by its absence from the call log.
        dispatcher.register("clip/delete", params -> {
            callLog.add("clip/delete:t" + params.get("trackIndex").getAsInt()
                + "s" + params.get("slotIndex").getAsInt());
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
        dispatcher.register("clip/rename", params -> {
            callLog.add("clip/rename:" + params.get("name").getAsString());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setChance", params -> {
            callLog.add("clip/setChance:" + params.getAsJsonArray("notes").size());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("scene/create", params -> {
            callLog.add("scene/create");
            return new JsonPrimitive("ok");
        });
        dispatcher.register("scene/rename", params -> {
            callLog.add("scene/rename:" + params.get("name").getAsString());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("sceneBank/scrollBy", params -> {
            callLog.add("sceneBank/scrollBy:" + params.get("amount").getAsInt());
            return new JsonPrimitive("ok");
        });

        new MacroHandler(dispatcher, stateCache, deferring, errorLog::add).register(dispatcher);
    }

    // --- 1. The refusal itself: the case that was the data-loss bug ---

    @Test
    void writeClip_refusesWhenTheCursorMovedBeforePhaseTwo() throws Exception {
        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":7,"lengthBeats":8,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1}]}""");

        // Phase 1 ran and nothing else: the write is parked in the scheduler.
        assertEquals(List.of("clip/create:t0s7l8", "clip/select:t0s7"), callLog);

        // Somebody else moves the cursor inside the window — a user click in Bitwig, or another
        // surface. P-C-05 reproduction C.
        handle("clip/select", "{\"trackIndex\":0,\"slotIndex\":5,\"force\":true}");

        drainPending();

        // THE assertion this class exists for. Against the pre-fix handler the log ends
        // "clip/setNotes:2" — two notes written to slot 5, which nobody asked for, with an `ok`
        // and a count of 2 returned to the caller.
        assertFalse(callLog.contains("clip/setNotes:2"),
            "notes were written while the cursor was on a slot the caller did not name: " + callLog);
        assertFalse(callLog.contains("clip/setStepSize:0.25"),
            "the step size was written to a slot the caller did not name: " + callLog);

        // And the refusal is announced three ways: the response the caller receives, the session
        // counter, and the marked console line. The RESPONSE is read first, because it is the one
        // the caller actually gets.
        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.CURSOR_MISMATCH, error.get("code").getAsInt());
        JsonObject data = error.getAsJsonObject("data");
        assertEquals(7, data.get("requestedScene").getAsInt());
        assertEquals(5, data.get("cursorScene").getAsInt());
        assertEquals(0, data.get("requestedTrack").getAsInt());
        assertEquals(0, data.get("cursorTrack").getAsInt());

        assertEquals(1, stateCache.getWriteClipRefusals());

        assertEquals(1, errorLog.size(), "expected exactly one console line: " + errorLog);
        String line = errorLog.get(0);
        assertTrue(line.startsWith("SECONDO-CURSOR-MISMATCH"),
            "the console line must lead with the greppable marker: " + line);
        assertTrue(line.contains("requested=t0s7") && line.contains("observed=t0s5"),
            "the console line must carry both positions: " + line);
        assertTrue(line.contains("notes=2"),
            "the console line must say how many notes were at stake: " + line);
        assertTrue(line.contains("T") && line.contains("Z"),
            "the console line must carry its ISO-8601 timestamp: " + line);
    }

    // --- 2. The control: it still writes when nothing moved ---

    @Test
    void writeClip_writesWhenTheCursorAgrees() throws Exception {
        CompletableFuture<String> future = enqueue("""
            {"trackIndex":2,"sceneIndex":3,"lengthBeats":8,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1}],
             "name":"Lead"}""");

        drainPending();

        assertEquals(List.of(
            "clip/create:t2s3l8",
            "clip/select:t2s3",
            "clip/setStepSize:0.25",
            "clip/setNotes:2",
            "clip/rename:Lead"
        ), callLog);
        assertEquals(2, resultOf(future).get("count").getAsInt());
        assertEquals(0, stateCache.getWriteClipRefusals());
        assertTrue(errorLog.isEmpty(), "a clean write should say nothing: " + errorLog);
    }

    // --- 3. A write that was correctly targeted and still failed reads differently ---

    @Test
    void writeClip_reportsAWriteFailureDistinctlyFromAMismatch() throws Exception {
        setNotesThrows = true;

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":1,"sceneIndex":1,"lengthBeats":4,"stepSize":0.5,
             "notes":[{"x":0,"y":48,"velocity":100,"duration":1}]}""");
        drainPending();

        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.NOTE_WRITE_FAILED, error.get("code").getAsInt(),
            "a broken write must not be reported as a cursor mismatch");
        assertEquals("clip is not writable",
            error.getAsJsonObject("data").get("reason").getAsString(),
            "the cause belongs in the answer — 'it failed' is not a diagnosis");
        assertEquals(1, stateCache.getWriteClipRefusals());

        assertEquals(1, errorLog.size());
        assertTrue(errorLog.get(0).startsWith("SECONDO-NOTE-WRITE-FAILED"), errorLog.get(0));
        assertTrue(errorLog.get(0).contains("clip is not writable"),
            "the cause belongs in the line — 'it failed' is not a diagnosis: " + errorLog.get(0));
    }

    // --- 4. A chain stops rather than scattering its remaining clips ---

    @Test
    void buildSection_abortsTheChainOnTheFirstMismatch() {
        // Direct, not through the queue: a chain macro does not defer (D-29-05), so its refusal
        // reaches no response and the captured console log IS where it is readable.
        handle("macro/buildSection", """
            {"sceneName":"Verse","sceneIndex":0,"clips":[
                {"trackIndex":0,"lengthBeats":8,"stepSize":0.25,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]},
                {"trackIndex":1,"lengthBeats":8,"stepSize":0.25,
                 "notes":[{"x":0,"y":48,"velocity":100,"duration":1}]},
                {"trackIndex":2,"lengthBeats":8,"stepSize":0.25,
                 "notes":[{"x":0,"y":36,"velocity":100,"duration":1}]}
            ]}""");

        // One round: clip 0 verifies, writes, and selects clip 1.
        runPendingOnce();
        assertTrue(callLog.contains("clip/setNotes:1"), callLog.toString());
        assertTrue(callLog.contains("clip/select:t1s0"), callLog.toString());

        // The cursor is stolen before clip 1's hop.
        handle("clip/select", "{\"trackIndex\":5,\"slotIndex\":0,\"force\":true}");
        drainPending();

        assertEquals(1, callLog.stream().filter(c -> c.startsWith("clip/setNotes")).count(),
            "the chain wrote past its first mismatch: " + callLog);
        // The KEPT counter, on the method that has nothing else: this is why D-29-13 retired the
        // detail and not the tally.
        assertEquals(1, stateCache.getWriteClipRefusals());

        assertEquals(1, errorLog.size(), errorLog.toString());
        String line = errorLog.get(0);
        assertTrue(line.contains("landed=[t0s0]"), "the line must name what DID land: " + line);
        assertTrue(line.contains("notWritten=[t1s0,t2s0]"),
            "the line must name every clip that was not attempted: " + line);
    }

    // --- 5. Serialisation, in the shape one thread actually produces ---

    @Test
    void writeClip_secondWriteDoesNotTouchTheCursorUntilTheFirstHasFinished() {
        // Two commands drained from the queue in the same flush — the ordinary case, not a
        // contrived one: CommandQueue.drainAndExecute runs every queued request back to back.
        enqueue("""
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":8,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        enqueue("""
            {"trackIndex":3,"sceneIndex":4,"lengthBeats":8,"stepSize":0.25,
             "notes":[{"x":0,"y":72,"velocity":100,"duration":1}]}""");

        // The second write has not created its clip or moved the cursor. Against the pre-fix
        // handler both phase 1s have already run here, and the second select is what the first
        // write's phase 2 would then have written into.
        assertEquals(List.of("clip/create:t0s0l8", "clip/select:t0s0"), callLog,
            "the second write started before the first had finished: " + callLog);

        drainPending();

        assertEquals(List.of(
            "clip/create:t0s0l8",
            "clip/select:t0s0",
            "clip/setStepSize:0.25",
            "clip/setNotes:1",
            "clip/create:t3s4l8",
            "clip/select:t3s4",
            "clip/setStepSize:0.25",
            "clip/setNotes:1"
        ), callLog);
        assertEquals(0, stateCache.getWriteClipRefusals(),
            "serialised writes should not have to refuse each other");
    }

    // --- 6. The -1 sentinel: a cursor nothing has ever observed is not track 0 ---

    @Test
    void writeClip_refusesWhenTheCursorPositionWasNeverObserved() throws Exception {
        // The state a fresh extension is in before any observer has fired. The request names
        // track 0 scene 0, which is what an unguarded `-1` would read as if the sentinel were 0.
        dispatcher.register("clip/selectSilently", params -> new JsonPrimitive("ok"));
        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":8,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");

        // Undo what the select stub modelled: this is the case where the cursor genuinely has not
        // been observed yet, so the fix must not read -1 as "track 0, scene 0".
        StateCacheTestHelper.setClipCursorPosition(stateCache, -1, -1);
        drainPending();

        assertFalse(callLog.contains("clip/setNotes:1"),
            "wrote against a cursor position that had never been observed: " + callLog);
        assertEquals(1, stateCache.getWriteClipRefusals());
        assertTrue(errorLog.get(0).contains("observed=unobserved(t-1s-1)"),
            "an unobserved cursor must not be reported as a real slot: " + errorLog.get(0));

        // And the sentinel does not leave the engine: the caller is told JSON null, not -1.
        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertTrue(data.get("cursorTrack").isJsonNull(),
            "the -1 sentinel reached the caller as a track number: " + data);
        assertTrue(data.get("cursorScene").isJsonNull(),
            "the -1 sentinel reached the caller as a scene number: " + data);
    }

    // --- 7. Expressions are a third hop, and land on the clip their notes did ---

    @Test
    void buildSection_expressionsNeverLandOnTheNextClip() {
        // Before this fix, applyNoteExpressions was scheduled from inside writeNotesToCursor and
        // fired one flush LATER — by which time the chain had already selected the next clip. So
        // clip 0's chance values were written to clip 1. Same bug, one layer down.
        handle("macro/buildSection", """
            {"sceneName":"Verse","sceneIndex":0,"clips":[
                {"trackIndex":0,"lengthBeats":8,"stepSize":0.25,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1,"chance":0.5}]},
                {"trackIndex":1,"lengthBeats":8,"stepSize":0.25,
                 "notes":[{"x":0,"y":48,"velocity":100,"duration":1}]}
            ]}""");
        drainPending();

        int chanceAt = callLog.indexOf("clip/setChance:1");
        int nextSelectAt = callLog.indexOf("clip/select:t1s0");
        assertTrue(chanceAt >= 0, "the chance values were never applied: " + callLog);
        assertTrue(nextSelectAt >= 0, "the chain never reached the second clip: " + callLog);
        assertTrue(chanceAt < nextSelectAt,
            "clip 0's expressions were applied after the cursor had moved to clip 1, so they"
                + " landed on the wrong clip: " + callLog);
        assertEquals(0, stateCache.getWriteClipRefusals());
    }

    // --- Helpers ---

    /** Drive a NON-deferring method and read the response string it returns. */
    private String handle(String method, String params) {
        return dispatcher.handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}");
    }

    /**
     * Drive {@code macro/writeClip} the way production does, and hand back the future the caller
     * is waiting on. A deferred request has no returned response string to read.
     */
    private CompletableFuture<String> enqueue(String params) {
        CompletableFuture<String> future = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"macro/writeClip\",\"params\":" + params
                + ",\"id\":1}");
        queue.drainAndExecute(dispatcher);
        return future;
    }

    private JsonObject resultOf(CompletableFuture<String> future) throws Exception {
        String response = future.get(1, TimeUnit.SECONDS);
        assertNotNull(response, "a deferred response must never complete with null");
        return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result");
    }

    private JsonObject errorOf(CompletableFuture<String> future) throws Exception {
        String response = future.get(1, TimeUnit.SECONDS);
        assertNotNull(response, "a deferred response must never complete with null");
        JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(parsed.has("error"), "expected a refusal, got: " + response);
        return parsed.getAsJsonObject("error");
    }

    /** One parked task: the runnable, the delay it was scheduled with, and when it comes due. */
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

    /** Run the earliest-due batch of parked tasks; anything they schedule waits for a later round. */
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
}
