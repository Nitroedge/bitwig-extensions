package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Arpeggiator;
import com.bitwig.extension.controller.api.NoteLatch;
import com.bitwig.extension.controller.api.NoteInput;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.bitwigpal.extension.StateCache;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;

import java.util.Set;

public class NoteInputHandler {

    private static final Set<String> VALID_ARP_MODES = Set.of(
        "all", "up", "up-down", "up-then-down", "down", "down-up", "down-then-up",
        "flow", "random", "converge-up", "converge-down", "diverge-up", "diverge-down",
        "thumb-up", "thumb-down", "pinky-up", "pinky-down"
    );

    private static final Set<String> VALID_LATCH_MODES = Set.of(
        "chord", "toggle", "velocity"
    );

    private final NoteInput noteInput;
    private final Arpeggiator arpeggiator;
    private final NoteLatch noteLatch;
    private final StateCache stateCache;

    public NoteInputHandler(NoteInput noteInput, Arpeggiator arpeggiator, NoteLatch noteLatch, StateCache stateCache) {
        this.noteInput = noteInput;
        this.arpeggiator = arpeggiator;
        this.noteLatch = noteLatch;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("noteInput/sendNote", this::handleSendNote);
        dispatcher.register("noteInput/sendMidi", this::handleSendMidi);
        dispatcher.register("arpeggiator/configure", this::handleArpConfigure);
        dispatcher.register("arpeggiator/setEnabled", this::handleArpSetEnabled);
        dispatcher.register("arpeggiator/releaseNotes", this::handleArpReleaseNotes);
        dispatcher.register("arpeggiator/getState", this::handleArpGetState);
        dispatcher.register("noteLatch/configure", this::handleLatchConfigure);
        dispatcher.register("noteLatch/setEnabled", this::handleLatchSetEnabled);
        dispatcher.register("noteLatch/releaseNotes", this::handleLatchReleaseNotes);
        dispatcher.register("noteLatch/getState", this::handleLatchGetState);
    }

    private JsonElement handleSendNote(JsonObject params) {
        int note = params.get("note").getAsInt();
        int velocity = params.get("velocity").getAsInt();
        int channel = params.has("channel") ? params.get("channel").getAsInt() : 0;

        if (note < 0 || note > 127) {
            throw new IllegalArgumentException("note must be 0-127, got " + note);
        }
        if (velocity < 0 || velocity > 127) {
            throw new IllegalArgumentException("velocity must be 0-127, got " + velocity);
        }
        if (channel < 0 || channel > 15) {
            throw new IllegalArgumentException("channel must be 0-15, got " + channel);
        }

        int status = velocity == 0 ? (0x80 | channel) : (0x90 | channel);
        noteInput.sendRawMidiEvent(status, note, velocity);

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleSendMidi(JsonObject params) {
        int status = params.get("status").getAsInt();
        int data0 = params.get("data0").getAsInt();
        int data1 = params.get("data1").getAsInt();

        noteInput.sendRawMidiEvent(status, data0, data1);

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleArpConfigure(JsonObject params) {
        if (params.has("mode")) {
            String mode = params.get("mode").getAsString().toLowerCase();
            if (!VALID_ARP_MODES.contains(mode)) {
                throw new IllegalArgumentException(
                    "Invalid arpeggiator mode '" + mode + "'. Valid modes: " + VALID_ARP_MODES);
            }
            arpeggiator.mode().set(mode);
        }
        if (params.has("octaves")) {
            arpeggiator.octaves().set(params.get("octaves").getAsInt());
        }
        if (params.has("rate")) {
            arpeggiator.rate().set(params.get("rate").getAsDouble());
        }
        if (params.has("gateLength")) {
            arpeggiator.gateLength().set(params.get("gateLength").getAsDouble());
        }
        if (params.has("shuffle")) {
            arpeggiator.shuffle().set(params.get("shuffle").getAsBoolean());
        }
        if (params.has("humanize")) {
            arpeggiator.humanize().set(params.get("humanize").getAsDouble());
        }
        if (params.has("isFreeRunning")) {
            arpeggiator.isFreeRunning().set(params.get("isFreeRunning").getAsBoolean());
        }
        if (params.has("enableOverlappingNotes")) {
            arpeggiator.enableOverlappingNotes().set(params.get("enableOverlappingNotes").getAsBoolean());
        }
        if (params.has("usePressureToVelocity")) {
            arpeggiator.usePressureToVelocity().set(params.get("usePressureToVelocity").getAsBoolean());
        }
        if (params.has("terminateNotesImmediately")) {
            arpeggiator.terminateNotesImmediately().set(params.get("terminateNotesImmediately").getAsBoolean());
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleArpSetEnabled(JsonObject params) {
        arpeggiator.isEnabled().set(params.get("enabled").getAsBoolean());

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleArpReleaseNotes(JsonObject params) {
        arpeggiator.releaseNotes();

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleArpGetState(JsonObject params) {
        return stateCache.getArpeggiatorState();
    }

    private JsonElement handleLatchConfigure(JsonObject params) {
        if (params.has("mode")) {
            String mode = params.get("mode").getAsString().toLowerCase();
            if (!VALID_LATCH_MODES.contains(mode)) {
                throw new IllegalArgumentException(
                    "Invalid note latch mode '" + mode + "'. Valid modes: " + VALID_LATCH_MODES);
            }
            noteLatch.mode().set(mode);
        }
        if (params.has("mono")) {
            noteLatch.mono().set(params.get("mono").getAsBoolean());
        }
        if (params.has("velocityThreshold")) {
            noteLatch.velocityThreshold().set(params.get("velocityThreshold").getAsInt());
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleLatchSetEnabled(JsonObject params) {
        noteLatch.isEnabled().set(params.get("enabled").getAsBoolean());

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleLatchReleaseNotes(JsonObject params) {
        noteLatch.releaseNotes();

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonElement handleLatchGetState(JsonObject params) {
        return stateCache.getNoteLatchState();
    }
}
