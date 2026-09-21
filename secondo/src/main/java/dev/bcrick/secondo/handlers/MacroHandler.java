package dev.bcrick.secondo.handlers;

import com.google.gson.*;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonParamValidator;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.rpc.JsonRpcError;
import dev.bcrick.secondo.rpc.PendingResponse;
import dev.bcrick.secondo.rpc.TaskScheduler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static dev.bcrick.secondo.rpc.JsonParamValidator.*;

/**
 * The multi-step macros: create a track, write a clip, build a section.
 *
 * <h2>Why the launcher writes are deferred, verified, and queued</h2>
 *
 * <p>There is no non-cursor note-writing surface at API v25. {@code ClipLauncherSlot} carries no
 * note, step or {@code Clip} accessor, the only launcher {@code Clip} factory is
 * {@code CursorTrack#createLauncherCursorClip}, and {@code CursorClip#selectClip(Clip)} can only
 * re-point at a {@code Clip} you already hold. So writing notes to slot (t, s) means moving the
 * ONE cursor clip there and writing to the cursor -- and the cursor moves on the flush cycle,
 * not on the call.
 *
 * <p>That gap is where notes used to land on the wrong slot (TODO-WRONG-SLOT / P-C-05): phase 2
 * wrote to whatever the cursor happened to be by then. It now compares the caller's
 * (trackIndex, sceneIndex) against the cursor clip's OWN observed absolute position
 * ({@code StateCache#getClipCursorTrackPosition()} / {@code #getClipCursorSceneIndex()}),
 * re-polls to {@link #CURSOR_VERIFY_CEILING_MS}, and writes only on a match. A mismatch is
 * refused -- never retried onto a different slot, never written with a warning.
 *
 * <p>WHAT IS TRUE FROM PHASE 31. That compare is kept, unchanged and at its own ceiling, but it
 * is no longer the whole proof -- because it can AGREE AND BE WRONG. At a competing selection
 * about a hundred milliseconds into a write, the cursor clip has already re-pointed while the
 * observers still report where phase 1 put it: the compare matches, the notes go to the new
 * cursor clip, an empty unnamed clip is left at the slot the caller named, and the engine
 * classifies the whole thing as a success. {@code 31-ECHO-MEASUREMENT.md}'s 100 ms row is that
 * outcome measured against a running Bitwig, with the response positively claiming
 * {@code landed: t7s4} while the notes were at {@code t7s5}.
 *
 * <p>So the compare is now the cheap filter in FRONT of an identity proof, and the proof changes
 * both the moment it is taken and the witness that takes it. Before any note is dispatched, a
 * unique token is written through the cursor and the NAMED SLOT'S OWN name observer -- a witness
 * fed by the launcher, completely independent of the cursor -- must report that exact token on an
 * observation newer than the stamp. Only then do the step size and the notes go out. The proof
 * comes BEFORE the notes, so a failure has written none anywhere and the existing refusal and
 * undo apply unchanged; and because the stamp is itself a rename of a clip whose identity is not
 * yet proven, every slot name is snapshotted first and put back when the proof fails. See
 * {@link #stampThenProve}, {@link #proveStampEcho} and {@link #restoreStampedName}.
 *
 * <h2>What {@code ok} means here, exactly</h2>
 *
 * <p>WHAT WAS TRUE UNTIL PHASE 29, kept because the paragraph that was believed is half of the
 * record and deleting it would leave a later reader unable to date the change:
 *
 * <blockquote>
 * <p>{@code macro/writeClip} returns when the write has been ACCEPTED AND QUEUED, not when the
 * notes have landed. It cannot do better at this pin, and the reason is structural rather than a
 * matter of effort: handlers execute inside Bitwig's {@code flush()} on the Control Surface
 * Session thread ({@code CommandQueue.java:20-22}), and {@code host.scheduleTask} schedules onto
 * that SAME thread. A handler that blocked on its own deferred verify would block the flush that
 * the verify is waiting for -- a deadlock, not a slow path. Making the RPC response itself
 * completable after the handler returns was DEFERRED TO Phase 29 ("Engine: Deferrable RPC
 * Responses"), which since the fourteenth build-pin move emits
 * {@link JsonRpcError#CURSOR_MISMATCH} / {@link JsonRpcError#NOTE_WRITE_FAILED} in the response.
 * Until it did, a refusal was announced two ways, both retrievable:
 *
 * <ul>
 *   <li>a {@code host.errorln} line marked {@value #MARKER_CURSOR_MISMATCH} or
 *       {@value #MARKER_NOTE_WRITE_FAILED}, with an ISO-8601 timestamp and both positions -- and
 *       these SURVIVE UNCHANGED, for the reason the paragraph below gives; and</li>
 *   <li>{@code writeClipRefusals} in the snapshot's clip section, and beside it the per-refusal
 *       detail that Phase 29 retired. The counter was kept and the detail went; the paragraph
 *       below says which was which and why.</li>
 * </ul>
 * </blockquote>
 *
 * <p>WHAT IS TRUE FROM PHASE 29 (plan 29-01, 2026-09-19). The diagnosis above still holds
 * exactly: a handler still cannot block on its own verify, and this one still returns before the
 * notes land. What changed is that the RESPONSE no longer has to leave when the handler does. A
 * top-level, single, non-notification {@code macro/writeClip} now CLAIMS its response
 * ({@code JsonRpcDispatcher#deferCurrentResponse}), the queue leaves that caller's future
 * outstanding, and one of this class's four terminal paths completes it later, from a scheduled
 * task, with what actually happened -- including {@link JsonRpcError#CURSOR_MISMATCH} and
 * {@link JsonRpcError#NOTE_WRITE_FAILED} as real error objects in the response.
 *
 * <p>The marked {@code host.errorln} lines above SURVIVE UNCHANGED and are still written on every
 * refusal (D-29-13 -- the docs tell owners to search for those literals, so they are not replaced
 * by the response, they are joined by it). The SNAPSHOT half was NARROWED by plan 29-03, and the
 * narrowing is stated here rather than discovered: {@code writeClipRefusals} is kept as a session
 * tally, and the per-refusal detail that stood beside it is retired, because that detail existed
 * only to carry what the response can now carry itself and a second copy of a fact can disagree
 * with the first. The four chain macros below do not defer, so the kept counter is their only
 * machine-readable refusal signal -- which is why it is a counter that stayed and a detail that
 * went. See {@code StateCache#recordWriteClipRefusal}.
 *
 * <p>WHAT IS TRUE FROM PHASE 31 about that response. The refusal it carries now has TWO triggers
 * rather than one, and the response says which of them fired: the cheap position compare
 * disagreed ({@code refusalReason} reading {@code cursor-position}), or it agreed and the
 * identity proof behind it did not ({@code stamp-echo}). Both are the same clean refusal -- one
 * terminal path, one code, one undo -- because what Phase 31 added is a TRIGGER and not an
 * OUTCOME (D-31-07). The proof itself is the stamp described at the top of this class: a unique
 * token written through the cursor BEFORE any note is dispatched, and required back from the
 * named slot's OWN name observer -- a witness fed by the launcher and wholly independent of the
 * cursor -- on an observation newer than the stamp. Its budget,
 * {@link #STAMP_ECHO_CEILING_MS}, is the echo measured against a running Bitwig in
 * {@code 31-ECHO-MEASUREMENT.md} (one flush, 125 ms) plus one flush of headroom, which is why it
 * is a constant of its own rather than a share of {@link #CURSOR_VERIFY_CEILING_MS}. And because
 * the stamp is a rename taken on a clip whose identity is not yet proven, the refusal also says
 * what became of it, in {@code stampRestored} and {@code stampLeftAt} -- both explicit nulls
 * where nothing was stamped, or where the put-back could not be proven.
 *
 * <p>WHAT A REFUSAL DOES TO THE SLOT (plan 29-03). {@code refuseJob} removes the clip this job
 * created, and only when the named slot was proven empty before the creation -- in range, and
 * observed, and observed empty. Anything it cannot prove it LEAVES, and says why in
 * {@code leftoverReason}. The removal is addressed by the caller's own {@code (trackIndex,
 * slotIndex)} through {@code clip/delete} and never by the cursor, because the refusal IS that
 * the cursor is somewhere else. {@code failJob} removes nothing at all: a write that failed may
 * have written something.
 *
 * <p>WHAT IS TRUE FROM PHASE 31, PLAN 05, about the paragraph above. "Proven empty before the
 * creation" gained a FOURTH fact and lost a coordinate ambiguity. Every fact is now read at the
 * RESOLVED physical bank slot -- the one the create dispatched into and the one the delete will
 * address -- rather than at the caller's public track index, which is a different track the
 * moment the bank is scrolled or a group is collapsed (29-REVIEW.md, WR-02). And the reading is
 * no longer accepted on its own age: {@code settleSlotEvidence} requires the launcher to have
 * reported that slot on an observation NEWER than the instant the job began, because Bitwig
 * fires every slot's has-content observer at init and a merely-present observation is therefore
 * the permanent state of every in-range slot (WR-01, D-29-11). The reading is still taken before
 * the create -- it can be taken nowhere else -- but it is dated there and proven later. Anything
 * it cannot prove still resolves to leaving the clip and saying why.
 *
 * <p>{@code clipCreated} was corrected in the same pass and for the same reason. It used to be
 * set {@code true} on any {@code clip/create} that did not throw, and at API v25 a create over an
 * occupied slot is acknowledged and changes nothing -- so a refusal aimed at a slot holding the
 * owner's material reported that this write had created a clip there (29-REVIEW.md, IN-05). It is
 * now DERIVED from the emptiness answer: true where the slot was proven empty, false where it was
 * proven occupied, and an explicit JSON null where the emptiness could not be proven, because a
 * create that may or may not have happened is a third state and collapsing it into false is the
 * same misreport one size smaller.
 *
 * <p>Deferral is deliberately NOT universal. It is taken only when
 * {@code JsonRpcDispatcher#deferralAvailable()} says so -- never for a notification, never inside
 * a batch, never inside {@code session/transaction} -- and never for the chain macros
 * {@code buildSection}, {@code buildSong}, {@code setupScenes} or {@code writeAutomation}, whose
 * chains are unbounded (roughly {@link #FLUSH_DELAY_MS} per clip plus an expression hop) and
 * cannot answer inside either five-second wall (D-29-05). Those keep the accepted-and-queued
 * behaviour described in the block quote above, and say so in their response.
 *
 * <p>Nor is it taken when the write queue is already busy. A {@code macro/writeClip} arriving
 * behind a chain provably cannot resolve inside {@link #DEFERRAL_DEADLINE_MS}, so it declines the
 * deferral at the front door and answers at once with {@code deferReason} reading
 * {@code queue-busy} -- see {@link #handleWriteClip}. Every deferral that IS taken is bounded by
 * that deadline, after which the caller is answered with
 * {@link JsonRpcError#WRITE_UNRESOLVED}: the write may still have landed, so read the slot back
 * and never retry it.
 *
 * <h2>Serialisation</h2>
 *
 * <p>Writes are queued, and the queue advances only when a write's LAST cursor-scoped step has
 * run -- including the expression hop. Write N+1's clip creation and cursor move therefore cannot
 * begin inside write N's verify window, which is the race the queue exists to remove. The queue
 * is a plain {@link ArrayDeque} with no synchronisation, deliberately: every path that touches it
 * runs on the one Control Surface Session thread named above.
 */
public class MacroHandler {

    private static final long FLUSH_DELAY_MS = 100;

    /**
     * How long the verify may keep re-reading the cursor position before refusing the write.
     *
     * <p>The observers this reads are themselves on the flush cycle, so a single read taken at
     * {@code FLUSH_DELAY_MS} can be one flush stale -- re-polling is load-bearing, not
     * belt-and-braces. 250 ms against P-C-13's measured cursor-position figures (stale at ~35 ms,
     * fresh at ~80-89 ms) leaves room for a slow flush without letting a genuinely wrong cursor
     * sit unexamined. With checks every {@code FLUSH_DELAY_MS} that is two observations, at
     * ~100 ms and ~200 ms.
     *
     * <p>Exhausting it REFUSES. It is not a timeout after which the write proceeds hopefully.
     */
    private static final long CURSOR_VERIFY_CEILING_MS = 250;

    /**
     * The prefix every identity stamp carries.
     *
     * <p>A stamp is a real rename of a real clip, so the name it writes has to explain itself if a
     * crash ever leaves one visible: it says which program wrote it, and the sequence and clip
     * index after it say which write. It is deliberately NOT derived from the caller's own final
     * name -- a caller writing one name to two clips would otherwise make a collision read as a
     * proof (D-31-04).
     */
    private static final String STAMP_PREFIX = "SECONDO-STAMP-";

    /**
     * How long the identity proof may wait for the NAMED SLOT'S OWN name observer to echo the
     * stamp before the write is refused.
     *
     * <p>SIZED FROM A MEASUREMENT. {@code 31-ECHO-MEASUREMENT.md} recorded
     * {@code echo_flushes: 1} and {@code echo_elapsed_ms: 125} against a running Bitwig at this
     * pin: a cursor-scoped {@code clip/rename} reached the slot's own published name on the FIRST
     * look. This is that measured flush count plus one flush of headroom -- two polls, at
     * {@link #FLUSH_DELAY_MS} and at twice it -- because a figure taken once on a quiet session is
     * a latency, not a guarantee.
     *
     * <p>IT IS ITS OWN BUDGET, and that is the point. {@link #CURSOR_VERIFY_CEILING_MS} is
     * deliberately untouched at 250 (D-31-03): the position compare in front of this proof is
     * live-proven at the 0 / 30 / 60 ms delays and re-polling it further changes nothing, so this
     * phase ADDS a proof rather than widening one. Sharing that counter would also have left the
     * proof whatever the compare had not already spent, which makes a budget an accident of
     * another step's luck.
     *
     * <p>Capped so a proven clip still costs under 400 ms (D-31-10): one hop to the verify, at
     * most two to the echo, one for the expression pass.
     *
     * <p>Exhausting it REFUSES, exactly as the compare's ceiling does. It is not a timeout after
     * which the write proceeds hopefully.
     */
    private static final long STAMP_ECHO_CEILING_MS = 200;

    /**
     * How long a DEFERRED {@code macro/writeClip} response may stay outstanding before it is
     * answered with {@link JsonRpcError#WRITE_UNRESOLVED} rather than left to run into a
     * transport wall.
     *
     * <p>THE ARITHMETIC, not the number alone. A lone {@code macro/writeClip} resolves in at most
     * 300 ms of engine-internal scheduling: one hop of {@link #FLUSH_DELAY_MS} to the verify, a
     * cursor re-poll that exhausts at elapsed 200 -- because 200 plus another hop exceeds
     * {@link #CURSOR_VERIFY_CEILING_MS} -- and one further hop for the expression pass. This
     * constant is a ten-times margin against that worst case (D-29-06).
     *
     * <p>AND IT IS STRICTLY INSIDE BOTH FIVE-SECOND WALLS: {@code HttpRpcServer.TIMEOUT_MS}, and
     * the Python client's {@code read=5.0} in {@code src/secondo/rpc_client.py} (D-29-08). That
     * is the property that matters on the HTTP path. No HTTP pool thread waits longer than it did
     * before Phase 29, and the caller receives a JSON-RPC error carrying its own id instead of
     * the bodiless HTTP 500 with no id that {@code HttpRpcServer.java:148-150} produces when
     * {@code future.get} times out.
     *
     * <p>ON THE WEBSOCKET PATH THIS IS THE ONLY BOUND THAT EXISTS. {@code WsRpcServer}'s
     * non-blocking {@code thenAccept} has no timeout of its own
     * ({@code WsRpcServer.java:124-129}), so a pending response nothing ever completed would stay
     * outstanding for the life of the connection. T-29-03 is mitigated there by this constant and
     * by nothing else.
     *
     * <p>Changing it is a coordinated edit rather than a one-line one: the figure is published in
     * {@code docs/rpc-api-reference.md}, mirrored in the mock harness, and asserted at both ends
     * of the repository boundary.
     */
    private static final long DEFERRAL_DEADLINE_MS = 3000;

    /**
     * The longest a lone {@code macro/writeClip} can take to reach a terminal answer, in
     * milliseconds. A deferral cannot honestly be claimed with less than this left on the caller's
     * wall.
     *
     * <p>DERIVED BY ADDITION, NEVER WRITTEN AS A LITERAL, and that is the point of it. The figure
     * is the sum of three budgets this class already declares, so a plan that widens any one of
     * them moves this with it instead of leaving a stale number that reads like a measurement:
     *
     * <ul>
     *   <li>{@link #CURSOR_VERIFY_CEILING_MS} -- the position compare, including the hop that
     *       reaches it and the re-polls it pays for;</li>
     *   <li>{@link #STAMP_ECHO_CEILING_MS} -- the identity proof plan 31-04 added AFTER
     *       {@link #DEFERRAL_DEADLINE_MS}'s own arithmetic was written down, which is why that
     *       comment's "300 ms" is a smaller number than this one; and</li>
     *   <li>one further {@link #FLUSH_DELAY_MS} for whichever hop comes last -- the expression
     *       pass on a success, or the name restore in front of a refusal (plan 31-04).</li>
     * </ul>
     *
     * <p>WALKED AGAINST THE ACTUAL CHAIN, so the sum is a bound rather than a hope. The deepest
     * path is: phase 1 synchronously at 0; the verify hop at 100; a second compare at 200, which
     * is the last the ceiling allows; the stamp goes out there and the echo is polled at 300 and
     * at 400, which is the last the echo ceiling allows; and one closing hop at 500 for the
     * restore-and-refuse or for the expressions. 500 measured, 550 budgeted.
     */
    private static final long WRITE_WORST_CASE_MS =
        CURSOR_VERIFY_CEILING_MS + STAMP_ECHO_CEILING_MS + FLUSH_DELAY_MS;

    /**
     * The message {@link JsonRpcError#WRITE_UNRESOLVED} travels with.
     *
     * <p>This is NOT a greppable console marker and no third marker was added: the two that exist
     * are a forensic trail for a refusal the caller could not see, and this outcome is by
     * construction visible to the caller. It leads with the symbol the way the Python tool layer
     * prints one, and then says the thing a caller must not get wrong -- because until plan
     * 29-07's branch lands, {@code write_clip.py}'s generic fall-through prints this message
     * verbatim beside a sentence that says nothing was written (T-29-08).
     */
    private static final String WRITE_UNRESOLVED_MESSAGE =
        "WRITE_UNRESOLVED -- the write was accepted and had not resolved when the deferral"
            + " deadline fired, so it MAY STILL HAVE LANDED. Read the slot back rather than"
            + " retrying: a retry of a creating write puts a second copy of the notes in the clip.";

    /** Greppable marker for a refused launcher write. Do not reword: the docs tell owners to search for it. */
    static final String MARKER_CURSOR_MISMATCH = "SECONDO-CURSOR-MISMATCH";

    /** Greppable marker for a launcher write that was correctly targeted and still failed. */
    static final String MARKER_NOTE_WRITE_FAILED = "SECONDO-NOTE-WRITE-FAILED";

    /**
     * {@code refusalReason} when the cheap pre-write position compare never agreed.
     *
     * <p>The two reasons are a DISCRIMINATOR on one outcome, not two outcomes. A refusal is a
     * refusal: same code, same undo, same response shape, same terminal path. This key exists so a
     * caller can tell which of the two triggers fired without the engine having to invent a second
     * refusal to carry the difference.
     */
    private static final String REFUSAL_CURSOR_POSITION = "cursor-position";

    /** {@code refusalReason} when the position compare agreed and the identity proof did not. */
    private static final String REFUSAL_STAMP_ECHO = "stamp-echo";

    /**
     * The scene bank window width, used by {@link #handleBuildSection} to convert an absolute
     * scene index into a bank-relative slot index after scrolling.
     *
     * <p>This was a method-local {@code int bankSize = 5;} carrying the comment
     * "matches SCENE_COUNT" -- a correspondence nothing enforced. It is deliberately NOT named
     * {@code SCENE_COUNT}: this class is not a sixth declaration of the ceiling, it is a
     * consumer of it, and giving it that name would make the five-way agreement test in
     * {@code SceneCountConsistencyTest} read six values while its message still described five.
     * The link back to the real ceiling is asserted by
     * {@code sceneCount_inlineBankSizeLiteralMatchesTheConstant} in that same test class,
     * source-level, because a method-local was invisible to reflection and this promotion is
     * exactly what that assertion now guards against being undone.
     */
    private static final int SCENE_BANK_SIZE = 16;

    private final JsonRpcDispatcher dispatcher;
    private final StateCache stateCache;
    private final TaskScheduler scheduler;
    private final Consumer<String> errorLog;

    /** Queued launcher writes. Drained one at a time; see the class comment on serialisation. */
    private final Deque<WriteJob> writeQueue = new ArrayDeque<>();
    private boolean writeInProgress;

    /**
     * Monotonic per-handler counter behind every stamp token, so no two stamps in a session can
     * collide. One handler exists per extension instance, and everything that touches this runs on
     * the one Control Surface Session thread.
     */
    private long stampSeq;

    /**
     * The production constructor.
     *
     * @param errorLog where a refused or failed write is announced -- {@code host::errorln} in the
     *                 extension, so the line reaches Bitwig's Controller Script Console.
     */
    public MacroHandler(JsonRpcDispatcher dispatcher, StateCache stateCache, TaskScheduler scheduler,
                        Consumer<String> errorLog) {
        this.dispatcher = dispatcher;
        this.stateCache = stateCache;
        this.scheduler = scheduler;
        this.errorLog = errorLog != null ? errorLog : message -> { };
    }

    /**
     * Kept for callers that have no host to log through.
     *
     * <p>Refusals still COUNT (they reach {@code StateCache}); they are simply not printed. This
     * overload exists so a test or an embedding that has no {@code ControllerHost} is not forced
     * to invent one, not as a way to opt out of the announcement.
     */
    public MacroHandler(JsonRpcDispatcher dispatcher, StateCache stateCache, TaskScheduler scheduler) {
        this(dispatcher, stateCache, scheduler, null);
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("macro/createTrack", this::handleCreateTrack);
        dispatcher.register("macro/createClip", this::handleCreateClip);
        dispatcher.register("macro/writeClip", this::handleWriteClip);
        dispatcher.register("macro/buildSection", this::handleBuildSection);
        dispatcher.register("macro/setupScenes", this::handleSetupScenes);
        dispatcher.register("macro/createSound", this::handleCreateSound);
        dispatcher.register("macro/buildSong", this::handleBuildSong);
        dispatcher.register("macro/writeAutomation", this::handleWriteAutomation);
    }

    private JsonElement handleCreateTrack(JsonObject params) throws Exception {
        String type = requireString(params, "type");

        String createMethod;
        switch (type) {
            case "audio":
                createMethod = "track/createAudio";
                break;
            case "instrument":
                createMethod = "track/createInstrument";
                break;
            case "effect":
                createMethod = "track/createEffect";
                break;
            default:
                throw new IllegalArgumentException("Invalid type: " + type
                    + " — must be 'audio', 'instrument', or 'effect'");
        }

        JsonObject createParams = new JsonObject();
        if (params.has("position")) {
            createParams.addProperty("position", params.get("position").getAsInt());
        }

        dispatcher.handleInternal(createMethod, createParams);

        if (params.has("name") && !params.get("name").isJsonNull()) {
            JsonObject renameParams = new JsonObject();
            renameParams.addProperty("name", params.get("name").getAsString());
            dispatcher.handleInternal("track/rename", renameParams);
        }

        if (params.has("color") && !params.get("color").isJsonNull()) {
            JsonObject color = params.getAsJsonObject("color");
            JsonObject colorParams = new JsonObject();
            colorParams.addProperty("r", color.get("r").getAsFloat());
            colorParams.addProperty("g", color.get("g").getAsFloat());
            colorParams.addProperty("b", color.get("b").getAsFloat());
            dispatcher.handleInternal("track/setCursorColor", colorParams);
        }

        boolean hasDevice = params.has("device") && !params.get("device").isJsonNull();
        boolean hasPlugin = params.has("plugin") && !params.get("plugin").isJsonNull();
        boolean hasPages = params.has("pages") && !params.get("pages").isJsonNull();

        if (hasDevice && hasPlugin) {
            throw new IllegalArgumentException("cannot specify both 'device' and 'plugin'");
        }
        if (hasPages && !hasDevice && !hasPlugin) {
            throw new IllegalArgumentException("'pages' requires 'device' or 'plugin' — cannot set parameters without a device");
        }

        if (hasDevice) {
            JsonObject deviceParams = new JsonObject();
            deviceParams.addProperty("name", params.get("device").getAsString());
            dispatcher.handleInternal("device/insertBitwigDevice", deviceParams);
        } else if (hasPlugin) {
            insertPlugin(params.getAsJsonObject("plugin"));
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);

        if (hasPages) {
            JsonArray pages = params.getAsJsonArray("pages");
            JsonObject soundParams = new JsonObject();
            soundParams.add("pages", pages);

            // Count params for response
            int paramCount = 0;
            for (JsonElement pageEl : pages) {
                paramCount += pageEl.getAsJsonObject().getAsJsonArray("params").size();
            }

            // Schedule after flush so the inserted device has initialized
            scheduler.schedule(() -> {
                try {
                    dispatcher.handleInternal("macro/createSound", soundParams);
                } catch (Exception e) {
                    // Deferred sound creation failed
                }
            }, FLUSH_DELAY_MS);

            result.addProperty("pageCount", pages.size());
            result.addProperty("paramCount", paramCount);
        }

        return result;
    }

    private JsonElement handleCreateClip(JsonObject params) throws Exception {
        int trackIndex = requireInt(params, "trackIndex");
        int sceneIndex = requireInt(params, "sceneIndex");
        int lengthBeats = requireInt(params, "lengthBeats");

        createClip(trackIndex, sceneIndex, lengthBeats);
        forceSelectClip(trackIndex, sceneIndex);

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleWriteClip(JsonObject params) throws Exception {
        int trackIndex = requireInt(params, "trackIndex");
        int sceneIndex = requireInt(params, "sceneIndex");
        int lengthBeats = requireInt(params, "lengthBeats");
        double stepSize = requireDouble(params, "stepSize");
        JsonArray notes = requireArray(params, "notes");
        String name = params.has("name") && !params.get("name").isJsonNull()
            ? params.get("name").getAsString() : null;

        // BEFORE the deferral is claimed and before anything is queued (29-REVIEW.md, WR-05).
        // A malformed expression field is a parameter error and must answer as one, here, where
        // the response still belongs to this call stack. Once a deferral is claimed the same fact
        // could only travel as a three-second -32013 saying the write MAY have landed; once the
        // job is queued it could not travel at all.
        validateExpressionFields(notes);

        // Queue the whole write -- clip creation, cursor move, verify, notes, expressions -- as one
        // job. Phase 1 runs inside the job rather than here so that a second writeClip arriving in
        // the same flush cannot move the cursor out from under a write already in its verify
        // window. See the class comment.
        //
        // THE QUEUE-BUSY FRONT DOOR (D-29-06), evaluated before any deferral is claimed.
        //
        // A write that arrives while another is in flight, or with one already queued ahead of it,
        // provably CANNOT resolve inside DEFERRAL_DEADLINE_MS, so it is not made to wait for a
        // proof that will not arrive. macro/buildSection places no upper bound on its clips array
        // -- handleBuildSection validates only non-emptiness -- writes are serialised through the
        // one writeQueue below, and a 25-clip chain in front of this write costs roughly 5,000 ms,
        // past BOTH five-second walls.
        //
        // Declining is strictly better than deferring here, three ways: the caller learns the same
        // fact (the outcome is not yet known) about 2.7 seconds sooner; one of only four HTTP pool
        // threads is not held for the whole deadline while /health -- what diagnose uses to decide
        // the engine is alive -- queues behind it (HttpRpcServer.java:61, T-29-01); and the answer
        // when it finally came would have been WRITE_UNRESOLVED anyway.
        //
        // The queue-position-aware deadline was considered and rejected: it would have to predict
        // cursor re-polls it cannot know about, and a deadline that varies per request is a
        // contract nothing at the other end can assert.
        boolean queueBusy = writeInProgress || !writeQueue.isEmpty();
        boolean deferrable = dispatcher.deferralAvailable();

        // THE LATE FRONT DOOR (29-REVIEW.md, WR-04), evaluated on the caller's clock rather than
        // on this handler's.
        //
        // remainingBudgetMs() is the caller's five-second wall, minus a stated margin, minus how
        // long this request has ALREADY been alive -- and it has been alive since it arrived, not
        // since this method started. Behind a slow flush or a chain that has just drained, most of
        // that wall can be gone before the first line here runs.
        //
        // With less than WRITE_WORST_CASE_MS left, a deferral would be a promise this engine can
        // be sure it cannot keep, and the caller would collect it as a bodiless HTTP 500 with no
        // id -- strictly worse than an answer that arrives at once and says the outcome is not yet
        // known (D-29-06). So it is DECLINED, the same way and through the same key the busy queue
        // declines, with a value that names lateness.
        long remainingBudgetMs = dispatcher.remainingBudgetMs();
        boolean tooLate = remainingBudgetMs < WRITE_WORST_CASE_MS;

        // Claim the response BEFORE enqueuing, because enqueueWrite can run the whole first phase
        // of the job synchronously -- and startNextJob's own catch calls failJob INSIDE this
        // handler's call stack, so the deferral can be RESOLVED before drainAndExecute has bound
        // the command to it. PendingResponse buffers a finished answer for exactly that case; see
        // its class comment. The value this method returns on the deferred path is discarded by
        // handleSingle and must not be relied on by anything.
        PendingResponse pending = (deferrable && !queueBusy && !tooLate)
            ? dispatcher.deferCurrentResponse() : null;

        WriteJob job = new WriteJob("macro/writeClip",
            List.of(new ClipWrite(trackIndex, sceneIndex, lengthBeats, stepSize, notes, name)),
            pending);

        // DECIDED HERE, ARMED BY THE JOB DRIVER (29-REVIEW.md, IN-04 and WR-04 together).
        //
        // It has to be decided here: remainingBudgetMs is a reading about the request currently in
        // a handler and means nothing once this method returns. It must not be ARMED here, because
        // arming is a scheduler call and a scheduler call after enqueueWrite is a statement that
        // can fail while a queued job is already writing. So the figure rides on the job and
        // startNextJob arms it as the job begins -- which also keeps Phase 29's property that a
        // deadline is armed only while the deferral is still outstanding.
        //
        // The deadline is the SMALLER of the standing deadline and what is left of the caller's
        // wall. DEFERRAL_DEADLINE_MS keeps its value, which is deliberate: WR-04 is about which
        // clock the arming reads, never about how long the engine may take.
        job.deadlineMs = Math.min(DEFERRAL_DEADLINE_MS, remainingBudgetMs);

        // The non-deferred path keeps the old contract: this count reports how many notes were
        // ACCEPTED, never that they landed. It now says which contract it is speaking under, and
        // WHICH declining path it took -- the two reasons are told apart by the VALUE, never by
        // prose, the same explicit-marker idiom D-29-16 prescribes for the transaction case:
        //
        //   "not-deferrable"  the dispatcher would not let this request defer at all: it is a
        //                     notification, a batch element, or an operation inside
        //                     session/transaction (D-29-15).
        //   "queue-busy"      deferral was available and was DECLINED by the front door above,
        //                     because a write behind an unbounded chain cannot answer in time.
        //   "late"            deferral was available and the queue was free, and there was still
        //                     not enough of the caller's own wall left to promise an answer inside
        //                     it (WR-04). "queue-busy" wins when both hold: it is the more specific
        //                     fact and it is the one that was true first.
        //
        // Both keys are ALWAYS present, never omitted: JsonRpcDispatcher.java's serializer comment
        // states that a Python reader tests whether a value is None and never tests key
        // membership, so an absent key is not a way to say "no reason".
        JsonObject result = new JsonObject();
        result.addProperty("count", notes.size());
        result.addProperty("deferred", false);
        result.addProperty("deferReason",
            !deferrable ? "not-deferrable" : queueBusy ? "queue-busy" : "late");

        // THE ENQUEUE IS LAST, AND nothing below this line may throw -- because an error answer
        // returned while a queued job keeps writing is exactly the blind retry AGENTS.md forbids:
        // the caller is told the write failed, re-issues it, and gets a second copy of the notes
        // in their own music (29-REVIEW.md, IN-04). Every read, every coordinate, every validation
        // and the deadline are all above this line for that reason. A later edit that wants to put
        // a throwing call below it has to delete this sentence to do so.
        enqueueWrite(job);
        return result;
    }

    private JsonElement handleBuildSection(JsonObject params) throws Exception {
        String sceneName = requireString(params, "sceneName");
        JsonArray clips = requireArray(params, "clips");

        if (clips.isEmpty()) {
            throw new IllegalArgumentException("'clips' array must not be empty");
        }

        int slotIndex;
        int sceneIndexResult;

        if (params.has("sceneIndex") && !params.get("sceneIndex").isJsonNull()) {
            // Caller-provided scene index — skip scene creation, use directly as slot index.
            // Scene must already exist and be visible in the current scene bank window.
            slotIndex = params.get("sceneIndex").getAsInt();
            sceneIndexResult = slotIndex;

            // Rename the existing scene if it's within bank bounds
            JsonObject renameParams = new JsonObject();
            renameParams.addProperty("index", slotIndex);
            renameParams.addProperty("name", sceneName);
            dispatcher.handleInternal("scene/rename", renameParams);
        } else {
            // Auto-create scene — relies on stateCache.getSceneItemCount() being accurate.
            // WARNING: This path may fail after bulk scene deletion (see ISS-002).
            int sceneCountBefore = stateCache.getSceneItemCount();

            dispatcher.handleInternal("scene/create", new JsonObject());

            JsonObject scrollParams = new JsonObject();
            scrollParams.addProperty("amount", sceneCountBefore);
            dispatcher.handleInternal("sceneBank/scrollBy", scrollParams);

            int bankSize = SCENE_BANK_SIZE;
            int totalScenes = sceneCountBefore + 1;
            int scrollPosition = Math.max(0, totalScenes - bankSize);
            slotIndex = (totalScenes - 1) - scrollPosition;
            sceneIndexResult = sceneCountBefore;

            JsonObject renameParams = new JsonObject();
            renameParams.addProperty("index", slotIndex);
            renameParams.addProperty("name", sceneName);
            dispatcher.handleInternal("scene/rename", renameParams);
        }

        // Validate every clip up front, then queue the chain as ONE job. Validation stays here, in
        // the handler, so a malformed clip is still an INVALID_PARAMS error in the response rather
        // than a log line the caller never sees.
        java.util.List<ClipWrite> chain = new java.util.ArrayList<>();
        for (JsonElement clipEl : clips) {
            JsonObject clip = clipEl.getAsJsonObject();
            chain.add(new ClipWrite(
                requireInt(clip, "trackIndex"),
                slotIndex,
                requireInt(clip, "lengthBeats"),
                requireDouble(clip, "stepSize"),
                requireArray(clip, "notes"),
                clip.has("name") && !clip.get("name").isJsonNull()
                    ? clip.get("name").getAsString() : null));
        }

        // The chain is verified per clip and ABORTS on the first mismatch: clips after the failing
        // one are left untouched and reported as notWritten. The alternative -- carrying on -- is
        // exactly "something got destroyed without being asked", n-1 times over.
        enqueueWrite(new WriteJob("macro/buildSection", chain));

        JsonObject result = new JsonObject();
        result.addProperty("sceneIndex", sceneIndexResult);
        result.addProperty("clipCount", clips.size());
        return result;
    }

    private JsonElement handleSetupScenes(JsonObject params) throws Exception {
        JsonArray scenes = requireArray(params, "scenes");

        if (scenes.isEmpty()) {
            throw new IllegalArgumentException("'scenes' array must not be empty");
        }

        int createCount = 0;
        boolean shouldCreate = !params.has("createOnly") || params.get("createOnly").getAsBoolean();

        // Phase 1 (this flush cycle): create all scenes if requested
        if (shouldCreate) {
            for (int i = 0; i < scenes.size(); i++) {
                dispatcher.handleInternal("scene/create", new JsonObject());
                createCount++;
            }
        }

        // Phase 2+: rename each scene in a separate flush cycle
        for (int i = 0; i < scenes.size(); i++) {
            JsonObject scene = scenes.get(i).getAsJsonObject();
            int index = requireInt(scene, "index");
            String name = requireString(scene, "name");

            long delay = FLUSH_DELAY_MS * (i + 1);
            scheduler.schedule(() -> {
                try {
                    JsonObject renameParams = new JsonObject();
                    renameParams.addProperty("index", index);
                    renameParams.addProperty("name", name);
                    dispatcher.handleInternal("scene/rename", renameParams);
                } catch (Exception e) {
                    // Deferred rename failed
                }
            }, delay);
        }

        JsonObject result = new JsonObject();
        result.addProperty("created", createCount);
        result.addProperty("renamed", scenes.size());
        return result;
    }

    private JsonElement handleCreateSound(JsonObject params) throws Exception {
        JsonArray pages = requireArray(params, "pages");
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("pages array must not be empty");
        }

        // Validate all pages up front
        int totalParams = 0;
        for (JsonElement pageEl : pages) {
            JsonObject page = pageEl.getAsJsonObject();
            if (!page.has("pageIndex")) {
                throw new IllegalArgumentException("each page must have 'pageIndex'");
            }
            JsonArray pageParams = requireArray(page, "params");
            if (pageParams.isEmpty()) {
                throw new IllegalArgumentException("each page must have a non-empty 'params' array");
            }
            for (JsonElement paramEl : pageParams) {
                JsonObject p = paramEl.getAsJsonObject();
                if (!p.has("index") || !p.has("value")) {
                    throw new IllegalArgumentException("each param must have 'index' and 'value'");
                }
                int idx = p.get("index").getAsInt();
                if (idx < 0 || idx >= 8) {
                    throw new IllegalArgumentException("parameter index out of range: 0-7, got " + idx);
                }
                double val = p.get("value").getAsDouble();
                if (val < 0.0 || val > 1.0) {
                    throw new IllegalArgumentException("parameter value out of range: 0.0-1.0, got " + val);
                }
                totalParams++;
            }
        }

        boolean inserted = false;
        String deviceName = "current";
        boolean hasDevice = params.has("device") && !params.get("device").isJsonNull();
        boolean hasPlugin = params.has("plugin") && !params.get("plugin").isJsonNull();

        if (hasDevice && hasPlugin) {
            throw new IllegalArgumentException("cannot specify both 'device' and 'plugin'");
        }

        if (hasDevice) {
            deviceName = params.get("device").getAsString();
            String position = params.has("position") && !params.get("position").isJsonNull()
                ? params.get("position").getAsString() : "end";

            JsonObject deviceParams = new JsonObject();
            deviceParams.addProperty("name", deviceName);
            deviceParams.addProperty("position", position);
            dispatcher.handleInternal("device/insertBitwigDevice", deviceParams);
            inserted = true;
        } else if (hasPlugin) {
            JsonObject plugin = params.getAsJsonObject("plugin");
            deviceName = plugin.get("type").getAsString() + ":" + plugin.get("id").getAsString();
            insertPlugin(plugin);
            inserted = true;
        }

        if (inserted) {
            // Phase 2: set parameters after flush (device needs to initialize)
            JsonObject setParamsPayload = new JsonObject();
            setParamsPayload.add("pages", pages);
            scheduler.schedule(() -> {
                try {
                    dispatcher.handleInternal("device/setParameters", setParamsPayload);
                } catch (Exception e) {
                    // Deferred parameter set failed
                }
            }, FLUSH_DELAY_MS);
        } else {
            // No device insertion — set parameters immediately
            JsonObject setParamsPayload = new JsonObject();
            setParamsPayload.add("pages", pages);
            dispatcher.handleInternal("device/setParameters", setParamsPayload);
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("device", deviceName);
        result.addProperty("pageCount", pages.size());
        result.addProperty("paramCount", totalParams);
        result.addProperty("inserted", inserted);
        return result;
    }

    private JsonElement handleBuildSong(JsonObject params) throws Exception {
        JsonArray tracks = requireArray(params, "tracks");
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("'tracks' array must not be empty");
        }

        // Calculate per-track delay: each track needs time for device init + page writes
        // Track with pages: FLUSH_DELAY_MS * (1 + 2 * pageCount)
        // Track with device only: FLUSH_DELAY_MS
        // Track without device: 0
        long cumulativeDelay = 0;

        for (int i = 0; i < tracks.size(); i++) {
            JsonObject track = tracks.get(i).getAsJsonObject();
            final long trackDelay = cumulativeDelay;

            // Build createTrack params
            JsonObject createTrackParams = new JsonObject();
            createTrackParams.addProperty("type", requireString(track, "type"));
            if (track.has("name") && !track.get("name").isJsonNull()) {
                createTrackParams.addProperty("name", track.get("name").getAsString());
            }
            if (track.has("device") && !track.get("device").isJsonNull()) {
                createTrackParams.addProperty("device", track.get("device").getAsString());
            }
            if (track.has("plugin") && !track.get("plugin").isJsonNull()) {
                createTrackParams.add("plugin", track.getAsJsonObject("plugin"));
            }
            if (track.has("color") && !track.get("color").isJsonNull()) {
                createTrackParams.add("color", track.getAsJsonObject("color"));
            }
            if (track.has("pages") && !track.get("pages").isJsonNull()) {
                createTrackParams.add("pages", track.getAsJsonArray("pages"));
            }

            if (trackDelay == 0) {
                dispatcher.handleInternal("macro/createTrack", createTrackParams);
            } else {
                scheduler.schedule(() -> {
                    try {
                        dispatcher.handleInternal("macro/createTrack", createTrackParams);
                    } catch (Exception e) {
                        // Deferred track creation failed
                    }
                }, trackDelay);
            }

            // Calculate delay this track needs before next track can start
            boolean hasDevice = track.has("device") && !track.get("device").isJsonNull();
            boolean hasPlugin = track.has("plugin") && !track.get("plugin").isJsonNull();
            boolean hasPages = track.has("pages") && !track.get("pages").isJsonNull();
            boolean hasAnyDevice = hasDevice || hasPlugin;

            if (hasPages) {
                int pageCount = track.getAsJsonArray("pages").size();
                cumulativeDelay += FLUSH_DELAY_MS * (1 + 2 * pageCount);
            } else if (hasAnyDevice) {
                cumulativeDelay += FLUSH_DELAY_MS;
            }
        }

        // Schedule sections after all tracks are created
        if (params.has("sections") && !params.get("sections").isJsonNull()) {
            JsonArray sections = params.getAsJsonArray("sections");

            for (int i = 0; i < sections.size(); i++) {
                JsonObject section = sections.get(i).getAsJsonObject();
                final long sectionDelay = cumulativeDelay;

                // Build buildSection params
                JsonObject sectionParams = new JsonObject();
                sectionParams.addProperty("sceneName", requireString(section, "sceneName"));
                sectionParams.add("clips", requireArray(section, "clips"));
                if (section.has("sceneIndex") && !section.get("sceneIndex").isJsonNull()) {
                    sectionParams.addProperty("sceneIndex", section.get("sceneIndex").getAsInt());
                }

                scheduler.schedule(() -> {
                    try {
                        dispatcher.handleInternal("macro/buildSection", sectionParams);
                    } catch (Exception e) {
                        // Deferred section building failed
                    }
                }, sectionDelay);

                // Calculate delay for next section: 1 flush for scene setup + 2 per clip
                int clipCount = section.getAsJsonArray("clips").size();
                cumulativeDelay += FLUSH_DELAY_MS * (1 + 2 * clipCount);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("trackCount", tracks.size());
        result.addProperty("sectionCount",
            params.has("sections") && !params.get("sections").isJsonNull()
                ? params.getAsJsonArray("sections").size() : 0);
        return result;
    }

    private JsonElement handleWriteAutomation(JsonObject params) throws Exception {
        JsonArray envelopes = requireArray(params, "envelopes");
        if (envelopes.isEmpty()) {
            throw new IllegalArgumentException("'envelopes' array must not be empty");
        }

        // Validate all envelopes up front and group by pageIndex
        // null page = current page, grouped separately at the end
        java.util.Map<Integer, java.util.List<JsonObject>> byPage = new java.util.LinkedHashMap<>();
        java.util.List<JsonObject> currentPageEnvelopes = new java.util.ArrayList<>();
        int totalPoints = 0;

        for (JsonElement el : envelopes) {
            JsonObject env = el.getAsJsonObject();
            int paramIndex = requireInt(env, "paramIndex");
            if (paramIndex < 0 || paramIndex >= 8) {
                throw new IllegalArgumentException("paramIndex out of range: 0-7, got " + paramIndex);
            }
            JsonArray points = requireArray(env, "points");
            if (points.isEmpty()) {
                throw new IllegalArgumentException("points array must not be empty for paramIndex " + paramIndex);
            }
            for (JsonElement ptEl : points) {
                JsonObject pt = ptEl.getAsJsonObject();
                if (!pt.has("position") || !pt.has("value")) {
                    throw new IllegalArgumentException("each point must have 'position' and 'value'");
                }
                double position = pt.get("position").getAsDouble();
                if (position < 0) {
                    throw new IllegalArgumentException("position must be >= 0, got: " + position);
                }
                double value = pt.get("value").getAsDouble();
                if (value < 0.0 || value > 1.0) {
                    throw new IllegalArgumentException("value out of range: 0.0-1.0, got " + value);
                }
            }
            totalPoints += points.size();

            if (env.has("pageIndex") && !env.get("pageIndex").isJsonNull()) {
                int pageIndex = env.get("pageIndex").getAsInt();
                byPage.computeIfAbsent(pageIndex, k -> new java.util.ArrayList<>()).add(env);
            } else {
                currentPageEnvelopes.add(env);
            }
        }

        int envelopeCount = envelopes.size();

        // Enable arranger automation write
        JsonObject enableParams = new JsonObject();
        enableParams.addProperty("enabled", true);
        dispatcher.handleInternal("transport/setArrangerAutomationWrite", enableParams);

        // Schedule envelope writes: page groups first, then current-page envelopes
        long cumulativeDelay = FLUSH_DELAY_MS; // initial delay for automation write to take effect

        for (var entry : byPage.entrySet()) {
            int pageIndex = entry.getKey();
            java.util.List<JsonObject> pageEnvelopes = entry.getValue();

            // Switch page
            final long pageDelay = cumulativeDelay;
            scheduler.schedule(() -> {
                try {
                    JsonObject pageParams = new JsonObject();
                    pageParams.addProperty("index", pageIndex);
                    dispatcher.handleInternal("device/selectPage", pageParams);
                } catch (Exception e) {
                    // Deferred page switch failed
                }
            }, pageDelay);
            cumulativeDelay += FLUSH_DELAY_MS;

            // Write each envelope in this page group
            for (JsonObject env : pageEnvelopes) {
                final long envDelay = cumulativeDelay;
                int pointCount = env.getAsJsonArray("points").size();
                scheduler.schedule(() -> {
                    try {
                        JsonObject writeParams = new JsonObject();
                        writeParams.addProperty("index", env.get("paramIndex").getAsInt());
                        writeParams.add("points", env.getAsJsonArray("points"));
                        dispatcher.handleInternal("device/writeEnvelope", writeParams);
                    } catch (Exception e) {
                        // Deferred envelope write failed
                    }
                }, envDelay);
                cumulativeDelay += 100L * (pointCount + 2);
            }
        }

        // Current-page envelopes (no page switch needed)
        for (JsonObject env : currentPageEnvelopes) {
            final long envDelay = cumulativeDelay;
            int pointCount = env.getAsJsonArray("points").size();
            scheduler.schedule(() -> {
                try {
                    JsonObject writeParams = new JsonObject();
                    writeParams.addProperty("index", env.get("paramIndex").getAsInt());
                    writeParams.add("points", env.getAsJsonArray("points"));
                    dispatcher.handleInternal("device/writeEnvelope", writeParams);
                } catch (Exception e) {
                    // Deferred envelope write failed
                }
            }, envDelay);
            cumulativeDelay += 100L * (pointCount + 2);
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("envelopeCount", envelopeCount);
        result.addProperty("totalPoints", totalPoints);
        return result;
    }

    // --- Internal helpers ---

    private void insertPlugin(JsonObject plugin) throws Exception {
        JsonObject pluginParams = new JsonObject();
        pluginParams.addProperty("type", plugin.get("type").getAsString());
        pluginParams.addProperty("id", plugin.get("id").getAsString());
        dispatcher.handleInternal("device/insertPluginDevice", pluginParams);
    }

    private void writeNotesToCursor(double stepSize, JsonArray notes, String name) throws Exception {
        JsonObject stepSizeParams = new JsonObject();
        stepSizeParams.addProperty("size", stepSize);
        dispatcher.handleInternal("clip/setStepSize", stepSizeParams);

        JsonObject noteParams = new JsonObject();
        noteParams.add("notes", notes);
        dispatcher.handleInternal("clip/setNotes", noteParams);

        if (name != null) {
            renameThroughCursor(name);
        }

        // Expressions are NOT applied from here any more. They are a third cursor-scoped hop one
        // flush later, so the job driver schedules them itself and re-verifies the cursor first --
        // expressions landing on the slot after the one their notes went to is the same wrong-slot
        // bug one layer down, and it used to happen on every buildSection chain because the next
        // clip was selected in the same task that scheduled them.
    }

    /**
     * Refuse, SYNCHRONOUSLY and before anything is promised or queued, any note whose expression
     * fields {@link #collectNoteExpressions} would later dereference blindly.
     *
     * <p>WHY THIS EXISTS AND WHY IT IS HERE RATHER THAN IN THE TOOL LAYER (29-REVIEW.md, WR-05).
     * The collection runs deep inside the job, on a scheduled task, and does raw Gson reads:
     * {@code repeat.get("curve").getAsDouble()} is a NullPointerException when {@code curve} is
     * absent, {@code getAsJsonObject("expressions")} is an IllegalStateException when the member
     * is not an object, and {@code note.get("chance").getAsDouble()} is a NumberFormatException on
     * a string. Before plan 31-06 that throw reached no terminal path at all: {@code finishJob}
     * never ran, {@code writeInProgress} stayed true for the rest of the session, every later
     * {@code macro/writeClip} answered {@code queue-busy} at the front door and joined a queue
     * that never drained again, and the caller that triggered it was told three seconds later that
     * its write MAY still have landed -- when in fact the notes had landed and the write path was
     * dead.
     *
     * <p>{@code bitwig_call} reaches {@code macro/writeClip} with NO product-side validation in
     * front of it. That is why the check belongs at this end of the wire: a rule that only the
     * Python tool layer enforces is a rule the escape hatch does not have.
     *
     * <p>ONE FIELD OF ONE NOTE is what the refusal names, and the wording of the absent case is
     * {@link JsonParamValidator#missingMessage(String)} -- the project's existing spelling --
     * rather than a second one invented here.
     *
     * @throws IllegalArgumentException which {@code JsonRpcDispatcher} turns into a -32602
     */
    static void validateExpressionFields(JsonArray notes) {
        for (int i = 0; i < notes.size(); i++) {
            JsonElement el = notes.get(i);
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException(
                    noteRef(i) + "each note must be an object, got " + el);
            }
            JsonObject note = el.getAsJsonObject();

            // x and y are read for EVERY note, unconditionally, before any expression is even
            // looked for -- so they are part of this surface whether the note carries expressions
            // or not.
            requireNoteNumber(note, "x", "x", i);
            requireNoteNumber(note, "y", "y", i);

            if (isPresent(note, "chance")) {
                requireNoteNumber(note, "chance", "chance", i);
            }

            if (isPresent(note, "expressions")) {
                JsonObject expressions = requireNoteObject(note, "expressions", "expressions", i);
                for (String property : expressions.keySet()) {
                    requireNoteNumber(expressions, property, "expressions." + property, i);
                }
            }

            if (isPresent(note, "repeat")) {
                JsonObject repeat = requireNoteObject(note, "repeat", "repeat", i);
                requireNoteNumber(repeat, "count", "repeat.count", i);
                requireNoteNumber(repeat, "curve", "repeat.curve", i);
                requireNoteNumber(repeat, "velocityEnd", "repeat.velocityEnd", i);
                requireNoteNumber(repeat, "velocityCurve", "repeat.velocityCurve", i);
            }

            if (isPresent(note, "occurrence")) {
                JsonElement occurrence = note.get("occurrence");
                if (!occurrence.isJsonPrimitive() || !occurrence.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(noteRef(i)
                        + "'occurrence' must be a string, got " + occurrence);
                }
            }

            if (isPresent(note, "recurrence")) {
                JsonObject recurrence = requireNoteObject(note, "recurrence", "recurrence", i);
                requireNoteNumber(recurrence, "length", "recurrence.length", i);
                requireNoteNumber(recurrence, "mask", "recurrence.mask", i);
            }
        }
    }

    /** Present and not an explicit null -- the same test the collection itself applies. */
    private static boolean isPresent(JsonObject note, String key) {
        return note.has(key) && !note.get(key).isJsonNull();
    }

    /** Which note the caller has to look at. Zero-based, as the caller's own array is. */
    private static String noteRef(int noteIndex) {
        return "note " + noteIndex + ": ";
    }

    private static void requireNoteNumber(JsonObject owner, String key, String label,
                                          int noteIndex) {
        JsonElement el = owner.get(key);
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException(
                noteRef(noteIndex) + JsonParamValidator.missingMessage(label));
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                noteRef(noteIndex) + "'" + label + "' must be a number, got " + el);
        }
    }

    private static JsonObject requireNoteObject(JsonObject owner, String key, String label,
                                                int noteIndex) {
        JsonElement el = owner.get(key);
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException(
                noteRef(noteIndex) + JsonParamValidator.missingMessage(label));
        }
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException(
                noteRef(noteIndex) + "'" + label + "' must be an object, got " + el);
        }
        return el.getAsJsonObject();
    }

    private ExpressionWork collectNoteExpressions(JsonArray notes) {
        // Collect expression data from notes
        JsonArray chanceNotes = new JsonArray();
        JsonArray repeatNotes = new JsonArray();
        JsonArray occurrenceNotes = new JsonArray();
        JsonArray recurrenceNotes = new JsonArray();
        // Map: property name → array of {x, y, property, value}
        java.util.Map<String, JsonArray> expressionsByProperty = new java.util.LinkedHashMap<>();

        for (JsonElement el : notes) {
            JsonObject note = el.getAsJsonObject();
            int x = note.get("x").getAsInt();
            int y = note.get("y").getAsInt();

            if (note.has("chance") && !note.get("chance").isJsonNull()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("x", x);
                entry.addProperty("y", y);
                entry.addProperty("chance", note.get("chance").getAsDouble());
                chanceNotes.add(entry);
            }

            if (note.has("expressions") && !note.get("expressions").isJsonNull()) {
                JsonObject expr = note.getAsJsonObject("expressions");
                for (String prop : expr.keySet()) {
                    expressionsByProperty.computeIfAbsent(prop, k -> new JsonArray());
                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", x);
                    entry.addProperty("y", y);
                    entry.addProperty("property", prop);
                    entry.addProperty("value", expr.get(prop).getAsDouble());
                    expressionsByProperty.get(prop).add(entry);
                }
            }

            if (note.has("repeat") && !note.get("repeat").isJsonNull()) {
                JsonObject repeat = note.getAsJsonObject("repeat");
                JsonObject entry = new JsonObject();
                entry.addProperty("x", x);
                entry.addProperty("y", y);
                entry.addProperty("count", repeat.get("count").getAsInt());
                entry.addProperty("curve", repeat.get("curve").getAsDouble());
                entry.addProperty("velocityEnd", repeat.get("velocityEnd").getAsDouble());
                entry.addProperty("velocityCurve", repeat.get("velocityCurve").getAsDouble());
                repeatNotes.add(entry);
            }

            if (note.has("occurrence") && !note.get("occurrence").isJsonNull()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("x", x);
                entry.addProperty("y", y);
                entry.addProperty("condition", note.get("occurrence").getAsString());
                occurrenceNotes.add(entry);
            }

            if (note.has("recurrence") && !note.get("recurrence").isJsonNull()) {
                JsonObject recurrence = note.getAsJsonObject("recurrence");
                JsonObject entry = new JsonObject();
                entry.addProperty("x", x);
                entry.addProperty("y", y);
                entry.addProperty("length", recurrence.get("length").getAsInt());
                entry.addProperty("mask", recurrence.get("mask").getAsInt());
                recurrenceNotes.add(entry);
            }
        }

        boolean hasExpressions = chanceNotes.size() > 0
            || !expressionsByProperty.isEmpty()
            || repeatNotes.size() > 0
            || occurrenceNotes.size() > 0
            || recurrenceNotes.size() > 0;

        // Null means "nothing to do", which the driver distinguishes from "a hop that must be
        // verified": a note-only clip finishes without spending a flush cycle it does not need.
        if (!hasExpressions) return null;

        return new ExpressionWork(chanceNotes, expressionsByProperty,
            repeatNotes, occurrenceNotes, recurrenceNotes);
    }

    private void applyNoteExpressions(ExpressionWork work) throws Exception {
        if (work.chanceNotes.size() > 0) {
            JsonObject p = new JsonObject();
            p.add("notes", work.chanceNotes);
            dispatcher.handleInternal("clip/setChance", p);
        }
        for (JsonArray exprNotes : work.expressionsByProperty.values()) {
            JsonObject p = new JsonObject();
            p.add("notes", exprNotes);
            dispatcher.handleInternal("clip/setNoteExpressions", p);
        }
        if (work.repeatNotes.size() > 0) {
            JsonObject p = new JsonObject();
            p.add("notes", work.repeatNotes);
            dispatcher.handleInternal("clip/setNoteRepeat", p);
        }
        if (work.occurrenceNotes.size() > 0) {
            JsonObject p = new JsonObject();
            p.add("notes", work.occurrenceNotes);
            dispatcher.handleInternal("clip/setNoteOccurrence", p);
        }
        if (work.recurrenceNotes.size() > 0) {
            JsonObject p = new JsonObject();
            p.add("notes", work.recurrenceNotes);
            dispatcher.handleInternal("clip/setNoteRecurrence", p);
        }
    }

    // --- The verified, serialised launcher write ---

    /** One clip's worth of write instructions, with the absolute slot it is FOR. */
    private static final class ClipWrite {
        final int trackIndex;
        final int sceneIndex;
        final int lengthBeats;
        final double stepSize;
        final JsonArray notes;
        final String name;

        /**
         * What the cache SAID about this slot immediately before phase 1 created a clip in it.
         *
         * <p>Three states, and the third is the reason this is a {@code Boolean} and not a
         * {@code boolean}: {@code TRUE} means in range, observed, and observed empty;
         * {@code FALSE} means observed and holding content; {@code null} means UNPROVEN -- the
         * coordinate could not be resolved, it is out of range, or no has-content observer has
         * ever fired for it. An unprovable answer to "was this slot empty?" resolves to leaving
         * the clip and saying so, never to deleting on a guess, because the thing on the other
         * side of a wrong guess is material the owner made (D-29-10, D-29-11).
         *
         * <p>Recorded BEFORE {@code createClip}, which is the only moment it can be read: one
         * flush later the slot holds the clip this job just made, and the observation is gone.
         *
         * <p>It is a READING and not yet a proof, which is why it is no longer what the undo
         * consults. {@link #slotWasEmpty} is the proof, and {@link
         * MacroHandler#settleSlotEvidence} is where this reading either becomes one or is
         * withdrawn (29-REVIEW.md, WR-01).
         */
        Boolean slotReadBeforeCreate;

        /**
         * Was this slot PROVEN empty immediately before phase 1 created a clip in it?
         *
         * <p>The same three states as {@link #slotReadBeforeCreate} and the same discipline --
         * only {@code TRUE} authorises the undo -- but this one is settled ONE FLUSH OR MORE
         * LATER, when the freshness of the reading can be tested rather than assumed. See
         * {@link MacroHandler#settleSlotEvidence} for the four facts it conjoins and for why a
         * reading taken before the create cannot be conjoined with its own freshness at the
         * moment it is taken.
         */
        Boolean slotWasEmpty;

        /**
         * The observation sequence the emptiness reading was actually taken at, or {@code 0} when
         * this slot has never been observed.
         *
         * <p>Published to the caller as {@code slotObservedAt}, so a refusal can say how fresh
         * its own evidence was rather than asserting a freshness it cannot show. Zero is an
         * absence and travels as an explicit JSON null, like every other absence in this payload.
         */
        long emptinessObservationSeq;

        /**
         * Has {@link MacroHandler#settleSlotEvidence} already run for this clip?
         *
         * <p>The settle is idempotent because the terminal paths do not all know about each
         * other: a deadline task that fires after a refusal must not re-derive an answer the
         * refusal already published, or the two copies of one fact could disagree.
         */
        boolean evidenceSettled;

        /**
         * The PHYSICAL bank slot {@link #trackIndex} resolves to, or {@code -1} when it could not
         * be resolved.
         *
         * <p>Resolved ONCE, at the top of phase 1, and carried: every read of the cache's clip
         * arrays on this path is subscripted with this rather than with the caller's public index,
         * because those arrays are filled from the canonical FLAT bank and the two coordinates
         * differ the moment a group is collapsed or the bank is scrolled (29-REVIEW.md, WR-02).
         * {@code -1} is unproven, never slot zero, and everything reading it treats it that way.
         */
        int bankSlot = -1;

        /**
         * The unique name written through the cursor to prove, before any note is dispatched, that
         * the cursor is holding the clip in the slot this write named. Null until phase 2 stamps.
         */
        String stampToken;

        /**
         * {@code StateCache#currentObservationTick()} as it stood at the instant of the stamp.
         *
         * <p>The echo is accepted only on an observation STRICTLY NEWER than this. That is the
         * load-bearing half: an observation that is merely PRESENT proves nothing -- the slot's
         * name has been published since startup -- while one that arrived after the stamp is the
         * only thing that can carry the stamp's own token.
         */
        long stampObservationSeq;

        /**
         * What every slot was called immediately before the stamp, subscripted by PHYSICAL bank
         * slot.
         *
         * <p>The stamp mutates a clip whose identity is not yet proven, so the name it overwrites
         * has to be recoverable. The copy is 256 interned string references taken on the session
         * thread -- a shallow array copy, and the cost is stated here so a later reader does not
         * have to re-derive it.
         */
        String[][] priorNames;

        /**
         * Was the stamp's own rename PROVEN to have been put back? {@code TRUE} or null, never
         * false.
         *
         * <p>Null is the absence, and it is deliberately not a false: a restore that was attempted
         * and could not be proven, and a restore that was never attempted at all, are different
         * facts. The pair with {@link #stampLeftAt} is what tells them apart -- see
         * {@link MacroHandler#restoreStampedName}.
         */
        Boolean stampRestored;

        /**
         * Where the stamp was found when the proof failed, or null when no slot carried the token.
         *
         * <p>Spelled in the same textual form the landed payload uses, {@code t<n>s<n>}, with one
         * difference a reader must not guess at: its FIRST number is a PHYSICAL BANK SLOT, not a
         * public track index, because the arrays the token was located in are keyed that way.
         * Saying which coordinate space it is in costs a sentence; translating it could be wrong.
         */
        String stampLeftAt;

        /**
         * Was {@code clip/create} DISPATCHED for this clip without throwing?
         *
         * <p>A fact about this engine's own call, and nothing more. It is deliberately not the
         * answer to "did a clip appear?", because at API v25 those are different questions: a
         * create over an occupied slot is acknowledged and changes nothing (measured at
         * 29-LIVE-ACCEPTANCE.md section 3, live step 5). {@link #created} is the answer to the
         * other question, and this is one of its inputs.
         */
        boolean createDispatched;

        /**
         * Did phase 1 actually create a clip at this slot?
         *
         * <p>Reported to the caller as {@code clipCreated}, and THREE-STATE since plan 31-05,
         * which is why it is a {@code Boolean} and not a {@code boolean}:
         *
         * <ul>
         *   <li>{@code TRUE} -- the slot was PROVEN empty before the create and the create was
         *       dispatched, so a clip appeared where there was none.</li>
         *   <li>{@code FALSE} -- either {@code clip/create} threw before reaching this clip, or
         *       the slot was proven to be holding content, in which case v25 treated the create
         *       as a no-op and nothing was made.</li>
         *   <li>{@code null} -- UNPROVEN. The slot's emptiness could not be proven, so whether
         *       the dispatch created anything cannot be either. It is published as an absence
         *       and never as a false: "no clip was created" and "nobody can say" are different
         *       facts, and collapsing the second into the first is the same misreport this field
         *       was corrected to stop (29-REVIEW.md, IN-05).</li>
         * </ul>
         *
         * <p>Derived in {@link MacroHandler#settleSlotEvidence} from {@link #slotWasEmpty} -- the
         * measured answer -- rather than from the dispatch alone. Before plan 31-05 it was set
         * {@code true} on any successful dispatch, so a refusal aimed at an occupied slot
         * reported {@code clipCreated: true} for a create Bitwig had ignored.
         */
        Boolean created;

        ClipWrite(int trackIndex, int sceneIndex, int lengthBeats, double stepSize,
                  JsonArray notes, String name) {
            this.trackIndex = trackIndex;
            this.sceneIndex = sceneIndex;
            this.lengthBeats = lengthBeats;
            this.stepSize = stepSize;
            this.notes = notes;
            this.name = name;
        }

        String label() {
            return "t" + trackIndex + "s" + sceneIndex;
        }

        /** What the slot this write NAMED was called immediately before the stamp, or null. */
        String priorNameAtNamedSlot() {
            if (priorNames == null || bankSlot < 0 || bankSlot >= priorNames.length
                || sceneIndex < 0 || sceneIndex >= priorNames[bankSlot].length) {
                return null;
            }
            return priorNames[bankSlot][sceneIndex];
        }
    }

    /** One queued write: a single clip for {@code macro/writeClip}, a chain for {@code buildSection}. */
    private static final class WriteJob {
        final String method;
        final List<ClipWrite> clips;
        final JsonArray landed = new JsonArray();

        /**
         * The caller's claimed response, or null when this job answers the old way.
         *
         * <p>Null for every chain macro (D-29-05) and for any {@code macro/writeClip} the
         * dispatcher declined to let defer -- a notification, a batch element, or an operation
         * inside {@code session/transaction}. {@link MacroHandler#completePending} no-ops on
         * null, so a chain job silently keeps its pre-Phase-29 behaviour at every call site.
         */
        final PendingResponse pending;

        int index;

        /**
         * {@code StateCache#currentObservationTick()} as it stood at the top of phase 1, before
         * this job created anything.
         *
         * <p>It dates the job. An observation numbered at or below it landed BEFORE this job
         * existed and therefore describes the slot as it was before -- which is not a statement
         * about the slot this job then created into. An observation numbered above it landed
         * after the job acted, and is the only kind that can carry the effect of the job's own
         * {@code clip/create}. {@link MacroHandler#settleSlotEvidence} is the one reader.
         *
         * <p>Recorded once per job rather than once per clip because phase 1 creates every clip
         * of a chain in one uninterrupted pass: there is exactly one instant at which none of
         * them had been created yet, and that instant is this one.
         */
        long startObservationTick;

        /**
         * The deadline this job's claimed response is bounded by, in milliseconds, as DECIDED by
         * the handler on the caller's own clock.
         *
         * <p>DECIDED IN THE HANDLER, ARMED BY THE DRIVER, and the split is the point (plan 31-06).
         * Only the handler can compute it: {@code JsonRpcDispatcher#remainingBudgetMs()} is a
         * reading about the request now in a handler, and it is meaningless once the handler has
         * returned. But the handler must not ARM it, because arming is the last thing that could
         * throw after {@code enqueueWrite} -- and an error answer returned while a queued job
         * keeps writing is the blind retry this project forbids (29-REVIEW.md, IN-04). So the
         * figure rides here and {@link MacroHandler#startNextJob} arms it as the job starts.
         *
         * <p>Meaningless when {@link #pending} is null; {@link MacroHandler#armDeadline} no-ops
         * there, exactly as {@code completePending} does.
         */
        long deadlineMs = DEFERRAL_DEADLINE_MS;

        WriteJob(String method, List<ClipWrite> clips) {
            this(method, clips, null);
        }

        WriteJob(String method, List<ClipWrite> clips, PendingResponse pending) {
            this.method = method;
            this.clips = clips;
            this.pending = pending;
        }
    }

    /** Expression calls collected from a clip's notes, ready to dispatch one flush later. */
    private static final class ExpressionWork {
        final JsonArray chanceNotes;
        final java.util.Map<String, JsonArray> expressionsByProperty;
        final JsonArray repeatNotes;
        final JsonArray occurrenceNotes;
        final JsonArray recurrenceNotes;

        ExpressionWork(JsonArray chanceNotes, java.util.Map<String, JsonArray> expressionsByProperty,
                       JsonArray repeatNotes, JsonArray occurrenceNotes, JsonArray recurrenceNotes) {
            this.chanceNotes = chanceNotes;
            this.expressionsByProperty = expressionsByProperty;
            this.repeatNotes = repeatNotes;
            this.occurrenceNotes = occurrenceNotes;
            this.recurrenceNotes = recurrenceNotes;
        }
    }

    private void enqueueWrite(WriteJob job) {
        writeQueue.add(job);
        if (!writeInProgress) {
            startNextJob();
        }
    }

    /**
     * Arm the deadline that bounds a deferred response. No-op when this job never claimed one, or
     * when it has already been answered.
     *
     * <p>WHY THE TASK IS SELF-EXPIRING AND READS THE COMPLETION FLAG. The scheduled task checks
     * {@code PendingResponse#isCompleted()} at the moment it runs, rather than consulting a
     * boolean gate that only it can clear. A gate of that shape is the latching failure recorded
     * at {@code ParkedRemoteControls.java:154-156}: once set it stays set, and what it guards is
     * off for the rest of the session. Here the equivalent mistake would be worse -- it would
     * answer a caller that had already been answered -- which is also why
     * {@code PendingResponse}'s completion is one-shot on the other side. Two independent reasons
     * a late deadline cannot overwrite a real answer, not one.
     *
     * <p>WHY IT IS CALLED FROM {@code startNextJob} RATHER THAN FROM THE HANDLER, since plan
     * 31-06 (29-REVIEW.md, IN-04). The handler must end with its {@code enqueueWrite}: anything
     * after it is a statement that can throw while a queued job is already writing, and the
     * dispatcher would then answer the caller an error for a write still in flight -- the blind
     * retry this project forbids. Arming is a scheduler call, so it moved INTO the job driver
     * rather than merely moving above the enqueue.
     *
     * <p>WHICH KEEPS BOTH PHASE 29 PROPERTIES INTACT, and that is why it moved there rather than
     * simply earlier. It still runs after {@code startNextJob}'s own phase-1 catch, so a job that
     * failed and answered inside the handler's call stack still arms nothing; and it still runs
     * after the verify hop is scheduled, so a scheduler that collapses the flush window to zero
     * -- {@code MacroHandlerTest}'s {@code IMMEDIATE_SCHEDULER} does exactly that -- runs the
     * whole job to its real answer first and finds this a no-op. Arming from the handler ahead of
     * the enqueue would have inverted that order and answered {@code WRITE_UNRESOLVED} for a write
     * that had not begun.
     *
     * <p>WHICH CLOCK IT IS ARMED AGAINST, since plan 31-06 (29-REVIEW.md, WR-04). The caller
     * passes the deadline in rather than this method reading {@link #DEFERRAL_DEADLINE_MS}, because
     * the honest bound is the SMALLER of that constant and whatever is left of the caller's own
     * five-second wall -- and only the handler, holding the dispatcher's remaining-budget reading,
     * can know the second number. The constant did not move; what moved is which clock it is
     * compared against. The figure that is armed is also the figure the unresolved answer reports,
     * so a caller answered early is told the shorter deadline it was actually measured against.
     *
     * <p>No console marker is written here, deliberately. The two markers this class declares are
     * a forensic trail for a refusal the caller could not see; this outcome IS the answer the
     * caller receives.
     */
    private void armDeadline(WriteJob job, long deadlineMs) {
        if (job.pending == null || job.pending.isCompleted()) {
            return;
        }
        scheduler.schedule(() -> {
            if (job.pending.isCompleted()) {
                return;
            }
            // Whichever clip the job had reached. For macro/writeClip that is always the only
            // one; the clamp is there because a chain job never claims a response at all and a
            // finished index would otherwise be out of range.
            ClipWrite clip = job.clips.get(Math.min(job.index, job.clips.size() - 1));
            settleSlotEvidence(job, clip);

            // Every key is ALWAYS present, JSON null where unobserved -- the explicit-null reader
            // rule at JsonRpcDispatcher.java:55-57. clipCreated and clipRemoved were DECLARED as
            // explicit nulls by plan 29-02 and are POPULATED here from plan 29-03: phase 1 records
            // whether it created the clip, and this path removes nothing, so both facts are now
            // observed rather than unobservable. Leaving them null would say "the engine did not
            // observe this" about something the engine now does observe -- which is the same class
            // of false claim this plan exists to remove from the refusal.
            JsonObject data = new JsonObject();
            // The deadline ACTUALLY ARMED, not the standing constant. The two are the same
            // whenever the request reached this handler promptly, and they differ exactly when
            // the caller's wall was already partly spent -- which is the case the caller most
            // needs told, and the one a hardcoded constant would have misreported (WR-04).
            data.addProperty("deadlineMs", deadlineMs);
            data.addProperty("requestedTrack", clip.trackIndex);
            data.addProperty("requestedScene", clip.sceneIndex);
            data.addProperty("noteCount", clip.notes.size());
            addCreated(data, clip);
            data.addProperty("clipRemoved", false);

            job.pending.completeError(
                JsonRpcError.WRITE_UNRESOLVED, WRITE_UNRESOLVED_MESSAGE, data);
        }, deadlineMs);
    }

    /**
     * Answer a claimed response with a SUCCESS. No-op when this job never claimed one.
     *
     * <p>Every terminal path calls one of these two overloads BEFORE its {@code finishJob(job)},
     * because {@code finishJob} does not know the outcome -- it only releases the queue.
     */
    private void completePending(WriteJob job, JsonElement result) {
        if (job.pending == null) {
            return;
        }
        job.pending.completeSuccess(result);
    }

    /** Answer a claimed response with an ERROR. No-op when this job never claimed one. */
    private void completePending(WriteJob job, int code, String message, JsonObject data) {
        if (job.pending == null) {
            return;
        }
        job.pending.completeError(code, message, data);
    }

    /**
     * What landed, as the deferred success payload.
     *
     * <p>{@code count} is the note total actually written, so the deferred answer keeps the shape
     * the pre-Phase-29 acknowledgement had, and {@code landed} carries the per-clip detail the
     * job has been accumulating all along. {@code deferReason} is an explicit JSON null rather
     * than an omitted key -- see the reader rule in {@code JsonRpcDispatcher}'s serializer
     * comment.
     */
    private static JsonObject landedPayload(WriteJob job) {
        int count = 0;
        for (JsonElement el : job.landed) {
            count += el.getAsJsonObject().get("notes").getAsInt();
        }
        JsonObject result = new JsonObject();
        result.addProperty("count", count);
        result.add("landed", job.landed);
        result.addProperty("deferred", true);
        result.add("deferReason", JsonNull.INSTANCE);
        return result;
    }

    private void startNextJob() {
        WriteJob job = writeQueue.poll();
        if (job == null) {
            writeInProgress = false;
            return;
        }
        writeInProgress = true;

        // Phase 1: create every clip of the job, then move the cursor to the first one. Both are
        // cursor-affecting, so both belong inside the serialised job rather than in the handler.
        //
        // The instant BEFORE any of it is what dates every emptiness answer this job will give.
        // It is taken here, once, and before the first create, because that is the only moment at
        // which nothing this job does has happened yet.
        job.startObservationTick = stateCache.currentObservationTick();
        try {
            for (ClipWrite clip : job.clips) {
                // BEFORE the creation, never after: one flush later this slot holds the clip we
                // are about to make, and "was it empty?" can no longer be asked. Three facts,
                // conjoined, and anything short of all three is null rather than false -- an
                // out-of-range coordinate and a never-observed slot both read as empty in the
                // underlying primitive array, and neither is a proof (D-29-10, D-29-11).
                //
                // The coordinate is resolved ONCE, here, and carried on the record: the arrays
                // below are keyed on the physical bank slot while the caller sent a public index,
                // and on a scrolled or grouped bank those address different tracks (WR-02). An
                // unresolvable index is one more way of not having proved anything, so it joins
                // the same conjunction rather than defaulting to slot zero.
                //
                // A FOURTH fact joins them, and it cannot be evaluated here: the observation this
                // reading rests on has to be NEWER than the instant above, and at this instant
                // nothing can be. An observation that is merely PRESENT proves nothing -- Bitwig
                // fires every slot's has-content observer at init, so after startup a present
                // observation is the permanent state of every in-range slot and is exactly the
                // one-flush-stale value D-29-11 exists to distrust (29-REVIEW.md, WR-01). So what
                // is recorded here is a READING and the sequence it was taken at; the fourth fact
                // is conjoined in settleSlotEvidence, one flush or more later, where the launcher
                // has had a chance to report the slot again and the reading can either be proven
                // current or withdrawn.
                clip.bankSlot = stateCache.resolveCanonicalBankSlot(clip.trackIndex);
                clip.emptinessObservationSeq =
                    stateCache.getClipObservationSeqAtBankSlot(clip.bankSlot, clip.sceneIndex);
                clip.slotReadBeforeCreate =
                    clip.bankSlot >= 0
                        && stateCache.clipSlotInRange(clip.bankSlot, clip.sceneIndex)
                        && stateCache.clipHasContentObserved(clip.bankSlot, clip.sceneIndex)
                    ? Boolean.valueOf(!stateCache.clipHasContent(clip.bankSlot, clip.sceneIndex))
                    : null;

                createClip(clip.trackIndex, clip.sceneIndex, clip.lengthBeats);
                clip.createDispatched = true;
            }
            ClipWrite first = job.clips.get(0);
            forceSelectClip(first.trackIndex, first.sceneIndex);
        } catch (Exception e) {
            failJob(job, job.clips.get(0), e);
            return;
        }

        scheduler.schedule(() -> verifyThenWrite(job, FLUSH_DELAY_MS), FLUSH_DELAY_MS);

        // LAST, and after the phase-1 catch above, so the two Phase 29 properties survive the
        // IN-04 reordering: a deadline is armed only for a deferral that is still outstanding,
        // and never for a job whose own first phase has already failed and answered inside this
        // call stack. The handler decided the figure; this is where it is armed.
        armDeadline(job, job.deadlineMs);
    }

    /**
     * Phase 2: compare where the cursor clip actually is against the slot this clip was named for,
     * and write only if they agree.
     *
     * @param elapsedMs how long has been spent waiting for the cursor, against
     *                  {@link #CURSOR_VERIFY_CEILING_MS}
     */
    private void verifyThenWrite(WriteJob job, long elapsedMs) {
        ClipWrite clip = job.clips.get(job.index);
        int observedTrack = stateCache.getClipCursorTrackPosition();
        int observedScene = stateCache.getClipCursorSceneIndex();

        if (observedTrack != clip.trackIndex || observedScene != clip.sceneIndex) {
            if (elapsedMs + FLUSH_DELAY_MS <= CURSOR_VERIFY_CEILING_MS) {
                // Possibly just a stale observer; give the flush cycle another go.
                scheduler.schedule(() -> verifyThenWrite(job, elapsedMs + FLUSH_DELAY_MS), FLUSH_DELAY_MS);
            } else {
                refuseJob(job, clip, observedTrack, observedScene, elapsedMs,
                    CURSOR_VERIFY_CEILING_MS, REFUSAL_CURSOR_POSITION);
            }
            return;
        }

        // The compare has agreed -- and agreeing while WRONG is exactly what it does at the
        // timing this phase exists for: 31-ECHO-MEASUREMENT.md's 100 ms row, where the engine
        // answered success and claimed a slot the notes were not in. So nothing is written yet.
        // The identity proof goes HERE, between the compare and the notes, which is what makes a
        // failed proof cost no notes anywhere and leaves the existing refusal and undo to apply
        // unchanged (D-31-02).
        stampThenProve(job, clip);
    }

    /**
     * Write a unique token through the cursor and then ask the NAMED SLOT ITSELF whether it got it.
     *
     * <p>There is no synchronous identity read at API v25 -- every accessor on the cursor clip
     * returns an observer, and the observers are what the compare above already read. So the
     * correction is not a better read of the same witness: it is a DIFFERENT witness. The named
     * slot's own indexed name observer is registered for all sixteen tracks and sixteen slots and
     * is fed by the launcher rather than by the cursor, so an echo arriving there could only have
     * come from a cursor that was on that slot.
     *
     * <p>The names snapshot is taken BEFORE the token goes out, because at the exact timing this
     * proof exists to catch, the cursor is on somebody else's clip and the stamp renames it. See
     * {@link #restoreStampedName}.
     */
    private void stampThenProve(WriteJob job, ClipWrite clip) {
        clip.priorNames = stateCache.snapshotClipNames();
        clip.stampToken = STAMP_PREFIX + (++stampSeq) + "-c" + job.index;
        clip.stampObservationSeq = stateCache.currentObservationTick();
        try {
            renameThroughCursor(clip.stampToken);
        } catch (Exception e) {
            failJob(job, clip, e);
            return;
        }
        scheduler.schedule(() -> proveStampEcho(job, clip, FLUSH_DELAY_MS), FLUSH_DELAY_MS);
    }

    /**
     * THE PROOF. Three facts, conjoined, in the same shape phase 1's emptiness read already uses,
     * and anything short of all three is not a proof:
     *
     * <ol>
     *   <li>the caller's track index RESOLVED to a physical bank slot -- {@code -1} is unproven,
     *       never slot zero;</li>
     *   <li>that slot's observation sequence is STRICTLY GREATER than the tick recorded at the
     *       stamp, so the reading is newer than the act it is meant to witness; and</li>
     *   <li>the name it published is the token, exactly.</li>
     * </ol>
     *
     * <p>Only then do the step size and the notes go out. On a miss it re-polls one flush later
     * while {@link #STAMP_ECHO_CEILING_MS} remains, and on exhaustion refuses through the existing
     * path with the existing code and the observed position. It NEVER re-points the cursor and
     * writes again: a false refusal is the accepted price of never a false success (D-31-07,
     * D-31-08), and a creating retry would put a second copy of the notes in the owner's music.
     *
     * <p>Paid PER CLIP and never amortised across a chain (D-31-11). Each clip has its own slot
     * and its own cursor move, so a shared proof would prove nothing about the later clips -- the
     * same reasoning that already gives the expression hop its own re-verified flush.
     */
    private void proveStampEcho(WriteJob job, ClipWrite clip, long elapsedMs) {
        boolean resolved = clip.bankSlot >= 0;
        long observedSeq = resolved
            ? stateCache.getClipObservationSeqAtBankSlot(clip.bankSlot, clip.sceneIndex)
            : 0;
        String observedName = resolved
            ? stateCache.getClipNameAtBankSlot(clip.bankSlot, clip.sceneIndex)
            : null;

        if (resolved
            && observedSeq > clip.stampObservationSeq
            && clip.stampToken.equals(observedName)) {
            writeProvenClip(job, clip);
            return;
        }

        if (elapsedMs + FLUSH_DELAY_MS <= STAMP_ECHO_CEILING_MS) {
            scheduler.schedule(
                () -> proveStampEcho(job, clip, elapsedMs + FLUSH_DELAY_MS), FLUSH_DELAY_MS);
            return;
        }

        // The proof has failed, so the stamp is on a clip that is not the caller's. Put the name
        // back BEFORE the refusal completes, so the answer can say whether the put-back was
        // proven rather than leaving the owner to find out.
        final long waitedMs = elapsedMs;
        restoreStampedName(clip, () -> refuseJob(job, clip,
            stateCache.getClipCursorTrackPosition(), stateCache.getClipCursorSceneIndex(),
            waitedMs, STAMP_ECHO_CEILING_MS, REFUSAL_STAMP_ECHO));
    }

    /**
     * Put back the name the stamp overwrote, then say plainly whether the put-back was proven.
     *
     * <p>WHY THIS EXISTS. The stamp is a rename and a rename is cursor-scoped, so at the exact
     * timing the proof is built to catch, the clip it renames is not the caller's. Without this,
     * the proof that stops the engine writing the caller's notes into somebody else's clip would
     * instead have renamed that clip -- on EVERY write that reaches phase 2 while the cursor is
     * elsewhere, which by construction is every case this phase exists for. A phase whose subject
     * is truthfulness must not silently rename the owner's music.
     *
     * <p>HOW THE SLOT IS FOUND. The token is unique per write (D-31-04), so at most one slot can
     * be carrying it, and that slot names exactly where the cursor was. The cursor is STILL on
     * that clip -- that is why the echo failed -- so the prior name goes back through the same
     * cursor-scoped rename route, with no new coordinate and no new RPC. The re-read one flush
     * later is what makes the reported fact an observation rather than a claim: an accepted
     * dispatch is not proof here either.
     *
     * <p>WHAT IT REPORTS, as two facts rather than one. Both absent means the token was not found
     * at any slot: the stamp may not have landed anywhere observable, which is itself the honest
     * answer and one the pair has to be able to express. {@code stampLeftAt} present with
     * {@code stampRestored} absent means the restore was attempted there and could not be proven.
     * {@code stampRestored} true means a read saw the prior name back.
     */
    private void restoreStampedName(ClipWrite clip, Runnable then) {
        String[][] now = stateCache.snapshotClipNames();
        int foundBankSlot = -1;
        int foundScene = -1;
        for (int bank = 0; bank < now.length && foundBankSlot < 0; bank++) {
            for (int scene = 0; scene < now[bank].length; scene++) {
                if (clip.stampToken != null && clip.stampToken.equals(now[bank][scene])) {
                    foundBankSlot = bank;
                    foundScene = scene;
                    break;
                }
            }
        }

        if (foundBankSlot < 0) {
            // Nothing carries the token. Both keys stay absent, which is the pair's way of saying
            // the stamp did not land anywhere this engine can see.
            then.run();
            return;
        }

        clip.stampLeftAt = "t" + foundBankSlot + "s" + foundScene;
        String prior = clip.priorNames != null ? clip.priorNames[foundBankSlot][foundScene] : null;
        final String wanted = prior != null ? prior : "";
        try {
            renameThroughCursor(wanted);
        } catch (Exception e) {
            // Attempted and not proven: stampLeftAt says where, stampRestored stays absent.
            then.run();
            return;
        }

        final int bankSlot = foundBankSlot;
        final int sceneIndex = foundScene;
        scheduler.schedule(() -> {
            if (wanted.equals(stateCache.getClipNameAtBankSlot(bankSlot, sceneIndex))) {
                clip.stampRestored = Boolean.TRUE;
            }
            then.run();
        }, FLUSH_DELAY_MS);
    }

    /** The one cursor-scoped rename route, shared by the stamp, the final name and the restore. */
    private void renameThroughCursor(String name) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        dispatcher.handleInternal("clip/rename", params);
    }

    /**
     * The name the clip is left carrying once the proof has held: the caller's when they gave one,
     * and otherwise whatever the named slot was called before the stamp.
     *
     * <p>This is not decoration. The stamp is a real rename, so a write that named no clip would
     * otherwise leave the owner looking at a clip called {@code SECONDO-STAMP-3-c0}. The rename is
     * cursor-scoped like every other step here and is safe for exactly the reason the notes are:
     * it runs only inside the branch where the slot's own observer has just proved the cursor is
     * on it.
     *
     * <p>Empty string when that name was never observed -- a state a running Bitwig is not in,
     * since every slot's name observer fires at initialization, and the name a clip phase 1 has
     * just created carries anyway.
     */
    private static String finalNameFor(ClipWrite clip) {
        if (clip.name != null) {
            return clip.name;
        }
        String prior = clip.priorNameAtNamedSlot();
        return prior != null ? prior : "";
    }

    /** Phase 2b: the write itself, reached only through an echo the named slot published. */
    private void writeProvenClip(WriteJob job, ClipWrite clip) {
        ExpressionWork work;
        try {
            writeNotesToCursor(clip.stepSize, clip.notes, finalNameFor(clip));
            // INSIDE the guard, since plan 31-06 (29-REVIEW.md, WR-05). This is the WEAKER half of
            // WR-05's fix and it is kept as a BACKSTOP rather than as the fix: handleWriteClip now
            // refuses a malformed payload before anything is queued, but macro/buildSection's
            // chain reaches this same collection, and a throw here used to escape every terminal
            // path -- leaving writeInProgress latched true and the write path dead for the rest of
            // the session. A guard that ends in a terminal path is what makes finishJob's clearing
            // of that flag unconditional.
            work = collectNoteExpressions(clip.notes);
        } catch (Exception e) {
            // The proof still holds -- the cursor is on the slot the caller named -- so the token
            // is on OUR clip and this rename cannot reach anybody else's. Best-effort and
            // deliberately silent: the write has already failed, or its expressions cannot be read,
            // and that is the fact the caller is about to be given.
            try {
                renameThroughCursor(finalNameFor(clip));
            } catch (Exception ignored) {
                // Nothing further to try. A visible token is the report.
            }
            failJob(job, clip, e);
            return;
        }

        if (work == null) {
            advance(job, clip, "none");
            return;
        }

        // Phase 3: expressions, one flush later, re-verified. No re-poll here -- nothing of ours
        // moves the cursor while a job is in flight, so a mismatch at this point is the user or
        // another surface moving it, and waiting would not make it come back.
        scheduler.schedule(() -> {
            int exprTrack = stateCache.getClipCursorTrackPosition();
            int exprScene = stateCache.getClipCursorSceneIndex();
            if (exprTrack != clip.trackIndex || exprScene != clip.sceneIndex) {
                refuseExpressions(job, clip, exprTrack, exprScene);
                return;
            }
            try {
                applyNoteExpressions(work);
            } catch (Exception e) {
                failJob(job, clip, e);
                return;
            }
            advance(job, clip, "applied");
        }, FLUSH_DELAY_MS);
    }

    /** This clip is done; move to the next one, or finish the job. */
    private void advance(WriteJob job, ClipWrite clip, String expressionsState) {
        job.landed.add(describe(clip, expressionsState));
        job.index++;

        if (job.index >= job.clips.size()) {
            // Terminal path 1 of 4: everything the job named actually landed.
            completePending(job, landedPayload(job));
            finishJob(job);
            return;
        }

        ClipWrite next = job.clips.get(job.index);
        try {
            forceSelectClip(next.trackIndex, next.sceneIndex);
        } catch (Exception e) {
            failJob(job, next, e);
            return;
        }
        scheduler.schedule(() -> verifyThenWrite(job, FLUSH_DELAY_MS), FLUSH_DELAY_MS);
    }

    private void finishJob(WriteJob job) {
        writeInProgress = false;
        startNextJob();
    }

    /**
     * Settle what this job can PROVE about the slot it named, at the last moment before an answer
     * carrying that proof is published.
     *
     * <p>WHY THE PROOF IS NOT COMPLETED WHERE THE READING IS TAKEN. The reading has to be taken
     * before {@code clip/create}, because one flush later the slot holds the clip this job just
     * made. Its freshness cannot be tested there: {@code startObservationTick} is read at that
     * same instant, so nothing can yet be newer than it. Conjoining the two in phase 1 would
     * therefore make every answer unproven and retire the undo altogether -- which is not the
     * safe direction, it is a different wrong answer, and it would leave an empty clip at the
     * owner's named slot on every refusal (D-31-09 forbids exactly that shape of regression).
     * So the reading and its date are recorded in phase 1 and conjoined HERE, on a terminal path,
     * by which time the job's own {@code clip/create} has had at least one flush to be reported.
     *
     * <p>WHAT THE FOURTH FACT BUYS. An observation newer than the job's own start is the only
     * kind that can carry the effect of the job's create. Requiring it means the undo deletes
     * only when the launcher itself has spoken about that slot since this job acted -- so a
     * create that was accepted and did nothing (the v25 no-op over an occupied slot), a slot
     * whose coordinate never resolved, and a slot nothing has ever observed all withhold the
     * delete instead of licensing it. It is strictly narrower than the guard it replaces: every
     * case that deleted before and still deletes now also satisfied the three facts it satisfied
     * before.
     *
     * <p>WHAT IT DOES NOT BUY, stated because a half-closed hazard read as a closed one is worse
     * than an open one. 29-REVIEW.md WR-01's own example -- a {@code clip/insertFile} for the
     * same slot drained in the same {@code drainAndExecute} as this write -- still produces a
     * fresh observation, because THEIR content lands on the slot and is reported. Freshness alone
     * cannot tell whose content arrived; only knowing that a content-creating request was
     * dispatched for that slot can, which is the second clause of WR-01's fix and needs a
     * per-slot dispatch record in {@code StateCache} that {@code ClipHandler} writes to. That is
     * a cross-handler change this plan did not have, and it is filed rather than assumed closed.
     *
     * <p>Idempotent: the first terminal path to run settles the answer, and a later one reads
     * what it decided rather than re-deriving it against a cache that has moved on.
     */
    private void settleSlotEvidence(WriteJob job, ClipWrite clip) {
        if (clip.evidenceSettled) {
            return;
        }
        clip.evidenceSettled = true;

        // FOUR facts, conjoined, and the coordinate is the RESOLVED bank slot in every one of
        // them -- the same slot the create dispatched into and the same slot the delete will
        // address. A reading taken against the caller's public index and a delete addressed by
        // the resolved one are two different slots the moment the bank is scrolled or a group is
        // collapsed, and the delete is the one that reaches the owner's music (WR-02).
        //
        // The FALSE answer falls out of the else, and is deliberately NOT gated on freshness: it
        // authorises no delete, so withholding it would buy no safety, and it would replace a
        // specific true report -- the slot already held content -- with a vaguer one, and lose
        // the fact that makes the create a no-op rather than an unknown (IN-05).
        clip.slotWasEmpty =
            clip.bankSlot >= 0
                && Boolean.TRUE.equals(clip.slotReadBeforeCreate)
                && stateCache.clipSlotInRange(clip.bankSlot, clip.sceneIndex)
                && stateCache.getClipObservationSeqAtBankSlot(clip.bankSlot, clip.sceneIndex)
                    > job.startObservationTick
            ? Boolean.TRUE
            : Boolean.FALSE.equals(clip.slotReadBeforeCreate) ? Boolean.FALSE : null;

        // One fact, one vocabulary: the field assigned below IS the published `clipCreated`, and
        // from this plan it is derived from the measured emptiness answer rather than from a
        // dispatch Bitwig may have ignored. At API v25 a create over an occupied slot is
        // acknowledged and changes nothing, so a flag set true on a successful dispatch was
        // answering a question nobody asked (29-REVIEW.md, IN-05). Three states, and the third is
        // an absence rather than a false, because a create that may or may not have happened is
        // not the same fact as a create that provably did not.
        clip.created =
            !clip.createDispatched ? Boolean.FALSE
                : Boolean.TRUE.equals(clip.slotWasEmpty) ? Boolean.TRUE
                : Boolean.FALSE.equals(clip.slotWasEmpty) ? Boolean.FALSE
                : null;
    }

    /**
     * @param cursorTrack where the launcher cursor clip actually was, or -1 if never observed.
     *                    Named for what the RESPONSE calls it, not for the observer that reports
     *                    it: the snapshot record used to speak observedTrack / observedScene here
     *                    and the response speaks cursorTrack / cursorScene, and one fact with two
     *                    vocabularies inside one method is how a caller ends up reading the wrong
     *                    key (D-29-18; src/secondo/tools/write_clip.py:1961-1964 already reads
     *                    these two names).
     * @param ceilingMs   which budget was exhausted -- the position compare's or the identity
     *                    proof's. Reported rather than assumed: the two differ, and a refusal that
     *                    quoted the wrong one would misdescribe how long the cursor was given.
     * @param refusalReason which of the two triggers fired, as a value a program can read.
     */
    private void refuseJob(WriteJob job, ClipWrite clip, int cursorTrack, int cursorScene,
                           long elapsedMs, long ceilingMs, String refusalReason) {
        String timestamp = java.time.Instant.now().toString();
        stateCache.recordWriteClipRefusal();

        // The slot facts are settled BEFORE the undo decides anything, because the undo is what
        // consumes them. This is the terminal path that deletes.
        settleSlotEvidence(job, clip);

        // THE UNDO (plan 29-03, T-29-06). Phase 1 created a clip at the slot the caller named --
        // that is what made the slot addressable and what the verify then read -- so a refusal
        // that says "nothing was written" while leaving an empty clip behind is a false report.
        // 23-UAT.md test 1 is the recorded proof: a refused write left an empty 16-beat clip at
        // track 3 scene 1 and still answered ok.
        //
        // It removes ONLY when the slot was proven empty before the creation, and ONLY from this
        // path -- failJob removes nothing, because a write that failed may have written
        // something. The address is the caller's own (trackIndex, slotIndex) through clip/delete,
        // never the cursor: the whole reason we are here is that the cursor is somewhere else.
        boolean clipRemoved = Boolean.TRUE.equals(clip.slotWasEmpty) && removeCreatedClip(clip);
        String leftoverReason = leftoverReason(clip, clipRemoved);

        errorLog.accept(MARKER_CURSOR_MISMATCH + " " + timestamp + " " + job.method
            + " code=" + JsonRpcError.CURSOR_MISMATCH
            + " requested=" + clip.label()
            + " observed=" + position(cursorTrack, cursorScene)
            + " notes=" + clip.notes.size()
            + " ceilingMs=" + ceilingMs
            + " waitedMs=" + elapsedMs
            + " reason=" + refusalReason
            + " — REFUSED: the cursor clip was not on the slot this write named, so no notes were"
            + " written. " + slotOutcome(clip, clipRemoved, leftoverReason) + chainSummary(job));

        // Terminal path 2 of 4, and it now has TWO triggers rather than one: the position compare
        // never agreed, or it agreed and the identity proof that follows it did not. Both are the
        // same clean refusal -- same code, same undo, same response shape -- so this stays one
        // terminal path and `refusalReason` carries the difference (D-31-07). The refusal travels
        // IN THE RESPONSE, not only in the marked
        // console line above and the snapshot counter. The four position keys are the ones
        // src/secondo/tools/write_clip.py:1961-1964 already reads; ceilingMs and finding say how
        // long the cursor was given and which finding this refusal belongs to; and the last three
        // say what happened to the slot itself, as separate readable facts rather than as prose a
        // program would have to parse (D-29-12).
        JsonObject data = new JsonObject();
        data.addProperty("requestedTrack", clip.trackIndex);
        data.addProperty("requestedScene", clip.sceneIndex);
        addPosition(data, "cursorTrack", cursorTrack);
        addPosition(data, "cursorScene", cursorScene);
        data.addProperty("ceilingMs", ceilingMs);
        data.addProperty("refusalReason", refusalReason);
        data.addProperty("finding", "TODO-WRONG-SLOT");
        addCreated(data, clip);
        data.addProperty("clipRemoved", clipRemoved);
        data.addProperty("leftoverReason", leftoverReason);
        // How fresh the evidence behind the line above actually was: the observation sequence the
        // emptiness reading was taken at, or an explicit null when nothing has ever observed that
        // slot. One key, spelled once, in the slot-facts vocabulary the three above already use.
        addObservationSeq(data, "slotObservedAt", clip.emptinessObservationSeq);
        // The stamp's own two facts, in the vocabulary the undo beside them already uses and with
        // the same discipline: an absence is PUBLISHED rather than omitted, and an unproven
        // restore is an absence rather than a false. Both null on a position-compare refusal,
        // where no stamp was ever written -- which is the honest reading of "never attempted".
        if (Boolean.TRUE.equals(clip.stampRestored)) {
            data.addProperty("stampRestored", true);
        } else {
            data.add("stampRestored", JsonNull.INSTANCE);
        }
        if (clip.stampLeftAt != null) {
            data.addProperty("stampLeftAt", clip.stampLeftAt);
        } else {
            data.add("stampLeftAt", JsonNull.INSTANCE);
        }
        completePending(job, JsonRpcError.CURSOR_MISMATCH,
            "the cursor clip was not on the slot this write named, so nothing was written",
            data);
        finishJob(job);
    }

    /**
     * Remove the clip this job created at the slot the CALLER named.
     *
     * <p>Coordinate-addressed through {@code clip/delete} ({@code ClipHandler.java:99-106}, whose
     * body reads its own {@code trackIndex} / {@code slotIndex} and touches no cursor),
     * mirroring {@link #createClip}'s internal-dispatch shape. Addressing it by the cursor would
     * delete whatever the cursor had wandered onto, which on this path is by definition not the
     * slot this job touched.
     *
     * @return whether the removal was dispatched without error. False is not a disaster and is
     *         not hidden: the caller is told {@code clipRemoved: false} and given a reason.
     */
    private boolean removeCreatedClip(ClipWrite clip) {
        JsonObject params = new JsonObject();
        params.addProperty("trackIndex", clip.trackIndex);
        params.addProperty("slotIndex", clip.sceneIndex);
        try {
            dispatcher.handleInternal("clip/delete", params);
            return true;
        } catch (Exception e) {
            // Deliberately no third console marker: the two that exist are a forensic trail for a
            // refusal the caller could not see, and this fact IS in the answer the caller gets.
            return false;
        }
    }

    /**
     * Why a clip was left at the named slot, or null when nothing was left.
     *
     * <p>Three reasons, and they are different facts rather than degrees of one: the slot already
     * held content, so removing the clip could have destroyed material this job did not create;
     * or the slot's emptiness was never observed, so the same risk could not be ruled out; or the
     * removal ran and did not succeed.
     */
    private static String leftoverReason(ClipWrite clip, boolean clipRemoved) {
        if (clipRemoved) {
            return null;
        }
        if (Boolean.FALSE.equals(clip.created)) {
            // Nothing of this write's making is at the slot, so there is nothing left over. The
            // occupied case still owes an explanation, because "no clip was created" is a
            // surprising answer to a create that was dispatched and acknowledged: at v25 that
            // dispatch was a no-op, and the caller is told so rather than left to infer it from
            // a clipCreated they may not have read (IN-05).
            if (Boolean.FALSE.equals(clip.slotWasEmpty)) {
                return "the slot already held content before this write, so no clip was created"
                    + " and nothing was removed";
            }
            return null;
        }
        if (Boolean.TRUE.equals(clip.slotWasEmpty)) {
            return "the clip this write created was left in place: clip/delete did not succeed";
        }
        // There is no FALSE branch here any more, and its absence is deliberate rather than an
        // omission: `created` is derived from `slotWasEmpty`, so a proven-occupied slot has
        // already returned above with the reason that says BOTH facts. A second spelling of it
        // here would be a second source of truth for one fact.
        //
        // Three ways of not having proved it, and they are different facts rather than degrees of
        // one -- which is the whole reason the unproven answer is a null and not a false. A
        // coordinate that never resolved is a different problem from a slot nothing has ever
        // looked at, and both are different from a reading that exists but predates this write.
        if (clip.bankSlot < 0) {
            return "the slot's coordinate could not be resolved, so nothing was removed";
        }
        if (clip.slotReadBeforeCreate == null) {
            return "the slot's emptiness was never observed, so nothing was removed";
        }
        return "the slot's emptiness was observed only before this write began, so it could not"
            + " be proven current and nothing was removed";
    }

    /** The refusal console line's closing sentence: what actually happened to the slot. */
    private static String slotOutcome(ClipWrite clip, boolean clipRemoved, String leftoverReason) {
        if (Boolean.FALSE.equals(clip.created)) {
            return "No clip was created at " + clip.label() + ".";
        }
        if (clip.created == null) {
            return "Whether a clip was created at " + clip.label() + " could not be proven: "
                + leftoverReason + ".";
        }
        if (clipRemoved) {
            return "The empty clip this write created at " + clip.label() + " was removed.";
        }
        return "The clip this write created at " + clip.label() + " was LEFT: " + leftoverReason
            + ".";
    }

    /**
     * Publish an observed coordinate, or JSON null when nothing has observed it.
     *
     * <p>The {@code -1} sentinel is an internal spelling of "never observed"
     * ({@code StateCache.java:208-209}) and must not leave the engine as a number: the snapshot
     * already guards it the same way, and a refusal that told a user the cursor was on "track -1"
     * would be the first thing they saw of this feature.
     */
    private static void addPosition(JsonObject data, String key, int value) {
        if (value < 0) {
            data.add(key, JsonNull.INSTANCE);
        } else {
            data.addProperty(key, value);
        }
    }

    /**
     * Publish an observation sequence, or JSON null when the slot has never been observed.
     *
     * <p>Zero is {@code StateCache}'s internal spelling of "no observer callback has ever landed
     * here" and must not leave the engine as a number: a caller reading {@code slotObservedAt: 0}
     * would read it as a real, very old observation rather than as the absence of one. Same
     * discipline as {@link #addPosition}, for the same reason.
     */
    private static void addObservationSeq(JsonObject data, String key, long value) {
        if (value <= 0) {
            data.add(key, JsonNull.INSTANCE);
        } else {
            data.addProperty(key, value);
        }
    }

    /**
     * Publish {@code clipCreated}, or JSON null when it could not be proven either way.
     *
     * <p>The one place the three states of {@link ClipWrite#created} are turned into wire form,
     * so every path that reports it reports it the same way. Same discipline as
     * {@link #addPosition} and {@link #addObservationSeq}: the key is always present, an absence
     * is an explicit null, and an unproven answer is never collapsed into a {@code false}.
     */
    private static void addCreated(JsonObject data, ClipWrite clip) {
        if (clip.created == null) {
            data.add("clipCreated", JsonNull.INSTANCE);
        } else {
            data.addProperty("clipCreated", clip.created);
        }
    }

    private void refuseExpressions(WriteJob job, ClipWrite clip, int cursorTrack, int cursorScene) {
        String timestamp = java.time.Instant.now().toString();
        stateCache.recordWriteClipRefusal();
        settleSlotEvidence(job, clip);

        errorLog.accept(MARKER_CURSOR_MISMATCH + " " + timestamp + " " + job.method
            + " code=" + JsonRpcError.CURSOR_MISMATCH
            + " requested=" + clip.label()
            + " observed=" + position(cursorTrack, cursorScene)
            + " notes=" + clip.notes.size()
            + " — the notes landed on " + clip.label() + " but the cursor moved before their"
            + " expressions could be applied, so the EXPRESSIONS were refused rather than written"
            + " to the wrong clip." + chainSummary(job));

        // The notes themselves did land; say so, and stop the chain.
        job.landed.add(describe(clip, "refused"));
        job.index++;

        // Terminal path 3 of 4, and the one that must NOT be an error (T-29-07). This is a
        // PARTIAL SUCCESS: the notes are on the slot the caller named and only the expressions
        // were refused, which is what the errorln line two statements up says in its own words.
        // Completing it as CURSOR_MISMATCH would make src/secondo/tools/write_clip.py tell the
        // user nothing was written and to RE-ISSUE THE WRITE -- and a re-issue of a creating
        // write puts a second copy of the notes in the owner's own music. The expressions key is
        // explicit so a reader never has to infer the partiality from the landed array.
        //
        // The slot pair travels here too, for symmetry with the two error paths: a reader of this
        // response asks the same question ("what is at the slot I named?") and must not have to
        // ask it a different way because the answer happened to be a success. Nothing is removed
        // on this path -- the notes LANDED, and removing the clip would delete them.
        JsonObject result = landedPayload(job);
        result.addProperty("expressions", "refused");
        addCreated(result, clip);
        result.addProperty("clipRemoved", false);
        completePending(job, result);
        finishJob(job);
    }

    private void failJob(WriteJob job, ClipWrite clip, Exception cause) {
        String timestamp = java.time.Instant.now().toString();
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        stateCache.recordWriteClipRefusal();
        settleSlotEvidence(job, clip);

        errorLog.accept(MARKER_NOTE_WRITE_FAILED + " " + timestamp + " " + job.method
            + " code=" + JsonRpcError.NOTE_WRITE_FAILED
            + " requested=" + clip.label()
            + " notes=" + clip.notes.size()
            + " — the cursor was on the named slot and the write itself failed: " + reason
            + chainSummary(job));

        // Terminal path 4 of 4. `reason` is the key src/secondo/tools/write_clip.py:1985 already
        // reads for this code, so it is the key emitted here rather than a new spelling.
        //
        // NOTHING IS REMOVED ON THIS PATH, and that is a decision rather than an omission
        // (T-29-06). A write that failed may have written something -- the notes may be half in,
        // the step size may be set -- so the slot's content is not this job's to delete. The
        // caller is told the clip is there and why it was left, and reads it back. `clipRemoved`
        // is therefore always false here, while `clipCreated` reports what actually happened:
        // false when clip/create itself was what threw, true when the failure came later.
        JsonObject data = new JsonObject();
        data.addProperty("requestedTrack", clip.trackIndex);
        data.addProperty("requestedScene", clip.sceneIndex);
        data.addProperty("reason", reason);
        data.addProperty("finding", "TODO-WRONG-SLOT");
        addCreated(data, clip);
        data.addProperty("clipRemoved", false);
        // Boolean.FALSE.equals rather than a bare negation, because `created` is three-state
        // since plan 31-05 and the unproven state belongs with the created one here: if nobody
        // can say whether a clip is at that slot, the caller still needs to be told why nothing
        // was removed from it.
        data.addProperty("leftoverReason", Boolean.FALSE.equals(clip.created)
            ? null
            : "a failed write may have written something, so this path removes nothing");
        completePending(job, JsonRpcError.NOTE_WRITE_FAILED,
            "the cursor was on the named slot and the write itself failed: " + reason, data);
        finishJob(job);
    }

    /** What landed and what did not, for a chain that stopped partway. Empty for a lone clip. */
    private String chainSummary(WriteJob job) {
        if (job.clips.size() < 2) {
            return "";
        }
        StringBuilder notWritten = new StringBuilder();
        for (int i = job.index; i < job.clips.size(); i++) {
            if (notWritten.length() > 0) {
                notWritten.append(",");
            }
            notWritten.append(job.clips.get(i).label());
        }
        StringBuilder landed = new StringBuilder();
        for (JsonElement el : job.landed) {
            if (landed.length() > 0) {
                landed.append(",");
            }
            landed.append(el.getAsJsonObject().get("slot").getAsString());
        }
        return " Chain ABORTED: landed=[" + landed + "] notWritten=[" + notWritten + "].";
    }

    private static JsonObject describe(ClipWrite clip, String expressionsState) {
        JsonObject obj = new JsonObject();
        obj.addProperty("slot", clip.label());
        obj.addProperty("notes", clip.notes.size());
        obj.addProperty("expressions", expressionsState);
        return obj;
    }

    private static String position(int track, int scene) {
        // -1 is "never observed", and reads as nothing else.
        if (track < 0 || scene < 0) {
            return "unobserved(t" + track + "s" + scene + ")";
        }
        return "t" + track + "s" + scene;
    }

    private void createClip(int trackIndex, int slotIndex, int lengthBeats) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("trackIndex", trackIndex);
        params.addProperty("slotIndex", slotIndex);
        params.addProperty("lengthInBeats", lengthBeats);
        dispatcher.handleInternal("clip/create", params);
    }

    private void forceSelectClip(int trackIndex, int slotIndex) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("trackIndex", trackIndex);
        params.addProperty("slotIndex", slotIndex);
        params.addProperty("force", true);
        dispatcher.handleInternal("clip/select", params);
    }

}
