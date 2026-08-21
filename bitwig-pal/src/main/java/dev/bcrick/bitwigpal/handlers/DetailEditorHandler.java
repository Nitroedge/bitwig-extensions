package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.DetailEditor;
import com.bitwig.extension.controller.api.ScrollbarModel;
import com.google.gson.JsonObject;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;

public class DetailEditorHandler {

    private final DetailEditor detailEditor;
    private final ScrollbarModel scrollbar;

    public DetailEditorHandler(DetailEditor detailEditor) {
        this.detailEditor = detailEditor;
        this.scrollbar = detailEditor.getHorizontalScrollbarModel();
    }

    public void register(JsonRpcDispatcher dispatcher) {
        // --- Timeline zoom (horizontal) ---

        dispatcher.register("detailEditor/zoomIn", params -> {
            detailEditor.zoomIn();
            return ok();
        });

        dispatcher.register("detailEditor/zoomOut", params -> {
            detailEditor.zoomOut();
            return ok();
        });

        dispatcher.register("detailEditor/zoomToFit", params -> {
            detailEditor.zoomToFit();
            return ok();
        });

        dispatcher.register("detailEditor/zoomToSelection", params -> {
            detailEditor.zoomToSelection();
            return ok();
        });

        dispatcher.register("detailEditor/zoomToFitSelectionOrAll", params -> {
            detailEditor.zoomToFitSelectionOrAll();
            return ok();
        });

        // --- Lane height zoom (vertical) ---

        dispatcher.register("detailEditor/zoomInLanes", params -> {
            detailEditor.zoomInLaneHeights();
            return ok();
        });

        dispatcher.register("detailEditor/zoomOutLanes", params -> {
            detailEditor.zoomOutLaneHeights();
            return ok();
        });

        // --- Region zoom via ScrollbarModel ---

        dispatcher.register("detailEditor/zoomToRegion", params -> {
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
    }

    private JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }
}
