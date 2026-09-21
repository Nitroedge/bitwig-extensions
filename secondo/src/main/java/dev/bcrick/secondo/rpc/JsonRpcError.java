package dev.bcrick.secondo.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class JsonRpcError {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int TRANSACTION_STEP_FAILED = -32010;

    // The three launcher-write codes. -32011 and -32012 are D-23-07 reading (a) (Phase 23, plan
    // 23-08, 2026-09-13); -32013 arrived in Phase 29, plan 29-02 (2026-09-19). The verb in
    // that last clause was the present tense, which here is an attribution and was never a
    // stale claim -- but ENGINE-PIN.md Entry 3 defines its own discharge as a git grep for
    // that present-tense phrase returning NOTHING across secondo/src, and the grep matched
    // this line as well as the one the entry was written about. Reworded by plan 31-07 so
    // the entry is discharged by its own test rather than in spite of it.
    //
    // WHAT WAS TRUE FROM THE SIXTH PIN MOVE UNTIL THE FOURTEENTH, recorded in the past tense
    // rather than deleted, because the paragraph that was believed is half of the record and a
    // reader who cannot date the change cannot tell a stale claim from a current one:
    //
    //   -32011 and -32012 were DECLARED HERE AND COULD NOT APPEAR IN A RESPONSE, and that was
    //   the whole point of declaring them when they were declared: the numbers were pinned at
    //   the pin move that carried the verify-before-write fix, so that Phase 29 -- "Engine:
    //   Deferrable RPC Responses" -- would emit numbers already published rather than mint them
    //   later against a tool layer that had shipped.
    //
    //   Why they could not be returned at that pin: a handler runs inside Bitwig's flush() on
    //   the Control Surface Session thread (CommandQueue.java:20-22) and host.scheduleTask
    //   schedules onto that SAME thread, so a handler cannot block on its own deferred verify
    //   -- it would starve the flush that the verify is waiting for. Until an RPC response could
    //   be completed after the handler returned, macro/writeClip's refusal was logged and
    //   counted, never returned.
    //
    // WHAT IS TRUE FROM PHASE 29. The diagnosis in the record above still holds exactly -- a
    // handler still cannot block on its own verify -- but the RESPONSE no longer has to leave
    // when the handler does (PendingResponse, plan 29-01). All three codes now TRAVEL IN THE
    // RESPONSE for macro/writeClip, as real JSON-RPC error objects with a data member.
    //
    // For macro/writeClip only. The four chain macros -- buildSection, buildSong, setupScenes and
    // writeAutomation -- keep the accepted-and-queued semantics described above (D-29-05): their
    // chains are unbounded and cannot answer inside either five-second wall, so they still
    // announce a refusal through the marked host.errorln line and the snapshot counter alone.
    // Both announcements survive for macro/writeClip too (D-29-13); the response joins them, it
    // does not replace them.
    //
    // The contract for each code -- its message, its data keys and what the caller must do next
    // -- is published in docs/rpc-api-reference.md. See also MacroHandler's class comment.

    /** The cursor clip was on a slot other than the one the caller named; the write was refused. */
    public static final int CURSOR_MISMATCH = -32011;

    /** The cursor was on the named slot and the note write itself failed. */
    public static final int NOTE_WRITE_FAILED = -32012;

    /**
     * The write was accepted and had not resolved when the deferral deadline fired. It MAY STILL
     * HAVE LANDED: read the slot back. Do NOT retry -- a blind retry of a creating write puts a
     * second copy of the notes in the caller's own music.
     *
     * <p>That distinct next step -- read back, never re-issue -- is why this is allocated its own
     * -3201x rather than reusing the shared -32001 general slot: -32011 says re-issue, -32012 says
     * investigate, and this one says neither.
     */
    public static final int WRITE_UNRESOLVED = -32013;

    private JsonRpcError() {}

    public static JsonObject create(int code, String message, JsonElement data) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        if (data != null) {
            error.add("data", data);
        }
        return error;
    }

    public static JsonObject create(int code, String message) {
        return create(code, message, null);
    }
}
