package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.ClipLauncherSlot;
import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.SceneBank;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import static dev.bcrick.bitwigpal.rpc.JsonParamValidator.*;

import java.util.Set;
import dev.bcrick.bitwigpal.extension.StateCache;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;

public class ClipHandler {

    private static final int SCENE_COUNT = 16;
    private static final Set<String> LAUNCH_QUANTIZATIONS = Set.of(
        "default", "none", "8", "4", "2", "1", "1/2", "1/4", "1/8", "1/16"
    );
    private static final Set<String> LAUNCH_MODES = Set.of(
        "default", "from_start", "continue_or_from_start", "continue_or_synced", "synced"
    );

    private final TrackBank trackBank;
    private final SceneBank sceneBank;
    private final Clip cursorClip;
    private final StateCache stateCache;

    public ClipHandler(TrackBank trackBank, SceneBank sceneBank, Clip cursorClip, StateCache stateCache) {
        this.trackBank = trackBank;
        this.sceneBank = sceneBank;
        this.cursorClip = cursorClip;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("clip/launch", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            boolean hasQuantization = params.has("quantization");
            boolean hasLaunchMode = params.has("launchMode");
            if (hasQuantization != hasLaunchMode) {
                throw new IllegalArgumentException("quantization and launchMode must both be provided or both omitted");
            }
            if (hasQuantization) {
                String quantization = params.get("quantization").getAsString();
                String launchMode = params.get("launchMode").getAsString();
                validateLaunchQuantization(quantization);
                validateLaunchMode(launchMode);
                ClipLauncherSlot slot = (ClipLauncherSlot) getSlotBank(trackIndex).getItemAt(slotIndex);
                slot.launchWithOptions(quantization, launchMode);
            } else {
                getSlotBank(trackIndex).launch(slotIndex);
            }
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/stop", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            getSlotBank(trackIndex).stop();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/record", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            getSlotBank(trackIndex).record(slotIndex);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/create", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            int lengthInBeats = requireInt(params, "lengthInBeats");
            getSlotBank(trackIndex).createEmptyClip(slotIndex, lengthInBeats);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/select", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            boolean force = params.has("force") && params.get("force").getAsBoolean();
            if (!force && !stateCache.clipHasContent(trackIndex, slotIndex)) {
                throw new IllegalArgumentException(
                    "slot is empty at track " + trackIndex + " slot " + slotIndex
                    + " — create a clip first with clip/create");
            }
            ClipLauncherSlotBank slotBank = getSlotBank(trackIndex);
            ClipLauncherSlot slot = (ClipLauncherSlot) slotBank.getItemAt(slotIndex);
            slot.select();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/delete", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            ClipLauncherSlotBank slotBank = getSlotBank(trackIndex);
            ClipLauncherSlot slot = (ClipLauncherSlot) slotBank.getItemAt(slotIndex);
            slot.deleteObject();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/rename", params -> {
            JsonElement nameEl = params.get("name");
            if (nameEl == null) {
                throw new IllegalArgumentException("missing 'name' parameter");
            }
            cursorClip.setName(nameEl.getAsString());
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/duplicate", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            getSlotBank(trackIndex).duplicateClip(slotIndex);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/duplicateToSlot", params -> {
            int srcTrackIndex = requireInt(params, "srcTrackIndex");
            int srcSlotIndex = requireInt(params, "srcSlotIndex");
            int destTrackIndex = requireInt(params, "destTrackIndex");
            int destSlotIndex = requireInt(params, "destSlotIndex");
            ClipLauncherSlot sourceSlot = (ClipLauncherSlot) getSlotBank(srcTrackIndex).getItemAt(srcSlotIndex);
            ClipLauncherSlot destSlot = (ClipLauncherSlot) getSlotBank(destTrackIndex).getItemAt(destSlotIndex);
            destSlot.replaceInsertionPoint().copySlotsOrScenes(sourceSlot);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("scene/launch", params -> {
            int index = requireInt(params, "index");
            if (index < 0 || index >= SCENE_COUNT) {
                throw new IllegalArgumentException("scene index out of range: " + index);
            }
            boolean hasQuantization = params.has("quantization");
            boolean hasLaunchMode = params.has("launchMode");
            if (hasQuantization != hasLaunchMode) {
                throw new IllegalArgumentException("quantization and launchMode must both be provided or both omitted");
            }
            if (hasQuantization) {
                String quantization = params.get("quantization").getAsString();
                String launchMode = params.get("launchMode").getAsString();
                validateLaunchQuantization(quantization);
                validateLaunchMode(launchMode);
                sceneBank.getScene(index).launchWithOptions(quantization, launchMode);
            } else {
                sceneBank.launchScene(index);
            }
            return new JsonPrimitive("ok");
        });

        // Per-clip launch settings (operate on cursor clip)
        dispatcher.register("clip/setLaunchQuantization", params -> {
            String quantization = requireString(params, "quantization");
            validateLaunchQuantization(quantization);
            cursorClip.launchQuantization().set(quantization);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/setLaunchMode", params -> {
            String launchMode = requireString(params, "launchMode");
            validateLaunchMode(launchMode);
            cursorClip.launchMode().set(launchMode);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/setShuffle", params -> {
            boolean enabled = requireBoolean(params, "enabled");
            cursorClip.getShuffle().set(enabled);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/setAccent", params -> {
            double value = requireDouble(params, "value");
            if (value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("accent value must be between 0.0 and 1.0");
            }
            cursorClip.getAccent().setImmediately(value);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/setUseLoopStartAsQuantizationReference", params -> {
            boolean enabled = requireBoolean(params, "enabled");
            cursorClip.useLoopStartAsQuantizationReference().set(enabled);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/getLaunchSettings", params -> stateCache.getClipLaunchSettings());

        // Color (slot-level)
        dispatcher.register("clip/setColor", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            float r = (float) requireDouble(params, "r");
            float g = (float) requireDouble(params, "g");
            float b = (float) requireDouble(params, "b");
            validateColorComponent(r, "r");
            validateColorComponent(g, "g");
            validateColorComponent(b, "b");
            ClipLauncherSlot slot = (ClipLauncherSlot) getSlotBank(trackIndex).getItemAt(slotIndex);
            slot.color().set(r, g, b);
            return new JsonPrimitive("ok");
        });

        // Play/loop boundaries (cursor clip)
        dispatcher.register("clip/setPlayStart", params -> {
            double beats = requireDouble(params, "beats");
            cursorClip.getPlayStart().set(beats);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/setPlayStop", params -> {
            double beats = requireDouble(params, "beats");
            cursorClip.getPlayStop().set(beats);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/setLoopStart", params -> {
            double beats = requireDouble(params, "beats");
            cursorClip.getLoopStart().set(beats);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/setLoopLength", params -> {
            double beats = requireDouble(params, "beats");
            cursorClip.getLoopLength().set(beats);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/setLoopEnabled", params -> {
            boolean enabled = requireBoolean(params, "enabled");
            cursorClip.isLoopEnabled().set(enabled);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/getPlaybackSettings", params -> stateCache.getClipPlaybackSettings());

        // Note operations (cursor clip)
        dispatcher.register("clip/quantize", params -> {
            double amount = requireDouble(params, "amount");
            if (amount < 0.0 || amount > 1.0) {
                throw new IllegalArgumentException("quantize amount must be between 0.0 and 1.0");
            }
            cursorClip.quantize(amount);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/transpose", params -> {
            int semitones = requireInt(params, "semitones");
            cursorClip.transpose(semitones);
            return new JsonPrimitive("ok");
        });

        // Content operations (cursor clip)
        dispatcher.register("clip/duplicateContent", params -> {
            cursorClip.duplicateContent();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/showInEditor", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            ClipLauncherSlot slot = (ClipLauncherSlot) getSlotBank(trackIndex).getItemAt(slotIndex);
            slot.showInEditor();
            return new JsonPrimitive("ok");
        });

        // Alternative launch methods (slot-level)
        dispatcher.register("clip/launchAlt", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            ClipLauncherSlot slot = (ClipLauncherSlot) getSlotBank(trackIndex).getItemAt(slotIndex);
            slot.launchAlt();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/launchRelease", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            ClipLauncherSlot slot = (ClipLauncherSlot) getSlotBank(trackIndex).getItemAt(slotIndex);
            slot.launchRelease();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("clip/launchReleaseAlt", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            ClipLauncherSlot slot = (ClipLauncherSlot) getSlotBank(trackIndex).getItemAt(slotIndex);
            slot.launchReleaseAlt();
            return new JsonPrimitive("ok");
        });
    }

    private ClipLauncherSlotBank getSlotBank(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= trackBank.getSizeOfBank()) {
            throw new IllegalArgumentException("track index out of range: " + trackIndex);
        }
        Track track = (Track) trackBank.getItemAt(trackIndex);
        return track.clipLauncherSlotBank();
    }



    private void validateColorComponent(float value, String name) {
        if (value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0");
        }
    }

    private void validateLaunchQuantization(String value) {
        if (!LAUNCH_QUANTIZATIONS.contains(value)) {
            throw new IllegalArgumentException("invalid launch quantization: " + value
                + " — valid values: " + LAUNCH_QUANTIZATIONS);
        }
    }

    private void validateLaunchMode(String value) {
        if (!LAUNCH_MODES.contains(value)) {
            throw new IllegalArgumentException("invalid launch mode: " + value
                + " — valid values: " + LAUNCH_MODES);
        }
    }
}
