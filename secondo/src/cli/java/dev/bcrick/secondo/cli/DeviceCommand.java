package dev.bcrick.secondo.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "device",
    description = "Device controls (insert, list, remove).",
    mixinStandardHelpOptions = true,
    subcommands = {
        DeviceCommand.InsertBitwig.class,
        DeviceCommand.InsertPlugin.class,
        DeviceCommand.ListBitwig.class,
        DeviceCommand.Remove.class
    }
)
class DeviceCommand {

    @ParentCommand
    SecondoCli parent;

    @Command(name = "insert-bitwig", description = "Insert a built-in Bitwig device by name.")
    static class InsertBitwig implements Runnable {
        @ParentCommand private DeviceCommand device;

        @Parameters(index = "0", description = "Device name (e.g., Polymer, EQ-5).")
        String name;

        @Option(names = {"--position", "-p"}, description = "Insert position: end, before, after (default: end).",
                defaultValue = "end")
        String position;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            params.addProperty("position", position);
            callRpc(device, "device/insertBitwigDevice", params);
        }
    }

    @Command(name = "insert-plugin", description = "Insert a third-party plugin (VST2, VST3, CLAP).")
    static class InsertPlugin implements Runnable {
        @ParentCommand private DeviceCommand device;

        @Parameters(index = "0", description = "Plugin type: vst2, vst3, clap.")
        String type;

        @Parameters(index = "1", description = "Plugin ID.")
        String id;

        @Option(names = {"--position", "-p"}, description = "Insert position: end, before, after (default: end).",
                defaultValue = "end")
        String position;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("type", type);
            params.addProperty("id", id);
            params.addProperty("position", position);
            callRpc(device, "device/insertPluginDevice", params);
        }
    }

    @Command(name = "list-bitwig", description = "List all available built-in Bitwig devices.")
    static class ListBitwig implements Runnable {
        @ParentCommand private DeviceCommand device;

        @Override
        public void run() {
            callRpc(device, "device/listBitwigDevices", new JsonObject());
        }
    }

    @Command(name = "remove", description = "Remove the currently selected device.")
    static class Remove implements Runnable {
        @ParentCommand private DeviceCommand device;

        @Override
        public void run() {
            callRpc(device, "device/remove", new JsonObject());
        }
    }

    private static void callRpc(DeviceCommand device, String method, JsonObject params) {
        try {
            RpcClient client = device.parent.createClient();
            JsonElement result = client.call(method, params);
            System.out.println(RpcClient.format(result, device.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
