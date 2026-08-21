package dev.bcrick.bitwigpal.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class TrackCommandTest {

    private TestableBitwigPalCli cli;
    private CommandLine cmd;
    private FakeRpcClient fake;

    @BeforeEach
    void setUp() {
        cli = new TestableBitwigPalCli();
        cmd = new CommandLine(cli);
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));
        fake = cli.getFakeClient();
    }

    @Test
    void setVolume_callsWithIndexAndValue() {
        cmd.execute("track", "set-volume", "-i", "2", "-v", "0.75");

        assertEquals("track/setVolume", fake.getLastMethod());
        assertEquals(2, fake.getLastParams().get("index").getAsInt());
        assertEquals(0.75, fake.getLastParams().get("value").getAsDouble());
    }

    @Test
    void setPan_callsWithIndexAndValue() {
        cmd.execute("track", "set-pan", "-i", "1", "-v", "0.3");

        assertEquals("track/setPan", fake.getLastMethod());
        assertEquals(1, fake.getLastParams().get("index").getAsInt());
        assertEquals(0.3, fake.getLastParams().get("value").getAsDouble(), 0.001);
    }

    @Test
    void setMute_on_callsWithEnabled() {
        cmd.execute("track", "set-mute", "-i", "0", "on");

        assertEquals("track/setMute", fake.getLastMethod());
        assertEquals(0, fake.getLastParams().get("index").getAsInt());
        assertTrue(fake.getLastParams().get("muted").getAsBoolean());
    }

    @Test
    void setSolo_off_callsWithDisabled() {
        cmd.execute("track", "set-solo", "-i", "3", "off");

        assertEquals("track/setSolo", fake.getLastMethod());
        assertEquals(3, fake.getLastParams().get("index").getAsInt());
        assertFalse(fake.getLastParams().get("soloed").getAsBoolean());
    }

    @Test
    void setArm_on_callsWithEnabled() {
        cmd.execute("track", "set-arm", "-i", "5", "on");

        assertEquals("track/setArm", fake.getLastMethod());
        assertEquals(5, fake.getLastParams().get("index").getAsInt());
        assertTrue(fake.getLastParams().get("armed").getAsBoolean());
    }

    @Test
    void createAudio_callsWithPosition() {
        cmd.execute("track", "create-audio", "-p", "2");

        assertEquals("track/createAudio", fake.getLastMethod());
        assertEquals(2, fake.getLastParams().get("position").getAsInt());
    }

    @Test
    void createInstrument_callsWithPosition() {
        cmd.execute("track", "create-instrument", "-p", "0");

        assertEquals("track/createInstrument", fake.getLastMethod());
        assertEquals(0, fake.getLastParams().get("position").getAsInt());
    }

    @Test
    void createEffect_defaultPosition() {
        cmd.execute("track", "create-effect");

        assertEquals("track/createEffect", fake.getLastMethod());
        assertEquals(-1, fake.getLastParams().get("position").getAsInt());
    }

    @Test
    void select_callsWithIndex() {
        cmd.execute("track", "select", "-i", "4");

        assertEquals("track/select", fake.getLastMethod());
        assertEquals(4, fake.getLastParams().get("index").getAsInt());
    }

    @Test
    void rename_callsWithName() {
        cmd.execute("track", "rename", "My Track");

        assertEquals("track/rename", fake.getLastMethod());
        assertEquals("My Track", fake.getLastParams().get("name").getAsString());
    }

    @Test
    void deleteSelected_callsCorrectMethod() {
        cmd.execute("track", "delete-selected");

        assertEquals("track/deleteSelected", fake.getLastMethod());
    }

    @Test
    void duplicate_callsCorrectMethod() {
        cmd.execute("track", "duplicate");

        assertEquals("track/duplicate", fake.getLastMethod());
    }
}
