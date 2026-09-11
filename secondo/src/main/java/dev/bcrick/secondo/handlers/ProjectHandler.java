package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Project;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

public class ProjectHandler {

    private final Project project;
    private final StateCache stateCache;

    public ProjectHandler(Project project, StateCache stateCache) {
        this.project = project;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("project/unsoloAll", params -> {
            project.unsoloAll();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("project/unmuteAll", params -> {
            project.unmuteAll();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("project/unarmAll", params -> {
            project.unarmAll();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("project/setCueVolume", params -> {
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            project.cueVolume().value().set(params.get("value").getAsDouble());
            return new JsonPrimitive("ok");
        });

        dispatcher.register("project/setCueMix", params -> {
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            project.cueMix().value().set(params.get("value").getAsDouble());
            return new JsonPrimitive("ok");
        });

        dispatcher.register("project/getState", params -> {
            JsonObject state = new JsonObject();
            state.addProperty("hasSoloedTracks", stateCache.hasSoloedTracks());
            state.addProperty("hasMutedTracks", stateCache.hasMutedTracks());
            state.addProperty("hasArmedTracks", stateCache.hasArmedTracks());
            state.addProperty("isModified", stateCache.isModified());
            state.addProperty("cueVolume", stateCache.getCueVolume());
            state.addProperty("cueMix", stateCache.getCueMix());
            return state;
        });
    }
}
