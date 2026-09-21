package dev.bcrick.secondo.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CommandQueue {

    private final ConcurrentLinkedQueue<RpcCommand> queue = new ConcurrentLinkedQueue<>();

    /**
     * Enqueue a JSON-RPC request from a network thread.
     * Returns a future that will be completed on the session thread.
     *
     * <p>The command is STAMPED here with {@code System.nanoTime()}, because this is the instant
     * the request arrived and therefore the instant the caller's own five-second wall started.
     * Everything downstream that has to promise an answer inside that wall measures from this
     * stamp and never from the moment the handler happened to run (29-REVIEW.md, WR-04). See
     * {@link RpcCommand#getEnqueuedNanos()} and {@link JsonRpcDispatcher#remainingBudgetMs()}.
     */
    public CompletableFuture<String> enqueue(String requestJson) {
        long enqueuedNanos = System.nanoTime();
        return enqueue(requestJson, enqueuedNanos);
    }

    /**
     * Enqueue with an explicit arrival stamp.
     *
     * <p>Package-private and visible for tests ONLY. A test that needs to express "this command
     * waited two seconds in the queue" must be able to back-date the stamp: the alternative is a
     * sleep, and a suite whose assertions are satisfied by wall-clock time passing is a suite that
     * goes flaky under load rather than one that measures anything.
     */
    CompletableFuture<String> enqueue(String requestJson, long enqueuedNanos) {
        RpcCommand command = new RpcCommand(requestJson, enqueuedNanos);
        queue.add(command);
        return command.getResponseFuture();
    }

    /**
     * Drain all pending commands and execute them on the current thread.
     * Called from Bitwig's flush() on the Control Surface Session thread.
     *
     * <p>A command's future is NOT always completed here. When the handler claimed its response
     * (Phase 29, D-29-04), the dispatcher answers with an identity sentinel instead of a wire
     * string; the command is handed to the dispatcher's pending registry and its future is
     * completed later, from a task scheduled on a subsequent flush, with the real outcome. Note
     * that "later" can also mean "already": a handler whose own catch resolved the deferral
     * inside its call stack has a buffered answer that {@code bindDeferred} flushes immediately.
     *
     * <p>{@code null} keeps its existing meaning -- notification, completed with null, HTTP 204 --
     * and the sentinel is deliberately not null so the two cannot be confused.
     */
    public int drainAndExecute(JsonRpcDispatcher dispatcher) {
        int count = 0;
        RpcCommand command;
        while ((command = queue.poll()) != null) {
            try {
                // The arrival stamp travels with the request, so a handler deciding whether
                // it can honour a deferral reads the budget that is actually left on the
                // CALLER'S wall rather than the one its own execution started with (WR-04).
                String response = dispatcher.handle(
                    command.getRequestJson(), command.getEnqueuedNanos());
                if (JsonRpcDispatcher.isDeferred(response)) {
                    dispatcher.bindDeferred(command);
                } else {
                    command.complete(response);
                }
            } catch (Exception e) {
                // A handler that deferred and then threw past the dispatcher would otherwise
                // leave a registry entry a later deadline task would try to complete.
                dispatcher.discardDeferred();
                command.fail(e);
            }
            count++;
        }
        return count;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
