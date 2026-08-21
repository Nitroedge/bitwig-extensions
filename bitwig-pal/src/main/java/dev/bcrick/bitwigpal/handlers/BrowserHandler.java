package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.BrowserFilterColumn;
import com.bitwig.extension.controller.api.BrowserResultsItemBank;
import com.bitwig.extension.controller.api.CursorBrowserFilterItem;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.PopupBrowser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.bitwigpal.extension.StateCache;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;

import static dev.bcrick.bitwigpal.rpc.JsonParamValidator.*;

import java.util.Map;
import java.util.Set;

public class BrowserHandler {

    private static final Set<String> SCROLL_DIRECTIONS = Set.of(
        "forward", "backward", "pageForward", "pageBackward"
    );

    private static final Map<String, Integer> COLUMN_INDEX_MAP = Map.of(
        "category", 0, "tag", 1, "creator", 2, "device", 3,
        "deviceType", 4, "fileType", 5, "location", 6, "smartCollection", 7
    );

    private final PopupBrowser popupBrowser;
    private final CursorDevice cursorDevice;
    private final StateCache stateCache;

    public BrowserHandler(PopupBrowser popupBrowser, CursorDevice cursorDevice,
                           StateCache stateCache) {
        this.popupBrowser = popupBrowser;
        this.cursorDevice = cursorDevice;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        // Browser opening — must use InsertionPoint.browse() to open
        dispatcher.register("browser/browsePresets", params -> {
            cursorDevice.replaceDeviceInsertionPoint().browse();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/browseInsertDevice", params -> {
            cursorDevice.afterDeviceInsertionPoint().browse();
            return new JsonPrimitive("ok");
        });

        // Result navigation
        dispatcher.register("browser/selectNextFile", params -> {
            popupBrowser.selectNextFile();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/selectPreviousFile", params -> {
            popupBrowser.selectPreviousFile();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/selectFirstFile", params -> {
            popupBrowser.selectFirstFile();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/selectLastFile", params -> {
            popupBrowser.selectLastFile();
            return new JsonPrimitive("ok");
        });

        // Commit / cancel
        dispatcher.register("browser/commit", params -> {
            popupBrowser.commit();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/cancel", params -> {
            popupBrowser.cancel();
            return new JsonPrimitive("ok");
        });

        // Content type switching
        dispatcher.register("browser/setContentType", params -> {
            int index = requireInt(params, "index");
            popupBrowser.selectedContentTypeIndex().set(index);
            return new JsonPrimitive("ok");
        });

        // Audition toggle
        dispatcher.register("browser/setShouldAudition", params -> {
            boolean enabled = requireBoolean(params, "enabled");
            popupBrowser.shouldAudition().set(enabled);
            return new JsonPrimitive("ok");
        });

        // State query
        dispatcher.register("browser/getState", params -> stateCache.getBrowserState());

        // --- Filter navigation (Phase 18) ---

        dispatcher.register("browser/filterSelectNext", params -> {
            resolveFilterCursor(params).selectNext();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/filterSelectPrevious", params -> {
            resolveFilterCursor(params).selectPrevious();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/filterSelectFirst", params -> {
            resolveFilterCursor(params).selectFirst();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/filterSelectLast", params -> {
            resolveFilterCursor(params).selectLast();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/filterSelectParent", params -> {
            resolveFilterCursor(params).selectParent();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/filterSelectFirstChild", params -> {
            resolveFilterCursor(params).selectFirstChild();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/filterReset", params -> {
            int idx = resolveColumnIndex(params);
            BrowserFilterColumn[] columns = stateCache.getFilterColumns();
            columns[idx].getWildcardItem().isSelected().set(true);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("browser/getFilters", params -> stateCache.getBrowserState().getAsJsonObject("filters"));

        // --- Result bank (Phase 18) ---

        dispatcher.register("browser/getResults", params -> stateCache.getResultBankState());

        dispatcher.register("browser/scrollResults", params -> {
            String direction = requireString(params, "direction");
            if (!SCROLL_DIRECTIONS.contains(direction)) {
                throw new IllegalArgumentException(
                    "Invalid direction: " + direction + ". Must be one of: " + SCROLL_DIRECTIONS);
            }
            BrowserResultsItemBank bank = stateCache.getResultBank();
            switch (direction) {
                case "forward": bank.scrollForwards(); break;
                case "backward": bank.scrollBackwards(); break;
                case "pageForward": bank.scrollPageForwards(); break;
                case "pageBackward": bank.scrollPageBackwards(); break;
            }
            return new JsonPrimitive("ok");
        });
    }

    private CursorBrowserFilterItem resolveFilterCursor(JsonObject params) {
        int idx = resolveColumnIndex(params);
        CursorBrowserFilterItem[] cursors = stateCache.getFilterCursors();
        return cursors[idx];
    }

    private int resolveColumnIndex(JsonObject params) {
        String column = requireString(params, "column");
        Integer idx = COLUMN_INDEX_MAP.get(column);
        if (idx == null) {
            throw new IllegalArgumentException(
                "Invalid column: " + column + ". Must be one of: " + COLUMN_INDEX_MAP.keySet());
        }
        return idx;
    }

}
