package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Groove;
import com.bitwig.extension.controller.api.Parameter;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrooveHandlerTest {

    @Mock private Groove mockGroove;
    @Mock private Parameter mockEnabled;
    @Mock private Parameter mockShuffleAmount;
    @Mock private Parameter mockShuffleRate;
    @Mock private Parameter mockAccentAmount;
    @Mock private Parameter mockAccentRate;
    @Mock private Parameter mockAccentPhase;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(mockGroove.getEnabled()).thenReturn(mockEnabled);
        when(mockGroove.getShuffleAmount()).thenReturn(mockShuffleAmount);
        when(mockGroove.getShuffleRate()).thenReturn(mockShuffleRate);
        when(mockGroove.getAccentAmount()).thenReturn(mockAccentAmount);
        when(mockGroove.getAccentRate()).thenReturn(mockAccentRate);
        when(mockGroove.getAccentPhase()).thenReturn(mockAccentPhase);

        dispatcher = new JsonRpcDispatcher();
        new GrooveHandler(mockGroove).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersThreeMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("groove/getState"));
        assertTrue(methods.contains("groove/setEnabled"));
        assertTrue(methods.contains("groove/setParameter"));
        assertEquals(3, methods.size());
    }

    // --- groove/getState ---

    @Test
    void getState_returnsAllSixFields() {
        when(mockEnabled.get()).thenReturn(1.0);
        when(mockShuffleAmount.get()).thenReturn(0.5);
        when(mockShuffleRate.get()).thenReturn(0.25);
        when(mockAccentAmount.get()).thenReturn(0.75);
        when(mockAccentRate.get()).thenReturn(0.1);
        when(mockAccentPhase.get()).thenReturn(0.0);

        String response = dispatcher.handle(rpc("groove/getState", "{}"));
        assertContains(response, "\"enabled\":true");
        assertContains(response, "\"shuffleAmount\":0.5");
        assertContains(response, "\"shuffleRate\":0.25");
        assertContains(response, "\"accentAmount\":0.75");
        assertContains(response, "\"accentRate\":0.1");
        assertContains(response, "\"accentPhase\":0.0");
    }

    @Test
    void getState_enabledFalseWhenBelowThreshold() {
        when(mockEnabled.get()).thenReturn(0.0);
        when(mockShuffleAmount.get()).thenReturn(0.0);
        when(mockShuffleRate.get()).thenReturn(0.0);
        when(mockAccentAmount.get()).thenReturn(0.0);
        when(mockAccentRate.get()).thenReturn(0.0);
        when(mockAccentPhase.get()).thenReturn(0.0);

        String response = dispatcher.handle(rpc("groove/getState", "{}"));
        assertContains(response, "\"enabled\":false");
    }

    // --- groove/setEnabled ---

    @Test
    void setEnabled_true_setsToOne() {
        dispatcher.handle(rpc("groove/setEnabled", "{\"enabled\":true}"));
        verify(mockEnabled).set(1.0);
    }

    @Test
    void setEnabled_false_setsToZero() {
        dispatcher.handle(rpc("groove/setEnabled", "{\"enabled\":false}"));
        verify(mockEnabled).set(0.0);
    }

    @Test
    void setEnabled_missingParam_returnsError() {
        String response = dispatcher.handle(rpc("groove/setEnabled", "{}"));
        assertContains(response, "enabled");
    }

    // --- groove/setParameter ---

    @Test
    void setParameter_shuffleAmount() {
        dispatcher.handle(rpc("groove/setParameter", "{\"name\":\"shuffleAmount\",\"value\":0.7}"));
        verify(mockShuffleAmount).set(0.7);
    }

    @Test
    void setParameter_shuffleRate() {
        dispatcher.handle(rpc("groove/setParameter", "{\"name\":\"shuffleRate\",\"value\":0.3}"));
        verify(mockShuffleRate).set(0.3);
    }

    @Test
    void setParameter_accentAmount() {
        dispatcher.handle(rpc("groove/setParameter", "{\"name\":\"accentAmount\",\"value\":0.6}"));
        verify(mockAccentAmount).set(0.6);
    }

    @Test
    void setParameter_accentRate() {
        dispatcher.handle(rpc("groove/setParameter", "{\"name\":\"accentRate\",\"value\":0.4}"));
        verify(mockAccentRate).set(0.4);
    }

    @Test
    void setParameter_accentPhase() {
        dispatcher.handle(rpc("groove/setParameter", "{\"name\":\"accentPhase\",\"value\":0.2}"));
        verify(mockAccentPhase).set(0.2);
    }

    @Test
    void setParameter_invalidName_returnsError() {
        String response = dispatcher.handle(rpc("groove/setParameter", "{\"name\":\"invalid\",\"value\":0.5}"));
        assertContains(response, "invalid parameter name");
    }

    @Test
    void setParameter_missingName_returnsError() {
        String response = dispatcher.handle(rpc("groove/setParameter", "{\"value\":0.5}"));
        assertContains(response, "name");
    }

    @Test
    void setParameter_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("groove/setParameter", "{\"name\":\"shuffleAmount\"}"));
        assertContains(response, "value");
    }

    // --- Helpers ---

    private String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}";
    }

    private void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected),
            "Expected '" + expected + "' in: " + actual);
    }
}
