package dev.bcrick.secondo.extension;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StateCacheDeltaTest {

    private StateCache cache;

    @BeforeEach
    void setUp() {
        cache = new StateCache();
    }

    @Test
    void firstCallReportsAllSectionsChanged() {
        List<String> changed = cache.getChangedSections();
        // On first call, hashes are all 0 (default int), but the serialized
        // JSON of default state will have a non-zero hash, so all sections
        // should be reported as changed.
        assertEquals(14, changed.size());
        assertTrue(changed.contains("transport"));
        assertTrue(changed.contains("tracks"));
        assertTrue(changed.contains("scenes"));
        assertTrue(changed.contains("device"));
        assertTrue(changed.contains("clip"));
        assertTrue(changed.contains("master"));
        assertTrue(changed.contains("application"));
        assertTrue(changed.contains("arranger"));
        assertTrue(changed.contains("arrangement"));
        assertTrue(changed.contains("masterDevice"));
        assertTrue(changed.contains("browser"));
        assertTrue(changed.contains("arpeggiator"));
        assertTrue(changed.contains("noteLatch"));
        assertTrue(changed.contains("groove"));
    }

    @Test
    void secondCallWithNoChangesReportsEmpty() {
        // First call initializes hashes
        cache.getChangedSections();
        // Second call with no state changes
        List<String> changed = cache.getChangedSections();
        assertTrue(changed.isEmpty(), "Expected no changes but got: " + changed);
    }

    @Test
    void snapshotStillWorksAfterDeltaDetection() {
        cache.getChangedSections();
        var snapshot = cache.getSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.has("transport"));
        assertTrue(snapshot.has("tracks"));
        assertTrue(snapshot.has("scenes"));
        assertTrue(snapshot.has("device"));
        assertTrue(snapshot.has("clip"));
        assertTrue(snapshot.has("master"));
        assertTrue(snapshot.has("application"));
        assertTrue(snapshot.has("arranger"));
        assertTrue(snapshot.has("arrangement"));
        assertTrue(snapshot.has("masterDevice"));
        assertTrue(snapshot.has("browser"));
        assertTrue(snapshot.has("arpeggiator"));
        assertTrue(snapshot.has("noteLatch"));
        assertTrue(snapshot.has("groove"));
    }

    @Test
    void repeatedCallsWithNoChangesAlwaysEmpty() {
        cache.getChangedSections(); // init
        for (int i = 0; i < 5; i++) {
            List<String> changed = cache.getChangedSections();
            assertTrue(changed.isEmpty(), "Iteration " + i + " reported changes: " + changed);
        }
    }

    // --- getDelta() tests ---

    @Test
    void getDelta_firstCallReturnsAllSectionsWithData() {
        JsonObject delta = cache.getDelta();
        assertNotNull(delta);
        assertEquals(14, delta.getAsJsonArray("changed").size());
        JsonObject data = delta.getAsJsonObject("data");
        assertEquals(14, data.size());
        assertTrue(data.has("transport"));
        assertTrue(data.has("device"));
        assertTrue(data.has("tracks"));
        // Verify data contains actual state, not just names
        assertTrue(data.getAsJsonObject("transport").has("isPlaying"));
        assertTrue(data.getAsJsonObject("device").has("remoteControls"));
    }

    @Test
    void getDelta_returnsNullWhenNothingChanged() {
        cache.getDelta(); // init hashes
        JsonObject delta = cache.getDelta();
        assertNull(delta, "Expected null delta when nothing changed");
    }

    @Test
    void getDelta_clearsChangedState() {
        cache.getDelta(); // first call sets hashes
        // Mutate a field via reflection
        StateCacheTestHelper.setField(cache, "tempo", 140.0);
        JsonObject delta1 = cache.getDelta();
        assertNotNull(delta1);
        assertTrue(delta1.getAsJsonArray("changed").toString().contains("transport"));

        // Second call with no further changes
        JsonObject delta2 = cache.getDelta();
        assertNull(delta2, "Expected null after change was already reported");
    }

    @Test
    void getDelta_reportsOnlyChangedSections() {
        cache.getDelta(); // init hashes
        // Change only transport
        StateCacheTestHelper.setField(cache, "isPlaying", true);
        JsonObject delta = cache.getDelta();
        assertNotNull(delta);
        JsonObject data = delta.getAsJsonObject("data");
        assertEquals(1, data.size(), "Only transport should have changed");
        assertTrue(data.has("transport"));
        assertTrue(data.getAsJsonObject("transport").get("isPlaying").getAsBoolean());
    }

    @Test
    void getDelta_multipleSectionsChanged() {
        cache.getDelta(); // init
        // Change transport and device
        StateCacheTestHelper.setField(cache, "tempo", 90.0);
        StateCacheTestHelper.setField(cache, "deviceName", "Polysynth");
        JsonObject delta = cache.getDelta();
        assertNotNull(delta);
        JsonObject data = delta.getAsJsonObject("data");
        assertTrue(data.has("transport"));
        assertTrue(data.has("device"));
        assertEquals(data.size(), delta.getAsJsonArray("changed").size());
    }
}
