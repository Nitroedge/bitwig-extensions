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

    /**
     * The scene bank's SCROLL POSITION — the offset between the caller's coordinate and the
     * cursor's (plan 31-15).
     *
     * <p>Three coordinate spaces meet on this path and until this field existed the harness had
     * only one, so CR-01 could not be expressed here at all. A caller's {@code sceneIndex} is a
     * BANK-WINDOW subscript; {@code clipCursorSceneIndex} is a PROJECT-ABSOLUTE scene, because it
     * is bound to {@code cursorClip.clipLauncherSlot().sceneIndex()}. This field is what turns one
     * into the other, and the select stub applies it when it writes the OBSERVATION half of the
     * two-valued cursor.
     *
     * <p><b>The default is zero, which is today's behaviour exactly.</b> At a scroll of zero
     * {@link #absoluteSceneOf(int)} is the identity, so the observation written is the same number
     * the stub wrote before this field existed and every test in this class keeps its meaning.
     * Zero is also every live run this project has ever made, which is why the defect has never
     * been seen: {@code macro/buildSection} scrolls the bank itself
     * ({@code MacroHandler.java:637-639}), so it is reachable in ordinary use.
     */
    private int sceneBankScrollPosition;

    /**
     * The PROJECT-ABSOLUTE track position modelled for each PHYSICAL BANK SLOT — the track axis's
     * half of the same coordinate problem (plan 31-15).
     *
     * <p>{@code clipCursorTrackPosition} is bound to {@code cursorClip.getTrack().position()},
     * which is absolute; a caller's {@code trackIndex} is a PUBLIC INDEX. They are different
     * numbers the moment a group track is collapsed or the track bank is scrolled.
     *
     * <p><b>The default is the identity</b> — slot {@code i} has position {@code i} — so the two
     * spaces coincide unless a test deliberately separates them, and every existing case reads as
     * it did. The same values are seeded into the state cache through
     * {@link StateCacheTestHelper#setTrackPositionAtBankSlot}, so a resolver that converts between
     * the two has something real to read rather than an array of nulls.
     */
    private int[] modelledTrackPositions;

    /**
     * Where the TRUE cursor is moved once the named slot has published the stamp token, or
     * {@code -1} for OFF, which is the default (plan 31-15).
     *
     * <p>This is the ORDERING RESIDUAL, made expressible. The identity proof sits in FRONT of the
     * note dispatch rather than being fused to it: {@code writeProvenClip:1942-1950} calls
     * {@code writeNotesToCursor} immediately after {@code proveStampEcho} returns, and the echo is
     * itself an observer reading on the flush cycle. So the proof establishes where the cursor
     * WAS at echo time, not where it is at write time — and nothing in this harness could put a
     * cursor move into that gap until this hook did.
     *
     * <p>It fires from inside the rename stub's own scheduled name publication, at the instant the
     * named slot publishes the token — which is the last moment before {@code proveStampEcho} reads
     * it and writes. The move itself goes through the ordinary {@code clip/select} route, so it
     * moves the INTENT now and lags the OBSERVATION exactly like any other competing selection.
     * Nothing sleeps, nothing threads: it rides the same parked-task clock as everything else here,
     * for the reason the class Javadoc gives.
     */
    private int postStampEchoCursorTrack;

    /** The slot half of {@link #postStampEchoCursorTrack}. See that field. */
    private int postStampEchoCursorSlot;

    /**
     * How many stamp publications the armed post-proof move lets pass before it fires (plan
     * 31-17).
     *
     * <p>Zero — the default and every case written before this plan — fires on the FIRST stamp,
     * which is the only stamp a lone {@code macro/writeClip} takes. A chain takes one per clip
     * (D-31-11: the proof is paid per clip and never amortised), so a case about the SECOND clip
     * of a chain has to say which stamp it means. Counting publications rather than clips is
     * deliberate: the stamp publication is the instant the hook fires at, so the thing being
     * counted and the thing being skipped are the same event.
     */
    private int postStampEchoStampsToSkip;

    /**
     * The slot that must be given the closing rename's OWN name, with NO observation bump, at the
     * instant that rename is dispatched — or {@code -1} for OFF, which is the default (plan
     * 31-17).
     *
     * <p>THE SEQUENCE CONJUNCT, MADE TESTABLE ON ITS OWN. The landing proof conjoins a name fact
     * and a freshness fact, and in ordinary play the stamp keeps them from ever failing
     * independently: whenever the name matches, the observation that carried it is newer than the
     * rename. That invariant belongs to {@code proveStampEcho} rather than to the landing proof,
     * so the freshness fact has to be exercised where the name fact PASSES — otherwise a handler
     * that dropped it would still be green, and the conjunct would be an assumption rather than a
     * tested one.
     *
     * <p>It writes the name directly into the cache rather than routing it through the rename
     * stub's own publication, because routing it there would bump the observation sequence, which
     * is the one thing this state must not have.
     */
    private int staleFinalNameBankSlot;

    /** The scene half of {@link #staleFinalNameBankSlot}. See that field. */
    private int staleFinalNameScene;

    /**
     * The slot whose observation sequence must be bumped — WITHOUT changing its name — at the
     * instant the closing rename is dispatched, or {@code -1} for OFF (plan 31-17).
     *
     * <p>THE NAME CONJUNCT, MADE TESTABLE ON ITS OWN, and the exact mirror of
     * {@link #staleFinalNameBankSlot}. On the wrong-slot path the named slot's last observation
     * is always the stamp's own publication, which happens strictly BEFORE the landing proof
     * records its rename tick — so the freshness fact refuses by itself and the name fact could
     * be deleted from the handler without a single test noticing. This switch gives the named
     * slot a fresh observation while it is still carrying the token, so the freshness fact PASSES
     * and only the name fact can produce the refusal.
     *
     * <p>Production reaches the same state whenever the launcher republishes a slot for any
     * reason after a write has left it: the observation is genuinely fresh and the name is
     * genuinely not the one that was written.
     */
    private int freshObservationBankSlot;

    /** The scene half of {@link #freshObservationBankSlot}. See that field. */
    private int freshObservationScene;

    /**
     * Withhold the name publication of the NEXT non-stamp rename, once (plan 31-17).
     *
     * <p>A closing rename whose publication has not landed within the landing ceiling is the
     * OTHER way that proof can fail — not a cursor that moved, but a flush that was slow, which
     * `31-REVIEW.md` CR-03 names as the likeliest reason the first echo misses too. It is the
     * state in which the cursor is still on the named slot, so it is the state the stamp put-back
     * can actually succeed from.
     *
     * <p>One-shot: the put-back's own rename is a non-stamp rename too, and suppressing that one
     * as well would model a cache that never hears anything again.
     */
    private boolean suppressTheClosingRenamePublication;

    /**
     * Extra rounds of lag on the STAMP's own name publication, beyond {@link #observerLagRounds}
     * (plan 31-17).
     *
     * <p>It separates two latencies the harness had conflated: how fast the CURSOR's position
     * observer catches up, and how fast a SLOT's name observer does. A test that needed the token
     * to be published after the echo ceiling could previously only raise
     * {@link #observerLagRounds}, which also slows the cursor observation and refuses the write at
     * the position compare long before the stamp is taken — so the state CR-03 is about was
     * unreachable. Default zero, which is every case written before this plan.
     */
    private int stampPublicationExtraLagRounds;

    /**
     * The prefix every identity-proof token carries, read from {@code MacroHandler} rather than
     * restated, the same way {@link #roundMs} is. It is how the rename stub tells a STAMP rename
     * from the caller's own closing rename, which is what makes the post-proof hook fire at the
     * one point it is supposed to.
     */
    private String stampPrefix;

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
        stampPrefix = StateCacheTestHelper.stampPrefixOf(MacroHandler.class);

        // Both coordinate axes start COINCIDENT, which is the state every test written before
        // plan 31-15 assumed without being able to say so: the scene bank at its origin, and each
        // bank slot's absolute track position equal to its own subscript. A test that wants the
        // two spaces to differ says so itself, through scrollSceneBank or
        // setTrackPositionAtBankSlot. The positions are seeded into the cache as well as modelled
        // here because `trackPositions` is an Integer[] whose unset entries read null -- "no
        // observer has fired" -- and production has fired one for every slot in the bank.
        sceneBankScrollPosition = 0;
        StateCacheTestHelper.setSceneBankOffset(stateCache, 0);
        int trackCount = StateCacheTestHelper.trackCountOf(StateCache.class);
        modelledTrackPositions = new int[trackCount];
        for (int slot = 0; slot < trackCount; slot++) {
            modelledTrackPositions[slot] = slot;
            StateCacheTestHelper.setTrackPositionAtBankSlot(stateCache, slot, slot);
        }

        // The post-proof cursor move is OFF. -1 is the same "never" sentinel the cursor fields use.
        postStampEchoCursorTrack = -1;
        postStampEchoCursorSlot = -1;
        postStampEchoStampsToSkip = 0;

        // Plan 31-17's three harness switches, all OFF, so every case written before it reads as
        // it did: no slot is given a stale copy of the closing name, no publication is withheld,
        // and the stamp's own publication is on the ordinary observer lag.
        staleFinalNameBankSlot = -1;
        staleFinalNameScene = -1;
        freshObservationBankSlot = -1;
        freshObservationScene = -1;
        suppressTheClosingRenamePublication = false;
        stampPublicationExtraLagRounds = 0;

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
            // The OBSERVATION is written in the cursor's OWN coordinate space, not the caller's
            // (plan 31-15). The arguments this stub received are a public track index and a
            // BANK-WINDOW slot subscript; what `registerClipCursorObservers` writes is
            // `getTrack().position()` and `clipLauncherSlot().sceneIndex()`, both PROJECT-ABSOLUTE.
            // Until this conversion existed the harness wrote the caller's numbers into the
            // cursor's fields, which modelled a world where the two spaces are the same one -- so
            // CR-01 could not fail a test here, and did not, through two live rounds.
            //
            // At the defaults the two conversions are the identity, so this is byte-for-byte what
            // the stub did before.
            final int absoluteTrack = absoluteTrackPositionOf(trackIndex);
            final int absoluteScene = absoluteSceneOf(slotIndex);
            scheduleRounds(observerLagRounds, () ->    // OBSERVATION: the state cache catches up
                StateCacheTestHelper.setClipCursorPosition(stateCache, absoluteTrack, absoluteScene));
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
            final boolean isStamp = name.startsWith(stampPrefix);

            // Plan 31-17's two non-stamp switches, applied where the rename is DISPATCHED rather
            // than where it is published, because that is the instant the landing proof records
            // its own tick at.
            if (!isStamp) {
                fireStaleFinalNameAtNamedSlot(name);
                fireFreshObservationAtSlot();
                if (suppressTheClosingRenamePublication) {
                    suppressTheClosingRenamePublication = false;
                    return new JsonPrimitive("ok");
                }
            }

            final int publicationLag =
                observerLagRounds + (isStamp ? stampPublicationExtraLagRounds : 0);
            if (bankSlot >= 0 && sceneIndex >= 0) {
                scheduleRounds(publicationLag, () -> {
                    StateCacheTestHelper.setClipSlotName(stateCache, bankSlot, sceneIndex, name);
                    StateCacheTestHelper.bumpClipObservationSeq(stateCache, bankSlot, sceneIndex);
                    // THE GAP, and the only place it can be stood in (plan 31-15). The token has
                    // just been published by the named slot's own observer; `proveStampEcho` reads
                    // it in the NEXT round and, satisfied, dispatches the notes synchronously in
                    // that same task. So this instant is the last one before the proof is taken and
                    // the write goes out, and a cursor that moves here is a cursor the proof has
                    // already stopped being able to see. Default is off.
                    if (name.startsWith(stampPrefix)) {
                        firePostStampEchoCursorMove();
                    }
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

        // Three rounds: clip 0 verifies and STAMPS; its echo is proved and it writes; its LANDING
        // is proved and it selects clip 1. The write costs one hop more than it did before plan
        // 31-04 and one more again since plan 31-17, because each proof is a flush of its own --
        // the same shape the expression hop already had. This count is re-timed, not relaxed:
        // every assertion below is the one plan 29-03 wrote.
        runPendingOnce();
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

    // --- 10. The scene bank scrolled off its origin (plan 31-15, 31-REVIEW.md CR-01) ---

    /**
     * A write that names a slot, with the cursor genuinely on it and nothing competing for it, and
     * the scene bank scrolled. It should be the dullest case in this class. It is not.
     *
     * <p>{@code verifyThenWrite:1744-1747} compares {@code getClipCursorSceneIndex()} — bound to
     * {@code cursorClip.clipLauncherSlot().sceneIndex()}, a PROJECT-ABSOLUTE scene — against
     * {@code clip.sceneIndex}, which is the caller's BANK-WINDOW subscript. At a scroll of zero
     * those are the same number and the compare is right by coincidence. At any other scroll they
     * cannot agree, so the re-poll exhausts at {@code CURSOR_VERIFY_CEILING_MS}, the write is
     * refused on {@code cursor-position}, and — because phase 1 proved the slot empty and created a
     * clip there — the undo DELETES the clip it just made. Every launcher write, refused, on a
     * project whose scene bank has been scrolled once.
     *
     * <p>It is reachable in ordinary use rather than contrived: {@code macro/buildSection} scrolls
     * the bank itself ({@code MacroHandler.java:637-639}) and, as {@code write_clip.py:631-633}
     * notes, once scrolled it stays scrolled for the rest of the session. Every live run this
     * project has made has been at scroll zero, which is the only reason this has never been seen.
     */
    // WATCHED FAILING BEFORE IT WAS TRUSTED. Run enabled at engine build pin b640c6c -- the
    // fifteenth pin, the handler this gap round exists to fix. Its assertion message is quoted
    // verbatim, with the whole run's figures, in
    // .planning/phases/31-engine-cursor-identity-verify/31-15-SUMMARY.md.
    //
    // Disabled rather than left red so that every plan boundary between here and the fix has a
    // green suite: several other findings ride the same build gate, and a permanently red gate
    // tells them nothing.
    //
    // ENABLED by plan 31-16, which converts the caller's pair into the cursor's own PROJECT-
    // ABSOLUTE space once, at the top of verifyThenWrite, and compares there. Not one assertion
    // below was changed, deleted or relaxed to make it green: the only edit to this case is the
    // removal of its disable annotation, which is what keeps the fix and its witness independent.
    @Test
    void refusesNothingAndWritesCorrectlyWhenTheSceneBankIsScrolled() throws Exception {
        // The bank is scrolled. Eight is an arbitrary non-zero -- what matters is that it is not
        // the origin, which is the only position the compare is correct at.
        scrollSceneBank(8);

        // Proven empty before the write, so a refusal WOULD delete the clip phase 1 creates. That
        // is half the consequence being asserted against, and without the observed emptiness the
        // handler would withhold the delete for an unrelated reason (D-29-10 / D-29-11).
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 2, false);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":2,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1},
                      {"x":8,"y":67,"velocity":90,"duration":1}]}""");

        drainPending();

        // ONE. The notes reached the slot the caller named. Nothing competed for the cursor in
        // this test -- it is on t0s2 and it stays there -- so anything but a clean write here is
        // the compare refusing a write it had no business refusing.
        assertTrue(callLog.contains("clip/setNotes:t0s2:3"),
            "a write to a slot the cursor was genuinely on was not performed, with the scene bank"
                + " scrolled to " + sceneBankScrollPosition + ": " + callLog);

        // TWO. And nothing was refused. Read three ways, because a refusal announces itself three
        // ways and a test that checked only one could pass against a handler that still refused.
        assertEquals(0, stateCache.getWriteClipRefusals(),
            "a correctly targeted write was refused because the bank was scrolled: " + callLog);
        assertTrue(errorLog.isEmpty(), "a clean write should say nothing: " + errorLog);
        assertEquals(3, resultOf(future).get("count").getAsInt(),
            "the caller was not told the three notes landed: " + callLog);

        // THREE. The clip phase 1 created is still there. This is the half that loses the user
        // something: the undo fires on the refusal and deletes a clip the caller asked for.
        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/delete")),
            "the undo deleted the clip the caller asked for, on a write that should have"
                + " succeeded: " + callLog);
    }

    // --- 10b. A coordinate that cannot be placed is refused, not compared (plan 31-16) ---

    /**
     * The other half of the conversion: what happens when it cannot be made.
     *
     * <p>The conversion the case above proves is {@code caller -> project-absolute}, and its track
     * leg can genuinely fail. {@code StateCache#absoluteTrackPositionForPublicIndex} names three
     * ways: the canonical resolver refuses the public index, the resolved bank slot is outside the
     * array, or the resolved slot's {@code position()} observer has never fired. This case is the
     * THIRD, deliberately, because it is the only one of the three in which the emptiness read in
     * front of the write is itself proven -- the public index resolves, so phase 1 could read the
     * slot -- which is what lets this assert the undo as well as the refusal.
     *
     * <p>What must NOT happen is the failure this whole plan exists to remove: comparing an
     * observed absolute position against a fabricated zero, agreeing by luck, and writing. The
     * write refuses instead, immediately -- there is nothing to re-poll for -- and says WHICH kind
     * of failure it was, so a caller is told the engine could not place their track rather than
     * told the cursor was somewhere else.
     */
    @Test
    void refusesWithAnUnresolvedCoordinateWhenTheTrackPositionWasNeverObserved() throws Exception {
        // No position observer has ever fired for bank slot 0. setUp seeds all sixteen, because
        // production does; this takes one back out, which is the state a slot is in before its
        // first flush.
        StateCacheTestHelper.clearTrackPositionAtBankSlot(stateCache, 0);

        // Proven empty before the write, so the undo is authorised and the third assertion below
        // is asserting the thing it names (D-29-10 / D-29-11).
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 2, false);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":2,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1},
                      {"x":8,"y":67,"velocity":90,"duration":1}]}""");

        drainPending();

        // ONE. Nothing was written anywhere. An unplaceable coordinate must not become a write
        // somewhere plausible.
        assertFalse(callLog.stream().anyMatch(c -> c.startsWith("clip/setNotes")),
            "notes were dispatched for a track whose position could not be resolved: " + callLog);

        // TWO. The answer names the coordinate rather than the cursor. The reason string is
        // asserted as a literal on purpose: it is a WIRE value a caller reads, so its spelling is
        // part of the contract and a test that derived it could not catch a rename.
        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.CURSOR_MISMATCH, error.get("code").getAsInt(),
            "an unplaceable coordinate must still refuse through the one refusal path");
        JsonObject data = error.getAsJsonObject("data");
        assertEquals("unresolved-coordinate", data.get("refusalReason").getAsString(),
            "the refusal blamed the cursor for a coordinate the engine could not place: " + data);
        assertEquals(1, stateCache.getWriteClipRefusals(),
            "a refusal that is not counted is a refusal the next session cannot see");

        // THREE. And no clip is left at the named slot: phase 1 created one to make the slot
        // addressable, and this path is a clean refusal like any other.
        assertTrue(callLog.contains("clip/delete:t0s2"),
            "the clip phase 1 created at the named slot was left behind: " + callLog);
        assertTrue(data.get("clipRemoved").getAsBoolean(),
            "the answer must say the slot was cleaned, not only clean it: " + data);
    }

    // --- 10c. A refusal a reader can reconcile (plan 31-16) ---

    /**
     * The payload half of CR-01 consequence 3: two coordinate spaces on the wire with nothing
     * saying so.
     *
     * <p>Before this, a refusal published {@code requestedScene} -- a BANK-WINDOW subscript -- beside
     * {@code cursorScene} -- a PROJECT-ABSOLUTE index -- and {@code write_clip.py} rendered both into
     * one sentence: "the cursor was on track X scene Y, you asked for track A scene B". On a
     * scrolled session those two scene numbers are not comparable and nothing in the answer said
     * so, so the sentence could not be acted on.
     *
     * <p>The caller's own two keys are UNCHANGED -- the tool layer reads them and its tests pin
     * them -- and three more travel beside them: the pair the engine actually compared, and the
     * offset that reconciles the two scene keys. The assertion is the ARITHMETIC RELATION between
     * the three scene numbers rather than three independent literals, because three literals would
     * still pass if the engine published an offset that reconciled nothing.
     */
    @Test
    void theRefusalPayloadCarriesBothSpacesAndTheOffsetThatReconcilesThem() throws Exception {
        scrollSceneBank(8);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":7,"lengthBeats":8,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1}]}""");

        assertEquals(List.of("clip/create:t0s7l8", "clip/select:t0s7"), callLog);

        // Somebody else moves the cursor inside the window, so the compare genuinely never agrees
        // and this is a real refusal rather than a payload built by hand.
        handle("clip/select", "{\"trackIndex\":0,\"slotIndex\":5,\"force\":true}");

        drainPending();

        JsonObject error = errorOf(future);
        assertEquals(JsonRpcError.CURSOR_MISMATCH, error.get("code").getAsInt());
        JsonObject data = error.getAsJsonObject("data");

        // ONE. The caller's own coordinates are exactly what the caller sent. This is the half the
        // Python tool layer reads and pins, and it must not move.
        assertEquals(0, data.get("requestedTrack").getAsInt(),
            "the caller's own track index was rewritten into another space: " + data);
        assertEquals(7, data.get("requestedScene").getAsInt(),
            "the caller's own scene subscript was rewritten into another space: " + data);

        // TWO. THE relation. Read all three off the payload and check they reconcile, rather than
        // asserting three numbers that could each be right while the set of them is nonsense.
        int callerScene = data.get("requestedScene").getAsInt();
        int offset = data.get("sceneBankOffset").getAsInt();
        int absoluteScene = data.get("requestedSceneAbsolute").getAsInt();
        assertEquals(sceneBankScrollPosition, offset,
            "the offset published is not the scroll position the compare ran at: " + data);
        assertEquals(callerScene + offset, absoluteScene,
            "requestedScene + sceneBankOffset must equal requestedSceneAbsolute, or the offset"
                + " reconciles nothing: " + data);

        // THREE. And the two numbers a reader is meant to compare ARE in one space: the engine's
        // absolute scene for the slot the cursor was on is the same conversion applied to 5.
        assertEquals(absoluteSceneOf(5), data.get("cursorScene").getAsInt(),
            "the observed scene is not in the space the requested scene was converted into: "
                + data);
        assertEquals(data.get("requestedTrackPosition").getAsInt(),
            absoluteTrackPositionOf(0),
            "the requested track position is not the absolute position of the named track: "
                + data);
    }

    // --- 11. The cursor moves AFTER the proof and BEFORE the notes (plan 31-15) ---

    /**
     * The 150 ms row of the live five-point map, expressed as an ordering: the residual
     * {@code 31-VERIFICATION.md} failed the phase goal on.
     *
     * <p>Test 8 above is the case the stamp-and-prove design CLOSES — the cursor moves before the
     * proof, the named slot never echoes the token, and the write refuses cleanly. This is the case
     * it does not: the named slot DOES echo, truthfully, and the cursor moves immediately
     * afterwards. {@code proveStampEcho} reads an echo that was true when it was published and is
     * false by the time it is read, hands off to {@code writeProvenClip}, and the notes go to
     * wherever the cursor now is — while the response positively claims the slot the caller named.
     *
     * <p>The live record, verbatim from {@code evidence/31-live-raw.json} {@code rows[4]}: the
     * response was {@code result {"count":3,"landed":[{"slot":"t4s4","notes":3}],"deferred":true}}
     * while {@code after.decoySlot} carried the caller's own name with three notes in it at
     * {@code t4s5}, and {@code writeClipRefusals} did not move. The named slot was left holding the
     * engine's internal token, {@code SECONDO-STAMP-2-c0}.
     *
     * <p><b>What a future design change would do to this test.</b> It asserts on what the engine
     * SAYS, because that is what the failed criterion is about, and the chosen {@code second-echo}
     * design makes the report true without preventing the move. The {@code pin-cursor} option the
     * owner declined (filed as a follow-up) would instead make the move impossible — under which
     * this test's scenario becomes unreachable and the case needs REVISITING, not weakening.
     */
    // WATCHED FAILING BEFORE IT WAS TRUSTED. Run enabled at engine build pin b640c6c, the handler
    // whose live run produced the WRONG-SLOT-SUCCESS this models. Its assertion message is quoted
    // verbatim in .planning/phases/31-engine-cursor-identity-verify/31-15-SUMMARY.md, beside the
    // scrolled-bank case above.
    //
    // ENABLED by plan 31-17, which takes a SECOND echo after the write -- the named slot's own
    // name observer must publish the name the closing rename actually dispatched, on an
    // observation newer than that rename -- and downgrades the answer from a success to a refusal
    // when it does not. Not one assertion below was changed, deleted or relaxed to make it green:
    // the only edit to this case is the removal of its disable annotation, which is what keeps
    // the fix and its witness independent.
    @Test
    void refusesWhenTheCursorMovesAfterTheStampEchoAndBeforeTheNotes() throws Exception {
        // The observation lags the cursor by one flush -- the engine's real behaviour, and the
        // reason the echo the proof reads can already be out of date when it is read.
        observerLagRounds = 1;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);

        // The move, armed to fire the instant the NAMED slot has published the token. Nothing
        // before this hook could put a cursor move into that gap, which is why the residual
        // survived a green suite.
        armPostStampEchoCursorMove(0, 5);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1},
                      {"x":8,"y":67,"velocity":90,"duration":1}]}""");

        // Phase 1 ran and nothing else, pinned exactly, in the style this class uses throughout.
        assertEquals(List.of("clip/create:t0s4l4", "clip/select:t0s4"), callLog);

        drainPending();

        // ONE. The scenario actually happened: the proof was satisfied at the named slot and the
        // notes then went somewhere else. This is a PRECONDITION of the case rather than a claim
        // about the fix -- a design that prevented the move would make it false, and would need
        // this case rewritten rather than relaxed. Asserted so the case cannot pass vacuously
        // against a handler that refused for some entirely different reason.
        assertTrue(callLog.contains("clip/rename:t0s4:" + stampPrefix + "1-c0"),
            "the stamp was never taken at the named slot, so this is not the case under test: "
                + callLog);
        assertTrue(callLog.contains("clip/setNotes:t0s5:3"),
            "the notes did not follow the moved cursor, so this is not the case under test: "
                + callLog);

        // TWO. THE assertion. Whatever the engine answers, it must not claim the named slot
        // received notes that are demonstrably in another clip. A success that positively names
        // the wrong slot is worse than an error, because it reads as proof.
        String response = future.get(1, TimeUnit.SECONDS);
        assertNotNull(response, "a deferred response must never complete with null");
        JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
        List<String> claimedSlots = new ArrayList<>();
        if (parsed.has("result") && parsed.get("result").isJsonObject()) {
            JsonElement landed = parsed.getAsJsonObject("result").get("landed");
            if (landed != null && landed.isJsonArray()) {
                for (JsonElement row : landed.getAsJsonArray()) {
                    if (row.isJsonObject() && row.getAsJsonObject().has("slot")) {
                        claimedSlots.add(row.getAsJsonObject().get("slot").getAsString());
                    }
                }
            }
        }
        // ASCII only in this message, for the reason test 8's message gives: the engine's Gradle
        // build sets no source encoding, and this message IS the evidence.
        assertFalse(claimedSlots.contains("t0s4"),
            "the answer claimed the notes landed at t0s4 while they were written to t0s5:"
                + " claimed=" + claimedSlots + " | response=" + response
                + " | full call log: " + callLog);
    }

    // --- 12. The landing proof: the named slot must say it got the name (plan 31-17) ---

    /**
     * The dull case, and it must stay dull: a write nobody competes for still lands, still
     * reports the slot it named, and pays exactly one more flush than it did before the landing
     * proof existed.
     *
     * <p>The cost is asserted as an ORDERING rather than as a round count, because a round count
     * is a number this class would have to restate every time a hop moves. The claim is that the
     * answer is not yet out when the notes have gone out -- which is precisely what "the proof is
     * a flush of its own" means, and is false of every build before this one.
     */
    @Test
    void landsAndReportsTheNamedSlotWhenTheCursorStaysPutForTheWholeWrite() throws Exception {
        observerLagRounds = 1;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,"name":"Verse Riff",
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1},
                      {"x":8,"y":67,"velocity":90,"duration":1}]}""");

        // Drive round by round until the notes have gone out, then assert the answer has NOT.
        for (int round = 0; round < MAX_DRAIN_ROUNDS
            && !callLog.contains("clip/setNotes:t0s4:3"); round++) {
            runPendingOnce();
        }
        assertTrue(callLog.contains("clip/setNotes:t0s4:3"),
            "the write never dispatched its notes: " + callLog);
        assertFalse(future.isDone(),
            "the job answered in the same round the notes went out, so nothing stood between the"
                + " dispatch and the claim: " + callLog);

        drainPending();

        JsonObject result = resultOf(future);
        assertEquals(3, result.get("count").getAsInt());
        assertEquals("t0s4",
            result.getAsJsonArray("landed").get(0).getAsJsonObject().get("slot").getAsString(),
            "a write that genuinely landed must still be reported as landing: " + result);
        assertEquals(0, stateCache.getWriteClipRefusals(),
            "an uncontested write was refused: " + callLog);
    }

    /**
     * The refusal names where the notes appear to be -- for a SINGLE fresh match, and for nothing
     * else.
     */
    @Test
    void theRefusalNamesTheOneSlotThatEndedUpCarryingTheNameTheRenameWrote() throws Exception {
        observerLagRounds = 1;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);
        armPostStampEchoCursorMove(0, 5);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,"name":"Verse Riff",
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("landing-echo", data.get("refusalReason").getAsString(),
            "this scenario must be refused by the landing proof: " + data);
        assertEquals("t0s5", data.get("notesLandedAt").getAsString(),
            "the answer must name the one slot that ended up with the caller's name: " + data);
    }

    /**
     * Two slots carrying the caller's name is not an identity: a caller is free to write one name
     * to two clips, and a first match dressed as a coordinate is the class of claim this phase
     * exists to stop.
     */
    @Test
    void theRefusalSaysNothingAboutWhereTheNotesAreWhenTwoSlotsCarryTheName() throws Exception {
        observerLagRounds = 1;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);
        // A clip of the owner's, elsewhere entirely, that happens to be called the same thing.
        StateCacheTestHelper.setClipSlotName(stateCache, 3, 2, "Verse Riff");
        StateCacheTestHelper.bumpClipObservationSeq(stateCache, 3, 2);
        armPostStampEchoCursorMove(0, 5);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,"name":"Verse Riff",
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("landing-echo", data.get("refusalReason").getAsString(), data.toString());
        assertTrue(data.get("notesLandedAt").isJsonNull(),
            "a name at two slots was published as a coordinate: " + data);
    }

    /**
     * No slot carrying the name is the other absence, and it has a realistic cause: the closing
     * rename's publication simply has not landed within the ceiling. The engine says it cannot
     * say, rather than guessing.
     */
    @Test
    void theRefusalSaysNothingAboutWhereTheNotesAreWhenNoSlotCarriesTheName() throws Exception {
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);
        suppressTheClosingRenamePublication = true;

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,"name":"Verse Riff",
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("landing-echo", data.get("refusalReason").getAsString(), data.toString());
        assertTrue(data.get("notesLandedAt").isJsonNull(),
            "the engine named a slot for notes it could not locate: " + data);
    }

    /**
     * A refusal that follows a dispatched write SAYS the write went out, and one that does not
     * says it did not.
     *
     * <p>Both halves in one case, because the fact is only useful as a discriminator: the
     * standing refusal sentence -- that nothing was written -- is true of the three triggers that
     * dispatch nothing and false of the one that does, and nothing downstream may render it over
     * a refusal carrying this true (T-31G-15).
     */
    @Test
    void aRefusalThatFollowedADispatchedWriteSaysSoAndOneThatDidNotSaysTheOpposite()
            throws Exception {
        observerLagRounds = 1;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);
        armPostStampEchoCursorMove(0, 5);

        CompletableFuture<String> landing = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        JsonObject landingData = errorOf(landing).getAsJsonObject("data");
        assertEquals("landing-echo", landingData.get("refusalReason").getAsString(),
            landingData.toString());
        assertTrue(landingData.get("notesDispatched").getAsBoolean(),
            "the notes went out and the refusal denied it: " + landingData);
        assertFalse(errorOf(landing).get("message").getAsString().contains("nothing was written"),
            "a refusal that followed a dispatched write still said nothing was written: "
                + errorOf(landing).get("message").getAsString());
        assertTrue(errorLog.get(0).contains("notes WERE dispatched"),
            "the console line a Bitwig owner reads still says no notes were written: "
                + errorLog.get(0));

        // And the contrast, on the trigger that genuinely writes nothing. The lag goes back to
        // zero so the competing selection is OBSERVED before the verify reads it -- which is
        // what makes this second write refuse at the position compare rather than at the
        // identity proof, and the position compare is the trigger the contrast is about.
        callLog.clear();
        errorLog.clear();
        observerLagRounds = 0;
        CompletableFuture<String> compare = enqueue("""
            {"trackIndex":1,"sceneIndex":1,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        handle("clip/select", "{\"trackIndex\":2,\"slotIndex\":2,\"force\":true}");
        drainPending();

        JsonObject compareData = errorOf(compare).getAsJsonObject("data");
        assertEquals("cursor-position", compareData.get("refusalReason").getAsString(),
            compareData.toString());
        assertFalse(compareData.get("notesDispatched").getAsBoolean(),
            "a refusal that wrote nothing claimed it had dispatched notes: " + compareData);
    }

    /**
     * THE REUSED-PRIOR-NAME SHAPE, where the witness could otherwise be a value that was already
     * true -- and the NAME conjunct standing on its own.
     *
     * <p>The caller supplies no name into an OCCUPIED slot, so {@code finalNameFor} reuses that
     * slot's own prior name and the landing proof's witness is a string the named slot itself
     * once published. What keeps that from being vacuous is the stamp: for the whole window the
     * proof covers, the named slot is publishing the TOKEN and not the reused name. This case
     * asserts exactly that -- the named slot reads the token at proof time -- so the pass is
     * about the name and not about the reuse.
     *
     * <p>The FRESHNESS fact is deliberately satisfied here, through
     * {@link #armFreshObservationAtSlot}: on the ordinary wrong-slot path the named slot's last
     * observation predates the rename, so freshness alone would refuse and the name fact could be
     * deleted from the handler with every test still green. With freshness satisfied, ONLY the
     * name fact can produce this refusal, which is what makes this case its sole witness.
     */
    @Test
    void refusesOnTheNameFactAloneWhenTheNamedSlotIsFreshlyObservedAndStillReadsTheToken()
            throws Exception {
        observerLagRounds = 1;
        // OCCUPIED, and named: this is the shape where the recorded final name is the named
        // slot's own reused prior name.
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, true);
        StateCacheTestHelper.setClipSlotName(stateCache, 0, 4, "Owner Riff");
        StateCacheTestHelper.bumpClipObservationSeq(stateCache, 0, 4);
        armPostStampEchoCursorMove(0, 5);
        armFreshObservationAtSlot(0, 4);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        // The precondition that makes the name fact non-vacuous, asserted rather than assumed:
        // the named slot is carrying the engine's token, not the name the proof is looking for.
        assertEquals(stampPrefix + "1-c0", stateCache.getClipNameAtBankSlot(0, 4),
            "the named slot was not carrying the token at proof time, so this case does not"
                + " exercise the interposition it exists for: " + callLog);

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("landing-echo", data.get("refusalReason").getAsString(),
            "a write whose notes went to another clip was not refused by the landing proof: "
                + data);
        assertFalse(callLog.contains("clip/setNotes:t0s4:1"),
            "the notes reached the named slot, so this is not the case under test: " + callLog);
    }

    /**
     * THE SEQUENCE CONJUNCT STANDING ON ITS OWN, in a harness state reached directly rather than
     * through the stamp path.
     *
     * <p>The named slot publishes a name equal, exactly, to the recorded final name, and its
     * observation is NOT newer than the rename that wrote it. The name fact therefore passes and
     * only the freshness fact can refuse -- the exact mirror of the case above, and the reason
     * neither of the two facts is permitted to be the one the tests assume while proving the
     * other. A handler that dropped the freshness fact reports this write as landed, and this is
     * the only case in the class that would go red.
     */
    @Test
    void refusesOnTheSequenceFactAloneWhenTheNamedSlotsMatchingNameIsAnOldObservation()
            throws Exception {
        observerLagRounds = 1;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);
        armPostStampEchoCursorMove(0, 5);
        // Reached DIRECTLY: the named slot is given the closing rename's own name with no
        // observation bump, so the only thing wrong with it is its age.
        armStaleFinalNameAtSlot(0, 4);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,"name":"Take One",
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        // The precondition: the name fact PASSES. Asserted so the case cannot go green because
        // the name happened to be wrong after all.
        assertEquals("Take One", stateCache.getClipNameAtBankSlot(0, 4),
            "the named slot is not publishing the recorded final name, so the name fact is doing"
                + " the refusing and this case proves nothing: " + callLog);

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("landing-echo", data.get("refusalReason").getAsString(),
            "an observation older than the rename it is meant to witness was accepted as a"
                + " proof: " + data);
    }

    /**
     * The proof is paid PER CLIP and never amortised (D-31-11): a chain whose cursor is stolen
     * during its SECOND clip keeps the first clip's landed record and refuses only the second.
     */
    @Test
    void aChainKeepsTheFirstClipsLandedRecordAndRefusesOnlyTheSecond() {
        // Skip the first clip's stamp; fire on the second.
        armPostStampEchoCursorMove(5, 0, 1);

        handle("macro/buildSection", """
            {"sceneName":"Verse","sceneIndex":0,"clips":[
                {"trackIndex":0,"lengthBeats":8,"stepSize":0.25,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]},
                {"trackIndex":1,"lengthBeats":8,"stepSize":0.25,
                 "notes":[{"x":0,"y":48,"velocity":100,"duration":1}]}
            ]}""");
        drainPending();

        assertTrue(callLog.contains("clip/setNotes:t0s0:1"),
            "the first clip's notes never landed on the slot it named: " + callLog);
        assertTrue(callLog.contains("clip/setNotes:t5s0:1"),
            "the second clip's notes did not follow the stolen cursor, so this is not the case"
                + " under test: " + callLog);

        assertEquals(1, stateCache.getWriteClipRefusals(),
            "the chain refused a number of clips other than one: " + callLog);
        assertEquals(1, errorLog.size(), errorLog.toString());
        String line = errorLog.get(0);
        assertTrue(line.contains("reason=landing-echo"),
            "the second clip was refused by something other than the landing proof: " + line);
        assertTrue(line.contains("landed=[t0s0]"),
            "the first clip's landed record did not survive the second clip's refusal: " + line);
        assertTrue(line.contains("notWritten=[t1s0]"),
            "the line must name the clip that was refused: " + line);
    }

    /**
     * The landing proof's budget is ITS OWN, behind the two in front of it rather than carved out
     * of them.
     *
     * <p>Three facts, and together they are what makes the new constant the only thing that
     * changed. The refusal quotes {@code LANDING_ECHO_CEILING_MS} rather than the stamp echo's
     * ceiling; the proof re-polls exactly that ceiling's worth of flushes and not one more; and
     * the three constants D-31-03 forbids widening still read what they read.
     *
     * <p>The ceilings are READ off {@code MacroHandler} rather than restated here, for the reason
     * {@link StateCacheTestHelper#flushDelayOf(Class)} exists: a test that restates a constant is
     * a test that can be left behind asserting the old number.
     */
    @Test
    void theLandingProofRePollsOnItsOwnCeilingAndLeavesTheOtherBudgetsAlone() throws Exception {
        long flush = ceilingOf("FLUSH_DELAY_MS");
        long cursorVerify = ceilingOf("CURSOR_VERIFY_CEILING_MS");
        long stampEcho = ceilingOf("STAMP_ECHO_CEILING_MS");
        long landing = ceilingOf("LANDING_ECHO_CEILING_MS");

        // D-31-03: this round ADDS a proof rather than widening one.
        assertEquals(100L, flush, "FLUSH_DELAY_MS moved");
        assertEquals(250L, cursorVerify, "CURSOR_VERIFY_CEILING_MS was widened");
        assertEquals(200L, stampEcho, "STAMP_ECHO_CEILING_MS was widened");

        // D-31-26: the walked worst case is a SUM over the constants the build carries, and the
        // deeper of its two paths is the refusal one, which pays the put-back's two flushes.
        assertEquals(cursorVerify + stampEcho + landing + flush + flush,
            ceilingOf("WRITE_WORST_CASE_MS"),
            "the worst case is not the sum this build's own constants add up to");

        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);
        suppressTheClosingRenamePublication = true;

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,"name":"Take One",
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");

        int roundsToDispatch = 0;
        while (roundsToDispatch < MAX_DRAIN_ROUNDS
            && !callLog.contains("clip/setNotes:t0s4:1")) {
            runPendingOnce();
            roundsToDispatch++;
        }
        assertTrue(callLog.contains("clip/setNotes:t0s4:1"),
            "the write never dispatched its notes: " + callLog);

        int landingPolls = 0;
        while (landingPolls < MAX_DRAIN_ROUNDS && !future.isDone()) {
            runPendingOnce();
            landingPolls++;
        }
        assertTrue(future.isDone(), "the landing proof never reached a terminal answer");
        assertEquals(landing / flush, landingPolls,
            "the landing proof spent a number of flushes other than its own ceiling's worth:"
                + " " + landingPolls + " polls at a ceiling of " + landing + " ms");

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals(landing, data.get("ceilingMs").getAsLong(),
            "the refusal quoted a budget other than the one it actually spent: " + data);
    }

    // --- 13. The stamp put-back: it reaches the success path, and it will not rename a
    //         bystander (plan 31-17, 31-REVIEW.md CR-02 and CR-03) ---

    /**
     * The undo and the put-back are mutually exclusive BY CONSTRUCTION: where the undo is about
     * to delete the clip the stamp is on, no put-back rename is dispatched at all.
     *
     * <p>This is the shape the live 150 ms row actually had -- the named slot held a clip this
     * job created into a slot it proved empty -- and putting a name back on a clip that is about
     * to cease to exist is one more cursor-scoped rename at the worst possible moment, with no
     * beneficiary. Both decisions read the SAME recorded emptiness facts, which is why they
     * cannot disagree about which case this is.
     */
    @Test
    void theUndoTakesTheStampWithTheClipAndNoPutBackRenameIsDispatched() throws Exception {
        observerLagRounds = 1;
        // PROVEN EMPTY: this is what licenses the undo, and it is the fact the put-back skip
        // reads too.
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);
        armPostStampEchoCursorMove(0, 5);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("landing-echo", data.get("refusalReason").getAsString(), data.toString());
        assertTrue(data.get("clipRemoved").getAsBoolean(),
            "the clip the stamp was on was not removed, so the stamp survives: " + data);
        assertTrue(callLog.contains("clip/delete:t0s4"),
            "the undo never ran: " + callLog);

        // TWO renames and no more: the stamp, and the closing rename that followed the cursor.
        // A third would be a put-back aimed at a clip that no longer exists.
        assertEquals(2, callLog.stream().filter(c -> c.startsWith("clip/rename")).count(),
            "a put-back rename was dispatched at a clip the undo was about to delete: "
                + callLog);
        assertTrue(data.get("stampLeftAt").isJsonNull(),
            "a put-back that was never attempted reported a location: " + data);
        assertTrue(data.get("stampNotRestoredReason").isJsonNull(),
            "a put-back that was never attempted gave a reason for not succeeding: " + data);
    }

    /**
     * GAP 2 IN ONE CASE. The overwrite path -- the clip at the named slot is the OWNER'S, so the
     * undo does not act -- is where the engine's own internal token would otherwise be
     * permanent, and it is the path {@code restoreStampedName} could not be reached from at all
     * before this plan.
     *
     * <p>The landing proof fails here because the closing rename's publication has not landed,
     * which `31-REVIEW.md` CR-03 names as the likeliest way an echo misses. That is also the
     * sub-case where the cursor never left the named slot, so it is the one the put-back can
     * genuinely succeed from.
     */
    @Test
    void thePutBackGivesTheOwnersClipItsNameBackOnTheOverwritePath() throws Exception {
        // OCCUPIED, and it is the owner's clip: the undo will not act on this slot.
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, true);
        StateCacheTestHelper.setClipSlotName(stateCache, 0, 4, "Owner Riff");
        StateCacheTestHelper.bumpClipObservationSeq(stateCache, 0, 4);
        suppressTheClosingRenamePublication = true;

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,"name":"New Take",
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("landing-echo", data.get("refusalReason").getAsString(), data.toString());
        assertFalse(data.get("clipRemoved").getAsBoolean(),
            "the owner's own clip was deleted by a refusal: " + data);

        assertEquals("Owner Riff", stateCache.getClipNameAtBankSlot(0, 4),
            "the owner's clip was left carrying the engine's own token: " + callLog);
        assertTrue(callLog.contains("clip/rename:t0s4:Owner Riff"),
            "the prior name was never put back through the cursor: " + callLog);
        assertTrue(data.get("stampRestored").getAsBoolean(),
            "a restore that was proven must say so: " + data);
        assertEquals("t0s4", data.get("stampLeftAt").getAsString(), data.toString());
        assertTrue(data.get("stampNotRestoredReason").isJsonNull(),
            "a put-back that succeeded gave a reason for not succeeding: " + data);
    }

    /**
     * CR-02: the put-back refuses to rename a clip this write never touched.
     *
     * <p>The rename is cursor-scoped and the coordinate the token was found at came from a
     * snapshot, so the two can disagree -- and on this path they DO, by construction: the cursor
     * moved after the echo, which is why the landing proof failed at all. Without the check the
     * put-back would write the NAMED slot's prior name onto whatever clip the cursor had wandered
     * to, which is a second, larger version of the defect the stamp exists to prevent.
     */
    @Test
    void thePutBackDispatchesNoRenameAndSaysWhyWhenTheCursorHasMovedAgain() throws Exception {
        observerLagRounds = 1;
        // OCCUPIED, so the undo does not act and the put-back is reached.
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, true);
        StateCacheTestHelper.setClipSlotName(stateCache, 0, 4, "Owner Riff");
        StateCacheTestHelper.bumpClipObservationSeq(stateCache, 0, 4);
        // THE BYSTANDER: a clip of the owner's that this write never addressed.
        StateCacheTestHelper.setClipSlotName(stateCache, 0, 5, "Decoy Loop");
        StateCacheTestHelper.bumpClipObservationSeq(stateCache, 0, 5);
        armPostStampEchoCursorMove(0, 5);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,"name":"New Take",
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("landing-echo", data.get("refusalReason").getAsString(), data.toString());

        // The bystander was NOT given the named slot's prior name. It carries what the stray
        // write put there, which is a different -- and reported -- problem.
        assertNotEquals("Owner Riff", stateCache.getClipNameAtBankSlot(0, 5),
            "the put-back renamed a clip this write never touched: " + callLog);
        assertFalse(callLog.contains("clip/rename:t0s5:Owner Riff"),
            "a put-back rename was dispatched through a cursor that had moved again: " + callLog);

        assertEquals("t0s4", data.get("stampLeftAt").getAsString(),
            "the answer must still say where the token is: " + data);
        assertTrue(data.get("stampRestored").isJsonNull(),
            "an unproven restore must be an absence, never a false: " + data);
        assertTrue(data.get("stampNotRestoredReason").getAsString().contains("moved again"),
            "the answer must say WHICH way the put-back did not happen: " + data);
    }

    /**
     * CR-03: a token nothing has published yet gets one more flush before that is allowed to
     * mean it landed nowhere -- and on the second look it is found and put back.
     *
     * <p>The measured echo is 125 ms of a 200 ms ceiling, so a publication arriving after the
     * ceiling is the ORDINARY slow case rather than an exotic one. Before this, the first
     * snapshot's miss ended the method, and the owner's clip kept the token permanently.
     */
    @Test
    void theTokenNotPublishedAtTheCeilingIsFoundOnTheExtraLookAndPutBack() throws Exception {
        observerLagRounds = 1;
        // The STAMP's own publication is slower than the echo ceiling -- the cursor observer is
        // not, so the write still reaches phase 2 and is refused by the identity proof.
        stampPublicationExtraLagRounds = 2;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);
        StateCacheTestHelper.setClipSlotName(stateCache, 0, 5, "Owner Riff");
        StateCacheTestHelper.bumpClipObservationSeq(stateCache, 0, 5);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        handle("clip/select", "{\"trackIndex\":0,\"slotIndex\":5,\"force\":true}");
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("stamp-echo", data.get("refusalReason").getAsString(),
            "this case must be refused by the identity proof: " + data);
        assertEquals("t0s5", data.get("stampLeftAt").getAsString(),
            "the token was published after the ceiling and the answer said it was nowhere: "
                + data);
        assertTrue(data.get("stampRestored").getAsBoolean(),
            "the extra look found the token and the put-back was still not proven: " + data);
        assertEquals("Owner Riff", stateCache.getClipNameAtBankSlot(0, 5),
            "the owner's clip kept the engine's token: " + callLog);
    }

    /**
     * And when the extra look finds nothing either, the answer NAMES THE TOKEN rather than
     * staying silent, so the owner is told a clip may be carrying it.
     */
    @Test
    void theTokenStillNowhereAfterTheExtraLookIsNamedInTheAnswer() throws Exception {
        observerLagRounds = 1;
        stampPublicationExtraLagRounds = 8;
        StateCacheTestHelper.setClipSlotContent(stateCache, 0, 4, false);

        CompletableFuture<String> future = enqueue("""
            {"trackIndex":0,"sceneIndex":4,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        handle("clip/select", "{\"trackIndex\":0,\"slotIndex\":5,\"force\":true}");
        drainPending();

        JsonObject data = errorOf(future).getAsJsonObject("data");
        assertEquals("stamp-echo", data.get("refusalReason").getAsString(), data.toString());
        assertTrue(data.get("stampLeftAt").isJsonNull(),
            "the answer located a token no slot has published: " + data);
        assertTrue(data.get("stampRestored").isJsonNull(),
            "an unproven restore must be an absence, never a false: " + data);
        assertTrue(data.get("stampNotRestoredReason").getAsString()
                .contains(stampPrefix + "1-c0"),
            "the answer must name the token so the owner can search for it: " + data);
    }

    /**
     * The prior-name read is bounds-guarded in BOTH dimensions, tested on the helper itself
     * because the state that would throw is unreachable through the handler: both snapshots come
     * from one fixed-size builder today, which is an accident of the implementation rather than
     * a property anything asserts.
     *
     * <p>An index failure here escapes a SCHEDULED TASK, so it reaches no terminal path and
     * latches the write queue for the session -- the WR-05 failure shape one handler over. That
     * is why the guard is worth having against a state today's code cannot produce.
     */
    @Test
    void thePriorNameReadAnswersRatherThanThrowingForEveryOutOfBoundsCoordinate() {
        String[][] names = new String[2][2];
        names[0][0] = "Owner Riff";
        names[1] = null;

        assertEquals("Owner Riff", MacroHandler.priorNameAt(names, 0, 0));
        assertEquals("", MacroHandler.priorNameAt(names, 0, 1), "an unobserved name is empty");
        assertEquals("", MacroHandler.priorNameAt(null, 0, 0), "a null snapshot");
        assertEquals("", MacroHandler.priorNameAt(names, -1, 0), "a negative bank slot");
        assertEquals("", MacroHandler.priorNameAt(names, 9, 0), "a bank slot past the end");
        assertEquals("", MacroHandler.priorNameAt(names, 1, 0), "a null row");
        assertEquals("", MacroHandler.priorNameAt(names, 0, -1), "a negative scene");
        assertEquals("", MacroHandler.priorNameAt(names, 0, 9), "a scene past the end");
    }

    // --- Helpers ---

    /**
     * Scroll the modelled scene bank, and tell the state cache the same thing.
     *
     * <p>Both halves, because they are two different readers of one fact: this class's own
     * {@link #sceneBankScrollPosition} is what the select stub converts the OBSERVATION with, and
     * the cache's {@code sceneBankOffset} is what a scene-axis resolver in the engine would read.
     * A test that set only one would model a bank that is scrolled for one reader and not the
     * other, which is a state production cannot be in.
     */
    private void scrollSceneBank(int scrollPosition) {
        sceneBankScrollPosition = scrollPosition;
        StateCacheTestHelper.setSceneBankOffset(stateCache, scrollPosition);
    }

    /**
     * Arm the post-proof cursor move: once the named slot publishes the stamp token, the cursor
     * goes to {@code trackIndex} / {@code slotIndex}, once.
     *
     * <p>Both arguments are in the CALLER's space — a public track index and a bank-window slot
     * subscript — because the move is delivered through {@code clip/select}, which is the route a
     * competing surface or a user click takes.
     */
    private void armPostStampEchoCursorMove(int trackIndex, int slotIndex) {
        armPostStampEchoCursorMove(trackIndex, slotIndex, 0);
    }

    /**
     * The same, but letting {@code afterStamps} stamp publications pass first — which is how a
     * case says it is about the SECOND clip of a chain rather than the first.
     */
    private void armPostStampEchoCursorMove(int trackIndex, int slotIndex, int afterStamps) {
        postStampEchoCursorTrack = trackIndex;
        postStampEchoCursorSlot = slotIndex;
        postStampEchoStampsToSkip = afterStamps;
    }

    /**
     * Arm the stale final name: the moment the closing rename is DISPATCHED, give
     * {@code bankSlot}/{@code sceneIndex} that same name with NO observation bump.
     *
     * <p>See {@link #staleFinalNameBankSlot} for why this is written into the cache directly
     * rather than routed through the rename stub's publication.
     */
    private void armStaleFinalNameAtSlot(int bankSlot, int sceneIndex) {
        staleFinalNameBankSlot = bankSlot;
        staleFinalNameScene = sceneIndex;
    }

    /**
     * Arm the fresh observation: the moment the closing rename is DISPATCHED, bump
     * {@code bankSlot}/{@code sceneIndex}'s observation sequence and leave its name alone.
     *
     * <p>See {@link #freshObservationBankSlot} for why the name conjunct cannot be tested without
     * it.
     */
    private void armFreshObservationAtSlot(int bankSlot, int sceneIndex) {
        freshObservationBankSlot = bankSlot;
        freshObservationScene = sceneIndex;
    }

    /** Deliver the armed observation bump, if there is one, and disarm it so it applies once. */
    private void fireFreshObservationAtSlot() {
        if (freshObservationBankSlot < 0 || freshObservationScene < 0) {
            return;
        }
        int bankSlot = freshObservationBankSlot;
        int sceneIndex = freshObservationScene;
        freshObservationBankSlot = -1;
        freshObservationScene = -1;
        StateCacheTestHelper.bumpClipObservationSeq(stateCache, bankSlot, sceneIndex);
    }

    /** Deliver the armed stale name, if there is one, and disarm it so it applies once. */
    private void fireStaleFinalNameAtNamedSlot(String name) {
        if (staleFinalNameBankSlot < 0 || staleFinalNameScene < 0) {
            return;
        }
        int bankSlot = staleFinalNameBankSlot;
        int sceneIndex = staleFinalNameScene;
        staleFinalNameBankSlot = -1;
        staleFinalNameScene = -1;
        StateCacheTestHelper.setClipSlotName(stateCache, bankSlot, sceneIndex, name);
    }

    /**
     * Read a {@code private static final long} ceiling off {@link MacroHandler} rather than
     * restating its value here, the way {@link StateCacheTestHelper#flushDelayOf(Class)} already
     * does for the flush delay and for the same reason: a test that restates a constant is a test
     * that can be left behind asserting the old number.
     */
    private static long ceilingOf(String name) {
        try {
            java.lang.reflect.Field field = MacroHandler.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getLong(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("MacroHandler declares no long constant named " + name, e);
        }
    }

    /**
     * Deliver the armed move, if there is one, and disarm it so it fires at most once per write.
     *
     * <p>It goes through the ordinary {@code clip/select} stub rather than writing the cursor
     * fields directly, so the move is two-valued exactly like every other selection in this class:
     * INTENT now, OBSERVATION {@link #observerLagRounds} rounds later. A hook that moved only the
     * true cursor would be modelling a selection Bitwig never publishes.
     */
    private void firePostStampEchoCursorMove() {
        if (postStampEchoCursorTrack < 0 || postStampEchoCursorSlot < 0) {
            return;
        }
        if (postStampEchoStampsToSkip > 0) {
            postStampEchoStampsToSkip--;
            return;
        }
        int trackIndex = postStampEchoCursorTrack;
        int slotIndex = postStampEchoCursorSlot;
        postStampEchoCursorTrack = -1;
        postStampEchoCursorSlot = -1;
        handle("clip/select", "{\"trackIndex\":" + trackIndex + ",\"slotIndex\":" + slotIndex
            + ",\"force\":true}");
    }

    /**
     * The PROJECT-ABSOLUTE track position of a PHYSICAL BANK SLOT — what
     * {@code cursorClip.getTrack().position()} would report for the track in that slot.
     *
     * <p>The identity by default, so the two spaces coincide unless a test separates them.
     * Out-of-range subscripts fall through unchanged rather than throwing, because {@code -1} — the
     * never-observed sentinel — is a value this is legitimately asked about.
     */
    private int absoluteTrackPositionOf(int bankSlot) {
        if (bankSlot < 0 || bankSlot >= modelledTrackPositions.length) {
            return bankSlot;
        }
        return modelledTrackPositions[bankSlot];
    }

    /**
     * The PROJECT-ABSOLUTE scene index of a BANK-WINDOW slot subscript — what
     * {@code cursorClip.clipLauncherSlot().sceneIndex()} would report for that slot.
     *
     * <p>The identity at a scroll of zero, which is the default and every test written before plan
     * 31-15. A negative subscript passes through unchanged for the reason
     * {@link #absoluteTrackPositionOf(int)} gives.
     */
    private int absoluteSceneOf(int bankWindowSlot) {
        if (bankWindowSlot < 0) {
            return bankWindowSlot;
        }
        return sceneBankScrollPosition + bankWindowSlot;
    }

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
