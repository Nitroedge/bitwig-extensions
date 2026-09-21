package dev.bcrick.secondo.rpc;

import com.google.gson.*;

import java.util.*;

public class JsonRpcDispatcher {

    private final Map<String, MethodHandler> handlers = new LinkedHashMap<>();

    /**
     * WHY THIS SERIALIZER EMITS NULLS, AND EXACTLY WHAT THAT REACHES (25-REVIEW WR-08).
     * The flag is on the builder line at the bottom of this comment; it is named there once, and
     * only there, so a grep for it lands on the code rather than on the prose about the code.
     *
     * <p>WHY. Decision D-25-15: the device read publishes a TOTAL schema, in which a field the
     * engine could not observe is present with the value null rather than absent. Gson omits
     * JsonNull members by default, which would have made "unobserved" and "not part of this
     * shape" the same wire fact. Two readers in secondo's src/secondo/tools/device.py are
     * written directly against that guarantee: :1903 tests "parentPath" not in raw_node, and
     * :1940 builds its missing set from key absence. The flag is load-bearing, not incidental.
     *
     * <p>THE BLAST RADIUS, MEASURED rather than reasoned about. This one line changes the wire
     * for every JsonNull.INSTANCE the engine has ever written, on all 328 registered methods,
     * not only the Phase 25 ones. Measured at pin c906934 by
     * grep -c "JsonNull.INSTANCE" over secondo/src/main/java: 25 sites in four files.
     *
     * <ul>
     *   <li>12 in StateCache.java: trackBank itemCount and lastReturnedPath; a track row's
     *       parentIndex, activated, effectiveActivated, position, legacyName and legacyPosition;
     *       cursorTrackPosition and cursorSceneIndex; the launcher-refusal detail (retired by
     *       plan 29-03 — see the 2026-09-19 entry below); and the master row's activated.</li>
     *   <li>6 in DeviceHandler.java: the two cold-field helpers addString and addBoolean, a
     *       top-level device's parentPath, slotNames, topLevelDeviceCount and
     *       lastReturnedPath.</li>
     *   <li>1 in TrackHandler.java: track/setActivated's cold observed activation.</li>
     *   <li>6 in this file: the JSON-RPC 2.0 error-envelope ids below.</li>
     * </ul>
     *
     * <p>7 of those 25 pre-date Phase 25, measured at pin 3b53206: the launcher-refusal detail
     * and the six envelope ids. 18 were introduced by Phase 25. One pre-existing site is GONE rather
     * than changed: the master row's colour null was removed by Phase 25 and master colour is
     * now always an object, which is why 3b53206 greps 8 and this pin greps 25 rather than 26.
     *
     * <p>2026-09-16, plan 26-06 (Phase 26 browser build, 0.2.4): the browser read side
     * (browser/getState, browser/getFilters, browser/getResults) and the new session/snapshot
     * masterChain section now publish explicit nulls for every value not yet observed
     * (D-26-12, D-26-20). Most of them go through addProperty with a null boxed value, which
     * Gson writes as the same JSON null but which the literal grep does not count. Recounted
     * over secondo/src/main/java after this line was written: grep -o "JsonNull.INSTANCE" finds
     * 30 occurrences in four files: 27 code sites (14 in StateCache.java, +2 on the 12 above:
     * contentTypeNames and a masterChain deviceNames slot; 6 in DeviceHandler.java; 1 in
     * TrackHandler.java; 6 envelope ids here) plus 3 mentions in this comment.
     *
     * <p>2026-09-19, plan 29-03 (Phase 29, the deferrable macro/writeClip response): the census
     * moved in both directions at once, so it was RE-MEASURED rather than adjusted by arithmetic.
     * StateCache lost one site when the launcher-refusal detail was retired (D-29-13). MacroHandler
     * lost two and gained one: the deadline answer's clipCreated / clipRemoved pair stopped being
     * declared nulls and became the real booleans plan 29-02 said 29-03 would populate, while a
     * refusal's cursor coordinate is now published as an explicit null instead of the internal -1
     * sentinel, so the first refusal a user ever sees cannot read as a track number that does not
     * exist. Recounted over secondo/src/main/java after this line was written, by the same grep:
     * 33 sites in six files — 30 code sites (13 in StateCache.java, 6 in DeviceHandler.java, 2 in
     * MacroHandler.java, 2 in ParkedRemoteControls.java from Phase 27 and never counted here
     * before, 1 in TrackHandler.java, 6 envelope ids here) plus the same 3 mentions in this
     * comment. The deferral vocabulary's other explicit nulls (deferReason on a deferred success)
     * go through the boxed-value form the literal grep does not count, exactly as the browser read
     * side does above.
     *
     * <p>THE READER RULE that follows from it. A Python reader tests value is None, NEVER key
     * membership. The two device.py sites named above are the sole exception and were written
     * for this flag deliberately. The six envelope ids are the JSON-RPC 2.0 id null the spec
     * requires when a request could not be parsed enough to have an id: omitting them was a
     * spec violation, and this flag is what made them correct. One further compensation exists
     * and is deliberate: getArrangerClipState at StateCache.java uses if (field != null)
     * addProperty(...) to keep genuinely absent arranger fields absent (its "F1" note).
     */
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    // ---------------------------------------------------------------------------------------
    // Deferred responses (Phase 29, D-29-01 / D-29-02 / D-29-03 / D-29-15).
    //
    // D-29-01 declined to widen MethodHandler. Deferral is therefore OPT-IN from inside a
    // handler, through the current-request slot below, rather than a capability of all 340
    // registered methods. The 339 that will never defer are unaffected and cannot acquire it.
    // ---------------------------------------------------------------------------------------

    /**
     * THE WALL THE CALLER ACTUALLY EXPERIENCES, in milliseconds.
     *
     * <p>BOTH ENDS AGREE ON THIS FIGURE, which is the only reason it can be written down here as
     * one number: {@code HttpRpcServer.TIMEOUT_MS} is 5000 and is the argument to the
     * {@code future.get(TIMEOUT_MS, MILLISECONDS)} that bounds an HTTP request, and the Python
     * client's {@code RPC_TIMEOUT} in {@code src/secondo/rpc_client.py} is
     * {@code httpx.Timeout(connect=2.0, read=5.0, write=5.0, pool=5.0)} -- the same five seconds
     * from the other side of the repository boundary. Neither end is authoritative over the other;
     * they are two statements of one contract, and this constant is the engine's reading of it.
     *
     * <p>IT STARTS ON ARRIVAL. That is the whole of WR-04: a handler's own start is a different
     * instant, later by however long the command waited for the next {@code flush()}, so a promise
     * measured from the handler is not a promise about this wall at all.
     */
    public static final long CALLER_WALL_MS = 5000;

    /**
     * Held back from {@link #CALLER_WALL_MS} before any budget is offered to a handler.
     *
     * <p>The stamp is taken when the request reaches {@code CommandQueue.enqueue}, which is AFTER
     * the client wrote the bytes, after the TCP and HTTP handshakes, and after the server thread
     * picked the request up; and the answer still has to be serialised and written back once the
     * handler is done. None of that is inside the measurement, so none of it may be inside the
     * budget. Half a second is deliberately generous: the cost of holding it back is that a
     * deferral is declined slightly sooner than strictly necessary, and the cost of not holding it
     * back is the bodiless HTTP 500 with no id this whole mechanism exists to avoid.
     */
    public static final long WALL_SAFETY_MARGIN_MS = 500;

    /**
     * The arrival stamp of the request now being executed. Meaningful only while
     * {@link #currentRequestTimed} is true.
     */
    private long currentRequestEnqueuedNanos;

    /**
     * Whether an arrival stamp is known for the request now being executed.
     *
     * <p>False for a dispatch that never came through {@link #handle(String, long)} at all -- the
     * direct {@code handleInternal} calls a test or {@code session/transaction} makes. Those have
     * no queue wait to account for, and a missing stamp must read as "no wait measured" rather
     * than as an elapsed time since the JVM started, which is what a bare zero would mean.
     */
    private boolean currentRequestTimed;

    /**
     * How long the request now being executed has been alive, measured from its arrival.
     *
     * <p>Includes the queue wait AND whatever this handler has spent so far, which is correct:
     * both have already been taken off the caller's wall by the time a handler asks.
     */
    public long queuedMs() {
        if (!currentRequestTimed) {
            return 0L;
        }
        return (System.nanoTime() - currentRequestEnqueuedNanos) / 1_000_000L;
    }

    /**
     * What is left of the caller's wall, in milliseconds, for the request now being executed.
     *
     * <p>A handler that is about to promise an answer later asks this first and arms its deadline
     * no later than the number it gets back. It can be negative, and a negative reading is a real
     * answer: the caller's wall has already been spent and nothing this engine does now can reach
     * them.
     */
    public long remainingBudgetMs() {
        return CALLER_WALL_MS - WALL_SAFETY_MARGIN_MS - queuedMs();
    }

    /** The id of the top-level, non-notification request currently in a handler. Null otherwise. */
    private JsonElement currentRequestId;

    /** Whether the handler now running has claimed its response. Reset per request. */
    private boolean currentRequestDeferred;

    /** Read by {@link #handle(String)} immediately after {@link #handleSingle} returns. */
    private boolean deferralTaken;

    /**
     * Whether a deferral may be claimed at all right now.
     *
     * <p>Cleared for the duration of {@link #handleBatch} and {@link #handleInternal}, and
     * restored in a {@code finally} in both. That save/clear/restore is the MECHANISM behind
     * D-29-15 -- without it the rule "deferral happens only for a top-level, single,
     * non-notification request" would be prose that nothing enforces, in a dispatcher whose
     * {@code handleInternal} is called re-entrantly from inside {@code macro/writeClip}'s own
     * handler and from tasks scheduled on later flushes.
     */
    private boolean deferralAvailable = true;

    /**
     * Responses claimed by a handler but not yet handed their {@link RpcCommand}.
     *
     * <p>NO SYNCHRONISATION, deliberately, exactly as {@code MacroHandler}'s {@code writeQueue}
     * carries none: every path that touches this deque -- the handler claiming a response, the
     * {@code drainAndExecute} that binds it, and the scheduled task that completes it -- runs on
     * the one Control Surface Session thread named at {@code CommandQueue.java:20-22}.
     *
     * <p>{@code CommandQueue}'s own queue IS a {@code ConcurrentLinkedQueue}, for the opposite
     * and equally deliberate reason: it is fed from network threads
     * ({@code CommandQueue.java:10-13}) and drained on the session thread. The two threading
     * models are different and both are chosen. Never leave a reader guessing which is real.
     *
     * <p>An entry lives here only between {@link #deferCurrentResponse()} and
     * {@link #bindDeferred(RpcCommand)} -- binding POPS it -- so this does not accumulate.
     */
    private final Deque<PendingResponse> pending = new ArrayDeque<>();

    /**
     * The out-of-band "I will answer later" signal {@link #handle(String)} returns to
     * {@code CommandQueue}.
     *
     * <p>It CANNOT be {@code null}: null already means notification, {@code CommandQueue}
     * completes that future with null unconditionally, {@code HttpRpcServer} turns a null
     * response into a bodiless HTTP 204, and {@code CommandQueueTest.notificationCompletesWithNull}
     * pins all three. A null deferral would ship the caller a 204 while the write was still in
     * flight (D-29-02).
     *
     * <p>Built with an explicit {@code new String(...)} so {@link #isDeferred(String)} is a
     * genuine identity check that no interning of an equal literal can satisfy by accident.
     */
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String DEFERRED = new String("<deferred>");

    public void register(String method, MethodHandler handler) {
        handlers.put(method, handler);
    }

    public Set<String> getRegisteredMethods() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    /**
     * Serialise through the one Gson that emits nulls. {@link PendingResponse} builds its wire
     * string here rather than creating a second serialiser -- see the comment on {@link #gson}
     * for why that flag is load-bearing.
     */
    String serialize(JsonElement element) {
        return gson.toJson(element);
    }

    /**
     * Whether the handler now running may claim its response and answer later.
     *
     * <p>False for a notification (no id to answer), inside a batch, and inside
     * {@code session/transaction} or any other {@link #handleInternal} call. A handler asks this
     * before deciding to defer, and takes its pre-Phase-29 accepted-and-queued path when it is
     * false.
     */
    public boolean deferralAvailable() {
        return deferralAvailable && currentRequestId != null && !currentRequestDeferred;
    }

    /**
     * Claim the in-flight response. The handler then returns normally; whatever it returns is
     * discarded, and the caller's future stays outstanding until the returned handle is
     * completed from a scheduled task.
     *
     * @throws IllegalStateException if called outside a top-level non-notification request,
     *                               inside a batch or an internal dispatch, or twice
     */
    public PendingResponse deferCurrentResponse() {
        if (currentRequestId == null) {
            throw new IllegalStateException(
                "deferCurrentResponse() outside a top-level non-notification request");
        }
        if (!deferralAvailable) {
            throw new IllegalStateException(
                "deferCurrentResponse() inside a batch or an internal dispatch");
        }
        if (currentRequestDeferred) {
            throw new IllegalStateException("deferCurrentResponse() called twice for one request");
        }
        currentRequestDeferred = true;
        deferralTaken = true;
        PendingResponse entry = new PendingResponse(this, currentRequestId);
        pending.push(entry);
        return entry;
    }

    /** Hand the newest claimed response the command whose future it owes an answer to. */
    public void bindDeferred(RpcCommand command) {
        PendingResponse entry = pending.poll();
        if (entry != null) {
            entry.bind(command);
        }
    }

    /**
     * Drop an unbound claim. Used when a handler defers and then throws, so the registry does not
     * keep an entry that nothing will ever complete.
     */
    public void discardDeferred() {
        pending.poll();
    }

    /**
     * Identity check against the deferral sentinel, so {@code CommandQueue} never has to see the
     * constant itself.
     */
    public static boolean isDeferred(String response) {
        return response == DEFERRED;
    }

    /**
     * Execute a registered handler directly and return its result.
     * Used by session/transaction and MacroHandler to call handlers without JSON serialization.
     *
     * @throws RpcException if the handler throws one
     * @throws IllegalArgumentException if the method is not registered or params are invalid
     */
    public JsonElement handleInternal(String method, JsonObject params) throws Exception {
        MethodHandler handler = handlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Method not found: " + method);
        }
        // D-29-15's mechanism. This method is called re-entrantly -- from inside
        // macro/writeClip's own handler, and from tasks scheduled on later flushes -- so a slot
        // left standing here is a slot a handler three flushes away could claim and answer a
        // caller that has long since been served. Save, clear, restore.
        JsonElement savedId = currentRequestId;
        boolean savedDeferred = currentRequestDeferred;
        boolean savedAvailable = deferralAvailable;
        currentRequestId = null;
        currentRequestDeferred = false;
        deferralAvailable = false;
        try {
            return handler.handle(params);
        } finally {
            currentRequestId = savedId;
            currentRequestDeferred = savedDeferred;
            deferralAvailable = savedAvailable;
        }
    }

    /**
     * Handle a raw JSON-RPC request string.
     * Returns null for notifications (requests without an id).
     * Returns a JSON string for normal requests or batch requests.
     * Returns the {@link #DEFERRED} sentinel -- never null -- when the handler claimed its
     * response and will answer from a scheduled task on a later flush.
     */
    public String handle(String json) {
        // No queue was involved, so nothing has been spent on the caller's wall yet.
        return handle(json, System.nanoTime());
    }

    /**
     * Handle a raw JSON-RPC request string that arrived at {@code enqueuedNanos}.
     *
     * <p>The stamp is what {@link #remainingBudgetMs()} measures from, and it is threaded in from
     * {@code CommandQueue} rather than read here because here is already too late: by the time
     * this runs, the command has waited for a flush and the caller's wall has been running the
     * whole time (29-REVIEW.md, WR-04).
     */
    public String handle(String json, long enqueuedNanos) {
        long savedEnqueuedNanos = currentRequestEnqueuedNanos;
        boolean savedTimed = currentRequestTimed;
        currentRequestEnqueuedNanos = enqueuedNanos;
        currentRequestTimed = true;
        try {
            return handleTimed(json);
        } finally {
            currentRequestEnqueuedNanos = savedEnqueuedNanos;
            currentRequestTimed = savedTimed;
        }
    }

    private String handleTimed(String json) {
        deferralTaken = false;
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            return gson.toJson(JsonRpcResponse.error(
                JsonNull.INSTANCE, JsonRpcError.PARSE_ERROR, "Parse error"));
        }

        if (parsed.isJsonArray()) {
            return handleBatch(parsed.getAsJsonArray());
        }

        if (parsed.isJsonObject()) {
            JsonObject result = handleSingle(parsed.getAsJsonObject());
            // Ordering is the whole point: null is ALREADY taken -- it means notification, it is
            // already mapped to HTTP 204 at HttpRpcServer.java:140-147, and a green test pins it.
            // A deferral has to be distinguishable from that by construction.
            if (deferralTaken) {
                return DEFERRED;
            }
            return result == null ? null : gson.toJson(result);
        }

        return gson.toJson(JsonRpcResponse.error(
            JsonNull.INSTANCE, JsonRpcError.INVALID_REQUEST, "Invalid Request"));
    }

    private String handleBatch(JsonArray batch) {
        if (batch.isEmpty()) {
            return gson.toJson(JsonRpcResponse.error(
                JsonNull.INSTANCE, JsonRpcError.INVALID_REQUEST, "Invalid Request: empty batch"));
        }

        // A batch answers with ONE array, built here and shipped when the loop ends. An element
        // that deferred would have no place in it, and there is no second array to send later, so
        // deferral is switched off for the batch's whole duration and its handlers take their
        // accepted-and-queued path instead (D-29-15).
        boolean savedAvailable = deferralAvailable;
        deferralAvailable = false;
        try {
            JsonArray responses = new JsonArray();
            for (JsonElement element : batch) {
                if (!element.isJsonObject()) {
                    responses.add(JsonRpcResponse.error(
                        JsonNull.INSTANCE, JsonRpcError.INVALID_REQUEST, "Invalid Request"));
                    continue;
                }
                JsonObject result = handleSingle(element.getAsJsonObject());
                if (result != null) {
                    responses.add(result);
                }
            }

            if (responses.isEmpty()) {
                return null; // all notifications
            }
            return gson.toJson(responses);
        } finally {
            deferralAvailable = savedAvailable;
        }
    }

    private JsonObject handleSingle(JsonObject request) {
        // Validate jsonrpc field
        JsonElement jsonrpcField = request.get("jsonrpc");
        if (jsonrpcField == null || !"2.0".equals(jsonrpcField.getAsString())) {
            JsonElement id = request.get("id");
            return JsonRpcResponse.error(
                id != null ? id : JsonNull.INSTANCE,
                JsonRpcError.INVALID_REQUEST, "Invalid Request: missing or invalid jsonrpc version");
        }

        // Validate method field
        JsonElement methodField = request.get("method");
        if (methodField == null || !methodField.isJsonPrimitive() || !methodField.getAsJsonPrimitive().isString()) {
            JsonElement id = request.get("id");
            return JsonRpcResponse.error(
                id != null ? id : JsonNull.INSTANCE,
                JsonRpcError.INVALID_REQUEST, "Invalid Request: missing or invalid method");
        }

        String method = methodField.getAsString();
        JsonElement idField = request.get("id");
        boolean isNotification = (idField == null);

        // Get params (default to empty object)
        JsonObject params;
        JsonElement paramsField = request.get("params");
        if (paramsField == null || paramsField.isJsonNull()) {
            params = new JsonObject();
        } else if (paramsField.isJsonObject()) {
            params = paramsField.getAsJsonObject();
        } else {
            if (isNotification) return null;
            return JsonRpcResponse.error(idField, JsonRpcError.INVALID_PARAMS,
                "Invalid params: must be an object");
        }

        // Look up handler
        MethodHandler handler = handlers.get(method);
        if (handler == null) {
            if (isNotification) return null;
            return JsonRpcResponse.error(idField, JsonRpcError.METHOD_NOT_FOUND,
                "Method not found: " + method);
        }

        // Execute.
        //
        // The claimable slot opens here and is closed in the finally below. `isNotification ?
        // null` is what implements the notification clause of D-29-15 by construction: a
        // notification has no id to answer with, so it never has a claimable slot at all and
        // deferralAvailable() is false for it without a separate check.
        boolean deferredReturn = false;
        currentRequestId = isNotification ? null : idField;
        currentRequestDeferred = false;
        try {
            JsonElement result = handler.handle(params);
            if (currentRequestDeferred) {
                // The handler claimed this response. Its return value is discarded; handle(String)
                // turns the still-true deferralTaken into the DEFERRED sentinel.
                deferredReturn = true;
                return null;
            }
            if (isNotification) return null;
            return JsonRpcResponse.success(idField, result);
        } catch (RpcException e) {
            if (isNotification) return null;
            return JsonRpcResponse.errorWithData(idField, e.getCode(), e.getMessage(), e.getData());
        } catch (IllegalArgumentException e) {
            if (isNotification) return null;
            return JsonRpcResponse.error(idField, JsonRpcError.INVALID_PARAMS,
                "Invalid params: " + e.getMessage());
        } catch (Exception e) {
            if (isNotification) return null;
            return JsonRpcResponse.error(idField, JsonRpcError.INTERNAL_ERROR,
                "Internal error: " + e.getMessage());
        } finally {
            if (currentRequestDeferred && !deferredReturn) {
                // The handler claimed the response and THEN threw. The catch chain above has
                // already built a real error response for this id, so the claim has to be
                // withdrawn: otherwise handle(String) would answer the queue with the sentinel,
                // drainAndExecute would bind a registry entry nothing will ever complete, and the
                // error the caller is owed would be discarded in favour of a hang.
                discardDeferred();
                deferralTaken = false;
            }
            // A slot that survives the handler is a slot a task scheduled three flushes later
            // could steal.
            currentRequestId = null;
            currentRequestDeferred = false;
        }
    }
}
