package dev.bcrick.secondo.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class TransportCommandTest {

    private TestableSecondoCli cli;
    private CommandLine cmd;
    private FakeRpcClient fake;

    @BeforeEach
    void setUp() {
        cli = new TestableSecondoCli();
        cmd = new CommandLine(cli);
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));
        fake = cli.getFakeClient();
    }

    @Test
    void play_callsTransportPlay() {
        cmd.execute("transport", "play");

        assertEquals("transport/play", fake.getLastMethod());
    }

    @Test
    void stop_callsTransportStop() {
        cmd.execute("transport", "stop");

        assertEquals("transport/stop", fake.getLastMethod());
    }

    @Test
    void record_callsTransportRecord() {
        cmd.execute("transport", "record");

        assertEquals("transport/record", fake.getLastMethod());
    }

    @Test
    void toggle_callsTransportTogglePlay() {
        cmd.execute("transport", "toggle");

        assertEquals("transport/togglePlay", fake.getLastMethod());
    }

    @Test
    void rewind_callsTransportRewind() {
        cmd.execute("transport", "rewind");

        assertEquals("transport/rewind", fake.getLastMethod());
    }

    @Test
    void ff_callsTransportFastForward() {
        cmd.execute("transport", "ff");

        assertEquals("transport/fastForward", fake.getLastMethod());
    }

    @Test
    void tapTempo_callsTransportTapTempo() {
        cmd.execute("transport", "tap-tempo");

        assertEquals("transport/tapTempo", fake.getLastMethod());
    }

    @Test
    void tempo_callsSetTempoWithBpm() {
        cmd.execute("transport", "tempo", "120.0");

        assertEquals("transport/setTempo", fake.getLastMethod());
        assertEquals(120.0, fake.getLastParams().get("tempo").getAsDouble());
    }

    @Test
    void position_callsSetPositionWithBeats() {
        cmd.execute("transport", "position", "8.0");

        assertEquals("transport/setPosition", fake.getLastMethod());
        assertEquals(8.0, fake.getLastParams().get("beats").getAsDouble());
    }

    @Test
    void loop_on_callsSetLoopEnabled() {
        cmd.execute("transport", "loop", "on");

        assertEquals("transport/setLoop", fake.getLastMethod());
        assertTrue(fake.getLastParams().get("enabled").getAsBoolean());
    }

    @Test
    void metronome_off_callsSetMetronomeDisabled() {
        cmd.execute("transport", "metronome", "off");

        assertEquals("transport/setMetronome", fake.getLastMethod());
        assertFalse(fake.getLastParams().get("enabled").getAsBoolean());
    }
}
