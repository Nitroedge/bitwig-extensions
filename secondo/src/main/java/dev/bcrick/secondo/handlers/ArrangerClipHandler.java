package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteOccurrence;
import com.bitwig.extension.controller.api.NoteStep;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.extension.SecondoExtension;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;

import static dev.bcrick.secondo.rpc.JsonParamValidator.*;

/**
 * The {@code arrangerClip/*} JSON-RPC namespace: the arranger timeline's counterpart to the
 * launcher-scoped {@code clip/*} and {@code note/*} namespaces.
 *
 * <p>There are two Clip objects in this extension, not one. The launcher cursor clip is created
 * from the cursor track and owns the unqualified {@code clip/*} and {@code note/*} namespaces;
 * this one is created from the ControllerHost and owns {@code arrangerClip/*}. The two surfaces
 * are not symmetric and the namespaces are deliberately separate rather than one namespace with a
 * surface switch: an arranger clip has no ClipLauncherSlot, so every slot-resolving and
 * launch-shaped operation the launcher publishes is absent here BY CONSTRUCTION rather than
 * present-and-inert. Publishing a method that compiles but does nothing on this surface is the
 * accepted-but-did-nothing failure this project's verification architecture exists to eliminate.
 *
 * <p>Constructor shape is {@code (Clip, StateCache)} -- the same two arguments as
 * {@link NoteHandler}, which is itself evidence that the note-content cut is the right one.
 *
 * <p>Every mutator here answers {@code "ok"}, or an object naming what it dispatched. Either way
 * that means the call was accepted for dispatch and nothing more: Bitwig's Controller API returns
 * void for these and updates state asynchronously through observers, so a reply is never evidence
 * that a clip changed. Callers verify by reading back.
 *
 * <p><b>The namespace is complete and closed.</b> Its membership is the note-content, transform,
 * boundary and identity subset enumerated in
 * {@code .planning/phases/06-arranger-editing/COVERAGE.md}, whose opt-out table carries a stated
 * reason for every capability that is absent. No count is written down here or anywhere else in
 * this file: the published surface is derived from the dispatcher's registrations by
 * {@code api/list}, and a number typed beside a list is a number that goes stale against it.
 *
 * <p>Two structural points a reader comparing this file against {@link ClipHandler} will need:
 * {@code arrangerClip/setPlaybackSettings} folds five launcher setters into one registration, and
 * {@code arrangerClip/setColor} is new code rather than a port because the launcher's twin is
 * slot-level. Both are commented at their registration sites.
 */
public class ArrangerClipHandler {

    // The grid the arranger cursor clip was actually created with, taken from the one place that
    // creates it rather than re-declared here. A third declaration of 256/128 would be a new
    // cross-declaration constant needing its own consistency test; referencing the source of the
    // value the factory was called with is both smaller and harder to get out of step.
    private static final int GRID_WIDTH = SecondoExtension.CLIP_GRID_WIDTH;
    private static final int GRID_HEIGHT = SecondoExtension.CLIP_GRID_HEIGHT;

    // The step size arrangerClip/clearAllNotes widens the viewport to before clearing, so that
    // clearSteps() reaches the whole clip rather than only the window the caller happened to be
    // looking through. Same value and same reason as NoteHandler.WIDE_STEP_SIZE: 256 steps x 4.0
    // beats covers 1024 beats (256 bars in 4/4).
    private static final double WIDE_STEP_SIZE = 4.0;

    private final Clip arrangerClip;
    private final StateCache stateCache;

    public ArrangerClipHandler(Clip arrangerClip, StateCache stateCache) {
        this.arrangerClip = arrangerClip;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {

        dispatcher.register("arrangerClip/getState", params -> stateCache.getArrangerClipState());

        dispatcher.register("arrangerClip/setNotes", params -> {
            JsonArray notes = requireArray(params, "notes");
            int count = 0;
            for (JsonElement el : notes) {
                JsonObject note = el.getAsJsonObject();
                int x = note.get("x").getAsInt();
                int y = note.get("y").getAsInt();
                requireGridCoordinates(x, y);
                int velocity = note.has("velocity")
                    ? (int) (note.get("velocity").getAsDouble() * 127)
                    : 100;
                double duration = note.has("duration")
                    ? note.get("duration").getAsDouble()
                    : 0.25;
                arrangerClip.setStep(0, x, y, velocity, duration);
                count++;
            }
            // The count is how many steps were DISPATCHED, not how many notes Bitwig now holds.
            // Callers verify by reading arrangerClip/getNotes back.
            JsonObject result = new JsonObject();
            result.addProperty("count", count);
            return result;
        });

        dispatcher.register("arrangerClip/clearNote", params -> {
            int x = requireInt(params, "x");
            int y = requireInt(params, "y");
            requireGridCoordinates(x, y);
            arrangerClip.clearStep(0, x, y);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/clearAllNotes", params -> {
            // Mirrors NoteHandler's widen-clear-restore sequence, but NOT its defect. The
            // launcher's clip/setStepSize never writes the cache, so its "restore" always
            // restores the cache's 0.25 default rather than whatever the caller was using
            // (finding O-12). arrangerClip/setStepSize does write the cache, so the value
            // restored here is genuinely the last one written through this namespace.
            //
            // When nothing has ever been written the cache holds null and there is NO prior
            // value to restore. The two paths are split below rather than folded through one
            // `restored` local, because folding them made the null path write WIDE_STEP_SIZE
            // into the cache -- and getState publishes that cache, so a resolution nobody chose
            // started being reported as one somebody set. Three facts about the null path:
            //
            //   * THE WIDENED SIZE IS LEFT ON THE BITWIG CLIP, and the reply's stepSize says so,
            //     because the widen genuinely happened and hiding it would be a second invention
            //     in the other direction.
            //   * THE ENGINE'S CACHE IS DELIBERATELY NOT WRITTEN, because nothing chose that
            //     value. An engine acknowledgement is not a read-back of Bitwig -- the API
            //     publishes no step-size getter at all -- so a value nobody set must be ABSENT
            //     rather than invented.
            //   * GETSTATE THEREFORE CONTINUES TO OMIT `stepSize`. JsonRpcDispatcher constructs
            //     a bare `new Gson()` with no serializeNulls(), so a null member is dropped from
            //     the payload rather than serialised as null. That absence is what lets a caller
            //     tell "nobody set a grid" apart from "somebody set 4.0".
            //
            // On this path the reply and the cache say different things, on purpose: the reply
            // describes a dispatch, the cache describes what somebody chose.
            Double savedStepSize = stateCache.getArrangerClipStepSize();

            arrangerClip.setStepSize(WIDE_STEP_SIZE);
            arrangerClip.scrollToStep(0);
            arrangerClip.clearSteps();

            if (savedStepSize != null) {
                double restored = savedStepSize;
                arrangerClip.setStepSize(restored);
                arrangerClip.scrollToStep(0);
                stateCache.setArrangerClipStepSize(restored);

                // Confirm the restore against the cache rather than assuming the write took.
                // This is a read-back of the engine's own record -- the API publishes no
                // step-size getter, so it can never be a read-back of Bitwig, and it is not
                // reported as one. It does NOT run on the null path, where the cache is
                // deliberately left alone and there is nothing to verify against.
                Double readBack = stateCache.getArrangerClipStepSize();
                if (readBack == null || readBack != restored) {
                    throw new IllegalStateException(
                        "step size restore did not take: expected " + restored + ", cache holds " + readBack);
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("stepSize", savedStepSize != null ? savedStepSize : WIDE_STEP_SIZE);
            result.addProperty("stepSizeRestored", savedStepSize != null);
            return result;
        });

        dispatcher.register("arrangerClip/getNotes", params -> {
            JsonArray notes = new JsonArray();
            for (int x = 0; x < GRID_WIDTH; x++) {
                for (int y = 0; y < GRID_HEIGHT; y++) {
                    NoteStep step = arrangerClip.getStep(0, x, y);
                    if (step.state().name().equals("NoteOn")) {
                        JsonObject note = new JsonObject();
                        note.addProperty("x", x);
                        note.addProperty("y", y);
                        note.addProperty("velocity", step.velocity());
                        note.addProperty("duration", step.duration());
                        if (step.isChanceEnabled()) {
                            note.addProperty("chance", step.chance());
                        }
                        // Expressive properties — only include non-default values
                        double pan = step.pan();
                        if (pan != 0.0) note.addProperty("pan", pan);
                        double timbre = step.timbre();
                        if (timbre != 0.0) note.addProperty("timbre", timbre);
                        double pressure = step.pressure();
                        if (pressure != 0.0) note.addProperty("pressure", pressure);
                        double gain = step.gain();
                        if (gain != 0.5) note.addProperty("gain", gain);
                        double transpose = step.transpose();
                        if (transpose != 0.0) note.addProperty("transpose", transpose);
                        double releaseVelocity = step.releaseVelocity();
                        if (releaseVelocity != 0.0) note.addProperty("releaseVelocity", releaseVelocity);
                        double velocitySpread = step.velocitySpread();
                        if (velocitySpread != 0.0) note.addProperty("velocitySpread", velocitySpread);
                        if (step.isMuted()) note.addProperty("mute", true);
                        // Occurrence
                        if (step.isOccurrenceEnabled()) {
                            note.addProperty("occurrence", step.occurrence().name());
                        }
                        // Recurrence
                        if (step.isRecurrenceEnabled()) {
                            JsonObject rec = new JsonObject();
                            rec.addProperty("length", step.recurrenceLength());
                            rec.addProperty("mask", step.recurrenceMask());
                            note.add("recurrence", rec);
                        }
                        // Repeat
                        if (step.isRepeatEnabled()) {
                            JsonObject rep = new JsonObject();
                            rep.addProperty("count", step.repeatCount());
                            rep.addProperty("curve", step.repeatCurve());
                            rep.addProperty("velocityEnd", step.repeatVelocityEnd());
                            rep.addProperty("velocityCurve", step.repeatVelocityCurve());
                            note.add("repeat", rep);
                        }
                        notes.add(note);
                    }
                }
            }
            return notes;
        });

        dispatcher.register("arrangerClip/setChance", params -> {
            JsonArray notes = requireArray(params, "notes");
            int count = 0;
            for (JsonElement el : notes) {
                JsonObject note = el.getAsJsonObject();
                int x = note.get("x").getAsInt();
                int y = note.get("y").getAsInt();
                requireGridCoordinates(x, y);
                double chance = note.get("chance").getAsDouble();
                if (chance < 0.0 || chance > 1.0) {
                    throw new IllegalArgumentException("chance must be between 0.0 and 1.0");
                }
                NoteStep step = arrangerClip.getStep(0, x, y);
                step.setChance(chance);
                step.setIsChanceEnabled(true);
                count++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", count);
            return result;
        });

        dispatcher.register("arrangerClip/setNoteExpressions", params -> {
            JsonArray notes = requireArray(params, "notes");
            int count = 0;
            for (JsonElement el : notes) {
                JsonObject note = el.getAsJsonObject();
                int x = note.get("x").getAsInt();
                int y = note.get("y").getAsInt();
                requireGridCoordinates(x, y);
                String property = note.get("property").getAsString();
                double value = note.get("value").getAsDouble();
                NoteStep step = arrangerClip.getStep(0, x, y);
                switch (property) {
                    case "pan":
                        if (value < -1.0 || value > 1.0)
                            throw new IllegalArgumentException("pan must be between -1.0 and 1.0");
                        step.setPan(value);
                        break;
                    case "timbre":
                        if (value < -1.0 || value > 1.0)
                            throw new IllegalArgumentException("timbre must be between -1.0 and 1.0");
                        step.setTimbre(value);
                        break;
                    case "pressure":
                        if (value < 0.0 || value > 1.0)
                            throw new IllegalArgumentException("pressure must be between 0.0 and 1.0");
                        step.setPressure(value);
                        break;
                    case "gain":
                        if (value < 0.0 || value > 1.0)
                            throw new IllegalArgumentException("gain must be between 0.0 and 1.0 (0.5 = 0dB)");
                        step.setGain(value);
                        break;
                    case "transpose":
                        if (value < -96.0 || value > 96.0)
                            throw new IllegalArgumentException("transpose must be between -96 and +96 semitones");
                        step.setTranspose(value);
                        break;
                    case "releaseVelocity":
                        if (value < 0.0 || value > 1.0)
                            throw new IllegalArgumentException("releaseVelocity must be between 0.0 and 1.0");
                        step.setReleaseVelocity(value);
                        break;
                    case "velocitySpread":
                        if (value < 0.0 || value > 1.0)
                            throw new IllegalArgumentException("velocitySpread must be between 0.0 and 1.0");
                        step.setVelocitySpread(value);
                        break;
                    case "mute":
                        step.setIsMuted(value != 0.0);
                        break;
                    default:
                        throw new IllegalArgumentException(
                            "unknown property '" + property + "'. Valid: pan, timbre, pressure, gain, transpose, releaseVelocity, velocitySpread, mute");
                }
                count++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", count);
            return result;
        });

        dispatcher.register("arrangerClip/setNoteRepeat", params -> {
            JsonArray notes = requireArray(params, "notes");
            int count = 0;
            for (JsonElement el : notes) {
                JsonObject note = el.getAsJsonObject();
                int x = note.get("x").getAsInt();
                int y = note.get("y").getAsInt();
                requireGridCoordinates(x, y);
                int repeatCount = note.get("count").getAsInt();
                double curve = note.get("curve").getAsDouble();
                double velocityEnd = note.get("velocityEnd").getAsDouble();
                double velocityCurve = note.get("velocityCurve").getAsDouble();
                if (repeatCount < -127 || repeatCount > 127)
                    throw new IllegalArgumentException("count must be between -127 and 127");
                if (curve < -1.0 || curve > 1.0)
                    throw new IllegalArgumentException("curve must be between -1.0 and 1.0");
                if (velocityEnd < -1.0 || velocityEnd > 1.0)
                    throw new IllegalArgumentException("velocityEnd must be between -1.0 and 1.0");
                if (velocityCurve < -1.0 || velocityCurve > 1.0)
                    throw new IllegalArgumentException("velocityCurve must be between -1.0 and 1.0");
                NoteStep step = arrangerClip.getStep(0, x, y);
                step.setRepeatCount(repeatCount);
                step.setRepeatCurve(curve);
                step.setRepeatVelocityEnd(velocityEnd);
                step.setRepeatVelocityCurve(velocityCurve);
                step.setIsRepeatEnabled(true);
                count++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", count);
            return result;
        });

        dispatcher.register("arrangerClip/setNoteOccurrence", params -> {
            JsonArray notes = requireArray(params, "notes");
            int count = 0;
            for (JsonElement el : notes) {
                JsonObject note = el.getAsJsonObject();
                int x = note.get("x").getAsInt();
                int y = note.get("y").getAsInt();
                requireGridCoordinates(x, y);
                String condition = note.get("condition").getAsString().toUpperCase();
                NoteOccurrence occurrence;
                try {
                    occurrence = NoteOccurrence.valueOf(condition);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                        "unknown occurrence '" + condition + "'. Valid: ALWAYS, FIRST, NOT_FIRST, PREV, NOT_PREV, PREV_CHANNEL, NOT_PREV_CHANNEL, PREV_KEY, NOT_PREV_KEY, FILL, NOT_FILL");
                }
                NoteStep step = arrangerClip.getStep(0, x, y);
                step.setOccurrence(occurrence);
                step.setIsOccurrenceEnabled(true);
                count++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", count);
            return result;
        });

        dispatcher.register("arrangerClip/setNoteRecurrence", params -> {
            JsonArray notes = requireArray(params, "notes");
            int count = 0;
            for (JsonElement el : notes) {
                JsonObject note = el.getAsJsonObject();
                int x = note.get("x").getAsInt();
                int y = note.get("y").getAsInt();
                requireGridCoordinates(x, y);
                int length = note.get("length").getAsInt();
                int mask = note.get("mask").getAsInt();
                if (length < 1 || length > 8)
                    throw new IllegalArgumentException("length must be between 1 and 8");
                NoteStep step = arrangerClip.getStep(0, x, y);
                step.setRecurrence(length, mask);
                step.setIsRecurrenceEnabled(true);
                count++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", count);
            return result;
        });

        dispatcher.register("arrangerClip/setStepSize", params -> {
            double size = requireDouble(params, "size");
            arrangerClip.setStepSize(size);
            // The API publishes two setStepSize setters and no getter, so the cache holds the
            // last value written here rather than observing one.
            stateCache.setArrangerClipStepSize(size);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/scrollSteps", params -> {
            // `offset` is an ABSOLUTE first-step position despite the method's name — it goes
            // straight to Clip.scrollToStep(int), matching the launcher's clip/scrollSteps.
            int offset = requireInt(params, "offset");
            arrangerClip.scrollToStep(offset);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/scrollToKey", params -> {
            int key = requireInt(params, "key");
            if (key < 0 || key > 127) {
                throw new IllegalArgumentException("key must be 0-127");
            }
            arrangerClip.scrollToKey(key);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/transpose", params -> {
            // No range check, deliberately, and this is NOT an oversight. The launcher's
            // clip/transpose passes the int straight through with no bound of its own, and the
            // product-side guard lives above both surfaces. Inventing a second, different bound
            // here would make the two surfaces silently disagree.
            int semitones = requireInt(params, "semitones");
            arrangerClip.transpose(semitones);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/scrollKeysPageUp", params -> {
            arrangerClip.scrollKeysPageUp();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/scrollKeysPageDown", params -> {
            arrangerClip.scrollKeysPageDown();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/quantize", params -> {
            double amount = requireDouble(params, "amount");
            if (amount < 0.0 || amount > 1.0) {
                throw new IllegalArgumentException("quantize amount must be between 0.0 and 1.0");
            }
            arrangerClip.quantize(amount);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/rename", params -> {
            // Ports directly from ClipHandler's clip/rename: that one already calls
            // cursorClip.setName rather than resolving a launcher slot, so the only thing that
            // changes is which Clip receives it.
            JsonElement nameEl = params.get("name");
            if (nameEl == null) {
                throw new IllegalArgumentException("missing 'name' parameter");
            }
            arrangerClip.setName(nameEl.getAsString());
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/setColor", params -> {
            // NEW CODE, NOT A PORT, and the divergence is deliberate. The launcher's
            // clip/setColor resolves a ClipLauncherSlot from (trackIndex, slotIndex) and sets
            // the SLOT's colour; an arranger clip has no slot, so there is nothing to resolve
            // and nothing to port. This sets the CLIP's own colour through Clip.color()
            // instead, which is a different Bitwig object with different parameters -- hence no
            // trackIndex/slotIndex here. A reader comparing the two files should read this as
            // the launcher's version being unavailable on this surface, not as gratuitous drift.
            float r = (float) requireDouble(params, "r");
            float g = (float) requireDouble(params, "g");
            float b = (float) requireDouble(params, "b");
            requireColorComponent(r, "r");
            requireColorComponent(g, "g");
            requireColorComponent(b, "b");
            arrangerClip.color().set(r, g, b);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("arrangerClip/setPlaybackSettings", params -> {
            // ONE REGISTRATION FOLDING FIVE SETTERS. The launcher publishes these as five
            // separate methods (clip/setLoopStart, setLoopLength, setLoopEnabled, setPlayStart,
            // setPlayStop). They are folded here because they are almost always set together
            // and because five near-identical registrations is five chances to publish one that
            // is inert. A reader counting this file's registrations against ClipHandler's
            // should read this as five capabilities, not as four missing methods.
            //
            // Every field is optional and applied only when present. The loop region
            // (loopStart/loopLength) and the play range (playStart/playStop) are DIFFERENT
            // ranges on the same clip -- finding F3 observed 9.17 against 16.0 on an ordinary
            // 4-bar clip -- so they are named separately here and must not be conflated.
            JsonArray applied = new JsonArray();

            if (hasValue(params, "loopStart")) {
                arrangerClip.getLoopStart().set(requireDouble(params, "loopStart"));
                applied.add("loopStart");
            }
            if (hasValue(params, "loopLength")) {
                arrangerClip.getLoopLength().set(requireDouble(params, "loopLength"));
                applied.add("loopLength");
            }
            if (hasValue(params, "loopEnabled")) {
                arrangerClip.isLoopEnabled().set(requireBoolean(params, "loopEnabled"));
                applied.add("loopEnabled");
            }
            if (hasValue(params, "playStart")) {
                arrangerClip.getPlayStart().set(requireDouble(params, "playStart"));
                applied.add("playStart");
            }
            if (hasValue(params, "playStop")) {
                arrangerClip.getPlayStop().set(requireDouble(params, "playStop"));
                applied.add("playStop");
            }

            if (applied.isEmpty()) {
                throw new IllegalArgumentException(
                    "no playback setting given — supply at least one of: "
                    + "loopStart, loopLength, loopEnabled, playStart, playStop");
            }

            // `applied` names what was DISPATCHED, never what Bitwig now holds. It exists so a
            // caller who misspells one of the five sees which ones actually reached a setter
            // instead of reading a bare "ok" over a silently ignored key. Verify by reading
            // arrangerClip/getState back.
            JsonObject result = new JsonObject();
            result.add("applied", applied);
            return result;
        });
    }

    /**
     * Refuses a grid coordinate outside the window the arranger cursor clip was created with,
     * naming the bound it broke.
     *
     * <p>This is STRICTER than the launcher twin, deliberately and as a correction rather than a
     * divergence: {@code clip/clearNote}, {@code clip/setChance} and the other NoteStep setters
     * bound-check nothing, so an out-of-range coordinate reaches
     * {@code Clip.getStep}/{@code Clip.clearStep} unchecked. Threat T-06-07 requires every
     * coordinate entering this namespace to be refused by name, and one shared check is harder to
     * forget than eight copies of it.
     */
    private static void requireGridCoordinates(int x, int y) {
        if (x < 0 || x >= GRID_WIDTH) {
            throw new IllegalArgumentException(
                "note x=" + x + " is out of range (0-" + (GRID_WIDTH - 1)
                + "). Grid covers " + GRID_WIDTH + " steps at current stepSize.");
        }
        if (y < 0 || y >= GRID_HEIGHT) {
            throw new IllegalArgumentException(
                "note y=" + y + " is out of range (0-" + (GRID_HEIGHT - 1) + ").");
        }
    }

    /**
     * True when the key is present AND carries a value. An explicit {@code null} counts as
     * absent rather than as an unparseable number: {@code {"loopStart": null}} is a caller
     * saying "leave this alone", and answering it with an internal error would be a worse
     * report than the one it deserves.
     */
    private static boolean hasValue(JsonObject params, String key) {
        JsonElement el = params.get(key);
        return el != null && !el.isJsonNull();
    }

    private static void requireColorComponent(float value, String name) {
        if (value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0");
        }
    }

}
