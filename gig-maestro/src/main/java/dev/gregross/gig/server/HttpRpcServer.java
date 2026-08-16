package dev.gregross.gig.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class HttpRpcServer {

    private final HttpServer server;
    private static final long TIMEOUT_MS = 5000;

    /**
     * The loopback authorities this extension serves from. A POST carrying an {@code Origin}
     * header outside this set is refused before the handler is reached.
     *
     * Declared here as well as in {@link WsRpcServer} rather than shared: the two listeners have
     * no common base class, and making the HTTP server depend on the WebSocket server's constant
     * (or the reverse) points the dependency the wrong way for a security boundary that each
     * listener enforces on its own terms. The duplication is three string literals and
     * {@code WsRpcServerTest} pins the two sets as equal, so they cannot drift apart silently.
     *
     * Deliberately NOT configurable and NOT read from an environment variable: a configurable
     * allow-list on an unauthenticated surface is a new way to open it.
     */
    static final java.util.Set<String> ALLOWED_ORIGINS = java.util.Set.of(
        "http://127.0.0.1:8787",
        "http://localhost:8787",
        "http://[::1]:8787"
    );

    /**
     * Loopback authorities accepted in the {@code Host} header, compared PORT-INSENSITIVELY.
     *
     * {@code localhost} is in the set because that is what the smoke scripts actually send —
     * {@code _helpers.sh} sets {@code BASE=http://localhost:8787}, so {@code curl} emits
     * {@code Host: localhost:8787} — and {@code 127.0.0.1} is in it because that is what the
     * Python client sends ({@code rpc_client.ENGINE_BASE_URL = "http://127.0.0.1:8787"}). Both
     * were read from those files, not assumed; dropping either breaks a real consumer.
     */
    static final java.util.Set<String> ALLOWED_HOSTS = java.util.Set.of(
        "127.0.0.1", "localhost", "[::1]", "::1"
    );

    public HttpRpcServer(int port, Function<String, CompletableFuture<String>> requestHandler) throws IOException {
        // Bind LOOPBACK explicitly. The one-argument InetSocketAddress(int) constructor
        // is the WILDCARD address, which exposed this unauthenticated DAW control surface
        // to the whole local network. Do not revert to the one-argument form.
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        server.createContext("/rpc", exchange -> handleRpc(exchange, requestHandler));
        // DECIDED, not overlooked: /health, /docs and /openapi.json get NO Origin or Host guard.
        // They are read-only — they mutate nothing and disclose no project state — and the
        // Scalar docs UI is itself served from /docs on this origin, so requiring a Host or
        // Origin on them would break the docs page for no gain. The guards live on the mutating
        // path (handleRpc, which the catch-all below also routes to) where the harm is.
        server.createContext("/health", this::handleHealth);
        server.createContext("/docs", this::handleDocs);
        server.createContext("/openapi.json", this::handleOpenApiSpec);
        // Catch-all: route any POST to the RPC handler (enables Scalar "Try It"
        // which sends to paths like /transport/play instead of /rpc)
        server.createContext("/", exchange -> handleRpc(exchange, requestHandler));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(1);
    }

    private void handleRpc(HttpExchange exchange, Function<String, CompletableFuture<String>> requestHandler) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        // --- Header guards, ahead of the body read and ahead of requestHandler.apply ---
        //
        // These run on EVERY POST, not just /rpc, because the catch-all context registered at
        // "/" routes any POST to this same method — a check that only guarded /rpc would be
        // bypassed by posting to /transport/play.

        // ORIGIN. Withholding Access-Control-Allow-Origin (see addCorsHeaders) withholds the
        // RESPONSE from a cross-origin reader; it does not stop a CORS-safelisted simple POST
        // being DELIVERED and EXECUTED. This is the check that refuses one.
        //
        // ABSENCE IS ACCEPTANCE, for the same reason as WsRpcServer: no in-project client sends
        // an Origin header — not the Python httpx client, not the CLI, not the smoke scripts —
        // and refusing absence would break every consumer this server has. A browser is the only
        // thing that attaches Origin unbidden, so refusing a present-and-unlisted value refuses
        // exactly the class of caller the threat is about, and authenticates nobody.
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank() && !ALLOWED_ORIGINS.contains(origin.trim())) {
            sendResponse(exchange, 403, refusal("origin not allowed"));
            return;
        }

        // HOST. The DNS-rebinding guard. The loopback bind stops a remote packet ever arriving;
        // it does NOT stop a browser being tricked, by an attacker-controlled name that resolves
        // to 127.0.0.1, into sending a loopback-destined request under that name. Pinning Host to
        // a loopback authority closes that path. What it does NOT close: an attacker who can
        // already run a process on this machine is unaffected — that is the localhost-only,
        // no-authentication posture PROJECT.md states for v1, unchanged by this check.
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !host.isBlank() && !ALLOWED_HOSTS.contains(hostAuthority(host))) {
            sendResponse(exchange, 403, refusal("host not allowed"));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) {
            sendResponse(exchange, 400, "{\"error\":\"Empty body\"}");
            return;
        }

        try {
            CompletableFuture<String> future = requestHandler.apply(body);
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (response == null) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else {
                sendResponse(exchange, 200, response);
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    /**
     * The authority of a Host header, port stripped. IPv6 literals are bracketed
     * ({@code [::1]:8787}), so the bracket has to be honoured before the port colon is sought.
     * Lower-cased because host names are case-insensitive.
     */
    private static String hostAuthority(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close < 0 ? h : h.substring(0, close + 1);
        }
        int colon = h.indexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    /**
     * A JSON-RPC-shaped refusal. Deliberately says nothing beyond the fact that the request was
     * refused: no method list, no state. That a server is listening on this port is already
     * disclosed by the port itself.
     */
    private static String refusal(String reason) {
        return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\""
            + reason + "\"},\"id\":null}";
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        sendResponse(exchange, 200, "{\"status\":\"ok\",\"version\":\"0.1.0\"}");
    }

    private void handleDocs(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        serveClasspathResource(exchange, "/docs/api.html", "text/html");
    }

    private void handleOpenApiSpec(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        serveClasspathResource(exchange, "/docs/openapi.json", "application/json");
    }

    private void serveClasspathResource(HttpExchange exchange, String path, String contentType) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                sendResponse(exchange, 404, "{\"error\":\"Resource not found\"}");
                return;
            }
            byte[] bytes = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void addCorsHeaders(HttpExchange exchange) {
        // NO Access-Control-Allow-Origin header. It was previously "*". Removing it is worth
        // doing, but be precise about what it buys, because the earlier version of this comment
        // claimed more than the code delivers and a wrong comment on a security boundary is
        // worse than none:
        //
        // 1. Withholding this header WITHHOLDS THE RESPONSE from a cross-origin reader. It does
        //    NOT PREVENT DELIVERY: a CORS-safelisted simple POST is still delivered to this
        //    server and still EXECUTED, and the browser merely discards what comes back. On a
        //    destructive surface like arrangerClip/* — no undo — execution is the whole harm;
        //    whether the attacker gets to read the reply is beside the point.
        // 2. What actually REFUSES such a request is the Origin check at the head of handleRpc,
        //    which returns 403 before requestHandler.apply is reached, together with the Host
        //    check beside it that closes the DNS-rebinding path.
        // 3. The WEBSOCKET LISTENER IS EXEMPT FROM CORS ENTIRELY (and from the same-origin
        //    policy), so none of this reaches it. It is guarded separately, by the handshake
        //    refusal in WsRpcServer.onWebsocketHandshakeReceivedAsServer.
        //
        // The only in-project browser consumer is the Scalar docs UI served from /docs by this
        // same server, which is SAME-ORIGIN and needs no CORS grant at all. If a genuine
        // cross-origin consumer is ever added, echo a specific allow-listed origin here — never
        // the wildcard — and add it to ALLOWED_ORIGINS above, or the Origin check will refuse it.
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    /** The address this server is actually bound to. Exposed so tests can assert loopback. */
    InetSocketAddress getBoundAddress() {
        return server.getAddress();
    }
}
