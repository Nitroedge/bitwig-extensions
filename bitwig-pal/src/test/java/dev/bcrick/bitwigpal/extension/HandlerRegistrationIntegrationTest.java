package dev.bcrick.bitwigpal.extension;

import com.bitwig.extension.controller.api.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.bcrick.bitwigpal.handlers.*;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
import dev.bcrick.bitwigpal.rpc.TaskScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HandlerRegistrationIntegrationTest {

    // Bitwig API mocks — one per unique type needed by handler constructors
    @Mock private Application mockApplication;
    @Mock private ControllerHost mockHost;
    @Mock private Transport mockTransport;
    @Mock private TrackBank mockTrackBank;
    @Mock private SceneBank mockSceneBank;
    @Mock private CursorTrack mockCursorTrack;
    @Mock private CursorDevice mockCursorDevice;
    @Mock private CursorDevice mockMasterCursorDevice;
    @Mock private CursorRemoteControlsPage mockRemoteControlsPage;
    @Mock private CursorRemoteControlsPage mockMasterRemoteControlsPage;
    @Mock private DrumPadBank mockDrumPadBank;
    @Mock private MasterTrack mockMasterTrack;
    @Mock private Clip mockCursorClip;
    @Mock private Clip mockArrangerClip;
    @Mock private Project mockProject;
    @Mock private NoteInput mockNoteInput;
    @Mock private Arpeggiator mockArpeggiator;
    @Mock private NoteLatch mockNoteLatch;
    @Mock private PopupBrowser mockPopupBrowser;
    @Mock private Arranger mockArranger;
    @Mock private CueMarkerBank mockCueMarkerBank;
    @Mock private ScrollbarModel mockScrollbar;
    @Mock private DetailEditor mockDetailEditor;
    @Mock private ScrollbarModel mockDetailScrollbar;
    @Mock private SettableBooleanValue mockIsPinned;
    @Mock private StringValue mockCursorTrackType;

    @TempDir
    Path tempDir;

    private JsonRpcDispatcher dispatcher;
    private static final TaskScheduler IMMEDIATE_SCHEDULER = (task, delayMs) -> task.run();

    @BeforeEach
    void setUp() throws IOException {
        // TrackBank.sceneBank() needed by ClipHandler and SceneHandler
        when(mockTrackBank.sceneBank()).thenReturn(mockSceneBank);

        // CursorTrack properties needed by TrackHandler constructor markInterested() calls
        when(mockCursorTrack.isPinned()).thenReturn(mockIsPinned);
        when(mockCursorTrack.trackType()).thenReturn(mockCursorTrackType);

        // DetailEditor scrollbar
        when(mockDetailEditor.getHorizontalScrollbarModel()).thenReturn(mockDetailScrollbar);

        dispatcher = new JsonRpcDispatcher();

        // Register built-in methods (mirrors BitwigPalExtension.init)
        StateCache stateCache = new StateCache();
        dispatcher.register("session/snapshot", params -> stateCache.getSnapshot());
        dispatcher.register("api/list", params -> {
            JsonArray methods = new JsonArray();
            for (String method : dispatcher.getRegisteredMethods()) {
                methods.add(new JsonPrimitive(method));
            }
            return methods;
        });

        // Register every handler in the same order as BitwigPalExtension.init(). This mirror is
        // load-bearing: when ArrangerClipHandler was added to init() and not to this list, the
        // only count assertion below was a loose `> 200` and nothing failed and nothing was
        // reported (deferred item D6-DEF-01). The per-namespace tests further down are what turn
        // a future omission into a red test instead of a silent one, so a handler added to init()
        // must gain a line here AND a namespace test.
        DeviceLibrary deviceLibrary = new DeviceLibrary(tempDir);

        new ApplicationHandler(mockApplication, mockHost, mockTrackBank).register(dispatcher);
        new TransportHandler(mockTransport, stateCache).register(dispatcher);
        TrackBankManager trackBankManager = new TrackBankManager(mockTrackBank, 8);
        new TrackHandler(mockTrackBank, mockApplication, mockCursorTrack, trackBankManager, stateCache, mockNoteInput).register(dispatcher);
        new MasterHandler(mockMasterTrack).register(dispatcher);
        new ClipHandler(mockTrackBank, mockSceneBank, mockCursorClip, stateCache).register(dispatcher);
        new DeviceHandler(mockCursorTrack, mockCursorDevice, mockRemoteControlsPage, mockDrumPadBank, deviceLibrary, mockTransport, mockHost, (task, delay) -> task.run()).register(dispatcher);
        new NoteHandler(mockCursorClip, stateCache).register(dispatcher);
        new ArrangerClipHandler(mockArrangerClip, stateCache).register(dispatcher);
        new SceneHandler(mockSceneBank, mockProject, stateCache).register(dispatcher);
        new ArrangerHandler(mockArranger, mockTransport, mockCueMarkerBank, mockScrollbar, stateCache).register(dispatcher);
        new MasterDeviceHandler(mockMasterTrack, mockMasterCursorDevice, mockMasterRemoteControlsPage, deviceLibrary, (task, delay) -> task.run()).register(dispatcher);
        new SendHandler(mockTrackBank, 4).register(dispatcher);
        new ProjectHandler(mockProject, stateCache).register(dispatcher);
        new TransactionHandler(dispatcher, stateCache).register(dispatcher);
        new BrowserHandler(mockPopupBrowser, mockCursorDevice, stateCache).register(dispatcher);
        new NoteInputHandler(mockNoteInput, mockArpeggiator, mockNoteLatch, stateCache).register(dispatcher);
        new DetailEditorHandler(mockDetailEditor).register(dispatcher);
        new MacroHandler(dispatcher, stateCache, IMMEDIATE_SCHEDULER).register(dispatcher);
    }

    // --- Wiring verification ---

    @Test
    void allHandlersRegisterSuccessfully() {
        Set<String> methods = dispatcher.getRegisteredMethods();
        // A floor, not a mirror — deliberately kept loose, because a handler-by-handler exact
        // count restated here would go stale on every new RPC method and would be re-typed
        // rather than re-derived. What actually catches an omission is a namespace test below: a
        // handler missing from setUp() loses its whole prefix and fails there. Note that the
        // namespace tests do not yet cover every handler — a handler whose prefix has no test is
        // a handler this class can still lose silently.
        assertTrue(methods.size() > 200, "Expected 200+ methods, got " + methods.size());
    }

    @Test
    void apiListReturnsAllMethods() {
        String response = rpc("api/list", "{}");
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(json.has("result"));
        JsonArray methods = json.getAsJsonArray("result");
        assertEquals(dispatcher.getRegisteredMethods().size(), methods.size());
    }

    // --- Namespace presence ---

    @Test
    void applicationNamespaceRegistered() {
        assertNamespacePresent("app/");
    }

    @Test
    void transportNamespaceRegistered() {
        assertNamespacePresent("transport/");
    }

    @Test
    void trackNamespaceRegistered() {
        assertNamespacePresent("track/");
    }

    @Test
    void clipNamespaceRegistered() {
        assertNamespacePresent("clip/");
    }

    /**
     * The test that would have failed when {@code ArrangerClipHandler} was added to
     * {@code init()} and not to this class's {@code setUp()} (D6-DEF-01). The `clip/` test above
     * does not cover it: `arrangerClip/` is a separate namespace on a separate Clip object.
     */
    @Test
    void arrangerClipNamespaceRegistered() {
        assertNamespacePresent("arrangerClip/");
    }

    @Test
    void deviceNamespaceRegistered() {
        assertNamespacePresent("device/");
    }

    @Test
    void masterDeviceNamespaceRegistered() {
        assertNamespacePresent("masterDevice/");
    }

    @Test
    void macroNamespaceRegistered() {
        assertNamespacePresent("macro/");
    }

    @Test
    void sessionNamespaceRegistered() {
        assertNamespacePresent("session/");
    }

    // --- End-to-end pipeline ---

    @Test
    void sampleRpcCallReturnsValidJsonRpcResponse() {
        String response = rpc("api/list", "{}");
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        assertEquals("2.0", json.get("jsonrpc").getAsString());
        assertTrue(json.has("result"));
        assertEquals(1, json.get("id").getAsInt());
    }

    @Test
    void unknownMethodReturnsError() {
        String response = rpc("nonexistent/method", "{}");
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(json.has("error"));
        assertEquals(-32601, json.getAsJsonObject("error").get("code").getAsInt());
    }

    // --- Helpers ---

    private String rpc(String method, String params) {
        return dispatcher.handle(
            "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}");
    }

    private void assertNamespacePresent(String prefix) {
        boolean found = dispatcher.getRegisteredMethods().stream()
            .anyMatch(m -> m.startsWith(prefix));
        assertTrue(found, "No methods found with prefix '" + prefix + "'");
    }
}
