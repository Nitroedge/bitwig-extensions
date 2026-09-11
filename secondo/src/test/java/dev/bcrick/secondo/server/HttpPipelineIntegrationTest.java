package dev.bcrick.secondo.server;

import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.rpc.CommandQueue;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpPipelineIntegrationTest {

    private static final int TEST_PORT = 19888;
    private HttpRpcServer server;
    private ScheduledExecutorService drainExecutor;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher();
        dispatcher.register("echo", params -> new JsonPrimitive("pong"));
        dispatcher.register("add", params -> {
            int a = params.get("a").getAsInt();
            int b = params.get("b").getAsInt();
            return new JsonPrimitive(a + b);
        });

        CommandQueue queue = new CommandQueue();

        // Wire HTTP server to enqueue into CommandQueue
        server = new HttpRpcServer(TEST_PORT, queue::enqueue);
        server.start();

        // Simulate Bitwig's flush cycle — drain queue every 10ms
        drainExecutor = Executors.newSingleThreadScheduledExecutor();
        drainExecutor.scheduleAtFixedRate(
            () -> queue.drainAndExecute(dispatcher), 0, 10, TimeUnit.MILLISECONDS);
    }

    @AfterEach
    void tearDown() {
        server.stop();
        drainExecutor.shutdownNow();
    }

    @Test
    void postRpc_validMethod_returnsJsonRpcResponse() throws Exception {
        HttpResponse<String> response = post(
            "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{},\"id\":1}");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"pong\""));
        assertTrue(response.body().contains("\"id\":1"));
    }

    @Test
    void postRpc_unknownMethod_returnsMethodNotFound() throws Exception {
        HttpResponse<String> response = post(
            "{\"jsonrpc\":\"2.0\",\"method\":\"nonexistent\",\"params\":{},\"id\":1}");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("-32601"));
    }

    @Test
    void postRpc_invalidJson_returnsParseError() throws Exception {
        HttpResponse<String> response = post("not json at all");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("-32700"));
    }

    @Test
    void postRpc_notification_returns204() throws Exception {
        HttpResponse<String> response = post(
            "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{}}");

        assertEquals(204, response.statusCode());
    }

    @Test
    void postRpc_batchRequest_returnsArrayResponse() throws Exception {
        HttpResponse<String> response = post(
            "[{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{},\"id\":1},"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"add\",\"params\":{\"a\":2,\"b\":3},\"id\":2}]");

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.startsWith("["));
        assertTrue(body.contains("\"pong\""));
        assertTrue(body.contains("5"));
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + "/rpc"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
