package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Transport;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.bitwigpal.extension.StateCache;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;

import java.util.Set;

public class TransportHandler {

    private final Transport transport;
    private final StateCache stateCache;

    public TransportHandler(Transport transport, StateCache stateCache) {
        this.transport = transport;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("transport/play", params -> {
            transport.play();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/stop", params -> {
            transport.stop();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/record", params -> {
            transport.record();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/togglePlay", params -> {
            transport.togglePlay();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/rewind", params -> {
            transport.rewind();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/fastForward", params -> {
            transport.fastForward();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/tapTempo", params -> {
            transport.tapTempo();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/setTempo", params -> {
            if (!params.has("tempo")) {
                throw new IllegalArgumentException("missing 'tempo' parameter");
            }
            double tempo = params.get("tempo").getAsDouble();
            transport.tempo().value().setRaw(tempo);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/setPosition", params -> {
            if (!params.has("beats")) {
                throw new IllegalArgumentException("missing 'beats' parameter");
            }
            double beats = params.get("beats").getAsDouble();
            transport.getPosition().set(beats);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/setLoop", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            boolean enabled = params.get("enabled").getAsBoolean();
            transport.isArrangerLoopEnabled().set(enabled);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("transport/setMetronome", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            boolean enabled = params.get("enabled").getAsBoolean();
            transport.isMetronomeEnabled().set(enabled);
            return new JsonPrimitive("ok");
        });

        // --- Loop range ---

        dispatcher.register("transport/setLoopRange", params -> {
            if (!params.has("start")) {
                throw new IllegalArgumentException("missing 'start' parameter");
            }
            if (!params.has("duration")) {
                throw new IllegalArgumentException("missing 'duration' parameter");
            }
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            transport.arrangerLoopStart().set(params.get("start").getAsDouble());
            transport.arrangerLoopDuration().set(params.get("duration").getAsDouble());
            transport.isArrangerLoopEnabled().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("transport/getLoopRange", params -> {
            JsonObject result = new JsonObject();
            result.addProperty("loopStart", transport.arrangerLoopStart().get());
            result.addProperty("loopDuration", transport.arrangerLoopDuration().get());
            result.addProperty("loopEnabled", transport.isArrangerLoopEnabled().get());
            result.addProperty("punchInPosition", transport.getInPosition().get());
            result.addProperty("punchInEnabled", transport.isPunchInEnabled().get());
            result.addProperty("punchOutPosition", transport.getOutPosition().get());
            result.addProperty("punchOutEnabled", transport.isPunchOutEnabled().get());
            return result;
        });

        // --- Punch in/out ---

        dispatcher.register("transport/setPunchIn", params -> {
            if (!params.has("position")) {
                throw new IllegalArgumentException("missing 'position' parameter");
            }
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            transport.getInPosition().set(params.get("position").getAsDouble());
            transport.isPunchInEnabled().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("transport/setPunchOut", params -> {
            if (!params.has("position")) {
                throw new IllegalArgumentException("missing 'position' parameter");
            }
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            transport.getOutPosition().set(params.get("position").getAsDouble());
            transport.isPunchOutEnabled().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        // --- Automation ---

        dispatcher.register("transport/setAutomationWriteMode", params -> {
            if (!params.has("mode")) {
                throw new IllegalArgumentException("missing 'mode' parameter");
            }
            String mode = params.get("mode").getAsString();
            if (!AUTOMATION_MODES.contains(mode)) {
                throw new IllegalArgumentException("invalid mode '" + mode + "', expected: latch, touch, write");
            }
            transport.automationWriteMode().set(mode);
            return ok();
        });

        dispatcher.register("transport/setArrangerAutomationWrite", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            transport.isArrangerAutomationWriteEnabled().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("transport/setClipLauncherAutomationWrite", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            transport.isClipLauncherAutomationWriteEnabled().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("transport/resetAutomationOverrides", params -> {
            transport.resetAutomationOverrides();
            return ok();
        });

        // --- Navigation ---

        dispatcher.register("transport/continuePlayback", params -> {
            transport.continuePlayback();
            return ok();
        });

        dispatcher.register("transport/restart", params -> {
            transport.restart();
            return ok();
        });

        dispatcher.register("transport/returnToArrangement", params -> {
            transport.returnToArrangement();
            return ok();
        });

        dispatcher.register("transport/jumpToPreviousCueMarker", params -> {
            transport.jumpToPreviousCueMarker();
            return ok();
        });

        dispatcher.register("transport/jumpToNextCueMarker", params -> {
            transport.jumpToNextCueMarker();
            return ok();
        });

        // --- Metronome & Pre-roll ---

        dispatcher.register("transport/setPreRoll", params -> {
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            String value = params.get("value").getAsString();
            if (!PRE_ROLL_VALUES.contains(value)) {
                throw new IllegalArgumentException("invalid value '" + value + "', expected: none, one_bar, two_bars, four_bars");
            }
            transport.preRoll().set(value);
            return ok();
        });

        dispatcher.register("transport/setMetronomeVolume", params -> {
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            double value = params.get("value").getAsDouble();
            transport.metronomeVolume().setImmediately(value);
            return ok();
        });

        // --- Clip Launcher Settings ---

        dispatcher.register("transport/setDefaultLaunchQuantization", params -> {
            if (!params.has("quantization")) {
                throw new IllegalArgumentException("missing 'quantization' parameter");
            }
            String quantization = params.get("quantization").getAsString();
            if (!DEFAULT_LAUNCH_QUANTIZATIONS.contains(quantization)) {
                throw new IllegalArgumentException("invalid quantization '" + quantization
                    + "' — valid values: " + DEFAULT_LAUNCH_QUANTIZATIONS);
            }
            transport.defaultLaunchQuantization().set(quantization);
            return ok();
        });

        dispatcher.register("transport/setPostRecordingAction", params -> {
            if (!params.has("action")) {
                throw new IllegalArgumentException("missing 'action' parameter");
            }
            String action = params.get("action").getAsString();
            if (!POST_RECORDING_ACTIONS.contains(action)) {
                throw new IllegalArgumentException("invalid action '" + action
                    + "' — valid values: " + POST_RECORDING_ACTIONS);
            }
            transport.clipLauncherPostRecordingAction().set(action);
            return ok();
        });

        dispatcher.register("transport/setPostRecordingTimeOffset", params -> {
            if (!params.has("beats")) {
                throw new IllegalArgumentException("missing 'beats' parameter");
            }
            double beats = params.get("beats").getAsDouble();
            transport.getClipLauncherPostRecordingTimeOffset().set(beats);
            return ok();
        });

        dispatcher.register("transport/setClipLauncherOverdub", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            transport.isClipLauncherOverdubEnabled().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("transport/setFillMode", params -> {
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            transport.isFillModeActive().set(params.get("enabled").getAsBoolean());
            return ok();
        });

        dispatcher.register("transport/getClipLauncherSettings", params -> stateCache.getClipLauncherSettings());
    }

    private static final Set<String> AUTOMATION_MODES = Set.of("latch", "touch", "write");
    private static final Set<String> PRE_ROLL_VALUES = Set.of("none", "one_bar", "two_bars", "four_bars");
    private static final Set<String> DEFAULT_LAUNCH_QUANTIZATIONS = Set.of(
        "none", "8", "4", "2", "1", "1/2", "1/4", "1/8", "1/16"
    );
    private static final Set<String> POST_RECORDING_ACTIONS = Set.of(
        "off", "play_recorded", "record_next_free_slot", "stop",
        "return_to_arrangement", "return_to_previous_clip", "play_random"
    );

    private JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }
}
