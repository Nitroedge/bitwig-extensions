package dev.bcrick.secondo.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class JsonParamValidator {

    private JsonParamValidator() {}

    public static int requireInt(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null) {
            throw new IllegalArgumentException("missing '" + key + "' parameter");
        }
        return el.getAsInt();
    }

    public static String requireString(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null) {
            throw new IllegalArgumentException("missing '" + key + "' parameter");
        }
        return el.getAsString();
    }

    public static boolean requireBoolean(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null) {
            throw new IllegalArgumentException("missing '" + key + "' parameter");
        }
        return el.getAsBoolean();
    }

    public static double requireDouble(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null) {
            throw new IllegalArgumentException("missing '" + key + "' parameter");
        }
        return el.getAsDouble();
    }

    public static int optionalInt(JsonObject params, String key, int defaultValue) {
        JsonElement el = params.get(key);
        if (el == null || el.isJsonNull()) {
            return defaultValue;
        }
        return el.getAsInt();
    }

    public static String optionalString(JsonObject params, String key, String defaultValue) {
        JsonElement el = params.get(key);
        if (el == null || el.isJsonNull()) {
            return defaultValue;
        }
        return el.getAsString();
    }

    public static JsonArray requireArray(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null || !el.isJsonArray()) {
            throw new IllegalArgumentException("Missing required param: " + key);
        }
        return el.getAsJsonArray();
    }
}
