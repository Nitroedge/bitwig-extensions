package dev.bcrick.secondo.extension;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that registerObservers() wires Bitwig API callbacks to StateCache fields.
 * Uses RETURNS_DEEP_STUBS to auto-mock the deeply-nested Bitwig value object chains.
 * Selective ArgumentCaptor captures verify the callback→field→snapshot pipeline.
 */
class StateCacheObserverTest {

    private StateCache cache;
    private Transport transport;
    private TrackBank trackBank;
    private MasterTrack masterTrack;
    private Application application;
    private Project project;

    @BeforeEach
    void setUp() {
        cache = new StateCache();

        transport = mock(Transport.class, RETURNS_DEEP_STUBS);
        trackBank = mock(TrackBank.class, RETURNS_DEEP_STUBS);
        masterTrack = mock(MasterTrack.class, RETURNS_DEEP_STUBS);
        application = mock(Application.class, RETURNS_DEEP_STUBS);
        project = mock(Project.class, RETURNS_DEEP_STUBS);

        // trackBank.getItemAt(i) is cast to Track in production code —
        // deep stubs return a generic proxy that can't be cast, so we
        // must explicitly return Track mocks.
        for (int i = 0; i < 8; i++) {
            Track track = mock(Track.class, RETURNS_DEEP_STUBS);
            when(trackBank.getItemAt(i)).thenReturn(track);
        }

        cache.registerObservers(transport, trackBank, masterTrack, application, project);
    }

    @Test
    void registerObservers_registersTransportPlayingCallback() {
        ArgumentCaptor<BooleanValueChangedCallback> captor =
                ArgumentCaptor.forClass(BooleanValueChangedCallback.class);
        verify(transport.isPlaying()).addValueObserver(captor.capture());

        captor.getValue().valueChanged(true);

        assertTrue(cache.getSnapshot().getAsJsonObject("transport")
                .get("isPlaying").getAsBoolean());
    }

    @Test
    void registerObservers_registersTempoCallback() {
        ArgumentCaptor<DoubleValueChangedCallback> captor =
                ArgumentCaptor.forClass(DoubleValueChangedCallback.class);
        verify(transport.tempo().value()).addRawValueObserver(captor.capture());

        captor.getValue().valueChanged(140.0);

        assertEquals(140.0, cache.getSnapshot().getAsJsonObject("transport")
                .get("tempo").getAsDouble());
    }

    @Test
    void registerObservers_registersPlayPositionCallback() {
        ArgumentCaptor<DoubleValueChangedCallback> captor =
                ArgumentCaptor.forClass(DoubleValueChangedCallback.class);
        verify(transport.playPosition()).addValueObserver(captor.capture());

        captor.getValue().valueChanged(8.5);

        assertEquals(8.5, cache.getSnapshot().getAsJsonObject("transport")
                .get("playPosition").getAsDouble());
    }

    @Test
    void registerObservers_registersTrackNameCallback() {
        Track track0 = (Track) trackBank.getItemAt(0);

        ArgumentCaptor<StringValueChangedCallback> captor =
                ArgumentCaptor.forClass(StringValueChangedCallback.class);
        verify(track0.name()).addValueObserver(captor.capture());

        captor.getValue().valueChanged("Synth Lead");

        assertEquals("Synth Lead", cache.getTrackName(0));
    }

    @Test
    void registerObservers_callbackUpdatesChangedSections() {
        // Prime the hash by calling getChangedSections once (resets prevTransportHash)
        cache.getChangedSections();

        // Fire the isPlaying callback to change transport state
        ArgumentCaptor<BooleanValueChangedCallback> captor =
                ArgumentCaptor.forClass(BooleanValueChangedCallback.class);
        verify(transport.isPlaying()).addValueObserver(captor.capture());
        captor.getValue().valueChanged(true);

        assertTrue(cache.getChangedSections().contains("transport"));
    }

    @Test
    void coldActivationAndItemCountRemainUnobserved() {
        JsonObject tracks = cache.getSnapshot().getAsJsonObject("tracks");
        assertFalse(tracks.get("itemCountObserved").getAsBoolean());
        assertTrue(tracks.get("itemCount").isJsonNull());

        JsonObject first = tracks.getAsJsonArray("tracks").get(0).getAsJsonObject();
        assertTrue(first.get("activated").isJsonNull());
        assertTrue(first.get("effectiveActivated").isJsonNull());
        assertEquals(List.of(0), jsonInts(first.getAsJsonArray("unobservedActivationIndices")));

        Track track0 = (Track) trackBank.getItemAt(0);
        ArgumentCaptor<BooleanValueChangedCallback> activation =
            ArgumentCaptor.forClass(BooleanValueChangedCallback.class);
        verify(track0.isActivated()).addValueObserver(activation.capture());
        activation.getValue().valueChanged(true);

        ArgumentCaptor<IntegerValueChangedCallback> count =
            ArgumentCaptor.forClass(IntegerValueChangedCallback.class);
        verify(trackBank.itemCount()).addValueObserver(count.capture());
        count.getValue().valueChanged(7);

        JsonObject observed = cache.getSnapshot().getAsJsonObject("tracks");
        assertTrue(observed.get("itemCountObserved").getAsBoolean());
        assertEquals(7, observed.get("itemCount").getAsInt());
        assertTrue(observed.getAsJsonArray("tracks").get(0).getAsJsonObject()
            .get("activated").getAsBoolean());
    }

    private static List<Integer> jsonInts(JsonArray values) {
        List<Integer> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsInt()));
        return result;
    }

}
