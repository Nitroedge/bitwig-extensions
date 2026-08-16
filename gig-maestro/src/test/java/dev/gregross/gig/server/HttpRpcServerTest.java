package dev.gregross.gig.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class HttpRpcServerTest {

    private static final int TEST_PORT = 19787;
    private HttpRpcServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        server = new HttpRpcServer(TEST_PORT, body -> {
            if (body.contains("\"echo\"")) {
                return CompletableFuture.completedFuture(
                    "{\"jsonrpc\":\"2.0\",\"result\":\"pong\",\"id\":1}");
            }
            return CompletableFuture.completedFuture(null); // notification
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + "/health"))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"ok\""));
        assertTrue(response.body().contains("\"version\":\"0.1.0\""));
    }

    @Test
    void rpcEndpointProcessesPost() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + "/rpc"))
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":1}"))
            .header("Content-Type", "application/json")
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"pong\""));
    }

    @Test
    void rpcNotificationReturns204() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + "/rpc"))
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}"))
            .header("Content-Type", "application/json")
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());
    }

    @Test
    void rpcRejectsGetMethod() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + "/rpc"))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }

    @Test
    void corsHeadersCarryNoAllowOriginGrant() throws Exception {
        // Was corsHeadersPresent, which asserted Access-Control-Allow-Origin: "*" and so
        // PINNED the defect in place. The wildcard let any page the user visited drive the
        // DAW cross-origin. The method/header lines remain (they are inert without an origin
        // grant and keep the OPTIONS preflight path's shape); the origin grant is gone.
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + "/health"))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
            "Access-Control-Allow-Origin must not be sent at all; a wildcard grant on this "
                + "unauthenticated port exposes the arrangerClip/* destructive surface to any "
                + "web page the user visits");
        assertEquals("POST, GET, OPTIONS",
            response.headers().firstValue("Access-Control-Allow-Methods").orElse(""));
    }

    @Test
    void listenerBindsLoopbackOnlyAndNotTheWildcardAddress() {
        // HttpServer.create(new InetSocketAddress(port), 0) — the ONE-argument form — is the
        // wildcard address, which published this unauthenticated DAW control surface to the
        // whole local network. Filed in phase 02-05, repaired in phase 06-09.
        assertTrue(server.getBoundAddress().getAddress().isLoopbackAddress(),
            "HTTP RPC listener must bind loopback, not the wildcard address; bound to "
                + server.getBoundAddress());
    }
}
