package dev.bcrick.bitwigpal.handlers;

import com.google.gson.*;
import dev.bcrick.bitwigpal.extension.StateCache;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
import dev.bcrick.bitwigpal.rpc.JsonRpcError;
import dev.bcrick.bitwigpal.rpc.RpcException;

public class TransactionHandler {

    private final JsonRpcDispatcher dispatcher;
    private final StateCache stateCache;

    public TransactionHandler(JsonRpcDispatcher dispatcher, StateCache stateCache) {
        this.dispatcher = dispatcher;
        this.stateCache = stateCache;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        dispatcher.register("session/transaction", this::handleTransaction);
    }

    private JsonElement handleTransaction(JsonObject params) {
        // Validate operations array
        JsonElement opsElement = params.get("operations");
        if (opsElement == null || !opsElement.isJsonArray()) {
            throw new IllegalArgumentException("Missing or invalid 'operations' array");
        }
        JsonArray operations = opsElement.getAsJsonArray();
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("'operations' array must not be empty");
        }

        // Parse options
        boolean preSnapshot = params.has("preSnapshot") && params.get("preSnapshot").getAsBoolean();
        boolean postSnapshot = params.has("postSnapshot") && params.get("postSnapshot").getAsBoolean();
        String rollback = params.has("rollback") ? params.get("rollback").getAsString() : null;

        JsonObject response = new JsonObject();

        // Capture pre-snapshot if requested
        if (preSnapshot) {
            response.add("preSnapshot", stateCache.getSnapshot());
        }

        // Execute operations sequentially
        JsonArray results = new JsonArray();
        int completedCount = 0;

        for (int i = 0; i < operations.size(); i++) {
            JsonElement opElement = operations.get(i);
            if (!opElement.isJsonObject()) {
                throw new IllegalArgumentException("Operation at index " + i + " is not an object");
            }
            JsonObject op = opElement.getAsJsonObject();

            // Validate operation fields
            JsonElement methodElement = op.get("method");
            if (methodElement == null || !methodElement.isJsonPrimitive()) {
                throw new IllegalArgumentException("Operation at index " + i + " missing 'method'");
            }
            String method = methodElement.getAsString();

            JsonObject opParams;
            JsonElement paramsElement = op.get("params");
            if (paramsElement == null || paramsElement.isJsonNull()) {
                opParams = new JsonObject();
            } else if (paramsElement.isJsonObject()) {
                opParams = paramsElement.getAsJsonObject();
            } else {
                throw new IllegalArgumentException("Operation at index " + i + " has invalid 'params'");
            }

            try {
                JsonElement result = dispatcher.handleInternal(method, opParams);
                results.add(result);
                completedCount++;
            } catch (RpcException e) {
                // Stop on first error — build error response
                response.add("results", results);
                response.addProperty("completedCount", completedCount);

                JsonObject error = new JsonObject();
                error.addProperty("step", i);
                error.addProperty("method", method);
                JsonObject errorDetail = new JsonObject();
                errorDetail.addProperty("code", e.getCode());
                errorDetail.addProperty("message", e.getMessage());
                if (e.getData() != null) {
                    errorDetail.add("data", e.getData());
                }
                error.add("error", errorDetail);
                response.add("error", error);

                // Rollback if requested
                if ("undoAll".equals(rollback) && completedCount > 0) {
                    int undoCount = performRollback(completedCount);
                    JsonObject rollbackInfo = new JsonObject();
                    rollbackInfo.addProperty("rolledBack", true);
                    rollbackInfo.addProperty("undoCount", undoCount);
                    response.add("rollback", rollbackInfo);
                }

                // Capture post-snapshot even on error
                if (postSnapshot) {
                    response.add("postSnapshot", stateCache.getSnapshot());
                }

                throw new RpcException(
                    JsonRpcError.TRANSACTION_STEP_FAILED,
                    "Transaction failed at step " + i + " (" + method + "): " + e.getMessage(),
                    response
                );
            } catch (IllegalArgumentException e) {
                // Param validation errors also stop execution
                response.add("results", results);
                response.addProperty("completedCount", completedCount);

                JsonObject error = new JsonObject();
                error.addProperty("step", i);
                error.addProperty("method", method);
                JsonObject errorDetail = new JsonObject();
                errorDetail.addProperty("code", JsonRpcError.INVALID_PARAMS);
                errorDetail.addProperty("message", e.getMessage());
                error.add("error", errorDetail);
                response.add("error", error);

                if ("undoAll".equals(rollback) && completedCount > 0) {
                    int undoCount = performRollback(completedCount);
                    JsonObject rollbackInfo = new JsonObject();
                    rollbackInfo.addProperty("rolledBack", true);
                    rollbackInfo.addProperty("undoCount", undoCount);
                    response.add("rollback", rollbackInfo);
                }

                if (postSnapshot) {
                    response.add("postSnapshot", stateCache.getSnapshot());
                }

                throw new RpcException(
                    JsonRpcError.TRANSACTION_STEP_FAILED,
                    "Transaction failed at step " + i + " (" + method + "): " + e.getMessage(),
                    response
                );
            } catch (Exception e) {
                response.add("results", results);
                response.addProperty("completedCount", completedCount);

                JsonObject error = new JsonObject();
                error.addProperty("step", i);
                error.addProperty("method", method);
                JsonObject errorDetail = new JsonObject();
                errorDetail.addProperty("code", JsonRpcError.INTERNAL_ERROR);
                errorDetail.addProperty("message", e.getMessage());
                error.add("error", errorDetail);
                response.add("error", error);

                if ("undoAll".equals(rollback) && completedCount > 0) {
                    int undoCount = performRollback(completedCount);
                    JsonObject rollbackInfo = new JsonObject();
                    rollbackInfo.addProperty("rolledBack", true);
                    rollbackInfo.addProperty("undoCount", undoCount);
                    response.add("rollback", rollbackInfo);
                }

                if (postSnapshot) {
                    response.add("postSnapshot", stateCache.getSnapshot());
                }

                throw new RpcException(
                    JsonRpcError.TRANSACTION_STEP_FAILED,
                    "Transaction failed at step " + i + " (" + method + "): " + e.getMessage(),
                    response
                );
            }
        }

        // All operations succeeded
        response.add("results", results);
        response.addProperty("completedCount", completedCount);

        if (postSnapshot) {
            response.add("postSnapshot", stateCache.getSnapshot());
        }

        return response;
    }

    private int performRollback(int count) {
        JsonObject emptyParams = new JsonObject();
        int undone = 0;
        for (int i = 0; i < count; i++) {
            try {
                dispatcher.handleInternal("app/undo", emptyParams);
                undone++;
            } catch (Exception e) {
                // Best-effort — stop if undo fails
                break;
            }
        }
        return undone;
    }
}
