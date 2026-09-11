package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Arranger;
import com.bitwig.extension.controller.api.CueMarker;
import com.bitwig.extension.controller.api.CueMarkerBank;
import com.bitwig.extension.controller.api.ScrollbarModel;
import com.bitwig.extension.controller.api.Transport;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.rpc.RpcException;

public class ArrangerHandler {

    private static final int CUE_MARKER_COUNT = 16;

    private final Arranger arranger;
    private final Transport transport;
    private final CueMarkerBank cueMarkerBank;
    private final ScrollbarModel scrollbar;
    private final StateCache stateCache;

    public ArrangerHandler(Arranger arranger, Transport transport, CueMarkerBank cueMarkerBank,
                           ScrollbarModel scrollbar, StateCache stateCache) {
        this.arranger = arranger;
        this.transport = transport;
        this.cueMarkerBank = cueMarkerBank;
        this.scrollbar = scrollbar;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        // --- Arranger visibility toggles ---

        dispatcher.register("arranger/setPlaybackFollow", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            arranger.isPlaybackFollowEnabled().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("arranger/setClipLauncherVisible", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            arranger.isClipLauncherVisible().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("arranger/setTimelineVisible", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            arranger.isTimelineVisible().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("arranger/setCueMarkersVisible", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            arranger.areCueMarkersVisible().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("arranger/setEffectTracksVisible", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            arranger.areEffectTracksVisible().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("arranger/setIoSectionVisible", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            arranger.isIoSectionVisible().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("arranger/setDoubleRowTrackHeight", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            arranger.hasDoubleRowTrackHeight().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        // --- Lane height zoom ---

        dispatcher.register("arranger/zoomInLanes", params -> {
            arranger.zoomInLaneHeightsAll();
            return ok();
        });

        dispatcher.register("arranger/zoomOutLanes", params -> {
            arranger.zoomOutLaneHeightsAll();
            return ok();
        });

        dispatcher.register("arranger/zoomInSelectedLanes", params -> {
            arranger.zoomInLaneHeightsSelected();
            return ok();
        });

        dispatcher.register("arranger/zoomOutSelectedLanes", params -> {
            arranger.zoomOutLaneHeightsSelected();
            return ok();
        });

        // --- Timeline navigation ---

        dispatcher.register("arranger/zoomToRegion", params -> {
            if (!params.has("from")) {
                throw new IllegalArgumentException("missing 'from' parameter");
            }
            if (!params.has("to")) {
                throw new IllegalArgumentException("missing 'to' parameter");
            }
            double from = params.get("from").getAsDouble();
            double to = params.get("to").getAsDouble();
            scrollbar.zoomToContentRegion(from, to);
            return ok();
        });

        dispatcher.register("arranger/zoomToFitSelectionOrAll", params -> {
            scrollbar.zoomToFitSelectionOrAll();
            return ok();
        });

        // --- Cue marker operations ---

        dispatcher.register("cueMarker/addAtPlayhead", params -> {
            transport.addCueMarkerAtPlaybackPosition();
            return ok();
        });

        dispatcher.register("cueMarker/list", params -> {
            JsonArray markers = new JsonArray();
            for (int i = 0; i < CUE_MARKER_COUNT; i++) {
                CueMarker marker = (CueMarker) cueMarkerBank.getItemAt(i);
                if (!marker.exists().get()) continue;
                JsonObject obj = new JsonObject();
                obj.addProperty("index", i);
                obj.addProperty("name", marker.name().get());
                obj.addProperty("position", marker.position().get());
                // Color as [r, g, b]
                JsonArray color = new JsonArray();
                color.add(marker.getColor().red());
                color.add(marker.getColor().green());
                color.add(marker.getColor().blue());
                obj.add("color", color);
                markers.add(obj);
            }
            return markers;
        });

        dispatcher.register("cueMarker/launch", params -> {
            if (!params.has("index")) {
                throw new IllegalArgumentException("missing 'index' parameter");
            }
            int index = params.get("index").getAsInt();
            if (index < 0 || index >= CUE_MARKER_COUNT) {
                throw new IllegalArgumentException("index must be 0-" + (CUE_MARKER_COUNT - 1));
            }
            boolean quantized = params.has("quantized") && params.get("quantized").getAsBoolean();
            CueMarker marker = (CueMarker) cueMarkerBank.getItemAt(index);
            marker.launch(quantized);
            return ok();
        });

        dispatcher.register("cueMarker/rename", params -> {
            if (!params.has("index")) {
                throw new IllegalArgumentException("missing 'index' parameter");
            }
            if (!params.has("name")) {
                throw new IllegalArgumentException("missing 'name' parameter");
            }
            int index = params.get("index").getAsInt();
            if (index < 0 || index >= CUE_MARKER_COUNT) {
                throw new IllegalArgumentException("index must be 0-" + (CUE_MARKER_COUNT - 1));
            }
            CueMarker marker = (CueMarker) cueMarkerBank.getItemAt(index);
            marker.name().set(params.get("name").getAsString());
            return ok();
        });

        dispatcher.register("cueMarker/setPosition", params -> {
            if (!params.has("index")) {
                throw new IllegalArgumentException("missing 'index' parameter");
            }
            if (!params.has("beats")) {
                throw new IllegalArgumentException("missing 'beats' parameter");
            }
            int index = params.get("index").getAsInt();
            if (index < 0 || index >= CUE_MARKER_COUNT) {
                throw new IllegalArgumentException("index must be 0-" + (CUE_MARKER_COUNT - 1));
            }
            double beats = params.get("beats").getAsDouble();
            CueMarker marker = (CueMarker) cueMarkerBank.getItemAt(index);
            marker.position().set(beats);
            return ok();
        });

        dispatcher.register("cueMarker/duplicate", params -> {
            if (!params.has("index")) {
                throw new IllegalArgumentException("missing 'index' parameter");
            }
            int index = params.get("index").getAsInt();
            if (index < 0 || index >= CUE_MARKER_COUNT) {
                throw new IllegalArgumentException("index must be 0-" + (CUE_MARKER_COUNT - 1));
            }
            CueMarker marker = (CueMarker) cueMarkerBank.getItemAt(index);
            marker.duplicateObject();
            return ok();
        });

        dispatcher.register("cueMarker/delete", params -> {
            if (!params.has("index")) {
                throw new IllegalArgumentException("missing 'index' parameter");
            }
            int index = params.get("index").getAsInt();
            if (index < 0 || index >= CUE_MARKER_COUNT) {
                throw new IllegalArgumentException("index must be 0-" + (CUE_MARKER_COUNT - 1));
            }
            CueMarker marker = (CueMarker) cueMarkerBank.getItemAt(index);
            marker.deleteObject();
            return ok();
        });

        // --- Cue marker bank scroll methods ---

        dispatcher.register("cueMarkerBank/scrollTo", params -> {
            if (!params.has("position")) {
                throw new IllegalArgumentException("missing 'position' parameter");
            }
            int position = params.get("position").getAsInt();
            int itemCount = stateCache.getCueMarkerItemCount();
            if (position < 0 || position >= itemCount) {
                JsonObject error = new JsonObject();
                error.addProperty("itemCount", itemCount);
                error.addProperty("requestedPosition", position);
                throw new RpcException(-32001, "POSITION_OUT_OF_RANGE", error);
            }
            cueMarkerBank.scrollPosition().set(position);
            return ok();
        });

        dispatcher.register("cueMarkerBank/scrollBy", params -> {
            if (!params.has("amount")) {
                throw new IllegalArgumentException("missing 'amount' parameter");
            }
            int amount = params.get("amount").getAsInt();
            cueMarkerBank.scrollBy(amount);
            return ok();
        });

        dispatcher.register("cueMarkerBank/getScrollInfo", params -> {
            return stateCache.getCueMarkerBankScrollInfo();
        });
    }

    private JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }
}
