package dev.bcrick.secondo.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for RpcClient that records calls instead of making HTTP requests.
 */
class FakeRpcClient extends RpcClient {

    private final List<RpcCall> calls = new ArrayList<>();
    private JsonElement defaultResult = new JsonPrimitive("ok");
    private String defaultRawResult = "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":1}";

    FakeRpcClient() {
        super("localhost", 0); // base URL never used
    }

    @Override
    JsonElement call(String method, JsonObject params) {
        calls.add(new RpcCall(method, params));
        return defaultResult;
    }

    @Override
    String callRaw(String requestJson) {
        calls.add(new RpcCall("__raw__", null));
        return defaultRawResult;
    }

    void setDefaultResult(JsonElement result) {
        this.defaultResult = result;
    }

    void setDefaultRawResult(String rawResult) {
        this.defaultRawResult = rawResult;
    }

    String getLastMethod() {
        if (calls.isEmpty()) return null;
        return calls.get(calls.size() - 1).method;
    }

    JsonObject getLastParams() {
        if (calls.isEmpty()) return null;
        return calls.get(calls.size() - 1).params;
    }

    List<RpcCall> getCalls() {
        return calls;
    }

    void reset() {
        calls.clear();
    }

    static class RpcCall {
        final String method;
        final JsonObject params;

        RpcCall(String method, JsonObject params) {
            this.method = method;
            this.params = params;
        }
    }
}
