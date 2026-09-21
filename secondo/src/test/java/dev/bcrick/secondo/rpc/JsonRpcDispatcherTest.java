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
    void preservesExplicitNullMembersInSingleAndBatchResponses() {
        dispatcher.register("cold", params -> {
            JsonObject result = new JsonObject();
            result.add("activated", JsonNull.INSTANCE);
            result.addProperty("observed", false);
            return result;
        });
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"cold\",\"id\":1}";
        JsonObject single = JsonParser.parseString(dispatcher.handle(request))
            .getAsJsonObject().getAsJsonObject("result");
        assertTrue(single.has("activated"));
        assertTrue(single.get("activated").isJsonNull());
        JsonObject batch = JsonParser.parseString(dispatcher.handle("[" + request + "]"))
            .getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("result");
        assertTrue(batch.has("activated"));
        assertTrue(batch.get("activated").isJsonNull());
    }

    /**
     * WR-08, the other half of the blast radius. Six of this file's twenty-five
     * JsonNull.INSTANCE sites are error-envelope ids, and they are the JSON-RPC 2.0 id null the
     * spec requires when a request could not be parsed far enough to have an id. Gson omits
     * JsonNull members by default, so before serializeNulls() those envelopes shipped with NO id
     * member at all -- a spec violation the flag fixed as a side effect of a device-schema
     * decision. Asserted here so a serializer-level change is caught rather than reasoned about.
     */
    @Test
    void errorEnvelopesCarryAnExplicitNullIdWhenTheRequestHadNone() {
        JsonObject parseError = JsonParser.parseString(dispatcher.handle("{invalid json"))
            .getAsJsonObject();
        assertTrue(parseError.has("id"), "a parse-error envelope must carry an id member");
        assertTrue(parseError.get("id").isJsonNull());

        JsonObject notAnObject = JsonParser.parseString(dispatcher.handle("\"a string\""))
            .getAsJsonObject();
        assertTrue(notAnObject.has("id"));
        assertTrue(notAnObject.get("id").isJsonNull());

        JsonObject emptyBatch = JsonParser.parseString(dispatcher.handle("[]"))
            .getAsJsonObject();
        assertTrue(emptyBatch.has("id"));
        assertTrue(emptyBatch.get("id").isJsonNull());
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

    // --- The defer-then-throw withdrawal (29-REVIEW.md, IN-04) ---

    /**
     * A handler that claims its response and then throws answers its caller EXACTLY ONCE, and the
     * work it had already started does not keep running behind that answer.
     *
     * <p>WHY THIS TEST DID NOT EXIST BEFORE. {@code deferCurrentResponse} appeared in exactly one
     * place under {@code src/test} -- {@code CommandQueueTest}'s notification contrast -- and no
     * engine test drove a handler that deferred and then threw. The withdrawal in
     * {@code handleSingle}'s {@code finally} was therefore reasoned about rather than exercised:
     * without it, {@code handle(String)} answers the queue with the deferral sentinel,
     * {@code drainAndExecute} binds a registry entry nothing will ever complete, and the error the
     * caller is owed is discarded in favour of a hang.
     *
     * <p>WHAT IS ASSERTED, and against what. Both properties are read off OBSERVABLE effects
     * rather than internals, in the style the rest of this class uses: the answer is the returned
     * wire string, and "no queued work kept running" is a counter the stubbed work increments.
     * The dispatcher has no way to stop a handler's side effects retroactively -- which is exactly
     * why {@code MacroHandler} puts its enqueue last and says so in a comment at the line.
     */
    @Test
    void aHandlerThatDefersAndThenThrowsAnswersOnceAndLeavesNoWorkRunning() {
        int[] queuedWorkRuns = new int[] {0};
        Runnable queuedWork = () -> queuedWorkRuns[0]++;

        dispatcher.register("defersThenThrows", params -> {
            dispatcher.deferCurrentResponse();
            // Nothing is queued before the throw, because this handler is written the way
            // MacroHandler.handleWriteClip now is: everything that can fail happens first.
            if (params.has("queueFirst") && params.get("queueFirst").getAsBoolean()) {
                queuedWork.run();
            }
            throw new IllegalStateException("threw after claiming its response");
        });

        String response = dispatcher.handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"defersThenThrows\",\"id\":7}");

        // ONE answer, and it is a real one. Not the deferral sentinel, which would have stranded
        // the caller, and not null, which already means notification and would ship an HTTP 204.
        assertNotNull(response, "the caller was answered with the notification signal");
        assertFalse(JsonRpcDispatcher.isDeferred(response),
            "the deferral claim was not withdrawn, so the caller would wait for an answer that"
                + " nothing will ever produce");
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        assertEquals(7, json.get("id").getAsInt(), "the error must carry the caller's own id");
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32603, error.get("code").getAsInt());
        assertTrue(error.get("message").getAsString().contains("threw after claiming"),
            error.get("message").getAsString());

        // And no work is running behind that answer.
        assertEquals(0, queuedWorkRuns[0],
            "an error answer was returned while work the handler had started kept going --"
                + " which is the blind retry this project forbids");

        // The claim really was withdrawn from the registry, not merely unused: a SECOND request
        // that defers legitimately must get its own entry back. If the stale claim were still
        // there, this one would bind to it.
        dispatcher.register("defersCleanly", params -> {
            dispatcher.deferCurrentResponse();
            return new JsonPrimitive("discarded");
        });
        String deferred = dispatcher.handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"defersCleanly\",\"id\":8}");
        assertTrue(JsonRpcDispatcher.isDeferred(deferred),
            "a later legitimate deferral was refused, so the withdrawn claim left the registry"
                + " in a state the next request inherited");
    }

    /**
     * The contrast that makes the assertion above mean something: work queued BEFORE the throw
     * does keep running, and the caller still gets the error.
     *
     * <p>This is the shape IN-04 warns about, built deliberately so the rule is visible rather
     * than assumed. The dispatcher cannot undo a side effect; only ordering inside the handler
     * can. That is why {@code MacroHandler.handleWriteClip} ends with its enqueue and carries the
     * one-sentence rule at that line.
     */
    @Test
    void workQueuedBeforeAThrowKeepsRunningBehindTheErrorAnswerWhichIsWhyOrderingIsTheFix() {
        int[] queuedWorkRuns = new int[] {0};
        dispatcher.register("queuesThenThrows", params -> {
            dispatcher.deferCurrentResponse();
            queuedWorkRuns[0]++;
            throw new IllegalStateException("threw after queueing");
        });

        String response = dispatcher.handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"queuesThenThrows\",\"id\":9}");

        JsonObject error = JsonParser.parseString(response).getAsJsonObject()
            .getAsJsonObject("error");
        assertEquals(-32603, error.get("code").getAsInt());
        assertEquals(1, queuedWorkRuns[0],
            "the dispatcher was expected to be UNABLE to unwind the handler's side effect;"
                + " if it now can, the ordering rule in handleWriteClip can be relaxed");
    }

    @Test
    void getRegisteredMethodsReturnsAll() {
        assertTrue(dispatcher.getRegisteredMethods().contains("echo"));
        assertTrue(dispatcher.getRegisteredMethods().contains("add"));
        assertTrue(dispatcher.getRegisteredMethods().contains("fail"));
        assertEquals(3, dispatcher.getRegisteredMethods().size());
    }
}
