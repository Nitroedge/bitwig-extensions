package dev.bcrick.bitwigpal.rpc;

import java.util.concurrent.CompletableFuture;

public class RpcCommand {

    private final String requestJson;
    private final CompletableFuture<String> responseFuture;

    public RpcCommand(String requestJson) {
        this.requestJson = requestJson;
        this.responseFuture = new CompletableFuture<>();
    }

    public String getRequestJson() {
        return requestJson;
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
