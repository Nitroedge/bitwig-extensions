package dev.gregross.gig.extension;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.gregross.gig.extension.StateCacheTestHelper.*;
import static org.junit.jupiter.api.Assertions.*;

class StateCacheSnapshotTest {

    private StateCache cache;

    @BeforeEach
    void setUp() {
        cache = new StateCache();
    }

    @Test
    void snapshot_transport_containsAllFields() {
        populateTransport(cache);
        JsonObject transport = cache.getSnapshot().getAsJsonObject("transport");

        assertTrue(transport.get("isPlaying").getAsBoolean());
        assertFalse(transport.get("isRecording").getAsBoolean());
        assertEquals(120.0, transport.get("tempo").getAsDouble());
        assertEquals(4.5, transport.get("playPosition").getAsDouble());
        assertEquals(4, transport.get("timeSignatureNumerator").getAsInt());
        assertEquals(4, transport.get("timeSignatureDenominator").getAsInt());
        assertTrue(transport.get("isLoopEnabled").getAsBoolean());
        assertFalse(transport.get("isMetronomeEnabled").getAsBoolean());
        assertEquals(0.8, transport.get("metronomeVolume").getAsDouble(), 0.001);
        assertEquals("one_bar", transport.get("preRoll").getAsString());
        assertEquals("default", transport.get("defaultLaunchQuantization").getAsString());
        assertEquals("play_recorded", transport.get("clipLauncherPostRecordingAction").getAsString());
        assertEquals(0.0, transport.get("clipLauncherPostRecordingTimeOffset").getAsDouble());
        assertFalse(transport.get("clipLauncherOverdubEnabled").getAsBoolean());
        assertFalse(transport.get("fillModeActive").getAsBoolean());
    }

    @Test
    void snapshot_tracks_containsTrackArray() {
        populateTrack(cache, 0);
        JsonObject tracks = cache.getSnapshot().getAsJsonObject("tracks");

        assertEquals(trackCountOf(StateCache.class), tracks.get("bankSize").getAsInt());
        assertTrue(tracks.has("scrollPosition"));
        assertTrue(tracks.has("itemCount"));
        assertTrue(tracks.has("canScrollBackwards"));
        assertTrue(tracks.has("canScrollForwards"));

        JsonArray trackArr = tracks.getAsJsonArray("tracks");
        assertEquals(trackCountOf(StateCache.class), trackArr.size());

        JsonObject track0 = trackArr.get(0).getAsJsonObject();
        assertEquals(0, track0.get("index").getAsInt());
        assertEquals("Bass", track0.get("name").getAsString());
        assertEquals(0.75, track0.get("volume").getAsDouble(), 0.001);
        assertEquals(-0.2, track0.get("pan").getAsDouble(), 0.001);
        assertFalse(track0.get("mute").getAsBoolean());
        assertTrue(track0.get("solo").getAsBoolean());
        assertTrue(track0.get("arm").getAsBoolean());

        JsonObject color = track0.getAsJsonObject("color");
        assertEquals(1.0f, color.get("r").getAsFloat(), 0.001f);
        assertEquals(0.5f, color.get("g").getAsFloat(), 0.001f);
        assertEquals(0.0f, color.get("b").getAsFloat(), 0.001f);

        assertEquals("AB", track0.get("crossfadeMode").getAsString());
        assertEquals("AUTO", track0.get("monitorMode").getAsString());
        assertEquals("Instrument", track0.get("trackType").getAsString());
        assertFalse(track0.get("isGroup").getAsBoolean());
        assertFalse(track0.get("isGroupExpanded").getAsBoolean());
        assertFalse(track0.get("canHoldNoteData").getAsBoolean());
        assertFalse(track0.get("canHoldAudioData").getAsBoolean());
        assertFalse(track0.get("isMutedBySolo").getAsBoolean());

        assertTrue(track0.has("sends"));
        assertTrue(track0.has("clips"));
        assertEquals(4, track0.getAsJsonArray("sends").size());
        assertEquals(sceneCountOf(StateCache.class), track0.getAsJsonArray("clips").size());
    }

    @Test
    void snapshot_scenes_containsSceneArray() {
        populateScene(cache, 0);
        setField(cache, "sceneItemCount", 10);
        JsonObject scenes = cache.getSnapshot().getAsJsonObject("scenes");

        assertEquals(sceneCountOf(StateCache.class), scenes.get("bankSize").getAsInt());
        assertEquals(10, scenes.get("itemCount").getAsInt());

        JsonArray sceneArr = scenes.getAsJsonArray("scenes");
        assertEquals(sceneCountOf(StateCache.class), sceneArr.size());

        JsonObject scene0 = sceneArr.get(0).getAsJsonObject();
        assertEquals(0, scene0.get("index").getAsInt());
        assertEquals("Intro", scene0.get("name").getAsString());
        assertEquals(3, scene0.get("clipCount").getAsInt());

        JsonObject color = scene0.getAsJsonObject("color");
        assertEquals(0.0f, color.get("r").getAsFloat(), 0.001f);
        assertEquals(1.0f, color.get("g").getAsFloat(), 0.001f);
    }

    @Test
    void snapshot_device_containsAllFields() {
        populateDevice(cache);
        JsonObject device = cache.getSnapshot().getAsJsonObject("device");

        assertEquals("Lead Synth", device.get("cursorTrackName").getAsString());
        assertEquals("Polymer", device.get("name").getAsString());
        assertTrue(device.get("isEnabled").getAsBoolean());
        assertFalse(device.get("isPlugin").getAsBoolean());
        assertEquals(0, device.get("position").getAsInt());
        assertEquals("Init", device.get("presetName").getAsString());
        assertEquals("Synth", device.get("presetCategory").getAsString());
        assertEquals("Bitwig", device.get("presetCreator").getAsString());
        assertFalse(device.get("isWindowOpen").getAsBoolean());
        assertTrue(device.get("isExpanded").getAsBoolean());
        assertFalse(device.get("isNested").getAsBoolean());
        assertFalse(device.get("hasSlots").getAsBoolean());
        assertTrue(device.has("slotNames"));
        assertFalse(device.get("hasLayers").getAsBoolean());
        assertFalse(device.get("hasDrumPads").getAsBoolean());

        JsonObject rc = device.getAsJsonObject("remoteControls");
        assertEquals(0, rc.get("pageIndex").getAsInt());
        assertEquals(2, rc.get("pageCount").getAsInt());
        assertEquals(2, rc.getAsJsonArray("pageNames").size());
        assertEquals("Main", rc.getAsJsonArray("pageNames").get(0).getAsString());

        JsonArray params = rc.getAsJsonArray("parameters");
        assertEquals(8, params.size());
        JsonObject p0 = params.get(0).getAsJsonObject();
        assertEquals("Cutoff", p0.get("name").getAsString());
        assertEquals(0.75, p0.get("value").getAsDouble(), 0.001);
        assertEquals(0.62, p0.get("modulatedValue").getAsDouble(), 0.001);
        assertEquals("75%", p0.get("displayedValue").getAsString());
        assertTrue(p0.get("hasAutomation").getAsBoolean());
        assertFalse(p0.get("isBeingMapped").getAsBoolean());
    }

    @Test
    void snapshot_clip_containsAllFields() {
        populateClip(cache);
        JsonObject clip = cache.getSnapshot().getAsJsonObject("clip");

        assertEquals("Bass", clip.get("trackName").getAsString());
        assertEquals(8, clip.get("playingStep").getAsInt());
        assertEquals(16.0, clip.get("loopLength").getAsDouble());
        assertEquals(0.0, clip.get("playStart").getAsDouble());
        assertEquals(16.0, clip.get("playStop").getAsDouble());
        assertEquals(0.25, clip.get("stepSize").getAsDouble());
        assertTrue(clip.get("hasContent").getAsBoolean());
        assertEquals("default", clip.get("launchQuantization").getAsString());
        assertEquals("play_with_quantization", clip.get("launchMode").getAsString());
        assertFalse(clip.get("shuffle").getAsBoolean());
        assertEquals(0.5, clip.get("accent").getAsDouble(), 0.001);
        assertFalse(clip.get("useLoopStartAsQuantizationReference").getAsBoolean());
        assertTrue(clip.get("isLoopEnabled").getAsBoolean());
        assertEquals(0.0, clip.get("loopStart").getAsDouble());

        JsonObject color = clip.getAsJsonObject("color");
        assertEquals(0.2f, color.get("r").getAsFloat(), 0.01f);
        assertEquals(0.4f, color.get("g").getAsFloat(), 0.01f);
        assertEquals(0.8f, color.get("b").getAsFloat(), 0.01f);
    }

    @Test
    void snapshot_master_containsAllFields() {
        populateMaster(cache);
        JsonObject master = cache.getSnapshot().getAsJsonObject("master");

        assertEquals(0.9, master.get("volume").getAsDouble(), 0.001);
        assertEquals(0.0, master.get("pan").getAsDouble());
        assertFalse(master.get("mute").getAsBoolean());
        assertFalse(master.get("solo").getAsBoolean());

        JsonObject color = master.getAsJsonObject("color");
        assertEquals(0.5f, color.get("r").getAsFloat(), 0.001f);
        assertEquals(0.5f, color.get("g").getAsFloat(), 0.001f);
        assertEquals(0.5f, color.get("b").getAsFloat(), 0.001f);
    }

    @Test
    void snapshot_application_containsAllFields() {
        populateApplication(cache);
        JsonObject app = cache.getSnapshot().getAsJsonObject("application");

        assertEquals("My Song", app.get("projectName").getAsString());
        assertTrue(app.get("canUndo").getAsBoolean());
        assertFalse(app.get("canRedo").getAsBoolean());
        assertTrue(app.get("hasActiveEngine").getAsBoolean());
        assertEquals("MIX", app.get("panelLayout").getAsString());
        assertTrue(app.get("hasSoloedTracks").getAsBoolean());
        assertFalse(app.get("hasMutedTracks").getAsBoolean());
        assertTrue(app.get("hasArmedTracks").getAsBoolean());
        assertTrue(app.get("isModified").getAsBoolean());
        assertTrue(app.has("cueVolume"));
        assertTrue(app.has("cueMix"));
    }

    @Test
    void snapshot_arranger_containsAllFields() {
        populateArranger(cache);
        JsonObject arranger = cache.getSnapshot().getAsJsonObject("arranger");

        assertTrue(arranger.get("playbackFollow").getAsBoolean());
        assertTrue(arranger.get("clipLauncherVisible").getAsBoolean());
        assertFalse(arranger.get("timelineVisible").getAsBoolean());
        assertTrue(arranger.get("cueMarkersVisible").getAsBoolean());
        assertFalse(arranger.get("effectTracksVisible").getAsBoolean());
        assertTrue(arranger.get("ioSectionVisible").getAsBoolean());
        assertFalse(arranger.get("doubleRowTrackHeight").getAsBoolean());
    }

    @Test
    void snapshot_arrangement_containsNestedStructure() {
        populateArrangement(cache);
        JsonObject arrangement = cache.getSnapshot().getAsJsonObject("arrangement");

        JsonObject loop = arrangement.getAsJsonObject("loop");
        assertEquals(4.0, loop.get("start").getAsDouble());
        assertEquals(16.0, loop.get("duration").getAsDouble());
        assertTrue(loop.get("enabled").getAsBoolean());

        JsonObject punch = arrangement.getAsJsonObject("punch");
        assertEquals(2.0, punch.get("inPosition").getAsDouble());
        assertTrue(punch.get("inEnabled").getAsBoolean());
        assertEquals(32.0, punch.get("outPosition").getAsDouble());
        assertFalse(punch.get("outEnabled").getAsBoolean());

        JsonObject automation = arrangement.getAsJsonObject("automation");
        assertEquals("latch", automation.get("writeMode").getAsString());
        assertTrue(automation.get("arrangerWriteEnabled").getAsBoolean());
        assertFalse(automation.get("clipLauncherWriteEnabled").getAsBoolean());
        assertFalse(automation.get("overrideActive").getAsBoolean());

        JsonObject cueMarkers = arrangement.getAsJsonObject("cueMarkers");
        assertEquals(16, cueMarkers.get("bankSize").getAsInt());
        assertTrue(cueMarkers.has("scrollPosition"));
        assertTrue(cueMarkers.has("itemCount"));
        JsonArray items = cueMarkers.getAsJsonArray("items");
        assertEquals(16, items.size());
    }

    @Test
    void snapshot_masterDevice_containsAllFields() {
        populateMasterDevice(cache);
        JsonObject md = cache.getSnapshot().getAsJsonObject("masterDevice");

        assertEquals("EQ-5", md.get("name").getAsString());
        assertTrue(md.get("isEnabled").getAsBoolean());
        assertFalse(md.get("isPlugin").getAsBoolean());
        assertEquals(1, md.get("position").getAsInt());
        assertEquals("Flat", md.get("presetName").getAsString());
        assertEquals("EQ", md.get("presetCategory").getAsString());
        assertEquals("Bitwig", md.get("presetCreator").getAsString());

        JsonObject rc = md.getAsJsonObject("remoteControls");
        assertEquals(0, rc.get("pageIndex").getAsInt());
        assertEquals(1, rc.get("pageCount").getAsInt());
        assertEquals("Main", rc.getAsJsonArray("pageNames").get(0).getAsString());

        JsonArray params = rc.getAsJsonArray("parameters");
        assertEquals(8, params.size());
        assertEquals("Gain", params.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(0.5, params.get(0).getAsJsonObject().get("value").getAsDouble(), 0.001);
        assertEquals(0.5, params.get(0).getAsJsonObject().get("modulatedValue").getAsDouble(), 0.001);
    }

    @Test
    void snapshot_browser_containsFilters() {
        populateBrowser(cache);
        JsonObject browser = cache.getSnapshot().getAsJsonObject("browser");

        assertTrue(browser.get("exists").getAsBoolean());
        assertEquals("Presets", browser.get("title").getAsString());
        assertEquals("Presets", browser.get("selectedContentType").getAsString());
        assertEquals(2, browser.getAsJsonArray("contentTypeNames").size());
        assertTrue(browser.get("canAudition").getAsBoolean());
        assertFalse(browser.get("shouldAudition").getAsBoolean());
        assertEquals("Init", browser.get("resultName").getAsString());
        assertTrue(browser.get("resultIsSelected").getAsBoolean());
        assertEquals(42, browser.get("resultsEntryCount").getAsInt());

        JsonObject filters = browser.getAsJsonObject("filters");
        assertTrue(filters.has("category"));
        assertTrue(filters.has("tag"));
        assertTrue(filters.has("creator"));
        assertTrue(filters.has("device"));
        assertTrue(filters.has("deviceType"));
        assertTrue(filters.has("fileType"));
        assertTrue(filters.has("location"));
        assertTrue(filters.has("smartCollection"));

        JsonObject category = filters.getAsJsonObject("category");
        assertTrue(category.get("exists").getAsBoolean());
        assertEquals("Synth", category.get("name").getAsString());
        assertEquals(10, category.get("hitCount").getAsInt());
        assertEquals(25, category.get("entryCount").getAsInt());
    }

    @Test
    void snapshot_arpeggiator_containsAllFields() {
        populateArpeggiator(cache);
        JsonObject arp = cache.getSnapshot().getAsJsonObject("arpeggiator");

        assertTrue(arp.get("isEnabled").getAsBoolean());
        assertEquals("up_down", arp.get("mode").getAsString());
        assertEquals(2, arp.get("octaves").getAsInt());
        assertEquals(0.25, arp.get("rate").getAsDouble());
        assertEquals(0.8, arp.get("gateLength").getAsDouble(), 0.001);
        assertTrue(arp.get("shuffle").getAsBoolean());
        assertEquals(0.1, arp.get("humanize").getAsDouble(), 0.001);
        assertFalse(arp.get("isFreeRunning").getAsBoolean());
        assertTrue(arp.get("enableOverlappingNotes").getAsBoolean());
        assertFalse(arp.get("usePressureToVelocity").getAsBoolean());
        assertFalse(arp.get("terminateNotesImmediately").getAsBoolean());
    }

    @Test
    void snapshot_noteLatch_containsAllFields() {
        populateNoteLatch(cache);
        JsonObject nl = cache.getSnapshot().getAsJsonObject("noteLatch");

        assertTrue(nl.get("isEnabled").getAsBoolean());
        assertEquals("hold", nl.get("mode").getAsString());
        assertFalse(nl.get("mono").getAsBoolean());
        assertEquals(64, nl.get("velocityThreshold").getAsInt());
        assertEquals(3, nl.get("activeNotes").getAsInt());
    }
}
