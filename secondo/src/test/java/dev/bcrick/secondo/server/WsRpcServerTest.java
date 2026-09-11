package dev.bcrick.secondo.server;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WsRpcServerTest {

    private static final int TEST_PORT = 19788;
    private WsRpcServer server;

    /**
     * Counts every requestHandler invocation. The Origin gap is specifically that the handler is
     * REACHABLE from a browser-originated connection, so the proof of the refusal has to be that
     * the handler was never reached — a closed connection or a status code proves nothing about
     * what ran before it closed. Reset per test by @BeforeEach.
     */
    private AtomicInteger handlerCalls;

    @BeforeEach
    void setUp() throws Exception {
        handlerCalls = new AtomicInteger();
        server = new WsRpcServer(TEST_PORT, body -> {
            handlerCalls.incrementAndGet();
            if (body.contains("\"echo\"")) {
                return CompletableFuture.completedFuture(
                    "{\"jsonrpc\":\"2.0\",\"result\":\"pong\",\"id\":1}");
            }
            return CompletableFuture.completedFuture(null); // notification
        });
        server.start();
        Thread.sleep(200); // let server bind
    }

    /** A client whose handshake carries the given Origin, or none when {@code origin} is null. */
    private WebSocketClient clientWithOrigin(String origin, CompletableFuture<String> received) {
        Map<String, String> headers = origin == null ? Map.of() : Map.of("Origin", origin);
        return new WebSocketClient(URI.create("ws://localhost:" + TEST_PORT), headers) {
            @Override public void onOpen(ServerHandshake handshake) {}
            @Override public void onMessage(String message) {
                if (received != null) received.complete(message);
            }
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception ex) {}
        };
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(1000);
    }

    @Test
    void listenerBindsLoopbackOnlyAndNotTheWildcardAddress() {
        // Same defect and same repair as HttpRpcServer: super(new InetSocketAddress(port)) is
        // the WILDCARD constructor. This listener dispatches the same RPC surface, so fixing
        // only 8787 would have left the whole exposure live on 8788.
        assertTrue(server.getAddress().getAddress().isLoopbackAddress(),
            "WebSocket RPC listener must bind loopback, not the wildcard address; bound to "
                + server.getAddress());
    }

    // --- Origin refusal on the handshake (Gap 6) ---

    @Test
    void refusesAWebSocketHandshakeCarryingAForeignOrigin() throws Exception {
        WebSocketClient client = clientWithOrigin("https://evil.example", null);

        boolean opened = client.connectBlocking(2, TimeUnit.SECONDS);
        assertFalse(opened,
            "a handshake carrying a foreign Origin must be refused before the connection opens");

        try {
            client.send("{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":1}");
        } catch (Exception expected) {
            // There is no open connection to send on — that is the refusal working.
        }
        Thread.sleep(300);

        assertEquals(0, handlerCalls.get(),
            "requestHandler must never be invoked on a foreign-Origin connection");
        assertEquals(0, server.getClientCount(),
            "a refused handshake must never reach onOpen, so no client is registered");
    }

    @Test
    void acceptsAHandshakeCarryingNoOriginBecauseNoInProjectClientSendsOne() throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        WebSocketClient client = clientWithOrigin(null, received);

        assertTrue(client.connectBlocking(2, TimeUnit.SECONDS),
            "absence is acceptance: every in-project client sends no Origin at all");
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":1}");

        assertTrue(received.get(2, TimeUnit.SECONDS).contains("\"pong\""));
        assertEquals(1, handlerCalls.get(),
            "a no-Origin client must reach the handler exactly as before");
        client.closeBlocking();
    }

    @Test
    void acceptsAHandshakeCarryingALoopbackOrigin() throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        WebSocketClient client = clientWithOrigin("http://127.0.0.1:8787", received);

        assertTrue(client.connectBlocking(2, TimeUnit.SECONDS),
            "an allow-listed loopback origin must connect");
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":1}");

        assertTrue(received.get(2, TimeUnit.SECONDS).contains("\"pong\""));
        assertEquals(1, handlerCalls.get());
        client.closeBlocking();
    }

    @Test
    void aForeignOriginCannotReachTheSubscriptionPathEither() throws Exception {
        // onMessage takes TWO paths: handleSubscriptionRpc first, then requestHandler. A refusal
        // that only guarded the second would leave state/subscribe wide open.
        WebSocketClient client = clientWithOrigin("https://evil.example", null);

        client.connectBlocking(2, TimeUnit.SECONDS);
        try {
            client.send("{\"jsonrpc\":\"2.0\",\"method\":\"state/subscribe\","
                + "\"params\":{\"topics\":[\"transport\"]},\"id\":1}");
        } catch (Exception expected) {
            // No open connection to send on.
        }
        Thread.sleep(300);

        assertEquals(0, server.getClientCount(),
            "no connection was registered, so the subscription map — which is keyed by "
                + "connections that only exist after onOpen — cannot have been touched");
        assertEquals(0, handlerCalls.get());
    }

    @Test
    void clientReceivesResponse() throws Exception {
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        WebSocketClient client = new WebSocketClient(URI.create("ws://localhost:" + TEST_PORT)) {
            @Override public void onOpen(ServerHandshake handshake) {}
            @Override public void onMessage(String message) { responseFuture.complete(message); }
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception ex) { responseFuture.completeExceptionally(ex); }
        };

        client.connectBlocking(2, TimeUnit.SECONDS);
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":1}");

        String response = responseFuture.get(2, TimeUnit.SECONDS);
        assertTrue(response.contains("\"pong\""));
        client.closeBlocking();
    }

    @Test
    void notificationSendsNoResponse() throws Exception {
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        WebSocketClient client = new WebSocketClient(URI.create("ws://localhost:" + TEST_PORT)) {
            @Override public void onOpen(ServerHandshake handshake) {}
            @Override public void onMessage(String message) { responseFuture.complete(message); }
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception ex) {}
        };

        client.connectBlocking(2, TimeUnit.SECONDS);
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}");

        Thread.sleep(300);
        assertFalse(responseFuture.isDone());
        client.closeBlocking();
    }

    @Test
    void broadcastReachesClients() throws Exception {
        CompletableFuture<String> broadcastFuture = new CompletableFuture<>();

        WebSocketClient client = new WebSocketClient(URI.create("ws://localhost:" + TEST_PORT)) {
            @Override public void onOpen(ServerHandshake handshake) {}
            @Override public void onMessage(String message) { broadcastFuture.complete(message); }
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception ex) {}
        };

        client.connectBlocking(2, TimeUnit.SECONDS);
        Thread.sleep(100);

        com.google.gson.JsonObject delta = new com.google.gson.JsonObject();
        com.google.gson.JsonArray changed = new com.google.gson.JsonArray();
        changed.add("transport");
        delta.add("changed", changed);
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        com.google.gson.JsonObject transportData = new com.google.gson.JsonObject();
        transportData.addProperty("isPlaying", true);
        data.add("transport", transportData);
        delta.add("data", data);
        server.broadcastDelta(delta);

        String received = broadcastFuture.get(2, TimeUnit.SECONDS);
        assertTrue(received.contains("state/changed"));
        assertTrue(received.contains("transport"));
        client.closeBlocking();
    }

    @Test
    void tracksClientCount() throws Exception {
        assertEquals(0, server.getClientCount());

        WebSocketClient client = new WebSocketClient(URI.create("ws://localhost:" + TEST_PORT)) {
            @Override public void onOpen(ServerHandshake handshake) {}
            @Override public void onMessage(String message) {}
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception ex) {}
        };

        client.connectBlocking(2, TimeUnit.SECONDS);
        Thread.sleep(100);
        assertEquals(1, server.getClientCount());

        client.closeBlocking();
        Thread.sleep(200);
        assertEquals(0, server.getClientCount());
    }

    // --- Subscription management (unit tests) ---

    @Test
    void subscription_defaultIsNull() {
        org.java_websocket.WebSocket mockConn = org.mockito.Mockito.mock(org.java_websocket.WebSocket.class);
        assertNull(server.getSubscription(mockConn));
    }

    @Test
    void subscription_setAndGet() {
        org.java_websocket.WebSocket mockConn = org.mockito.Mockito.mock(org.java_websocket.WebSocket.class);
        server.setSubscription(mockConn, java.util.Set.of("transport", "tracks"));
        java.util.Set<String> topics = server.getSubscription(mockConn);
        assertNotNull(topics);
        assertEquals(2, topics.size());
        assertTrue(topics.contains("transport"));
        assertTrue(topics.contains("tracks"));
    }

    @Test
    void subscription_clearRemovesEntry() {
        org.java_websocket.WebSocket mockConn = org.mockito.Mockito.mock(org.java_websocket.WebSocket.class);
        server.setSubscription(mockConn, java.util.Set.of("transport"));
        server.clearSubscription(mockConn);
        assertNull(server.getSubscription(mockConn));
    }

    @Test
    void handleSubscribe_validTopics_setsSubscription() {
        org.java_websocket.WebSocket mockConn = org.mockito.Mockito.mock(org.java_websocket.WebSocket.class);
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"state/subscribe\",\"params\":{\"topics\":[\"transport\",\"device\"]},\"id\":1}";
        String response = server.handleSubscriptionRpc(mockConn, request);
        assertNotNull(response);
        assertTrue(response.contains("\"ok\":true"));
        assertEquals(java.util.Set.of("transport", "device"), server.getSubscription(mockConn));
    }

    @Test
    void handleSubscribe_invalidTopic_returnsError() {
        org.java_websocket.WebSocket mockConn = org.mockito.Mockito.mock(org.java_websocket.WebSocket.class);
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"state/subscribe\",\"params\":{\"topics\":[\"bogus\"]},\"id\":1}";
        String response = server.handleSubscriptionRpc(mockConn, request);
        assertNotNull(response);
        assertTrue(response.contains("-32602"));
    }

    @Test
    void handleUnsubscribe_removesTopics() {
        org.java_websocket.WebSocket mockConn = org.mockito.Mockito.mock(org.java_websocket.WebSocket.class);
        server.setSubscription(mockConn, new java.util.HashSet<>(java.util.Set.of("transport", "tracks", "device")));
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"state/unsubscribe\",\"params\":{\"topics\":[\"tracks\"]},\"id\":1}";
        String response = server.handleSubscriptionRpc(mockConn, request);
        assertNotNull(response);
        assertTrue(response.contains("\"ok\":true"));
        java.util.Set<String> remaining = server.getSubscription(mockConn);
        assertEquals(2, remaining.size());
        assertFalse(remaining.contains("tracks"));
    }

    @Test
    void handleSubscribeAll_clearsSubscription() {
        org.java_websocket.WebSocket mockConn = org.mockito.Mockito.mock(org.java_websocket.WebSocket.class);
        server.setSubscription(mockConn, java.util.Set.of("transport"));
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"state/subscribeAll\",\"params\":{},\"id\":1}";
        String response = server.handleSubscriptionRpc(mockConn, request);
        assertNotNull(response);
        assertTrue(response.contains("\"ok\":true"));
        assertNull(server.getSubscription(mockConn));
    }

    @Test
    void handleNonSubscriptionRpc_returnsNull() {
        org.java_websocket.WebSocket mockConn = org.mockito.Mockito.mock(org.java_websocket.WebSocket.class);
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"transport/play\",\"params\":{},\"id\":1}";
        String response = server.handleSubscriptionRpc(mockConn, request);
        assertNull(response);
    }

    @Test
    void validTopics_containsAll14Sections() {
        assertEquals(14, WsRpcServer.VALID_TOPICS.size());
        assertTrue(WsRpcServer.VALID_TOPICS.contains("transport"));
        assertTrue(WsRpcServer.VALID_TOPICS.contains("groove"));
        assertTrue(WsRpcServer.VALID_TOPICS.contains("masterDevice"));
    }
}
