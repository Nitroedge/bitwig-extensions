package dev.bcrick.bitwigpal.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

@FunctionalInterface
public interface MethodHandler {
    JsonElement handle(JsonObject params) throws Exception;
}
