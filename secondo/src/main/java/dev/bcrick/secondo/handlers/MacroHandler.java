package dev.bcrick.secondo.handlers;

import com.google.gson.*;
import dev.bcrick.secondo.extension.StateCache;
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
 * completable after the handler returns is Phase 29 ("Engine: Deferrable RPC Responses"), which
 * will emit {@link JsonRpcError#CURSOR_MISMATCH} / {@link JsonRpcError#NOTE_WRITE_FAILED} in the
 * response. Until then a refusal is announced two ways, both retrievable:
 *
 * <ul>
 *   <li>a {@code host.errorln} line marked {@value #MARKER_CURSOR_MISMATCH} or
 *       {@value #MARKER_NOTE_WRITE_FAILED}, with an ISO-8601 timestamp and both positions; and</li>
 *   <li>{@code writeClipRefusals} / {@code lastWriteClipRefusal} in the snapshot's clip section.</li>
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
 * <p>WHAT A REFUSAL DOES TO THE SLOT (plan 29-03). {@code refuseJob} removes the clip this job
 * created, and only when the named slot was proven empty before the creation -- in range, and
 * observed, and observed empty. Anything it cannot prove it LEAVES, and says why in
 * {@code leftoverReason}. The removal is addressed by the caller's own {@code (trackIndex,
 * slotIndex)} through {@code clip/delete} and never by the cursor, because the refusal IS that
 * the cursor is somewhere else. {@code failJob} removes nothing at all: a write that failed may
 * have written something.
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

        // Claim the response BEFORE enqueuing, because enqueueWrite can run the whole first phase
        // of the job synchronously -- and startNextJob's own catch calls failJob INSIDE this
        // handler's call stack, so the deferral can be RESOLVED before drainAndExecute has bound
        // the command to it. PendingResponse buffers a finished answer for exactly that case; see
        // its class comment. The value this method returns on the deferred path is discarded by
        // handleSingle and must not be relied on by anything.
        PendingResponse pending = (deferrable && !queueBusy)
            ? dispatcher.deferCurrentResponse() : null;

        WriteJob job = new WriteJob("macro/writeClip",
            List.of(new ClipWrite(trackIndex, sceneIndex, lengthBeats, stepSize, notes, name)),
            pending);
        enqueueWrite(job);

        // AFTER enqueueWrite, never before: see armDeadline's own comment on why arming a deadline
        // for an answer that has already been produced is the one ordering that would be wrong.
        armDeadline(job);

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
        //
        // Both keys are ALWAYS present, never omitted: JsonRpcDispatcher.java's serializer comment
        // states that a Python reader tests whether a value is None and never tests key
        // membership, so an absent key is not a way to say "no reason".
        JsonObject result = new JsonObject();
        result.addProperty("count", notes.size());
        result.addProperty("deferred", false);
        result.addProperty("deferReason", deferrable ? "queue-busy" : "not-deferrable");
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
            JsonObject renameP = new JsonObject();
            renameP.addProperty("name", name);
            dispatcher.handleInternal("clip/rename", renameP);
        }

        // Expressions are NOT applied from here any more. They are a third cursor-scoped hop one
        // flush later, so the job driver schedules them itself and re-verifies the cursor first --
        // expressions landing on the slot after the one their notes went to is the same wrong-slot
        // bug one layer down, and it used to happen on every buildSection chain because the next
        // clip was selected in the same task that scheduled them.
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
         * Was this slot PROVEN empty immediately before phase 1 created a clip in it?
         *
         * <p>Three states, and the third is the reason this is a {@code Boolean} and not a
         * {@code boolean}: {@code TRUE} means in range, observed, and observed empty;
         * {@code FALSE} means observed and holding content; {@code null} means UNPROVEN -- out of
         * range, or no has-content observer has ever fired for it. Only {@code TRUE} authorises
         * the undo. An unprovable answer to "was this slot empty?" resolves to leaving the clip
         * and saying so, never to deleting on a guess, because the thing on the other side of a
         * wrong guess is material the owner made (D-29-10, D-29-11).
         *
         * <p>Recorded BEFORE {@code createClip}, which is the only moment it can be read: one
         * flush later the slot holds the clip this job just made, and the observation is gone.
         */
        Boolean slotWasEmpty;

        /**
         * Did phase 1 actually create a clip at this slot?
         *
         * <p>Reported to the caller as {@code clipCreated}. False when {@code createClip} threw
         * before reaching this clip -- which is the one case where "this job put a clip there" is
         * not true, and the one case a flat {@code true} would misreport.
         */
        boolean created;

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
     * <p>WHY IT IS CALLED AFTER {@code enqueueWrite}. {@code enqueueWrite} can run the whole first
     * phase of the job synchronously, and {@code startNextJob}'s own catch can complete the
     * response inside the handler's call stack. Arming before that would schedule a deadline for
     * a response already produced -- harmless, because the task re-checks, but it would leave a
     * task parked for three seconds on a job that finished in the same flush, and it would make
     * "a deadline is armed exactly when a deferral is still outstanding" untrue.
     *
     * <p>No console marker is written here, deliberately. The two markers this class declares are
     * a forensic trail for a refusal the caller could not see; this outcome IS the answer the
     * caller receives.
     */
    private void armDeadline(WriteJob job) {
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

            // Every key is ALWAYS present, JSON null where unobserved -- the explicit-null reader
            // rule at JsonRpcDispatcher.java:55-57. clipCreated and clipRemoved were DECLARED as
            // explicit nulls by plan 29-02 and are POPULATED here from plan 29-03: phase 1 records
            // whether it created the clip, and this path removes nothing, so both facts are now
            // observed rather than unobservable. Leaving them null would say "the engine did not
            // observe this" about something the engine now does observe -- which is the same class
            // of false claim this plan exists to remove from the refusal.
            JsonObject data = new JsonObject();
            data.addProperty("deadlineMs", DEFERRAL_DEADLINE_MS);
            data.addProperty("requestedTrack", clip.trackIndex);
            data.addProperty("requestedScene", clip.sceneIndex);
            data.addProperty("noteCount", clip.notes.size());
            data.addProperty("clipCreated", clip.created);
            data.addProperty("clipRemoved", false);

            job.pending.completeError(
                JsonRpcError.WRITE_UNRESOLVED, WRITE_UNRESOLVED_MESSAGE, data);
        }, DEFERRAL_DEADLINE_MS);
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
        try {
            for (ClipWrite clip : job.clips) {
                // BEFORE the creation, never after: one flush later this slot holds the clip we
                // are about to make, and "was it empty?" can no longer be asked. Three facts,
                // conjoined, and anything short of all three is null rather than false -- an
                // out-of-range coordinate and a never-observed slot both read as empty in the
                // underlying primitive array, and neither is a proof (D-29-10, D-29-11).
                clip.slotWasEmpty =
                    stateCache.clipSlotInRange(clip.trackIndex, clip.sceneIndex)
                        && stateCache.clipHasContentObserved(clip.trackIndex, clip.sceneIndex)
                    ? Boolean.valueOf(!stateCache.clipHasContent(clip.trackIndex, clip.sceneIndex))
                    : null;

                createClip(clip.trackIndex, clip.sceneIndex, clip.lengthBeats);
                clip.created = true;
            }
            ClipWrite first = job.clips.get(0);
            forceSelectClip(first.trackIndex, first.sceneIndex);
        } catch (Exception e) {
            failJob(job, job.clips.get(0), e);
            return;
        }

        scheduler.schedule(() -> verifyThenWrite(job, FLUSH_DELAY_MS), FLUSH_DELAY_MS);
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
                refuseJob(job, clip, observedTrack, observedScene, elapsedMs);
            }
            return;
        }

        try {
            writeNotesToCursor(clip.stepSize, clip.notes, clip.name);
        } catch (Exception e) {
            failJob(job, clip, e);
            return;
        }

        ExpressionWork work = collectNoteExpressions(clip.notes);
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
     * @param cursorTrack where the launcher cursor clip actually was, or -1 if never observed.
     *                    Named for what the RESPONSE calls it, not for the observer that reports
     *                    it: the snapshot record used to speak observedTrack / observedScene here
     *                    and the response speaks cursorTrack / cursorScene, and one fact with two
     *                    vocabularies inside one method is how a caller ends up reading the wrong
     *                    key (D-29-18; src/secondo/tools/write_clip.py:1961-1964 already reads
     *                    these two names).
     */
    private void refuseJob(WriteJob job, ClipWrite clip, int cursorTrack, int cursorScene,
                           long elapsedMs) {
        String timestamp = java.time.Instant.now().toString();
        stateCache.recordWriteClipRefusal();

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
            + " ceilingMs=" + CURSOR_VERIFY_CEILING_MS
            + " waitedMs=" + elapsedMs
            + " — REFUSED: the cursor clip was not on the slot this write named, so no notes were"
            + " written. " + slotOutcome(clip, clipRemoved, leftoverReason) + chainSummary(job));

        // Terminal path 2 of 4: the refusal now travels IN THE RESPONSE, not only in the marked
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
        data.addProperty("ceilingMs", CURSOR_VERIFY_CEILING_MS);
        data.addProperty("finding", "TODO-WRONG-SLOT");
        data.addProperty("clipCreated", clip.created);
        data.addProperty("clipRemoved", clipRemoved);
        data.addProperty("leftoverReason", leftoverReason);
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
        if (clipRemoved || !clip.created) {
            return null;
        }
        if (Boolean.TRUE.equals(clip.slotWasEmpty)) {
            return "the clip this write created was left in place: clip/delete did not succeed";
        }
        if (Boolean.FALSE.equals(clip.slotWasEmpty)) {
            return "the slot already held content before this write, so nothing was removed";
        }
        return "the slot's emptiness was never observed, so nothing was removed";
    }

    /** The refusal console line's closing sentence: what actually happened to the slot. */
    private static String slotOutcome(ClipWrite clip, boolean clipRemoved, String leftoverReason) {
        if (!clip.created) {
            return "No clip was created at " + clip.label() + ".";
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

    private void refuseExpressions(WriteJob job, ClipWrite clip, int cursorTrack, int cursorScene) {
        String timestamp = java.time.Instant.now().toString();
        stateCache.recordWriteClipRefusal();

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
        result.addProperty("clipCreated", clip.created);
        result.addProperty("clipRemoved", false);
        completePending(job, result);
        finishJob(job);
    }

    private void failJob(WriteJob job, ClipWrite clip, Exception cause) {
        String timestamp = java.time.Instant.now().toString();
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        stateCache.recordWriteClipRefusal();

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
        data.addProperty("clipCreated", clip.created);
        data.addProperty("clipRemoved", false);
        data.addProperty("leftoverReason", clip.created
            ? "a failed write may have written something, so this path removes nothing"
            : null);
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
