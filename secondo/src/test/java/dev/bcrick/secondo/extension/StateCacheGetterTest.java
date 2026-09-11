package dev.bcrick.secondo.extension;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.bcrick.secondo.extension.StateCacheTestHelper.*;
import static org.junit.jupiter.api.Assertions.*;

class StateCacheGetterTest {

    private StateCache cache;

    @BeforeEach
    void setUp() {
        cache = new StateCache();
    }

    // --- getTrackName ---

    @Test
    void getTrackName_validIndex_returnsName() {
        setArrayElement(cache, "trackNames", 3, "Drums");
        assertEquals("Drums", cache.getTrackName(3));
    }

    @Test
    void getTrackName_nullName_returnsEmpty() {
        assertEquals("", cache.getTrackName(0));
    }

    @Test
    void getTrackName_negativeIndex_returnsEmpty() {
        assertEquals("", cache.getTrackName(-1));
    }

    @Test
    void getTrackName_indexTooLarge_returnsEmpty() {
        assertEquals("", cache.getTrackName(8));
    }

    // --- clipHasContent ---

    @Test
    void clipHasContent_validIndices_returnsTrue() {
        set2DArrayElement(cache, "clipHasContent", 2, 3, true);
        assertTrue(cache.clipHasContent(2, 3));
    }

    @Test
    void clipHasContent_defaultFalse() {
        assertFalse(cache.clipHasContent(0, 0));
    }

    @Test
    void clipHasContent_negativeTrack_returnsFalse() {
        assertFalse(cache.clipHasContent(-1, 0));
    }

    @Test
    void clipHasContent_trackTooLarge_returnsFalse() {
        assertFalse(cache.clipHasContent(8, 0));
    }

    @Test
    void clipHasContent_slotTooLarge_returnsFalse() {
        assertFalse(cache.clipHasContent(0, 5));
    }

    // --- clipStepSize ---

    @Test
    void getClipStepSize_default() {
        assertEquals(0.25, cache.getClipStepSize());
    }

    @Test
    void setClipStepSize_updatesValue() {
        cache.setClipStepSize(0.5);
        assertEquals(0.5, cache.getClipStepSize());
    }

    // --- Boolean project state ---

    @Test
    void hasSoloedTracks_default() {
        assertFalse(cache.hasSoloedTracks());
    }

    @Test
    void hasSoloedTracks_afterSet() {
        setField(cache, "hasSoloedTracks", true);
        assertTrue(cache.hasSoloedTracks());
    }

    @Test
    void hasMutedTracks_afterSet() {
        setField(cache, "hasMutedTracks", true);
        assertTrue(cache.hasMutedTracks());
    }

    @Test
    void hasArmedTracks_afterSet() {
        setField(cache, "hasArmedTracks", true);
        assertTrue(cache.hasArmedTracks());
    }

    @Test
    void isModified_default() {
        assertFalse(cache.isModified());
    }

    // --- Scroll info JSON structures ---

    @Test
    void getSceneBankScrollInfo_structure() {
        setField(cache, "sceneBankOffset", 5);
        setField(cache, "sceneItemCount", 20);
        setField(cache, "sceneCanScrollBackwards", true);
        setField(cache, "sceneCanScrollForwards", true);

        JsonObject info = cache.getSceneBankScrollInfo();
        assertEquals(5, info.get("scrollPosition").getAsInt());
        assertEquals(20, info.get("itemCount").getAsInt());
        assertEquals(sceneCountOf(StateCache.class), info.get("bankSize").getAsInt());
        assertTrue(info.get("canScrollBackwards").getAsBoolean());
        assertTrue(info.get("canScrollForwards").getAsBoolean());
    }

    @Test
    void getTrackBankScrollInfo_structure() {
        setField(cache, "trackScrollPosition", 8);
        setField(cache, "trackItemCount", 32);

        JsonObject info = cache.getTrackBankScrollInfo();
        assertEquals(8, info.get("scrollPosition").getAsInt());
        assertEquals(32, info.get("itemCount").getAsInt());
        assertEquals(trackCountOf(StateCache.class), info.get("bankSize").getAsInt());
    }

    @Test
    void getCueMarkerBankScrollInfo_structure() {
        setField(cache, "cueMarkerScrollPosition", 0);
        setField(cache, "cueMarkerItemCount", 4);

        JsonObject info = cache.getCueMarkerBankScrollInfo();
        assertEquals(0, info.get("scrollPosition").getAsInt());
        assertEquals(4, info.get("itemCount").getAsInt());
        assertEquals(16, info.get("bankSize").getAsInt());
    }

    // --- Item counts ---

    @Test
    void getSceneItemCount_default() {
        assertEquals(0, cache.getSceneItemCount());
    }

    @Test
    void getTrackItemCount_default() {
        assertEquals(0, cache.getTrackItemCount());
    }

    @Test
    void getCueMarkerItemCount_default() {
        assertEquals(0, cache.getCueMarkerItemCount());
    }

    // --- Specialized state methods ---

    @Test
    void getBrowserState_structure() {
        populateBrowser(cache);
        JsonObject browser = cache.getBrowserState();
        assertTrue(browser.has("exists"));
        assertTrue(browser.has("title"));
        assertTrue(browser.has("selectedContentType"));
        assertTrue(browser.has("contentTypeNames"));
        assertTrue(browser.has("canAudition"));
        assertTrue(browser.has("shouldAudition"));
        assertTrue(browser.has("resultName"));
        assertTrue(browser.has("resultIsSelected"));
        assertTrue(browser.has("resultsEntryCount"));
        assertTrue(browser.has("filters"));
    }

    @Test
    void getResultBankState_structure() {
        setArrayElement(cache, "resultBankNames", 0, "Pad 1");
        setArrayElement(cache, "resultBankSelected", 0, true);
        setField(cache, "resultsEntryCount", 15);

        JsonObject state = cache.getResultBankState();
        assertEquals(15, state.get("entryCount").getAsInt());
        assertEquals(8, state.getAsJsonArray("items").size());
        assertEquals("Pad 1", state.getAsJsonArray("items").get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(state.getAsJsonArray("items").get(0).getAsJsonObject().get("isSelected").getAsBoolean());
    }

    @Test
    void getClipLaunchSettings_structure() {
        populateClip(cache);
        JsonObject settings = cache.getClipLaunchSettings();
        assertTrue(settings.has("launchQuantization"));
        assertTrue(settings.has("launchMode"));
        assertTrue(settings.has("shuffle"));
        assertTrue(settings.has("accent"));
        assertTrue(settings.has("useLoopStartAsQuantizationReference"));
    }

    @Test
    void getClipPlaybackSettings_structure() {
        populateClip(cache);
        JsonObject settings = cache.getClipPlaybackSettings();
        assertTrue(settings.has("playStart"));
        assertTrue(settings.has("playStop"));
        assertTrue(settings.has("loopStart"));
        assertTrue(settings.has("loopLength"));
        assertTrue(settings.has("isLoopEnabled"));
    }

    @Test
    void getClipLauncherSettings_structure() {
        populateTransport(cache);
        JsonObject settings = cache.getClipLauncherSettings();
        assertTrue(settings.has("defaultLaunchQuantization"));
        assertTrue(settings.has("clipLauncherPostRecordingAction"));
        assertTrue(settings.has("clipLauncherPostRecordingTimeOffset"));
        assertTrue(settings.has("clipLauncherOverdubEnabled"));
        assertTrue(settings.has("fillModeActive"));
    }

    @Test
    void getArpeggiatorState_structure() {
        populateArpeggiator(cache);
        JsonObject state = cache.getArpeggiatorState();
        assertEquals(11, state.size());
        assertTrue(state.get("isEnabled").getAsBoolean());
        assertEquals("up_down", state.get("mode").getAsString());
    }

    @Test
    void getNoteLatchState_structure() {
        populateNoteLatch(cache);
        JsonObject state = cache.getNoteLatchState();
        assertEquals(5, state.size());
        assertTrue(state.get("isEnabled").getAsBoolean());
        assertEquals("hold", state.get("mode").getAsString());
    }
}
