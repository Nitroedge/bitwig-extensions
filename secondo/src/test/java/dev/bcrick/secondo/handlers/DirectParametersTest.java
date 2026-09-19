package dev.bcrick.secondo.handlers;

import com.bitwig.extension.callback.DirectParameterDisplayedValueChangedCallback;
import com.bitwig.extension.callback.DirectParameterNameChangedCallback;
import com.bitwig.extension.callback.DirectParameterNormalizedValueChangedCallback;
import com.bitwig.extension.callback.StringArrayValueChangedCallback;
import com.bitwig.extension.controller.api.BooleanValue;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.DirectParameterValueDisplayObserver;
import com.bitwig.extension.controller.api.StringValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The direct-parameter caches, the id-set pruning and the panel write (Phase 27, D-27-12,
 * D-27-17, research Pitfalls 3 and 4). Every observer callback is captured and fired by hand.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectParametersTest {

    @Mock private CursorDevice mockDevice;
    @Mock private BooleanValue mockExists;
    @Mock private BooleanValue mockIsPlugin;
    @Mock private StringValue mockName;
    @Mock private DirectParameterValueDisplayObserver mockDisplayObserver;

    private StringArrayValueChangedCallback idCallback;
    private DirectParameterNameChangedCallback nameCallback;
    private DirectParameterDisplayedValueChangedCallback displayCallback;
    private DirectParameterNormalizedValueChangedCallback normalizedCallback;
    private DirectParameters direct;

    @BeforeEach
    void setUp() {
        when(mockDevice.exists()).thenReturn(mockExists);
        when(mockDevice.isPlugin()).thenReturn(mockIsPlugin);
        when(mockDevice.name()).thenReturn(mockName);
        doAnswer(inv -> {
            idCallback = inv.getArgument(0);
            return null;
        }).when(mockDevice).addDirectParameterIdObserver(any());
        doAnswer(inv -> {
            nameCallback = inv.getArgument(1);
            return null;
        }).when(mockDevice).addDirectParameterNameObserver(anyInt(), any());
        when(mockDevice.addDirectParameterValueDisplayObserver(anyInt(), any()))
            .thenAnswer(inv -> {
                displayCallback = inv.getArgument(1);
                return mockDisplayObserver;
            });
        doAnswer(inv -> {
            normalizedCallback = inv.getArgument(0);
            return null;
        }).when(mockDevice).addDirectParameterNormalizedValueObserver(any());

        direct = DirectParameters.attach(mockDevice);
    }

    private void observeAll(String id, String name, String display, double value) {
        nameCallback.directParameterNameChanged(id, name);
        displayCallback.directParameterDisplayedValueChanged(id, display);
        normalizedCallback.directParameterNormalizedValueChanged(id, value);
    }

    private static JsonObject parameter(JsonObject result, String id) {
        for (JsonElement element : result.getAsJsonArray("parameters")) {
            JsonObject parameter = element.getAsJsonObject();
            if (id.equals(parameter.get("id").getAsString())) return parameter;
        }
        return null;
    }

    private static JsonObject warning(JsonObject result, String code, String field) {
        for (JsonElement element : result.getAsJsonArray("warnings")) {
            JsonObject warning = element.getAsJsonObject();
            if (!code.equals(warning.get("code").getAsString())) continue;
            if (field == null || field.equals(warning.get("field").getAsString())) return warning;
        }
        return null;
    }

    // --- attach ---

    @Test
    void attach_registersTheIdNameDisplayAndNormalizedObserversWithTheirBudgets() {
        verify(mockDevice).addDirectParameterIdObserver(any());
        verify(mockDevice).addDirectParameterNameObserver(eq(256), any());
        verify(mockDevice).addDirectParameterValueDisplayObserver(eq(64), any());
        verify(mockDevice).addDirectParameterNormalizedValueObserver(any());
        verify(mockExists).markInterested();
        verify(mockIsPlugin).markInterested();
        verify(mockName).markInterested();
        assertEquals(256, DirectParameters.MAX_NAME_CHARS);
        assertEquals(64, DirectParameters.MAX_DISPLAY_CHARS);
        assertEquals(65536, DirectParameters.PANEL_WRITE_RESOLUTION);
    }

    /**
     * The message Bitwig's own {@code com.bitwig.flt.control_surface.proxy.DeviceProxy} throws
     * from {@code addDirectParameterNameObserver}, {@code addDirectParameterValueDisplayObserver}
     * and {@code addDirectParameterNormalizedValueObserver} when
     * {@code mDirectParameterIds.isInterested()} is false — read out of bitwig.jar's bytecode
     * (Bitwig Studio 6, API v25). Reproduced live 2026-09-17: the 0.2.6 candidate registered the
     * display observer first, this threw out of {@code init()}, and Bitwig refused to enable the
     * extension with the "Secondo did something wrong" dialog.
     */
    private static final String BITWIG_IDS_NOT_INTERESTED =
        "You need to either add an observer to the direct parameter ids "
            + "or mark them as interested first";

    @Test
    void attach_subscribesTheIdSetBeforeEveryOtherDirectParameterObserver() {
        // Only the id observer's position is asserted — that is the whole constraint, and the
        // other three may be registered in any order among themselves.
        InOrder beforeDisplay = inOrder(mockDevice);
        beforeDisplay.verify(mockDevice).addDirectParameterIdObserver(any());
        beforeDisplay.verify(mockDevice).addDirectParameterValueDisplayObserver(anyInt(), any());

        InOrder beforeName = inOrder(mockDevice);
        beforeName.verify(mockDevice).addDirectParameterIdObserver(any());
        beforeName.verify(mockDevice).addDirectParameterNameObserver(anyInt(), any());

        InOrder beforeNormalized = inOrder(mockDevice);
        beforeNormalized.verify(mockDevice).addDirectParameterIdObserver(any());
        beforeNormalized.verify(mockDevice).addDirectParameterNormalizedValueObserver(any());
    }

    @Test
    void attach_survivesBitwigsIdsMustBeInterestedFirstGuard() {
        // A device that enforces Bitwig's own precondition rather than merely recording calls.
        CursorDevice guarded = mock(CursorDevice.class);
        boolean[] idsInterested = {false};
        when(guarded.exists()).thenReturn(mockExists);
        when(guarded.isPlugin()).thenReturn(mockIsPlugin);
        when(guarded.name()).thenReturn(mockName);
        doAnswer(inv -> {
            idsInterested[0] = true;
            return null;
        }).when(guarded).addDirectParameterIdObserver(any());
        when(guarded.addDirectParameterValueDisplayObserver(anyInt(), any())).thenAnswer(inv -> {
            if (!idsInterested[0]) throw new IllegalStateException(BITWIG_IDS_NOT_INTERESTED);
            return mockDisplayObserver;
        });
        doAnswer(inv -> {
            if (!idsInterested[0]) throw new IllegalStateException(BITWIG_IDS_NOT_INTERESTED);
            return null;
        }).when(guarded).addDirectParameterNameObserver(anyInt(), any());
        doAnswer(inv -> {
            if (!idsInterested[0]) throw new IllegalStateException(BITWIG_IDS_NOT_INTERESTED);
            return null;
        }).when(guarded).addDirectParameterNormalizedValueObserver(any());

        // init() must complete. On the defective order this throws BITWIG_IDS_NOT_INTERESTED,
        // which is exactly what stopped Bitwig enabling the extension.
        assertDoesNotThrow(() -> DirectParameters.attach(guarded));
    }

    @Test
    void attach_subscribesDisplaysToAnIdSetDeliveredBeforeTheDisplayObserverExists() {
        // Going second costs the display observer the id set replaceIds could not hand it: a
        // device whose id observer answers synchronously would otherwise observe no display at
        // all, because by default the observer observes none.
        CursorDevice eager = mock(CursorDevice.class);
        when(eager.exists()).thenReturn(mockExists);
        when(eager.isPlugin()).thenReturn(mockIsPlugin);
        when(eager.name()).thenReturn(mockName);
        doAnswer(inv -> {
            ((StringArrayValueChangedCallback) inv.getArgument(0))
                .valueChanged(new String[] {"p", "q"});
            return null;
        }).when(eager).addDirectParameterIdObserver(any());
        when(eager.addDirectParameterValueDisplayObserver(anyInt(), any()))
            .thenReturn(mockDisplayObserver);

        DirectParameters eagerDirect = DirectParameters.attach(eager);

        verify(mockDisplayObserver).setObservedParameterIds(aryEq(new String[] {"p", "q"}));
        assertEquals(1, eagerDirect.generation());
        assertEquals(2, eagerDirect.read().get("parameterCount").getAsInt());
    }

    // --- the id observer ---

    @Test
    void idCallback_replacesTheSetBumpsTheGenerationPrunesAndResubscribes() {
        idCallback.valueChanged(new String[] {"a", "b"});
        assertEquals(1, direct.generation());
        verify(mockDisplayObserver).setObservedParameterIds(aryEq(new String[] {"a", "b"}));
        observeAll("a", "Cutoff", "440 Hz", 0.5);
        observeAll("b", "Reso", "10 %", 0.1);

        idCallback.valueChanged(new String[] {"b", "c"});
        assertEquals(2, direct.generation());
        verify(mockDisplayObserver).setObservedParameterIds(aryEq(new String[] {"b", "c"}));
        JsonObject result = direct.read();
        assertEquals(2, result.get("generation").getAsInt());
        assertEquals(2, result.get("parameterCount").getAsInt());
        assertEquals("Reso", parameter(result, "b").get("name").getAsString());
        assertNull(parameter(result, "a"));

        // "a" returns without any new callback: its old cache entries were pruned, not kept.
        idCallback.valueChanged(new String[] {"a", "b", "c"});
        JsonObject again = direct.read();
        assertEquals(3, again.get("generation").getAsInt());
        JsonObject a = parameter(again, "a");
        assertTrue(a.get("name").isJsonNull());
        assertTrue(a.get("displayValue").isJsonNull());
        assertTrue(a.get("normalizedValue").isJsonNull());
    }

    // --- read ---

    @Test
    void read_beforeAnyIdCallbackPublishesIdsUnobserved() {
        JsonObject result = direct.read();
        assertFalse(result.get("idsObserved").getAsBoolean());
        assertTrue(result.get("parameterCount").isJsonNull());
        assertEquals(0, result.getAsJsonArray("parameters").size());
        assertNotNull(warning(result, "PANEL_IDS_UNOBSERVED", null));
    }

    @Test
    void read_anObservedEmptyIdSetPublishesNoDirectParameters() {
        idCallback.valueChanged(new String[0]);
        JsonObject result = direct.read();
        assertTrue(result.get("idsObserved").getAsBoolean());
        assertEquals(0, result.get("parameterCount").getAsInt());
        assertNotNull(warning(result, "PANEL_NO_DIRECT_PARAMETERS", null));
    }

    @Test
    void read_publishesObservedFieldsInIdOrderWithDeviceFacts() {
        doAnswer(inv -> {
            ((com.bitwig.extension.callback.StringValueChangedCallback) inv.getArgument(0))
                .valueChanged("Diva");
            return null;
        }).when(mockName).addValueObserver(any());
        doAnswer(inv -> {
            ((com.bitwig.extension.callback.BooleanValueChangedCallback) inv.getArgument(0))
                .valueChanged(true);
            return null;
        }).when(mockIsPlugin).addValueObserver(any());
        DirectParameters fresh = new DirectParameters(mockDevice);
        idCallback.valueChanged(new String[] {"z", "y"});
        observeAll("z", "Volume", "-3 dB", 0.75);
        observeAll("y", "Pan", "C", 0.5);

        JsonObject result = fresh.read();
        assertEquals("Diva", result.get("deviceName").getAsString());
        assertTrue(result.get("isPlugin").getAsBoolean());
        assertTrue(result.get("deviceExists").isJsonNull());
        JsonArray parameters = result.getAsJsonArray("parameters");
        assertEquals("z", parameters.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("y", parameters.get(1).getAsJsonObject().get("id").getAsString());
        JsonObject z = parameters.get(0).getAsJsonObject();
        assertEquals("Volume", z.get("name").getAsString());
        assertEquals("-3 dB", z.get("displayValue").getAsString());
        assertEquals(0.75, z.get("normalizedValue").getAsDouble(), 1e-12);
        assertEquals(0, result.getAsJsonArray("warnings").size());
    }

    @Test
    void read_aNaNNormalizedValuePublishesNullAndAFieldCount() {
        idCallback.valueChanged(new String[] {"a", "b"});
        observeAll("a", "Cutoff", "440 Hz", 0.5);
        observeAll("b", "Reso", "10 %", 0.1);
        normalizedCallback.directParameterNormalizedValueChanged("b", Double.NaN);

        JsonObject result = direct.read();
        assertTrue(parameter(result, "b").get("normalizedValue").isJsonNull());
        assertEquals(0.5, parameter(result, "a").get("normalizedValue").getAsDouble(), 1e-12);
        JsonObject cold = warning(result, "PANEL_FIELD_UNOBSERVED", "normalizedValue");
        assertNotNull(cold);
        assertEquals(1, cold.get("count").getAsInt());
        assertNull(warning(result, "PANEL_FIELD_UNOBSERVED", "name"));
    }

    // --- write ---

    @Test
    void write_anIdOutsideTheCurrentSetIsRefusedBeforeTheSetter() {
        idCallback.valueChanged(new String[] {"a"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> direct.write("stale", 0.5));
        assertEquals("PANEL_PARAMETER_ID_UNKNOWN: stale", error.getMessage());
        verify(mockDevice, never()).setDirectParameterValueNormalized(anyString(), any(), any());
    }

    @Test
    void write_beforeTheIdSetIsObservedIsAnIllegalState() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> direct.write("a", 0.5));
        assertEquals("PANEL_IDS_UNOBSERVED", error.getMessage());
        verify(mockDevice, never()).setDirectParameterValueNormalized(anyString(), any(), any());
    }

    @Test
    void write_aValueOutsideTheUnitRangeIsRefused() {
        idCallback.valueChanged(new String[] {"a"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> direct.write("a", 1.2));
        assertEquals("panel parameter value out of range: 0.0-1.0, got 1.2", error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> direct.write("a", Double.NaN));
        verify(mockDevice, never()).setDirectParameterValueNormalized(anyString(), any(), any());
    }

    @Test
    void write_halfScalesTo32768AtResolution65536AndAnswersTheGeneration() {
        idCallback.valueChanged(new String[] {"a"});
        JsonObject result = direct.write("a", 0.5);

        ArgumentCaptor<Number> scaled = ArgumentCaptor.forClass(Number.class);
        ArgumentCaptor<Number> resolution = ArgumentCaptor.forClass(Number.class);
        verify(mockDevice).setDirectParameterValueNormalized(
            eq("a"), scaled.capture(), resolution.capture());
        assertEquals(32768L, scaled.getValue().longValue());
        assertEquals(65536L, resolution.getValue().longValue());
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(1, result.get("generation").getAsInt());
    }

    @Test
    void panelParams_parseTheContractsIdAndValueMessages() {
        JsonObject missingId = JsonParser.parseString("{\"value\":0.5}").getAsJsonObject();
        IllegalArgumentException idError = assertThrows(IllegalArgumentException.class,
            () -> DirectParameters.panelId(missingId));
        assertEquals("panel parameter id must be a non-empty string", idError.getMessage());
        JsonObject emptyId = JsonParser.parseString("{\"id\":\"\"}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> DirectParameters.panelId(emptyId));
        JsonObject booleanValue =
            JsonParser.parseString("{\"id\":\"a\",\"value\":true}").getAsJsonObject();
        IllegalArgumentException valueError = assertThrows(IllegalArgumentException.class,
            () -> DirectParameters.panelValue(booleanValue));
        // IN-04: a TYPE fault says so. It used to report the RANGE message, which told a caller
        // who had sent `true` that their number was outside 0.0-1.0.
        assertTrue(valueError.getMessage().startsWith("panel parameter value must be a number"),
            "reported: " + valueError.getMessage());
        JsonObject good = JsonParser.parseString("{\"id\":\"a\",\"value\":0.25}").getAsJsonObject();
        assertEquals("a", DirectParameters.panelId(good));
        assertEquals(0.25, DirectParameters.panelValue(good), 1e-12);
    }

    /**
     * IN-04, and the point of the finding: the two faults are different faults and a caller has
     * only the message to tell them apart. The Python layer quotes the engine's message verbatim
     * into the refusal the user hears, so a type fault wearing the range message told somebody
     * who had sent a STRING that their NUMBER was out of bounds -- a false report that sends the
     * reader looking at the wrong thing. The mock mirrors both messages in the same split
     * (`mock/state.py::set_panel_parameter`), so the two sides cannot disagree about which one a
     * caller sees.
     */
    @Test
    void panelValue_theTypeFaultAndTheRangeFaultCarryDifferentMessages() {
        idCallback.valueChanged(new String[] {"a"});

        for (String json : new String[] {
            "{\"id\":\"a\",\"value\":true}",
            "{\"id\":\"a\",\"value\":\"0.5\"}",
            "{\"id\":\"a\",\"value\":null}",
            "{\"id\":\"a\"}",
        }) {
            JsonObject params = JsonParser.parseString(json).getAsJsonObject();
            IllegalArgumentException typeError = assertThrows(IllegalArgumentException.class,
                () -> DirectParameters.panelValue(params));
            assertTrue(typeError.getMessage().startsWith("panel parameter value must be a number"),
                json + " reported: " + typeError.getMessage());
            assertFalse(typeError.getMessage().contains("out of range"),
                "a type fault must not wear the range message: " + typeError.getMessage());
        }

        // The real bound check keeps the range message, on a value that really is a number.
        IllegalArgumentException rangeError = assertThrows(IllegalArgumentException.class,
            () -> direct.write("a", 1.2));
        assertEquals("panel parameter value out of range: 0.0-1.0, got 1.2",
            rangeError.getMessage());
        verify(mockDevice, never()).setDirectParameterValueNormalized(anyString(), any(), any());
    }
}
