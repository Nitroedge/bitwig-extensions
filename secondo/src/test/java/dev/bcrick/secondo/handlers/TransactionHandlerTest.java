package dev.bcrick.secondo.handlers;

import com.google.gson.*;
import dev.bcrick.secondo.extension.StateCache;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.rpc.JsonRpcError;
import dev.bcrick.secondo.rpc.RpcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransactionHandlerTest {

    private JsonRpcDispatcher dispatcher;
    private int undoCallCount;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        undoCallCount = 0;

        // Register test handlers
        dispatcher.register("test/echo", params -> params.get("value"));
        dispatcher.register("test/add", params -> {
            int a = params.get("a").getAsInt();
            int b = params.get("b").getAsInt();
            return new JsonPrimitive(a + b);
        });
        dispatcher.register("test/fail", params -> {
            JsonObject data = new JsonObject();
            data.addProperty("reason", "test failure");
            throw new RpcException(-32099, "Intentional failure", data);
        });
        dispatcher.register("test/badParam", params -> {
            throw new IllegalArgumentException("missing 'x' parameter");
        });
        dispatcher.register("app/undo", params -> {
            undoCallCount++;
            return new JsonPrimitive("ok");
        });
        dispatcher.register("session/snapshot", params -> {
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("mock", true);
            return snapshot;
        });

        new TransactionHandler(dispatcher, new StateCache()).register(dispatcher);
    }

    // --- Registration ---

    @Test
    void registersTransactionMethod() {
        assertTrue(dispatcher.getRegisteredMethods().contains("session/transaction"));
    }

    // --- Successful transactions ---

    @Test
    void transactionWithSingleOp() {
        String response = handle("""
            {"operations":[{"method":"test/add","params":{"a":2,"b":3}}]}""");
        JsonObject result = parseResult(response);
        assertEquals(1, result.get("completedCount").getAsInt());
        assertEquals(5, result.getAsJsonArray("results").get(0).getAsInt());
    }

    @Test
    void transactionWithMultipleOps() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/echo","params":{"value":"hello"}},
                {"method":"test/add","params":{"a":10,"b":20}}
            ]}""");
        JsonObject result = parseResult(response);
        assertEquals(3, result.get("completedCount").getAsInt());
        JsonArray results = result.getAsJsonArray("results");
        assertEquals(3, results.size());
        assertEquals(3, results.get(0).getAsInt());
        assertEquals("hello", results.get(1).getAsString());
        assertEquals(30, results.get(2).getAsInt());
    }

    @Test
    void transactionWithNoParams() {
        // Operations with no params field should default to empty object
        String response = handle("""
            {"operations":[{"method":"test/echo","params":{"value":42}}]}""");
        JsonObject result = parseResult(response);
        assertEquals(1, result.get("completedCount").getAsInt());
    }

    @Test
    void transactionWithNullParams() {
        String response = handle("""
            {"operations":[{"method":"test/echo","params":null}]}""");
        JsonObject result = parseResult(response);
        assertEquals(1, result.get("completedCount").getAsInt());
    }

    // --- Error handling ---

    @Test
    void transactionStopsOnFirstError() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/fail","params":{}},
                {"method":"test/add","params":{"a":10,"b":20}}
            ]}""");
        JsonObject error = parseError(response);
        assertEquals(JsonRpcError.TRANSACTION_STEP_FAILED, error.get("code").getAsInt());

        JsonObject data = error.getAsJsonObject("data");
        assertEquals(1, data.get("completedCount").getAsInt());
        JsonArray results = data.getAsJsonArray("results");
        assertEquals(1, results.size());
        assertEquals(3, results.get(0).getAsInt());

        JsonObject stepError = data.getAsJsonObject("error");
        assertEquals(1, stepError.get("step").getAsInt());
        assertEquals("test/fail", stepError.get("method").getAsString());
    }

    @Test
    void transactionErrorOnFirstStep() {
        String response = handle("""
            {"operations":[
                {"method":"test/fail","params":{}},
                {"method":"test/add","params":{"a":1,"b":2}}
            ]}""");
        JsonObject error = parseError(response);
        JsonObject data = error.getAsJsonObject("data");
        assertEquals(0, data.get("completedCount").getAsInt());
        assertEquals(0, data.getAsJsonArray("results").size());
        assertEquals(0, data.getAsJsonObject("error").get("step").getAsInt());
    }

    @Test
    void transactionErrorOnLastStep() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/add","params":{"a":3,"b":4}},
                {"method":"test/fail","params":{}}
            ]}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertEquals(2, data.get("completedCount").getAsInt());
        assertEquals(2, data.getAsJsonArray("results").size());
        assertEquals(2, data.getAsJsonObject("error").get("step").getAsInt());
    }

    @Test
    void transactionHandlesIllegalArgumentException() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/badParam","params":{}}
            ]}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertEquals(1, data.get("completedCount").getAsInt());
        JsonObject stepError = data.getAsJsonObject("error");
        assertEquals(1, stepError.get("step").getAsInt());
        assertEquals(JsonRpcError.INVALID_PARAMS, stepError.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void transactionErrorOnUnknownMethod() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"nonexistent/method","params":{}}
            ]}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertEquals(1, data.get("completedCount").getAsInt());
    }

    // --- Pre/Post snapshot ---

    @Test
    void transactionWithPreSnapshot() {
        String response = handle("""
            {"operations":[{"method":"test/add","params":{"a":1,"b":2}}],
             "preSnapshot":true}""");
        JsonObject result = parseResult(response);
        assertTrue(result.has("preSnapshot"));
        assertTrue(result.getAsJsonObject("preSnapshot").has("transport"));
        assertFalse(result.has("postSnapshot"));
    }

    @Test
    void transactionWithPostSnapshot() {
        String response = handle("""
            {"operations":[{"method":"test/add","params":{"a":1,"b":2}}],
             "postSnapshot":true}""");
        JsonObject result = parseResult(response);
        assertFalse(result.has("preSnapshot"));
        assertTrue(result.has("postSnapshot"));
    }

    @Test
    void transactionWithBothSnapshots() {
        String response = handle("""
            {"operations":[{"method":"test/add","params":{"a":1,"b":2}}],
             "preSnapshot":true,"postSnapshot":true}""");
        JsonObject result = parseResult(response);
        assertTrue(result.has("preSnapshot"));
        assertTrue(result.has("postSnapshot"));
    }

    @Test
    void transactionErrorIncludesPostSnapshot() {
        String response = handle("""
            {"operations":[{"method":"test/fail","params":{}}],
             "postSnapshot":true}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertTrue(data.has("postSnapshot"));
    }

    // --- Rollback ---

    @Test
    void transactionRollbackOnError() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/add","params":{"a":3,"b":4}},
                {"method":"test/fail","params":{}}
            ],"rollback":"undoAll"}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertEquals(2, data.get("completedCount").getAsInt());
        assertTrue(data.has("rollback"));
        JsonObject rollback = data.getAsJsonObject("rollback");
        assertTrue(rollback.get("rolledBack").getAsBoolean());
        assertEquals(2, rollback.get("undoCount").getAsInt());
        assertEquals(2, undoCallCount);
    }

    @Test
    void transactionNoRollbackOnSuccess() {
        String response = handle("""
            {"operations":[{"method":"test/add","params":{"a":1,"b":2}}],
             "rollback":"undoAll"}""");
        JsonObject result = parseResult(response);
        assertFalse(result.has("rollback"));
        assertEquals(0, undoCallCount);
    }

    @Test
    void transactionNoRollbackWhenNotRequested() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/fail","params":{}}
            ]}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertFalse(data.has("rollback"));
        assertEquals(0, undoCallCount);
    }

    @Test
    void transactionRollbackOnFirstStepError() {
        // No completed steps = no rollback even if requested
        String response = handle("""
            {"operations":[{"method":"test/fail","params":{}}],
             "rollback":"undoAll"}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertFalse(data.has("rollback"));
        assertEquals(0, undoCallCount);
    }

    @Test
    void transactionRollbackOnIllegalArgumentError() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/badParam","params":{}}
            ],"rollback":"undoAll"}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertEquals(1, data.get("completedCount").getAsInt());
        assertTrue(data.has("rollback"));
        JsonObject rollback = data.getAsJsonObject("rollback");
        assertTrue(rollback.get("rolledBack").getAsBoolean());
        assertEquals(1, rollback.get("undoCount").getAsInt());
        assertEquals(1, undoCallCount);
    }

    @Test
    void transactionRollbackOnIllegalArgCountsCorrectly() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/add","params":{"a":3,"b":4}},
                {"method":"test/badParam","params":{}}
            ],"rollback":"undoAll"}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertEquals(2, data.get("completedCount").getAsInt());
        JsonObject rollback = data.getAsJsonObject("rollback");
        assertEquals(2, rollback.get("undoCount").getAsInt());
        assertEquals(2, undoCallCount);
    }

    @Test
    void transactionRollbackWithPostSnapshot() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":{"a":1,"b":2}},
                {"method":"test/fail","params":{}}
            ],"rollback":"undoAll","postSnapshot":true}""");
        JsonObject data = parseError(response).getAsJsonObject("data");
        assertTrue(data.has("rollback"));
        assertTrue(data.has("postSnapshot"));
        assertEquals(1, data.getAsJsonObject("rollback").get("undoCount").getAsInt());
    }

    // --- Validation ---

    @Test
    void transactionMissingOperations() {
        String response = handle("{}");
        JsonObject error = parseError(response);
        assertEquals(JsonRpcError.INVALID_PARAMS, error.get("code").getAsInt());
    }

    @Test
    void transactionEmptyOperations() {
        String response = handle("""
            {"operations":[]}""");
        JsonObject error = parseError(response);
        assertEquals(JsonRpcError.INVALID_PARAMS, error.get("code").getAsInt());
    }

    @Test
    void transactionInvalidOperationNotObject() {
        String response = handle("""
            {"operations":["not an object"]}""");
        JsonObject error = parseError(response);
        assertEquals(JsonRpcError.INVALID_PARAMS, error.get("code").getAsInt());
    }

    @Test
    void transactionInvalidParamsType_returnsError() {
        String response = handle("""
            {"operations":[
                {"method":"test/add","params":[1,2]}
            ]}""");
        JsonObject error = parseError(response);
        assertEquals(JsonRpcError.INVALID_PARAMS, error.get("code").getAsInt());
    }

    @Test
    void transactionOperationMissingMethod() {
        String response = handle("""
            {"operations":[{"params":{}}]}""");
        JsonObject error = parseError(response);
        assertEquals(JsonRpcError.INVALID_PARAMS, error.get("code").getAsInt());
    }

    // --- handleInternal tests ---

    @Test
    void handleInternalSuccess() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("a", 5);
        params.addProperty("b", 7);
        JsonElement result = dispatcher.handleInternal("test/add", params);
        assertEquals(12, result.getAsInt());
    }

    @Test
    void handleInternalThrowsOnUnknownMethod() {
        assertThrows(IllegalArgumentException.class,
            () -> dispatcher.handleInternal("nonexistent/method", new JsonObject()));
    }

    @Test
    void handleInternalPropagatesRpcException() {
        RpcException ex = assertThrows(RpcException.class,
            () -> dispatcher.handleInternal("test/fail", new JsonObject()));
        assertEquals(-32099, ex.getCode());
    }

    // --- Helpers ---

    private String handle(String params) {
        return dispatcher.handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"session/transaction\",\"params\":" + params + ",\"id\":1}");
    }

    private JsonObject parseResult(String response) {
        return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result");
    }

    private JsonObject parseError(String response) {
        return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("error");
    }
}
