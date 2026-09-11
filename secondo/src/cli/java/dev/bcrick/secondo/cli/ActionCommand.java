package dev.bcrick.secondo.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "action",
    description = "Application action controls (list, categories, invoke).",
    mixinStandardHelpOptions = true,
    subcommands = {
        ActionCommand.List.class,
        ActionCommand.Categories.class,
        ActionCommand.Invoke.class
    }
)
class ActionCommand {

    @ParentCommand
    SecondoCli parent;

    @Command(name = "list", description = "List available actions, optionally filtered by category.")
    static class List implements Runnable {
        @ParentCommand private ActionCommand action;

        @Option(names = {"--category"}, description = "Filter by action category.")
        String category;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            if (category != null) {
                params.addProperty("category", category);
            }
            callWithParams(action, "action/list", params);
        }
    }

    @Command(name = "categories", description = "List action categories.")
    static class Categories implements Runnable {
        @ParentCommand private ActionCommand action;

        @Override
        public void run() {
            callSimple(action, "action/listCategories");
        }
    }

    @Command(name = "invoke", description = "Invoke an action by ID.")
    static class Invoke implements Runnable {
        @ParentCommand private ActionCommand action;

        @Parameters(index = "0", description = "Action ID to invoke.")
        String id;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("id", id);
            callWithParams(action, "action/invoke", params);
        }
    }

    private static void callSimple(ActionCommand action, String method) {
        try {
            RpcClient client = action.parent.createClient();
            JsonElement result = client.call(method, null);
            System.out.println(RpcClient.format(result, action.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void callWithParams(ActionCommand action, String method, JsonObject params) {
        try {
            RpcClient client = action.parent.createClient();
            JsonElement result = client.call(method, params);
            System.out.println(RpcClient.format(result, action.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
