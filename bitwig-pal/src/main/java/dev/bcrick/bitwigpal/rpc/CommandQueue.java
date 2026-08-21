package dev.bcrick.bitwigpal.rpc;

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
     */
    public int drainAndExecute(JsonRpcDispatcher dispatcher) {
        int count = 0;
        RpcCommand command;
        while ((command = queue.poll()) != null) {
            try {
                String response = dispatcher.handle(command.getRequestJson());
                command.complete(response);
            } catch (Exception e) {
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
