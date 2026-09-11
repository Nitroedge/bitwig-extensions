package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.SendBank;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import com.google.gson.JsonObject;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

public class SendHandler {

    private final TrackBank trackBank;
    private final int sendCount;

    public SendHandler(TrackBank trackBank, int sendCount) {
        this.trackBank = trackBank;
        this.sendCount = sendCount;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("send/setLevel", params -> {
            Send send = getSend(params);
            if (!params.has("value")) {
                throw new IllegalArgumentException("missing 'value' parameter");
            }
            double value = params.get("value").getAsDouble();
            send.value().setImmediately(value);
            return ok();
        });

        dispatcher.register("send/setMode", params -> {
            Send send = getSend(params);
            if (!params.has("mode")) {
                throw new IllegalArgumentException("missing 'mode' parameter");
            }
            String mode = params.get("mode").getAsString().toUpperCase();
            if (!mode.equals("AUTO") && !mode.equals("PRE") && !mode.equals("POST")) {
                throw new IllegalArgumentException("invalid send mode: " + mode + " (expected AUTO, PRE, or POST)");
            }
            send.sendMode().set(mode);
            return ok();
        });

        dispatcher.register("send/setEnabled", params -> {
            Send send = getSend(params);
            if (!params.has("enabled")) {
                throw new IllegalArgumentException("missing 'enabled' parameter");
            }
            boolean enabled = params.get("enabled").getAsBoolean();
            send.isEnabled().set(enabled);
            return ok();
        });
    }

    private Send getSend(JsonObject params) {
        if (!params.has("trackIndex")) {
            throw new IllegalArgumentException("missing 'trackIndex' parameter");
        }
        if (!params.has("sendIndex")) {
            throw new IllegalArgumentException("missing 'sendIndex' parameter");
        }
        int trackIndex = params.get("trackIndex").getAsInt();
        int sendIndex = params.get("sendIndex").getAsInt();
        if (trackIndex < 0 || trackIndex >= trackBank.getSizeOfBank()) {
            throw new IllegalArgumentException("track index out of range: " + trackIndex);
        }
        if (sendIndex < 0 || sendIndex >= sendCount) {
            throw new IllegalArgumentException("send index out of range: " + sendIndex);
        }
        Track track = (Track) trackBank.getItemAt(trackIndex);
        SendBank sendBank = track.sendBank();
        return (Send) sendBank.getItemAt(sendIndex);
    }

    private JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }
}
