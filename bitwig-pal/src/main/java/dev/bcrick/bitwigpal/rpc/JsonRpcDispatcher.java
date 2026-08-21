package dev.bcrick.bitwigpal.rpc;

import com.google.gson.*;

import java.util.*;

public class JsonRpcDispatcher {

    private final Map<String, MethodHandler> handlers = new LinkedHashMap<>();
    private final Gson gson = new Gson();

    public void register(String method, MethodHandler handler) {
        handlers.put(method, handler);
    }

    public Set<String> getRegisteredMethods() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    /**
     * Execute a registered handler directly and return its result.
     * Used by session/transaction and MacroHandler to call handlers without JSON serialization.
     *
     * @throws RpcException if the handler throws one
     * @throws IllegalArgumentException if the method is not registered or params are invalid
     */
    public JsonElement handleInternal(String method, JsonObject params) throws Exception {
        MethodHandler handler = handlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Method not found: " + method);
        }
        return handler.handle(params);
    }

    /**
     * Handle a raw JSON-RPC request string.
     * Returns null for notifications (requests without an id).
     * Returns a JSON string for normal requests or batch requests.
     */
    public String handle(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            return gson.toJson(JsonRpcResponse.error(
                JsonNull.INSTANCE, JsonRpcError.PARSE_ERROR, "Parse error"));
        }

        if (parsed.isJsonArray()) {
            return handleBatch(parsed.getAsJsonArray());
        }

        if (parsed.isJsonObject()) {
            JsonObject result = handleSingle(parsed.getAsJsonObject());
            return result == null ? null : gson.toJson(result);
        }

        return gson.toJson(JsonRpcResponse.error(
            JsonNull.INSTANCE, JsonRpcError.INVALID_REQUEST, "Invalid Request"));
    }

    private String handleBatch(JsonArray batch) {
        if (batch.isEmpty()) {
            return gson.toJson(JsonRpcResponse.error(
                JsonNull.INSTANCE, JsonRpcError.INVALID_REQUEST, "Invalid Request: empty batch"));
        }

        JsonArray responses = new JsonArray();
        for (JsonElement element : batch) {
            if (!element.isJsonObject()) {
                responses.add(JsonRpcResponse.error(
                    JsonNull.INSTANCE, JsonRpcError.INVALID_REQUEST, "Invalid Request"));
                continue;
            }
            JsonObject result = handleSingle(element.getAsJsonObject());
            if (result != null) {
                responses.add(result);
            }
        }

        if (responses.isEmpty()) {
            return null; // all notifications
        }
        return gson.toJson(responses);
    }

    private JsonObject handleSingle(JsonObject request) {
        // Validate jsonrpc field
        JsonElement jsonrpcField = request.get("jsonrpc");
        if (jsonrpcField == null || !"2.0".equals(jsonrpcField.getAsString())) {
            JsonElement id = request.get("id");
            return JsonRpcResponse.error(
                id != null ? id : JsonNull.INSTANCE,
                JsonRpcError.INVALID_REQUEST, "Invalid Request: missing or invalid jsonrpc version");
        }

        // Validate method field
        JsonElement methodField = request.get("method");
        if (methodField == null || !methodField.isJsonPrimitive() || !methodField.getAsJsonPrimitive().isString()) {
            JsonElement id = request.get("id");
            return JsonRpcResponse.error(
                id != null ? id : JsonNull.INSTANCE,
                JsonRpcError.INVALID_REQUEST, "Invalid Request: missing or invalid method");
        }

        String method = methodField.getAsString();
        JsonElement idField = request.get("id");
        boolean isNotification = (idField == null);

        // Get params (default to empty object)
        JsonObject params;
        JsonElement paramsField = request.get("params");
        if (paramsField == null || paramsField.isJsonNull()) {
            params = new JsonObject();
        } else if (paramsField.isJsonObject()) {
            params = paramsField.getAsJsonObject();
        } else {
            if (isNotification) return null;
            return JsonRpcResponse.error(idField, JsonRpcError.INVALID_PARAMS,
                "Invalid params: must be an object");
        }

        // Look up handler
        MethodHandler handler = handlers.get(method);
        if (handler == null) {
            if (isNotification) return null;
            return JsonRpcResponse.error(idField, JsonRpcError.METHOD_NOT_FOUND,
                "Method not found: " + method);
        }

        // Execute
        try {
            JsonElement result = handler.handle(params);
            if (isNotification) return null;
            return JsonRpcResponse.success(idField, result);
        } catch (RpcException e) {
            if (isNotification) return null;
            return JsonRpcResponse.errorWithData(idField, e.getCode(), e.getMessage(), e.getData());
        } catch (IllegalArgumentException e) {
            if (isNotification) return null;
            return JsonRpcResponse.error(idField, JsonRpcError.INVALID_PARAMS,
                "Invalid params: " + e.getMessage());
        } catch (Exception e) {
            if (isNotification) return null;
            return JsonRpcResponse.error(idField, JsonRpcError.INTERNAL_ERROR,
                "Internal error: " + e.getMessage());
        }
    }
}
