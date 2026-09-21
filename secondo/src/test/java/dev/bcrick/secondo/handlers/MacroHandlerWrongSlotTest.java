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
 * <h2>The cursor has TWO values, and the gap between them is the defect (plan 31-03)</h2>
 *
 * <p>Until this section was written the modelled cursor moved <em>synchronously</em>: the
 * {@code clip/select} stub called {@link StateCacheTestHelper#setClipCursorPosition} inline, so
 * the harness's OBSERVED position and its TRUE position were the same two fields. The defect this
 * class is named for is precisely that those two diverge for one flush — so the harness modelled a
 * world in which the bug cannot happen, and every assertion in the class passed against a broken
 * handler for exactly that reason.
 *
 * <p>So there are now two values, and the vocabulary is borrowed from the Python harness's track
 * axis ({@code mock/state.py:2559-2577}) so that the two harnesses read alike:
 *
 * <ul>
 *   <li><b>INTENT</b> — {@link #trueCursorTrack} / {@link #trueCursorSlot}: where the cursor
 *       ACTUALLY is. {@code clip/select} moves these immediately, the way a real selection moves
 *       the real cursor the moment Bitwig processes it.</li>
 *   <li><b>OBSERVATION</b> — the {@code clipCursorTrackPosition} / {@code clipCursorSceneIndex}
 *       pair inside {@link StateCache}, which is the only thing {@code MacroHandler}'s verify can
 *       read. It catches up {@link #observerLagRounds} rounds later, through
 *       {@link #scheduleRounds(int, Runnable)}.</li>
 * </ul>
 *
 * <p><b>What this does not model: milliseconds.</b> A round here is an ORDERING, not a duration.
 * The harness advances time by running parked tasks, never by the wall clock, so "a lag of one
 * round" means "the observation lands one flush-hop later", not "100 ms later". The live
 * 0/30/60/100/150 ms map is measured against a running Bitwig and recorded in
 * {@code 31-ECHO-MEASUREMENT.md}; it is not reproduced here and a green case here does not predict
 * it. What transfers between the two is the ordering, and nothing else.
 *
 * <p><b>Why the intra-round ordering is load-bearing and must not be "tidied".</b>
 * {@link #runPendingOnce()} runs the earliest-due batch in the order the tasks were parked. With a
 * lag of one round, phase 1's observer catch-up and phase 2's verify come due in the SAME batch,
 * and the catch-up is parked first because {@code startNextJob} selects the clip before it
 * schedules the verify. A competing select issued after both is parked last. That is what lets a
 * verify read an observation which is true of where the cursor WAS and false of where it now is —
 * the 100 ms row of the live map, expressed as an ordering. Sorting or reordering a batch would
 * make the defect inexpressible again.
 *
 * <p><b>Still one thread.</b> {@code scheduleRounds} parks a {@link ParkedTask} on the same virtual
 * clock as everything else. There is no sleep call, no real timer and no second thread
 * here, for the reason given two sections above: a two-threaded test would stay green against a
 * handler that deadlocks inside Bitwig.
 *
 * <p><b>The default is today's behaviour.</b> {@link #observerLagRounds} is {@code 0} unless a test
 * sets it, and at {@code 0} the observation is written inline exactly as it used to be. Every test
 * written before this section keeps its meaning.
 *
 * <h2>Stubs log their DESTINATION, not just their payload (plan 31-03)</h2>
 *
 * <p>{@code clip/setNotes}, {@code clip/setStepSize} and {@code clip/rename} all act on whatever
 * clip the cursor is on, so their entries in {@link #callLog} carry the true cursor position:
 * {@code "clip/setNotes:t0s5:2"}, not {@code "clip/setNotes:2"}. Without that a test cannot tell
 * "wrote two notes" from "wrote two notes to the wrong slot", and that is the only distinction this
 * class is about.
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

    /**
     * INTENT — where the cursor ACTUALLY is, moved by {@code clip/select} the moment it is
     * dispatched. {@code -1} is "never selected", matching the sentinel {@link StateCache} uses for
     * a position no observer has reported.
     */
    private int trueCursorTrack;

    /** INTENT — the slot half of {@link #trueCursorTrack}. See that field. */
    private int trueCursorSlot;

    /**
     * How many rounds the OBSERVATION lags the INTENT by.
     *
     * <p>{@code 0} — the default, and every test written before plan 31-03 — writes the observed
     * position inline inside the select stub, which is what this class did from the start. {@code 1}
     * is the real engine: the observer fires one flush after the selection, and in that gap the
     * state cache reports a position which is true of where the cursor was and false of where it is.
     */
    private int observerLagRounds;

    /**
     * One round, in virtual milliseconds — the flush hop {@code MacroHandler} schedules against.
     *
     * <p>Read through {@link StateCacheTestHelper#flushDelayOf(Class)} rather than written here as
     * {@code 100}, for the reason that helper exists: a test that restates the constant is a test
     * that can be left behind asserting the old number. It is read once per test, in {@code setUp}.
     */
    private long roundMs;

    @BeforeEach
    void setUp() {
        queue = new CommandQueue();
        dispatcher = new JsonRpcDispatcher();
        stateCache = new StateCache();
        // Every public track index the engine reads a cached clip fact with is resolved through
        // one TrackBankManager (D-31-06), and a cache without one answers -1 -- unproven -- to all
        // of them. Production wires one in during initialization, so a test that left it absent
        // would be asserting against a coordinate space the engine is never in. A null bank
        // resolves every in-range index to itself, which is what these unscrolled cases want.
        StateCacheTestHelper.installTrackBankManager(stateCache,
            new TrackBankManager(null, StateCacheTestHelper.trackCountOf(StateCache.class)));
        callLog = new ArrayList<>();
        errorLog = new ArrayList<>();
        pending = new ArrayList<>();
        clock = 0;
        setNotesThrows = false;
        trueCursorTrack = -1;
        trueCursorSlot = -1;
        observerLagRounds = 0;
        roundMs = StateCacheTestHelper.flushDelayOf(MacroHandler.class);

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

        // STATEFUL since plan 31-05, in the same way the rename stub below has been since 31-04
        // and for the same reason: a create that LANDS is reported by the slot's OWN has-content
        // observer, and the undo now requires that report to be newer than the instant the write
        // began (29-REVIEW.md, WR-01). Without this half every refusal here would leave its clip
        // behind for a reason none of these tests is about. Both modelled facts are measured: a
        // create over an OCCUPIED slot changes nothing and publishes nothing at API v25
        // (29-LIVE-ACCEPTANCE.md section 3), and one over an empty slot flips its has-content.
        dispatcher.register("clip/create", params -> {
            int createTrack = params.get("trackIndex").getAsInt();
            int createSlot = params.get("slotIndex").getAsInt();
            callLog.add("clip/create:t" + createTrack + "s" + createSlot
                + "l" + params.get("lengthInBeats").getAsInt());
            if (!stateCache.clipHasContent(createTrack, createSlot)) {
                StateCacheTestHelper.setClipSlotContent(stateCache, createTrack, createSlot, true);
                StateCacheTestHelper.bumpClipObservationSeq(stateCache, createTrack, createSlot);
            }
            return new JsonPrimitive("ok");
        });
        // STATEFUL, and TWO-VALUED: selecting a clip moves the cursor NOW, and the observation the
        // fix reads catches up observerLagRounds rounds later. At a lag of 0 the two happen in the
        // same statement, which is what this stub did before plan 31-03.
        dispatcher.register("clip/select", params -> {
            int trackIndex = params.get("trackIndex").getAsInt();
            int slotIndex = params.get("slotIndex").getAsInt();
            callLog.add("clip/select:t" + trackIndex + "s" + slotIndex);
            trueCursorTrack = trackIndex;              // INTENT: the cursor is there already
            trueCursorSlot = slotIndex;
            scheduleRounds(observerLagRounds, () ->    // OBSERVATION: the state cache catches up
                StateCacheTestHelper.setClipCursorPosition(stateCache, trackIndex, slotIndex));
            return new JsonPrimitive("ok");
        });
        // The undo-on-refusal route (plan 29-03). Registered so a removal that SHOULD happen can,
        // and so a removal that should NOT happen is visible by its absence from the call log.
        dispatcher.register("clip/delete", params -> {
            callLog.add("clip/delete:t" + params.get("trackIndex").getAsInt()
                + "s" + params.get("slotIndex").getAsInt());
            return new JsonPrimitive("ok");
        });
        // The three cursor-scoped stubs. Each logs WHERE IT WROTE — the true cursor position — and
        // not merely what it was handed, because "wrote two notes" and "wrote two notes to the
        // wrong slot" are the two outcomes this class exists to tell apart.
        dispatcher.register("clip/setStepSize", params -> {
            callLog.add("clip/setStepSize:t" + trueCursorTrack + "s" + trueCursorSlot
                + ":" + params.get("size").getAsDouble());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setNotes", params -> {
            int count = params.getAsJsonArray("notes").size();
            if (setNotesThrows) {
                throw new IllegalStateException("clip is not writable");
            }
            callLog.add("clip/setNotes:t" + trueCursorTrack + "s" + trueCursorSlot + ":" + count);
            return new JsonPrimitive(count);
        });
        // A rename is cursor-scoped AND it is what the identity proof of plan 31-04 is taken
        // through, so this stub does both halves of what production does: it renames whatever clip
        // the cursor is on, and the named slot's OWN name observer publishes that name into the
        // state cache observerLagRounds rounds later. Without the second half no stamp could ever
        // echo and every write in this class would refuse -- and with it, a stamp dispatched onto
        // a cursor that has wandered publishes at the slot it actually landed on, which is exactly
        // the fact the proof exists to notice.
        dispatcher.register("clip/rename", params -> {
            String name = params.get("name").getAsString();
            callLog.add("clip/rename:t" + trueCursorTrack + "s" + trueCursorSlot + ":" + name);
            final int bankSlot = trueCursorTrack;
            final int sceneIndex = trueCursorSlot;
            if (bankSlot >= 0 && sceneIndex >= 0) {
                scheduleRounds(observerLagRounds, () -> {
                    StateCacheTestHelper.setClipSlotName(stateCache, bankSlot, sceneIndex, name);
                    StateCacheTestHelper.bumpClipObservationSeq(stateCache, bankSlot, sceneIndex);
                });
            }
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
        // "clip/setNotes:t0s5:2" — two notes written to slot 5, which nobody asked for, with an
        // `ok` and a count of 2 returned to the caller.
        //
        // Matched on the method PREFIX rather than on a whole entry: the entries now carry their
        // destination, and an equality check against one exact spelling would pass vacuously the
        // next time the format gains a field. Absence of the write is the claim, so absence is what
        // is asserted.
        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/setNotes")),
            "notes were written while the cursor was on a slot the caller did not name: " + callLog);
        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/setStepSize")),
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

        // The stamp is pinned HERE, between the select and the step size, because that ordering is
        // the design: the identity proof is taken before any note is dispatched, so a failed proof
        // has written nothing anywhere (D-31-02). An expected list that omitted it would be a list
        // that could not tell a proven write from an unproven one.
        assertEquals(List.of(
            "clip/create:t2s3l8",
            "clip/select:t2s3",
            "clip/rename:t2s3:SECONDO-STAMP-1-c0",
            "clip/setStepSize:t2s3:0.25",
            "clip/setNotes:t2s3:2",
            "clip/rename:t2s3:Lead"
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

        // Two rounds: clip 0 verifies and STAMPS, then its echo is proved and it writes and selects
        // clip 1. The write costs one hop more than it did before plan 31-04, because the identity
        // proof is a flush of its own -- the same shape the expression hop already had.
        runPendingOnce();
        runPendingOnce();
        assertTrue(callLog.contains("clip/setNotes:t0s0:1"), callLog.toString());
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

        // Each write now carries its own stamp and its own restore: the proof is paid PER CLIP and
        // never amortised (D-31-11), and neither of these two writes names a clip, so the name the
        // slot carried before the stamp is put back rather than leaving an engine token visible as
        // the owner's clip name.
        assertEquals(List.of(
            "clip/create:t0s0l8",
            "clip/select:t0s0",
            "clip/rename:t0s0:SECONDO-STAMP-1-c0",
            "clip/setStepSize:t0s0:0.25",
            "clip/setNotes:t0s0:1",
            "clip/rename:t0s0:",
            "clip/create:t3s4l8",
            "clip/select:t3s4",
            "clip/rename:t3s4:SECONDO-STAMP-2-c0",
            "clip/setStepSize:t3s4:0.25",
            "clip/setNotes:t3s4:1",
            "clip/rename:t3s4:"
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

        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/setNotes")),
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

    // --- 8. The observer AGREES and is WRONG: the case the harness could not express until 31-03 ---

    /**
     * The 100 ms row of {@code 31-ECHO-MEASUREMENT.md}, expressed as an ordering rather than as a
     * duration.
     *
     * <p>Every other refusal case in this class has the observation DISAGREEING with the request,
     * which the verify catches. This one has it AGREEING — truthfully about where the cursor was
     * when phase 1 put it there, and falsely about where it is now. The verify reads the agreement,
     * concludes the cursor is on the named slot, and writes to a cursor that has already moved. The
     * live session measured exactly that: the engine answered success and positively claimed
     * {@code landed: t7s4} while the three notes were in the decoy at {@code t7s5}.
     *
     * <p>What makes it expressible here is {@link #observerLagRounds} at {@code 1}: phase 1's
     * observer catch-up and phase 2's verify come due in the same batch, catch-up first, and the
     * competing select's own catch-up lands after. At a lag of {@code 0} — every other test in this
     * class — the competing select updates the observation immediately and the verify refuses, which
     * is {@link #writeClip_refusesWhenTheCursorMovedBeforePhaseTwo()}. The lag is the whole
     * difference between the case that was always caught and the case that shipped.
     */
    // WATCHED FAILING BEFORE IT WAS TRUSTED. Run enabled once at engine build pin 9f3a62f — the
    // fourteenth pin, the handler this phase exists to fix — where the suite reported
    // "1102 tests completed, 1 failed" and this was the one. It failed on the first assertion
    // below:
    //
    //   org.opentest4j.AssertionFailedError: notes were written to a slot the caller did not
    //   name: [clip/setNotes:t0s5:3] | full call log: [clip/create:t0s4l4, clip/select:t0s4,
    //   clip/select:t0s5, clip/setStepSize:t0s5:0.25, clip/setNotes:t0s5:3] ==> expected: <true>
    //   but was: <false>
    //
    // Three notes and a step size into slot 5 against a write that named slot 4, and the caller
    // told it succeeded. That output is quoted verbatim, with the whole run's figures, in
    // .planning/phases/31-engine-cursor-identity-verify/31-03-SUMMARY.md.
    //
    // It was disabled rather than left red so that every plan boundary between there and the fix
    // had a green suite — four other findings ride the same build gate, and a permanently red
    // gate tells them nothing.
    //
    // ENABLED by plan 31-04, which turned it GREEN against the stamp-and-prove handler with
    // every assertion below exactly as plan 31-03 wrote it. Not one was weakened. What changed
    // is the HARNESS, and only by modelling two facts about the engine it did not model before:
    // the canonical resolver the extension wires into the state cache at initialization, and
    // the named slot's own name observer publishing what a cursor-scoped rename wrote.
    @Test
    void refusesWhenTheObserverAgreesAndTheCursorHasAlreadyMoved() throws Exception {
        // The observation lags the cursor by one flush. This is the engine's real behaviour; the
        // default of 0 is the simplification every older test in this class was written against.
        observerLagRounds = 1;

        // The named slot is PROVEN empty before the write. Without an observed emptiness the
        // handler must leave the clip it created rather than risk deleting material it did not
        // make (D-29-10 / D-29-11), and the third assertion below would be asserting the wrong
        // thing for the wrong reason.
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);

        // The live map's payload: lengthBeats 4, stepSize 0.25, three notes.
        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1},
                      {"x":8,"y":67,"velocity":90,"duration":1}]}""");

        // Phase 1 ran and nothing else, pinned exactly, in the style this class uses throughout:
        // the ordering is asserted, not merely the outcome.
        assertEquals(List.of("clip/create:t0s4l4", "clip/select:t0s4"), callLog);

        // The competing select: a different slot on the SAME track, issued while the write is
        // parked. Its own observation will not land until after the verify has run, which is the
        // arrangement this test exists for.
        handle("clip/select", "{\"trackIndex\":0,\"slotIndex\":5,\"force\":true}");

        drainPending();

        // ONE. No note write reached a slot other than the one the caller named. Read off the
        // destination in the call log, not off a payload size: "wrote three notes" and "wrote
        // three notes to the wrong slot" are the two outcomes, and only the destination tells
        // them apart.
        List<String> strayWrites = new ArrayList<>();
        for (String entry : callLog) {
            if (entry.startsWith("clip/setNotes") && !entry.startsWith("clip/setNotes:t0s4:")) {
                strayWrites.add(entry);
            }
        }
        // ASCII only in this message, deliberately. The engine's Gradle build sets no source
        // encoding, so javac reads these UTF-8 files with the platform default and any non-ASCII
        // character in a string literal reaches the test report as U+FFFD. This message IS the
        // evidence plan 31-03 exists to capture, so it is kept readable.
        assertTrue(strayWrites.isEmpty(),
            "notes were written to a slot the caller did not name: " + strayWrites
                + " | full call log: " + callLog);

        // TWO. What the caller receives is the refusal, not a landed payload. The live 100 ms row
        // got `result {"count":3,"landed":[{"slot":"t7s4",...}]}` — a positive claim about the slot
        // the notes were NOT in, which is worse than an error because it reads as proof.
        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.CURSOR_MISMATCH, error.get("code").getAsInt(),
            "the write was reported as landed while the cursor was elsewhere");
        JsonObject data = error.getAsJsonObject("data");
        assertEquals(0, data.get("requestedTrack").getAsInt());
        assertEquals(4, data.get("requestedScene").getAsInt());
        assertEquals(1, stateCache.getWriteClipRefusals(),
            "a refusal that is not counted is a refusal the next session cannot see");

        // THREE. The named slot was left clean. Phase 1 created a clip there to make it
        // addressable; a refusal that leaves it behind is the 23-UAT.md test 1 defect, and the
        // removal is addressed by the caller's own coordinate rather than by the cursor — which is
        // by definition somewhere else on this path.
        assertTrue(callLog.contains("clip/delete:t0s4"),
            "the clip phase 1 created at the named slot was left behind: " + callLog);
        assertTrue(data.get("clipRemoved").getAsBoolean(),
            "the answer must say the slot was cleaned, not only clean it: " + data);
        assertTrue(data.get("leftoverReason").isJsonNull(),
            "nothing was left, so there is no reason to give: " + data);
    }

    // --- 9. The proof must not cost the owner a clip name (plan 31-04, Pitfall 1) ---

    /**
     * The hazard inside the design, closed rather than accepted.
     *
     * <p>The stamp the proof is taken with is a cursor-scoped rename, and at the timing this class
     * is about, the cursor is on a clip that is not the caller's. So the act that STOPS the
     * caller's notes landing on the owner's clip would, unhandled, rename that clip instead. Test
     * 8 above would pass either way -- it asserts that no notes were written, and no notes are
     * written on this path -- which is exactly why that assertion alone is the signature of this
     * hazard being unhandled rather than evidence against it.
     *
     * <p>So this case gives the decoy a name of its own and asserts it is still there afterwards,
     * that the engine says so in the answer, and that the refusal came from the IDENTITY PROOF
     * rather than from the position compare -- which is what makes the whole scenario the one
     * plan 31-04 built for and not an ordinary mismatch caught a flush earlier.
     */
    @Test
    void restoresThePriorNameWhenTheStampLandedElsewhere() throws Exception {
        observerLagRounds = 1;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);

        // The decoy is the OWNER's clip, and its name is the thing at stake. Addressed by physical
        // bank slot, which the installed resolver maps one-to-one from track 0 here.
        StateCacheTestHelper.setClipSlotName(stateCache, 0, 5, "Owner Riff");
        StateCacheTestHelper.bumpClipObservationSeq(stateCache, 0, 5);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1},
                      {"x":8,"y":67,"velocity":90,"duration":1}]}""");
        assertEquals(List.of("clip/create:t0s4l4", "clip/select:t0s4"), callLog);

        handle("clip/select", "{\"trackIndex\":0,\"slotIndex\":5,\"force\":true}");
        drainPending();

        // ONE. Still no note write anywhere but the slot the caller named -- the guarantee test 8
        // makes, restated here because this case must not be allowed to buy its restore by
        // weakening it.
        List<String> strayWrites = new ArrayList<>();
        for (String entry : callLog) {
            if (entry.startsWith("clip/setNotes") && !entry.startsWith("clip/setNotes:t0s4:")) {
                strayWrites.add(entry);
            }
        }
        assertTrue(strayWrites.isEmpty(),
            "notes were written to a slot the caller did not name: " + strayWrites
                + " | full call log: " + callLog);

        // TWO. The decoy is called what it was called. The stamp DID land on it -- that is the
        // whole scenario -- and it was put back through the same cursor-scoped rename route, with
        // no new coordinate and no new RPC.
        assertEquals("Owner Riff", stateCache.getClipNameAtBankSlot(0, 5),
            "the proof renamed the owner's clip and left it renamed: " + callLog);
        assertTrue(callLog.contains("clip/rename:t0s5:Owner Riff"),
            "the prior name was not put back through the cursor: " + callLog);

        // THREE. And the answer SAYS both facts, rather than quietly doing one of them.
        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertTrue(data.get("stampRestored").getAsBoolean(),
            "a restore that was proven must say so: " + data);
        assertEquals("t0s5", data.get("stampLeftAt").getAsString(),
            "the answer must name where the stamp was left: " + data);

        // And it was the identity proof that refused, not the position compare. Without this the
        // case could pass for the reason test 1 passes, which is a different bug being caught.
        assertEquals("stamp-echo", data.get("refusalReason").getAsString(),
            "this scenario must be refused by the identity proof: " + data);
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

    /**
     * Run {@code body} {@code rounds} drain rounds from now, on this class's own virtual clock.
     *
     * <p>This is how the OBSERVATION half of the two-valued cursor catches up with the INTENT half.
     * A round is one flush hop ({@link #roundMs}), which is the interval {@code MacroHandler}
     * schedules its own verify against — so a lag of one round parks the observation so that it
     * comes due in the same batch as the verify that follows the selection, and, being parked
     * first, runs just before it. That ordering IS the defect: the verify then reads a position the
     * cursor has already left.
     *
     * <p>{@code rounds <= 0} runs {@code body} inline, which is byte-for-byte what the select stub
     * did before plan 31-03 and is why the default lag of {@code 0} leaves every older test's
     * meaning intact.
     *
     * <p>No sleep call, no real timer, no second thread — the same single-threaded rule
     * the class Javadoc gives, for the same reason.
     */
    private void scheduleRounds(int rounds, Runnable body) {
        if (rounds <= 0) {
            body.run();
            return;
        }
        long delay = roundMs * rounds;
        pending.add(new ParkedTask(body, delay, clock + delay));
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
