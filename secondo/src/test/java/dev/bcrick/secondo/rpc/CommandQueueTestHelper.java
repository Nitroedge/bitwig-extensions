package dev.bcrick.secondo.rpc;

import java.util.concurrent.CompletableFuture;

/**
 * Test-only reach into {@link CommandQueue}'s package-private arrival-stamp overload, for tests
 * that live outside the {@code rpc} package.
 *
 * <p>The same idiom, and for the same reason, as {@code StateCacheTestHelper}: the production
 * surface stays exactly what production needs -- {@code enqueue(String)} and nothing else -- while
 * a test in another package can still express "this request arrived two seconds ago".
 *
 * <p>WHY BACK-DATING IS THE ONLY HONEST WAY TO SAY THAT. The alternative is to sleep, and a suite
 * whose assertions are satisfied by wall-clock time passing measures how busy the build machine
 * was rather than what the engine does. {@code MacroHandlerDeferredResponseTest}'s class comment
 * makes the same commitment about scheduler time; this is its equivalent for arrival time.
 */
public final class CommandQueueTestHelper {

    private CommandQueueTestHelper() {}

    /** Enqueue a request as though it had arrived at {@code enqueuedNanos}. */
    public static CompletableFuture<String> enqueueAsOf(CommandQueue queue, String requestJson,
                                                        long enqueuedNanos) {
        return queue.enqueue(requestJson, enqueuedNanos);
    }

    /** A monotonic instant {@code agoMs} milliseconds in the past. */
    public static long arrivedMsAgo(long agoMs) {
        return System.nanoTime() - agoMs * 1_000_000L;
    }
}
