package dev.bcrick.secondo.handlers;

import com.google.gson.*;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.rpc.JsonRpcError;
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
        // The response reports how many notes were ACCEPTED. It does not and cannot report that
        // they landed; rpc-api-reference.md says so in those words, and Phase 29 is what changes
        // it. Do not add a field here claiming otherwise.
        enqueueWrite(new WriteJob("macro/writeClip",
            List.of(new ClipWrite(trackIndex, sceneIndex, lengthBeats, stepSize, notes, name))));

        JsonObject result = new JsonObject();
        result.addProperty("count", notes.size());
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
        int index;

        WriteJob(String method, List<ClipWrite> clips) {
            this.method = method;
            this.clips = clips;
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
                createClip(clip.trackIndex, clip.sceneIndex, clip.lengthBeats);
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

    private void refuseJob(WriteJob job, ClipWrite clip, int observedTrack, int observedScene,
                           long elapsedMs) {
        String timestamp = java.time.Instant.now().toString();
        stateCache.recordWriteClipRefusal(timestamp, job.method, JsonRpcError.CURSOR_MISMATCH,
            MARKER_CURSOR_MISMATCH, clip.trackIndex, clip.sceneIndex, observedTrack, observedScene,
            clip.notes.size());

        errorLog.accept(MARKER_CURSOR_MISMATCH + " " + timestamp + " " + job.method
            + " code=" + JsonRpcError.CURSOR_MISMATCH
            + " requested=" + clip.label()
            + " observed=" + position(observedTrack, observedScene)
            + " notes=" + clip.notes.size()
            + " ceilingMs=" + CURSOR_VERIFY_CEILING_MS
            + " waitedMs=" + elapsedMs
            + " — REFUSED: the cursor clip was not on the slot this write named, so nothing was"
            + " written. No slot was modified." + chainSummary(job));
        finishJob(job);
    }

    private void refuseExpressions(WriteJob job, ClipWrite clip, int observedTrack, int observedScene) {
        String timestamp = java.time.Instant.now().toString();
        stateCache.recordWriteClipRefusal(timestamp, job.method, JsonRpcError.CURSOR_MISMATCH,
            MARKER_CURSOR_MISMATCH, clip.trackIndex, clip.sceneIndex, observedTrack, observedScene,
            clip.notes.size());

        errorLog.accept(MARKER_CURSOR_MISMATCH + " " + timestamp + " " + job.method
            + " code=" + JsonRpcError.CURSOR_MISMATCH
            + " requested=" + clip.label()
            + " observed=" + position(observedTrack, observedScene)
            + " notes=" + clip.notes.size()
            + " — the notes landed on " + clip.label() + " but the cursor moved before their"
            + " expressions could be applied, so the EXPRESSIONS were refused rather than written"
            + " to the wrong clip." + chainSummary(job));

        // The notes themselves did land; say so, and stop the chain.
        job.landed.add(describe(clip, "refused"));
        job.index++;
        finishJob(job);
    }

    private void failJob(WriteJob job, ClipWrite clip, Exception cause) {
        String timestamp = java.time.Instant.now().toString();
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        stateCache.recordWriteClipRefusal(timestamp, job.method, JsonRpcError.NOTE_WRITE_FAILED,
            MARKER_NOTE_WRITE_FAILED, clip.trackIndex, clip.sceneIndex,
            stateCache.getClipCursorTrackPosition(), stateCache.getClipCursorSceneIndex(),
            clip.notes.size());

        errorLog.accept(MARKER_NOTE_WRITE_FAILED + " " + timestamp + " " + job.method
            + " code=" + JsonRpcError.NOTE_WRITE_FAILED
            + " requested=" + clip.label()
            + " notes=" + clip.notes.size()
            + " — the cursor was on the named slot and the write itself failed: " + reason
            + chainSummary(job));
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
