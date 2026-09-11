package dev.bcrick.secondo.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "transport",
    description = "Transport controls (play, stop, record, tempo, etc.).",
    mixinStandardHelpOptions = true,
    subcommands = {
        TransportCommand.Play.class,
        TransportCommand.Stop.class,
        TransportCommand.Record.class,
        TransportCommand.Toggle.class,
        TransportCommand.Rewind.class,
        TransportCommand.FastForward.class,
        TransportCommand.TapTempo.class,
        TransportCommand.Tempo.class,
        TransportCommand.Position.class,
        TransportCommand.Loop.class,
        TransportCommand.Metronome.class
    }
)
class TransportCommand {

    @ParentCommand
    SecondoCli parent;

    @Command(name = "play", description = "Start playback.")
    static class Play implements Runnable {
        @ParentCommand private TransportCommand transport;

        @Override
        public void run() {
            callSimple(transport, "transport/play");
        }
    }

    @Command(name = "stop", description = "Stop playback.")
    static class Stop implements Runnable {
        @ParentCommand private TransportCommand transport;

        @Override
        public void run() {
            callSimple(transport, "transport/stop");
        }
    }

    @Command(name = "record", description = "Toggle recording.")
    static class Record implements Runnable {
        @ParentCommand private TransportCommand transport;

        @Override
        public void run() {
            callSimple(transport, "transport/record");
        }
    }

    @Command(name = "toggle", description = "Toggle play/stop.")
    static class Toggle implements Runnable {
        @ParentCommand private TransportCommand transport;

        @Override
        public void run() {
            callSimple(transport, "transport/togglePlay");
        }
    }

    @Command(name = "rewind", description = "Rewind the playhead.")
    static class Rewind implements Runnable {
        @ParentCommand private TransportCommand transport;

        @Override
        public void run() {
            callSimple(transport, "transport/rewind");
        }
    }

    @Command(name = "ff", description = "Fast-forward the playhead.")
    static class FastForward implements Runnable {
        @ParentCommand private TransportCommand transport;

        @Override
        public void run() {
            callSimple(transport, "transport/fastForward");
        }
    }

    @Command(name = "tap-tempo", description = "Tap tempo.")
    static class TapTempo implements Runnable {
        @ParentCommand private TransportCommand transport;

        @Override
        public void run() {
            callSimple(transport, "transport/tapTempo");
        }
    }

    @Command(name = "tempo", description = "Set the tempo in BPM.")
    static class Tempo implements Runnable {
        @ParentCommand private TransportCommand transport;

        @picocli.CommandLine.Parameters(index = "0", description = "Tempo in BPM (e.g., 120.0).")
        double bpm;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("tempo", bpm);
            callWithParams(transport, "transport/setTempo", params);
        }
    }

    @Command(name = "position", description = "Set the playhead position in beats.")
    static class Position implements Runnable {
        @ParentCommand private TransportCommand transport;

        @picocli.CommandLine.Parameters(index = "0", description = "Position in beats.")
        double beats;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("beats", beats);
            callWithParams(transport, "transport/setPosition", params);
        }
    }

    @Command(name = "loop", description = "Enable or disable the arranger loop.")
    static class Loop implements Runnable {
        @ParentCommand private TransportCommand transport;

        @picocli.CommandLine.Parameters(index = "0", description = "on or off.")
        String state;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("enabled", "on".equalsIgnoreCase(state));
            callWithParams(transport, "transport/setLoop", params);
        }
    }

    @Command(name = "metronome", description = "Enable or disable the metronome.")
    static class Metronome implements Runnable {
        @ParentCommand private TransportCommand transport;

        @picocli.CommandLine.Parameters(index = "0", description = "on or off.")
        String state;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("enabled", "on".equalsIgnoreCase(state));
            callWithParams(transport, "transport/setMetronome", params);
        }
    }

    private static void callSimple(TransportCommand transport, String method) {
        try {
            RpcClient client = transport.parent.createClient();
            JsonElement result = client.call(method, null);
            System.out.println(RpcClient.format(result, transport.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void callWithParams(TransportCommand transport, String method, JsonObject params) {
        try {
            RpcClient client = transport.parent.createClient();
            JsonElement result = client.call(method, params);
            System.out.println(RpcClient.format(result, transport.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
