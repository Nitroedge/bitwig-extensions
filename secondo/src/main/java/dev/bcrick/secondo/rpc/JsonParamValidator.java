package dev.bcrick.secondo.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class JsonParamValidator {

    private JsonParamValidator() {}

    /**
     * THE ONE SPELLING of "that parameter is not there".
     *
     * <p>Extracted by plan 31-06 so a caller outside this class -- {@code MacroHandler}'s
     * per-note expression validation, which has to name a note index as well as a field and so
     * cannot simply call {@code requireDouble} -- refuses in the SAME words rather than inventing
     * a second wording for the same fact. Two spellings of one refusal is a defect in waiting:
     * a reader at the other end of the wire has to learn both, and only one of them gets tested.
     */
    public static String missingMessage(String key) {
        return "missing '" + key + "' parameter";
    }

    public static int requireInt(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null) {
            throw new IllegalArgumentException(missingMessage(key));
        }
        return el.getAsInt();
    }

    public static String requireString(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null) {
            throw new IllegalArgumentException(missingMessage(key));
        }
        return el.getAsString();
    }

    public static boolean requireBoolean(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null) {
            throw new IllegalArgumentException(missingMessage(key));
        }
        return el.getAsBoolean();
    }

    public static double requireDouble(JsonObject params, String key) {
        JsonElement el = params.get(key);
        if (el == null) {
            throw new IllegalArgumentException(missingMessage(key));
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
