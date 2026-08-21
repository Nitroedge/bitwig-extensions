package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Action;
import com.bitwig.extension.controller.api.ActionCategory;
import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.TrackBank;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
import dev.bcrick.bitwigpal.rpc.RpcException;

import java.util.Set;

public class ApplicationHandler {

    private final Application application;
    private final ControllerHost host;
    private final TrackBank trackBank;

    public ApplicationHandler(Application application, ControllerHost host, TrackBank trackBank) {
        this.application = application;
        this.host = host;
        this.trackBank = trackBank;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("app/undo", params -> {
            application.undo();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/redo", params -> {
            application.redo();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/getState", params -> {
            JsonObject state = new JsonObject();
            state.addProperty("projectName", application.projectName().get());
            state.addProperty("canUndo", application.canUndo().get());
            state.addProperty("canRedo", application.canRedo().get());
            state.addProperty("hasActiveEngine", application.hasActiveEngine().get());
            state.addProperty("panelLayout", application.panelLayout().get());
            return state;
        });

        dispatcher.register("app/activateEngine", params -> {
            application.activateEngine();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/deactivateEngine", params -> {
            application.deactivateEngine();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/showNotification", params -> {
            if (!params.has("text")) {
                throw new IllegalArgumentException("missing 'text' parameter");
            }
            host.showPopupNotification(params.get("text").getAsString());
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/setPanelLayout", params -> {
            if (!params.has("layout")) {
                throw new IllegalArgumentException("missing 'layout' parameter");
            }
            String layout = params.get("layout").getAsString();
            if (!PANEL_LAYOUTS.contains(layout)) {
                throw new IllegalArgumentException("invalid layout '" + layout + "', expected: ARRANGE, MIX, EDIT");
            }
            application.setPanelLayout(layout);
            return new JsonPrimitive("ok");
        });

        // Panel visibility toggles
        dispatcher.register("app/toggleInspector", params -> {
            application.toggleInspector();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/toggleDevices", params -> {
            application.toggleDevices();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/toggleMixer", params -> {
            application.toggleMixer();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/toggleNoteEditor", params -> {
            application.toggleNoteEditor();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/toggleAutomationEditor", params -> {
            application.toggleAutomationEditor();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/toggleBrowser", params -> {
            application.toggleBrowserVisibility();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/toggleFullScreen", params -> {
            application.toggleFullScreen();
            return new JsonPrimitive("ok");
        });

        // Sub-panel navigation
        dispatcher.register("app/previousSubPanel", params -> {
            application.previousSubPanel();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/nextSubPanel", params -> {
            application.nextSubPanel();
            return new JsonPrimitive("ok");
        });

        // Zoom controls
        dispatcher.register("app/zoomIn", params -> {
            application.zoomIn();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/zoomOut", params -> {
            application.zoomOut();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/zoomToFit", params -> {
            application.zoomToFit();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/zoomToSelection", params -> {
            application.zoomToSelection();
            return new JsonPrimitive("ok");
        });

        // Track group navigation
        dispatcher.register("app/navigateIntoTrackGroup", params -> {
            if (!params.has("trackIndex")) {
                throw new IllegalArgumentException("missing 'trackIndex' parameter");
            }
            int trackIndex = params.get("trackIndex").getAsInt();
            application.navigateIntoTrackGroup(trackBank.getItemAt(trackIndex));
            return new JsonPrimitive("ok");
        });

        dispatcher.register("app/navigateToParentTrackGroup", params -> {
            application.navigateToParentTrackGroup();
            return new JsonPrimitive("ok");
        });

        // Action discovery & invoke
        dispatcher.register("action/list", params -> {
            Action[] actions = application.getActions();
            String categoryFilter = params.has("category") ? params.get("category").getAsString() : null;
            JsonArray result = new JsonArray();
            for (Action action : actions) {
                if (categoryFilter != null) {
                    ActionCategory cat = action.getCategory();
                    if (cat == null || !categoryFilter.equals(cat.getName())) {
                        continue;
                    }
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("id", action.getId());
                obj.addProperty("name", action.getName());
                ActionCategory cat = action.getCategory();
                obj.addProperty("category", cat != null ? cat.getName() : null);
                obj.addProperty("menuItemText", action.getMenuItemText());
                result.add(obj);
            }
            return result;
        });

        dispatcher.register("action/listCategories", params -> {
            ActionCategory[] categories = application.getActionCategories();
            JsonArray result = new JsonArray();
            for (ActionCategory cat : categories) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", cat.getId());
                obj.addProperty("name", cat.getName());
                result.add(obj);
            }
            return result;
        });

        dispatcher.register("action/invoke", params -> {
            if (!params.has("id")) {
                throw new IllegalArgumentException("missing 'id' parameter");
            }
            String id = params.get("id").getAsString();
            Action action = application.getAction(id);
            if (action == null) {
                JsonObject data = new JsonObject();
                data.addProperty("actionId", id);
                throw new RpcException(-32001, "ACTION_NOT_FOUND", data);
            }
            action.invoke();
            return new JsonPrimitive("ok");
        });
    }

    private static final Set<String> PANEL_LAYOUTS = Set.of("ARRANGE", "MIX", "EDIT");
}
