package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Groove;
import com.bitwig.extension.controller.api.Parameter;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

import java.util.Map;
import java.util.Set;

public class GrooveHandler {

    private final Groove groove;

    private static final Set<String> PARAM_NAMES = Set.of(
        "shuffleAmount", "shuffleRate", "accentAmount", "accentRate", "accentPhase"
    );

    public GrooveHandler(Groove groove) {
        this.groove = groove;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("groove/getState", params -> {
            JsonObject state = new JsonObject();
            state.addProperty("enabled", groove.getEnabled().get() > 0.5);
            state.addProperty("shuffleAmount", groove.getShuffleAmount().get());
            state.addProperty("shuffleRate", groove.getShuffleRate().get());
            state.addProperty("accentAmount", groove.getAccentAmount().get());
            state.addProperty("accentRate", groove.getAccentRate().get());
            state.addProperty("accentPhase", groove.getAccentPhase().get());
            return state;
        });

        dispatcher.register("groove/setEnabled", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            boolean enabled = params.get("enabled").getAsBoolean();
            groove.getEnabled().set(enabled ? 1.0 : 0.0);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("groove/setParameter", params -> {
            if (!params.has("name")) {
                throw new IllegalArgumentException("missing 'name' parameter");
            }
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            String name = params.get("name").getAsString();
            double value = params.get("value").getAsDouble();

            if (!PARAM_NAMES.contains(name)) {
                throw new IllegalArgumentException("invalid parameter name '" + name
                    + "', expected one of: shuffleAmount, shuffleRate, accentAmount, accentRate, accentPhase");
            }

            getParameter(name).set(value);
            return new JsonPrimitive("ok");
        });
    }

    private Parameter getParameter(String name) {
        return switch (name) {
            case "shuffleAmount" -> groove.getShuffleAmount();
            case "shuffleRate" -> groove.getShuffleRate();
            case "accentAmount" -> groove.getAccentAmount();
            case "accentRate" -> groove.getAccentRate();
            case "accentPhase" -> groove.getAccentPhase();
            default -> throw new IllegalArgumentException("unknown parameter: " + name);
        };
    }
}
