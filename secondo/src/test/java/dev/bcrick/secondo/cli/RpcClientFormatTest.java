package dev.bcrick.secondo.cli;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcClientFormatTest {

    @Test
    void format_nonPretty_compactJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", "value");

        String result = RpcClient.format(obj, false);

        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void format_pretty_indentedJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", "value");

        String result = RpcClient.format(obj, true);

        assertTrue(result.contains("\n"), "Pretty output should contain newlines");
        assertTrue(result.contains("  "), "Pretty output should contain indentation");
        assertTrue(result.contains("\"key\": \"value\""));
    }

    @Test
    void formatRaw_nonPretty_returnsUnchanged() {
        String json = "{\"key\":\"value\"}";

        String result = RpcClient.formatRaw(json, false);

        assertEquals(json, result);
    }

    @Test
    void formatRaw_pretty_indentsJson() {
        String json = "{\"key\":\"value\"}";

        String result = RpcClient.formatRaw(json, true);

        assertTrue(result.contains("\n"));
        assertTrue(result.contains("\"key\": \"value\""));
    }

    @Test
    void format_nullElement_returnsNullString() {
        String result = RpcClient.format(JsonNull.INSTANCE, false);

        assertEquals("null", result);
    }

    @Test
    void format_nestedObject_preservesStructure() {
        JsonObject inner = new JsonObject();
        inner.addProperty("b", 2);
        JsonObject outer = new JsonObject();
        outer.addProperty("a", 1);
        outer.add("nested", inner);

        String result = RpcClient.format(outer, false);

        assertEquals("{\"a\":1,\"nested\":{\"b\":2}}", result);
    }
}
