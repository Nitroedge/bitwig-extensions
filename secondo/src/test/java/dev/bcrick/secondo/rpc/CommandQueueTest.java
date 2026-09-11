package dev.bcrick.secondo.rpc;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CommandQueueTest {

    private CommandQueue queue;
    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        queue = new CommandQueue();
        dispatcher = new JsonRpcDispatcher();
        dispatcher.register("ping", params -> new JsonPrimitive("pong"));
        dispatcher.register("add", params -> {
            int a = params.get("a").getAsInt();
            int b = params.get("b").getAsInt();
            return new JsonPrimitive(a + b);
        });
    }

    @Test
    void enqueueAndDrainProducesResponse() throws Exception {
        CompletableFuture<String> future = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}");

        assertFalse(future.isDone());

        int count = queue.drainAndExecute(dispatcher);

        assertEquals(1, count);
        assertTrue(future.isDone());
        String response = future.get(1, TimeUnit.SECONDS);
        assertTrue(response.contains("\"pong\""));
    }

    @Test
    void multipleCommandsDrainInFifoOrder() throws Exception {
        CompletableFuture<String> f1 = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"add\",\"params\":{\"a\":1,\"b\":2},\"id\":1}");
        CompletableFuture<String> f2 = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"add\",\"params\":{\"a\":3,\"b\":4},\"id\":2}");
        CompletableFuture<String> f3 = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":3}");

        int count = queue.drainAndExecute(dispatcher);

        assertEquals(3, count);
        assertTrue(f1.get(1, TimeUnit.SECONDS).contains("3"));
        assertTrue(f2.get(1, TimeUnit.SECONDS).contains("7"));
        assertTrue(f3.get(1, TimeUnit.SECONDS).contains("pong"));
    }

    @Test
    void drainOnEmptyQueueReturnsZero() {
        assertEquals(0, queue.drainAndExecute(dispatcher));
    }

    @Test
    void isEmptyReflectsState() {
        assertTrue(queue.isEmpty());
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}");
        assertFalse(queue.isEmpty());
        queue.drainAndExecute(dispatcher);
        assertTrue(queue.isEmpty());
    }

    @Test
    void notificationCompletesWithNull() throws Exception {
        CompletableFuture<String> future = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"); // no id = notification

        queue.drainAndExecute(dispatcher);

        assertTrue(future.isDone());
        assertNull(future.get(1, TimeUnit.SECONDS));
    }
}
