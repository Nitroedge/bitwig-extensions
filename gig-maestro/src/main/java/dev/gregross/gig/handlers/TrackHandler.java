package dev.gregross.gig.handlers;

import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.gregross.gig.extension.StateCache;
import dev.gregross.gig.rpc.JsonRpcDispatcher;

import static dev.gregross.gig.rpc.JsonParamValidator.*;
import dev.gregross.gig.rpc.RpcException;

public class TrackHandler {

    private static final int SEND_COUNT = 4;
    private static final int SCENE_COUNT = 16;

    private final TrackBank trackBank;
    private final Application application;
    private final CursorTrack cursorTrack;
    private final TrackBankManager trackBankManager;
    private final StateCache stateCache;
    private final NoteInput noteInput;

    public TrackHandler(TrackBank trackBank, Application application,
                        CursorTrack cursorTrack, TrackBankManager trackBankManager,
                        StateCache stateCache, NoteInput noteInput) {
        this.trackBank = trackBank;
        this.application = application;
        this.cursorTrack = cursorTrack;
        this.trackBankManager = trackBankManager;
        this.stateCache = stateCache;
        this.noteInput = noteInput;

        // Mark cursor properties interested for cursor/getInfo
        cursorTrack.isPinned().markInterested();
        cursorTrack.trackType().markInterested();
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("track/setVolume", params -> {
            Track track = getTrack(params.get("index").getAsInt());
            double value = params.get("value").getAsDouble();
            track.volume().value().setImmediately(value);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("track/setPan", params -> {
            Track track = getTrack(params.get("index").getAsInt());
            double value = params.get("value").getAsDouble();
            track.pan().value().setImmediately(value);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("track/setMute", params -> {
            Track track = getTrack(params.get("index").getAsInt());
            boolean muted = params.get("muted").getAsBoolean();
            track.mute().set(muted);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("track/setSolo", params -> {
            Track track = getTrack(params.get("index").getAsInt());
            boolean soloed = params.get("soloed").getAsBoolean();
            track.solo().set(soloed);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("track/toggleSolo", params -> {
            Track track = getTrack(params.get("index").getAsInt());
            boolean exclusive = params.has("exclusive") && params.get("exclusive").getAsBoolean();
            track.solo().toggle(exclusive);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("track/selectInMixer", params -> {
            Track track = getTrack(params.get("index").getAsInt());
            track.selectInMixer();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("track/makeVisibleInMixer", params -> {
            Track track = getTrack(params.get("index").getAsInt());
            track.makeVisibleInMixer();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("track/setArm", params -> {
            Track track = getTrack(params.get("index").getAsInt());
            boolean armed = params.get("armed").getAsBoolean();
            track.arm().set(armed);
            return new JsonPrimitive("ok");
        });

        // --- Phase 6: Track management methods ---

        dispatcher.register("track/createAudio", params -> {
            int position = optionalInt(params, "position", -1);
            application.createAudioTrack(position);
            return cursorResponse();
        });

        dispatcher.register("track/createInstrument", params -> {
            int position = optionalInt(params, "position", -1);
            application.createInstrumentTrack(position);
            return cursorResponse();
        });

        dispatcher.register("track/createEffect", params -> {
            int position = optionalInt(params, "position", -1);
            application.createEffectTrack(position);
            return cursorResponse();
        });

        dispatcher.register("track/select", params -> {
            int index = requireInt(params, "index");
            trackBankManager.selectByIndex(index);
            // Return bank track name (accurate) — cursorTrack name is stale until next flush
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("trackName", stateCache.getTrackName(index));
            result.addProperty("trackIndex", index);
            return result;
        });

        dispatcher.register("track/rename", params -> {
            String name = requireString(params, "name");
            cursorTrack.name().set(name);
            return cursorResponse();
        });

        dispatcher.register("track/deleteSelected", params -> {
            cursorTrack.deleteObject();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            return result;
        });

        dispatcher.register("track/duplicate", params -> {
            cursorTrack.duplicate();
            return cursorResponse();
        });

        // --- Phase 13: Mixer methods ---

        dispatcher.register("track/setColor", params -> {
            Track track = getTrack(requireInt(params, "index"));
            float r = params.get("r").getAsFloat();
            float g = params.get("g").getAsFloat();
            float b = params.get("b").getAsFloat();
            track.color().set(r, g, b);
            return ok();
        });

        dispatcher.register("track/setCursorColor", params -> {
            float r = params.get("r").getAsFloat();
            float g = params.get("g").getAsFloat();
            float b = params.get("b").getAsFloat();
            cursorTrack.color().set(r, g, b);
            return ok();
        });

        dispatcher.register("track/setCrossfade", params -> {
            Track track = getTrack(requireInt(params, "index"));
            String mode = requireString(params, "mode").toUpperCase();
            if (!mode.equals("A") && !mode.equals("B") && !mode.equals("AB")) {
                throw new IllegalArgumentException("invalid crossfade mode: " + mode + " (expected A, B, or AB)");
            }
            track.crossFadeMode().set(mode);
            return ok();
        });

        dispatcher.register("track/setMonitor", params -> {
            Track track = getTrack(requireInt(params, "index"));
            String mode = requireString(params, "mode").toUpperCase();
            if (!mode.equals("ON") && !mode.equals("OFF") && !mode.equals("AUTO")) {
                throw new IllegalArgumentException("invalid monitor mode: " + mode + " (expected ON, OFF, or AUTO)");
            }
            track.monitorMode().set(mode);
            return ok();
        });

        // --- Phase 25: Group & routing methods ---

        dispatcher.register("track/setGroupExpanded", params -> {
            boolean hasExpanded = params.has("expanded") && !params.get("expanded").isJsonNull();
            boolean hasToggle = params.has("toggle") && !params.get("toggle").isJsonNull();
            if (!hasExpanded && !hasToggle) {
                throw new IllegalArgumentException("must provide 'expanded' or 'toggle' parameter");
            }
            if (hasExpanded && hasToggle) {
                throw new IllegalArgumentException("'expanded' and 'toggle' are mutually exclusive — provide one, not both");
            }
            if (hasToggle) {
                cursorTrack.isGroupExpanded().toggle();
            } else {
                cursorTrack.isGroupExpanded().set(params.get("expanded").getAsBoolean());
            }
            return ok();
        });

        dispatcher.register("track/navigateInto", params -> {
            application.navigateIntoTrackGroup(cursorTrack);
            return ok();
        });

        dispatcher.register("track/navigateToParent", params -> {
            application.navigateToParentTrackGroup();
            return ok();
        });

        dispatcher.register("track/createGroup", params -> {
            cursorTrack.createParentTrack(SEND_COUNT, SCENE_COUNT);
            return ok();
        });

        dispatcher.register("track/addNoteSource", params -> {
            cursorTrack.addNoteSource(noteInput);
            return ok();
        });

        dispatcher.register("track/removeNoteSource", params -> {
            cursorTrack.removeNoteSource(noteInput);
            return ok();
        });

        // --- Cursor navigation methods ---

        dispatcher.register("cursor/selectParent", params -> {
            cursorTrack.selectParent();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("cursor/selectFirstChild", params -> {
            cursorTrack.selectFirstChild();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("cursor/setPinned", params -> {
            if (!params.has("pinned")) {
                throw new IllegalArgumentException("missing 'pinned' parameter");
            }
            cursorTrack.isPinned().set(params.get("pinned").getAsBoolean());
            return new JsonPrimitive("ok");
        });

        dispatcher.register("cursor/getInfo", params -> {
            JsonObject info = new JsonObject();
            info.addProperty("name", cursorTrack.name().get());
            info.addProperty("trackType", cursorTrack.trackType().get());
            info.addProperty("isPinned", cursorTrack.isPinned().get());
            return info;
        });

        // --- Activity feedback methods ---

        dispatcher.register("track/getVuMeters", params -> {
            int[] meters = stateCache.getVuMeters();
            JsonArray arr = new JsonArray();
            for (int v : meters) arr.add(v);
            return arr;
        });

        dispatcher.register("track/getPlayingNotes", params -> {
            int index = params.get("index").getAsInt();
            int[] packed = stateCache.getPlayingNotes(index);
            JsonArray arr = new JsonArray();
            for (int n = 0; n < packed.length; n += 2) {
                JsonObject note = new JsonObject();
                note.addProperty("pitch", packed[n]);
                note.addProperty("velocity", packed[n + 1]);
                arr.add(note);
            }
            return arr;
        });

        // --- Track bank scroll methods ---

        dispatcher.register("trackBank/scrollTo", params -> {
            int position = requireInt(params, "position");
            int itemCount = stateCache.getTrackItemCount();
            if (position < 0 || position >= itemCount) {
                JsonObject error = new JsonObject();
                error.addProperty("itemCount", itemCount);
                error.addProperty("requestedPosition", position);
                throw new RpcException(-32001, "POSITION_OUT_OF_RANGE", error);
            }
            trackBank.scrollPosition().set(position);
            return ok();
        });

        dispatcher.register("trackBank/scrollBy", params -> {
            int amount = requireInt(params, "amount");
            trackBank.scrollBy(amount);
            return ok();
        });

        dispatcher.register("trackBank/getScrollInfo", params -> {
            return stateCache.getTrackBankScrollInfo();
        });
    }

    private Track getTrack(int index) {
        if (index < 0 || index >= trackBank.getSizeOfBank()) {
            throw new IllegalArgumentException("track index out of range: " + index);
        }
        return (Track) trackBank.getItemAt(index);
    }

    private JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private JsonObject cursorResponse() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("cursorTrackName", cursorTrack.name().get());
        return result;
    }

}
