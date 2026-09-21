package dev.bcrick.secondo.rpc;

import java.util.concurrent.CompletableFuture;

public class RpcCommand {

    private final String requestJson;
    private final CompletableFuture<String> responseFuture;

    /**
     * The monotonic instant at which this command was enqueued, i.e. the instant the request
     * ARRIVED on a network thread.
     *
     * <p>WHY IT IS RECORDED HERE RATHER THAN WHERE THE HANDLER RUNS (29-REVIEW.md, WR-04). Two
     * clocks bound a request and they start at different moments. The caller's wall --
     * {@code HttpRpcServer}'s {@code future.get(5000)} and the Python client's {@code read=5.0} --
     * starts when the request arrives. A handler starts only after the command has waited in
     * {@link CommandQueue} for the next {@code flush()}. Anything the handler measures from its
     * own start therefore promises nothing about the wall the caller is actually watching, and a
     * deferral armed off that clock can outlive it -- which is the bodiless HTTP 500 with no id
     * that D-29-06 calls strictly worse than answering slowly.
     *
     * <p>This stamp is the one instant BOTH ends share, so it is what the remaining budget is
     * measured from. See {@link JsonRpcDispatcher#remainingBudgetMs()}.
     */
    private final long enqueuedNanos;

    public RpcCommand(String requestJson) {
        this(requestJson, System.nanoTime());
    }

    public RpcCommand(String requestJson, long enqueuedNanos) {
        this.requestJson = requestJson;
        this.responseFuture = new CompletableFuture<>();
        this.enqueuedNanos = enqueuedNanos;
    }

    public String getRequestJson() {
        return requestJson;
    }

    /** The monotonic instant this command was enqueued. See the field comment for why it exists. */
    public long getEnqueuedNanos() {
        return enqueuedNanos;
    }

    public CompletableFuture<String> getResponseFuture() {
        return responseFuture;
    }

    public void complete(String responseJson) {
        responseFuture.complete(responseJson);
    }

    public void fail(Throwable ex) {
        responseFuture.completeExceptionally(ex);
    }
}
