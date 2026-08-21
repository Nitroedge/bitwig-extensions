package dev.bcrick.bitwigpal.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@Command(
    name = "watch",
    description = "Stream state changes via WebSocket. Runs until Ctrl+C.",
    mixinStandardHelpOptions = true
)
class WatchCommand implements Runnable {

    @ParentCommand
    BitwigPalCli parent;

    @Option(names = {"--topics"}, description = "Comma-separated topic filter (e.g., transport,device).", split = ",")
    List<String> topics;

    @Override
    public void run() {
        int wsPort = parent.port + 1;
        String wsUrl = "ws://" + parent.host + ":" + wsPort;
        CountDownLatch closeLatch = new CountDownLatch(1);

        WebSocketClient client = new WebSocketClient(URI.create(wsUrl)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                if (topics != null && !topics.isEmpty()) {
                    JsonObject request = new JsonObject();
                    request.addProperty("jsonrpc", "2.0");
                    request.addProperty("method", "state/subscribe");
                    JsonObject params = new JsonObject();
                    JsonArray topicsArr = new JsonArray();
                    for (String t : topics) {
                        topicsArr.add(t.trim());
                    }
                    params.add("topics", topicsArr);
                    request.add("params", params);
                    request.addProperty("id", 1);
                    send(request.toString());
                }
                System.err.println("Connected to " + wsUrl + (topics != null ? " (topics: " + topics + ")" : " (all topics)"));
            }

            @Override
            public void onMessage(String message) {
                if (parent.pretty) {
                    System.out.println(RpcClient.format(
                        com.google.gson.JsonParser.parseString(message), true));
                } else {
                    System.out.println(message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.err.println("Disconnected: " + reason);
                closeLatch.countDown();
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("Error: " + ex.getMessage());
                closeLatch.countDown();
            }
        };

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("\nDisconnecting...");
            client.close();
        }));

        try {
            client.connectBlocking();
            closeLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
