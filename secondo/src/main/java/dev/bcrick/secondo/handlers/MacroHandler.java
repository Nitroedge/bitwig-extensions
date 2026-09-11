package dev.bcrick.secondo.handlers;

import com.google.gson.*;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.rpc.TaskScheduler;

import static dev.bcrick.secondo.rpc.JsonParamValidator.*;

public class MacroHandler {

    private static final long FLUSH_DELAY_MS = 100;

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

    public MacroHandler(JsonRpcDispatcher dispatcher, StateCache stateCache, TaskScheduler scheduler) {
        this.dispatcher = dispatcher;
        this.stateCache = stateCache;
        this.scheduler = scheduler;
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

        // Phase 1 (this flush cycle): create clip and request cursor move
        createClip(trackIndex, sceneIndex, lengthBeats);
        forceSelectClip(trackIndex, sceneIndex);

        // Phase 2 (next flush cycle): cursor has followed, now write notes
        scheduler.schedule(() -> {
            try {
                writeNotesToCursor(stepSize, notes, name);
            } catch (Exception e) {
                // Deferred write failed — logged but not propagated to caller
            }
        }, FLUSH_DELAY_MS);

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

        // Phase 1 (this flush cycle): create all clips and select the first one
        for (JsonElement clipEl : clips) {
            JsonObject clip = clipEl.getAsJsonObject();
            int trackIndex = requireInt(clip, "trackIndex");
            int lengthBeats = requireInt(clip, "lengthBeats");
            createClip(trackIndex, slotIndex, lengthBeats);
        }

        // Select the first clip — cursor will follow in next flush cycle
        JsonObject firstClip = clips.get(0).getAsJsonObject();
        forceSelectClip(requireInt(firstClip, "trackIndex"), slotIndex);

        // Phase 2+: chain clip writes across flush cycles
        // Each clip needs: (flush N) write notes + select next clip → (flush N+1) write next
        for (int i = 0; i < clips.size(); i++) {
            JsonObject clip = clips.get(i).getAsJsonObject();
            double stepSize = requireDouble(clip, "stepSize");
            JsonArray notes = requireArray(clip, "notes");
            String clipName = clip.has("name") && !clip.get("name").isJsonNull()
                ? clip.get("name").getAsString() : null;

            boolean isLast = (i == clips.size() - 1);
            int nextClipTrackIndex = isLast ? -1
                : requireInt(clips.get(i + 1).getAsJsonObject(), "trackIndex");

            long writeDelay = FLUSH_DELAY_MS * (2L * i + 1);
            final int sceneIdx = slotIndex;
            final int nextTrack = nextClipTrackIndex;
            scheduler.schedule(() -> {
                try {
                    writeNotesToCursor(stepSize, notes, clipName);
                    if (nextTrack >= 0) {
                        forceSelectClip(nextTrack, sceneIdx);
                    }
                } catch (Exception e) {
                    // Deferred write failed
                }
            }, writeDelay);
        }

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

        // Apply expression properties (deferred — notes must exist first)
        applyNoteExpressions(notes);
    }

    private void applyNoteExpressions(JsonArray notes) {
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

        // Schedule expression calls if any were collected
        boolean hasExpressions = chanceNotes.size() > 0
            || !expressionsByProperty.isEmpty()
            || repeatNotes.size() > 0
            || occurrenceNotes.size() > 0
            || recurrenceNotes.size() > 0;

        if (!hasExpressions) return;

        scheduler.schedule(() -> {
            try {
                if (chanceNotes.size() > 0) {
                    JsonObject p = new JsonObject();
                    p.add("notes", chanceNotes);
                    dispatcher.handleInternal("clip/setChance", p);
                }
                for (JsonArray exprNotes : expressionsByProperty.values()) {
                    JsonObject p = new JsonObject();
                    p.add("notes", exprNotes);
                    dispatcher.handleInternal("clip/setNoteExpressions", p);
                }
                if (repeatNotes.size() > 0) {
                    JsonObject p = new JsonObject();
                    p.add("notes", repeatNotes);
                    dispatcher.handleInternal("clip/setNoteRepeat", p);
                }
                if (occurrenceNotes.size() > 0) {
                    JsonObject p = new JsonObject();
                    p.add("notes", occurrenceNotes);
                    dispatcher.handleInternal("clip/setNoteOccurrence", p);
                }
                if (recurrenceNotes.size() > 0) {
                    JsonObject p = new JsonObject();
                    p.add("notes", recurrenceNotes);
                    dispatcher.handleInternal("clip/setNoteRecurrence", p);
                }
            } catch (Exception e) {
                // Deferred expression application failed
            }
        }, FLUSH_DELAY_MS);
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
