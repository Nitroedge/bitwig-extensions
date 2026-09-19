package dev.bcrick.secondo.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CommandQueue {

    private final ConcurrentLinkedQueue<RpcCommand> queue = new ConcurrentLinkedQueue<>();

    /**
     * Enqueue a JSON-RPC request from a network thread.
     * Returns a future that will be completed on the session thread.
     */
    public CompletableFuture<String> enqueue(String requestJson) {
        RpcCommand command = new RpcCommand(requestJson);
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
                String response = dispatcher.handle(command.getRequestJson());
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
