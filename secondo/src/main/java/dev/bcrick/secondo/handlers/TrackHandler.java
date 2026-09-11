package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Application;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

import static dev.bcrick.secondo.rpc.JsonParamValidator.*;
import dev.bcrick.secondo.rpc.RpcException;

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

        // THIS METHOD IS MISNAMED AND HAS NEVER CREATED A GROUP TRACK, AT ANY PIN, ON ANY
        // PLATFORM. It is kept registered, and made to refuse informatively, rather than
        // deleted: the name is already published, and a caller who reaches for it deserves
        // to be told what the platform actually offers instead of an obscure failure.
        //
        // What it used to call: `cursorTrack.createParentTrack(SEND_COUNT, SCENE_COUNT)`.
        // That is an OBJECT-PROXY FACTORY. Its complete javadoc in the Controller API v25
        // reference (docs/bitwig-api-reference.txt:3832) reads, in full:
        //
        //     "Creates an object that represent the parent track."
        //
        // It is in the same family as its immediate neighbours -- the siblings-track-bank
        // factory and the two insertion-point accessors -- and Bitwig requires host-object
        // factories to be called during driver initialisation. That is exactly what the old
        // body reported: JSON-RPC -32603, "This can only be called during driver
        // initialization" (finding O-33, observed live 2026-08-14). The refusal was never a
        // timing problem or a missing permission; the call could not have made a group even
        // if it had been allowed to run, because making a group is not what it does.
        //
        // AND CONTROLLER API v25 CONTAINS NO GROUP-TRACK CREATION METHOD AT ALL. An
        // exhaustive search of the reference returns only observers and navigation: the
        // group tests, the group-expanded test, a track-type value reporting Group,
        // navigate-into and navigate-to-parent, set-index-in-group, select-parent, and the
        // two root/top-level group accessors. Nothing creates one. So moving the call into
        // initialisation would build a proxy object at startup and still never make a group.
        //
        // WHAT DOES WORK, and it is named in the refusal below so the next reader does not
        // have to find it: Bitwig's own named action `Create Group Track` (menu text "Add
        // Group Track", category Project), reachable through `action/invoke`. Observed live
        // 2026-08-16 at engine SHA 0c21850 -- it created a group track. Note that an action
        // invoke returns void and publishes no per-action outcome, so its success is read
        // back from the track list afterwards, never from the invoke itself.
        //
        // Decided at plan 06-08's Task 3 checkpoint. The two options not chosen were
        // `leave-both` (record the finding only) and `rename-and-deprecate` (rename the RPC
        // method and remove the tool's operation member). `bitwig_track`'s published
        // `create_group` operation is DELIBERATELY LEFT IN PLACE: the schema is published
        // once and Claude has already been conformed to it, so the member stays and now
        // fails informatively instead of obscurely.
        dispatcher.register("track/createGroup", params -> {
            JsonObject data = new JsonObject();
            data.addProperty("reason",
                "track/createGroup is misnamed and cannot work. It called "
                + "CursorTrack.createParentTrack(...), which is an object-proxy factory whose "
                + "complete javadoc reads \"Creates an object that represent the parent track\" "
                + "-- it does not create a group track, and host-object factories may only be "
                + "called during driver initialization, which is what the old -32603 reported.");
            data.addProperty("platformLimit",
                "Bitwig Controller API v25 contains NO group-track creation method at all. An "
                + "exhaustive search of the reference returns only observers and navigation: "
                + "isGroup, isGroupExpanded, a track-type value reporting Group, "
                + "navigateIntoTrackGroup, navigateToParentTrackGroup, setIndexInGroup, "
                + "selectParent, and the root/top-level group accessors. Nothing creates one.");
            data.addProperty("useInstead",
                "Invoke Bitwig's named action \"Create Group Track\" (menu text \"Add Group "
                + "Track\", category Project) via action/invoke. An action invoke returns void "
                + "and publishes no per-action outcome, so read the result back from the track "
                + "list rather than from the invoke.");
            data.addProperty("finding", "O-33; decision recorded by plan 06-08 Task 3");
            throw new RpcException(-32001, "GROUP_CREATION_NOT_SUPPORTED_BY_API", data);
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
