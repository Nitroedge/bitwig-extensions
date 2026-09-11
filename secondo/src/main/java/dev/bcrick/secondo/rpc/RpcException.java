package dev.bcrick.secondo.rpc;

import com.google.gson.JsonObject;

/**
 * Structured JSON-RPC error with custom code and data payload.
 */
public class RpcException extends RuntimeException {

    private final int code;
    private final JsonObject data;

    public RpcException(int code, String message, JsonObject data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int getCode() { return code; }
    public JsonObject getData() { return data; }
}
