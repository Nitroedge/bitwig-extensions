package dev.bcrick.secondo.rpc;

import com.google.gson.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcDispatcherTest {

    private JsonRpcDispatcher dispatcher;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        dispatcher.register("echo", params -> {
            return params.get("message");
        });
        dispatcher.register("add", params -> {
            int a = params.get("a").getAsInt();
            int b = params.get("b").getAsInt();
            return new JsonPrimitive(a + b);
        });
        dispatcher.register("fail", params -> {
            throw new RuntimeException("intentional error");
        });
    }

    @Test
    void handlesValidRequest() {
        String request = """
            {"jsonrpc":"2.0","method":"add","params":{"a":2,"b":3},"id":1}""";
        String response = dispatcher.handle(request);

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        assertEquals("2.0", json.get("jsonrpc").getAsString());
        assertEquals(5, json.get("result").getAsInt());
        assertEquals(1, json.get("id").getAsInt());
    }

    @Test
    void returnsMethodNotFound() {
        String request = """
            {"jsonrpc":"2.0","method":"unknown","id":1}""";
        String response = dispatcher.handle(request);

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32601, error.get("code").getAsInt());
        assertTrue(error.get("message").getAsString().contains("Method not found"));
    }

    @Test
    void handlesBatchRequest() {
        String request = """
            [
                {"jsonrpc":"2.0","method":"add","params":{"a":1,"b":2},"id":1},
                {"jsonrpc":"2.0","method":"add","params":{"a":3,"b":4},"id":2}
            ]""";
        String response = dispatcher.handle(request);

        JsonArray array = JsonParser.parseString(response).getAsJsonArray();
        assertEquals(2, array.size());

        JsonObject first = array.get(0).getAsJsonObject();
        assertEquals(3, first.get("result").getAsInt());
        assertEquals(1, first.get("id").getAsInt());

        JsonObject second = array.get(1).getAsJsonObject();
        assertEquals(7, second.get("result").getAsInt());
        assertEquals(2, second.get("id").getAsInt());
    }

    @Test
    void notificationReturnsNull() {
        String request = """
            {"jsonrpc":"2.0","method":"echo","params":{"message":"hello"}}""";
        String response = dispatcher.handle(request);
        assertNull(response);
    }

    @Test
    void returnsParseError() {
        String response = dispatcher.handle("{invalid json");
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32700, error.get("code").getAsInt());
    }

    @Test
    void returnsInvalidRequestForMissingVersion() {
        String request = """
            {"method":"echo","id":1}""";
        String response = dispatcher.handle(request);

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32600, error.get("code").getAsInt());
    }

    @Test
    void returnsInternalErrorOnHandlerException() {
        String request = """
            {"jsonrpc":"2.0","method":"fail","id":1}""";
        String response = dispatcher.handle(request);

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32603, error.get("code").getAsInt());
        assertTrue(error.get("message").getAsString().contains("intentional error"));
    }

    @Test
    void emptyBatchReturnsError() {
        String response = dispatcher.handle("[]");
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32600, error.get("code").getAsInt());
    }

    @Test
    void batchWithAllNotificationsReturnsNull() {
        String request = """
            [
                {"jsonrpc":"2.0","method":"echo","params":{"message":"a"}},
                {"jsonrpc":"2.0","method":"echo","params":{"message":"b"}}
            ]""";
        String response = dispatcher.handle(request);
        assertNull(response);
    }

    @Test
    void emptyParamsDefaultsToEmptyObject() {
        dispatcher.register("noparams", params -> new JsonPrimitive("ok"));
        String request = """
            {"jsonrpc":"2.0","method":"noparams","id":1}""";
        String response = dispatcher.handle(request);

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        assertEquals("ok", json.get("result").getAsString());
    }

    @Test
    void getRegisteredMethodsReturnsAll() {
        assertTrue(dispatcher.getRegisteredMethods().contains("echo"));
        assertTrue(dispatcher.getRegisteredMethods().contains("add"));
        assertTrue(dispatcher.getRegisteredMethods().contains("fail"));
        assertEquals(3, dispatcher.getRegisteredMethods().size());
    }
}
