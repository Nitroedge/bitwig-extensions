package dev.bcrick.bitwigpal.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "track",
    description = "Track controls (volume, pan, mute, solo, arm, create, select, rename, delete, duplicate).",
    mixinStandardHelpOptions = true,
    subcommands = {
        TrackCommand.SetVolume.class,
        TrackCommand.SetPan.class,
        TrackCommand.SetMute.class,
        TrackCommand.SetSolo.class,
        TrackCommand.SetArm.class,
        TrackCommand.CreateAudio.class,
        TrackCommand.CreateInstrument.class,
        TrackCommand.CreateEffect.class,
        TrackCommand.Select.class,
        TrackCommand.Rename.class,
        TrackCommand.DeleteSelected.class,
        TrackCommand.Duplicate.class
    }
)
class TrackCommand {

    @ParentCommand
    BitwigPalCli parent;

    @Command(name = "set-volume", description = "Set track volume (0.0–1.0).")
    static class SetVolume implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--index", "-i"}, required = true, description = "Track index (0-based).")
        int index;

        @Option(names = {"--value", "-v"}, required = true, description = "Volume (0.0–1.0).")
        double value;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            params.addProperty("value", value);
            callRpc(track, "track/setVolume", params);
        }
    }

    @Command(name = "set-pan", description = "Set track pan (0.0=left, 0.5=center, 1.0=right).")
    static class SetPan implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--index", "-i"}, required = true, description = "Track index (0-based).")
        int index;

        @Option(names = {"--value", "-v"}, required = true, description = "Pan (0.0–1.0).")
        double value;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            params.addProperty("value", value);
            callRpc(track, "track/setPan", params);
        }
    }

    @Command(name = "set-mute", description = "Mute or unmute a track.")
    static class SetMute implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--index", "-i"}, required = true, description = "Track index (0-based).")
        int index;

        @Parameters(index = "0", description = "on or off.")
        String state;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            params.addProperty("muted", "on".equalsIgnoreCase(state));
            callRpc(track, "track/setMute", params);
        }
    }

    @Command(name = "set-solo", description = "Solo or unsolo a track.")
    static class SetSolo implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--index", "-i"}, required = true, description = "Track index (0-based).")
        int index;

        @Parameters(index = "0", description = "on or off.")
        String state;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            params.addProperty("soloed", "on".equalsIgnoreCase(state));
            callRpc(track, "track/setSolo", params);
        }
    }

    @Command(name = "set-arm", description = "Arm or disarm a track for recording.")
    static class SetArm implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--index", "-i"}, required = true, description = "Track index (0-based).")
        int index;

        @Parameters(index = "0", description = "on or off.")
        String state;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            params.addProperty("armed", "on".equalsIgnoreCase(state));
            callRpc(track, "track/setArm", params);
        }
    }

    // --- Phase 6: Track management subcommands ---

    @Command(name = "create-audio", description = "Create a new audio track.")
    static class CreateAudio implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--position", "-p"}, description = "Insert position (0-based index, -1 = append).",
                defaultValue = "-1")
        int position;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("position", position);
            callRpc(track, "track/createAudio", params);
        }
    }

    @Command(name = "create-instrument", description = "Create a new instrument track.")
    static class CreateInstrument implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--position", "-p"}, description = "Insert position (0-based index, -1 = append).",
                defaultValue = "-1")
        int position;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("position", position);
            callRpc(track, "track/createInstrument", params);
        }
    }

    @Command(name = "create-effect", description = "Create a new effect track.")
    static class CreateEffect implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--position", "-p"}, description = "Insert position (0-based index, -1 = append).",
                defaultValue = "-1")
        int position;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("position", position);
            callRpc(track, "track/createEffect", params);
        }
    }

    @Command(name = "select", description = "Select a track by index (0–63).")
    static class Select implements Runnable {
        @ParentCommand private TrackCommand track;

        @Option(names = {"--index", "-i"}, required = true, description = "Track index (0-based).")
        int index;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("index", index);
            callRpc(track, "track/select", params);
        }
    }

    @Command(name = "rename", description = "Rename the currently selected track.")
    static class Rename implements Runnable {
        @ParentCommand private TrackCommand track;

        @Parameters(index = "0", description = "New track name.")
        String name;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            callRpc(track, "track/rename", params);
        }
    }

    @Command(name = "delete-selected", description = "Delete the currently selected track.")
    static class DeleteSelected implements Runnable {
        @ParentCommand private TrackCommand track;

        @Override
        public void run() {
            callRpc(track, "track/deleteSelected", new JsonObject());
        }
    }

    @Command(name = "duplicate", description = "Duplicate the currently selected track.")
    static class Duplicate implements Runnable {
        @ParentCommand private TrackCommand track;

        @Override
        public void run() {
            callRpc(track, "track/duplicate", new JsonObject());
        }
    }

    private static void callRpc(TrackCommand track, String method, JsonObject params) {
        try {
            RpcClient client = track.parent.createClient();
            JsonElement result = client.call(method, params);
            System.out.println(RpcClient.format(result, track.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
