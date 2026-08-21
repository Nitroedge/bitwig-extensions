package dev.bcrick.bitwigpal.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "rpc", description = "Send a raw JSON-RPC 2.0 request to Bitwig Pal.")
class RpcCommand implements Runnable {

    @ParentCommand
    private BitwigPalCli parent;

    @Parameters(index = "0", description = "Raw JSON-RPC request body.")
    private String requestJson;

    @Override
    public void run() {
        try {
            RpcClient client = parent.createClient();
            String response = client.callRaw(requestJson);
            System.out.println(RpcClient.formatRaw(response, parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
