package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.BrowserFilterColumn;
import com.bitwig.extension.controller.api.BrowserResultsItemBank;
import com.bitwig.extension.controller.api.CursorBrowserFilterItem;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.MasterTrack;
import com.bitwig.extension.controller.api.PopupBrowser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

import static dev.bcrick.secondo.rpc.JsonParamValidator.*;

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
    private final MasterTrack masterTrack;
    private final CursorDevice masterCursorDevice;
    private final StateCache stateCache;

    public BrowserHandler(PopupBrowser popupBrowser, CursorDevice cursorDevice,
                           MasterTrack masterTrack, CursorDevice masterCursorDevice,
                           StateCache stateCache) {
        this.popupBrowser = popupBrowser;
        this.cursorDevice = cursorDevice;
        this.masterTrack = masterTrack;
        this.masterCursorDevice = masterCursorDevice;
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

        // Master chain openers (Phase 26, D-26-07 / D-26-08). Both open the ONE shared popup
        // browser the StateCache observers already watch, so browser/getState sees them.
        // MasterTrack extends Track (bitwig-api-reference.txt :16274), so the master track has the
        // DeviceChain insertion points. The deprecated track device-browser factory is deliberately
        // NOT used: it returns the deprecated Browser type, it is an init-only proxy factory that
        // cannot be called from a request, and it would be a second browser the popup observers
        // cannot see. Insertion points are resolved at request time; they are not factories.

        // bitwig-api-reference.txt :16172 DeviceChain#endOfDeviceChainInsertionPoint -- the END of
        // the master chain, independent of any cursor (as masterDevice/insert "end" uses it).
        dispatcher.register("browser/browseMasterInsertDevice", params -> {
            masterTrack.endOfDeviceChainInsertionPoint().browse();
            return new JsonPrimitive("ok");
        });

        // bitwig-api-reference.txt :11746 Device#replaceDeviceInsertionPoint -- a commit
        // REPLACES the master cursor device. Never the track cursor device.
        dispatcher.register("browser/browseMasterPresets", params -> {
            masterCursorDevice.replaceDeviceInsertionPoint().browse();
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

        // --- Filter item bank (Phase 28, D-28-32) ---
        //
        // APPENDED after browser/scrollResults so every existing BrowserHandler.java line
        // citation still points where it pointed; fully qualified names keep the import block
        // untouched for the same reason. The banks exist on category, tag and creator only
        // (StateCache.registerFilterItemBanks, created at init). The recall reads a banked column
        // whole at its wildcard, before any selection (D-28-33).

        dispatcher.register("browser/getFilterItems", params ->
            stateCache.getFilterItemBankState(resolveBankedColumnIndex(params)));

        // Moves the column's bank WINDOW, never its cursor. "ok" is never proof the window moved;
        // the next read's scrollPosition is.
        dispatcher.register("browser/scrollFilterItems", params -> {
            int idx = resolveBankedColumnIndex(params);
            String direction = requireString(params, "direction");
            if (!SCROLL_DIRECTIONS.contains(direction)) {
                throw new IllegalArgumentException(
                    "Invalid direction: " + direction + ". Must be one of: " + SCROLL_DIRECTIONS);
            }
            com.bitwig.extension.controller.api.BrowserFilterItemBank bank =
                stateCache.getFilterItemBank(idx);
            switch (direction) {
                case "forward": bank.scrollForwards(); break;
                case "backward": bank.scrollBackwards(); break;
                case "pageForward": bank.scrollPageForwards(); break;
                case "pageBackward": bank.scrollPageBackwards(); break;
            }
            return new JsonPrimitive("ok");
        });

        // A COMPARE-AND-SET. The name cached at the window slot must be the caller's, or -32001
        // FILTER_ITEM_NAME_MISMATCH is thrown and the item is never touched, so a list that moved
        // between the caller's read and this select can never select the wrong entry. Every
        // parameter is validated before any API call. No sleep, no scheduling, no deferred
        // response (D-28-32).
        dispatcher.register("browser/setFilterItemSelected", params -> {
            int idx = resolveBankedColumnIndex(params);
            int slot = requireFilterItemSlot(params);
            String name = requireFilterItemName(params);
            boolean selected = true;
            if (params.has("selected")) {
                com.google.gson.JsonElement raw = params.get("selected");
                if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("'selected' must be a boolean, got " + raw);
                }
                selected = requireBoolean(params, "selected");
            }
            String observed = stateCache.getFilterItemName(idx, slot);
            if (!name.equals(observed)) {
                JsonObject data = new JsonObject();
                data.addProperty("column", requireString(params, "column"));
                data.addProperty("slot", slot);
                data.addProperty("expected", name);
                data.addProperty("observed", observed);
                data.addProperty("scrollPosition", stateCache.getFilterItemBankScrollPosition(idx));
                throw new RpcException(-32001, "FILTER_ITEM_NAME_MISMATCH", data);
            }
            com.bitwig.extension.controller.api.BrowserFilterItem item =
                (com.bitwig.extension.controller.api.BrowserFilterItem)
                    stateCache.getFilterItemBank(idx).getItemAt(slot);
            item.isSelected().set(selected);
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

    /**
     * Phase 28 (D-28-32): the column index of one of the three BANKED columns. Any other value,
     * including the five un-banked column names, is an IllegalArgumentException (so -32602), in
     * the harness's words.
     */
    private int resolveBankedColumnIndex(JsonObject params) {
        String column = requireString(params, "column");
        switch (column) {
            case "category": return 0;
            case "tag": return 1;
            case "creator": return 2;
            default:
                throw new IllegalArgumentException(
                    "Invalid filter item bank column: " + column
                        + ". Must be one of: [category, tag, creator]");
        }
    }

    /** A window slot: a JSON integer in 0..FILTER_ITEM_BANK_SIZE-1, or -32602. */
    private static int requireFilterItemSlot(JsonObject params) {
        int size = StateCache.FILTER_ITEM_BANK_SIZE;
        com.google.gson.JsonElement raw = params.get("slot");
        if (raw == null) {
            throw new IllegalArgumentException(missingMessage("slot"));
        }
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                "filter item slot out of range: 0-" + (size - 1) + ", got " + raw);
        }
        double value = raw.getAsDouble();
        if (value != Math.rint(value) || value < 0 || value >= size) {
            throw new IllegalArgumentException(
                "filter item slot out of range: 0-" + (size - 1) + ", got " + raw);
        }
        return (int) value;
    }

    /** The entry name a select compares against: a non-empty JSON string, or -32602. */
    private static String requireFilterItemName(JsonObject params) {
        com.google.gson.JsonElement raw = params.get("name");
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()
                || raw.getAsString().isEmpty()) {
            throw new IllegalArgumentException(missingMessage("name"));
        }
        return raw.getAsString();
    }

    /**
     * Phase 28 (D-28-32): the rpc package's RpcException under its simple name. It is declared
     * here rather than imported because an import line would move every line below it, and the
     * filter item bank's Java is appended precisely so no existing `BrowserHandler.java:<line>`
     * citation moves. The dispatcher catches it as the RpcException it extends, so the wire
     * shape is identical, and `new RpcException(-32001, ...)` stays readable to the conformance
     * scan of custom codes.
     */
    private static final class RpcException extends dev.bcrick.secondo.rpc.RpcException {
        RpcException(int code, String message, JsonObject data) {
            super(code, message, data);
        }
    }

}
