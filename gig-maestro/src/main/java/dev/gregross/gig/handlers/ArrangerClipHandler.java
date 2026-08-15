package dev.gregross.gig.handlers;

import com.bitwig.extension.controller.api.Clip;
import com.bitwig.extension.controller.api.NoteStep;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.gregross.gig.extension.GigMaestroExtension;
import dev.gregross.gig.extension.StateCache;
import dev.gregross.gig.rpc.JsonRpcDispatcher;

import static dev.gregross.gig.rpc.JsonParamValidator.*;

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
 * <p>Every mutator here answers {@code "ok"}. That means the call was accepted for dispatch and
 * nothing more: Bitwig's Controller API returns void for these and updates state asynchronously
 * through observers, so {@code "ok"} is never evidence that a clip changed. Callers verify by
 * reading back.
 */
public class ArrangerClipHandler {

    // The grid the arranger cursor clip was actually created with, taken from the one place that
    // creates it rather than re-declared here. A third declaration of 256/128 would be a new
    // cross-declaration constant needing its own consistency test; referencing the source of the
    // value the factory was called with is both smaller and harder to get out of step.
    private static final int GRID_WIDTH = GigMaestroExtension.CLIP_GRID_WIDTH;
    private static final int GRID_HEIGHT = GigMaestroExtension.CLIP_GRID_HEIGHT;

    private final Clip arrangerClip;
    private final StateCache stateCache;

    public ArrangerClipHandler(Clip arrangerClip, StateCache stateCache) {
        this.arrangerClip = arrangerClip;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {

        dispatcher.register("arrangerClip/getState", params -> stateCache.getArrangerClipState());

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
    }

}
