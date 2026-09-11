package dev.bcrick.secondo.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class CliCommandStructureTest {

    @Test
    void songCommandRegisteredInCli() {
        CommandLine cli = new CommandLine(new SecondoCli());
        assertNotNull(cli.getSubcommands().get("song"),
            "SecondoCli should have 'song' subcommand");
    }

    @Test
    void songHasDumpSubcommand() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine songCmd = cli.getSubcommands().get("song");
        assertNotNull(songCmd.getSubcommands().get("dump"),
            "song should have 'dump' subcommand");
    }

    @Test
    void songHasRebuildSubcommand() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine songCmd = cli.getSubcommands().get("song");
        assertNotNull(songCmd.getSubcommands().get("rebuild"),
            "song should have 'rebuild' subcommand");
    }

    @Test
    void dumpHelpShowsOutputOption() {
        CommandLine cli = new CommandLine(new SecondoCli());
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute("song", "dump", "--help");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("--output"), "dump help should mention --output option");
        assertTrue(output.contains("-o"), "dump help should mention -o shorthand");
    }

    @Test
    void rebuildHelpShowsFileParameter() {
        CommandLine cli = new CommandLine(new SecondoCli());
        StringWriter sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));

        int exitCode = cli.execute("song", "rebuild", "--help");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("<filePath>"), "rebuild help should show filePath parameter");
    }

    @Test
    void rebuildRequiresFileArgument() {
        CommandLine cli = new CommandLine(new SecondoCli());
        StringWriter sw = new StringWriter();
        StringWriter errSw = new StringWriter();
        cli.setOut(new PrintWriter(sw));
        cli.setErr(new PrintWriter(errSw));

        int exitCode = cli.execute("song", "rebuild");

        assertNotEquals(0, exitCode, "rebuild without file should fail");
    }

    @Test
    void cliHasTwelveSubcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        // transport, track, device, note, snapshot, rpc, song, scene, action, mixer, project, watch
        assertEquals(12, cli.getSubcommands().size(),
            "SecondoCli should have 12 subcommands");
    }

    // --- Subcommand counts for each top-level command ---

    @Test
    void transportHas11Subcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine transport = cli.getSubcommands().get("transport");
        assertNotNull(transport);
        assertEquals(11, transport.getSubcommands().size(),
            "transport should have 11 subcommands");
    }

    @Test
    void trackHas12Subcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine track = cli.getSubcommands().get("track");
        assertNotNull(track);
        assertEquals(12, track.getSubcommands().size(),
            "track should have 12 subcommands");
    }

    @Test
    void deviceHas4Subcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine device = cli.getSubcommands().get("device");
        assertNotNull(device);
        assertEquals(4, device.getSubcommands().size(),
            "device should have 4 subcommands");
    }

    @Test
    void noteHas8Subcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine note = cli.getSubcommands().get("note");
        assertNotNull(note);
        assertEquals(8, note.getSubcommands().size(),
            "note should have 8 subcommands");
    }

    @Test
    void snapshotIsRegistered() {
        CommandLine cli = new CommandLine(new SecondoCli());
        assertNotNull(cli.getSubcommands().get("snapshot"),
            "SecondoCli should have 'snapshot' subcommand");
    }

    @Test
    void rpcIsRegistered() {
        CommandLine cli = new CommandLine(new SecondoCli());
        assertNotNull(cli.getSubcommands().get("rpc"),
            "SecondoCli should have 'rpc' subcommand");
    }

    @Test
    void rpcHasRequestParameter() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine rpcCmd = cli.getSubcommands().get("rpc");
        assertNotNull(rpcCmd);
        // RpcCommand has a positional parameter for the request JSON
        assertFalse(rpcCmd.getCommandSpec().positionalParameters().isEmpty(),
            "rpc should have a positional parameter for request JSON");
    }

    // --- Help text for parameterized commands ---

    @Test
    void transportTempoHasPositionalParam() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine tempoCmd = cli.getSubcommands().get("transport")
            .getSubcommands().get("tempo");
        assertNotNull(tempoCmd);
        assertFalse(tempoCmd.getCommandSpec().positionalParameters().isEmpty(),
            "tempo should have a positional parameter for BPM");
    }

    // --- New commands (phase 40) ---

    @Test
    void sceneHas7Subcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine scene = cli.getSubcommands().get("scene");
        assertNotNull(scene, "SecondoCli should have 'scene' subcommand");
        assertEquals(7, scene.getSubcommands().size(),
            "scene should have 7 subcommands");
    }

    @Test
    void actionHas3Subcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine action = cli.getSubcommands().get("action");
        assertNotNull(action, "SecondoCli should have 'action' subcommand");
        assertEquals(3, action.getSubcommands().size(),
            "action should have 3 subcommands");
    }

    @Test
    void mixerHas4Subcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine mixer = cli.getSubcommands().get("mixer");
        assertNotNull(mixer, "SecondoCli should have 'mixer' subcommand");
        assertEquals(4, mixer.getSubcommands().size(),
            "mixer should have 4 subcommands");
    }

    @Test
    void projectHas6Subcommands() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine project = cli.getSubcommands().get("project");
        assertNotNull(project, "SecondoCli should have 'project' subcommand");
        assertEquals(6, project.getSubcommands().size(),
            "project should have 6 subcommands");
    }

    @Test
    void watchIsRegistered() {
        CommandLine cli = new CommandLine(new SecondoCli());
        assertNotNull(cli.getSubcommands().get("watch"),
            "SecondoCli should have 'watch' subcommand");
    }

    @Test
    void watchHasTopicsOption() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine watch = cli.getSubcommands().get("watch");
        assertNotNull(watch);
        assertNotNull(watch.getCommandSpec().optionsMap().get("--topics"),
            "watch should have --topics option");
    }

    @Test
    void actionListHasCategoryOption() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine listCmd = cli.getSubcommands().get("action")
            .getSubcommands().get("list");
        assertNotNull(listCmd);
        assertNotNull(listCmd.getCommandSpec().optionsMap().get("--category"),
            "action list should have --category option");
    }

    @Test
    void sceneLaunchHasIndexParam() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine launchCmd = cli.getSubcommands().get("scene")
            .getSubcommands().get("launch");
        assertNotNull(launchCmd);
        assertFalse(launchCmd.getCommandSpec().positionalParameters().isEmpty(),
            "scene launch should have positional index parameter");
    }

    @Test
    void trackSetVolumeHasIndexAndValueOptions() {
        CommandLine cli = new CommandLine(new SecondoCli());
        CommandLine setVolumeCmd = cli.getSubcommands().get("track")
            .getSubcommands().get("set-volume");
        assertNotNull(setVolumeCmd);
        assertNotNull(setVolumeCmd.getCommandSpec().optionsMap().get("--index"),
            "set-volume should have --index option");
        assertNotNull(setVolumeCmd.getCommandSpec().optionsMap().get("--value"),
            "set-volume should have --value option");
    }
}
