package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.InsertionPoint;
import com.bitwig.extension.controller.api.MasterTrack;
import com.bitwig.extension.controller.api.RemoteControl;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableIntegerValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MasterDeviceHandlerTest {

    @Mock private MasterTrack mockMasterTrack;
    @Mock private CursorDevice mockCursorDevice;
    @Mock private CursorRemoteControlsPage mockRemoteControlsPage;
    @Mock private DeviceLibrary mockDeviceLibrary;

    // Chain mocks
    @Mock private SettableBooleanValue mockDeviceEnabled;
    @Mock private SettableIntegerValue mockPageIndex;
    @Mock private RemoteControl mockRemoteControl;
    @Mock private SettableRangedValue mockParamValue;
    @Mock private InsertionPoint mockInsertionPoint;

    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new JsonRpcDispatcher();
        new MasterDeviceHandler(mockMasterTrack, mockCursorDevice,
            mockRemoteControlsPage, mockDeviceLibrary,
            (task, delay) -> task.run()).register(dispatcher);

        // Common stubs
        when(mockRemoteControlsPage.getParameter(0)).thenReturn(mockRemoteControl);
    }

    // --- Registration ---

    @Test
    void registersTwentyOneMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("masterDevice/selectNext"));
        assertTrue(methods.contains("masterDevice/selectPrevious"));
        assertTrue(methods.contains("masterDevice/setEnabled"));
        assertTrue(methods.contains("masterDevice/insertBitwigDevice"));
        assertTrue(methods.contains("masterDevice/insertPluginDevice"));
        assertTrue(methods.contains("masterDevice/insertFile"));
        assertTrue(methods.contains("masterDevice/remove"));
        assertTrue(methods.contains("masterDevice/selectPage"));
        assertTrue(methods.contains("masterDevice/nextPage"));
        assertTrue(methods.contains("masterDevice/previousPage"));
        assertTrue(methods.contains("masterDevice/setParameterValue"));
        assertTrue(methods.contains("masterDevice/enterSlot"));
        assertTrue(methods.contains("masterDevice/exitToParent"));
        assertTrue(methods.contains("masterDevice/enterLayer"));
        assertTrue(methods.contains("masterDevice/enterKeyPad"));
        assertTrue(methods.contains("masterDevice/selectPageByTag"));
        assertTrue(methods.contains("masterDevice/setParameterMapping"));
        assertTrue(methods.contains("masterDevice/getParameterMapping"));
        assertTrue(methods.contains("masterDevice/getRemoteControlPages"));
        assertTrue(methods.contains("masterDevice/setRemoteControlValues"));
        assertTrue(methods.contains("masterDevice/getPanelParameters"));
        assertTrue(methods.contains("masterDevice/setPanelParameter"));
        // 24 before Phase 27, plus the four remote-control page and panel parameter routes,
        // plus Phase 29 plan 29-04's masterDevice/insertFile.
        assertEquals(29, methods.size());
    }

    // --- Phase 27: parked remote-control pages and panel parameters ---

    @Test
    void phase27Routes_throughTheLegacyOverloadAnswerUnavailableRatherThanANullDereference() {
        String pages = dispatcher.handle(rpc("masterDevice/getRemoteControlPages", "{}"));
        assertContains(pages, "-32603");
        assertContains(pages, "REMOTE_CONTROLS_UNAVAILABLE");
        String write = dispatcher.handle(rpc("masterDevice/setRemoteControlValues",
            "{\"pages\":[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.5}]}]}"));
        assertContains(write, "REMOTE_CONTROLS_UNAVAILABLE");
        String panel = dispatcher.handle(rpc("masterDevice/getPanelParameters", "{}"));
        assertContains(panel, "-32603");
        assertContains(panel, "PANEL_PARAMETERS_UNAVAILABLE");
        String set = dispatcher.handle(rpc("masterDevice/setPanelParameter",
            "{\"id\":\"a\",\"value\":0.5}"));
        assertContains(set, "PANEL_PARAMETERS_UNAVAILABLE");
    }

    @Test
    void phase27Routes_delegateToTheMasterHelpers() {
        CursorDevice device = mock(CursorDevice.class, RETURNS_DEEP_STUBS);
        com.bitwig.extension.callback.StringArrayValueChangedCallback[] idCallback =
            new com.bitwig.extension.callback.StringArrayValueChangedCallback[1];
        doAnswer(inv -> {
            idCallback[0] = inv.getArgument(0);
            return null;
        }).when(device).addDirectParameterIdObserver(any());
        ParkedRemoteControls parked = ParkedRemoteControls.create(
            device, "secondo-master-page-", (task, delay) -> task.run());
        DirectParameters direct = DirectParameters.attach(device);
        JsonRpcDispatcher wired = new JsonRpcDispatcher();
        new MasterDeviceHandler(mockMasterTrack, device, mockRemoteControlsPage, mockDeviceLibrary,
            (task, delay) -> task.run(), parked, direct).register(wired);

        for (int p = 0; p < 8; p++) {
            verify(device).createCursorRemoteControlsPage("secondo-master-page-" + p, 8, "");
        }
        String pages = wired.handle(rpc("masterDevice/getRemoteControlPages", "{}"));
        assertContains(pages, "\"cursorCount\":8");
        String write = wired.handle(rpc("masterDevice/setRemoteControlValues",
            "{\"pages\":[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.5}]}]}"));
        assertContains(write, "REMOTE_PAGE_OUT_OF_REACH: page 0");
        String panel = wired.handle(rpc("masterDevice/getPanelParameters", "{}"));
        assertContains(panel, "PANEL_IDS_UNOBSERVED");

        idCallback[0].valueChanged(new String[] {"a"});
        String unknown = wired.handle(rpc("masterDevice/setPanelParameter",
            "{\"id\":\"b\",\"value\":0.5}"));
        assertContains(unknown, "-32602");
        assertContains(unknown, "PANEL_PARAMETER_ID_UNKNOWN: b");
        verify(device, never()).setDirectParameterValueNormalized(any(), any(), any());
        String set = wired.handle(rpc("masterDevice/setPanelParameter",
            "{\"id\":\"a\",\"value\":1.0}"));
        assertContains(set, "\"ok\":true");
        verify(device).setDirectParameterValueNormalized(eq("a"), any(), any());
    }

    // --- setEnabled validation ---

    @Test
    void setEnabled_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/setEnabled", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "enabled");
    }

    // --- insertBitwigDevice validation ---

    @Test
    void insertBitwigDevice_missingName_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/insertBitwigDevice", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "name");
    }

    // --- insertPluginDevice validation ---

    @Test
    void insertPluginDevice_missingType_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/insertPluginDevice", "{\"id\":\"abc\"}"));
        assertContains(response, "-32602");
        assertContains(response, "type");
    }

    @Test
    void insertPluginDevice_missingId_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/insertPluginDevice", "{\"type\":\"vst3\"}"));
        assertContains(response, "-32602");
        assertContains(response, "id");
    }

    // --- enterSlot validation ---

    @Test
    void enterSlot_missingName_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/enterSlot", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "name");
    }

    // --- selectPage validation ---

    @Test
    void selectPage_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/selectPage", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    // --- setParameterValue validation ---

    @Test
    void setParameterValue_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/setParameterValue", "{\"value\":0.5}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void setParameterValue_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/setParameterValue", "{\"index\":0}"));
        assertContains(response, "-32602");
        assertContains(response, "value");
    }

    @Test
    void setParameterValue_indexOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/setParameterValue", "{\"index\":8,\"value\":0.5}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- masterDevice/enterLayer validation ---

    @Test
    void enterLayer_missingBothParams_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/enterLayer", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "must provide");
    }

    @Test
    void enterLayer_bothParams_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/enterLayer", "{\"index\":0,\"name\":\"Layer 1\"}"));
        assertContains(response, "-32602");
        assertContains(response, "mutually exclusive");
    }

    // --- masterDevice/enterKeyPad validation ---

    @Test
    void enterKeyPad_missingKey_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/enterKeyPad", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "key");
    }

    @Test
    void enterKeyPad_keyOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/enterKeyPad", "{\"key\":128}"));
        assertContains(response, "-32602");
        assertContains(response, "0-127");
    }

    // --- masterDevice/selectPageByTag validation ---

    @Test
    void selectPageByTag_missingTag_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/selectPageByTag", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "tag");
    }

    @Test
    void selectPageByTag_invalidTag_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/selectPageByTag", "{\"tag\":\"bogus\"}"));
        assertContains(response, "-32602");
        assertContains(response, "Invalid page tag");
    }

    // --- Behavioral tests (Mockito) — Device navigation ---

    @Test
    void selectNext_callsCursorDeviceSelectNext() {
        dispatcher.handle(rpc("masterDevice/selectNext", "{}"));
        verify(mockCursorDevice).selectNext();
    }

    @Test
    void selectPrevious_callsCursorDeviceSelectPrevious() {
        dispatcher.handle(rpc("masterDevice/selectPrevious", "{}"));
        verify(mockCursorDevice).selectPrevious();
    }

    @Test
    void setEnabled_callsCursorDeviceIsEnabledSet() {
        when(mockCursorDevice.isEnabled()).thenReturn(mockDeviceEnabled);
        dispatcher.handle(rpc("masterDevice/setEnabled", "{\"enabled\":true}"));
        verify(mockDeviceEnabled).set(true);
    }

    @Test
    void remove_callsDeleteObjectThenSelectFirstInChannel() {
        dispatcher.handle(rpc("masterDevice/remove", "{}"));
        verify(mockCursorDevice).deleteObject();
        verify(mockCursorDevice).selectFirstInChannel(mockMasterTrack);
    }

    // --- Behavioral tests (Mockito) — Page navigation ---

    @Test
    void selectPage_callsRemoteControlsPageSelectedPageIndexSet() {
        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        dispatcher.handle(rpc("masterDevice/selectPage", "{\"index\":3}"));
        verify(mockPageIndex).set(3);
    }

    @Test
    void nextPage_callsRemoteControlsPageSelectNextPage() {
        dispatcher.handle(rpc("masterDevice/nextPage", "{}"));
        verify(mockRemoteControlsPage).selectNextPage(false);
    }

    @Test
    void previousPage_callsRemoteControlsPageSelectPreviousPage() {
        dispatcher.handle(rpc("masterDevice/previousPage", "{}"));
        verify(mockRemoteControlsPage).selectPreviousPage(false);
    }

    // --- Behavioral tests (Mockito) — Parameter mutation ---

    @Test
    void setParameterValue_callsParamValueSetImmediately() {
        when(mockRemoteControl.value()).thenReturn(mockParamValue);
        dispatcher.handle(rpc("masterDevice/setParameterValue", "{\"index\":0,\"value\":0.5}"));
        verify(mockParamValue).setImmediately(0.5);
    }

    // --- Behavioral tests (Mockito) — Device insertion ---

    @Test
    void insertBitwigDevice_callsInsertionPointInsertFile() {
        when(mockDeviceLibrary.resolve("Reverb")).thenReturn(Path.of("/devices/Reverb.bwdevice"));
        when(mockMasterTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("masterDevice/insertBitwigDevice", "{\"name\":\"Reverb\"}"));
        verify(mockInsertionPoint).insertFile(Path.of("/devices/Reverb.bwdevice").toString());
    }

    @Test
    void insertPluginDevice_vst3_callsInsertionPointInsertVST3Device() {
        when(mockMasterTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("masterDevice/insertPluginDevice", "{\"type\":\"vst3\",\"id\":\"com.example.master\"}"));
        verify(mockInsertionPoint).insertVST3Device("com.example.master");
    }

    @Test
    void insertBitwigDevice_afterPosition_callsAfterInsertionPoint() {
        when(mockDeviceLibrary.resolve("EQ-5")).thenReturn(Path.of("/devices/EQ-5.bwdevice"));
        when(mockCursorDevice.afterDeviceInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("masterDevice/insertBitwigDevice", "{\"name\":\"EQ-5\",\"position\":\"after\"}"));
        verify(mockInsertionPoint).insertFile(Path.of("/devices/EQ-5.bwdevice").toString());
    }

    // --- Phase 29 (29-04): masterDevice/insertFile, the master-chain twin ---
    //
    // The twin resolves the MASTER track's end-of-chain insertion point; everything else is the
    // same body and the SAME shared rule (InsertFilePathValidator, D-29-23). The refusal table is
    // mirrored rather than shared across test classes, matching how these suites are already
    // written. Every refusal asserts the insertion point was never touched (T-29-10).

    @TempDir
    Path presetDir;

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\") + "\"";
    }

    /** A refusal must land before the Bitwig call, not after it. */
    private void assertInsertFileRefused(String path, String expectedMessage) {
        when(mockMasterTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);
        String response = dispatcher.handle(rpc("masterDevice/insertFile",
            "{\"path\":" + jsonString(path) + "}"));
        assertContains(response, "-32602");
        assertContains(response, expectedMessage);
        verify(mockInsertionPoint, never()).insertFile(anyString());
    }

    @Test
    void masterInsertFile_relativePath_refusedBeforeTheBitwigCall() {
        assertInsertFileRefused("presets\\Organ Echo.bwpreset",
            "preset file path is not absolute: ");
    }

    @Test
    void masterInsertFile_twoBackslashPath_refusedAsNetworkPath() {
        assertInsertFileRefused("\\\\server\\share\\p.bwpreset",
            "preset file path is a network path: ");
    }

    @Test
    void masterInsertFile_twoForwardSlashPath_refusedAsNetworkPath() {
        assertInsertFileRefused("//server/share/p.bwpreset",
            "preset file path is a network path: ");
    }

    @Test
    void masterInsertFile_backslashThenSlashUncPath_refusedAsNetworkPath() {
        assertInsertFileRefused("\\/srv/share/p.bwpreset",
            "preset file path is a network path: ");
    }

    @Test
    void masterInsertFile_slashThenBackslashUncPath_refusedAsNetworkPath() {
        assertInsertFileRefused("/\\srv\\share\\p.bwpreset",
            "preset file path is a network path: ");
    }

    @Test
    void masterInsertFile_questionMarkUncPrefix_refusedAsNetworkPath() {
        assertInsertFileRefused("\\\\?\\UNC\\srv\\s\\p.bwpreset",
            "preset file path is a network path: ");
    }

    @Test
    void masterInsertFile_dotUncPrefix_refusedAsNetworkPath() {
        assertInsertFileRefused("\\\\.\\UNC\\srv\\s\\p.bwpreset",
            "preset file path is a network path: ");
    }

    @Test
    void masterInsertFile_questionMarkLocalDevicePath_refusedAsNetworkPath() {
        assertInsertFileRefused("\\\\?\\C:\\presets\\p.bwpreset",
            "preset file path is a network path: ");
    }

    /** IN-02 generalised: a final component that is the extension and nothing else is not a name. */
    @Test
    void masterInsertFile_fileNamedOnlyTheExtension_refusedForItsExtension() throws IOException {
        Path onlyExtension = Files.writeString(presetDir.resolve(".bwpreset"), "x");
        assertInsertFileRefused(onlyExtension.toString(),
            "preset file path does not end in .bwpreset: ");
    }

    @Test
    void masterInsertFile_missingFile_refusedAsNotAnExistingFile() {
        assertInsertFileRefused(presetDir.resolve("missing.bwpreset").toString(),
            "preset file path is not an existing file: ");
    }

    @Test
    void masterInsertFile_forwardSlashDrivePath_reachesTheMasterInsertionPointWithThePathAsGiven()
            throws IOException {
        Path preset = Files.writeString(presetDir.resolve("forward.bwpreset"), "x");
        String forwardSlashPath = preset.toString().replace('\\', '/');
        when(mockMasterTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);

        String response = dispatcher.handle(rpc("masterDevice/insertFile",
            "{\"path\":" + jsonString(forwardSlashPath) + "}"));

        assertContains(response, "\"ok\"");
        verify(mockInsertionPoint).insertFile(forwardSlashPath);
    }

    /** The twin's one difference: "end" is the MASTER track's end-of-chain insertion point. */
    @Test
    void masterInsertFile_defaultPosition_isTheMasterTracksEndOfDeviceChain() throws IOException {
        Path preset = Files.writeString(presetDir.resolve("default.bwpreset"), "x");
        when(mockMasterTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);

        dispatcher.handle(rpc("masterDevice/insertFile",
            "{\"path\":" + jsonString(preset.toString()) + "}"));

        verify(mockMasterTrack).endOfDeviceChainInsertionPoint();
        verify(mockInsertionPoint).insertFile(preset.toString());
    }

    @Test
    void masterInsertFile_afterPosition_reachesTheAfterDeviceInsertionPoint() throws IOException {
        Path preset = Files.writeString(presetDir.resolve("after.bwpreset"), "x");
        when(mockCursorDevice.afterDeviceInsertionPoint()).thenReturn(mockInsertionPoint);

        dispatcher.handle(rpc("masterDevice/insertFile",
            "{\"path\":" + jsonString(preset.toString()) + ",\"position\":\"after\"}"));

        verify(mockInsertionPoint).insertFile(preset.toString());
    }

    @Test
    void masterInsertFile_unknownPosition_refusedBeforeAnyInsertionPointIsResolved()
            throws IOException {
        Path preset = Files.writeString(presetDir.resolve("sideways.bwpreset"), "x");

        String response = dispatcher.handle(rpc("masterDevice/insertFile",
            "{\"path\":" + jsonString(preset.toString()) + ",\"position\":\"sideways\"}"));

        assertContains(response, "-32602");
        // Gson escapes the message's single quotes as \u0027, so assert the quote-free parts.
        assertContains(response, "position must be ");
        assertContains(response, "got: sideways");
        verify(mockMasterTrack, never()).endOfDeviceChainInsertionPoint();
        verify(mockCursorDevice, never()).beforeDeviceInsertionPoint();
        verify(mockCursorDevice, never()).afterDeviceInsertionPoint();
    }

    // --- Behavioral tests (Mockito) — Chain navigation ---

    @Test
    void enterSlot_callsCursorDeviceSelectFirstInSlot() {
        dispatcher.handle(rpc("masterDevice/enterSlot", "{\"name\":\"FX Layer\"}"));
        verify(mockCursorDevice).selectFirstInSlot("FX Layer");
    }

    @Test
    void exitToParent_callsCursorDeviceSelectParent() {
        dispatcher.handle(rpc("masterDevice/exitToParent", "{}"));
        verify(mockCursorDevice).selectParent();
    }

    @Test
    void enterLayer_byIndex_callsCursorDeviceSelectFirstInLayer() {
        dispatcher.handle(rpc("masterDevice/enterLayer", "{\"index\":1}"));
        verify(mockCursorDevice).selectFirstInLayer(1);
    }

    @Test
    void enterLayer_byName_callsCursorDeviceSelectFirstInLayer() {
        dispatcher.handle(rpc("masterDevice/enterLayer", "{\"name\":\"Layer A\"}"));
        verify(mockCursorDevice).selectFirstInLayer("Layer A");
    }

    @Test
    void enterKeyPad_callsCursorDeviceSelectFirstInKeyPad() {
        dispatcher.handle(rpc("masterDevice/enterKeyPad", "{\"key\":60}"));
        verify(mockCursorDevice).selectFirstInKeyPad(60);
    }

    // --- Behavioral tests (Mockito) — Page tag filtering ---

    @Test
    void selectPageByTag_next_callsSelectNextPageMatching() {
        dispatcher.handle(rpc("masterDevice/selectPageByTag", "{\"tag\":\"eq\"}"));
        verify(mockRemoteControlsPage).selectNextPageMatching("eq", true);
    }

    @Test
    void selectPageByTag_previous_callsSelectPreviousPageMatching() {
        dispatcher.handle(rpc("masterDevice/selectPageByTag", "{\"tag\":\"lfo\",\"direction\":\"previous\"}"));
        verify(mockRemoteControlsPage).selectPreviousPageMatching("lfo", true);
    }

    // --- masterDevice/setParameters validation ---

    @Test
    void setParameters_missingPages_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/setParameters", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "pages");
    }

    @Test
    void setParameters_emptyPages_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/setParameters", "{\"pages\":[]}"));
        assertContains(response, "-32602");
        assertContains(response, "empty");
    }

    @Test
    void setParameters_paramIndexOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/setParameters",
            "{\"pages\":[{\"pageIndex\":0,\"params\":[{\"index\":8,\"value\":0.5}]}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    @Test
    void setParameters_paramValueOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("masterDevice/setParameters",
            "{\"pages\":[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":1.5}]}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- masterDevice/setParameters behavioral ---

    @Test
    void setParameters_singlePage_setsParametersImmediately() {
        List<Runnable> scheduledTasks = new ArrayList<>();
        JsonRpcDispatcher localDispatcher = new JsonRpcDispatcher();
        new MasterDeviceHandler(mockMasterTrack, mockCursorDevice,
            mockRemoteControlsPage, mockDeviceLibrary,
            (task, delay) -> scheduledTasks.add(task)).register(localDispatcher);

        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        RemoteControl mockParam0 = mock(RemoteControl.class);
        RemoteControl mockParam1 = mock(RemoteControl.class);
        SettableRangedValue mockVal0 = mock(SettableRangedValue.class);
        SettableRangedValue mockVal1 = mock(SettableRangedValue.class);
        when(mockRemoteControlsPage.getParameter(0)).thenReturn(mockParam0);
        when(mockRemoteControlsPage.getParameter(1)).thenReturn(mockParam1);
        when(mockParam0.value()).thenReturn(mockVal0);
        when(mockParam1.value()).thenReturn(mockVal1);

        String response = localDispatcher.handle(rpc("masterDevice/setParameters",
            "{\"pages\":[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.25},{\"index\":1,\"value\":0.75}]}]}"));

        assertContains(response, "\"ok\":true");
        verify(mockPageIndex).set(0);
        verify(mockVal0).setImmediately(0.25);
        verify(mockVal1).setImmediately(0.75);
        assertTrue(scheduledTasks.isEmpty(), "Single page should not schedule any tasks");
    }

    @Test
    void setParameters_multiPage_schedulesSubsequentPages() {
        List<Runnable> scheduledTasks = new ArrayList<>();
        JsonRpcDispatcher localDispatcher = new JsonRpcDispatcher();
        new MasterDeviceHandler(mockMasterTrack, mockCursorDevice,
            mockRemoteControlsPage, mockDeviceLibrary,
            (task, delay) -> scheduledTasks.add(task)).register(localDispatcher);

        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        RemoteControl mockParam0 = mock(RemoteControl.class);
        RemoteControl mockParam2 = mock(RemoteControl.class);
        SettableRangedValue mockVal0 = mock(SettableRangedValue.class);
        SettableRangedValue mockVal2 = mock(SettableRangedValue.class);
        when(mockRemoteControlsPage.getParameter(0)).thenReturn(mockParam0);
        when(mockRemoteControlsPage.getParameter(2)).thenReturn(mockParam2);
        when(mockParam0.value()).thenReturn(mockVal0);
        when(mockParam2.value()).thenReturn(mockVal2);

        String response = localDispatcher.handle(rpc("masterDevice/setParameters",
            "{\"pages\":["
            + "{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.5}]},"
            + "{\"pageIndex\":1,\"params\":[{\"index\":2,\"value\":0.9}]}"
            + "]}"));

        assertContains(response, "\"ok\":true");
        verify(mockPageIndex).set(0);
        verify(mockVal0).setImmediately(0.5);
        assertEquals(1, scheduledTasks.size(), "Second page should be scheduled");

        scheduledTasks.get(0).run();
        verify(mockPageIndex).set(1);
        verify(mockVal2).setImmediately(0.9);
    }

    // --- Helpers ---

    private String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + ",\"id\":1}";
    }

    private void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected),
            "Expected '" + expected + "' in: " + actual);
    }

    // --- Preset navigation ---

    @Test
    void registersPresetNavigationMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("masterDevice/nextPreset"));
        assertTrue(methods.contains("masterDevice/previousPreset"));
        assertTrue(methods.contains("masterDevice/nextPresetCategory"));
        assertTrue(methods.contains("masterDevice/previousPresetCategory"));
        assertTrue(methods.contains("masterDevice/nextPresetCreator"));
        assertTrue(methods.contains("masterDevice/previousPresetCreator"));
    }

    @Test
    void nextPreset_callsSwitchToNextPreset() {
        dispatcher.handle(rpc("masterDevice/nextPreset", "{}"));
        verify(mockCursorDevice).switchToNextPreset();
    }

    @Test
    void previousPreset_callsSwitchToPreviousPreset() {
        dispatcher.handle(rpc("masterDevice/previousPreset", "{}"));
        verify(mockCursorDevice).switchToPreviousPreset();
    }

    @Test
    void nextPresetCategory_callsSwitchToNextPresetCategory() {
        dispatcher.handle(rpc("masterDevice/nextPresetCategory", "{}"));
        verify(mockCursorDevice).switchToNextPresetCategory();
    }

    @Test
    void previousPresetCategory_callsSwitchToPreviousPresetCategory() {
        dispatcher.handle(rpc("masterDevice/previousPresetCategory", "{}"));
        verify(mockCursorDevice).switchToPreviousPresetCategory();
    }

    @Test
    void nextPresetCreator_callsSwitchToNextPresetCreator() {
        dispatcher.handle(rpc("masterDevice/nextPresetCreator", "{}"));
        verify(mockCursorDevice).switchToNextPresetCreator();
    }

    @Test
    void previousPresetCreator_callsSwitchToPreviousPresetCreator() {
        dispatcher.handle(rpc("masterDevice/previousPresetCreator", "{}"));
        verify(mockCursorDevice).switchToPreviousPresetCreator();
    }
}
