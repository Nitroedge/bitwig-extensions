package dev.bcrick.secondo.server;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class ServerManager {

    private HttpRpcServer httpServer;
    private WsRpcServer wsServer;

    public void start(int httpPort, Function<String, CompletableFuture<String>> requestHandler) throws IOException {
        httpServer = new HttpRpcServer(httpPort, requestHandler);
        httpServer.start();

        int wsPort = httpPort + 1;
        wsServer = new WsRpcServer(wsPort, requestHandler);
        wsServer.start();
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (wsServer != null) {
            try {
                wsServer.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            wsServer = null;
        }
    }

    public void broadcastDelta(JsonObject delta) {
        if (wsServer != null) {
            wsServer.broadcastDelta(delta);
        }
    }

    public int getWsClientCount() {
        return wsServer != null ? wsServer.getClientCount() : 0;
    }
}
