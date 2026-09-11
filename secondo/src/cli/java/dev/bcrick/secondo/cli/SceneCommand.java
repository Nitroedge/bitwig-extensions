package dev.bcrick.secondo.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "scene",
    description = "Scene controls (list, launch, create, delete, rename, set-color).",
    mixinStandardHelpOptions = true,
    subcommands = {
        SceneCommand.List.class,
        SceneCommand.Launch.class,
        SceneCommand.Create.class,
        SceneCommand.CreateFromPlaying.class,
        SceneCommand.Delete.class,
        SceneCommand.Rename.class,
        SceneCommand.SetColor.class
    }
)
class SceneCommand {

    @ParentCommand
    SecondoCli parent;

    @Command(name = "list", description = "List scenes (scroll info).")
    static class List implements Runnable {
        @ParentCommand private SceneCommand scene;

        @Override
        public void run() {
            callSimple(scene, "sceneBank/getScrollInfo");
        }
    }

    @Command(name = "launch", description = "Launch a scene by index.")
    static class Launch implements Runnable {
        @ParentCommand private SceneCommand scene;

        @Parameters(index = "0", description = "Scene index (0-based).")
        int index;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            callWithParams(scene, "scene/launch", params);
        }
    }

    @Command(name = "create", description = "Create a new scene.")
    static class Create implements Runnable {
        @ParentCommand private SceneCommand scene;

        @Override
        public void run() {
            callSimple(scene, "scene/create");
        }
    }

    @Command(name = "create-from-playing", description = "Create a scene from currently playing clips.")
    static class CreateFromPlaying implements Runnable {
        @ParentCommand private SceneCommand scene;

        @Override
        public void run() {
            callSimple(scene, "scene/createFromPlaying");
        }
    }

    @Command(name = "delete", description = "Delete a scene by index.")
    static class Delete implements Runnable {
        @ParentCommand private SceneCommand scene;

        @Parameters(index = "0", description = "Scene index (0-based).")
        int index;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            callWithParams(scene, "scene/delete", params);
        }
    }

    @Command(name = "rename", description = "Rename a scene.")
    static class Rename implements Runnable {
        @ParentCommand private SceneCommand scene;

        @Parameters(index = "0", description = "Scene index (0-based).")
        int index;

        @Parameters(index = "1", description = "New scene name.")
        String name;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            params.addProperty("name", name);
            callWithParams(scene, "scene/rename", params);
        }
    }

    @Command(name = "set-color", description = "Set the color of a scene.")
    static class SetColor implements Runnable {
        @ParentCommand private SceneCommand scene;

        @Parameters(index = "0", description = "Scene index (0-based).")
        int index;

        @Parameters(index = "1", description = "Color value.")
        String color;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            params.addProperty("color", color);
            callWithParams(scene, "scene/setColor", params);
        }
    }

    private static void callSimple(SceneCommand scene, String method) {
        try {
            RpcClient client = scene.parent.createClient();
            JsonElement result = client.call(method, null);
            System.out.println(RpcClient.format(result, scene.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void callWithParams(SceneCommand scene, String method, JsonObject params) {
        try {
            RpcClient client = scene.parent.createClient();
            JsonElement result = client.call(method, params);
            System.out.println(RpcClient.format(result, scene.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
