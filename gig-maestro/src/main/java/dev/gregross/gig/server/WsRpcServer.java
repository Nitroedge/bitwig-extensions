package dev.gregross.gig.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;

public class WsRpcServer extends WebSocketServer {

    public static final Set<String> VALID_TOPICS = Set.of(
        "transport", "tracks", "scenes", "device", "clip", "master",
        "application", "arranger", "arrangement", "masterDevice",
        "browser", "arpeggiator", "noteLatch", "groove"
    );

    private final Function<String, CompletableFuture<String>> requestHandler;
    private final Set<WebSocket> clients = new CopyOnWriteArraySet<>();
    private final Map<WebSocket, Set<String>> subscriptions = new ConcurrentHashMap<>();

    public WsRpcServer(int port, Function<String, CompletableFuture<String>> requestHandler) {
        // Bind LOOPBACK explicitly, for the same reason as HttpRpcServer: the one-argument
        // InetSocketAddress(int) constructor is the WILDCARD address. This listener dispatches
        // the same RPC surface as the HTTP one, so loopback-binding only 8787 would have left
        // the whole exposure live on 8788. Do not revert to the one-argument form.
        super(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        this.requestHandler = requestHandler;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        subscriptions.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // Intercept subscription RPCs that need the WebSocket reference
        String intercepted = handleSubscriptionRpc(conn, message);
        if (intercepted != null) {
            if (conn.isOpen()) {
                conn.send(intercepted);
            }
            return;
        }

        CompletableFuture<String> future = requestHandler.apply(message);
        future.thenAccept(response -> {
            if (response != null && conn.isOpen()) {
                conn.send(response);
            }
        });
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            clients.remove(conn);
            subscriptions.remove(conn);
        }
    }

    @Override
    public void onStart() {
        // Server started
    }

    /**
     * Broadcast a delta to all connected clients, filtering by their subscriptions.
     * Clients with no subscription entry receive the full delta (backward compatible).
     */
    public void broadcastDelta(JsonObject delta) {
        JsonArray changed = delta.getAsJsonArray("changed");
        JsonObject data = delta.getAsJsonObject("data");

        for (WebSocket client : clients) {
            if (!client.isOpen()) continue;

            Set<String> topics = subscriptions.get(client);
            if (topics == null) {
                // No subscription — send full delta (backward compatible)
                JsonObject notification = wrapNotification(delta);
                client.send(notification.toString());
            } else {
                // Filter to subscribed topics
                JsonArray filteredChanged = new JsonArray();
                JsonObject filteredData = new JsonObject();
                for (JsonElement section : changed) {
                    String name = section.getAsString();
                    if (topics.contains(name)) {
                        filteredChanged.add(name);
                        filteredData.add(name, data.get(name));
                    }
                }
                if (!filteredChanged.isEmpty()) {
                    JsonObject filteredDelta = new JsonObject();
                    filteredDelta.add("changed", filteredChanged);
                    filteredDelta.add("data", filteredData);
                    JsonObject notification = wrapNotification(filteredDelta);
                    client.send(notification.toString());
                }
            }
        }
    }

    // --- Subscription management ---

    public void setSubscription(WebSocket conn, Set<String> topics) {
        subscriptions.put(conn, Collections.unmodifiableSet(new HashSet<>(topics)));
    }

    public void clearSubscription(WebSocket conn) {
        subscriptions.remove(conn);
    }

    public Set<String> getSubscription(WebSocket conn) {
        return subscriptions.getOrDefault(conn, null);
    }

    public int getClientCount() {
        return clients.size();
    }

    // --- Internal helpers ---

    private JsonObject wrapNotification(JsonObject params) {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "state/changed");
        notification.add("params", params);
        return notification;
    }

    /**
     * Handle subscription RPCs directly (they need the WebSocket reference).
     * Returns the JSON-RPC response string, or null if the message is not a subscription RPC.
     */
    String handleSubscriptionRpc(WebSocket conn, String message) {
        try {
            JsonObject request = JsonParser.parseString(message).getAsJsonObject();
            if (!request.has("method")) return null;
            String method = request.get("method").getAsString();
            JsonElement idEl = request.get("id");

            switch (method) {
                case "state/subscribe": {
                    JsonObject params = request.getAsJsonObject("params");
                    if (params == null || !params.has("topics")) {
                        return errorResponse(idEl, -32602, "missing 'topics' parameter");
                    }
                    JsonArray topicsArr = params.getAsJsonArray("topics");
                    Set<String> topics = new HashSet<>();
                    for (JsonElement t : topicsArr) {
                        String topic = t.getAsString();
                        if (!VALID_TOPICS.contains(topic)) {
                            return errorResponse(idEl, -32602,
                                "invalid topic '" + topic + "', valid: " + VALID_TOPICS);
                        }
                        topics.add(topic);
                    }
                    setSubscription(conn, topics);
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", true);
                    result.add("topics", topicsArr);
                    return successResponse(idEl, result);
                }
                case "state/unsubscribe": {
                    JsonObject params = request.getAsJsonObject("params");
                    if (params == null || !params.has("topics")) {
                        return errorResponse(idEl, -32602, "missing 'topics' parameter");
                    }
                    JsonArray topicsArr = params.getAsJsonArray("topics");
                    Set<String> current = subscriptions.getOrDefault(conn, new HashSet<>(VALID_TOPICS));
                    Set<String> updated = new HashSet<>(current);
                    for (JsonElement t : topicsArr) {
                        updated.remove(t.getAsString());
                    }
                    if (updated.isEmpty()) {
                        clearSubscription(conn);
                    } else {
                        setSubscription(conn, updated);
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", true);
                    JsonArray remaining = new JsonArray();
                    for (String t : updated) remaining.add(t);
                    result.add("topics", remaining);
                    return successResponse(idEl, result);
                }
                case "state/subscribeAll": {
                    clearSubscription(conn);
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", true);
                    result.addProperty("subscribed", "all");
                    return successResponse(idEl, result);
                }
                default:
                    return null; // Not a subscription RPC — let normal handler process it
            }
        } catch (Exception e) {
            return null; // Parse error — let normal handler deal with it
        }
    }

    private String successResponse(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("result", result);
        if (id != null) response.add("id", id);
        return response.toString();
    }

    private String errorResponse(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        if (id != null) response.add("id", id);
        return response.toString();
    }
}
