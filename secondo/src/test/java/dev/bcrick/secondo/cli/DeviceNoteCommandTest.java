package dev.bcrick.secondo.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class DeviceNoteCommandTest {

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

    // --- Device commands ---

    @Test
    void insertBitwig_callsWithNameAndPosition() {
        cmd.execute("device", "insert-bitwig", "Polymer", "-p", "before");

        assertEquals("device/insertBitwigDevice", fake.getLastMethod());
        assertEquals("Polymer", fake.getLastParams().get("name").getAsString());
        assertEquals("before", fake.getLastParams().get("position").getAsString());
    }

    @Test
    void insertBitwig_defaultPosition() {
        cmd.execute("device", "insert-bitwig", "EQ-5");

        assertEquals("device/insertBitwigDevice", fake.getLastMethod());
        assertEquals("end", fake.getLastParams().get("position").getAsString());
    }

    @Test
    void insertPlugin_callsWithTypeAndId() {
        cmd.execute("device", "insert-plugin", "vst3", "com.example.synth");

        assertEquals("device/insertPluginDevice", fake.getLastMethod());
        assertEquals("vst3", fake.getLastParams().get("type").getAsString());
        assertEquals("com.example.synth", fake.getLastParams().get("id").getAsString());
    }

    @Test
    void listBitwig_callsCorrectMethod() {
        cmd.execute("device", "list-bitwig");

        assertEquals("device/listBitwigDevices", fake.getLastMethod());
    }

    @Test
    void remove_callsCorrectMethod() {
        cmd.execute("device", "remove");

        assertEquals("device/remove", fake.getLastMethod());
    }

    // --- Note commands ---

    @Test
    void select_callsWithTrackAndSlot() {
        cmd.execute("note", "select", "-t", "1", "-s", "3");

        assertEquals("clip/select", fake.getLastMethod());
        assertEquals(1, fake.getLastParams().get("trackIndex").getAsInt());
        assertEquals(3, fake.getLastParams().get("slotIndex").getAsInt());
    }

    @Test
    void delete_callsWithTrackAndSlot() {
        cmd.execute("note", "delete", "-t", "0", "-s", "2");

        assertEquals("clip/delete", fake.getLastMethod());
        assertEquals(0, fake.getLastParams().get("trackIndex").getAsInt());
        assertEquals(2, fake.getLastParams().get("slotIndex").getAsInt());
    }

    @Test
    void setNotes_callsWithNotesJson() {
        String json = "[{\"x\":0,\"y\":60,\"velocity\":100,\"duration\":1.0}]";
        cmd.execute("note", "set-notes", "-j", json);

        assertEquals("clip/setNotes", fake.getLastMethod());
        assertTrue(fake.getLastParams().has("notes"));
        assertEquals(1, fake.getLastParams().getAsJsonArray("notes").size());
        assertEquals(60, fake.getLastParams().getAsJsonArray("notes")
            .get(0).getAsJsonObject().get("y").getAsInt());
    }

    @Test
    void clearNote_callsWithStepAndNote() {
        cmd.execute("note", "clear-note", "--x", "4", "--y", "64");

        assertEquals("clip/clearNote", fake.getLastMethod());
        assertEquals(4, fake.getLastParams().get("x").getAsInt());
        assertEquals(64, fake.getLastParams().get("y").getAsInt());
    }

    @Test
    void clearAll_callsCorrectMethod() {
        cmd.execute("note", "clear-all");

        assertEquals("clip/clearAllNotes", fake.getLastMethod());
    }

    @Test
    void getNotes_callsCorrectMethod() {
        cmd.execute("note", "get-notes");

        assertEquals("clip/getNotes", fake.getLastMethod());
    }

    @Test
    void setStepSize_callsWithSize() {
        cmd.execute("note", "set-step-size", "-s", "0.25");

        assertEquals("clip/setStepSize", fake.getLastMethod());
        assertEquals(0.25, fake.getLastParams().get("size").getAsDouble());
    }

    @Test
    void scrollSteps_callsWithOffset() {
        cmd.execute("note", "scroll-steps", "-o", "16");

        assertEquals("clip/scrollSteps", fake.getLastMethod());
        assertEquals(16, fake.getLastParams().get("offset").getAsInt());
    }
}
