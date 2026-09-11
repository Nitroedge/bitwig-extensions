package dev.bcrick.secondo.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static dev.bcrick.secondo.rpc.JsonParamValidator.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonParamValidatorTest {

    // --- requireInt ---

    @Test
    void requireInt_returnsValue() {
        JsonObject params = new JsonObject();
        params.addProperty("count", 42);
        assertEquals(42, requireInt(params, "count"));
    }

    @Test
    void requireInt_missingKey_throws() {
        JsonObject params = new JsonObject();
        var ex = assertThrows(IllegalArgumentException.class,
            () -> requireInt(params, "count"));
        assertTrue(ex.getMessage().contains("count"));
    }

    // --- requireString ---

    @Test
    void requireString_returnsValue() {
        JsonObject params = new JsonObject();
        params.addProperty("name", "hello");
        assertEquals("hello", requireString(params, "name"));
    }

    @Test
    void requireString_missingKey_throws() {
        JsonObject params = new JsonObject();
        var ex = assertThrows(IllegalArgumentException.class,
            () -> requireString(params, "name"));
        assertTrue(ex.getMessage().contains("name"));
    }

    // --- requireBoolean ---

    @Test
    void requireBoolean_returnsValue() {
        JsonObject params = new JsonObject();
        params.addProperty("enabled", true);
        assertTrue(requireBoolean(params, "enabled"));
    }

    @Test
    void requireBoolean_missingKey_throws() {
        JsonObject params = new JsonObject();
        var ex = assertThrows(IllegalArgumentException.class,
            () -> requireBoolean(params, "enabled"));
        assertTrue(ex.getMessage().contains("enabled"));
    }

    // --- requireDouble ---

    @Test
    void requireDouble_returnsValue() {
        JsonObject params = new JsonObject();
        params.addProperty("volume", 0.75);
        assertEquals(0.75, requireDouble(params, "volume"), 0.001);
    }

    @Test
    void requireDouble_missingKey_throws() {
        JsonObject params = new JsonObject();
        var ex = assertThrows(IllegalArgumentException.class,
            () -> requireDouble(params, "volume"));
        assertTrue(ex.getMessage().contains("volume"));
    }

    // --- optionalInt ---

    @Test
    void optionalInt_returnsValue_whenPresent() {
        JsonObject params = new JsonObject();
        params.addProperty("position", 5);
        assertEquals(5, optionalInt(params, "position", -1));
    }

    @Test
    void optionalInt_returnsDefault_whenMissing() {
        JsonObject params = new JsonObject();
        assertEquals(-1, optionalInt(params, "position", -1));
    }

    @Test
    void optionalInt_returnsDefault_whenNull() {
        JsonObject params = new JsonObject();
        params.add("position", JsonNull.INSTANCE);
        assertEquals(-1, optionalInt(params, "position", -1));
    }

    // --- optionalString ---

    @Test
    void optionalString_returnsValue_whenPresent() {
        JsonObject params = new JsonObject();
        params.addProperty("label", "test");
        assertEquals("test", optionalString(params, "label", "default"));
    }

    @Test
    void optionalString_returnsDefault_whenMissing() {
        JsonObject params = new JsonObject();
        assertEquals("default", optionalString(params, "label", "default"));
    }

    @Test
    void optionalString_returnsDefault_whenNull() {
        JsonObject params = new JsonObject();
        params.add("label", JsonNull.INSTANCE);
        assertEquals("default", optionalString(params, "label", "default"));
    }

    // --- requireArray ---

    @Test
    void requireArray_returnsArray() {
        JsonObject params = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(1);
        arr.add(2);
        params.add("notes", arr);
        JsonArray result = requireArray(params, "notes");
        assertEquals(2, result.size());
    }

    @Test
    void requireArray_missingKey_throws() {
        JsonObject params = new JsonObject();
        var ex = assertThrows(IllegalArgumentException.class,
            () -> requireArray(params, "notes"));
        assertTrue(ex.getMessage().contains("notes"));
    }

    @Test
    void requireArray_nonArrayValue_throws() {
        JsonObject params = new JsonObject();
        params.addProperty("notes", "not an array");
        var ex = assertThrows(IllegalArgumentException.class,
            () -> requireArray(params, "notes"));
        assertTrue(ex.getMessage().contains("notes"));
    }
}
