package dev.bcrick.bitwigpal.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpRpcServerTest {

    private static final int TEST_PORT = 19787;
    private HttpRpcServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * Counts every requestHandler invocation. The gap is that the handler is REACHABLE from a
     * browser-shaped POST, so the proof of the refusal has to be that the handler was never
     * reached — a 403 alone says nothing about what ran before the response was written.
     */
    private AtomicInteger handlerCalls;

    @BeforeEach
    void setUp() throws IOException {
        handlerCalls = new AtomicInteger();
        server = new HttpRpcServer(TEST_PORT, body -> {
            handlerCalls.incrementAndGet();
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

    // --- Origin and Host guards (Gap 6) ---

    private static final String ECHO = "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":1}";

    /**
     * A POST built by hand on a raw socket. java.net.http.HttpClient treats Host as a restricted
     * header and will not let a test set it, and the Host pin is precisely what has to be
     * exercised — so the request is written literally rather than through a client that
     * sanitises it.
     */
    private String rawPost(String path, String hostHeader, String originHeader, String body)
        throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", TEST_PORT)) {
            socket.setSoTimeout(3000);
            byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder()
                .append("POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n");
            if (originHeader != null) {
                req.append("Origin: ").append(originHeader).append("\r\n");
            }
            req.append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(payload.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
            socket.getOutputStream()
                .write(req.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            try {
                return new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.net.SocketException reset) {
                // A reset here means the connection died before this server answered, so nothing
                // about the guard under test was exercised -- which is a FAILURE and not a pass,
                // and it must say WHY it is unreadable rather than surfacing a bare stack trace.
                // Measured cause on the bitwig-pal validation machine, 2026-09-09: a machine-local
                // web shield resets a plaintext HTTP connection carrying certain Host names before
                // any listener sees a byte. See refusesARpcPostWhoseHostIsNotLoopback below.
                throw new AssertionError(
                    "the connection was reset before this server answered (Host: " + hostHeader
                        + (originHeader == null ? "" : ", Origin: " + originHeader)
                        + "), so the guard under test was never exercised. This is NOT an engine "
                        + "failure on its own: a machine-local network filter that blocks the "
                        + "header value used as a fixture produces exactly this. Reproduce with "
                        + "any minimal socket server on this port before changing engine code.",
                    reset);
            }
        }
    }


    @Test
    void refusesARpcPostCarryingAForeignOrigin() throws Exception {
        String response = rawPost("/rpc", "localhost:" + TEST_PORT, "https://evil.example", ECHO);

        assertTrue(response.startsWith("HTTP/1.1 403"),
            "a browser-shaped POST must be refused; got: " + response.lines().findFirst().orElse(""));
        assertEquals(0, handlerCalls.get(),
            "requestHandler must never be reached by a foreign-Origin POST — withholding "
                + "Access-Control-Allow-Origin only withholds the response, it does not stop "
                + "delivery and execution");
    }

    @Test
    void servesARpcPostCarryingNoOriginBecauseThatIsEveryInProjectClient() throws Exception {
        String response = rawPost("/rpc", "localhost:" + TEST_PORT, null, ECHO);

        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertTrue(response.contains("\"pong\""));
        assertEquals(1, handlerCalls.get(), "absence is acceptance");
    }

    @Test
    void refusesARpcPostWhoseHostIsNotLoopback() throws Exception {
        // DNS rebinding: the loopback bind cannot tell this apart from a legitimate request,
        // because the packet really does arrive on 127.0.0.1. The Host header is what gives it
        // away.
        //
        // THE NAME IS `attacker.example`, NOT `attacker.example.com`, AND THAT IS A MEASUREMENT
        // RATHER THAN A PREFERENCE. This case read `attacker.example.com` from 2026-08-16 until
        // 2026-09-09, when it began failing on the bitwig-pal validation machine with
        // `SocketException: Connection reset` inside rawPost -- never reaching an assertion. It
        // was proven NOT to be an engine defect: a fifteen-line Python socket server containing
        // none of this code, on the same port, was reset identically for that one Host value
        // while `attacker.example`, `rebound.example.com`, `example.com`, `not-loopback.invalid`,
        // `evil.example` and `192.0.2.1` all reached it and were answered. Something on that
        // machine (a web shield's name blocklist) kills a plaintext HTTP connection whose Host is
        // that specific FQDN, before the server sees a byte. The subject of this test is "a Host
        // that is not a loopback authority", and any non-loopback name proves it, so the fixture
        // moved to the RFC 2606 reserved `.example` TLD -- which is also exactly what
        // bitwig-pal's own `tests/test_transport_http.py` sends, so the two repositories now
        // agree on one fixture instead of drifting.
        String response = rawPost("/rpc", "attacker.example", null, ECHO);

        assertTrue(response.startsWith("HTTP/1.1 403"),
            "got: " + response.lines().findFirst().orElse(""));
        assertEquals(0, handlerCalls.get());
    }

    @Test
    void servesARpcPostWhoseHostIsTheLoopbackNameUsedByTheSmokeScripts() throws Exception {
        // _helpers.sh sets BASE=http://localhost:8787, so curl sends exactly this. The port
        // deliberately differs from TEST_PORT: the comparison is port-insensitive, and this is
        // what proves it.
        String response = rawPost("/rpc", "localhost:8787", null, ECHO);

        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertEquals(1, handlerCalls.get());
    }

    @Test
    void refusesAForeignOriginPostToTheCatchAllPathToo() throws Exception {
        // The "/" context routes ANY POST to the same handler (it is what makes Scalar's
        // "Try It" work), so a guard that only covered /rpc would be bypassed by posting here.
        String response = rawPost("/transport/play", "localhost:" + TEST_PORT,
            "https://evil.example", ECHO);

        assertTrue(response.startsWith("HTTP/1.1 403"),
            "got: " + response.lines().findFirst().orElse(""));
        assertEquals(0, handlerCalls.get());
    }

    @Test
    void bothListenersDeclareTheSameOriginAllowList() {
        // The two listeners declare ALLOWED_ORIGINS separately — they share no base class and
        // coupling one to the other would point the dependency the wrong way. This test is what
        // stops the two copies drifting apart on a security boundary.
        assertEquals(WsRpcServer.ALLOWED_ORIGINS, HttpRpcServer.ALLOWED_ORIGINS);
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
