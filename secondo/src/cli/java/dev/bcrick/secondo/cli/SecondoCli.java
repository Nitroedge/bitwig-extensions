package dev.bcrick.secondo.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IDefaultValueProvider;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Command(
    name = "gig",
    description = "CLI for Secondo — control Bitwig Studio via JSON-RPC.",
    mixinStandardHelpOptions = true,
    version = "secondo-cli 0.3.0",
    subcommands = {
        TransportCommand.class,
        TrackCommand.class,
        DeviceCommand.class,
        NoteCommand.class,
        SnapshotCommand.class,
        RpcCommand.class,
        SongCommand.class,
        SceneCommand.class,
        ActionCommand.class,
        MixerCommand.class,
        ProjectCommand.class,
        WatchCommand.class
    }
)
public class SecondoCli {

    @Option(names = {"--host"}, description = "Server host (default: localhost)", defaultValue = "localhost")
    String host;

    @Option(names = {"--port"}, description = "Server port (default: 8787)", defaultValue = "8787")
    int port;

    @Option(names = {"--pretty"}, description = "Pretty-print JSON output")
    boolean pretty;

    RpcClient createClient() {
        return new RpcClient(host, port);
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new SecondoCli());
        cmd.setDefaultValueProvider(new ConfigFileDefaultProvider());
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    /**
     * Reads defaults from ~/.secondo/config.json when present.
     * Config format: {"host":"...", "port":...}
     * CLI flags always override config values.
     */
    static class ConfigFileDefaultProvider implements IDefaultValueProvider {

        private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), ".secondo", "config.json");

        private JsonObject config;
        private boolean loaded = false;

        @Override
        public String defaultValue(ArgSpec argSpec) {
            if (!loaded) {
                config = loadConfig();
                loaded = true;
            }
            if (config == null || !(argSpec instanceof OptionSpec)) {
                return null;
            }
            OptionSpec option = (OptionSpec) argSpec;
            String longestName = option.longestName();
            switch (longestName) {
                case "--host":
                    return config.has("host") ? config.get("host").getAsString() : null;
                case "--port":
                    return config.has("port") ? String.valueOf(config.get("port").getAsInt()) : null;
                default:
                    return null;
            }
        }

        private static JsonObject loadConfig() {
            if (!Files.exists(CONFIG_PATH)) {
                return null;
            }
            try {
                String content = Files.readString(CONFIG_PATH);
                return JsonParser.parseString(content).getAsJsonObject();
            } catch (IOException | IllegalStateException e) {
                // Malformed or unreadable config — silently fall back to defaults
                return null;
            }
        }
    }
}
