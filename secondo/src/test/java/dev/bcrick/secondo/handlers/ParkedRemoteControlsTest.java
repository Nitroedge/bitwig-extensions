package dev.bcrick.secondo.handlers;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.callback.ObjectValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.BooleanValue;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.IntegerValue;
import com.bitwig.extension.controller.api.RemoteControl;
import com.bitwig.extension.controller.api.SettableIntegerValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.controller.api.SettableStringValue;
import com.bitwig.extension.controller.api.StringArrayValue;
import com.bitwig.extension.controller.api.StringValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bcrick.secondo.rpc.TaskScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * The parking gate, the parked read and the parked write (Phase 27, D-27-01, D-27-02, D-27-04,
 * D-27-06, D-27-07). Every observer callback is captured, so each test drives the exact observed
 * state it names rather than relying on Bitwig's initial callbacks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParkedRemoteControlsTest {

    @Mock private CursorDevice mockDevice;
    @Mock private BooleanValue mockExists;
    @Mock private StringValue mockDeviceName;

    private final List<Runnable> scheduledTasks = new ArrayList<>();
    private final List<Long> scheduledDelays = new ArrayList<>();
    /** Immediate, and recording: every task runs at once and its delay is kept for assertion. */
    private final TaskScheduler scheduler = (task, delayMs) -> {
        scheduledTasks.add(task);
        scheduledDelays.add(delayMs);
        task.run();
    };

    private BooleanValueChangedCallback existsCallback;
    private StringValueChangedCallback deviceNameCallback;
    private PageFixture[] fixtures;
    private ParkedRemoteControls parked;

    /**
     * The re-park window's clock (WR-09), driven by hand. Every test runs at t=0 until it says
     * otherwise, which is what makes "inside one window" and "after the window" assertable
     * without sleeping -- and what keeps the window itself out of the wall clock, where a test
     * that happened to straddle a 100 ms boundary would flake.
     */
    private long clockMs;

    /** The width of the window under test; {@code ParkedRemoteControls.FLUSH_DELAY_MS} is private. */
    private static final long REPARK_WINDOW_MS = 100;

    private void advancePastTheReparkWindow() {
        clockMs += REPARK_WINDOW_MS;
    }

    /** One mocked parked cursor with every observer callback captured. */
    static final class PageFixture {
        final CursorRemoteControlsPage cursor = mock(CursorRemoteControlsPage.class);
        final SettableIntegerValue selected = mock(SettableIntegerValue.class);
        final IntegerValue count = mock(IntegerValue.class);
        final StringArrayValue names = mock(StringArrayValue.class);
        final RemoteControl[] controls = new RemoteControl[8];
        final SettableStringValue[] controlNames = new SettableStringValue[8];
        final SettableRangedValue[] values = new SettableRangedValue[8];
        final StringValue[] displays = new StringValue[8];
        IntegerValueChangedCallback selectedCallback;
        IntegerValueChangedCallback countCallback;
        ObjectValueChangedCallback<String[]> namesCallback;
        final StringValueChangedCallback[] controlNameCallbacks = new StringValueChangedCallback[8];
        final DoubleValueChangedCallback[] valueCallbacks = new DoubleValueChangedCallback[8];
        final StringValueChangedCallback[] displayCallbacks = new StringValueChangedCallback[8];

        PageFixture() {
            when(cursor.selectedPageIndex()).thenReturn(selected);
            when(cursor.pageCount()).thenReturn(count);
            when(cursor.pageNames()).thenReturn(names);
            doAnswer(inv -> {
                selectedCallback = inv.getArgument(0);
                return null;
            }).when(selected).addValueObserver(any(IntegerValueChangedCallback.class));
            doAnswer(inv -> {
                countCallback = inv.getArgument(0);
                return null;
            }).when(count).addValueObserver(any(IntegerValueChangedCallback.class));
            doAnswer(inv -> {
                namesCallback = inv.getArgument(0);
                return null;
            }).when(names).addValueObserver(any());
            for (int i = 0; i < 8; i++) {
                final int control = i;
                controls[i] = mock(RemoteControl.class);
                controlNames[i] = mock(SettableStringValue.class);
                values[i] = mock(SettableRangedValue.class);
                displays[i] = mock(StringValue.class);
                when(cursor.getParameter(control)).thenReturn(controls[i]);
                when(controls[i].name()).thenReturn(controlNames[i]);
                when(controls[i].value()).thenReturn(values[i]);
                when(values[i].displayedValue()).thenReturn(displays[i]);
                doAnswer(inv -> {
                    controlNameCallbacks[control] = inv.getArgument(0);
                    return null;
                }).when(controlNames[i]).addValueObserver(any(StringValueChangedCallback.class));
                doAnswer(inv -> {
                    valueCallbacks[control] = inv.getArgument(0);
                    return null;
                }).when(values[i]).addValueObserver(any(DoubleValueChangedCallback.class));
                doAnswer(inv -> {
                    displayCallbacks[control] = inv.getArgument(0);
                    return null;
                }).when(displays[i]).addValueObserver(any(StringValueChangedCallback.class));
            }
        }

        /** Fire every control observer with values unique to {@code label}. */
        void observeControls(int label) {
            for (int i = 0; i < 8; i++) {
                controlNameCallbacks[i].valueChanged("P" + label + "C" + i);
                valueCallbacks[i].valueChanged((label * 10 + i) / 100.0);
                displayCallbacks[i].valueChanged("d" + label + "." + i);
            }
        }
    }

    @BeforeEach
    void setUp() {
        when(mockDevice.exists()).thenReturn(mockExists);
        when(mockDevice.name()).thenReturn(mockDeviceName);
        doAnswer(inv -> {
            existsCallback = inv.getArgument(0);
            return null;
        }).when(mockExists).addValueObserver(any(BooleanValueChangedCallback.class));
        doAnswer(inv -> {
            deviceNameCallback = inv.getArgument(0);
            return null;
        }).when(mockDeviceName).addValueObserver(any(StringValueChangedCallback.class));

        fixtures = new PageFixture[8];
        CursorRemoteControlsPage[] cursors = new CursorRemoteControlsPage[8];
        for (int p = 0; p < 8; p++) {
            fixtures[p] = new PageFixture();
            cursors[p] = fixtures[p].cursor;
        }
        parked = new ParkedRemoteControls(mockDevice, cursors, scheduler);
        clockMs = 0;
        parked.setMonotonicMs(() -> clockMs);
        existsCallback.valueChanged(true);
        deviceNameCallback.valueChanged("Polymer");
    }

    /** Every cursor observes the same page count, as every section of one device does. */
    private void observePageCount(int pageCount) {
        for (PageFixture fixture : fixtures) fixture.countCallback.valueChanged(pageCount);
    }

    private void observePageNames(String... names) {
        for (PageFixture fixture : fixtures) fixture.namesCallback.valueChanged(names);
    }

    /** Park every cursor on its own index and observe its controls. */
    private void parkAll() {
        for (int p = 0; p < 8; p++) {
            fixtures[p].selectedCallback.valueChanged(p);
            fixtures[p].observeControls(p);
        }
    }

    private static JsonObject page(JsonObject result, int index) {
        return result.getAsJsonArray("pages").get(index).getAsJsonObject();
    }

    private static JsonObject warning(JsonObject result, String code) {
        for (JsonElement element : result.getAsJsonArray("warnings")) {
            JsonObject warning = element.getAsJsonObject();
            if (code.equals(warning.get("code").getAsString())) return warning;
        }
        return null;
    }

    private static JsonArray payload(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    // --- create ---

    @Test
    void create_callsTheThreeArgumentOverloadEightTimesWithAnEmptyFilter() {
        CursorDevice device = mock(CursorDevice.class, RETURNS_DEEP_STUBS);
        ParkedRemoteControls created =
            ParkedRemoteControls.create(device, "secondo-track-page-", scheduler);
        assertNotNull(created);
        for (int p = 0; p < 8; p++) {
            verify(device).createCursorRemoteControlsPage("secondo-track-page-" + p, 8, "");
        }
        verify(device, never()).createCursorRemoteControlsPage(anyInt());
        assertEquals(8, ParkedRemoteControls.PARKED_PAGE_COUNT);
    }

    // --- readPages ---

    @Test
    void readPages_parkedPagePublishesItsOwnNamesValuesAndDisplays() {
        observePageCount(4);
        observePageNames("Main", "Filter", "Env", "FX");
        parkAll();

        JsonObject result = parked.readPages();

        assertTrue(result.get("deviceExists").getAsBoolean());
        assertEquals("Polymer", result.get("deviceName").getAsString());
        assertEquals(4, result.get("pageCount").getAsInt());
        assertEquals(8, result.get("cursorCount").getAsInt());
        assertEquals(4, result.getAsJsonArray("pages").size());
        JsonObject pageTwo = page(result, 2);
        assertEquals("Env", pageTwo.get("name").getAsString());
        assertTrue(pageTwo.get("parked").getAsBoolean());
        JsonArray parameters = pageTwo.getAsJsonArray("parameters");
        assertEquals(8, parameters.size());
        JsonObject control5 = parameters.get(5).getAsJsonObject();
        assertEquals(5, control5.get("index").getAsInt());
        assertEquals("P2C5", control5.get("name").getAsString());
        assertEquals(0.25, control5.get("value").getAsDouble(), 1e-9);
        assertEquals("d2.5", control5.get("displayedValue").getAsString());
        assertEquals(0, result.getAsJsonArray("warnings").size());
    }

    @Test
    void readPages_unparkedPagePublishesNullParametersAndSchedulesARepark() {
        observePageCount(4);
        observePageNames("Main", "Filter", "Env", "FX");
        parkAll();
        // Cursor 3 has drifted to page 0 and is showing page 0's controls (G3).
        fixtures[3].selectedCallback.valueChanged(0);
        fixtures[3].observeControls(0);
        // The page-count callback in setup already scheduled a re-park for every then-unparked
        // page, so the read below is inside that page's window unless the clock moves (WR-09).
        // This test is about WHAT an unparked page publishes, not about the window's width.
        advancePastTheReparkWindow();
        reset(fixtures[3].selected);
        scheduledTasks.clear();
        scheduledDelays.clear();

        JsonObject result = parked.readPages();

        JsonObject pageThree = page(result, 3);
        assertEquals("FX", pageThree.get("name").getAsString());
        assertFalse(pageThree.get("parked").getAsBoolean());
        assertTrue(pageThree.get("parameters").isJsonNull(),
            "an unparked page's parameters must be JSON null, never page 0's values");
        JsonObject notParked = warning(result, "REMOTE_PAGE_NOT_PARKED");
        assertNotNull(notParked);
        assertEquals(1, notParked.getAsJsonArray("pages").size());
        assertEquals(3, notParked.getAsJsonArray("pages").get(0).getAsInt());
        assertFalse(scheduledTasks.isEmpty());
        assertEquals(0L, scheduledDelays.get(0));
        verify(fixtures[3].selected).set(3);
    }

    @Test
    void readPages_pageCountBeyondCursorsPublishesEightPagesAndNamesTheRest() {
        observePageCount(11);
        parkAll();

        JsonObject result = parked.readPages();

        assertEquals(11, result.get("pageCount").getAsInt());
        assertEquals(8, result.getAsJsonArray("pages").size());
        JsonObject beyond = warning(result, "REMOTE_PAGES_BEYOND_CURSORS");
        assertNotNull(beyond);
        JsonArray pages = beyond.getAsJsonArray("pages");
        assertEquals(3, pages.size());
        assertEquals(8, pages.get(0).getAsInt());
        assertEquals(9, pages.get(1).getAsInt());
        assertEquals(10, pages.get(2).getAsInt());
    }

    @Test
    void observers_pageCountOfThreeDrivesNoReparkForCursorFive() {
        fixtures[5].selectedCallback.valueChanged(0);
        observePageCount(3);
        verify(fixtures[5].selected, never()).set(anyInt());

        JsonObject result = parked.readPages();
        assertEquals(3, result.getAsJsonArray("pages").size());
        verify(fixtures[5].selected, never()).set(anyInt());
    }

    @Test
    void observers_driftedCursorWithinThePageCountIsReparkedWithZeroDelay() {
        fixtures[3].selectedCallback.valueChanged(0);
        scheduledDelays.clear();
        fixtures[3].countCallback.valueChanged(4);

        verify(fixtures[3].selected, atLeastOnce()).set(3);
        assertFalse(scheduledDelays.isEmpty());
        for (long delay : scheduledDelays) assertEquals(0L, delay);
    }

    @Test
    void readPages_unobservedControlFieldsPublishNullWithFieldWarnings() {
        observePageCount(2);
        observePageNames("Main", "Filter");
        fixtures[0].selectedCallback.valueChanged(0);
        fixtures[1].selectedCallback.valueChanged(1);
        // Page 0 observed; page 1's controls never fired.
        fixtures[0].observeControls(0);

        JsonObject result = parked.readPages();

        JsonObject control = page(result, 1).getAsJsonArray("parameters").get(0).getAsJsonObject();
        assertTrue(control.get("name").isJsonNull());
        assertTrue(control.get("value").isJsonNull());
        assertTrue(control.get("displayedValue").isJsonNull());
        int fieldWarnings = 0;
        for (JsonElement element : result.getAsJsonArray("warnings")) {
            JsonObject warning = element.getAsJsonObject();
            if (!"REMOTE_FIELD_UNOBSERVED".equals(warning.get("code").getAsString())) continue;
            fieldWarnings++;
            assertEquals(1, warning.getAsJsonArray("pages").size());
            assertEquals(1, warning.getAsJsonArray("pages").get(0).getAsInt());
        }
        assertEquals(3, fieldWarnings);
    }

    @Test
    void readPages_unobservedPageCountPublishesNoPagesAndAWarning() {
        parkAll();

        JsonObject result = parked.readPages();

        assertTrue(result.get("pageCount").isJsonNull());
        assertTrue(result.get("pageNames").isJsonNull());
        assertEquals(0, result.getAsJsonArray("pages").size());
        JsonObject unobserved = warning(result, "REMOTE_FIELD_UNOBSERVED");
        assertNotNull(unobserved);
        assertEquals("pageCount", unobserved.get("field").getAsString());
    }

    // --- writeValues ---

    @Test
    void writeValues_badIndexOnTheSecondPageThrowsTheSetParametersMessageAndWritesNothing() {
        observePageCount(4);
        parkAll();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parked.writeValues(payload(
                "[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.5}]},"
                    + "{\"pageIndex\":1,\"params\":[{\"index\":9,\"value\":0.5}]}]")));
        assertEquals("parameter index out of range: 0-7, got 9", error.getMessage());
        for (PageFixture fixture : fixtures) {
            for (SettableRangedValue value : fixture.values) {
                verify(value, never()).setImmediately(anyDouble());
            }
        }
    }

    @Test
    void writeValues_emptyPagesThrowsTheSetParametersMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parked.writeValues(new JsonArray()));
        assertEquals("pages array must not be empty", error.getMessage());
    }

    @Test
    void writeValues_pageAtOrPastTheReachableCountIsOutOfReach() {
        observePageCount(3);
        parkAll();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parked.writeValues(payload(
                "[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.5}]},"
                    + "{\"pageIndex\":3,\"params\":[{\"index\":0,\"value\":0.5}]}]")));
        assertEquals("REMOTE_PAGE_OUT_OF_REACH: page 3", error.getMessage());
        verify(fixtures[0].values[0], never()).setImmediately(anyDouble());
    }

    @Test
    void writeValues_unobservedPageCountIsOutOfReach() {
        parkAll();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parked.writeValues(payload(
                "[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.5}]}]")));
        assertEquals("REMOTE_PAGE_OUT_OF_REACH: page 0", error.getMessage());
    }

    @Test
    void writeValues_unparkedPageRefusesTheWholeCallAndSchedulesARepark() {
        observePageCount(4);
        parkAll();
        fixtures[2].selectedCallback.valueChanged(0);
        // As above: setup's page-count callback opened this page's re-park window, and the
        // refusal below schedules only once that window has expired (WR-09).
        advancePastTheReparkWindow();
        reset(fixtures[2].selected);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> parked.writeValues(payload(
                "[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.5}]},"
                    + "{\"pageIndex\":2,\"params\":[{\"index\":1,\"value\":0.25}]}]")));

        assertEquals("REMOTE_PAGE_NOT_PARKED: page 2", error.getMessage());
        verify(fixtures[0].values[0], never()).setImmediately(anyDouble());
        verify(fixtures[2].values[1], never()).setImmediately(anyDouble());
        verify(fixtures[2].selected).set(2);
    }

    @Test
    void writeValues_parkedPagesWriteOnlyOnTheirOwnCursor() {
        observePageCount(4);
        parkAll();
        scheduledTasks.clear();
        // The page-count callbacks above fired before any cursor was parked, so each cursor was
        // already re-parked once during setup; only the write's own effect is asserted below.
        for (PageFixture fixture : fixtures) clearInvocations(fixture.selected);

        JsonObject result = parked.writeValues(payload(
            "[{\"pageIndex\":1,\"params\":[{\"index\":2,\"value\":0.75}]},"
                + "{\"pageIndex\":3,\"params\":[{\"index\":2,\"value\":0.125},"
                + "{\"index\":7,\"value\":1.0}]}]"));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(2, result.get("pageCount").getAsInt());
        assertEquals(3, result.get("paramCount").getAsInt());
        verify(fixtures[1].values[2]).setImmediately(0.75);
        verify(fixtures[3].values[2]).setImmediately(0.125);
        verify(fixtures[3].values[7]).setImmediately(1.0);
        verify(fixtures[0].values[2], never()).setImmediately(anyDouble());
        verify(fixtures[2].values[2], never()).setImmediately(anyDouble());
        // The write is synchronous: nothing was scheduled for it.
        assertTrue(scheduledTasks.isEmpty());
        // And no parked write moved any page.
        for (PageFixture fixture : fixtures) verify(fixture.selected, never()).set(anyInt());
    }

    // --- the re-park window (WR-09) ---
    //
    // Before this window existed, every read of an unparked page scheduled another zero-delay
    // task, and readPages is precisely what a caller POLLS while waiting for a cursor to settle:
    // a cursor that could not park enqueued one re-park per poll, unbounded, on the thread every
    // other operation shares. The bound is a monotonic last-scheduled millisecond per page rather
    // than a pending flag, because a flag cleared only by the task itself latches the gate shut
    // for the session if one scheduled task never runs (the WR-06 lesson).

    /** Drift page 3 off its own index and leave its re-park window expired. */
    private void driftPageThreeWithAnExpiredWindow() {
        observePageCount(4);
        parkAll();
        fixtures[3].selectedCallback.valueChanged(0);
        advancePastTheReparkWindow();
        reset(fixtures[3].selected);
        scheduledTasks.clear();
        scheduledDelays.clear();
    }

    @Test
    void repark_twoReadsOfOneUnparkedPageInsideOneWindowScheduleExactlyOneTask() {
        driftPageThreeWithAnExpiredWindow();

        parked.readPages();
        clockMs += REPARK_WINDOW_MS - 1;
        parked.readPages();

        assertEquals(1, scheduledTasks.size(),
            "a second read inside the same window must not enqueue a second re-park: the set is "
                + "idempotent and the first one has not been observed yet");
        verify(fixtures[3].selected, times(1)).set(3);
        // The page is still reported as unparked on BOTH reads -- the window bounds the
        // scheduling, never what the read tells the caller.
        assertFalse(page(parked.readPages(), 3).get("parked").getAsBoolean());
    }

    @Test
    void repark_aReadAfterTheWindowSchedulesASecondTask() {
        driftPageThreeWithAnExpiredWindow();

        parked.readPages();
        advancePastTheReparkWindow();
        parked.readPages();

        assertEquals(2, scheduledTasks.size(),
            "the bound must EXPIRE on its own -- a window that never reopens is the latching a "
                + "pending flag would have caused");
        verify(fixtures[3].selected, times(2)).set(3);
        for (long delay : scheduledDelays) assertEquals(0L, delay);
    }

    // --- malformed write payloads (WR-10) ---
    //
    // Each of these threw out of Gson before: IllegalStateException for a non-object (mapped to
    // -32603 internal error, which tells the caller the ENGINE misbehaved) or
    // NumberFormatException for a non-numeric field (an IllegalArgumentException subclass, so
    // -32602 already -- but carrying Java's `For input string: "..."`, which names no field).
    // Both are now a plain IllegalArgumentException naming the field, which is why every case
    // below asserts the EXACT class: assertThrows(IllegalArgumentException.class) alone would
    // pass on a NumberFormatException and prove nothing.

    private void assertInvalidParams(String json, String expectedMessage) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parked.writeValues(payload(json)));
        assertSame(IllegalArgumentException.class, error.getClass(),
            "the guard must throw IllegalArgumentException itself, not a Gson subclass: "
                + "NumberFormatException would satisfy the declared type and still carry a "
                + "message that names no field");
        assertEquals(expectedMessage, error.getMessage());
        for (PageFixture fixture : fixtures) {
            for (SettableRangedValue value : fixture.values) {
                verify(value, never()).setImmediately(anyDouble());
            }
        }
    }

    @Test
    void writeValues_aPageElementThatIsNotAnObjectIsRefusedAsInvalidParams() {
        observePageCount(4);
        parkAll();
        assertInvalidParams("[\"pageIndex\"]", "each page must be an object, got \"pageIndex\"");
    }

    @Test
    void writeValues_aStringPageIndexIsRefusedAsInvalidParamsNotAnInternalError() {
        observePageCount(4);
        parkAll();
        assertInvalidParams(
            "[{\"pageIndex\":\"1\",\"params\":[{\"index\":0,\"value\":0.5}]}]",
            "'pageIndex' must be a number, got \"1\"");
    }

    @Test
    void writeValues_aStringParameterIndexIsRefusedAsInvalidParams() {
        observePageCount(4);
        parkAll();
        assertInvalidParams(
            "[{\"pageIndex\":1,\"params\":[{\"index\":\"two\",\"value\":0.5}]}]",
            "'index' must be a number, got \"two\"");
    }

    @Test
    void writeValues_aBooleanParameterValueIsRefusedAsInvalidParams() {
        observePageCount(4);
        parkAll();
        assertInvalidParams(
            "[{\"pageIndex\":1,\"params\":[{\"index\":0,\"value\":true}]}]",
            "'value' must be a number, got true");
    }

    @Test
    void writeValues_aParamElementThatIsNotAnObjectIsRefusedAsInvalidParams() {
        observePageCount(4);
        parkAll();
        assertInvalidParams(
            "[{\"pageIndex\":1,\"params\":[7]}]",
            "each param must be an object, got 7");
    }
}
