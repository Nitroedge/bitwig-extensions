package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.SendBank;
import com.bitwig.extension.controller.api.Track;
import com.google.gson.JsonObject;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

public class SendHandler {

    private final TrackBankManager trackBankManager;
    private final int sendCount;

    public SendHandler(TrackBankManager trackBankManager, int sendCount) {
        this.trackBankManager = trackBankManager;
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
        if (sendIndex < 0 || sendIndex >= sendCount) {
            throw new IllegalArgumentException("send index out of range: " + sendIndex);
        }
        // ONE COORDINATE (25-REVIEW CR-03). trackIndex is the canonical public index the
        // snapshot published; the single resolver maps it to a physical slot and owns the
        // out-of-range refusal wording.
        Track track = trackBankManager.getCanonicalTrack(trackIndex);
        SendBank sendBank = track.sendBank();
        return (Send) sendBank.getItemAt(sendIndex);
    }

    private JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }
}
