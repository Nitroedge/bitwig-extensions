package dev.bcrick.secondo.handlers;

import com.google.gson.*;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.rpc.TaskScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MacroHandlerTest {

    private JsonRpcDispatcher dispatcher;
    private List<String> callLog;

    /** Runs scheduled tasks immediately — simulates instant flush cycles for testing. */
    private static final TaskScheduler IMMEDIATE_SCHEDULER = (task, delayMs) -> task.run();

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        callLog = new ArrayList<>();

        // Register stub handlers that log calls
        dispatcher.register("track/createAudio", params -> {
            callLog.add("track/createAudio" + (params.has("position")
                ? ":pos=" + params.get("position").getAsInt() : ""));
            return new JsonPrimitive("ok");
        });
        dispatcher.register("track/createInstrument", params -> {
            callLog.add("track/createInstrument");
            return new JsonPrimitive("ok");
        });
        dispatcher.register("track/createEffect", params -> {
            callLog.add("track/createEffect");
            return new JsonPrimitive("ok");
        });
        dispatcher.register("track/rename", params -> {
            callLog.add("track/rename:" + params.get("name").getAsString());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("device/insertBitwigDevice", params -> {
            callLog.add("device/insertBitwigDevice:" + params.get("name").getAsString());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/create", params -> {
            callLog.add("clip/create:t" + params.get("trackIndex").getAsInt()
                + "s" + params.get("slotIndex").getAsInt()
                + "l" + params.get("lengthInBeats").getAsInt());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/select", params -> {
            callLog.add("clip/select:t" + params.get("trackIndex").getAsInt()
                + "s" + params.get("slotIndex").getAsInt());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setStepSize", params -> {
            callLog.add("clip/setStepSize:" + params.get("size").getAsDouble());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setNotes", params -> {
            int count = params.getAsJsonArray("notes").size();
            callLog.add("clip/setNotes:" + count);
            return new JsonPrimitive(count);
        });
        dispatcher.register("clip/rename", params -> {
            callLog.add("clip/rename:" + params.get("name").getAsString());
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
        dispatcher.register("track/setCursorColor", params -> {
            callLog.add("track/setCursorColor:"
                + params.get("r").getAsFloat() + ","
                + params.get("g").getAsFloat() + ","
                + params.get("b").getAsFloat());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("device/insertPluginDevice", params -> {
            callLog.add("device/insertPluginDevice:" + params.get("type").getAsString()
                + ":" + params.get("id").getAsString());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("device/setParameters", params -> {
            int pageCount = params.getAsJsonArray("pages").size();
            int paramCount = 0;
            for (var pageEl : params.getAsJsonArray("pages")) {
                paramCount += pageEl.getAsJsonObject().getAsJsonArray("params").size();
            }
            callLog.add("device/setParameters:pages=" + pageCount + ",params=" + paramCount);
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setChance", params -> {
            int count = params.getAsJsonArray("notes").size();
            callLog.add("clip/setChance:" + count);
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setNoteExpressions", params -> {
            JsonArray notes = params.getAsJsonArray("notes");
            String prop = notes.get(0).getAsJsonObject().get("property").getAsString();
            callLog.add("clip/setNoteExpressions:" + prop + ":" + notes.size());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setNoteRepeat", params -> {
            int count = params.getAsJsonArray("notes").size();
            callLog.add("clip/setNoteRepeat:" + count);
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setNoteOccurrence", params -> {
            int count = params.getAsJsonArray("notes").size();
            callLog.add("clip/setNoteOccurrence:" + count);
            return new JsonPrimitive("ok");
        });
        dispatcher.register("clip/setNoteRecurrence", params -> {
            int count = params.getAsJsonArray("notes").size();
            callLog.add("clip/setNoteRecurrence:" + count);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/writeEnvelope", params -> {
            int index = params.get("index").getAsInt();
            int pointCount = params.getAsJsonArray("points").size();
            callLog.add("device/writeEnvelope:param=" + index + ",points=" + pointCount);
            return new JsonPrimitive("ok");
        });
        dispatcher.register("device/selectPage", params -> {
            callLog.add("device/selectPage:" + params.get("index").getAsInt());
            return new JsonPrimitive("ok");
        });
        dispatcher.register("transport/setArrangerAutomationWrite", params -> {
            callLog.add("transport/setArrangerAutomationWrite:" + params.get("enabled").getAsBoolean());
            return new JsonPrimitive("ok");
        });

        new MacroHandler(dispatcher, new StateCache(), IMMEDIATE_SCHEDULER).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersEightMacroMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("macro/createTrack"));
        assertTrue(methods.contains("macro/createClip"));
        assertTrue(methods.contains("macro/writeClip"));
        assertTrue(methods.contains("macro/buildSection"));
        assertTrue(methods.contains("macro/setupScenes"));
        assertTrue(methods.contains("macro/createSound"));
        assertTrue(methods.contains("macro/buildSong"));
        assertTrue(methods.contains("macro/writeAutomation"));
    }

    // --- macro/createTrack ---

    @Test
    void createTrack_audioOnly() {
        handle("macro/createTrack", """
            {"type":"audio"}""");
        assertEquals(List.of("track/createAudio"), callLog);
    }

    @Test
    void createTrack_instrumentWithName() {
        handle("macro/createTrack", """
            {"type":"instrument","name":"Synth"}""");
        assertEquals(List.of("track/createInstrument", "track/rename:Synth"), callLog);
    }

    @Test
    void createTrack_effectWithNameAndDevice() {
        handle("macro/createTrack", """
            {"type":"effect","name":"FX Bus","device":"Delay-2"}""");
        assertEquals(List.of(
            "track/createEffect",
            "track/rename:FX Bus",
            "device/insertBitwigDevice:Delay-2"
        ), callLog);
    }

    @Test
    void createTrack_invalidType() {
        String response = handle("macro/createTrack", """
            {"type":"midi"}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("Invalid type"));
    }

    @Test
    void createTrack_missingType() {
        String response = handle("macro/createTrack", "{}");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("missing") && response.contains("type"));
    }

    @Test
    void createTrack_withPosition_forwardsPosition() {
        handle("macro/createTrack", """
            {"type":"audio","position":3}""");
        assertEquals(List.of("track/createAudio:pos=3"), callLog);
    }

    @Test
    void createTrack_withDeviceAndPages_delegatesToCreateSound() {
        handle("macro/createTrack", """
            {"type":"instrument","name":"Lead Synth","device":"Polymer","pages":[
                {"pageIndex":0,"params":[{"index":0,"value":0.75},{"index":1,"value":0.5}]},
                {"pageIndex":1,"params":[{"index":2,"value":0.3}]}
            ]}""");
        assertEquals(List.of(
            "track/createInstrument",
            "track/rename:Lead Synth",
            "device/insertBitwigDevice:Polymer",
            "device/setParameters:pages=2,params=3"
        ), callLog);
    }

    @Test
    void createTrack_withPagesButNoDevice_returnsError() {
        String response = handle("macro/createTrack", """
            {"type":"instrument","pages":[
                {"pageIndex":0,"params":[{"index":0,"value":0.5}]}
            ]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("pages") && response.contains("device"));
    }

    @Test
    void createTrack_withDeviceNoPages_worksAsBeforeNoSoundCall() {
        handle("macro/createTrack", """
            {"type":"instrument","device":"Polymer"}""");
        assertEquals(List.of(
            "track/createInstrument",
            "device/insertBitwigDevice:Polymer"
        ), callLog);
    }

    @Test
    void createTrack_withPages_returnsPageAndParamCounts() {
        String response = handle("macro/createTrack", """
            {"type":"instrument","device":"Polymer","pages":[
                {"pageIndex":0,"params":[{"index":0,"value":0.75},{"index":1,"value":0.5}]},
                {"pageIndex":1,"params":[{"index":2,"value":0.3}]}
            ]}""");
        JsonObject result = parseResult(response);
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(2, result.get("pageCount").getAsInt());
        assertEquals(3, result.get("paramCount").getAsInt());
    }

    @Test
    void createTrack_withColor_setsColorAfterRename() {
        handle("macro/createTrack", """
            {"type":"instrument","name":"Bass","color":{"r":0.5,"g":0.2,"b":0.8}}""");
        assertEquals(List.of(
            "track/createInstrument",
            "track/rename:Bass",
            "track/setCursorColor:0.5,0.2,0.8"
        ), callLog);
    }

    @Test
    void createTrack_withColorAndDevice_colorBeforeDevice() {
        handle("macro/createTrack", """
            {"type":"instrument","name":"Synth","color":{"r":1.0,"g":0.0,"b":0.0},"device":"Polymer"}""");
        assertEquals(List.of(
            "track/createInstrument",
            "track/rename:Synth",
            "track/setCursorColor:1.0,0.0,0.0",
            "device/insertBitwigDevice:Polymer"
        ), callLog);
    }

    @Test
    void createTrack_withPlugin_insertsPluginDevice() {
        handle("macro/createTrack", """
            {"type":"instrument","name":"Synth","plugin":{"type":"vst3","id":"ABCD-1234"}}""");
        assertEquals(List.of(
            "track/createInstrument",
            "track/rename:Synth",
            "device/insertPluginDevice:vst3:ABCD-1234"
        ), callLog);
    }

    @Test
    void createTrack_withPluginAndPages_insertsPluginAndSetsParams() {
        handle("macro/createTrack", """
            {"type":"instrument","name":"Synth","plugin":{"type":"clap","id":"com.vendor.synth"},
             "pages":[{"pageIndex":0,"params":[{"index":0,"value":0.5}]}]}""");
        assertTrue(callLog.contains("device/insertPluginDevice:clap:com.vendor.synth"));
        assertTrue(callLog.contains("device/setParameters:pages=1,params=1"));
    }

    @Test
    void createTrack_withDeviceAndPlugin_returnsError() {
        String response = handle("macro/createTrack", """
            {"type":"instrument","device":"Polymer","plugin":{"type":"vst3","id":"123"}}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("cannot specify both"));
    }

    @Test
    void createSound_withPlugin_insertsPluginAndSetsParams() {
        String response = handle("macro/createSound", """
            {"plugin":{"type":"vst2","id":"12345"},
             "pages":[{"pageIndex":0,"params":[{"index":0,"value":0.75}]}]}""");
        assertTrue(callLog.contains("device/insertPluginDevice:vst2:12345"));
        assertTrue(callLog.contains("device/setParameters:pages=1,params=1"));
        JsonObject result = parseResult(response);
        assertTrue(result.get("inserted").getAsBoolean());
        assertEquals("vst2:12345", result.get("device").getAsString());
    }

    @Test
    void createSound_withDeviceAndPlugin_returnsError() {
        String response = handle("macro/createSound", """
            {"device":"Polymer","plugin":{"type":"vst3","id":"123"},
             "pages":[{"pageIndex":0,"params":[{"index":0,"value":0.5}]}]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("cannot specify both"));
    }

    // --- macro/buildSong ---

    @Test
    void buildSong_twoTracksOneSection() {
        handle("macro/buildSong", """
            {"tracks":[
                {"type":"instrument","name":"Bass","device":"Polymer"},
                {"type":"instrument","name":"Lead","device":"Polysynth"}
            ],"sections":[
                {"sceneName":"Verse","clips":[
                    {"trackIndex":0,"lengthBeats":16,"stepSize":0.25,
                     "notes":[{"x":0,"y":48,"velocity":100,"duration":1}],"name":"Bass Line"},
                    {"trackIndex":1,"lengthBeats":16,"stepSize":0.25,
                     "notes":[{"x":0,"y":60,"velocity":80,"duration":1}],"name":"Lead Line"}
                ]}
            ]}""");
        // Track 0: immediate createTrack
        // Track 1: deferred (device needs flush)
        // Section: deferred after tracks
        assertTrue(callLog.contains("track/createInstrument"));
        assertTrue(callLog.contains("track/rename:Bass"));
        assertTrue(callLog.contains("device/insertBitwigDevice:Polymer"));
        assertTrue(callLog.contains("track/rename:Lead"));
        assertTrue(callLog.contains("device/insertBitwigDevice:Polysynth"));
        assertTrue(callLog.contains("scene/create"));
        assertTrue(callLog.contains("scene/rename:Verse"));
    }

    @Test
    void buildSong_tracksOnly_noSections() {
        String response = handle("macro/buildSong", """
            {"tracks":[
                {"type":"audio","name":"Vocal"},
                {"type":"instrument","name":"Keys"}
            ]}""");
        JsonObject result = parseResult(response);
        assertEquals(2, result.get("trackCount").getAsInt());
        assertEquals(0, result.get("sectionCount").getAsInt());
        assertTrue(callLog.contains("track/createAudio"));
        assertTrue(callLog.contains("track/rename:Vocal"));
        assertTrue(callLog.contains("track/rename:Keys"));
    }

    @Test
    void buildSong_emptyTracks_returnsError() {
        String response = handle("macro/buildSong", """
            {"tracks":[]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("must not be empty"));
    }

    @Test
    void buildSong_returnsTrackAndSectionCounts() {
        String response = handle("macro/buildSong", """
            {"tracks":[
                {"type":"instrument","name":"Drums","device":"Drum Machine"},
                {"type":"instrument","name":"Bass","device":"Polymer"},
                {"type":"instrument","name":"Lead","device":"Polysynth"}
            ],"sections":[
                {"sceneName":"Verse","clips":[
                    {"trackIndex":0,"lengthBeats":16,"stepSize":0.25,
                     "notes":[{"x":0,"y":36,"velocity":100,"duration":1}]}
                ]},
                {"sceneName":"Chorus","clips":[
                    {"trackIndex":0,"lengthBeats":16,"stepSize":0.25,
                     "notes":[{"x":0,"y":36,"velocity":100,"duration":1}]}
                ]}
            ]}""");
        JsonObject result = parseResult(response);
        assertEquals(3, result.get("trackCount").getAsInt());
        assertEquals(2, result.get("sectionCount").getAsInt());
    }

    @Test
    void buildSong_withColorAndPages() {
        handle("macro/buildSong", """
            {"tracks":[
                {"type":"instrument","name":"Bass","device":"Polymer",
                 "color":{"r":0.0,"g":0.5,"b":1.0},
                 "pages":[{"pageIndex":0,"params":[{"index":0,"value":0.75}]}]}
            ]}""");
        assertTrue(callLog.contains("track/createInstrument"));
        assertTrue(callLog.contains("track/rename:Bass"));
        assertTrue(callLog.contains("track/setCursorColor:0.0,0.5,1.0"));
        assertTrue(callLog.contains("device/insertBitwigDevice:Polymer"));
        // createSound delegation happens via deferred scheduler
        assertTrue(callLog.contains("device/setParameters:pages=1,params=1"));
    }

    @Test
    void buildSong_withPluginTrack() {
        handle("macro/buildSong", """
            {"tracks":[
                {"type":"instrument","name":"VST Synth",
                 "plugin":{"type":"vst3","id":"GUID-123"}}
            ]}""");
        assertTrue(callLog.contains("track/createInstrument"));
        assertTrue(callLog.contains("track/rename:VST Synth"));
        assertTrue(callLog.contains("device/insertPluginDevice:vst3:GUID-123"));
    }

    // --- macro/createClip ---

    @Test
    void createClip_createsAndSelects() {
        handle("macro/createClip", """
            {"trackIndex":2,"sceneIndex":1,"lengthBeats":16}""");
        assertEquals(List.of("clip/create:t2s1l16", "clip/select:t2s1"), callLog);
    }

    @Test
    void createClip_missingTrackIndex() {
        String response = handle("macro/createClip", """
            {"sceneIndex":0,"lengthBeats":8}""");
        assertTrue(response.contains("error"));
    }

    @Test
    void createClip_missingSceneIndex_returnsError() {
        String response = handle("macro/createClip", """
            {"trackIndex":0,"lengthBeats":8}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("sceneIndex"));
    }

    @Test
    void createClip_missingLengthBeats_returnsError() {
        String response = handle("macro/createClip", """
            {"trackIndex":0,"sceneIndex":1}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("lengthBeats"));
    }

    // --- macro/writeClip ---

    @Test
    void writeClip_fullSequence() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":1,"lengthBeats":8,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":4,"y":64,"velocity":80,"duration":1}]}""");
        // Phase 1: create + select; Phase 2 (deferred): setStepSize + setNotes
        assertEquals(List.of(
            "clip/create:t0s1l8",
            "clip/select:t0s1",
            "clip/setStepSize:0.25",
            "clip/setNotes:2"
        ), callLog);
    }

    @Test
    void writeClip_withName() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.5,
             "notes":[{"x":0,"y":48,"velocity":100,"duration":1}],
             "name":"Bass Line"}""");
        assertEquals(List.of(
            "clip/create:t0s0l4",
            "clip/select:t0s0",
            "clip/setStepSize:0.5",
            "clip/setNotes:1",
            "clip/rename:Bass Line"
        ), callLog);
    }

    @Test
    void writeClip_returnsCount() {
        String response = handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1},
                      {"x":1,"y":62,"velocity":90,"duration":1},
                      {"x":2,"y":64,"velocity":80,"duration":1}]}""");
        JsonObject result = parseResult(response);
        assertEquals(3, result.get("count").getAsInt());
    }

    @Test
    void writeClip_missingNotes() {
        String response = handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.25}""");
        assertTrue(response.contains("error"));
    }

    @Test
    void writeClip_missingStepSize_returnsError() {
        String response = handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("stepSize"));
    }

    @Test
    void writeClip_missingTrackIndex_returnsError() {
        String response = handle("macro/writeClip", """
            {"sceneIndex":0,"lengthBeats":4,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("trackIndex"));
    }

    // --- macro/buildSection ---

    @Test
    void buildSection_createsSceneAndWritesClips() {
        handle("macro/buildSection", """
            {"sceneName":"Verse 1","clips":[
                {"trackIndex":0,"lengthBeats":16,"stepSize":0.25,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1}],"name":"Lead"},
                {"trackIndex":1,"lengthBeats":16,"stepSize":0.25,
                 "notes":[{"x":0,"y":48,"velocity":80,"duration":2}],"name":"Bass"}
            ]}""");
        // Phase 1: scene create + scroll + rename + create all clips + select first
        // Phase 2+ (deferred): write notes per clip with select-next chaining
        assertEquals(List.of(
            "scene/create",
            "sceneBank/scrollBy:0",
            "scene/rename:Verse 1",
            "clip/create:t0s0l16",
            "clip/create:t1s0l16",
            "clip/select:t0s0",
            "clip/setStepSize:0.25",
            "clip/setNotes:1",
            "clip/rename:Lead",
            "clip/select:t1s0",
            "clip/setStepSize:0.25",
            "clip/setNotes:1",
            "clip/rename:Bass"
        ), callLog);
    }

    @Test
    void buildSection_returnsSceneIndexAndClipCount() {
        String response = handle("macro/buildSection", """
            {"sceneName":"Chorus","clips":[
                {"trackIndex":0,"lengthBeats":8,"stepSize":0.5,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]},
                {"trackIndex":1,"lengthBeats":8,"stepSize":0.5,
                 "notes":[{"x":0,"y":48,"velocity":80,"duration":1}]},
                {"trackIndex":2,"lengthBeats":8,"stepSize":0.5,
                 "notes":[{"x":0,"y":36,"velocity":90,"duration":1}]}
            ]}""");
        JsonObject result = parseResult(response);
        assertEquals(0, result.get("sceneIndex").getAsInt());
        assertEquals(3, result.get("clipCount").getAsInt());
    }

    @Test
    void buildSection_emptyClips() {
        String response = handle("macro/buildSection", """
            {"sceneName":"Empty","clips":[]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("must not be empty"));
    }

    @Test
    void buildSection_missingSceneName() {
        String response = handle("macro/buildSection", """
            {"clips":[{"trackIndex":0,"lengthBeats":8,"stepSize":0.25,
             "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}]}""");
        assertTrue(response.contains("error"));
    }

    @Test
    void buildSection_withExplicitSceneIndex() {
        handle("macro/buildSection", """
            {"sceneName":"Verse 1","sceneIndex":2,"clips":[
                {"trackIndex":0,"lengthBeats":16,"stepSize":0.25,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1}],"name":"Lead"},
                {"trackIndex":1,"lengthBeats":16,"stepSize":0.25,
                 "notes":[{"x":0,"y":48,"velocity":80,"duration":2}],"name":"Bass"}
            ]}""");
        // No scene/create or sceneBank/scrollBy — uses sceneIndex directly
        assertEquals(List.of(
            "scene/rename:Verse 1",
            "clip/create:t0s2l16",
            "clip/create:t1s2l16",
            "clip/select:t0s2",
            "clip/setStepSize:0.25",
            "clip/setNotes:1",
            "clip/rename:Lead",
            "clip/select:t1s2",
            "clip/setStepSize:0.25",
            "clip/setNotes:1",
            "clip/rename:Bass"
        ), callLog);
    }

    @Test
    void buildSection_withExplicitSceneIndex_returnsIndex() {
        String response = handle("macro/buildSection", """
            {"sceneName":"Chorus","sceneIndex":3,"clips":[
                {"trackIndex":0,"lengthBeats":8,"stepSize":0.5,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}
            ]}""");
        JsonObject result = parseResult(response);
        assertEquals(3, result.get("sceneIndex").getAsInt());
        assertEquals(1, result.get("clipCount").getAsInt());
    }

    @Test
    void buildSection_clipMissingTrackIndex_returnsError() {
        String response = handle("macro/buildSection", """
            {"sceneName":"Test","clips":[
                {"lengthBeats":8,"stepSize":0.25,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}
            ]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("trackIndex"));
    }

    @Test
    void buildSection_clipMissingLengthBeats_returnsError() {
        String response = handle("macro/buildSection", """
            {"sceneName":"Test","clips":[
                {"trackIndex":0,"stepSize":0.25,
                 "notes":[{"x":0,"y":60,"velocity":100,"duration":1}]}
            ]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("lengthBeats"));
    }

    // --- macro/setupScenes ---

    @Test
    void setupScenes_createsAndRenamesInSeparateFlushes() {
        handle("macro/setupScenes", """
            {"scenes":[
                {"index":0,"name":"Intro"},
                {"index":1,"name":"Verse"},
                {"index":2,"name":"Chorus"}
            ]}""");
        // Phase 1: 3 scene creates in this flush cycle
        // Phase 2+: each rename deferred to separate flush cycle
        assertEquals(List.of(
            "scene/create",
            "scene/create",
            "scene/create",
            "scene/rename:Intro",
            "scene/rename:Verse",
            "scene/rename:Chorus"
        ), callLog);
    }

    @Test
    void setupScenes_returnsCreatedAndRenamedCounts() {
        String response = handle("macro/setupScenes", """
            {"scenes":[
                {"index":0,"name":"A"},
                {"index":1,"name":"B"}
            ]}""");
        JsonObject result = parseResult(response);
        assertEquals(2, result.get("created").getAsInt());
        assertEquals(2, result.get("renamed").getAsInt());
    }

    @Test
    void setupScenes_sceneMissingName_returnsError() {
        String response = handle("macro/setupScenes", """
            {"scenes":[{"index":0}]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("name"));
    }

    @Test
    void setupScenes_emptyArray() {
        String response = handle("macro/setupScenes", """
            {"scenes":[]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("must not be empty"));
    }

    // --- macro/createSound ---

    @Test
    void createSound_withDevice_insertsAndSetsParams() {
        handle("macro/createSound", """
            {"device":"Polymer","pages":[
                {"pageIndex":0,"params":[{"index":0,"value":0.75}]},
                {"pageIndex":1,"params":[{"index":2,"value":0.3}]}
            ]}""");
        assertEquals(List.of(
            "device/insertBitwigDevice:Polymer",
            "device/setParameters:pages=2,params=2"
        ), callLog);
    }

    @Test
    void createSound_withDeviceAndPosition_forwardsPosition() {
        handle("macro/createSound", """
            {"device":"Wavetable","position":"after","pages":[
                {"pageIndex":0,"params":[{"index":0,"value":0.5}]}
            ]}""");
        // Verify device insertion happened (position is forwarded)
        assertTrue(callLog.get(0).startsWith("device/insertBitwigDevice:Wavetable"));
    }

    @Test
    void createSound_withoutDevice_setsParamsImmediately() {
        handle("macro/createSound", """
            {"pages":[
                {"pageIndex":0,"params":[{"index":0,"value":0.5},{"index":1,"value":0.3}]}
            ]}""");
        // No device insertion — only setParameters
        assertEquals(List.of(
            "device/setParameters:pages=1,params=2"
        ), callLog);
    }

    @Test
    void createSound_returnsResult() {
        String response = handle("macro/createSound", """
            {"device":"Polymer","pages":[
                {"pageIndex":0,"params":[{"index":0,"value":0.75},{"index":1,"value":0.5}]},
                {"pageIndex":1,"params":[{"index":2,"value":0.3}]}
            ]}""");
        JsonObject result = parseResult(response);
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals("Polymer", result.get("device").getAsString());
        assertEquals(2, result.get("pageCount").getAsInt());
        assertEquals(3, result.get("paramCount").getAsInt());
        assertTrue(result.get("inserted").getAsBoolean());
    }

    @Test
    void createSound_withoutDevice_returnsCurrent() {
        String response = handle("macro/createSound", """
            {"pages":[
                {"pageIndex":0,"params":[{"index":0,"value":0.5}]}
            ]}""");
        JsonObject result = parseResult(response);
        assertEquals("current", result.get("device").getAsString());
        assertFalse(result.get("inserted").getAsBoolean());
    }

    @Test
    void createSound_missingPages_returnsError() {
        String response = handle("macro/createSound", "{}");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("pages"));
    }

    @Test
    void createSound_emptyPages_returnsError() {
        String response = handle("macro/createSound", """
            {"pages":[]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("empty"));
    }

    @Test
    void createSound_paramIndexOutOfRange_returnsError() {
        String response = handle("macro/createSound", """
            {"pages":[{"pageIndex":0,"params":[{"index":8,"value":0.5}]}]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("out of range"));
    }

    @Test
    void createSound_paramValueOutOfRange_returnsError() {
        String response = handle("macro/createSound", """
            {"pages":[{"pageIndex":0,"params":[{"index":0,"value":1.5}]}]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("out of range"));
    }

    // --- macro/writeAutomation ---

    @Test
    void writeAutomation_singleEnvelope_callsWriteEnvelope() {
        handle("macro/writeAutomation", """
            {"envelopes":[
                {"paramIndex":0,"points":[{"position":0.0,"value":0.0},{"position":4.0,"value":1.0}]}
            ]}""");
        assertTrue(callLog.contains("transport/setArrangerAutomationWrite:true"));
        assertTrue(callLog.contains("device/writeEnvelope:param=0,points=2"));
    }

    @Test
    void writeAutomation_multipleEnvelopes_callsWriteEnvelopeForEach() {
        handle("macro/writeAutomation", """
            {"envelopes":[
                {"paramIndex":0,"points":[{"position":0.0,"value":0.5}]},
                {"paramIndex":3,"points":[{"position":0.0,"value":0.0},{"position":8.0,"value":1.0}]}
            ]}""");
        assertTrue(callLog.contains("device/writeEnvelope:param=0,points=1"));
        assertTrue(callLog.contains("device/writeEnvelope:param=3,points=2"));
    }

    @Test
    void writeAutomation_withPageIndex_switchesPageBeforeGroup() {
        handle("macro/writeAutomation", """
            {"envelopes":[
                {"paramIndex":2,"pageIndex":1,"points":[{"position":0.0,"value":0.7}]}
            ]}""");
        assertTrue(callLog.contains("device/selectPage:1"));
        assertTrue(callLog.contains("device/writeEnvelope:param=2,points=1"));
        // Page switch must come before the envelope write
        int pageIdx = callLog.indexOf("device/selectPage:1");
        int writeIdx = callLog.indexOf("device/writeEnvelope:param=2,points=1");
        assertTrue(pageIdx < writeIdx, "page switch should precede envelope write");
    }

    @Test
    void writeAutomation_withoutPageIndex_noPageSwitch() {
        handle("macro/writeAutomation", """
            {"envelopes":[
                {"paramIndex":0,"points":[{"position":0.0,"value":0.5}]}
            ]}""");
        for (String call : callLog) {
            assertFalse(call.startsWith("device/selectPage"), "unexpected page switch");
        }
    }

    @Test
    void writeAutomation_mixedPageGroups_switchesBetweenGroups() {
        handle("macro/writeAutomation", """
            {"envelopes":[
                {"paramIndex":0,"pageIndex":0,"points":[{"position":0.0,"value":0.5}]},
                {"paramIndex":1,"pageIndex":2,"points":[{"position":0.0,"value":0.3}]},
                {"paramIndex":5,"points":[{"position":0.0,"value":0.8}]}
            ]}""");
        assertTrue(callLog.contains("device/selectPage:0"));
        assertTrue(callLog.contains("device/selectPage:2"));
        assertTrue(callLog.contains("device/writeEnvelope:param=0,points=1"));
        assertTrue(callLog.contains("device/writeEnvelope:param=1,points=1"));
        assertTrue(callLog.contains("device/writeEnvelope:param=5,points=1"));
    }

    @Test
    void writeAutomation_returnsEnvelopeCountAndTotalPoints() {
        String response = handle("macro/writeAutomation", """
            {"envelopes":[
                {"paramIndex":0,"points":[{"position":0.0,"value":0.0},{"position":4.0,"value":1.0}]},
                {"paramIndex":1,"points":[{"position":0.0,"value":0.5},{"position":2.0,"value":0.8},{"position":8.0,"value":0.1}]}
            ]}""");
        JsonObject result = parseResult(response);
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(2, result.get("envelopeCount").getAsInt());
        assertEquals(5, result.get("totalPoints").getAsInt());
    }

    @Test
    void writeAutomation_emptyEnvelopes_returnsError() {
        String response = handle("macro/writeAutomation", """
            {"envelopes":[]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("must not be empty"));
    }

    @Test
    void writeAutomation_paramIndexOutOfRange_returnsError() {
        String response = handle("macro/writeAutomation", """
            {"envelopes":[
                {"paramIndex":9,"points":[{"position":0.0,"value":0.5}]}
            ]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("out of range"));
    }

    @Test
    void writeAutomation_emptyPoints_returnsError() {
        String response = handle("macro/writeAutomation", """
            {"envelopes":[
                {"paramIndex":0,"points":[]}
            ]}""");
        assertTrue(response.contains("error"));
        assertTrue(response.contains("must not be empty"));
    }

    // --- Expression support in writeClip ---

    @Test
    void writeClip_withChance_callsSetChance() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.25,
             "notes":[
                {"x":0,"y":60,"velocity":100,"duration":1,"chance":0.75},
                {"x":1,"y":62,"velocity":90,"duration":1,"chance":0.5}
             ]}""");
        assertTrue(callLog.contains("clip/setChance:2"));
    }

    @Test
    void writeClip_withExpressions_callsSetNoteExpressionsPerProperty() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.25,
             "notes":[
                {"x":0,"y":60,"velocity":100,"duration":1,
                 "expressions":{"pan":0.3,"timbre":0.7}},
                {"x":1,"y":62,"velocity":90,"duration":1,
                 "expressions":{"pan":0.6}}
             ]}""");
        assertTrue(callLog.contains("clip/setNoteExpressions:pan:2"));
        assertTrue(callLog.contains("clip/setNoteExpressions:timbre:1"));
    }

    @Test
    void writeClip_withRepeat_callsSetNoteRepeat() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.25,
             "notes":[
                {"x":0,"y":60,"velocity":100,"duration":1,
                 "repeat":{"count":3,"curve":0.0,"velocityEnd":0.5,"velocityCurve":0.0}}
             ]}""");
        assertTrue(callLog.contains("clip/setNoteRepeat:1"));
    }

    @Test
    void writeClip_withOccurrence_callsSetNoteOccurrence() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.25,
             "notes":[
                {"x":0,"y":60,"velocity":100,"duration":1,"occurrence":"FILL"},
                {"x":2,"y":64,"velocity":80,"duration":1,"occurrence":"NOT_FILL"}
             ]}""");
        assertTrue(callLog.contains("clip/setNoteOccurrence:2"));
    }

    @Test
    void writeClip_withRecurrence_callsSetNoteRecurrence() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.25,
             "notes":[
                {"x":0,"y":60,"velocity":100,"duration":1,
                 "recurrence":{"length":4,"mask":5}}
             ]}""");
        assertTrue(callLog.contains("clip/setNoteRecurrence:1"));
    }

    @Test
    void writeClip_basicNotesOnly_noExpressionCalls() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":4,"stepSize":0.25,
             "notes":[
                {"x":0,"y":60,"velocity":100,"duration":1},
                {"x":1,"y":62,"velocity":90,"duration":1}
             ]}""");
        // Should NOT contain any expression calls
        for (String call : callLog) {
            assertFalse(call.startsWith("clip/setChance"), "unexpected setChance call");
            assertFalse(call.startsWith("clip/setNoteExpressions"), "unexpected setNoteExpressions call");
            assertFalse(call.startsWith("clip/setNoteRepeat"), "unexpected setNoteRepeat call");
            assertFalse(call.startsWith("clip/setNoteOccurrence"), "unexpected setNoteOccurrence call");
            assertFalse(call.startsWith("clip/setNoteRecurrence"), "unexpected setNoteRecurrence call");
        }
    }

    @Test
    void writeClip_mixedExpressions_allTypesApplied() {
        handle("macro/writeClip", """
            {"trackIndex":0,"sceneIndex":0,"lengthBeats":8,"stepSize":0.25,
             "notes":[
                {"x":0,"y":60,"velocity":100,"duration":1,"chance":0.8,
                 "expressions":{"pan":0.3},"occurrence":"FILL"},
                {"x":4,"y":64,"velocity":80,"duration":1,
                 "repeat":{"count":2,"curve":0.0,"velocityEnd":1.0,"velocityCurve":0.0},
                 "recurrence":{"length":3,"mask":5}}
             ]}""");
        assertTrue(callLog.contains("clip/setChance:1"));
        assertTrue(callLog.contains("clip/setNoteExpressions:pan:1"));
        assertTrue(callLog.contains("clip/setNoteOccurrence:1"));
        assertTrue(callLog.contains("clip/setNoteRepeat:1"));
        assertTrue(callLog.contains("clip/setNoteRecurrence:1"));
    }

    @Test
    void buildSection_withExpressionNotes_expressionsAppliedPerClip() {
        handle("macro/buildSection", """
            {"sceneName":"Verse","clips":[
                {"trackIndex":0,"lengthBeats":8,"stepSize":0.25,
                 "notes":[
                    {"x":0,"y":60,"velocity":100,"duration":1,"chance":0.9},
                    {"x":2,"y":64,"velocity":80,"duration":1,"expressions":{"timbre":0.5}}
                 ]}
            ]}""");
        assertTrue(callLog.contains("clip/setChance:1"));
        assertTrue(callLog.contains("clip/setNoteExpressions:timbre:1"));
    }

    // --- Helpers ---

    private String handle(String method, String params) {
        return dispatcher.handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}");
    }

    private JsonObject parseResult(String response) {
        return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result");
    }
}
