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

    // The two launcher-write codes, D-23-07 reading (a) (Phase 23, plan 23-08, 2026-09-13).
    //
    // DECLARED HERE, NOT YET REACHABLE IN A RESPONSE, and that is the whole point of declaring
    // them now: the numbers are pinned at the pin move that carries the verify-before-write fix,
    // so Phase 29 -- "Engine: Deferrable RPC Responses" -- emits numbers already published rather
    // than minting them later against a tool layer that has shipped.
    //
    // Why they cannot be returned at this pin: a handler runs inside Bitwig's flush() on the
    // Control Surface Session thread (CommandQueue.java:20-22) and host.scheduleTask schedules
    // onto that SAME thread, so a handler cannot block on its own deferred verify -- it would
    // starve the flush that the verify is waiting for. Until an RPC response can be completed
    // after the handler returns (Phase 29), macro/writeClip's refusal is logged and counted, not
    // returned. See MacroHandler's class comment and docs/rpc-api-reference.md.

    /** The cursor clip was on a slot other than the one the caller named; the write was refused. */
    public static final int CURSOR_MISMATCH = -32011;

    /** The cursor was on the named slot and the note write itself failed. */
    public static final int NOTE_WRITE_FAILED = -32012;

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
