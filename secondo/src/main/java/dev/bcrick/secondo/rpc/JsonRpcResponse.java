package dev.bcrick.secondo.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class JsonRpcResponse {

    private JsonRpcResponse() {}

    public static JsonObject success(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("result", result);
        response.add("id", id);
        return response;
    }

    public static JsonObject error(JsonElement id, int code, String message) {
        return error(id, JsonRpcError.create(code, message));
    }

    public static JsonObject errorWithData(JsonElement id, int code, String message, JsonElement data) {
        return error(id, JsonRpcError.create(code, message, data));
    }

    public static JsonObject error(JsonElement id, JsonObject error) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("error", error);
        response.add("id", id);
        return response;
    }
}
