package dev.bcrick.secondo.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal JSON-RPC 2.0 client that POSTs to Secondo's HTTP server.
 */
class RpcClient {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String baseUrl;
    private final HttpClient httpClient;
    private int nextId = 1;

    RpcClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port + "/rpc";
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Send a JSON-RPC request and return the result.
     */
    JsonElement call(String method, JsonObject params) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("method", method);
        request.add("params", params != null ? params : new JsonObject());
        request.addProperty("id", nextId++);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        JsonObject rpcResponse = JsonParser.parseString(response.body()).getAsJsonObject();

        if (rpcResponse.has("error")) {
            JsonObject error = rpcResponse.getAsJsonObject("error");
            throw new IOException("RPC error " + error.get("code") + ": " + error.get("message").getAsString());
        }

        return rpcResponse.get("result");
    }

    /**
     * Send a raw JSON-RPC request string and return the raw response.
     */
    String callRaw(String requestJson) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    static String format(JsonElement element, boolean pretty) {
        return pretty ? PRETTY_GSON.toJson(element) : GSON.toJson(element);
    }

    static String formatRaw(String json, boolean pretty) {
        if (!pretty) return json;
        JsonElement el = JsonParser.parseString(json);
        return PRETTY_GSON.toJson(el);
    }
}
