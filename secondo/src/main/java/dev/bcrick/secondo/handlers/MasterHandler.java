package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.MasterTrack;
import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

public class MasterHandler {

    private final MasterTrack masterTrack;

    public MasterHandler(MasterTrack masterTrack) {
        this.masterTrack = masterTrack;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("master/setVolume", params -> {
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            double value = params.get("value").getAsDouble();
            masterTrack.volume().value().setImmediately(value);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("master/setPan", params -> {
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            double value = params.get("value").getAsDouble();
            masterTrack.pan().value().setImmediately(value);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("master/setMute", params -> {
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            boolean value = params.get("value").getAsBoolean();
            masterTrack.mute().set(value);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("master/setSolo", params -> {
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            boolean value = params.get("value").getAsBoolean();
            masterTrack.solo().set(value);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("master/setColor", params -> {
            float r = params.get("r").getAsFloat();
            float g = params.get("g").getAsFloat();
            float b = params.get("b").getAsFloat();
            masterTrack.color().set(r, g, b);
            return new JsonPrimitive("ok");
        });
    }
}
