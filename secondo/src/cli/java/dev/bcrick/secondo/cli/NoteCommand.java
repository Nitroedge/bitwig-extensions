package dev.bcrick.secondo.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "note",
    description = "Note editing controls (select clip, write/read/clear notes).",
    mixinStandardHelpOptions = true,
    subcommands = {
        NoteCommand.Select.class,
        NoteCommand.Delete.class,
        NoteCommand.SetNotes.class,
        NoteCommand.ClearNote.class,
        NoteCommand.ClearAll.class,
        NoteCommand.GetNotes.class,
        NoteCommand.SetStepSize.class,
        NoteCommand.ScrollSteps.class
    }
)
class NoteCommand {

    @ParentCommand
    SecondoCli parent;

    @Command(name = "select", description = "Select a clip slot for note editing.")
    static class Select implements Runnable {
        @ParentCommand private NoteCommand note;

        @Option(names = {"--track-index", "-t"}, required = true, description = "Track index (0-based).")
        int trackIndex;

        @Option(names = {"--slot-index", "-s"}, required = true, description = "Slot index (0-based).")
        int slotIndex;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("trackIndex", trackIndex);
            params.addProperty("slotIndex", slotIndex);
            callRpc(note, "clip/select", params);
        }
    }

    @Command(name = "delete", description = "Delete a clip from a clip launcher slot.")
    static class Delete implements Runnable {
        @ParentCommand private NoteCommand note;

        @Option(names = {"--track-index", "-t"}, required = true, description = "Track index (0-based).")
        int trackIndex;

        @Option(names = {"--slot-index", "-s"}, required = true, description = "Slot index (0-based).")
        int slotIndex;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("trackIndex", trackIndex);
            params.addProperty("slotIndex", slotIndex);
            callRpc(note, "clip/delete", params);
        }
    }

    @Command(name = "set-notes", description = "Write notes into the selected clip (batch).")
    static class SetNotes implements Runnable {
        @ParentCommand private NoteCommand note;

        @Option(names = {"--json", "-j"}, required = true, description = "JSON array of notes: [{x,y,velocity,duration},...]")
        String json;

        @Override
        public void run() {
            JsonArray notesArray = JsonParser.parseString(json).getAsJsonArray();
            JsonObject params = new JsonObject();
            params.add("notes", notesArray);
            callRpc(note, "clip/setNotes", params);
        }
    }

    @Command(name = "clear-note", description = "Clear a single note at the given position.")
    static class ClearNote implements Runnable {
        @ParentCommand private NoteCommand note;

        @Option(names = {"--x"}, required = true, description = "Step position (0-based).")
        int x;

        @Option(names = {"--y"}, required = true, description = "MIDI note number (0-127).")
        int y;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("x", x);
            params.addProperty("y", y);
            callRpc(note, "clip/clearNote", params);
        }
    }

    @Command(name = "clear-all", description = "Clear all notes from the selected clip.")
    static class ClearAll implements Runnable {
        @ParentCommand private NoteCommand note;

        @Override
        public void run() {
            callRpc(note, "clip/clearAllNotes", new JsonObject());
        }
    }

    @Command(name = "get-notes", description = "Read all notes in the selected clip's viewport.")
    static class GetNotes implements Runnable {
        @ParentCommand private NoteCommand note;

        @Override
        public void run() {
            callRpc(note, "clip/getNotes", new JsonObject());
        }
    }

    @Command(name = "set-step-size", description = "Set step grid resolution (0.25=1/16, 0.5=1/8, 1.0=1/4).")
    static class SetStepSize implements Runnable {
        @ParentCommand private NoteCommand note;

        @Option(names = {"--size", "-s"}, required = true, description = "Step size in beat time.")
        double size;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("size", size);
            callRpc(note, "clip/setStepSize", params);
        }
    }

    @Command(name = "scroll-steps", description = "Scroll the step grid viewport to a step offset.")
    static class ScrollSteps implements Runnable {
        @ParentCommand private NoteCommand note;

        @Option(names = {"--offset", "-o"}, required = true, description = "Step offset (0-based).")
        int offset;

        @Override
        public void run() {
            JsonObject params = new JsonObject();
            params.addProperty("offset", offset);
            callRpc(note, "clip/scrollSteps", params);
        }
    }

    private static void callRpc(NoteCommand note, String method, JsonObject params) {
        try {
            RpcClient client = note.parent.createClient();
            JsonElement result = client.call(method, params);
            System.out.println(RpcClient.format(result, note.parent.pretty));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
