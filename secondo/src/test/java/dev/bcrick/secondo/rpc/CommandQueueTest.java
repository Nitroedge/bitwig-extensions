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

    // --- The arrival stamp, and the budget it makes measurable (29-REVIEW.md, WR-04) ---

    /**
     * A command drained on the flush it arrived on has spent almost none of the caller's wall.
     *
     * <p>Nothing here sleeps and no assertion can be satisfied by wall-clock time passing: the
     * stamp is supplied, so "how long did this wait?" is arithmetic over a number the test chose.
     */
    @Test
    void aCommandDrainedImmediatelyReportsANearlyUntouchedBudget() {
        long[] seen = new long[] {-1, -1};
        dispatcher.register("budget", params -> {
            seen[0] = dispatcher.queuedMs();
            seen[1] = dispatcher.remainingBudgetMs();
            return new JsonPrimitive("ok");
        });

        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"budget\",\"id\":1}");
        queue.drainAndExecute(dispatcher);

        assertTrue(seen[0] >= 0 && seen[0] < 250,
            "a command drained on arrival reported a queue wait of " + seen[0] + " ms");
        long floor = JsonRpcDispatcher.CALLER_WALL_MS - JsonRpcDispatcher.WALL_SAFETY_MARGIN_MS - 250;
        assertTrue(seen[1] > floor,
            "the remaining budget should be nearly the whole wall less the margin, was " + seen[1]);
    }

    /**
     * A command that waited reports the wait, proportionally, and the budget shrinks by it.
     *
     * <p>The stamp is back-dated by two seconds through the package-private enqueue overload. That
     * is the ONLY way to express this without a sleep, and a sleep would make the assertion a
     * statement about how busy the build machine was.
     */
    @Test
    void aCommandThatWaitedInTheQueueReportsAProportionallyLargerWait() {
        long[] seen = new long[] {-1, -1};
        dispatcher.register("budget", params -> {
            seen[0] = dispatcher.queuedMs();
            seen[1] = dispatcher.remainingBudgetMs();
            return new JsonPrimitive("ok");
        });

        long enqueuedNanos = System.nanoTime() - 2_000L * 1_000_000L;
        queue.enqueue("{\"jsonrpc\":\"2.0\",\"method\":\"budget\",\"id\":1}", enqueuedNanos);
        queue.drainAndExecute(dispatcher);

        assertTrue(seen[0] >= 2000 && seen[0] < 2250,
            "the two-second queue wait was not accounted for; reported " + seen[0] + " ms");
        assertEquals(JsonRpcDispatcher.CALLER_WALL_MS - JsonRpcDispatcher.WALL_SAFETY_MARGIN_MS
            - seen[0], seen[1],
            "the remaining budget must be the wall, less the margin, less the measured wait");
    }

    /**
     * The reading a handler acts on: a command that waited nearly the whole wall has less left
     * than a write path needs, and a handler that checks can decline rather than promise.
     *
     * <p>This asserts the READING, not a handler's use of it -- {@code macro/writeClip}'s actual
     * decline is pinned in {@code MacroHandlerDeferredResponseTest}. What is pinned here is that
     * the seam reports a budget small enough for that decision to be reachable at all, and that a
     * handler consulting it leaves the caller ANSWERED rather than owed.
     */
    @Test
    void aCommandThatWaitedOutMostOfTheWallLeavesTooLittleBudgetToPromiseAnAnswer() {
        dispatcher.register("defersIfItCan", params -> {
            // 550 ms is macro/writeClip's own worst case at this pin; the figure is restated here
            // rather than imported because MacroHandler's constant is private and this stub is a
            // consumer of the shape of the decision, not a second declaration of the number.
            if (dispatcher.remainingBudgetMs() >= 550) {
                dispatcher.deferCurrentResponse();
            }
            return new JsonPrimitive("answered-now");
        });

        long enqueuedNanos = System.nanoTime() - 4_600L * 1_000_000L;
        CompletableFuture<String> late = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"defersIfItCan\",\"id\":1}", enqueuedNanos);
        queue.drainAndExecute(dispatcher);

        assertTrue(late.isDone(),
            "a request with less of its own wall left than the work needs was still promised an"
                + " answer later -- which the caller would collect as a bodiless timeout");

        // The controlled opposite: the same handler, the same wall, a fresh arrival.
        CompletableFuture<String> prompt = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"defersIfItCan\",\"id\":2}");
        queue.drainAndExecute(dispatcher);
        assertFalse(prompt.isDone(),
            "the budget reading refused a deferral that had the whole wall available");
    }

    /**
     * A deferral and a notification must be distinguishable at this seam, and this is the test
     * that says so in one place.
     *
     * <p>The test above is the reason the deferral signal cannot be {@code null}: null is already
     * spoken for. It means notification, it completes the future with null, and
     * {@code HttpRpcServer} turns that into a bodiless HTTP 204. A null deferral would ship the
     * caller a 204 while the write was still in flight. Both halves are asserted here together so
     * the contrast is visible rather than spread across two classes.
     */
    @Test
    void deferredRequestStaysOutstandingWhileANotificationDoesNot() throws Exception {
        // A handler that claims its response and answers nothing now.
        dispatcher.register("defers", params -> {
            dispatcher.deferCurrentResponse();
            return new JsonPrimitive("discarded");
        });

        CompletableFuture<String> deferred = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"defers\",\"id\":1}");
        queue.drainAndExecute(dispatcher);
        assertFalse(deferred.isDone(),
            "a claimed response must leave its caller's future outstanding");

        CompletableFuture<String> notification = queue.enqueue(
            "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}");
        queue.drainAndExecute(dispatcher);
        assertTrue(notification.isDone(), "a notification must still complete immediately");
        assertNull(notification.get(1, TimeUnit.SECONDS));

        // And the two did not get confused for one another along the way.
        assertFalse(deferred.isDone(),
            "the deferred future was completed by the notification draining behind it");
    }
}
