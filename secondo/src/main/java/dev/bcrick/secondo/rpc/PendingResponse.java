package dev.bcrick.secondo.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A response a handler has claimed the right to answer LATER, after it has returned.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A handler runs inside Bitwig's {@code flush()} on the Control Surface Session thread
 * ({@code CommandQueue.java:20-22}), and {@code host.scheduleTask} schedules onto that SAME
 * thread. A handler that blocked on its own deferred verify would block the flush the verify is
 * waiting for -- a deadlock, not a slow path. So the answer cannot be produced before the handler
 * returns. What CAN outlive the handler is the {@link RpcCommand}'s {@code CompletableFuture},
 * and this class is the handle that reaches it from a task scheduled one or more flushes later.
 *
 * <h2>Why completion can arrive BEFORE the command is bound</h2>
 *
 * <p>The ordering is not "handler returns, queue binds, task completes". {@code MacroHandler}'s
 * {@code startNextJob} has its own {@code catch} that calls {@code failJob} INSIDE the handler's
 * call stack, so a deferral can be resolved while {@code drainAndExecute} has not yet reached
 * {@code command.complete(...)} and has therefore not yet called
 * {@link JsonRpcDispatcher#bindDeferred(RpcCommand)}. That is why {@link #completedJson} buffers
 * the finished wire string for {@link #bind(RpcCommand)} to flush, rather than asserting that a
 * command is present. The buffering is load-bearing, not defensive.
 *
 * <h2>Idempotence</h2>
 *
 * <p>Both completion methods are one-shot. After the first, they return without effect. A
 * deadline task firing behind a real answer therefore cannot overwrite it, and the four terminal
 * paths of a write job cannot double-answer a caller between them.
 *
 * <h2>Threading</h2>
 *
 * <p>No synchronisation, deliberately: every path that touches an instance -- the handler that
 * claims it, the scheduled task that completes it, and {@code drainAndExecute} that binds it --
 * runs on the one Control Surface Session thread named above. See the {@code pending} field in
 * {@link JsonRpcDispatcher} for the same rationale stated once more where the registry lives.
 */
public final class PendingResponse {

    /**
     * The dispatcher owns the ONLY Gson configured with {@code serializeNulls()}
     * ({@code JsonRpcDispatcher.java:63}), and the explicit-null contract documented there is
     * what the Python readers are written against. A second serialiser here would silently drop
     * every {@code JsonNull} this response carries, so the wire string is always built through
     * the dispatcher.
     */
    private final JsonRpcDispatcher dispatcher;

    /** The JSON-RPC id this response must carry back. Never null: a notification cannot defer. */
    private final JsonElement id;

    /** Bound by {@code drainAndExecute} once it sees the deferral sentinel. Null until then. */
    private RpcCommand command;

    /** A finished wire string produced before {@link #bind} ran. Null once flushed. */
    private String completedJson;

    private boolean completed;

    PendingResponse(JsonRpcDispatcher dispatcher, JsonElement id) {
        this.dispatcher = dispatcher;
        this.id = id;
    }

    /**
     * Attach the queued command whose future this response owes an answer to.
     *
     * <p>If the answer is already known -- see the class comment on synchronous resolution -- it
     * is flushed immediately and the future completes inside this call.
     */
    public void bind(RpcCommand command) {
        this.command = command;
        if (completedJson != null) {
            command.complete(completedJson);
            completedJson = null;
        }
    }

    /** True once an answer has been produced, whether or not a command was bound to receive it. */
    public boolean isCompleted() {
        return completed;
    }

    /** Answer with a JSON-RPC success carrying {@code result}. No-op if already answered. */
    public void completeSuccess(JsonElement result) {
        complete(JsonRpcResponse.success(id, result));
    }

    /**
     * Answer with a JSON-RPC error. No-op if already answered.
     *
     * @param data the error's {@code data} member, or null to omit it
     */
    public void completeError(int code, String message, JsonObject data) {
        complete(JsonRpcResponse.errorWithData(id, code, message, data));
    }

    private void complete(JsonObject response) {
        if (completed) {
            return;
        }
        completed = true;
        String json = dispatcher.serialize(response);
        if (command != null) {
            command.complete(json);
        } else {
            completedJson = json;
        }
    }
}
