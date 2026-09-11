package dev.bcrick.secondo.cli;

import com.google.gson.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

@Command(
    name = "song",
    description = "Song dump and rebuild operations.",
    mixinStandardHelpOptions = true,
    subcommands = {
        SongCommand.DumpCommand.class,
        SongCommand.RebuildCommand.class
    }
)
class SongCommand {

    @ParentCommand
    SecondoCli parent;

    // --- Dump ---

    @Command(name = "dump", description = "Export the current Bitwig session to a song JSON file.",
             mixinStandardHelpOptions = true)
    static class DumpCommand implements Runnable {

        @ParentCommand
        private SongCommand songParent;

        @Option(names = {"--output", "-o"}, description = "Output file path (default: stdout)")
        private String outputPath;

        private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
        private static final int SCENE_BANK_SIZE = 5;
        private static final int TRACK_BANK_SIZE = 8;
        private static final int CLIP_SETTLE_MS = 300;
        private static final int SCROLL_SETTLE_MS = 500;

        @Override
        public void run() {
            try {
                RpcClient client = songParent.parent.createClient();
                PrintStream log = System.err; // progress to stderr so stdout is clean JSON

                // Step 1: Get base snapshot
                log.println("[1/5] Reading session snapshot...");
                JsonObject snapshot = client.call("session/snapshot", null).getAsJsonObject();

                JsonObject transport = snapshot.getAsJsonObject("transport");
                JsonObject tracksSection = snapshot.getAsJsonObject("tracks");
                JsonObject scenesSection = snapshot.getAsJsonObject("scenes");
                JsonObject master = snapshot.getAsJsonObject("master");
                JsonObject device = snapshot.getAsJsonObject("device");
                JsonObject clip = snapshot.getAsJsonObject("clip");

                int trackCount = tracksSection.getAsJsonArray("tracks").size();
                int sceneItemCount = scenesSection.get("itemCount").getAsInt();

                // Step 2: Collect all scenes (may need to scroll)
                log.println("[2/5] Reading " + sceneItemCount + " scenes...");
                JsonArray allScenes = new JsonArray();
                JsonArray[][] clipSlots = new JsonArray[TRACK_BANK_SIZE][];
                for (int t = 0; t < TRACK_BANK_SIZE; t++) {
                    clipSlots[t] = new JsonArray[sceneItemCount];
                }

                int sceneBanks = (sceneItemCount + SCENE_BANK_SIZE - 1) / SCENE_BANK_SIZE;
                for (int bank = 0; bank < sceneBanks; bank++) {
                    int scrollPos = bank * SCENE_BANK_SIZE;
                    if (bank > 0) {
                        JsonObject scrollParams = new JsonObject();
                        scrollParams.addProperty("position", scrollPos);
                        client.call("sceneBank/scrollTo", scrollParams);
                        Thread.sleep(SCROLL_SETTLE_MS);
                        snapshot = client.call("session/snapshot", null).getAsJsonObject();
                        tracksSection = snapshot.getAsJsonObject("tracks");
                        scenesSection = snapshot.getAsJsonObject("scenes");
                    }

                    JsonArray bankScenes = scenesSection.getAsJsonArray("scenes");
                    JsonArray bankTracks = tracksSection.getAsJsonArray("tracks");

                    int scenesInBank = Math.min(SCENE_BANK_SIZE, sceneItemCount - scrollPos);
                    for (int s = 0; s < scenesInBank; s++) {
                        JsonObject scene = bankScenes.get(s).getAsJsonObject();
                        JsonObject sceneOut = new JsonObject();
                        sceneOut.addProperty("absoluteIndex", scrollPos + s);
                        sceneOut.addProperty("name", scene.get("name").getAsString());
                        sceneOut.add("color", scene.getAsJsonObject("color"));
                        allScenes.add(sceneOut);
                    }

                    // Capture clip hasContent per track in this bank window
                    for (int t = 0; t < Math.min(trackCount, TRACK_BANK_SIZE); t++) {
                        JsonObject track = bankTracks.get(t).getAsJsonObject();
                        JsonArray trackClips = track.getAsJsonArray("clips");
                        for (int s = 0; s < scenesInBank; s++) {
                            clipSlots[t][scrollPos + s] = new JsonArray();
                            JsonObject slotInfo = trackClips.get(s).getAsJsonObject();
                            if (slotInfo.get("hasContent").getAsBoolean()) {
                                // Store slot info — we'll read notes later
                                JsonObject marker = new JsonObject();
                                marker.addProperty("hasContent", true);
                                marker.addProperty("name", slotInfo.get("name").getAsString());
                                marker.add("color", slotInfo.getAsJsonObject("color"));
                                clipSlots[t][scrollPos + s].add(marker);
                            }
                        }
                    }
                }

                // Scroll back to 0
                if (sceneBanks > 1) {
                    JsonObject scrollParams = new JsonObject();
                    scrollParams.addProperty("position", 0);
                    client.call("sceneBank/scrollTo", scrollParams);
                    Thread.sleep(SCROLL_SETTLE_MS);
                }

                // Step 3: Read notes from each clip with content
                log.println("[3/5] Reading clip notes...");
                JsonArray allClips = new JsonArray();
                int clipCount = 0;
                int totalNotes = 0;

                for (int sceneAbs = 0; sceneAbs < sceneItemCount; sceneAbs++) {
                    // Ensure correct scene bank window
                    int neededScrollPos = (sceneAbs / SCENE_BANK_SIZE) * SCENE_BANK_SIZE;
                    int slotIndex = sceneAbs - neededScrollPos;

                    // Scroll if needed
                    JsonObject scrollParams = new JsonObject();
                    scrollParams.addProperty("position", neededScrollPos);
                    client.call("sceneBank/scrollTo", scrollParams);

                    for (int t = 0; t < Math.min(trackCount, TRACK_BANK_SIZE); t++) {
                        if (clipSlots[t][sceneAbs] == null || clipSlots[t][sceneAbs].isEmpty()) {
                            continue;
                        }

                        // Select this clip
                        JsonObject selectParams = new JsonObject();
                        selectParams.addProperty("trackIndex", t);
                        selectParams.addProperty("slotIndex", slotIndex);
                        selectParams.addProperty("force", true);
                        client.call("clip/select", selectParams);
                        Thread.sleep(CLIP_SETTLE_MS);

                        // Read clip metadata from snapshot
                        JsonObject clipSnap = client.call("session/snapshot", null)
                            .getAsJsonObject().getAsJsonObject("clip");

                        // Read notes
                        JsonElement notesResult = client.call("clip/getNotes", null);
                        JsonArray notes = notesResult.getAsJsonArray();

                        JsonObject clipInfo = clipSlots[t][sceneAbs].get(0).getAsJsonObject();

                        JsonObject clipOut = new JsonObject();
                        clipOut.addProperty("track", t);
                        clipOut.addProperty("scene", slotIndex);
                        clipOut.addProperty("sceneAbsolute", sceneAbs);
                        clipOut.addProperty("name", clipInfo.get("name").getAsString());
                        clipOut.addProperty("lengthBeats", clipSnap.get("loopLength").getAsDouble());
                        clipOut.addProperty("stepSize", clipSnap.get("stepSize").getAsDouble());
                        clipOut.add("color", clipInfo.getAsJsonObject("color"));
                        clipOut.add("notes", notes);

                        // Capture clip launch settings (non-default only)
                        JsonObject launchSettings = new JsonObject();
                        String launchQuant = clipSnap.has("launchQuantization")
                            ? clipSnap.get("launchQuantization").getAsString() : "default";
                        String launchMode = clipSnap.has("launchMode")
                            ? clipSnap.get("launchMode").getAsString() : "default";
                        boolean shuffle = clipSnap.has("shuffle")
                            && clipSnap.get("shuffle").getAsBoolean();
                        double accent = clipSnap.has("accent")
                            ? clipSnap.get("accent").getAsDouble() : 0.0;
                        boolean useLoopRef = clipSnap.has("useLoopStartAsQuantizationReference")
                            && clipSnap.get("useLoopStartAsQuantizationReference").getAsBoolean();

                        if (!"default".equals(launchQuant))
                            launchSettings.addProperty("launchQuantization", launchQuant);
                        if (!"default".equals(launchMode))
                            launchSettings.addProperty("launchMode", launchMode);
                        if (shuffle)
                            launchSettings.addProperty("shuffle", true);
                        if (accent > 0.0)
                            launchSettings.addProperty("accent", accent);
                        if (useLoopRef)
                            launchSettings.addProperty("useLoopStartAsQuantizationReference", true);

                        if (launchSettings.size() > 0) {
                            clipOut.add("launchSettings", launchSettings);
                        }

                        // Capture clip playback settings (non-default only)
                        JsonObject playbackSettings = new JsonObject();
                        double playStart = clipSnap.has("playStart")
                            ? clipSnap.get("playStart").getAsDouble() : 0.0;
                        double playStop = clipSnap.has("playStop")
                            ? clipSnap.get("playStop").getAsDouble() : 0.0;
                        double loopStart = clipSnap.has("loopStart")
                            ? clipSnap.get("loopStart").getAsDouble() : 0.0;
                        double loopLength = clipSnap.has("loopLength")
                            ? clipSnap.get("loopLength").getAsDouble() : 0.0;
                        boolean loopEnabled = !clipSnap.has("isLoopEnabled")
                            || clipSnap.get("isLoopEnabled").getAsBoolean();

                        if (playStart > 0.0)
                            playbackSettings.addProperty("playStart", playStart);
                        if (playStop > 0.0)
                            playbackSettings.addProperty("playStop", playStop);
                        if (loopStart > 0.0)
                            playbackSettings.addProperty("loopStart", loopStart);
                        if (loopLength > 0.0)
                            playbackSettings.addProperty("loopLength", loopLength);
                        if (!loopEnabled)
                            playbackSettings.addProperty("isLoopEnabled", false);

                        if (playbackSettings.size() > 0) {
                            clipOut.add("playbackSettings", playbackSettings);
                        }

                        allClips.add(clipOut);

                        clipCount++;
                        totalNotes += notes.size();
                        log.println("  [" + clipCount + "] Track " + t + " / Scene " + sceneAbs
                            + " (" + notes.size() + " notes)");
                    }
                }

                // Step 4: Read track info (instruments, drums)
                log.println("[4/5] Reading track instruments...");
                JsonArray instruments = new JsonArray();
                JsonArray drumPadMap = new JsonArray();

                // Select each track and read device info
                for (int t = 0; t < trackCount; t++) {
                    JsonObject selectParams = new JsonObject();
                    selectParams.addProperty("index", t);
                    client.call("track/select", selectParams);
                    Thread.sleep(CLIP_SETTLE_MS);

                    // Read device info
                    JsonObject devSnap = client.call("session/snapshot", null)
                        .getAsJsonObject().getAsJsonObject("device");
                    String deviceName = devSnap.get("name").getAsString();
                    String presetName = devSnap.get("presetName").getAsString();

                    if (deviceName != null && !deviceName.isEmpty()) {
                        JsonObject inst = new JsonObject();
                        inst.addProperty("track", t);
                        inst.addProperty("device", deviceName);
                        inst.addProperty("preset", presetName != null ? presetName : "");

                        // Discover device parameters (all pages)
                        try {
                            client.call("device/discoverAll", null);
                            Thread.sleep(CLIP_SETTLE_MS * 2); // discovery needs time to iterate pages
                            JsonObject discoveryParams = new JsonObject();
                            discoveryParams.addProperty("format", "preset");
                            JsonElement discoveryResult = client.call("device/getDiscoveryResult", discoveryParams);
                            if (discoveryResult.isJsonObject()) {
                                JsonObject disc = discoveryResult.getAsJsonObject();
                                if (disc.has("pages") && disc.getAsJsonArray("pages").size() > 0) {
                                    inst.add("pages", disc.getAsJsonArray("pages"));
                                    int paramCount = 0;
                                    for (JsonElement pageEl : disc.getAsJsonArray("pages")) {
                                        paramCount += pageEl.getAsJsonObject().getAsJsonArray("params").size();
                                    }
                                    log.println("  Track " + t + ": " + deviceName
                                        + " — " + disc.getAsJsonArray("pages").size() + " pages, "
                                        + paramCount + " params");
                                }
                            }
                        } catch (Exception e) {
                            log.println("  Warning: Could not discover params for track " + t
                                + " (" + deviceName + "): " + e.getMessage());
                        }

                        instruments.add(inst);
                    }

                    // Check for drum pads
                    if (devSnap.has("hasDrumPads") && devSnap.get("hasDrumPads").getAsBoolean()) {
                        try {
                            JsonElement padsResult = client.call("device/getDrumPads", null);
                            if (padsResult.isJsonArray()) {
                                for (JsonElement pad : padsResult.getAsJsonArray()) {
                                    drumPadMap.add(pad);
                                }
                            }
                        } catch (IOException e) {
                            log.println("  Warning: Could not read drum pads for track " + t);
                        }
                    }
                }

                // Step 5: Read cue markers
                log.println("[5/5] Reading cue markers...");
                JsonObject arrangementSnap = client.call("session/snapshot", null)
                    .getAsJsonObject().getAsJsonObject("arrangement");
                JsonArray cueMarkers = new JsonArray();
                if (arrangementSnap != null && arrangementSnap.has("cueMarkers")) {
                    cueMarkers = arrangementSnap.getAsJsonArray("cueMarkers");
                }

                // Assemble song JSON
                JsonObject song = new JsonObject();

                // Meta
                JsonObject meta = new JsonObject();
                meta.addProperty("formatVersion", "1");
                meta.addProperty("name", "");
                meta.addProperty("key", "");
                meta.addProperty("created", LocalDate.now().toString());
                meta.addProperty("version", "secondo-v0.21.1");
                song.add("meta", meta);

                // Transport
                JsonObject transportOut = new JsonObject();
                transportOut.addProperty("tempo", transport.get("tempo").getAsDouble());
                transportOut.addProperty("timeSignature", transport.get("timeSignature").getAsString());
                song.add("transport", transportOut);

                // Tracks
                JsonArray tracksOut = new JsonArray();
                JsonArray bankTracks = tracksSection.getAsJsonArray("tracks");
                for (int t = 0; t < Math.min(trackCount, TRACK_BANK_SIZE); t++) {
                    // Re-read from original snapshot for accurate track data
                    // (we may have scrolled scenes, but tracks don't change)
                    JsonObject origTrack;
                    if (t < bankTracks.size()) {
                        origTrack = bankTracks.get(t).getAsJsonObject();
                    } else {
                        continue;
                    }
                    JsonObject trackOut = new JsonObject();
                    trackOut.addProperty("index", t);
                    trackOut.addProperty("name", origTrack.get("name").getAsString());
                    trackOut.addProperty("volume", origTrack.get("volume").getAsDouble());
                    trackOut.addProperty("pan", origTrack.get("pan").getAsDouble());
                    trackOut.addProperty("mute", origTrack.get("mute").getAsBoolean());
                    trackOut.addProperty("solo", origTrack.get("solo").getAsBoolean());
                    trackOut.add("color", origTrack.getAsJsonObject("color"));
                    tracksOut.add(trackOut);
                }
                song.add("tracks", tracksOut);

                // Master
                JsonObject masterOut = new JsonObject();
                masterOut.addProperty("volume", master.get("volume").getAsDouble());
                masterOut.addProperty("pan", master.get("pan").getAsDouble());
                song.add("master", masterOut);

                // Scenes
                song.add("scenes", allScenes);

                // Clips
                song.add("clips", allClips);

                // Instruments + Drum pad map
                song.add("instruments", instruments);
                song.add("drumPadMap", drumPadMap);

                // Cue markers
                song.add("cueMarkers", cueMarkers);

                // Stats
                JsonObject stats = new JsonObject();
                stats.addProperty("totalClips", clipCount);
                stats.addProperty("totalNotes", totalNotes);
                stats.addProperty("totalScenes", sceneItemCount);
                stats.addProperty("totalCueMarkers", cueMarkers.size());
                song.add("stats", stats);

                // Output
                String json = PRETTY.toJson(song);
                if (outputPath != null) {
                    Files.writeString(Path.of(outputPath), json);
                    log.println("Song exported to: " + outputPath);
                } else {
                    System.out.println(json);
                }

                log.println("Done! " + clipCount + " clips, " + totalNotes + " notes, "
                    + sceneItemCount + " scenes.");

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // --- Rebuild ---

    @Command(name = "rebuild", description = "Rebuild a Bitwig session from a song JSON file.",
             mixinStandardHelpOptions = true)
    static class RebuildCommand implements Runnable {

        @ParentCommand
        private SongCommand songParent;

        @Parameters(index = "0", description = "Path to the song JSON file")
        private String filePath;

        private static final int SCENE_SETTLE_MS = 500;
        private static final int CLIP_WRITE_MS = 200;
        private static final int CLIP_COLOR_MS = 50;
        private static final int CUE_MARKER_MS = 200;
        private static final int SCROLL_SETTLE_MS = 300;
        private static final int SCENE_BANK_SIZE = 5;

        @Override
        public void run() {
            try {
                RpcClient client = songParent.parent.createClient();
                PrintStream log = System.err;

                // Read and parse song JSON
                String jsonStr = Files.readString(Path.of(filePath));
                JsonObject song = JsonParser.parseString(jsonStr).getAsJsonObject();

                // Validate format version (accept missing for legacy files)
                if (song.has("meta") && song.getAsJsonObject("meta").has("formatVersion")) {
                    String version = song.getAsJsonObject("meta").get("formatVersion").getAsString();
                    if (!"1".equals(version)) {
                        log.println("Error: Unsupported format version: " + version);
                        System.exit(1);
                    }
                }

                JsonObject transport = song.getAsJsonObject("transport");
                JsonArray tracks = song.getAsJsonArray("tracks");
                JsonObject master = song.getAsJsonObject("master");
                JsonArray scenes = song.getAsJsonArray("scenes");
                JsonArray clips = song.getAsJsonArray("clips");
                JsonArray cueMarkers = song.has("cueMarkers") ? song.getAsJsonArray("cueMarkers") : new JsonArray();

                int totalSteps = 8;
                JsonArray instruments = song.has("instruments")
                    ? song.getAsJsonArray("instruments") : new JsonArray();
                int step = 0;

                // --- Step 1: Transport ---
                step++;
                log.println("[" + step + "/" + totalSteps + "] Setting transport: "
                    + transport.get("tempo").getAsDouble() + " BPM, "
                    + transport.get("timeSignature").getAsString());

                JsonObject tempoParams = new JsonObject();
                tempoParams.addProperty("bpm", transport.get("tempo").getAsDouble());
                client.call("transport/setTempo", tempoParams);

                // --- Step 2: Scenes ---
                step++;
                log.println("[" + step + "/" + totalSteps + "] Creating " + scenes.size() + " scenes...");

                // Build scene array for macro/setupScenes
                JsonArray sceneSetup = new JsonArray();
                for (int i = 0; i < scenes.size(); i++) {
                    JsonObject scene = scenes.get(i).getAsJsonObject();
                    JsonObject sceneEntry = new JsonObject();
                    sceneEntry.addProperty("index", i);
                    sceneEntry.addProperty("name", scene.get("name").getAsString());
                    sceneSetup.add(sceneEntry);
                }

                JsonObject setupParams = new JsonObject();
                setupParams.add("scenes", sceneSetup);
                client.call("macro/setupScenes", setupParams);
                Thread.sleep(SCENE_SETTLE_MS);

                // Set scene colors (need to scroll through scene bank)
                for (int i = 0; i < scenes.size(); i++) {
                    JsonObject scene = scenes.get(i).getAsJsonObject();
                    if (!scene.has("color")) continue;

                    int scrollPos = (i / SCENE_BANK_SIZE) * SCENE_BANK_SIZE;
                    int bankIndex = i - scrollPos;

                    JsonObject scrollParams = new JsonObject();
                    scrollParams.addProperty("position", scrollPos);
                    client.call("sceneBank/scrollTo", scrollParams);

                    JsonObject colorObj = scene.getAsJsonObject("color");
                    JsonObject colorParams = new JsonObject();
                    colorParams.addProperty("index", bankIndex);
                    colorParams.addProperty("r", colorObj.get("r").getAsFloat());
                    colorParams.addProperty("g", colorObj.get("g").getAsFloat());
                    colorParams.addProperty("b", colorObj.get("b").getAsFloat());
                    client.call("scene/setColor", colorParams);
                }
                Thread.sleep(SCROLL_SETTLE_MS);

                // --- Step 3: Clips ---
                step++;
                log.println("[" + step + "/" + totalSteps + "] Writing " + clips.size() + " clips...");

                for (int c = 0; c < clips.size(); c++) {
                    JsonObject clip = clips.get(c).getAsJsonObject();
                    int trackIndex = clip.get("track").getAsInt();
                    int sceneAbsolute = clip.get("sceneAbsolute").getAsInt();
                    JsonArray notes = clip.getAsJsonArray("notes");

                    // Default values for legacy format
                    int lengthBeats = clip.has("lengthBeats")
                        ? (int) clip.get("lengthBeats").getAsDouble() : 16;
                    double stepSize = clip.has("stepSize")
                        ? clip.get("stepSize").getAsDouble() : 0.25;
                    String clipName = clip.has("name") && !clip.get("name").isJsonNull()
                        ? clip.get("name").getAsString() : null;

                    // Scroll scene bank to make this scene visible
                    int scrollPos = (sceneAbsolute / SCENE_BANK_SIZE) * SCENE_BANK_SIZE;
                    int slotIndex = sceneAbsolute - scrollPos;

                    JsonObject scrollParams = new JsonObject();
                    scrollParams.addProperty("position", scrollPos);
                    client.call("sceneBank/scrollTo", scrollParams);

                    // Strip chance data from notes for setNotes (chance is set separately)
                    JsonArray cleanNotes = new JsonArray();
                    JsonArray chanceNotes = new JsonArray();
                    for (JsonElement noteEl : notes) {
                        JsonObject note = noteEl.getAsJsonObject();
                        JsonObject clean = new JsonObject();
                        clean.addProperty("x", note.get("x").getAsInt());
                        clean.addProperty("y", note.get("y").getAsInt());
                        clean.addProperty("velocity", note.get("velocity").getAsDouble());
                        clean.addProperty("duration", note.get("duration").getAsDouble());
                        cleanNotes.add(clean);

                        if (note.has("chance") && note.get("chance").getAsDouble() < 1.0) {
                            JsonObject cn = new JsonObject();
                            cn.addProperty("x", note.get("x").getAsInt());
                            cn.addProperty("y", note.get("y").getAsInt());
                            cn.addProperty("chance", note.get("chance").getAsDouble());
                            chanceNotes.add(cn);
                        }
                    }

                    // Use macro/writeClip for creation + note writing
                    JsonObject writeParams = new JsonObject();
                    writeParams.addProperty("trackIndex", trackIndex);
                    writeParams.addProperty("sceneIndex", slotIndex);
                    writeParams.addProperty("lengthBeats", lengthBeats);
                    writeParams.addProperty("stepSize", stepSize);
                    writeParams.add("notes", cleanNotes);
                    if (clipName != null) {
                        writeParams.addProperty("name", clipName);
                    }
                    client.call("macro/writeClip", writeParams);
                    Thread.sleep(CLIP_WRITE_MS);

                    // Set chance on notes if any
                    if (!chanceNotes.isEmpty()) {
                        // Need to wait for clip write to complete, then set chance
                        Thread.sleep(CLIP_WRITE_MS);
                        JsonObject chanceParams = new JsonObject();
                        chanceParams.add("notes", chanceNotes);
                        client.call("clip/setChance", chanceParams);
                    }

                    // Set expressive properties if present
                    JsonArray exprNotes = new JsonArray();
                    JsonArray repeatNotes = new JsonArray();
                    JsonArray occurrenceNotes = new JsonArray();
                    JsonArray recurrenceNotes = new JsonArray();

                    for (JsonElement noteEl : notes) {
                        JsonObject note = noteEl.getAsJsonObject();
                        int nx = note.get("x").getAsInt();
                        int ny = note.get("y").getAsInt();

                        // Scalar expressions
                        String[] scalarProps = {"pan", "timbre", "pressure", "gain",
                            "transpose", "releaseVelocity", "velocitySpread"};
                        for (String prop : scalarProps) {
                            if (note.has(prop)) {
                                JsonObject expr = new JsonObject();
                                expr.addProperty("x", nx);
                                expr.addProperty("y", ny);
                                expr.addProperty("property", prop);
                                expr.addProperty("value", note.get(prop).getAsDouble());
                                exprNotes.add(expr);
                            }
                        }
                        // Mute
                        if (note.has("mute") && note.get("mute").getAsBoolean()) {
                            JsonObject expr = new JsonObject();
                            expr.addProperty("x", nx);
                            expr.addProperty("y", ny);
                            expr.addProperty("property", "mute");
                            expr.addProperty("value", 1.0);
                            exprNotes.add(expr);
                        }

                        // Repeat
                        if (note.has("repeat")) {
                            JsonObject rep = note.getAsJsonObject("repeat");
                            JsonObject rn = new JsonObject();
                            rn.addProperty("x", nx);
                            rn.addProperty("y", ny);
                            rn.addProperty("count", rep.get("count").getAsInt());
                            rn.addProperty("curve", rep.get("curve").getAsDouble());
                            rn.addProperty("velocityEnd", rep.get("velocityEnd").getAsDouble());
                            rn.addProperty("velocityCurve", rep.get("velocityCurve").getAsDouble());
                            repeatNotes.add(rn);
                        }

                        // Occurrence
                        if (note.has("occurrence")) {
                            JsonObject on = new JsonObject();
                            on.addProperty("x", nx);
                            on.addProperty("y", ny);
                            on.addProperty("condition", note.get("occurrence").getAsString());
                            occurrenceNotes.add(on);
                        }

                        // Recurrence
                        if (note.has("recurrence")) {
                            JsonObject rec = note.getAsJsonObject("recurrence");
                            JsonObject rn = new JsonObject();
                            rn.addProperty("x", nx);
                            rn.addProperty("y", ny);
                            rn.addProperty("length", rec.get("length").getAsInt());
                            rn.addProperty("mask", rec.get("mask").getAsInt());
                            recurrenceNotes.add(rn);
                        }
                    }

                    if (!exprNotes.isEmpty()) {
                        JsonObject exprParams = new JsonObject();
                        exprParams.add("notes", exprNotes);
                        client.call("clip/setNoteExpressions", exprParams);
                    }
                    if (!repeatNotes.isEmpty()) {
                        JsonObject repParams = new JsonObject();
                        repParams.add("notes", repeatNotes);
                        client.call("clip/setNoteRepeat", repParams);
                    }
                    if (!occurrenceNotes.isEmpty()) {
                        JsonObject occParams = new JsonObject();
                        occParams.add("notes", occurrenceNotes);
                        client.call("clip/setNoteOccurrence", occParams);
                    }
                    if (!recurrenceNotes.isEmpty()) {
                        JsonObject recParams = new JsonObject();
                        recParams.add("notes", recurrenceNotes);
                        client.call("clip/setNoteRecurrence", recParams);
                    }

                    // Restore clip launch settings if present
                    if (clip.has("launchSettings") && !clip.get("launchSettings").isJsonNull()) {
                        JsonObject ls = clip.getAsJsonObject("launchSettings");
                        if (ls.has("launchQuantization")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("quantization", ls.get("launchQuantization").getAsString());
                            client.call("clip/setLaunchQuantization", p);
                        }
                        if (ls.has("launchMode")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("launchMode", ls.get("launchMode").getAsString());
                            client.call("clip/setLaunchMode", p);
                        }
                        if (ls.has("shuffle")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("enabled", ls.get("shuffle").getAsBoolean());
                            client.call("clip/setShuffle", p);
                        }
                        if (ls.has("accent")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("value", ls.get("accent").getAsDouble());
                            client.call("clip/setAccent", p);
                        }
                        if (ls.has("useLoopStartAsQuantizationReference")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("enabled", ls.get("useLoopStartAsQuantizationReference").getAsBoolean());
                            client.call("clip/setUseLoopStartAsQuantizationReference", p);
                        }
                    }

                    // Restore clip playback settings if present
                    if (clip.has("playbackSettings") && !clip.get("playbackSettings").isJsonNull()) {
                        JsonObject ps = clip.getAsJsonObject("playbackSettings");
                        if (ps.has("playStart")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("beats", ps.get("playStart").getAsDouble());
                            client.call("clip/setPlayStart", p);
                        }
                        if (ps.has("playStop")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("beats", ps.get("playStop").getAsDouble());
                            client.call("clip/setPlayStop", p);
                        }
                        if (ps.has("loopStart")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("beats", ps.get("loopStart").getAsDouble());
                            client.call("clip/setLoopStart", p);
                        }
                        if (ps.has("loopLength")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("beats", ps.get("loopLength").getAsDouble());
                            client.call("clip/setLoopLength", p);
                        }
                        if (ps.has("isLoopEnabled")) {
                            JsonObject p = new JsonObject();
                            p.addProperty("enabled", ps.get("isLoopEnabled").getAsBoolean());
                            client.call("clip/setLoopEnabled", p);
                        }
                    }

                    log.println("  [" + (c + 1) + "/" + clips.size() + "] Track " + trackIndex
                        + " / Scene " + sceneAbsolute + " (" + notes.size() + " notes)");
                }

                // --- Step 4: Clip colors ---
                step++;
                log.println("[" + step + "/" + totalSteps + "] Setting clip colors...");

                for (int c = 0; c < clips.size(); c++) {
                    JsonObject clip = clips.get(c).getAsJsonObject();
                    int trackIndex = clip.get("track").getAsInt();
                    int sceneAbsolute = clip.get("sceneAbsolute").getAsInt();

                    // Determine color: use clip color if present, else scene color
                    JsonObject colorObj = null;
                    if (clip.has("color") && !clip.get("color").isJsonNull()) {
                        colorObj = clip.getAsJsonObject("color");
                    } else {
                        // Fall back to scene color
                        for (JsonElement sceneEl : scenes) {
                            JsonObject scene = sceneEl.getAsJsonObject();
                            int absIdx = scene.has("absoluteIndex")
                                ? scene.get("absoluteIndex").getAsInt()
                                : scene.get("index").getAsInt();
                            if (absIdx == sceneAbsolute && scene.has("color")) {
                                colorObj = scene.getAsJsonObject("color");
                                break;
                            }
                        }
                    }
                    if (colorObj == null) continue;

                    // Scroll and select the clip
                    int scrollPos = (sceneAbsolute / SCENE_BANK_SIZE) * SCENE_BANK_SIZE;
                    int slotIndex = sceneAbsolute - scrollPos;

                    JsonObject scrollParams = new JsonObject();
                    scrollParams.addProperty("position", scrollPos);
                    client.call("sceneBank/scrollTo", scrollParams);

                    JsonObject selectParams = new JsonObject();
                    selectParams.addProperty("trackIndex", trackIndex);
                    selectParams.addProperty("slotIndex", slotIndex);
                    selectParams.addProperty("force", true);
                    client.call("clip/select", selectParams);
                    Thread.sleep(CLIP_COLOR_MS);

                    JsonObject colorParams = new JsonObject();
                    colorParams.addProperty("r", colorObj.get("r").getAsFloat());
                    colorParams.addProperty("g", colorObj.get("g").getAsFloat());
                    colorParams.addProperty("b", colorObj.get("b").getAsFloat());
                    client.call("clip/setColor", colorParams);
                    Thread.sleep(CLIP_COLOR_MS);
                }

                // --- Step 5: Track mix ---
                step++;
                log.println("[" + step + "/" + totalSteps + "] Mixing " + tracks.size() + " tracks...");

                for (JsonElement trackEl : tracks) {
                    JsonObject track = trackEl.getAsJsonObject();
                    int idx = track.get("index").getAsInt();

                    JsonObject volParams = new JsonObject();
                    volParams.addProperty("index", idx);
                    volParams.addProperty("value", track.get("volume").getAsDouble());
                    client.call("track/setVolume", volParams);

                    JsonObject panParams = new JsonObject();
                    panParams.addProperty("index", idx);
                    panParams.addProperty("value", track.get("pan").getAsDouble());
                    client.call("track/setPan", panParams);

                    if (track.has("mute")) {
                        JsonObject muteParams = new JsonObject();
                        muteParams.addProperty("index", idx);
                        muteParams.addProperty("value", track.get("mute").getAsBoolean());
                        client.call("track/setMute", muteParams);
                    }

                    if (track.has("solo")) {
                        JsonObject soloParams = new JsonObject();
                        soloParams.addProperty("index", idx);
                        soloParams.addProperty("value", track.get("solo").getAsBoolean());
                        client.call("track/setSolo", soloParams);
                    }

                    if (track.has("color")) {
                        JsonObject colorObj = track.getAsJsonObject("color");
                        JsonObject colorParams = new JsonObject();
                        colorParams.addProperty("index", idx);
                        colorParams.addProperty("r", colorObj.get("r").getAsFloat());
                        colorParams.addProperty("g", colorObj.get("g").getAsFloat());
                        colorParams.addProperty("b", colorObj.get("b").getAsFloat());
                        client.call("track/setColor", colorParams);
                    }
                }

                // --- Step 6: Device parameters ---
                step++;
                int paramRestoredCount = 0;
                for (JsonElement instEl : instruments) {
                    JsonObject inst = instEl.getAsJsonObject();
                    if (!inst.has("pages") || inst.get("pages").isJsonNull()) continue;
                    JsonArray pages = inst.getAsJsonArray("pages");
                    if (pages.isEmpty()) continue;

                    int trackIdx = inst.get("track").getAsInt();
                    String deviceName = inst.get("device").getAsString();

                    // Select the track
                    JsonObject selectParams = new JsonObject();
                    selectParams.addProperty("index", trackIdx);
                    client.call("track/select", selectParams);
                    Thread.sleep(CLIP_WRITE_MS);

                    // Set parameters on the cursor device
                    JsonObject setParams = new JsonObject();
                    setParams.add("pages", pages);
                    client.call("device/setParameters", setParams);

                    // Wait for page writes: 100ms per page
                    Thread.sleep(100L * pages.size() + CLIP_WRITE_MS);

                    int paramCount = 0;
                    for (JsonElement pageEl : pages) {
                        paramCount += pageEl.getAsJsonObject().getAsJsonArray("params").size();
                    }
                    paramRestoredCount++;
                    log.println("  Track " + trackIdx + ": " + deviceName
                        + " — " + pages.size() + " pages, " + paramCount + " params");
                }
                if (paramRestoredCount > 0) {
                    log.println("[" + step + "/" + totalSteps + "] Restored device parameters for "
                        + paramRestoredCount + " instruments.");
                } else {
                    log.println("[" + step + "/" + totalSteps + "] No device parameters to restore.");
                }

                // --- Step 7: Master mix ---
                step++;
                log.println("[" + step + "/" + totalSteps + "] Setting master mix...");

                JsonObject masterVolParams = new JsonObject();
                masterVolParams.addProperty("value", master.get("volume").getAsDouble());
                client.call("master/setVolume", masterVolParams);

                JsonObject masterPanParams = new JsonObject();
                masterPanParams.addProperty("value", master.get("pan").getAsDouble());
                client.call("master/setPan", masterPanParams);

                // --- Step 7: Cue markers ---
                step++;
                if (!cueMarkers.isEmpty()) {
                    log.println("[" + step + "/" + totalSteps + "] Adding " + cueMarkers.size()
                        + " cue markers...");

                    for (int m = 0; m < cueMarkers.size(); m++) {
                        JsonObject marker = cueMarkers.get(m).getAsJsonObject();
                        double position = marker.get("position").getAsDouble();
                        String name = marker.get("name").getAsString();

                        // Set transport to marker position
                        JsonObject posParams = new JsonObject();
                        posParams.addProperty("beats", position);
                        client.call("transport/setPosition", posParams);
                        Thread.sleep(CUE_MARKER_MS);

                        // Add marker at playhead
                        client.call("cueMarker/addAtPlayhead", null);
                        Thread.sleep(CUE_MARKER_MS);

                        // Rename the marker (it's at bank index m, assuming bank is large enough)
                        // Need to scroll cue marker bank if m >= 16
                        int bankIdx = m % 16;
                        if (m > 0 && bankIdx == 0) {
                            JsonObject scrollParams = new JsonObject();
                            scrollParams.addProperty("amount", 16);
                            client.call("cueMarkerBank/scrollBy", scrollParams);
                            Thread.sleep(CUE_MARKER_MS);
                        }

                        JsonObject renameParams = new JsonObject();
                        renameParams.addProperty("index", bankIdx);
                        renameParams.addProperty("name", name);
                        client.call("cueMarker/rename", renameParams);
                    }

                    // Reset transport to start
                    JsonObject posParams = new JsonObject();
                    posParams.addProperty("beats", 0.0);
                    client.call("transport/setPosition", posParams);
                } else {
                    log.println("[" + step + "/" + totalSteps + "] No cue markers to add.");
                }

                // Scroll scene bank back to 0
                JsonObject scrollParams = new JsonObject();
                scrollParams.addProperty("position", 0);
                client.call("sceneBank/scrollTo", scrollParams);

                log.println("Done! Rebuilt " + clips.size() + " clips across "
                    + scenes.size() + " scenes.");

                // Print manual steps reminder (only for instruments without captured params)
                if (!instruments.isEmpty()) {
                    boolean hasManualSteps = false;
                    for (JsonElement instEl : instruments) {
                        JsonObject inst = instEl.getAsJsonObject();
                        if (!inst.has("pages") || inst.get("pages").isJsonNull()
                                || inst.getAsJsonArray("pages").isEmpty()) {
                            if (!hasManualSteps) {
                                log.println();
                                log.println("Manual steps required:");
                                hasManualSteps = true;
                            }
                            log.println("  Track " + inst.get("track").getAsInt()
                                + ": Load " + inst.get("device").getAsString()
                                + " → " + inst.get("preset").getAsString());
                        }
                    }
                }

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace(System.err);
                System.exit(1);
            }
        }
    }
}
