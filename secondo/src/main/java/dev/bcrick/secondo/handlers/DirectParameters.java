package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.BooleanValue;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.Device;
import com.bitwig.extension.controller.api.DirectParameterValueDisplayObserver;
import com.bitwig.extension.controller.api.StringValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The panel (direct) parameters of ONE cursor device: every parameter the device exposes by
 * string id, read from init-time observer caches and written through the normalized direct
 * setter (Phase 27, F-17-04, VST3-PARAMS, D-27-12).
 *
 * <p>WHY THE DIRECT MEMBERS. {@code Device} carries the id, name, display and normalized value
 * observers and the normalized setter directly, so they work on the existing cursor devices for
 * whatever device the cursor lands on. The Specific device factories (the Bitwig-device one
 * keyed by UUID and the VST3 one keyed by plug-in id) are deliberately NOT used (D-27-17): they
 * are initialization-only proxy factories and need the identity of the device at init, and a
 * user's chain can hold any of hundreds of devices.
 *
 * <p>STALE IDS (research Pitfall 3, T-27-23). When the cursor moves to another device, the id
 * observer fires with the new id set. Its callback replaces the set, bumps {@link #generation()},
 * prunes every cached name, display and value whose id vanished, and re-subscribes the display
 * observer to the new ids (by default it observes none). A write refuses an id outside the
 * current set before calling the setter, and every read and write answers the generation so the
 * tool can tell a device change between write and read-back.
 *
 * <p>INITIALIZATION ONLY. {@link #attach} registers observers, which Bitwig permits only during
 * driver initialization (D-25-20); it is called from {@code SecondoExtension.init()} only.
 */
public final class DirectParameters {

    /**
     * The resolution handed to the normalized setter, whose javadoc reads the value as "normalized
     * to the range [0..resolution-1]" (bitwig-api-reference.txt :11635-11645). A value v in 0..1
     * is sent as round(v x (PANEL_WRITE_RESOLUTION - 1)). Pinned against the Python side by 27-06;
     * confirmed live on a continuous knob at stage 2 (research Pitfall 4).
     */
    public static final int PANEL_WRITE_RESOLUTION = 65536;

    /** Name observer character budget [ASSUMED choice, research § Code Examples]. */
    public static final int MAX_NAME_CHARS = 256;

    /** Display observer character budget [ASSUMED choice, research § Code Examples]. */
    public static final int MAX_DISPLAY_CHARS = 64;

    // Duplicated minimally from DeviceHandler's Observed holder (the WR-09 markInterested-first
    // block): null means unobserved (D-25-15).
    private static final class Observed<T> {
        volatile T value;
    }

    private static Observed<Boolean> observe(BooleanValue value) {
        Observed<Boolean> observed = new Observed<>();
        value.markInterested();
        value.addValueObserver(v -> observed.value = v);
        return observed;
    }

    private static Observed<String> observe(StringValue value) {
        Observed<String> observed = new Observed<>();
        value.markInterested();
        value.addValueObserver(v -> observed.value = v);
        return observed;
    }

    private final Device device;
    private final Observed<Boolean> exists;
    private final Observed<String> name;
    private final Observed<Boolean> plugin;
    // ConcurrentHashMap holds no null values, so an absent entry IS the unobserved state.
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Map<String, String> displays = new ConcurrentHashMap<>();
    private final Map<String, Double> normalized = new ConcurrentHashMap<>();
    private final DirectParameterValueDisplayObserver displayObserver;
    /** Null until the id observer's first callback: "ids unobserved" is not "no ids". */
    private volatile String[] ids = null;
    private volatile int generation = 0;

    /** Register every direct-parameter observer on {@code device}. Init only (D-25-20). */
    public static DirectParameters attach(CursorDevice device) {
        return new DirectParameters(device);
    }

    /** Test seam and the body of {@link #attach}. */
    DirectParameters(Device device) {
        this.device = device;
        this.exists = observe(device.exists());
        this.name = observe(device.name());
        this.plugin = observe(device.isPlugin());
        // THE ID OBSERVER GOES FIRST, AND THIS IS NOT A STYLE CHOICE. Bitwig's own
        // DeviceProxy guards addDirectParameterNameObserver,
        // addDirectParameterValueDisplayObserver and addDirectParameterNormalizedValueObserver
        // on mDirectParameterIds.isInterested() and, when that is false, throws
        // "You need to either add an observer to the direct parameter ids or mark them as
        // interested first". addDirectParameterIdObserver is what sets that interest (it calls
        // ComputedStringArrayValue.addValueObserver). Registering any of the other three first
        // therefore throws out of init(), and Bitwig answers with the "Secondo did something
        // wrong" dialog and refuses to enable the extension — measured live 2026-09-17 and read
        // straight out of bitwig.jar's DeviceProxy bytecode (Bitwig Studio 6, API v25).
        // The javadoc in bitwig-api-reference.txt states none of this; the bytecode is the
        // source. DirectParametersTest reproduces the guard.
        device.addDirectParameterIdObserver(this::replaceIds);
        this.displayObserver = device.addDirectParameterValueDisplayObserver(MAX_DISPLAY_CHARS,
            (id, value) -> put(displays, id, value));
        device.addDirectParameterNameObserver(MAX_NAME_CHARS,
            (id, value) -> put(names, id, value));
        device.addDirectParameterNormalizedValueObserver((id, value) -> {
            if (id == null) return;
            // "If the value is not accessible 'Number.NaN' (not-a-number) is reported": NaN is
            // stored as unobserved, and published as null.
            if (Double.isNaN(value)) normalized.remove(id);
            else normalized.put(id, value);
        });
        // The cost of going second: an id set delivered while displayObserver was still null was
        // skipped by replaceIds' null guard, and by default the display observer observes no
        // parameter at all. Subscribe whatever the id observer already published. A no-op in the
        // ordinary case, where the first callback arrives on a later flush.
        subscribeDisplaysToCurrentIds();
    }

    /** Point the display observer at the current id set, if one has already been observed. */
    private synchronized void subscribeDisplaysToCurrentIds() {
        String[] current = ids;
        if (current != null && displayObserver != null) {
            displayObserver.setObservedParameterIds(current.clone());
        }
    }

    private static void put(Map<String, String> cache, String id, String value) {
        if (id == null) return;
        if (value == null) cache.remove(id);
        else cache.put(id, value);
    }

    /** The id observer's callback: replace, bump, prune, re-subscribe. */
    void replaceIds(String[] observedIds) {
        String[] next = observedIds == null ? new String[0] : observedIds.clone();
        Set<String> keep = new HashSet<>(Arrays.asList(next));
        synchronized (this) {
            ids = next;
            generation++;
            names.keySet().retainAll(keep);
            displays.keySet().retainAll(keep);
            normalized.keySet().retainAll(keep);
        }
        if (displayObserver != null) {
            displayObserver.setObservedParameterIds(next.clone());
        }
    }

    public int generation() {
        return generation;
    }

    private static JsonObject warning(String code, String message) {
        JsonObject warning = new JsonObject();
        warning.addProperty("code", code);
        warning.addProperty("message", message);
        return warning;
    }

    /**
     * {@code device/getPanelParameters} and its master twin (plan 27-03's contract): a pure cache
     * read in id-set order. Never moves any cursor.
     */
    public synchronized JsonObject read() {
        JsonObject result = new JsonObject();
        result.addProperty("deviceExists", exists.value);
        result.addProperty("deviceName", name.value);
        result.addProperty("isPlugin", plugin.value);
        result.addProperty("generation", generation);
        JsonArray parameters = new JsonArray();
        JsonArray warnings = new JsonArray();
        String[] current = ids;
        if (current == null) {
            result.addProperty("idsObserved", false);
            result.addProperty("parameterCount", (Number) null);
            warnings.add(warning("PANEL_IDS_UNOBSERVED",
                "The direct-parameter id set is not observed yet."));
        } else if (current.length == 0) {
            result.addProperty("idsObserved", true);
            result.addProperty("parameterCount", 0);
            warnings.add(warning("PANEL_NO_DIRECT_PARAMETERS",
                "Bitwig published an empty direct-parameter id set."));
        } else {
            result.addProperty("idsObserved", true);
            result.addProperty("parameterCount", current.length);
            int namesCold = 0;
            int displaysCold = 0;
            int valuesCold = 0;
            for (String id : current) {
                JsonObject parameter = new JsonObject();
                parameter.addProperty("id", id);
                String parameterName = id == null ? null : names.get(id);
                String display = id == null ? null : displays.get(id);
                Double value = id == null ? null : normalized.get(id);
                parameter.addProperty("name", parameterName);
                parameter.addProperty("displayValue", display);
                parameter.addProperty("normalizedValue", value);
                if (parameterName == null) namesCold++;
                if (display == null) displaysCold++;
                if (value == null) valuesCold++;
                parameters.add(parameter);
            }
            addFieldWarning(warnings, "name", namesCold);
            addFieldWarning(warnings, "displayValue", displaysCold);
            addFieldWarning(warnings, "normalizedValue", valuesCold);
        }
        result.add("parameters", parameters);
        result.add("warnings", warnings);
        return result;
    }

    private static void addFieldWarning(JsonArray warnings, String field, int count) {
        if (count == 0) return;
        JsonObject warning = warning("PANEL_FIELD_UNOBSERVED",
            "Panel field '" + field + "' is not observed yet for " + count + " id(s).");
        warning.addProperty("field", field);
        warning.addProperty("count", count);
        warnings.add(warning);
    }

    /** The {@code id} param as the contract reads it: a non-empty JSON string. */
    public static String panelId(JsonObject params) {
        JsonElement element = params.get("id");
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isString() || element.getAsString().isEmpty()) {
            throw new IllegalArgumentException("panel parameter id must be a non-empty string");
        }
        return element.getAsString();
    }

    /**
     * The {@code value} param as the contract reads it: a JSON number (never a boolean).
     *
     * <p>This is a TYPE fault and says so (IN-04). It used to report the range message below,
     * which told a caller who had sent {@code "0.5"} or {@code true} that their number was out
     * of 0.0-1.0 -- a false report, because no number was sent at all. The range message belongs
     * to {@link #write}'s real bound check and stays there; the Python layer quotes whichever
     * one it receives verbatim into the refusal the user hears, so the two must be different.
     */
    public static double panelValue(JsonObject params) {
        JsonElement element = params.get("value");
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                "panel parameter value must be a number, got " + element);
        }
        return element.getAsDouble();
    }

    /**
     * {@code device/setPanelParameter} and its master twin. Checks in the contract's order: the
     * id, the value range, an unobserved id set ({@code PANEL_IDS_UNOBSERVED}, -32603), an id
     * outside the CURRENT set ({@code PANEL_PARAMETER_ID_UNKNOWN: id}, -32602). Only then the
     * setter. An ok is never proof: the tool verifies by the panel read.
     */
    public synchronized JsonObject write(String id, double value) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("panel parameter id must be a non-empty string");
        }
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(
                "panel parameter value out of range: 0.0-1.0, got " + value);
        }
        String[] current = ids;
        if (current == null) {
            throw new IllegalStateException("PANEL_IDS_UNOBSERVED");
        }
        if (!Arrays.asList(current).contains(id)) {
            // T-27-23: a stale id from the previous device is refused before the setter runs.
            throw new IllegalArgumentException("PANEL_PARAMETER_ID_UNKNOWN: " + id);
        }
        long scaled = Math.round(value * (PANEL_WRITE_RESOLUTION - 1));
        device.setDirectParameterValueNormalized(id, scaled, PANEL_WRITE_RESOLUTION);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("generation", generation);
        return result;
    }
}
