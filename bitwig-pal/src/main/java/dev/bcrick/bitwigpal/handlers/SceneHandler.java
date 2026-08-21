package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Project;
import com.bitwig.extension.controller.api.Scene;
import com.bitwig.extension.controller.api.SceneBank;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.bitwigpal.extension.StateCache;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;

import static dev.bcrick.bitwigpal.rpc.JsonParamValidator.*;
import dev.bcrick.bitwigpal.rpc.RpcException;

public class SceneHandler {

    private static final int SCENE_COUNT = 16;

    private final SceneBank sceneBank;
    private final Project project;
    private final StateCache stateCache;

    public SceneHandler(SceneBank sceneBank, Project project, StateCache stateCache) {
        this.sceneBank = sceneBank;
        this.project = project;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("scene/create", params -> {
            project.createScene();
            return ok();
        });

        dispatcher.register("scene/createFromPlaying", params -> {
            project.createSceneFromPlayingLauncherClips();
            return ok();
        });

        dispatcher.register("scene/duplicate", params -> {
            int index = requireInt(params, "index");
            validateIndex(index);
            Scene scene = sceneBank.getScene(index);
            scene.duplicateObject();
            return ok();
        });

        dispatcher.register("scene/rename", params -> {
            int index = requireInt(params, "index");
            validateIndex(index);
            String name = requireString(params, "name");
            Scene scene = sceneBank.getScene(index);
            scene.name().set(name);
            return ok();
        });

        dispatcher.register("scene/delete", params -> {
            int index = requireInt(params, "index");
            validateIndex(index);
            Scene scene = sceneBank.getScene(index);
            scene.deleteObject();
            return ok();
        });

        // --- Scene bank scroll methods ---

        dispatcher.register("sceneBank/scrollTo", params -> {
            int position = requireInt(params, "position");
            int itemCount = stateCache.getSceneItemCount();
            if (position < 0 || position >= itemCount) {
                JsonObject error = new JsonObject();
                error.addProperty("itemCount", itemCount);
                error.addProperty("requestedPosition", position);
                throw new RpcException(-32001, "POSITION_OUT_OF_RANGE", error);
            }
            sceneBank.scrollPosition().set(position);
            return ok();
        });

        dispatcher.register("sceneBank/scrollBy", params -> {
            int amount = requireInt(params, "amount");
            sceneBank.scrollBy(amount);
            return ok();
        });

        dispatcher.register("sceneBank/getScrollInfo", params -> {
            return stateCache.getSceneBankScrollInfo();
        });

        // Color
        dispatcher.register("scene/setColor", params -> {
            int index = requireInt(params, "index");
            validateIndex(index);
            float r = (float) requireDouble(params, "r");
            float g = (float) requireDouble(params, "g");
            float b = (float) requireDouble(params, "b");
            if (r < 0.0f || r > 1.0f) throw new IllegalArgumentException("r must be between 0.0 and 1.0");
            if (g < 0.0f || g > 1.0f) throw new IllegalArgumentException("g must be between 0.0 and 1.0");
            if (b < 0.0f || b > 1.0f) throw new IllegalArgumentException("b must be between 0.0 and 1.0");
            sceneBank.getScene(index).color().set(r, g, b);
            return ok();
        });

        // Alternative launch methods
        dispatcher.register("scene/launchAlt", params -> {
            int index = requireInt(params, "index");
            validateIndex(index);
            sceneBank.getScene(index).launchAlt();
            return ok();
        });

        dispatcher.register("scene/launchRelease", params -> {
            int index = requireInt(params, "index");
            validateIndex(index);
            sceneBank.getScene(index).launchRelease();
            return ok();
        });

        dispatcher.register("scene/launchReleaseAlt", params -> {
            int index = requireInt(params, "index");
            validateIndex(index);
            sceneBank.getScene(index).launchReleaseAlt();
            return ok();
        });
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= SCENE_COUNT) {
            throw new IllegalArgumentException("scene index out of range: " + index);
        }
    }

    private JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

}
