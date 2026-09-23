package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.BrowserFilterColumn;
import com.bitwig.extension.controller.api.BrowserFilterItem;
import com.bitwig.extension.controller.api.BrowserFilterItemBank;
import com.bitwig.extension.controller.api.BrowserResultsItemBank;
import com.bitwig.extension.controller.api.CursorBrowserFilterItem;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.InsertionPoint;
import com.bitwig.extension.controller.api.MasterTrack;
import com.bitwig.extension.controller.api.PopupBrowser;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableIntegerValue;
import com.google.gson.JsonObject;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BrowserHandlerTest {

    @Mock private PopupBrowser mockPopupBrowser;
    @Mock private CursorDevice mockCursorDevice;
    @Mock private MasterTrack mockMasterTrack;
    @Mock private CursorDevice mockMasterCursorDevice;
    @Mock private StateCache mockStateCache;

    // Chain mocks
    @Mock private InsertionPoint mockInsertionPoint;
    @Mock private SettableIntegerValue mockContentTypeIndex;
    @Mock private SettableBooleanValue mockShouldAudition;
    @Mock private CursorBrowserFilterItem mockFilterCursor;
    @Mock private BrowserFilterColumn mockFilterColumn;
    @Mock private CursorBrowserFilterItem mockWildcard;
    @Mock private SettableBooleanValue mockWildcardSelected;
    @Mock private BrowserResultsItemBank mockResultBank;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // Stub filter cursors — index 0 = "category"
        CursorBrowserFilterItem[] cursors = new CursorBrowserFilterItem[8];
        cursors[0] = mockFilterCursor;
        when(mockStateCache.getFilterCursors()).thenReturn(cursors);

        // Stub filter columns — index 0 = "category"
        BrowserFilterColumn[] columns = new BrowserFilterColumn[8];
        columns[0] = mockFilterColumn;
        when(mockStateCache.getFilterColumns()).thenReturn(columns);
        when(mockFilterColumn.getWildcardItem()).thenReturn(mockWildcard);
        when(mockWildcard.isSelected()).thenReturn(mockWildcardSelected);

        // Stub result bank
        when(mockStateCache.getResultBank()).thenReturn(mockResultBank);

        // Stub state queries
        JsonObject browserState = new JsonObject();
        JsonObject filters = new JsonObject();
        browserState.add("filters", filters);
        when(mockStateCache.getBrowserState()).thenReturn(browserState);
        when(mockStateCache.getResultBankState()).thenReturn(new JsonObject());

        dispatcher = new JsonRpcDispatcher();
        new BrowserHandler(mockPopupBrowser, mockCursorDevice, mockMasterTrack,
            mockMasterCursorDevice, mockStateCache).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersTwentySixMethods() {
        var methods = dispatcher.getRegisteredMethods();
        // Phase 17 — 11 methods
        assertTrue(methods.contains("browser/browsePresets"));
        assertTrue(methods.contains("browser/browseInsertDevice"));
        assertTrue(methods.contains("browser/selectNextFile"));
        assertTrue(methods.contains("browser/selectPreviousFile"));
        assertTrue(methods.contains("browser/selectFirstFile"));
        assertTrue(methods.contains("browser/selectLastFile"));
        assertTrue(methods.contains("browser/commit"));
        assertTrue(methods.contains("browser/cancel"));
        assertTrue(methods.contains("browser/setContentType"));
        assertTrue(methods.contains("browser/setShouldAudition"));
        assertTrue(methods.contains("browser/getState"));
        // Phase 18 — 10 methods
        assertTrue(methods.contains("browser/filterSelectNext"));
        assertTrue(methods.contains("browser/filterSelectPrevious"));
        assertTrue(methods.contains("browser/filterSelectFirst"));
        assertTrue(methods.contains("browser/filterSelectLast"));
        assertTrue(methods.contains("browser/filterSelectParent"));
        assertTrue(methods.contains("browser/filterSelectFirstChild"));
        assertTrue(methods.contains("browser/filterReset"));
        assertTrue(methods.contains("browser/getFilters"));
        assertTrue(methods.contains("browser/getResults"));
        assertTrue(methods.contains("browser/scrollResults"));
        // Phase 26 — 2 master chain openers
        assertTrue(methods.contains("browser/browseMasterInsertDevice"));
        assertTrue(methods.contains("browser/browseMasterPresets"));
        // Phase 28 — the filter item bank (D-28-32)
        assertTrue(methods.contains("browser/getFilterItems"));
        assertTrue(methods.contains("browser/scrollFilterItems"));
        assertTrue(methods.contains("browser/setFilterItemSelected"));
        assertEquals(26, methods.size());
    }

    // --- setContentType validation ---

    @Test
    void setContentType_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("browser/setContentType", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    // --- setShouldAudition validation ---

    @Test
    void setShouldAudition_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("browser/setShouldAudition", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- Filter validation ---

    @Test
    void filterSelectNext_missingColumn_returnsError() {
        String response = dispatcher.handle(rpc("browser/filterSelectNext", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "column");
    }

    @Test
    void filterSelectNext_invalidColumn_returnsError() {
        String response = dispatcher.handle(rpc("browser/filterSelectNext",
            "{\"column\": \"invalid\"}"));
        assertContains(response, "-32602");
        assertContains(response, "invalid");
    }

    @Test
    void filterReset_missingColumn_returnsError() {
        String response = dispatcher.handle(rpc("browser/filterReset", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "column");
    }

    // --- scrollResults validation ---

    @Test
    void scrollResults_missingDirection_returnsError() {
        String response = dispatcher.handle(rpc("browser/scrollResults", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "direction");
    }

    @Test
    void scrollResults_invalidDirection_returnsError() {
        String response = dispatcher.handle(rpc("browser/scrollResults",
            "{\"direction\": \"sideways\"}"));
        assertContains(response, "-32602");
        assertContains(response, "sideways");
    }

    // --- Behavioral tests (Mockito) — Browser opening ---

    @Test
    void browsePresets_callsCursorDeviceReplaceInsertionPointBrowse() {
        when(mockCursorDevice.replaceDeviceInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("browser/browsePresets", "{}"));
        verify(mockInsertionPoint).browse();
    }

    @Test
    void browseInsertDevice_callsCursorDeviceAfterInsertionPointBrowse() {
        when(mockCursorDevice.afterDeviceInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("browser/browseInsertDevice", "{}"));
        verify(mockInsertionPoint).browse();
    }

    @Test
    void browseMasterInsertDevice_callsMasterTrackEndOfDeviceChainInsertionPointBrowse() {
        when(mockMasterTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);
        String response = dispatcher.handle(rpc("browser/browseMasterInsertDevice", "{}"));
        assertContains(response, "\"ok\"");
        verify(mockInsertionPoint).browse();
        verifyNoInteractions(mockCursorDevice);
        verifyNoInteractions(mockMasterCursorDevice);
    }

    @Test
    void browseMasterPresets_callsMasterCursorDeviceReplaceInsertionPointBrowse() {
        when(mockMasterCursorDevice.replaceDeviceInsertionPoint()).thenReturn(mockInsertionPoint);
        String response = dispatcher.handle(rpc("browser/browseMasterPresets", "{}"));
        assertContains(response, "\"ok\"");
        verify(mockInsertionPoint).browse();
        verifyNoInteractions(mockCursorDevice);
    }

    // --- Behavioral tests (Mockito) — Result navigation ---

    @Test
    void selectNextFile_callsPopupBrowserSelectNextFile() {
        dispatcher.handle(rpc("browser/selectNextFile", "{}"));
        verify(mockPopupBrowser).selectNextFile();
    }

    @Test
    void selectPreviousFile_callsPopupBrowserSelectPreviousFile() {
        dispatcher.handle(rpc("browser/selectPreviousFile", "{}"));
        verify(mockPopupBrowser).selectPreviousFile();
    }

    @Test
    void selectFirstFile_callsPopupBrowserSelectFirstFile() {
        dispatcher.handle(rpc("browser/selectFirstFile", "{}"));
        verify(mockPopupBrowser).selectFirstFile();
    }

    @Test
    void selectLastFile_callsPopupBrowserSelectLastFile() {
        dispatcher.handle(rpc("browser/selectLastFile", "{}"));
        verify(mockPopupBrowser).selectLastFile();
    }

    // --- Behavioral tests (Mockito) — Commit / cancel ---

    @Test
    void commit_callsPopupBrowserCommit() {
        dispatcher.handle(rpc("browser/commit", "{}"));
        verify(mockPopupBrowser).commit();
    }

    @Test
    void cancel_callsPopupBrowserCancel() {
        dispatcher.handle(rpc("browser/cancel", "{}"));
        verify(mockPopupBrowser).cancel();
    }

    // --- Behavioral tests (Mockito) — Settings ---

    @Test
    void setContentType_callsPopupBrowserSelectedContentTypeIndexSet() {
        when(mockPopupBrowser.selectedContentTypeIndex()).thenReturn(mockContentTypeIndex);
        dispatcher.handle(rpc("browser/setContentType", "{\"index\":2}"));
        verify(mockContentTypeIndex).set(2);
    }

    @Test
    void setShouldAudition_callsPopupBrowserShouldAuditionSet() {
        when(mockPopupBrowser.shouldAudition()).thenReturn(mockShouldAudition);
        dispatcher.handle(rpc("browser/setShouldAudition", "{\"enabled\":true}"));
        verify(mockShouldAudition).set(true);
    }

    // --- Behavioral tests (Mockito) — Filter cursor navigation ---

    @Test
    void filterSelectNext_callsCursorSelectNext() {
        dispatcher.handle(rpc("browser/filterSelectNext", "{\"column\":\"category\"}"));
        verify(mockFilterCursor).selectNext();
    }

    @Test
    void filterSelectPrevious_callsCursorSelectPrevious() {
        dispatcher.handle(rpc("browser/filterSelectPrevious", "{\"column\":\"category\"}"));
        verify(mockFilterCursor).selectPrevious();
    }

    @Test
    void filterSelectFirst_callsCursorSelectFirst() {
        dispatcher.handle(rpc("browser/filterSelectFirst", "{\"column\":\"category\"}"));
        verify(mockFilterCursor).selectFirst();
    }

    @Test
    void filterSelectLast_callsCursorSelectLast() {
        dispatcher.handle(rpc("browser/filterSelectLast", "{\"column\":\"category\"}"));
        verify(mockFilterCursor).selectLast();
    }

    @Test
    void filterSelectParent_callsCursorSelectParent() {
        dispatcher.handle(rpc("browser/filterSelectParent", "{\"column\":\"category\"}"));
        verify(mockFilterCursor).selectParent();
    }

    @Test
    void filterSelectFirstChild_callsCursorSelectFirstChild() {
        dispatcher.handle(rpc("browser/filterSelectFirstChild", "{\"column\":\"category\"}"));
        verify(mockFilterCursor).selectFirstChild();
    }

    // --- Behavioral tests (Mockito) — Filter reset ---

    @Test
    void filterReset_setsWildcardSelectedTrue() {
        dispatcher.handle(rpc("browser/filterReset", "{\"column\":\"category\"}"));
        verify(mockWildcardSelected).set(true);
    }

    // --- Behavioral tests (Mockito) — State queries ---

    @Test
    void getFilters_returnsBrowserStateFilters() {
        String response = dispatcher.handle(rpc("browser/getFilters", "{}"));
        assertContains(response, "\"result\"");
    }

    @Test
    void getResults_returnsResultBankState() {
        String response = dispatcher.handle(rpc("browser/getResults", "{}"));
        assertContains(response, "\"result\"");
    }

    // --- Behavioral tests (Mockito) — Scroll results ---

    @Test
    void scrollResults_forward_callsBankScrollForwards() {
        dispatcher.handle(rpc("browser/scrollResults", "{\"direction\":\"forward\"}"));
        verify(mockResultBank).scrollForwards();
    }

    @Test
    void scrollResults_pageBackward_callsBankScrollPageBackwards() {
        dispatcher.handle(rpc("browser/scrollResults", "{\"direction\":\"pageBackward\"}"));
        verify(mockResultBank).scrollPageBackwards();
    }

    // --- Phase 28 (D-28-32): the filter item bank ---

    @Test
    void getFilterItems_tag_returnsTheStateCachePayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("column", "tag");
        payload.addProperty("bankSize", 64);
        when(mockStateCache.getFilterItemBankState(1)).thenReturn(payload);

        String response = dispatcher.handle(rpc("browser/getFilterItems", "{\"column\":\"tag\"}"));

        assertContains(response, "\"result\":{\"column\":\"tag\",\"bankSize\":64}");
        verify(mockStateCache).getFilterItemBankState(1);
    }

    @Test
    void getFilterItems_categoryAndCreator_resolveToColumnsZeroAndTwo() {
        when(mockStateCache.getFilterItemBankState(anyInt())).thenReturn(new JsonObject());
        dispatcher.handle(rpc("browser/getFilterItems", "{\"column\":\"category\"}"));
        dispatcher.handle(rpc("browser/getFilterItems", "{\"column\":\"creator\"}"));
        verify(mockStateCache).getFilterItemBankState(0);
        verify(mockStateCache).getFilterItemBankState(2);
    }

    @Test
    void getFilterItems_unbankedColumn_returnsError() {
        String response = dispatcher.handle(rpc("browser/getFilterItems",
            "{\"column\":\"device\"}"));
        assertContains(response, "-32602");
        assertContains(response, "device");
        assertContains(response, "[category, tag, creator]");
        verify(mockStateCache, never()).getFilterItemBankState(anyInt());
    }

    @Test
    void getFilterItems_missingColumn_returnsError() {
        String response = dispatcher.handle(rpc("browser/getFilterItems", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "column");
        verify(mockStateCache, never()).getFilterItemBankState(anyInt());
    }

    // --- Phase 28 (D-28-32): scrollFilterItems ---

    private BrowserFilterItemBank stubTagBank() {
        BrowserFilterItemBank bank = mock(BrowserFilterItemBank.class);
        when(mockStateCache.getFilterItemBank(1)).thenReturn(bank);
        return bank;
    }

    @Test
    void scrollFilterItems_forward_callsOnlyBankScrollForwards() {
        BrowserFilterItemBank bank = stubTagBank();
        String response = dispatcher.handle(rpc("browser/scrollFilterItems",
            "{\"column\":\"tag\",\"direction\":\"forward\"}"));
        assertContains(response, "\"ok\"");
        verify(bank).scrollForwards();
        verifyNoMoreInteractions(bank);
    }

    @Test
    void scrollFilterItems_backward_callsOnlyBankScrollBackwards() {
        BrowserFilterItemBank bank = stubTagBank();
        dispatcher.handle(rpc("browser/scrollFilterItems",
            "{\"column\":\"tag\",\"direction\":\"backward\"}"));
        verify(bank).scrollBackwards();
        verifyNoMoreInteractions(bank);
    }

    @Test
    void scrollFilterItems_pageForward_callsOnlyBankScrollPageForwards() {
        BrowserFilterItemBank bank = stubTagBank();
        dispatcher.handle(rpc("browser/scrollFilterItems",
            "{\"column\":\"tag\",\"direction\":\"pageForward\"}"));
        verify(bank).scrollPageForwards();
        verifyNoMoreInteractions(bank);
    }

    @Test
    void scrollFilterItems_pageBackward_callsOnlyBankScrollPageBackwards() {
        BrowserFilterItemBank bank = stubTagBank();
        dispatcher.handle(rpc("browser/scrollFilterItems",
            "{\"column\":\"tag\",\"direction\":\"pageBackward\"}"));
        verify(bank).scrollPageBackwards();
        verifyNoMoreInteractions(bank);
    }

    @Test
    void scrollFilterItems_invalidDirection_isRefusedBeforeAnyScroll() {
        BrowserFilterItemBank bank = stubTagBank();
        String response = dispatcher.handle(rpc("browser/scrollFilterItems",
            "{\"column\":\"tag\",\"direction\":\"sideways\"}"));
        assertContains(response, "-32602");
        assertContains(response, "sideways");
        verifyNoInteractions(bank);
    }

    @Test
    void scrollFilterItems_missingDirection_isRefused() {
        BrowserFilterItemBank bank = stubTagBank();
        String response = dispatcher.handle(rpc("browser/scrollFilterItems",
            "{\"column\":\"tag\"}"));
        assertContains(response, "-32602");
        assertContains(response, "direction");
        verifyNoInteractions(bank);
    }

    @Test
    void scrollFilterItems_unbankedColumn_isRefused() {
        String response = dispatcher.handle(rpc("browser/scrollFilterItems",
            "{\"column\":\"location\",\"direction\":\"forward\"}"));
        assertContains(response, "-32602");
        assertContains(response, "location");
        verify(mockStateCache, never()).getFilterItemBank(anyInt());
    }

    // --- Phase 28 (D-28-32): setFilterItemSelected, the compare-and-set ---

    /** The tag bank with a castable item at slot 5 whose selection is `selected`. */
    private SettableBooleanValue stubTagItemAtSlotFive() {
        BrowserFilterItemBank bank = stubTagBank();
        BrowserFilterItem item = mock(BrowserFilterItem.class);
        SettableBooleanValue selected = mock(SettableBooleanValue.class);
        when(item.isSelected()).thenReturn(selected);
        when(bank.getItemAt(5)).thenReturn(item);
        return selected;
    }

    @Test
    void setFilterItemSelected_matchingName_setsTheItemSelected() {
        SettableBooleanValue selected = stubTagItemAtSlotFive();
        when(mockStateCache.getFilterItemName(1, 5)).thenReturn("secondo");

        String response = dispatcher.handle(rpc("browser/setFilterItemSelected",
            "{\"column\":\"tag\",\"slot\":5,\"name\":\"secondo\"}"));

        assertContains(response, "\"result\":\"ok\"");
        verify(selected).set(true);
        verifyNoMoreInteractions(selected);
    }

    @Test
    void setFilterItemSelected_selectedFalse_setsTheItemDeselected() {
        SettableBooleanValue selected = stubTagItemAtSlotFive();
        when(mockStateCache.getFilterItemName(1, 5)).thenReturn("secondo");

        String response = dispatcher.handle(rpc("browser/setFilterItemSelected",
            "{\"column\":\"tag\",\"slot\":5,\"name\":\"secondo\",\"selected\":false}"));

        assertContains(response, "\"result\":\"ok\"");
        verify(selected).set(false);
        verifyNoMoreInteractions(selected);
    }

    @Test
    void setFilterItemSelected_mismatchingName_refusesMinus32001AndNeverSets() {
        SettableBooleanValue selected = stubTagItemAtSlotFive();
        when(mockStateCache.getFilterItemName(1, 5)).thenReturn("Bass");
        when(mockStateCache.getFilterItemBankScrollPosition(1)).thenReturn(64);

        String response = dispatcher.handle(rpc("browser/setFilterItemSelected",
            "{\"column\":\"tag\",\"slot\":5,\"name\":\"secondo\"}"));

        JsonObject error = com.google.gson.JsonParser.parseString(response).getAsJsonObject()
            .getAsJsonObject("error");
        assertEquals(-32001, error.get("code").getAsInt());
        assertEquals("FILTER_ITEM_NAME_MISMATCH", error.get("message").getAsString());
        JsonObject data = error.getAsJsonObject("data");
        assertEquals(java.util.Set.of("column", "slot", "expected", "observed", "scrollPosition"),
            data.keySet());
        assertEquals("tag", data.get("column").getAsString());
        assertEquals(5, data.get("slot").getAsInt());
        assertEquals("secondo", data.get("expected").getAsString());
        assertEquals("Bass", data.get("observed").getAsString());
        assertEquals(64, data.get("scrollPosition").getAsInt());
        verify(selected, never()).set(anyBoolean());
        verify(mockStateCache, never()).getFilterItemBank(anyInt());
    }

    @Test
    void setFilterItemSelected_unobservedName_isAMismatchWithANullObserved() {
        SettableBooleanValue selected = stubTagItemAtSlotFive();
        when(mockStateCache.getFilterItemName(1, 5)).thenReturn(null);
        // Mockito answers 0 for an unstubbed Integer; the engine's unobserved value is null.
        when(mockStateCache.getFilterItemBankScrollPosition(1)).thenReturn(null);

        String response = dispatcher.handle(rpc("browser/setFilterItemSelected",
            "{\"column\":\"tag\",\"slot\":5,\"name\":\"secondo\"}"));

        assertContains(response, "-32001");
        assertContains(response, "\"observed\":null");
        assertContains(response, "\"scrollPosition\":null");
        verify(selected, never()).set(anyBoolean());
    }

    @Test
    void setFilterItemSelected_slotMinusOne_isRefusedBeforeAnyCall() {
        assertRefusedBeforeAnyCall("{\"column\":\"tag\",\"slot\":-1,\"name\":\"secondo\"}", "slot");
    }

    @Test
    void setFilterItemSelected_slotSixtyFour_isRefusedBeforeAnyCall() {
        assertRefusedBeforeAnyCall("{\"column\":\"tag\",\"slot\":64,\"name\":\"secondo\"}", "64");
    }

    @Test
    void setFilterItemSelected_nonIntegerSlot_isRefusedBeforeAnyCall() {
        assertRefusedBeforeAnyCall("{\"column\":\"tag\",\"slot\":1.5,\"name\":\"secondo\"}", "slot");
        assertRefusedBeforeAnyCall("{\"column\":\"tag\",\"slot\":\"5\",\"name\":\"secondo\"}", "slot");
    }

    @Test
    void setFilterItemSelected_missingSlot_isRefusedBeforeAnyCall() {
        assertRefusedBeforeAnyCall("{\"column\":\"tag\",\"name\":\"secondo\"}", "slot");
    }

    @Test
    void setFilterItemSelected_missingOrEmptyName_isRefusedBeforeAnyCall() {
        assertRefusedBeforeAnyCall("{\"column\":\"tag\",\"slot\":5}", "name");
        assertRefusedBeforeAnyCall("{\"column\":\"tag\",\"slot\":5,\"name\":\"\"}", "name");
    }

    @Test
    void setFilterItemSelected_nonBooleanSelected_isRefusedBeforeAnyCall() {
        assertRefusedBeforeAnyCall(
            "{\"column\":\"tag\",\"slot\":5,\"name\":\"secondo\",\"selected\":\"yes\"}", "selected");
    }

    @Test
    void setFilterItemSelected_unbankedColumn_isRefusedBeforeAnyCall() {
        assertRefusedBeforeAnyCall(
            "{\"column\":\"device\",\"slot\":5,\"name\":\"secondo\"}", "device");
    }

    /** A -32602 naming `mentions`, with neither the cache's name nor any bank item touched. */
    private void assertRefusedBeforeAnyCall(String params, String mentions) {
        SettableBooleanValue selected = stubTagItemAtSlotFive();
        String response = dispatcher.handle(rpc("browser/setFilterItemSelected", params));
        assertContains(response, "-32602");
        assertContains(response, mentions);
        verify(mockStateCache, never()).getFilterItemName(anyInt(), anyInt());
        verify(mockStateCache, never()).getFilterItemBank(anyInt());
        verify(selected, never()).set(anyBoolean());
    }

    // --- Helpers ---

    private String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}";
    }

    private void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected),
            "Expected '" + expected + "' in: " + actual);
    }
}
