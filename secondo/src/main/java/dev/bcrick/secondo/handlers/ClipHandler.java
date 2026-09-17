package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.ClipLauncherSlot;
import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.SceneBank;
import com.bitwig.extension.controller.api.Track;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import static dev.bcrick.secondo.rpc.JsonParamValidator.*;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

public class ClipHandler {

    private static final int SCENE_COUNT = 16;
    private static final Set<String> LAUNCH_QUANTIZATIONS = Set.of(
        "default", "none", "8", "4", "2", "1", "1/2", "1/4", "1/8", "1/16"
    );
    private static final Set<String> LAUNCH_MODES = Set.of(
        "default", "from_start", "continue_or_from_start", "continue_or_synced", "synced"
    );

    private final TrackBankManager trackBankManager;
    private final SceneBank sceneBank;
    private final Clip cursorClip;
    private final StateCache stateCache;

    public ClipHandler(TrackBankManager trackBankManager, SceneBank sceneBank, Clip cursorClip,
                       StateCache stateCache) {
        this.trackBankManager = trackBankManager;
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

        // Phase 26 (D-4 / B-3): load a saved .bwclip into a launcher slot.
        // bitwig-api-reference.txt :902 ClipLauncherSlot#replaceInsertionPoint() and :14177
        // InsertionPoint#insertFile(String) -- whose own doc sentence is "If it's not possible to
        // do so then this does nothing." So this route's "ok" is NEVER proof of a load: it means
        // the path passed the engine-side checks and the call was dispatched. The tool verifies by
        // reading the slot back. The path checks are repeated here (D-26-22) because bitwig_call
        // reaches this method without the Python checks. The insertion point is resolved at
        // request time, as clip/duplicateToSlot's is; it is not a proxy factory.
        dispatcher.register("clip/insertFile", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            String path = requireString(params, "path");
            validateClipFilePath(path);
            requireSlotIndex(slotIndex);
            ClipLauncherSlot slot = (ClipLauncherSlot) getSlotBank(trackIndex).getItemAt(slotIndex);
            slot.replaceInsertionPoint().insertFile(path);
            return new JsonPrimitive("ok");
        });

        // Phase 26 (E5): open the one shared popup browser to insert a clip into a slot.
        // bitwig-api-reference.txt :6218 ClipLauncherSlot#browseToInsertClip. D-26-18: this is
        // the ONE slot opener registered. The fallback, replaceInsertionPoint().browse(), would
        // ship under this same method name, and only if the stage-2 probe shows this call
        // misbehaving on an occupied slot.
        dispatcher.register("clip/browseToInsert", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            int slotIndex = requireInt(params, "slotIndex");
            requireSlotIndex(slotIndex);
            ((ClipLauncherSlot) getSlotBank(trackIndex).getItemAt(slotIndex)).browseToInsertClip();
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

    /**
     * ONE COORDINATE (25-REVIEW CR-03). {@code trackIndex} is the canonical public index the
     * snapshot published, resolved by the single resolver rather than subscripted raw. Range
     * refusal and its wording belong to {@code TrackBankManager.canonicalBankSlot}.
     */
    private ClipLauncherSlotBank getSlotBank(int trackIndex) {
        Track track = trackBankManager.getCanonicalTrack(trackIndex);
        return track.clipLauncherSlotBank();
    }

    /**
     * The slot range both Phase 26 slot routes share, worded as scene/launch words its own.
     */
    private static void requireSlotIndex(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SCENE_COUNT) {
            throw new IllegalArgumentException("slot index out of range: " + slotIndex);
        }
    }

    /**
     * A local drive-letter root as {@link Path#getRoot()} prints it on Windows: one letter, a
     * colon and one backslash. Anything else (a UNC root, a device root) is not local.
     */
    private static final Pattern LOCAL_DRIVE_ROOT = Pattern.compile("[A-Za-z]:\\\\");

    /** The clip file extension, and the shortest name that is not the extension alone. */
    private static final String BWCLIP_EXTENSION = ".bwclip";

    /** True when the first two characters are each a backslash or a forward slash. */
    private static boolean startsWithTwoSeparators(String path) {
        return path.length() >= 2 && isSeparator(path.charAt(0)) && isSeparator(path.charAt(1));
    }

    private static boolean isSeparator(char c) {
        return c == '\\' || c == '/';
    }

    /**
     * D-26-22: the engine-side clip file checks, in the contract's order. Each failure is an
     * IllegalArgumentException (-32602) and insertFile is never reached.
     *
     * <ol>
     *   <li>not absolute: "clip file path is not absolute: "</li>
     *   <li>not on a local drive-letter root: "clip file path is a network path: "</li>
     *   <li>final component does not end in ".bwclip" (any case) or is only the extension:
     *       "clip file path does not end in .bwclip: "</li>
     *   <li>not a regular file: "clip file path is not an existing file: "</li>
     * </ol>
     *
     * <p>CR-01: the network test reads the parsed root, not a string prefix. Windows parses ANY
     * two leading separators, in any mix of backslash and forward slash, as a UNC root, so the
     * original two-literal prefix test let a backslash-then-slash or slash-then-backslash UNC
     * spelling through to {@link Files#isRegularFile}, which is an outbound SMB request on
     * Bitwig's control-surface thread (T-26-01). A string prefix was never the right test. The
     * device-prefixed spellings (backslash-backslash-question-mark, backslash-backslash-dot) may
     * not parse at all, so they are refused by the two-separator rule whether or not
     * {@link Path#of} accepts them; a device path to a local drive is refused too. The rule and
     * the order are the ones src/secondo/tools/browse.py and mock/state.py use (IN-02: a name is
     * longer than the extension alone). Every check before the existence check is a pure string
     * parse that touches nothing on disk or on the network.
     *
     * <p>A path the platform cannot parse at all (InvalidPathException) cannot be proven absolute
     * or existing; it skips the absoluteness check and ends at the last message unless an
     * earlier rule refuses it.
     *
     * <p>Accepted residual (owner answer (ii), 2026-09-16, T-26-64): the existence check still
     * runs on the control-surface thread for a local drive-letter root, so a mapped network drive
     * letter whose share is offline can stall the extension until Windows times the connection
     * out; moving the check off that thread needs Phase 29's deferrable responses.
     */
    private static void validateClipFilePath(String path) {
        Path parsed;
        try {
            parsed = Path.of(path);
        } catch (InvalidPathException e) {
            parsed = null;
        }
        if (parsed != null && !parsed.isAbsolute()) {
            throw new IllegalArgumentException("clip file path is not absolute: " + path);
        }
        if (startsWithTwoSeparators(path)
                || (parsed != null && (parsed.getRoot() == null
                    || !LOCAL_DRIVE_ROOT.matcher(parsed.getRoot().toString()).matches()))) {
            throw new IllegalArgumentException("clip file path is a network path: " + path);
        }
        if (!isBwclipName(finalComponent(path, parsed))) {
            throw new IllegalArgumentException("clip file path does not end in .bwclip: " + path);
        }
        if (parsed == null || !Files.isRegularFile(parsed)) {
            throw new IllegalArgumentException("clip file path is not an existing file: " + path);
        }
    }

    /** The final component: the parsed file name, else the raw text after the last separator. */
    private static String finalComponent(String path, Path parsed) {
        if (parsed != null) {
            Path name = parsed.getFileName();
            return name == null ? "" : name.toString();
        }
        int last = Math.max(path.lastIndexOf('\\'),path.lastIndexOf('/'));
        return path.substring(last + 1);
    }

    /** IN-02: ends in ".bwclip" in any case and is longer than the extension alone. */
    private static boolean isBwclipName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(BWCLIP_EXTENSION)
            && name.length() > BWCLIP_EXTENSION.length();
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
