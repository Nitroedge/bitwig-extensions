package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.BooleanValue;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.Device;
import com.bitwig.extension.controller.api.IntegerValue;
import com.bitwig.extension.controller.api.RangedValue;
import com.bitwig.extension.controller.api.RemoteControl;
import com.bitwig.extension.controller.api.StringArrayValue;
import com.bitwig.extension.controller.api.StringValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.bcrick.secondo.rpc.TaskScheduler;

import static dev.bcrick.secondo.rpc.JsonParamValidator.requireArray;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Eight remote-control page cursors parked one per page index on ONE cursor device (Phase 27,
 * D-27-01, D-27-02), read as a single synchronous cache read and written synchronously on each
 * page's own cursor.
 *
 * <p>WHY PARKED CURSORS. The one-argument {@code createCursorRemoteControlsPage(int)} follows the
 * page the user selects, so reading page 3 meant moving the user's page and waiting a flush per
 * page ({@code device/discoverAll}). Device's three-argument overload instead creates a section
 * that, in its javadoc's words, "will be independent from the current page selected by the user
 * in Bitwig Studio's user interface" (bitwig-api-reference.txt :10975-10998, {@code @since API
 * version 2} on {@code Device}). Note the findings register's T3 row inverts the Track and Device
 * citations: :3906/:3930 are Track's pair ({@code @since 18}) and :10969/:10993 are Device's
 * (research 27-RESEARCH.md § Verified API Surface). Both are inside v25; the verdict is unchanged.
 *
 * <p>THE PARKING GATE (D-27-04, research Pitfall 1 / G3). A parked cursor is one whose observed
 * {@code selectedPageIndex} equals its own index. A cursor that has not settled there publishes
 * {@code parameters: null} and a {@code REMOTE_PAGE_NOT_PARKED} warning, never another page's
 * values under this page's label, and a zero-delay re-park is scheduled through the
 * {@link TaskScheduler}. Nothing here sleeps or waits (D-27-07); the caller polls.
 *
 * <p>INITIALIZATION ONLY. {@link #create} calls a proxy factory, and Bitwig permits proxy
 * factories and observer registration only during driver initialization (D-25-20). It is called
 * from {@code SecondoExtension.init()} and nowhere else; no handler lambda calls it.
 *
 * <p>The one-argument cursor stays created at init for {@code session/snapshot}'s user-selected
 * page and the still-registered {@code device/discoverAll} pair (D-27-05).
 */
public final class ParkedRemoteControls {

    /** Parked page cursors per cursor device (D-27-02). Pinned against the Python side by 27-06. */
    public static final int PARKED_PAGE_COUNT = 8;

    /** Remote controls per page, the same eight {@code DeviceHandler.PARAM_COUNT} names. */
    static final int CONTROLS_PER_PAGE = 8;

    /** An empty filter expression: "If the expression is empty then no filtering will occur." */
    static final String EMPTY_FILTER = "";

    // Duplicated minimally from DeviceHandler's Observed holder and observe overloads
    // (DeviceHandler.java, the WR-09 markInterested-first block): null means unobserved
    // (D-25-15). Duplicated rather than lifted because lifting it would add lines above
    // DeviceHandler.PARAM_COUNT, whose declaration line src/secondo/models.py cites by number.
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

    private static Observed<String[]> observe(StringArrayValue value) {
        Observed<String[]> observed = new Observed<>();
        value.markInterested();
        value.addValueObserver(v -> observed.value = v == null ? null : v.clone());
        return observed;
    }

    private static Observed<Double> observe(RangedValue value) {
        Observed<Double> observed = new Observed<>();
        value.markInterested();
        value.addValueObserver(v -> observed.value = v);
        return observed;
    }

    /**
     * The engine's scheduler delay, and the width of this class's re-park window (WR-09).
     *
     * <p>THIS IS THE FOURTH DECLARATION, and the one {@code FlushDelayConsistencyTest}'s class
     * comment names as the thing it is most afraid of: a handler declaring its own copy under a
     * private name would be scheduled by a number no test reads. It carries the same name, the
     * same value and the same {@code private static final long} form as the declarations in
     * {@code MacroHandler}, {@code DeviceHandler} and {@code MasterDeviceHandler} precisely so
     * that test's source-text half finds it, and that test now names this file. A differently
     * named private literal here would have slipped past it. Change all four or none.
     *
     * <p>Declared here, below the observe overloads rather than beside {@link #PARKED_PAGE_COUNT},
     * only because secondo's {@code src/secondo/verify.py} cites this file's {@code :71-93} by
     * line for the markInterested-first registration, and moving those lines would silently
     * falsify a citation this plan may not edit.
     */
    private static final long FLUSH_DELAY_MS = 100;

    /**
     * The re-park window's clock, in monotonic milliseconds. A field rather than a direct call so
     * a test can drive the window without sleeping; production never replaces it.
     *
     * <p>MONOTONIC, NEVER WALL-CLOCK. {@code System.currentTimeMillis()} can step backwards or
     * forwards when the machine syncs its time mid-session, which would either reopen the window
     * on every read (the unbounded scheduling this bound exists to stop) or hold it shut for
     * hours (the latching this bound exists to avoid).
     */
    private LongSupplier monotonicMs = () -> System.nanoTime() / 1_000_000L;

    /** Test seam: drive the re-park window's clock. Package-private; no handler calls it. */
    void setMonotonicMs(LongSupplier clock) {
        this.monotonicMs = clock;
    }

    /** One parked cursor's observed state. */
    private final class ParkedPage {
        final int index;
        final CursorRemoteControlsPage cursor;
        final Observed<Integer> selected = new Observed<>();
        final Observed<Integer> count = new Observed<>();
        final Observed<String[]> names;
        final Observed<String>[] controlNames;
        final Observed<Double>[] values;
        final Observed<String>[] displays;
        /**
         * The monotonic millisecond at which a re-park was last scheduled for THIS page, or null
         * until one has been. Per page, because one cursor drifting is not a reason to stop
         * re-parking the other seven. Volatile because an observer callback and a handler read
         * can reach {@link #scheduleRepark} from different threads; the worst a lost update can
         * do here is schedule one extra idempotent task, which is what the old code did on every
         * read.
         */
        volatile Long lastScheduledMs;

        @SuppressWarnings({"unchecked", "rawtypes"})
        ParkedPage(int index, CursorRemoteControlsPage cursor) {
            this.index = index;
            this.cursor = cursor;
            // selectedPageIndex and pageCount carry the parking trigger, so they are observed
            // here rather than through observe(IntegerValue): each observer also re-parks.
            IntegerValue selectedPageIndex = cursor.selectedPageIndex();
            selectedPageIndex.markInterested();
            selectedPageIndex.addValueObserver(v -> {
                selected.value = v;
                reparkIfDrifted();
            });
            IntegerValue pageCount = cursor.pageCount();
            pageCount.markInterested();
            pageCount.addValueObserver(v -> {
                count.value = v;
                reparkIfDrifted();
            });
            names = observe(cursor.pageNames());
            controlNames = new Observed[CONTROLS_PER_PAGE];
            values = new Observed[CONTROLS_PER_PAGE];
            displays = new Observed[CONTROLS_PER_PAGE];
            for (int control = 0; control < CONTROLS_PER_PAGE; control++) {
                RemoteControl remote = cursor.getParameter(control);
                controlNames[control] = observe(remote.name());
                values[control] = observe(remote.value());
                displays[control] = observe(remote.value().displayedValue());
            }
        }

        boolean parked() {
            Integer observed = selected.value;
            return observed != null && observed == index;
        }

        /** Observer-side trigger: this cursor's own page count reaches it and it is off-page. */
        void reparkIfDrifted() {
            Integer pages = count.value;
            if (pages != null && pages > index && !parked()) {
                scheduleRepark();
            }
        }

        /**
         * A zero-delay task setting this cursor's page to its own index, at most one per
         * {@link #FLUSH_DELAY_MS} window per page. Scheduled, never called inline from an
         * observer and never waited on (D-27-07).
         *
         * <p>Deliberately NOT de-duplicated with a pending flag: the set is idempotent, and a
         * flag cleared only by the task itself latches the gate shut for the session if one
         * scheduled task never runs (the WR-06 lesson from device/listChain). That reasoning is
         * unchanged and is why the bound below is a MONOTONIC LAST-SCHEDULED MILLISECOND rather
         * than a flag: it cannot latch, because it expires on its own whether or not the task it
         * gated ever ran.
         *
         * <p>WHAT IT FIXES (WR-09). Every read of an unparked page scheduled another task, and
         * {@code readPages} is exactly what a caller polls while waiting for a cursor to settle.
         * A cursor that cannot park -- a device with fewer pages than it claims, a page Bitwig
         * refuses to select -- therefore enqueued one re-park per poll, without bound, on the
         * control-surface thread every other operation shares. The window caps that at one task
         * per flush per page, which is the most that can usefully be in flight anyway: the
         * scheduled set takes a flush to be observed.
         */
        void scheduleRepark() {
            long now = monotonicMs.getAsLong();
            Long last = lastScheduledMs;
            if (last != null && now - last < FLUSH_DELAY_MS) {
                return;
            }
            lastScheduledMs = now;
            scheduler.schedule(() -> cursor.selectedPageIndex().set(index), 0);
        }
    }

    private final TaskScheduler scheduler;
    private final Observed<Boolean> deviceExists;
    private final Observed<String> deviceName;
    private final ParkedPage[] pages;

    /**
     * Create the eight parked cursors on {@code device} and register every observer. Called only
     * from {@code SecondoExtension.init()} (D-25-20).
     *
     * @param cursorNamePrefix {@code secondo-track-page-} or {@code secondo-master-page-}; the
     *     index is appended, and Bitwig uses the name to remember manual mappings per section
     */
    public static ParkedRemoteControls create(CursorDevice device, String cursorNamePrefix,
                                              TaskScheduler scheduler) {
        CursorRemoteControlsPage[] cursors = new CursorRemoteControlsPage[PARKED_PAGE_COUNT];
        for (int page = 0; page < PARKED_PAGE_COUNT; page++) {
            cursors[page] = device.createCursorRemoteControlsPage(
                cursorNamePrefix + page, CONTROLS_PER_PAGE, EMPTY_FILTER);
        }
        return new ParkedRemoteControls(device, cursors, scheduler);
    }

    /** Test seam: the cursors are supplied rather than created. */
    ParkedRemoteControls(Device device, CursorRemoteControlsPage[] cursors,
                         TaskScheduler scheduler) {
        if (cursors.length != PARKED_PAGE_COUNT) {
            throw new IllegalArgumentException(
                "expected " + PARKED_PAGE_COUNT + " parked cursors, got " + cursors.length);
        }
        this.scheduler = scheduler;
        this.deviceExists = observe(device.exists());
        this.deviceName = observe(device.name());
        this.pages = new ParkedPage[PARKED_PAGE_COUNT];
        for (int page = 0; page < PARKED_PAGE_COUNT; page++) {
            pages[page] = new ParkedPage(page, cursors[page]);
        }
    }

    /** The device's page count as the first cursor with an observed count reports it. */
    private Integer observedPageCount() {
        for (ParkedPage page : pages) {
            Integer count = page.count.value;
            if (count != null) return count;
        }
        return null;
    }

    private String[] observedPageNames() {
        for (ParkedPage page : pages) {
            String[] names = page.names.value;
            if (names != null) return names;
        }
        return null;
    }

    private static int reachable(Integer pageCount) {
        return pageCount == null ? 0 : Math.max(0, Math.min(pageCount, PARKED_PAGE_COUNT));
    }

    private static JsonArray intArray(List<Integer> values) {
        JsonArray array = new JsonArray();
        for (int value : values) array.add(value);
        return array;
    }

    private static void warning(JsonArray warnings, String code, String message,
                                List<Integer> pageIndexes, String field) {
        JsonObject warning = new JsonObject();
        warning.addProperty("code", code);
        warning.addProperty("message", message);
        if (pageIndexes != null) warning.add("pages", intArray(pageIndexes));
        if (field != null) warning.addProperty("field", field);
        warnings.add(warning);
    }

    /**
     * {@code device/getRemoteControlPages} and its master twin: one synchronous cache read in the
     * shape plan 27-01's wire contract fixes. Every unobserved value is JSON null (the dispatcher
     * serializes nulls, D-25-15). Side effect: a zero-delay re-park for each unparked reachable
     * page, at most one per {@link #FLUSH_DELAY_MS} window per page (WR-09 -- see
     * {@code ParkedPage#scheduleRepark}, which is where the bound lives, so that a caller polling
     * this read cannot enqueue one task per poll). The user-following cursor is never touched.
     */
    public JsonObject readPages() {
        JsonObject result = new JsonObject();
        JsonArray warnings = new JsonArray();
        result.addProperty("deviceExists", deviceExists.value);
        result.addProperty("deviceName", deviceName.value);
        Integer pageCount = observedPageCount();
        String[] pageNames = observedPageNames();
        result.addProperty("pageCount", pageCount);
        if (pageNames == null) {
            result.add("pageNames", JsonNull.INSTANCE);
        } else {
            JsonArray names = new JsonArray();
            for (String name : pageNames) names.add(name);
            result.add("pageNames", names);
        }
        result.addProperty("cursorCount", PARKED_PAGE_COUNT);

        JsonArray pageRows = new JsonArray();
        if (pageCount == null) {
            warning(warnings, "REMOTE_FIELD_UNOBSERVED",
                "Remote-control field 'pageCount' is not observed yet.", null, "pageCount");
            result.add("pages", pageRows);
            result.add("warnings", warnings);
            return result;
        }

        int reachable = reachable(pageCount);
        List<Integer> unparked = new ArrayList<>();
        List<Integer> reachableIndexes = new ArrayList<>();
        List<Integer> namesCold = new ArrayList<>();
        List<Integer> valuesCold = new ArrayList<>();
        List<Integer> displaysCold = new ArrayList<>();
        for (int index = 0; index < reachable; index++) {
            reachableIndexes.add(index);
            ParkedPage page = pages[index];
            JsonObject row = new JsonObject();
            row.addProperty("index", index);
            row.addProperty("name",
                pageNames != null && index < pageNames.length ? pageNames[index] : null);
            if (!page.parked()) {
                // G3: an unparked cursor may be showing page 0's controls. Publishing them under
                // this page's label is the failure this gate exists to prevent.
                row.addProperty("parked", false);
                row.add("parameters", JsonNull.INSTANCE);
                unparked.add(index);
                page.scheduleRepark();
                pageRows.add(row);
                continue;
            }
            row.addProperty("parked", true);
            JsonArray parameters = new JsonArray();
            boolean nameCold = false;
            boolean valueCold = false;
            boolean displayCold = false;
            for (int control = 0; control < CONTROLS_PER_PAGE; control++) {
                JsonObject parameter = new JsonObject();
                parameter.addProperty("index", control);
                String name = page.controlNames[control].value;
                Double value = page.values[control].value;
                String display = page.displays[control].value;
                parameter.addProperty("name", name);
                parameter.addProperty("value", value);
                parameter.addProperty("displayedValue", display);
                nameCold |= name == null;
                valueCold |= value == null;
                displayCold |= display == null;
                parameters.add(parameter);
            }
            if (nameCold) namesCold.add(index);
            if (valueCold) valuesCold.add(index);
            if (displayCold) displaysCold.add(index);
            row.add("parameters", parameters);
            pageRows.add(row);
        }

        if (!unparked.isEmpty()) {
            warning(warnings, "REMOTE_PAGE_NOT_PARKED",
                "Parked page cursors " + unparked + " had not settled on their own page; "
                    + "their parameters are null and a re-park was scheduled.",
                unparked, null);
        }
        if (pageCount > PARKED_PAGE_COUNT) {
            List<Integer> beyond = new ArrayList<>();
            for (int index = PARKED_PAGE_COUNT; index < pageCount; index++) beyond.add(index);
            warning(warnings, "REMOTE_PAGES_BEYOND_CURSORS",
                "Pages " + beyond + " exist but no parked cursor exists for them ("
                    + PARKED_PAGE_COUNT + " cursors per device).",
                beyond, null);
        }
        if (pageNames == null && !reachableIndexes.isEmpty()) {
            warning(warnings, "REMOTE_FIELD_UNOBSERVED",
                "Remote-control field 'pageNames' is not observed yet.",
                reachableIndexes, "pageNames");
        }
        if (!namesCold.isEmpty()) {
            warning(warnings, "REMOTE_FIELD_UNOBSERVED",
                "Remote-control field 'name' is not observed yet.", namesCold, "name");
        }
        if (!valuesCold.isEmpty()) {
            warning(warnings, "REMOTE_FIELD_UNOBSERVED",
                "Remote-control field 'value' is not observed yet.", valuesCold, "value");
        }
        if (!displaysCold.isEmpty()) {
            warning(warnings, "REMOTE_FIELD_UNOBSERVED",
                "Remote-control field 'displayedValue' is not observed yet.",
                displaysCold, "displayedValue");
        }
        result.add("pages", pageRows);
        result.add("warnings", warnings);
        return result;
    }

    /**
     * {@code device/setRemoteControlValues} and its master twin (plan 27-02's contract).
     *
     * <p>EVERY CHECK RUNS BEFORE ANY WRITE, in this order: the payload shape with exactly
     * {@code device/setParameters}' messages; every page's reach
     * ({@code REMOTE_PAGE_OUT_OF_REACH: page N}, an IllegalArgumentException, so -32602); every
     * page's parking gate ({@code REMOTE_PAGE_NOT_PARKED: page N}, an IllegalStateException, so
     * -32603, with a re-park scheduled for each unparked page). Only then is each control written
     * with {@code setImmediately} on its own page's cursor. Nothing is scheduled for the write and
     * nothing sleeps (D-27-06, D-27-07). An ok is never proof: the tool reads the pages back.
     *
     * <p>EVERY RAW ACCESSOR IN THE FIRST BLOCK IS GUARDED BEFORE IT READS (WR-10). A non-object
     * page element, a non-object param element, or a {@code pageIndex} / {@code index} /
     * {@code value} that is a string or a boolean used to throw out of Gson --
     * {@code IllegalStateException} or {@code NumberFormatException} -- and the dispatcher maps
     * those to {@code -32603 internal error}, which tells the caller the ENGINE misbehaved and
     * names no field. Each is now an {@code IllegalArgumentException} in
     * {@code DirectParameters.panelId}'s style, so the caller gets {@code -32602 invalid params}
     * and the name of the field that was wrong. The review marked this advisory; it is
     * load-bearing now, because the Python side discriminates on the code.
     *
     * <p>The three loops AFTER the first one re-read those same fields raw, deliberately and
     * safely: the first loop returns only when every element and every numeric field in the whole
     * payload has been checked, and nothing mutates the payload in between.
     */
    public JsonObject writeValues(JsonArray payload) {
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("pages array must not be empty");
        }
        int totalParams = 0;
        // The device/setParameters validation block, with its messages unchanged -- and, since
        // WR-10, every raw accessor in it guarded before it reads (see the method javadoc).
        for (JsonElement pageEl : payload) {
            if (!pageEl.isJsonObject()) {
                throw new IllegalArgumentException("each page must be an object, got " + pageEl);
            }
            JsonObject page = pageEl.getAsJsonObject();
            if (!page.has("pageIndex")) {
                throw new IllegalArgumentException("each page must have 'pageIndex'");
            }
            JsonElement pageIndexEl = page.get("pageIndex");
            if (!pageIndexEl.isJsonPrimitive() || !pageIndexEl.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(
                    "'pageIndex' must be a number, got " + pageIndexEl);
            }
            JsonArray pageParams = requireArray(page, "params");
            if (pageParams.isEmpty()) {
                throw new IllegalArgumentException("each page must have a non-empty 'params' array");
            }
            for (JsonElement paramEl : pageParams) {
                if (!paramEl.isJsonObject()) {
                    throw new IllegalArgumentException(
                        "each param must be an object, got " + paramEl);
                }
                JsonObject p = paramEl.getAsJsonObject();
                if (!p.has("index") || !p.has("value")) {
                    throw new IllegalArgumentException("each param must have 'index' and 'value'");
                }
                JsonElement indexEl = p.get("index");
                if (!indexEl.isJsonPrimitive() || !indexEl.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("'index' must be a number, got " + indexEl);
                }
                int idx = indexEl.getAsInt();
                if (idx < 0 || idx >= CONTROLS_PER_PAGE) {
                    throw new IllegalArgumentException("parameter index out of range: 0-7, got " + idx);
                }
                JsonElement valueEl = p.get("value");
                if (!valueEl.isJsonPrimitive() || !valueEl.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("'value' must be a number, got " + valueEl);
                }
                double val = valueEl.getAsDouble();
                if (val < 0.0 || val > 1.0) {
                    throw new IllegalArgumentException("parameter value out of range: 0.0-1.0, got " + val);
                }
                totalParams++;
            }
        }

        int reachable = reachable(observedPageCount());
        for (JsonElement pageEl : payload) {
            int pageIndex = pageEl.getAsJsonObject().get("pageIndex").getAsInt();
            if (pageIndex < 0 || pageIndex >= reachable) {
                throw new IllegalArgumentException("REMOTE_PAGE_OUT_OF_REACH: page " + pageIndex);
            }
        }

        Integer firstUnparked = null;
        for (JsonElement pageEl : payload) {
            int pageIndex = pageEl.getAsJsonObject().get("pageIndex").getAsInt();
            ParkedPage page = pages[pageIndex];
            if (!page.parked()) {
                page.scheduleRepark();
                if (firstUnparked == null) firstUnparked = pageIndex;
            }
        }
        if (firstUnparked != null) {
            // T-27-22: a drifted cursor would write another page's controls. Refuse the whole
            // call; nothing has been written.
            throw new IllegalStateException("REMOTE_PAGE_NOT_PARKED: page " + firstUnparked);
        }

        for (JsonElement pageEl : payload) {
            JsonObject page = pageEl.getAsJsonObject();
            CursorRemoteControlsPage cursor = pages[page.get("pageIndex").getAsInt()].cursor;
            for (JsonElement paramEl : page.getAsJsonArray("params")) {
                JsonObject p = paramEl.getAsJsonObject();
                cursor.getParameter(p.get("index").getAsInt())
                    .value().setImmediately(p.get("value").getAsDouble());
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("pageCount", payload.size());
        result.addProperty("paramCount", totalParams);
        return result;
    }
}
