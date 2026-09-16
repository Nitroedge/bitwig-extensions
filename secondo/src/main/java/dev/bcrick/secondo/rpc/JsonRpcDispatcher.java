package dev.bcrick.secondo.rpc;

import com.google.gson.*;

import java.util.*;

public class JsonRpcDispatcher {

    private final Map<String, MethodHandler> handlers = new LinkedHashMap<>();

    /**
     * WHY THIS SERIALIZER EMITS NULLS, AND EXACTLY WHAT THAT REACHES (25-REVIEW WR-08).
     * The flag is on the builder line at the bottom of this comment; it is named there once, and
     * only there, so a grep for it lands on the code rather than on the prose about the code.
     *
     * <p>WHY. Decision D-25-15: the device read publishes a TOTAL schema, in which a field the
     * engine could not observe is present with the value null rather than absent. Gson omits
     * JsonNull members by default, which would have made "unobserved" and "not part of this
     * shape" the same wire fact. Two readers in secondo's src/secondo/tools/device.py are
     * written directly against that guarantee: :1903 tests "parentPath" not in raw_node, and
     * :1940 builds its missing set from key absence. The flag is load-bearing, not incidental.
     *
     * <p>THE BLAST RADIUS, MEASURED rather than reasoned about. This one line changes the wire
     * for every JsonNull.INSTANCE the engine has ever written, on all 328 registered methods,
     * not only the Phase 25 ones. Measured at pin c906934 by
     * grep -c "JsonNull.INSTANCE" over secondo/src/main/java: 25 sites in four files.
     *
     * <ul>
     *   <li>12 in StateCache.java: trackBank itemCount and lastReturnedPath; a track row's
     *       parentIndex, activated, effectiveActivated, position, legacyName and legacyPosition;
     *       cursorTrackPosition and cursorSceneIndex; lastWriteClipRefusal; and the master row's
     *       activated.</li>
     *   <li>6 in DeviceHandler.java: the two cold-field helpers addString and addBoolean, a
     *       top-level device's parentPath, slotNames, topLevelDeviceCount and
     *       lastReturnedPath.</li>
     *   <li>1 in TrackHandler.java: track/setActivated's cold observed activation.</li>
     *   <li>6 in this file: the JSON-RPC 2.0 error-envelope ids below.</li>
     * </ul>
     *
     * <p>7 of those 25 pre-date Phase 25, measured at pin 3b53206: lastWriteClipRefusal and the
     * six envelope ids. 18 were introduced by Phase 25. One pre-existing site is GONE rather
     * than changed: the master row's colour null was removed by Phase 25 and master colour is
     * now always an object, which is why 3b53206 greps 8 and this pin greps 25 rather than 26.
     *
     * <p>2026-09-16, plan 26-06 (Phase 26 browser build, 0.2.4): the browser read side
     * (browser/getState, browser/getFilters, browser/getResults) and the new session/snapshot
     * masterChain section now publish explicit nulls for every value not yet observed
     * (D-26-12, D-26-20). Most of them go through addProperty with a null boxed value, which
     * Gson writes as the same JSON null but which the literal grep does not count. Recounted
     * over secondo/src/main/java after this line was written: grep -o "JsonNull.INSTANCE" finds
     * 30 occurrences in four files: 27 code sites (14 in StateCache.java, +2 on the 12 above:
     * contentTypeNames and a masterChain deviceNames slot; 6 in DeviceHandler.java; 1 in
     * TrackHandler.java; 6 envelope ids here) plus 3 mentions in this comment.
     *
     * <p>THE READER RULE that follows from it. A Python reader tests value is None, NEVER key
     * membership. The two device.py sites named above are the sole exception and were written
     * for this flag deliberately. The six envelope ids are the JSON-RPC 2.0 id null the spec
     * requires when a request could not be parsed enough to have an id: omitting them was a
     * spec violation, and this flag is what made them correct. One further compensation exists
     * and is deliberate: getArrangerClipState at StateCache.java uses if (field != null)
     * addProperty(...) to keep genuinely absent arranger fields absent (its "F1" note).
     */
    private final Gson gson = new GsonBuilder().serializeNulls().create();

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
