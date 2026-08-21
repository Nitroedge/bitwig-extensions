package dev.bcrick.bitwigpal.cli;

import com.google.gson.JsonElement;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(name = "snapshot", description = "Get the current session snapshot from Bitwig Studio.")
class SnapshotCommand implements Runnable {

    @ParentCommand
    private BitwigPalCli parent;

    @Override
    public void run() {
        try {
            RpcClient client = parent.createClient();
            JsonElement result = client.call("session/snapshot", null);
            System.out.println(RpcClient.format(result, parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
