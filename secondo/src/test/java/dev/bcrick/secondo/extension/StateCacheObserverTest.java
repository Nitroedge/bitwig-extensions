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

    // --- Phase 26: browser read-side observers (D-26-12, D-26-14) ---

    /** Stubs the casts registerBrowserObservers / registerFilterObservers make on deep stubs. */
    private static PopupBrowser browserWithCastableItems(BrowserResultsItemBank bank,
                                                         BrowserFilterColumn[] columns,
                                                         CursorBrowserFilterItem[] cursors) {
        PopupBrowser popup = mock(PopupBrowser.class, RETURNS_DEEP_STUBS);
        when(popup.resultsColumn().createItemBank(8)).thenReturn(bank);
        for (int i = 0; i < 8; i++) {
            when(bank.getItemAt(i)).thenReturn(mock(BrowserResultsItem.class, RETURNS_DEEP_STUBS));
        }
        for (int i = 0; i < 8; i++) {
            columns[i] = mock(BrowserFilterColumn.class, RETURNS_DEEP_STUBS);
            cursors[i] = mock(CursorBrowserFilterItem.class, RETURNS_DEEP_STUBS);
            when(columns[i].createCursorItem()).thenReturn(cursors[i]);
        }
        when(popup.categoryColumn()).thenReturn(columns[0]);
        when(popup.tagColumn()).thenReturn(columns[1]);
        when(popup.creatorColumn()).thenReturn(columns[2]);
        when(popup.deviceColumn()).thenReturn(columns[3]);
        when(popup.deviceTypeColumn()).thenReturn(columns[4]);
        when(popup.fileTypeColumn()).thenReturn(columns[5]);
        when(popup.locationColumn()).thenReturn(columns[6]);
        when(popup.smartCollectionColumn()).thenReturn(columns[7]);
        return popup;
    }

    private static IntegerValueChangedCallback intObserver(IntegerValue value) {
        ArgumentCaptor<IntegerValueChangedCallback> captor =
                ArgumentCaptor.forClass(IntegerValueChangedCallback.class);
        verify(value).addValueObserver(captor.capture());
        return captor.getValue();
    }

    private static BooleanValueChangedCallback boolObserver(BooleanValue value) {
        ArgumentCaptor<BooleanValueChangedCallback> captor =
                ArgumentCaptor.forClass(BooleanValueChangedCallback.class);
        verify(value).addValueObserver(captor.capture());
        return captor.getValue();
    }

    @Test
    void registerBrowserObservers_marksContentTypeIndexAndResultBankScrollInterested() {
        BrowserResultsItemBank bank = mock(BrowserResultsItemBank.class, RETURNS_DEEP_STUBS);
        PopupBrowser popup = browserWithCastableItems(bank, new BrowserFilterColumn[8],
                new CursorBrowserFilterItem[8]);

        cache.registerBrowserObservers(popup);

        verify(popup.selectedContentTypeIndex()).markInterested();
        verify(bank.scrollPosition()).markInterested();
        verify(bank.itemCount()).markInterested();
        verify(bank.canScrollForwards()).markInterested();
        verify(bank.canScrollBackwards()).markInterested();

        assertTrue(cache.getResultBankState().get("scrollPosition").isJsonNull());

        intObserver(popup.selectedContentTypeIndex()).valueChanged(3);
        intObserver(bank.scrollPosition()).valueChanged(8);
        intObserver(bank.itemCount()).valueChanged(1661);
        boolObserver(bank.canScrollBackwards()).valueChanged(true);
        boolObserver(bank.canScrollForwards()).valueChanged(false);
        intObserver(popup.resultsColumn().entryCount()).valueChanged(0);

        JsonObject results = cache.getResultBankState();
        assertEquals(8, results.get("scrollPosition").getAsInt());
        assertEquals(1661, results.get("itemCount").getAsInt());
        assertTrue(results.get("canScrollBackwards").getAsBoolean());
        assertFalse(results.get("canScrollForwards").getAsBoolean());
        // An OBSERVED zero is a zero, not a null.
        assertEquals(0, results.get("entryCount").getAsInt());
        assertEquals(3, cache.getBrowserState().get("selectedContentTypeIndex").getAsInt());
    }

    @Test
    void registerFilterObservers_marksHasNextHasPreviousAndWildcardHitCountInterested() {
        BrowserFilterColumn[] columns = new BrowserFilterColumn[8];
        CursorBrowserFilterItem[] cursors = new CursorBrowserFilterItem[8];
        PopupBrowser popup = browserWithCastableItems(
                mock(BrowserResultsItemBank.class, RETURNS_DEEP_STUBS), columns, cursors);

        cache.registerFilterObservers(popup);

        for (int i = 0; i < 8; i++) {
            verify(cursors[i].hasNext()).markInterested();
            verify(cursors[i].hasPrevious()).markInterested();
            verify(columns[i].getWildcardItem().hitCount()).markInterested();
        }

        // deviceType is column 4: Bitwig's zero on the entry, a real count on the wildcard.
        intObserver(cursors[4].hitCount()).valueChanged(0);
        intObserver(columns[4].getWildcardItem().hitCount()).valueChanged(1661);
        boolObserver(cursors[4].hasNext()).valueChanged(true);
        boolObserver(cursors[4].hasPrevious()).valueChanged(false);

        JsonObject filters = cache.getBrowserState().getAsJsonObject("filters");
        JsonObject deviceType = filters.getAsJsonObject("deviceType");
        assertEquals(0, deviceType.get("hitCount").getAsInt());
        assertEquals(1661, deviceType.get("wildcardHitCount").getAsInt());
        assertTrue(deviceType.get("hasNext").getAsBoolean());
        assertFalse(deviceType.get("hasPrevious").getAsBoolean());
        JsonObject category = filters.getAsJsonObject("category");
        assertTrue(category.get("hitCount").isJsonNull());
        assertTrue(category.get("wildcardHitCount").isJsonNull());
        assertTrue(category.get("name").isJsonNull());
    }

    private static List<Integer> jsonInts(JsonArray values) {
        List<Integer> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsInt()));
        return result;
    }

}
