package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.DrumPadBank;
import com.bitwig.extension.controller.api.InsertionPoint;
import com.bitwig.extension.controller.api.RemoteControl;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.bitwig.extension.controller.api.SettableIntegerValue;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.controller.api.SettableStringValue;
import com.bitwig.extension.controller.api.StringArrayValue;
import com.bitwig.extension.controller.api.StringValue;
import com.bitwig.extension.controller.api.IntegerValue;
import com.bitwig.extension.controller.api.Transport;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceHandlerTest {

    @Mock private CursorTrack mockCursorTrack;
    @Mock private CursorDevice mockCursorDevice;
    @Mock private CursorRemoteControlsPage mockRemoteControlsPage;
    @Mock private DrumPadBank mockDrumPadBank;
    @Mock private DeviceLibrary mockDeviceLibrary;
    @Mock private Transport mockTransport;
    @Mock private ControllerHost mockHost;

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
        new DeviceHandler(mockCursorTrack, mockCursorDevice, mockRemoteControlsPage,
            mockDrumPadBank, mockDeviceLibrary, mockTransport, mockHost,
            (task, delay) -> task.run()).register(dispatcher);

        // Common stubs
        when(mockRemoteControlsPage.getParameter(0)).thenReturn(mockRemoteControl);
    }

    // --- Registration ---

    @Test
    void registersFiveNewAutomationMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("device/hasAutomation"));
        assertTrue(methods.contains("device/deleteAllAutomation"));
        assertTrue(methods.contains("device/restoreAutomationControl"));
        assertTrue(methods.contains("device/touch"));
        assertTrue(methods.contains("device/writeEnvelope"));
    }

    @Test
    void registersAllExistingMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("device/selectNext"));
        assertTrue(methods.contains("device/selectPrevious"));
        assertTrue(methods.contains("device/setEnabled"));
        assertTrue(methods.contains("device/selectPage"));
        assertTrue(methods.contains("device/nextPage"));
        assertTrue(methods.contains("device/previousPage"));
        assertTrue(methods.contains("device/setParameterValue"));
        assertTrue(methods.contains("device/insertBitwigDevice"));
        assertTrue(methods.contains("device/insertPluginDevice"));
        assertTrue(methods.contains("device/listBitwigDevices"));
        assertTrue(methods.contains("device/remove"));
        assertTrue(methods.contains("cursor/selectTrack"));
    }

    @Test
    void registersChainNavMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("device/enterSlot"));
        assertTrue(methods.contains("device/exitToParent"));
    }

    @Test
    void registersDrumPadMethod() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("device/getDrumPads"));
    }

    @Test
    void registersLayerAndKeyPadMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("device/enterLayer"));
        assertTrue(methods.contains("device/enterKeyPad"));
        assertTrue(methods.contains("device/selectPageByTag"));
    }

    @Test
    void registersDiscoveryMethods() {
        var methods = dispatcher.getRegisteredMethods();
        assertTrue(methods.contains("device/discoverAll"));
        assertTrue(methods.contains("device/getDiscoveryResult"));
    }

    @Test
    void registersExactlyThirtyFourMethods() {
        assertEquals(34, dispatcher.getRegisteredMethods().size());
    }

    // --- device/hasAutomation validation ---

    @Test
    void hasAutomation_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/hasAutomation", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void hasAutomation_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("device/hasAutomation", "{\"index\": 8}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    @Test
    void hasAutomation_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/hasAutomation", "{\"index\": -1}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- device/deleteAllAutomation validation ---

    @Test
    void deleteAllAutomation_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/deleteAllAutomation", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void deleteAllAutomation_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("device/deleteAllAutomation", "{\"index\": 8}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- device/restoreAutomationControl validation ---

    @Test
    void restoreAutomationControl_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/restoreAutomationControl", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void restoreAutomationControl_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("device/restoreAutomationControl", "{\"index\": 8}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- device/touch validation ---

    @Test
    void touch_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/touch", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void touch_missingTouched_returnsError() {
        String response = dispatcher.handle(rpc("device/touch", "{\"index\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "touched");
    }

    @Test
    void touch_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("device/touch", "{\"index\": 8, \"touched\": true}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- device/writeEnvelope validation ---

    @Test
    void writeEnvelope_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/writeEnvelope", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void writeEnvelope_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("device/writeEnvelope",
            "{\"index\": 8, \"points\": [{\"position\": 0, \"value\": 0.5}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    @Test
    void writeEnvelope_negativeIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/writeEnvelope",
            "{\"index\": -1, \"points\": [{\"position\": 0, \"value\": 0.5}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    @Test
    void writeEnvelope_automationWriteDisabled_returnsError() {
        SettableBooleanValue mockAutoWriteEnabled = mock(SettableBooleanValue.class);
        when(mockTransport.isArrangerAutomationWriteEnabled()).thenReturn(mockAutoWriteEnabled);
        when(mockAutoWriteEnabled.get()).thenReturn(false);
        String response = dispatcher.handle(rpc("device/writeEnvelope",
            "{\"index\": 0, \"points\": [{\"position\": 0, \"value\": 0.5}]}"));
        assertContains(response, "automation write must be enabled");
    }

    // --- device/enterSlot validation ---

    @Test
    void enterSlot_missingName_returnsError() {
        String response = dispatcher.handle(rpc("device/enterSlot", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "name");
    }

    // --- device/setParameterValue validation ---

    @Test
    void setParameterValue_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameterValue", "{\"value\": 0.5}"));
        assertContains(response, "-32602");
        assertContains(response, "index");
    }

    @Test
    void setParameterValue_missingValue_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameterValue", "{\"index\": 0}"));
        assertContains(response, "-32602");
        assertContains(response, "value");
    }

    @Test
    void setParameterValue_indexTooHigh_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameterValue", "{\"index\": 8, \"value\": 0.5}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- device/enterLayer validation ---

    @Test
    void enterLayer_missingBothParams_returnsError() {
        String response = dispatcher.handle(rpc("device/enterLayer", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "must provide");
    }

    @Test
    void enterLayer_bothParams_returnsError() {
        String response = dispatcher.handle(rpc("device/enterLayer", "{\"index\":0,\"name\":\"Layer 1\"}"));
        assertContains(response, "-32602");
        assertContains(response, "mutually exclusive");
    }

    // --- device/enterKeyPad validation ---

    @Test
    void enterKeyPad_missingKey_returnsError() {
        String response = dispatcher.handle(rpc("device/enterKeyPad", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "key");
    }

    @Test
    void enterKeyPad_keyOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("device/enterKeyPad", "{\"key\":128}"));
        assertContains(response, "-32602");
        assertContains(response, "0-127");
    }

    @Test
    void enterKeyPad_negativeKey_returnsError() {
        String response = dispatcher.handle(rpc("device/enterKeyPad", "{\"key\":-1}"));
        assertContains(response, "-32602");
        assertContains(response, "0-127");
    }

    // --- device/selectPageByTag validation ---

    @Test
    void selectPageByTag_missingTag_returnsError() {
        String response = dispatcher.handle(rpc("device/selectPageByTag", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "tag");
    }

    @Test
    void selectPageByTag_invalidTag_returnsError() {
        String response = dispatcher.handle(rpc("device/selectPageByTag", "{\"tag\":\"bogus\"}"));
        assertContains(response, "-32602");
        assertContains(response, "Invalid page tag");
    }

    // --- VALID_PAGE_TAGS validation ---

    @Test
    void validPageTagsContainsEightTags() {
        assertEquals(8, DeviceHandler.VALID_PAGE_TAGS.size());
    }

    @Test
    void validPageTagsContainsExpectedValues() {
        assertTrue(DeviceHandler.VALID_PAGE_TAGS.contains("env"));
        assertTrue(DeviceHandler.VALID_PAGE_TAGS.contains("eq"));
        assertTrue(DeviceHandler.VALID_PAGE_TAGS.contains("filter"));
        assertTrue(DeviceHandler.VALID_PAGE_TAGS.contains("fx"));
        assertTrue(DeviceHandler.VALID_PAGE_TAGS.contains("lfo"));
        assertTrue(DeviceHandler.VALID_PAGE_TAGS.contains("mixer"));
        assertTrue(DeviceHandler.VALID_PAGE_TAGS.contains("osc"));
        assertTrue(DeviceHandler.VALID_PAGE_TAGS.contains("perf"));
    }

    // --- Behavioral tests (Mockito) — Device navigation ---

    @Test
    void selectNext_callsCursorDeviceSelectNext() {
        dispatcher.handle(rpc("device/selectNext", "{}"));
        verify(mockCursorDevice).selectNext();
    }

    @Test
    void selectPrevious_callsCursorDeviceSelectPrevious() {
        dispatcher.handle(rpc("device/selectPrevious", "{}"));
        verify(mockCursorDevice).selectPrevious();
    }

    @Test
    void setEnabled_callsCursorDeviceIsEnabledSet() {
        when(mockCursorDevice.isEnabled()).thenReturn(mockDeviceEnabled);
        dispatcher.handle(rpc("device/setEnabled", "{\"enabled\":true}"));
        verify(mockDeviceEnabled).set(true);
    }

    @Test
    void remove_callsDeleteObjectThenSelectFirstInChannel() {
        dispatcher.handle(rpc("device/remove", "{}"));
        verify(mockCursorDevice).deleteObject();
        verify(mockCursorDevice).selectFirstInChannel(mockCursorTrack);
    }

    // --- Behavioral tests (Mockito) — Page navigation ---

    @Test
    void selectPage_callsRemoteControlsPageSelectedPageIndexSet() {
        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        dispatcher.handle(rpc("device/selectPage", "{\"index\":2}"));
        verify(mockPageIndex).set(2);
    }

    @Test
    void nextPage_callsRemoteControlsPageSelectNextPage() {
        dispatcher.handle(rpc("device/nextPage", "{}"));
        verify(mockRemoteControlsPage).selectNextPage(false);
    }

    @Test
    void previousPage_callsRemoteControlsPageSelectPreviousPage() {
        dispatcher.handle(rpc("device/previousPage", "{}"));
        verify(mockRemoteControlsPage).selectPreviousPage(false);
    }

    // --- Behavioral tests (Mockito) — Parameter mutation ---

    @Test
    void setParameterValue_callsParamValueSetImmediately() {
        when(mockRemoteControl.value()).thenReturn(mockParamValue);
        dispatcher.handle(rpc("device/setParameterValue", "{\"index\":0,\"value\":0.75}"));
        verify(mockParamValue).setImmediately(0.75);
    }

    // --- Behavioral tests (Mockito) — Automation ---

    @Test
    void deleteAllAutomation_callsParamDeleteAllAutomation() {
        dispatcher.handle(rpc("device/deleteAllAutomation", "{\"index\":0}"));
        verify(mockRemoteControl).deleteAllAutomation();
    }

    @Test
    void restoreAutomationControl_callsParamRestoreAutomationControl() {
        dispatcher.handle(rpc("device/restoreAutomationControl", "{\"index\":0}"));
        verify(mockRemoteControl).restoreAutomationControl();
    }

    @Test
    void touch_callsParamTouch() {
        dispatcher.handle(rpc("device/touch", "{\"index\":0,\"touched\":true}"));
        verify(mockRemoteControl).touch(true);
    }

    // --- Behavioral tests (Mockito) — Device insertion ---

    @Test
    void insertBitwigDevice_callsInsertionPointInsertFile() {
        when(mockDeviceLibrary.resolve("E-Clap")).thenReturn(Path.of("/devices/E-Clap.bwdevice"));
        when(mockCursorTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("device/insertBitwigDevice", "{\"name\":\"E-Clap\"}"));
        verify(mockInsertionPoint).insertFile(Path.of("/devices/E-Clap.bwdevice").toString());
    }

    @Test
    void insertPluginDevice_vst3_callsInsertionPointInsertVST3Device() {
        when(mockCursorTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("device/insertPluginDevice", "{\"type\":\"vst3\",\"id\":\"com.example.synth\"}"));
        verify(mockInsertionPoint).insertVST3Device("com.example.synth");
    }

    @Test
    void insertPluginDevice_clap_callsInsertionPointInsertCLAPDevice() {
        when(mockCursorTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("device/insertPluginDevice", "{\"type\":\"clap\",\"id\":\"com.example.fx\"}"));
        verify(mockInsertionPoint).insertCLAPDevice("com.example.fx");
    }

    @Test
    void insertPluginDevice_vst2_callsInsertionPointInsertVST2Device() {
        when(mockCursorTrack.endOfDeviceChainInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("device/insertPluginDevice", "{\"type\":\"vst2\",\"id\":\"12345\"}"));
        verify(mockInsertionPoint).insertVST2Device(12345);
    }

    @Test
    void insertBitwigDevice_beforePosition_callsBeforeInsertionPoint() {
        when(mockDeviceLibrary.resolve("Delay-2")).thenReturn(Path.of("/devices/Delay-2.bwdevice"));
        when(mockCursorDevice.beforeDeviceInsertionPoint()).thenReturn(mockInsertionPoint);
        dispatcher.handle(rpc("device/insertBitwigDevice", "{\"name\":\"Delay-2\",\"position\":\"before\"}"));
        verify(mockInsertionPoint).insertFile(Path.of("/devices/Delay-2.bwdevice").toString());
    }

    // --- Behavioral tests (Mockito) — Chain navigation ---

    @Test
    void enterSlot_callsCursorDeviceSelectFirstInSlot() {
        dispatcher.handle(rpc("device/enterSlot", "{\"name\":\"FX Layer\"}"));
        verify(mockCursorDevice).selectFirstInSlot("FX Layer");
    }

    @Test
    void exitToParent_callsCursorDeviceSelectParent() {
        dispatcher.handle(rpc("device/exitToParent", "{}"));
        verify(mockCursorDevice).selectParent();
    }

    @Test
    void enterLayer_byIndex_callsCursorDeviceSelectFirstInLayer() {
        dispatcher.handle(rpc("device/enterLayer", "{\"index\":2}"));
        verify(mockCursorDevice).selectFirstInLayer(2);
    }

    @Test
    void enterLayer_byName_callsCursorDeviceSelectFirstInLayer() {
        dispatcher.handle(rpc("device/enterLayer", "{\"name\":\"Layer 1\"}"));
        verify(mockCursorDevice).selectFirstInLayer("Layer 1");
    }

    @Test
    void enterKeyPad_callsCursorDeviceSelectFirstInKeyPad() {
        dispatcher.handle(rpc("device/enterKeyPad", "{\"key\":36}"));
        verify(mockCursorDevice).selectFirstInKeyPad(36);
    }

    // --- Behavioral tests (Mockito) — Page tag filtering ---

    @Test
    void selectPageByTag_next_callsSelectNextPageMatching() {
        dispatcher.handle(rpc("device/selectPageByTag", "{\"tag\":\"filter\"}"));
        verify(mockRemoteControlsPage).selectNextPageMatching("filter", true);
    }

    @Test
    void selectPageByTag_previous_callsSelectPreviousPageMatching() {
        dispatcher.handle(rpc("device/selectPageByTag", "{\"tag\":\"osc\",\"direction\":\"previous\"}"));
        verify(mockRemoteControlsPage).selectPreviousPageMatching("osc", true);
    }

    // --- Behavioral tests (Mockito) — Cursor track ---

    @Test
    void cursorSelectTrack_next_callsCursorTrackSelectNext() {
        dispatcher.handle(rpc("cursor/selectTrack", "{\"direction\":\"next\"}"));
        verify(mockCursorTrack).selectNext();
    }

    @Test
    void cursorSelectTrack_previous_callsCursorTrackSelectPrevious() {
        dispatcher.handle(rpc("cursor/selectTrack", "{\"direction\":\"previous\"}"));
        verify(mockCursorTrack).selectPrevious();
    }

    // --- device/setParameters validation ---

    @Test
    void setParameters_missingPages_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameters", "{}"));
        assertContains(response, "-32602");
        assertContains(response, "pages");
    }

    @Test
    void setParameters_emptyPages_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameters", "{\"pages\":[]}"));
        assertContains(response, "-32602");
        assertContains(response, "empty");
    }

    @Test
    void setParameters_paramIndexOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameters",
            "{\"pages\":[{\"pageIndex\":0,\"params\":[{\"index\":8,\"value\":0.5}]}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    @Test
    void setParameters_paramValueOutOfRange_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameters",
            "{\"pages\":[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":1.5}]}]}"));
        assertContains(response, "-32602");
        assertContains(response, "out of range");
    }

    // --- device/setParameters behavioral ---

    @Test
    void setParameters_singlePage_setsParametersImmediately() {
        // Set up a capturing scheduler to verify no scheduling occurs
        List<Runnable> scheduledTasks = new ArrayList<>();
        JsonRpcDispatcher localDispatcher = new JsonRpcDispatcher();
        new DeviceHandler(mockCursorTrack, mockCursorDevice, mockRemoteControlsPage,
            mockDrumPadBank, mockDeviceLibrary, mockTransport, mockHost,
            (task, delay) -> scheduledTasks.add(task)).register(localDispatcher);

        // Stub page index and two parameters
        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        RemoteControl mockParam0 = mock(RemoteControl.class);
        RemoteControl mockParam1 = mock(RemoteControl.class);
        SettableRangedValue mockVal0 = mock(SettableRangedValue.class);
        SettableRangedValue mockVal1 = mock(SettableRangedValue.class);
        when(mockRemoteControlsPage.getParameter(0)).thenReturn(mockParam0);
        when(mockRemoteControlsPage.getParameter(1)).thenReturn(mockParam1);
        when(mockParam0.value()).thenReturn(mockVal0);
        when(mockParam1.value()).thenReturn(mockVal1);

        String response = localDispatcher.handle(rpc("device/setParameters",
            "{\"pages\":[{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.25},{\"index\":1,\"value\":0.75}]}]}"));

        assertContains(response, "\"ok\":true");
        verify(mockPageIndex).set(0);
        // Param write is scheduled for next flush cycle (1 task)
        assertEquals(1, scheduledTasks.size(), "Single page schedules 1 write task");
        scheduledTasks.get(0).run();
        verify(mockVal0).setImmediately(0.25);
        verify(mockVal1).setImmediately(0.75);
    }

    @Test
    void setParameters_multiPage_schedulesSubsequentPages() {
        // Set up a capturing scheduler
        List<Runnable> scheduledTasks = new ArrayList<>();
        JsonRpcDispatcher localDispatcher = new JsonRpcDispatcher();
        new DeviceHandler(mockCursorTrack, mockCursorDevice, mockRemoteControlsPage,
            mockDrumPadBank, mockDeviceLibrary, mockTransport, mockHost,
            (task, delay) -> scheduledTasks.add(task)).register(localDispatcher);

        // Stub page index and parameters
        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        RemoteControl mockParam0 = mock(RemoteControl.class);
        RemoteControl mockParam2 = mock(RemoteControl.class);
        SettableRangedValue mockVal0 = mock(SettableRangedValue.class);
        SettableRangedValue mockVal2 = mock(SettableRangedValue.class);
        when(mockRemoteControlsPage.getParameter(0)).thenReturn(mockParam0);
        when(mockRemoteControlsPage.getParameter(2)).thenReturn(mockParam2);
        when(mockParam0.value()).thenReturn(mockVal0);
        when(mockParam2.value()).thenReturn(mockVal2);

        String response = localDispatcher.handle(rpc("device/setParameters",
            "{\"pages\":["
            + "{\"pageIndex\":0,\"params\":[{\"index\":0,\"value\":0.5}]},"
            + "{\"pageIndex\":1,\"params\":[{\"index\":2,\"value\":0.9}]}"
            + "]}"));

        assertContains(response, "\"ok\":true");
        // First page switches immediately (no scheduled task for switch)
        verify(mockPageIndex).set(0);
        // 3 scheduled tasks: page0 write, page1 switch, page1 write
        assertEquals(3, scheduledTasks.size(),
            "Two pages produce 3 tasks: page0 write, page1 switch, page1 write");

        // Task 0: write page 0 params (one flush cycle after switch)
        scheduledTasks.get(0).run();
        verify(mockVal0).setImmediately(0.5);

        // Task 1: switch to page 1
        scheduledTasks.get(1).run();
        verify(mockPageIndex).set(1);

        // Task 2: write page 1 params
        scheduledTasks.get(2).run();
        verify(mockVal2).setImmediately(0.9);
    }

    // --- device/discoverAll behavioral ---

    @Test
    void discoverAll_zeroPages_returnsEmptyResult() {
        IntegerValue mockPageCount = mock(IntegerValue.class);
        when(mockRemoteControlsPage.pageCount()).thenReturn(mockPageCount);
        when(mockPageCount.get()).thenReturn(0);
        StringValue mockDeviceName = mock(StringValue.class);
        when(mockCursorDevice.name()).thenReturn(mockDeviceName);
        when(mockDeviceName.get()).thenReturn("Empty Device");

        String response = dispatcher.handle(rpc("device/discoverAll", "{}"));
        assertContains(response, "\"pageCount\":0");
        assertContains(response, "\"deviceName\":\"Empty Device\"");
        assertContains(response, "\"pages\":[]");
    }

    @Test
    void discoverAll_multiPage_returnsScanningResponse() {
        // Set up a non-immediate scheduler to verify the immediate response
        JsonRpcDispatcher localDispatcher = new JsonRpcDispatcher();
        List<Runnable> scheduledTasks = new ArrayList<>();
        new DeviceHandler(mockCursorTrack, mockCursorDevice, mockRemoteControlsPage,
            mockDrumPadBank, mockDeviceLibrary, mockTransport, mockHost,
            (task, delay) -> scheduledTasks.add(task)).register(localDispatcher);

        IntegerValue mockPageCount = mock(IntegerValue.class);
        when(mockRemoteControlsPage.pageCount()).thenReturn(mockPageCount);
        when(mockPageCount.get()).thenReturn(3);
        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        when(mockPageIndex.get()).thenReturn(0);
        StringValue mockDeviceName = mock(StringValue.class);
        when(mockCursorDevice.name()).thenReturn(mockDeviceName);
        when(mockDeviceName.get()).thenReturn("Polymer");
        StringArrayValue mockPageNames = mock(StringArrayValue.class);
        when(mockRemoteControlsPage.pageNames()).thenReturn(mockPageNames);
        when(mockPageNames.get()).thenReturn(new String[]{"A", "B", "C"});

        String response = localDispatcher.handle(rpc("device/discoverAll", "{}"));
        assertContains(response, "\"scanning\":true");
        assertContains(response, "\"pageCount\":3");
        assertEquals(3, scheduledTasks.size());
    }

    @Test
    void discoverAll_completeScan_returnsFullParameterMap() {
        // With immediate scheduler, scan completes synchronously
        IntegerValue mockPageCount = mock(IntegerValue.class);
        when(mockRemoteControlsPage.pageCount()).thenReturn(mockPageCount);
        when(mockPageCount.get()).thenReturn(2);
        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        when(mockPageIndex.get()).thenReturn(0);
        StringValue mockDeviceName = mock(StringValue.class);
        when(mockCursorDevice.name()).thenReturn(mockDeviceName);
        when(mockDeviceName.get()).thenReturn("Polymer");

        // Mock page names array
        StringArrayValue mockPageNames = mock(StringArrayValue.class);
        when(mockRemoteControlsPage.pageNames()).thenReturn(mockPageNames);
        when(mockPageNames.get()).thenReturn(new String[]{"Oscillator", "Filter"});

        // Mock all 8 parameters
        for (int i = 0; i < 8; i++) {
            RemoteControl param = mock(RemoteControl.class);
            SettableStringValue paramName = mock(SettableStringValue.class);
            SettableRangedValue paramValue = mock(SettableRangedValue.class);
            StringValue displayedValue = mock(StringValue.class);
            when(mockRemoteControlsPage.getParameter(i)).thenReturn(param);
            when(param.name()).thenReturn(paramName);
            when(paramName.get()).thenReturn("Param " + i);
            when(param.value()).thenReturn(paramValue);
            when(paramValue.get()).thenReturn(0.5);
            when(paramValue.displayedValue()).thenReturn(displayedValue);
            when(displayedValue.get()).thenReturn("50%");
        }

        // Run discoverAll — tasks execute immediately
        dispatcher.handle(rpc("device/discoverAll", "{}"));

        // Fetch result
        String result = dispatcher.handle(rpc("device/getDiscoveryResult", "{}"));
        assertContains(result, "\"deviceName\":\"Polymer\"");
        assertContains(result, "\"pageCount\":2");
        assertContains(result, "\"Oscillator\"");
        assertContains(result, "\"Param 0\"");
        assertContains(result, "\"50%\"");
    }

    @Test
    void discoverAll_restoresOriginalPage() {
        IntegerValue mockPageCount = mock(IntegerValue.class);
        when(mockRemoteControlsPage.pageCount()).thenReturn(mockPageCount);
        when(mockPageCount.get()).thenReturn(2);
        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        when(mockPageIndex.get()).thenReturn(5); // Original page was 5

        StringValue mockDeviceName = mock(StringValue.class);
        when(mockCursorDevice.name()).thenReturn(mockDeviceName);
        when(mockDeviceName.get()).thenReturn("Test");
        StringArrayValue mockPageNamesArr = mock(StringArrayValue.class);
        when(mockRemoteControlsPage.pageNames()).thenReturn(mockPageNamesArr);
        when(mockPageNamesArr.get()).thenReturn(new String[]{"Page A", "Page B"});
        for (int i = 0; i < 8; i++) {
            RemoteControl param = mock(RemoteControl.class);
            SettableStringValue pn = mock(SettableStringValue.class);
            SettableRangedValue pv = mock(SettableRangedValue.class);
            StringValue dv = mock(StringValue.class);
            when(mockRemoteControlsPage.getParameter(i)).thenReturn(param);
            when(param.name()).thenReturn(pn);
            when(pn.get()).thenReturn("P");
            when(param.value()).thenReturn(pv);
            when(pv.get()).thenReturn(0.0);
            when(pv.displayedValue()).thenReturn(dv);
            when(dv.get()).thenReturn("0");
        }

        dispatcher.handle(rpc("device/discoverAll", "{}"));

        // Verify: page 0 set initially, then page 1, then restored to 5
        var inOrder = inOrder(mockPageIndex);
        inOrder.verify(mockPageIndex).set(0);   // Start scan
        inOrder.verify(mockPageIndex).set(1);   // Advance to page 1
        inOrder.verify(mockPageIndex).set(5);   // Restore original
    }

    @Test
    void getDiscoveryResult_noDiscovery_returnsError() {
        String response = dispatcher.handle(rpc("device/getDiscoveryResult", "{}"));
        assertContains(response, "No discovery in progress");
    }

    @Test
    void getDiscoveryResult_presetFormat_returnsPresetCompatibleJson() {
        // Set up a 2-page device with immediate scheduler
        setupDiscoveryMocks(2);

        dispatcher.handle(rpc("device/discoverAll", "{}"));
        String result = dispatcher.handle(rpc("device/getDiscoveryResult",
            "{\"format\":\"preset\"}"));

        // Preset format uses pageIndex/params instead of index/parameters
        assertContains(result, "\"deviceName\":\"Polymer\"");
        assertContains(result, "\"pageCount\":2");
        assertContains(result, "\"pageIndex\":0");
        assertContains(result, "\"pageIndex\":1");
        assertContains(result, "\"params\":");
        // Should NOT contain full format fields
        assertFalse(result.contains("\"displayedValue\""),
            "Preset format should not include displayedValue");
        assertFalse(result.contains("\"name\":\"Param"),
            "Preset format should not include parameter names");
    }

    @Test
    void getDiscoveryResult_defaultFormat_returnsFullJson() {
        setupDiscoveryMocks(2);

        dispatcher.handle(rpc("device/discoverAll", "{}"));
        String result = dispatcher.handle(rpc("device/getDiscoveryResult", "{}"));

        // Full format has index/parameters/name/displayedValue
        assertContains(result, "\"parameters\":");
        assertContains(result, "\"displayedValue\":");
        assertContains(result, "\"Param 0\"");
    }

    @Test
    void getDiscoveryResult_presetFormat_noDiscovery_returnsError() {
        String response = dispatcher.handle(rpc("device/getDiscoveryResult",
            "{\"format\":\"preset\"}"));
        assertContains(response, "No discovery in progress");
    }

    private void setupDiscoveryMocks(int pageCount) {
        IntegerValue mockPageCount = mock(IntegerValue.class);
        when(mockRemoteControlsPage.pageCount()).thenReturn(mockPageCount);
        when(mockPageCount.get()).thenReturn(pageCount);
        when(mockRemoteControlsPage.selectedPageIndex()).thenReturn(mockPageIndex);
        when(mockPageIndex.get()).thenReturn(0);
        StringValue mockDeviceName = mock(StringValue.class);
        when(mockCursorDevice.name()).thenReturn(mockDeviceName);
        when(mockDeviceName.get()).thenReturn("Polymer");
        StringArrayValue mockPageNames = mock(StringArrayValue.class);
        when(mockRemoteControlsPage.pageNames()).thenReturn(mockPageNames);
        String[] names = new String[pageCount];
        for (int i = 0; i < pageCount; i++) names[i] = "Page " + i;
        when(mockPageNames.get()).thenReturn(names);
        for (int i = 0; i < 8; i++) {
            RemoteControl param = mock(RemoteControl.class);
            SettableStringValue paramName = mock(SettableStringValue.class);
            SettableRangedValue paramValue = mock(SettableRangedValue.class);
            StringValue displayedValue = mock(StringValue.class);
            when(mockRemoteControlsPage.getParameter(i)).thenReturn(param);
            when(param.name()).thenReturn(paramName);
            when(paramName.get()).thenReturn("Param " + i);
            when(param.value()).thenReturn(paramValue);
            when(paramValue.get()).thenReturn(0.5);
            when(paramValue.displayedValue()).thenReturn(displayedValue);
            when(displayedValue.get()).thenReturn("50%");
        }
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
        assertTrue(methods.contains("device/nextPreset"));
        assertTrue(methods.contains("device/previousPreset"));
        assertTrue(methods.contains("device/nextPresetCategory"));
        assertTrue(methods.contains("device/previousPresetCategory"));
        assertTrue(methods.contains("device/nextPresetCreator"));
        assertTrue(methods.contains("device/previousPresetCreator"));
    }

    @Test
    void nextPreset_callsSwitchToNextPreset() {
        dispatcher.handle(rpc("device/nextPreset", "{}"));
        verify(mockCursorDevice).switchToNextPreset();
    }

    @Test
    void previousPreset_callsSwitchToPreviousPreset() {
        dispatcher.handle(rpc("device/previousPreset", "{}"));
        verify(mockCursorDevice).switchToPreviousPreset();
    }

    @Test
    void nextPresetCategory_callsSwitchToNextPresetCategory() {
        dispatcher.handle(rpc("device/nextPresetCategory", "{}"));
        verify(mockCursorDevice).switchToNextPresetCategory();
    }

    @Test
    void previousPresetCategory_callsSwitchToPreviousPresetCategory() {
        dispatcher.handle(rpc("device/previousPresetCategory", "{}"));
        verify(mockCursorDevice).switchToPreviousPresetCategory();
    }

    @Test
    void nextPresetCreator_callsSwitchToNextPresetCreator() {
        dispatcher.handle(rpc("device/nextPresetCreator", "{}"));
        verify(mockCursorDevice).switchToNextPresetCreator();
    }

    @Test
    void previousPresetCreator_callsSwitchToPreviousPresetCreator() {
        dispatcher.handle(rpc("device/previousPresetCreator", "{}"));
        verify(mockCursorDevice).switchToPreviousPresetCreator();
    }

    // --- Remote control mapping ---

    @Test
    void setParameterMapping_callsIsBeingMappedSet() {
        com.bitwig.extension.controller.api.SettableBooleanValue mockMappingValue =
            org.mockito.Mockito.mock(com.bitwig.extension.controller.api.SettableBooleanValue.class);
        when(mockRemoteControlsPage.getParameter(0)).thenReturn(mockRemoteControl);
        when(mockRemoteControl.isBeingMapped()).thenReturn(mockMappingValue);

        dispatcher.handle(rpc("device/setParameterMapping", "{\"index\":0,\"enabled\":true}"));
        verify(mockMappingValue).set(true);
    }

    @Test
    void setParameterMapping_missingIndex_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameterMapping", "{\"enabled\":true}"));
        assertContains(response, "-32602");
    }

    @Test
    void setParameterMapping_missingEnabled_returnsError() {
        String response = dispatcher.handle(rpc("device/setParameterMapping", "{\"index\":0}"));
        assertContains(response, "-32602");
    }

    @Test
    void getParameterMapping_returnsArrayOfBooleans() {
        com.bitwig.extension.controller.api.SettableBooleanValue mockMappingValue =
            org.mockito.Mockito.mock(com.bitwig.extension.controller.api.SettableBooleanValue.class);
        when(mockMappingValue.get()).thenReturn(false);
        for (int i = 0; i < 8; i++) {
            RemoteControl rc = org.mockito.Mockito.mock(RemoteControl.class);
            when(rc.isBeingMapped()).thenReturn(mockMappingValue);
            when(mockRemoteControlsPage.getParameter(i)).thenReturn(rc);
        }

        String response = dispatcher.handle(rpc("device/getParameterMapping", "{}"));
        assertContains(response, "\"result\"");
    }
}
