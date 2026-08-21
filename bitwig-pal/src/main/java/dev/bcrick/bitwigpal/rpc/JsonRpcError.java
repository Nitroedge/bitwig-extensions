package dev.bcrick.bitwigpal.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class JsonRpcError {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int TRANSACTION_STEP_FAILED = -32010;

    private JsonRpcError() {}

    public static JsonObject create(int code, String message, JsonElement data) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        if (data != null) {
            error.add("data", data);
        }
        return error;
    }

    public static JsonObject create(int code, String message) {
        return create(code, message, null);
    }
}
