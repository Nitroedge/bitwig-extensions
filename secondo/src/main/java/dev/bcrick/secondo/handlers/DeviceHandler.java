package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.BooleanValue;
import com.bitwig.extension.controller.api.Device;
import com.bitwig.extension.controller.api.DeviceBank;
import com.bitwig.extension.controller.api.IntegerValue;
import com.bitwig.extension.controller.api.StringArrayValue;
import com.bitwig.extension.controller.api.StringValue;
import com.bitwig.extension.controller.api.DrumPadBank;
import com.bitwig.extension.controller.api.InsertionPoint;
import com.bitwig.extension.controller.api.RemoteControl;
import com.bitwig.extension.controller.api.Transport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.rpc.TaskScheduler;

import static dev.bcrick.secondo.rpc.JsonParamValidator.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class DeviceHandler {

    private static final int PARAM_COUNT = 8;
    private static final long FLUSH_DELAY_MS = 100;
    private static final int CHAIN_NODE_BUDGET = 48;
    public static final int CHAIN_ROOT_BANK_WIDTH = CHAIN_NODE_BUDGET + 1;
    static final Set<String> VALID_PAGE_TAGS = Set.of(
        "env", "eq", "filter", "fx", "lfo", "mixer", "osc", "perf"
    );

    private final CursorTrack cursorTrack;
    private final CursorDevice cursorDevice;
    private final CursorRemoteControlsPage remoteControlsPage;
    private final DrumPadBank drumPadBank;
    private final DeviceLibrary deviceLibrary;
    private final Transport transport;
    private final ControllerHost host;
    private final TaskScheduler scheduler;
    private final TrackBankManager trackBankManager;
    private final PreparedChainBank[] canonicalDeviceBanks;
    private volatile boolean chainScanInProgress = false;
    private volatile JsonObject chainScanResult = null;
    private volatile int chainScanId = 0;

    // Discovery state
    private volatile JsonObject discoveryResult = null;
    private volatile boolean discoveryInProgress = false;

    public DeviceHandler(CursorTrack cursorTrack, CursorDevice cursorDevice,
                         CursorRemoteControlsPage remoteControlsPage,
                         DrumPadBank drumPadBank,
                         DeviceLibrary deviceLibrary, Transport transport,
                         ControllerHost host, TaskScheduler scheduler) {
        this(cursorTrack, cursorDevice, remoteControlsPage, drumPadBank,
            deviceLibrary, transport, host, scheduler, null);
    }

    public DeviceHandler(CursorTrack cursorTrack, CursorDevice cursorDevice,
                         CursorRemoteControlsPage remoteControlsPage,
                         DrumPadBank drumPadBank, DeviceLibrary deviceLibrary,
                         Transport transport, ControllerHost host,
                         TaskScheduler scheduler, TrackBankManager trackBankManager) {
        this(cursorTrack, cursorDevice, remoteControlsPage, drumPadBank,
            deviceLibrary, transport, host, scheduler, trackBankManager, null);
    }

    public DeviceHandler(CursorTrack cursorTrack, CursorDevice cursorDevice,
                         CursorRemoteControlsPage remoteControlsPage,
                         DrumPadBank drumPadBank, DeviceLibrary deviceLibrary,
                         Transport transport, ControllerHost host,
                         TaskScheduler scheduler, TrackBankManager trackBankManager,
                         DeviceBank[] canonicalDeviceBanks) {
        this.cursorTrack = cursorTrack;
        this.cursorDevice = cursorDevice;
        this.remoteControlsPage = remoteControlsPage;
        this.drumPadBank = drumPadBank;
        this.deviceLibrary = deviceLibrary;
        this.transport = transport;
        this.host = host;
        this.scheduler = scheduler;
        this.trackBankManager = trackBankManager;
        this.canonicalDeviceBanks = prepareCanonicalDeviceBanks(canonicalDeviceBanks);
    }

    public void register(JsonRpcDispatcher dispatcher) {
        // RPC dispatch is synchronous on the controller thread. Start one scheduled
        // observer job and poll the same scanId rather than blocking for a flush.
        dispatcher.register("device/listChain", params -> {
            int trackIndex = requireInt(params, "trackIndex");
            if (trackBankManager == null) {
                throw new IllegalStateException("Canonical track bank is unavailable");
            }
            synchronized (this) {
                if (chainScanInProgress) {
                    throw new IllegalStateException("DEVICE_CHAIN_SCAN_IN_PROGRESS");
                }
                int bankSlot = trackBankManager.canonicalBankSlot(trackIndex);
                PreparedChainBank rootBank = canonicalDeviceBanks == null ? null
                    : canonicalDeviceBanks[bankSlot];
                if (rootBank == null) {
                    throw new IllegalStateException(
                        "Canonical device bank is unavailable for track " + trackIndex);
                }
                ChainScanJob job = new ChainScanJob(rootBank, ++chainScanId);
                chainScanResult = null;
                chainScanInProgress = true;
                try {
                    scheduler.schedule(job::advance, FLUSH_DELAY_MS);
                } catch (RuntimeException error) {
                    chainScanInProgress = false;
                    throw error;
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("scanning", true);
            response.addProperty("scanId", chainScanId);
            return response;
        });
        dispatcher.register("device/getChainResult", params -> {
            int requestedId = requireInt(params, "scanId");
            synchronized (this) {
                if (requestedId != chainScanId) {
                    throw new IllegalStateException("DEVICE_CHAIN_SCAN_ID_MISMATCH");
                }
                if (chainScanInProgress) {
                    JsonObject response = new JsonObject();
                    response.addProperty("scanning", true);
                    response.addProperty("scanId", chainScanId);
                    return response;
                }
                if (chainScanResult == null) {
                    throw new IllegalStateException(
                        "No device chain result. Call device/listChain first.");
                }
                return chainScanResult.deepCopy();
            }
        });
        // Device chain navigation
        dispatcher.register("device/selectNext", params -> {
            cursorDevice.selectNext();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/selectPrevious", params -> {
            cursorDevice.selectPrevious();
            return new JsonPrimitive("ok");
        });

        // Preset navigation
        dispatcher.register("device/nextPreset", params -> {
            cursorDevice.switchToNextPreset();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/previousPreset", params -> {
            cursorDevice.switchToPreviousPreset();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/nextPresetCategory", params -> {
            cursorDevice.switchToNextPresetCategory();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/previousPresetCategory", params -> {
            cursorDevice.switchToPreviousPresetCategory();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/nextPresetCreator", params -> {
            cursorDevice.switchToNextPresetCreator();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/previousPresetCreator", params -> {
            cursorDevice.switchToPreviousPresetCreator();
            return new JsonPrimitive("ok");
        });

        // Device state
        dispatcher.register("device/setEnabled", params -> {
            boolean enabled = requireBoolean(params, "enabled");
            cursorDevice.isEnabled().set(enabled);
            return new JsonPrimitive("ok");
        });

        // Parameter page navigation
        dispatcher.register("device/selectPage", params -> {
            int index = requireInt(params, "index");
            remoteControlsPage.selectedPageIndex().set(index);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/nextPage", params -> {
            remoteControlsPage.selectNextPage(false);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/previousPage", params -> {
            remoteControlsPage.selectPreviousPage(false);
            return new JsonPrimitive("ok");
        });

        // Parameter mutation
        dispatcher.register("device/setParameterValue", params -> {
            int index = requireInt(params, "index");
            double value = requireDouble(params, "value");
            if (index < 0 || index >= PARAM_COUNT) {
                throw new IllegalArgumentException("parameter index out of range: " + index);
            }
            RemoteControl param = remoteControlsPage.getParameter(index);
            param.value().setImmediately(value);
            return new JsonPrimitive("ok");
        });

        // Batch parameter setter across pages
        dispatcher.register("device/setParameters", params -> {
            JsonArray pages = requireArray(params, "pages");
            if (pages.isEmpty()) {
                throw new IllegalArgumentException("pages array must not be empty");
            }

            int totalParams = 0;
            // Validate all pages up front before applying anything
            for (JsonElement pageEl : pages) {
                JsonObject page = pageEl.getAsJsonObject();
                if (!page.has("pageIndex")) {
                    throw new IllegalArgumentException("each page must have 'pageIndex'");
                }
                JsonArray pageParams = requireArray(page, "params");
                if (pageParams.isEmpty()) {
                    throw new IllegalArgumentException("each page must have a non-empty 'params' array");
                }
                for (JsonElement paramEl : pageParams) {
                    JsonObject p = paramEl.getAsJsonObject();
                    if (!p.has("index") || !p.has("value")) {
                        throw new IllegalArgumentException("each param must have 'index' and 'value'");
                    }
                    int idx = p.get("index").getAsInt();
                    if (idx < 0 || idx >= PARAM_COUNT) {
                        throw new IllegalArgumentException("parameter index out of range: 0-7, got " + idx);
                    }
                    double val = p.get("value").getAsDouble();
                    if (val < 0.0 || val > 1.0) {
                        throw new IllegalArgumentException("parameter value out of range: 0.0-1.0, got " + val);
                    }
                    totalParams++;
                }
            }

            // Switch to first page immediately (this flush cycle).
            // Params are written in the NEXT flush cycle after the page
            // switch takes effect — Bitwig needs one cycle to update the
            // RemoteControl objects after a page change.
            for (int i = 0; i < pages.size(); i++) {
                JsonObject page = pages.get(i).getAsJsonObject();
                int pageIndex = page.get("pageIndex").getAsInt();
                JsonArray pageParams = page.getAsJsonArray("params");

                // Task 1: switch to page
                long switchDelay = FLUSH_DELAY_MS * (i * 2);
                if (switchDelay == 0) {
                    // First page: switch immediately in this flush cycle
                    remoteControlsPage.selectedPageIndex().set(pageIndex);
                } else {
                    scheduler.schedule(() -> {
                        remoteControlsPage.selectedPageIndex().set(pageIndex);
                    }, switchDelay);
                }

                // Task 2: write params (one flush cycle after the switch)
                long writeDelay = FLUSH_DELAY_MS * (i * 2 + 1);
                scheduler.schedule(() -> {
                    for (JsonElement paramEl : pageParams) {
                        JsonObject p = paramEl.getAsJsonObject();
                        remoteControlsPage.getParameter(p.get("index").getAsInt())
                            .value().setImmediately(p.get("value").getAsDouble());
                    }
                }, writeDelay);
            }

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("pageCount", pages.size());
            result.addProperty("paramCount", totalParams);
            return result;
        });

        // Device insertion
        dispatcher.register("device/insertBitwigDevice", params -> {
            String name = requireString(params, "name");
            String position = optionalString(params, "position", "end");
            Path devicePath = deviceLibrary.resolve(name);
            getInsertionPoint(position).insertFile(devicePath.toString());
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/insertPluginDevice", params -> {
            String type = requireString(params, "type");
            String id = requireString(params, "id");
            String position = optionalString(params, "position", "end");
            InsertionPoint ip = getInsertionPoint(position);
            switch (type) {
                case "vst2":
                    ip.insertVST2Device(Integer.parseInt(id));
                    break;
                case "vst3":
                    ip.insertVST3Device(id);
                    break;
                case "clap":
                    ip.insertCLAPDevice(id);
                    break;
                default:
                    throw new IllegalArgumentException("type must be 'vst2', 'vst3', or 'clap', got: " + type);
            }
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/listBitwigDevices", params -> {
            JsonArray arr = new JsonArray();
            for (String name : deviceLibrary.listDevices()) {
                arr.add(name);
            }
            return arr;
        });

        dispatcher.register("device/remove", params -> {
            cursorDevice.deleteObject();
            scheduler.schedule(() -> cursorDevice.selectFirstInChannel(cursorTrack), FLUSH_DELAY_MS);
            return new JsonPrimitive("ok");
        });

        // Per-parameter automation methods
        dispatcher.register("device/hasAutomation", params -> {
            int index = requireInt(params, "index");
            if (index < 0 || index >= PARAM_COUNT) {
                throw new IllegalArgumentException("parameter index out of range: " + index);
            }
            RemoteControl param = remoteControlsPage.getParameter(index);
            JsonObject result = new JsonObject();
            result.addProperty("hasAutomation", param.hasAutomation().get());
            return result;
        });

        dispatcher.register("device/deleteAllAutomation", params -> {
            int index = requireInt(params, "index");
            if (index < 0 || index >= PARAM_COUNT) {
                throw new IllegalArgumentException("parameter index out of range: " + index);
            }
            RemoteControl param = remoteControlsPage.getParameter(index);
            param.deleteAllAutomation();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            return result;
        });

        dispatcher.register("device/restoreAutomationControl", params -> {
            int index = requireInt(params, "index");
            if (index < 0 || index >= PARAM_COUNT) {
                throw new IllegalArgumentException("parameter index out of range: " + index);
            }
            RemoteControl param = remoteControlsPage.getParameter(index);
            param.restoreAutomationControl();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            return result;
        });

        dispatcher.register("device/touch", params -> {
            int index = requireInt(params, "index");
            boolean touched = requireBoolean(params, "touched");
            if (index < 0 || index >= PARAM_COUNT) {
                throw new IllegalArgumentException("parameter index out of range: " + index);
            }
            RemoteControl param = remoteControlsPage.getParameter(index);
            param.touch(touched);
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            return result;
        });

        // Envelope writing
        dispatcher.register("device/writeEnvelope", params -> {
            int index = requireInt(params, "index");
            if (index < 0 || index >= PARAM_COUNT) {
                throw new IllegalArgumentException("parameter index out of range: " + index);
            }

            // Precondition: arranger automation write must be enabled
            if (!transport.isArrangerAutomationWriteEnabled().get()) {
                throw new IllegalStateException("Arranger automation write must be enabled");
            }

            // Parse and validate points
            JsonArray pointsArr = requireArray(params, "points");
            if (pointsArr.isEmpty()) {
                throw new IllegalArgumentException("points array must not be empty");
            }

            List<double[]> points = new ArrayList<>();
            for (JsonElement el : pointsArr) {
                JsonObject pt = el.getAsJsonObject();
                if (!pt.has("position") || !pt.has("value")) {
                    throw new IllegalArgumentException("each point must have 'position' and 'value'");
                }
                double position = pt.get("position").getAsDouble();
                double value = pt.get("value").getAsDouble();
                if (position < 0) {
                    throw new IllegalArgumentException("position must be >= 0, got: " + position);
                }
                // Clamp value to [0, 1]
                value = Math.max(0.0, Math.min(1.0, value));
                points.add(new double[]{position, value});
            }

            // Sort by position ascending
            points.sort(Comparator.comparingDouble(a -> a[0]));

            // Deduplicate: last-wins for same position
            List<double[]> deduped = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                if (i == points.size() - 1 || points.get(i)[0] != points.get(i + 1)[0]) {
                    deduped.add(points.get(i));
                }
            }

            int pointCount = deduped.size();
            RemoteControl param = remoteControlsPage.getParameter(index);

            // Save state
            double savedPosition = transport.getPosition().get();
            boolean wasPlaying = transport.isPlaying().get();

            // Stop if playing, then start fresh playback
            // (Bitwig requires active playback for touch automation recording — D-9.2a)
            if (wasPlaying) {
                transport.stop();
            }
            transport.play();

            // Schedule point-writing as chained tasks across flush cycles.
            // Each point needs its own flush cycle for the engine to process
            // the position jump before recording the touch + value.
            long delay = 100; // initial delay for playback to engage
            for (int i = 0; i < deduped.size(); i++) {
                final double[] pt = deduped.get(i);
                final boolean isLast = (i == deduped.size() - 1);
                host.scheduleTask(() -> {
                    transport.getPosition().set(pt[0]);
                    param.touch(true);
                    param.value().setImmediately(pt[1]);
                    param.touch(false);

                    if (isLast) {
                        // Final point: schedule cleanup
                        host.scheduleTask(() -> {
                            param.touch(false); // ensure untouched
                            transport.stop();
                            transport.getPosition().set(savedPosition);
                            if (wasPlaying) {
                                transport.play();
                            }
                        }, 50);
                    }
                }, delay);
                delay += 100; // 100ms between points
            }

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("pointsWritten", pointCount);
            return result;
        });

        // Drum pad inspection
        dispatcher.register("device/getDrumPads", params -> {
            if (!cursorDevice.hasDrumPads().get()) {
                throw new IllegalStateException("Current device has no drum pads");
            }
            JsonArray pads = new JsonArray();
            for (int i = 0; i < 128; i++) {
                com.bitwig.extension.controller.api.DrumPad pad =
                    (com.bitwig.extension.controller.api.DrumPad) drumPadBank.getItemAt(i);
                if (pad.exists().get()) {
                    String name = pad.name().get();
                    if (name != null && !name.isEmpty()) {
                        JsonObject padObj = new JsonObject();
                        padObj.addProperty("note", i);
                        padObj.addProperty("name", name);
                        pads.add(padObj);
                    }
                }
            }
            return pads;
        });

        // Nested device chain navigation
        dispatcher.register("device/enterSlot", params -> {
            String name = requireString(params, "name");
            cursorDevice.selectFirstInSlot(name);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/exitToParent", params -> {
            cursorDevice.selectParent();
            return new JsonPrimitive("ok");
        });

        // Layer and drum pad navigation
        dispatcher.register("device/enterLayer", params -> {
            boolean hasIndex = params.has("index") && !params.get("index").isJsonNull();
            boolean hasName = params.has("name") && !params.get("name").isJsonNull();
            if (!hasIndex && !hasName) {
                throw new IllegalArgumentException("must provide 'index' or 'name' parameter");
            }
            if (hasIndex && hasName) {
                throw new IllegalArgumentException("'index' and 'name' are mutually exclusive — provide one, not both");
            }
            if (hasIndex) {
                cursorDevice.selectFirstInLayer(params.get("index").getAsInt());
            } else {
                cursorDevice.selectFirstInLayer(params.get("name").getAsString());
            }
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/enterKeyPad", params -> {
            int key = requireInt(params, "key");
            if (key < 0 || key > 127) {
                throw new IllegalArgumentException("key must be 0-127, got " + key);
            }
            cursorDevice.selectFirstInKeyPad(key);
            return new JsonPrimitive("ok");
        });

        // Parameter page tag filtering
        dispatcher.register("device/selectPageByTag", params -> {
            String tag = requireString(params, "tag").toLowerCase();
            validatePageTag(tag);
            String direction = optionalString(params, "direction", "next");
            boolean cycle = params.has("cycle") ? params.get("cycle").getAsBoolean() : true;
            if ("next".equals(direction)) {
                remoteControlsPage.selectNextPageMatching(tag, cycle);
            } else if ("previous".equals(direction)) {
                remoteControlsPage.selectPreviousPageMatching(tag, cycle);
            } else {
                throw new IllegalArgumentException("direction must be 'next' or 'previous', got: " + direction);
            }
            return new JsonPrimitive("ok");
        });

        // Device parameter discovery
        dispatcher.register("device/discoverAll", params -> {
            if (discoveryInProgress) {
                throw new IllegalStateException("Discovery already in progress");
            }
            int totalPages = remoteControlsPage.pageCount().get();
            if (totalPages <= 0) {
                JsonObject result = new JsonObject();
                result.addProperty("deviceName", cursorDevice.name().get());
                result.addProperty("pageCount", 0);
                result.add("pages", new JsonArray());
                return result;
            }

            int originalPage = remoteControlsPage.selectedPageIndex().get();
            discoveryInProgress = true;
            discoveryResult = null;

            String deviceName = cursorDevice.name().get();
            String[] allPageNames = remoteControlsPage.pageNames().get();
            JsonArray pages = new JsonArray();

            // Set to page 0 — first read happens in task at FLUSH_DELAY_MS
            remoteControlsPage.selectedPageIndex().set(0);

            for (int i = 0; i < totalPages; i++) {
                final int pageIndex = i;
                long delay = FLUSH_DELAY_MS * (i + 1);
                scheduler.schedule(() -> {
                    // Read current page's parameters
                    JsonObject page = new JsonObject();
                    page.addProperty("index", pageIndex);
                    String pageName = (pageIndex < allPageNames.length)
                        ? allPageNames[pageIndex] : "";
                    page.addProperty("name", pageName);
                    JsonArray parameters = new JsonArray();
                    for (int j = 0; j < PARAM_COUNT; j++) {
                        RemoteControl param = remoteControlsPage.getParameter(j);
                        JsonObject paramObj = new JsonObject();
                        paramObj.addProperty("index", j);
                        paramObj.addProperty("name", param.name().get());
                        paramObj.addProperty("value", param.value().get());
                        paramObj.addProperty("displayedValue",
                            param.value().displayedValue().get());
                        parameters.add(paramObj);
                    }
                    page.add("parameters", parameters);
                    pages.add(page);

                    // Advance to next page, or finalize
                    if (pageIndex < totalPages - 1) {
                        remoteControlsPage.selectedPageIndex().set(pageIndex + 1);
                    } else {
                        // Restore original page and publish result
                        remoteControlsPage.selectedPageIndex().set(originalPage);
                        JsonObject result = new JsonObject();
                        result.addProperty("deviceName", deviceName);
                        result.addProperty("pageCount", totalPages);
                        result.add("pages", pages);
                        discoveryResult = result;
                        discoveryInProgress = false;
                    }
                }, delay);
            }

            JsonObject response = new JsonObject();
            response.addProperty("scanning", true);
            response.addProperty("pageCount", totalPages);
            response.addProperty("estimatedMs", totalPages * FLUSH_DELAY_MS);
            return response;
        });

        dispatcher.register("device/getDiscoveryResult", params -> {
            if (discoveryResult != null) {
                String format = optionalString(params, "format", "full");
                JsonObject result;
                if ("preset".equals(format)) {
                    result = toPresetFormat(discoveryResult);
                } else {
                    result = discoveryResult;
                }
                discoveryResult = null;
                return result;
            }
            if (discoveryInProgress) {
                JsonObject result = new JsonObject();
                result.addProperty("scanning", true);
                return result;
            }
            throw new IllegalStateException(
                "No discovery in progress. Call device/discoverAll first.");
        });

        // Cursor track navigation
        dispatcher.register("cursor/selectTrack", params -> {
            String direction = requireString(params, "direction");
            switch (direction) {
                case "next":
                    cursorTrack.selectNext();
                    break;
                case "previous":
                    cursorTrack.selectPrevious();
                    break;
                default:
                    throw new IllegalArgumentException("direction must be 'next' or 'previous', got: " + direction);
            }
            return new JsonPrimitive("ok");
        });

        // --- Remote control mapping ---

        dispatcher.register("device/setParameterMapping", params -> {
            int index = requireInt(params, "index");
            if (index < 0 || index >= PARAM_COUNT) {
                throw new IllegalArgumentException("index must be 0-" + (PARAM_COUNT - 1));
            }
            boolean enabled = requireBoolean(params, "enabled");
            remoteControlsPage.getParameter(index).isBeingMapped().set(enabled);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("device/getParameterMapping", params -> {
            JsonArray result = new JsonArray();
            for (int i = 0; i < PARAM_COUNT; i++) {
                result.add(remoteControlsPage.getParameter(i).isBeingMapped().get());
            }
            return result;
        });
    }

    private static final class Observed<T> {
        volatile T value;
    }

    private static Observed<Boolean> observe(BooleanValue value) {
        Observed<Boolean> observed = new Observed<>();
        value.addValueObserver(v -> observed.value = v);
        return observed;
    }

    private static Observed<Integer> observe(IntegerValue value) {
        Observed<Integer> observed = new Observed<>();
        value.addValueObserver(v -> observed.value = v);
        return observed;
    }

    private static Observed<String> observe(StringValue value) {
        Observed<String> observed = new Observed<>();
        value.addValueObserver(v -> observed.value = v);
        return observed;
    }

    private static Observed<String[]> observe(StringArrayValue value) {
        Observed<String[]> observed = new Observed<>();
        value.addValueObserver(v -> observed.value = v == null ? null : v.clone());
        return observed;
    }

    private static final class PreparedDevice {
        final Observed<Boolean> exists;
        final Observed<String> name;
        final Observed<Boolean> plugin;
        final Observed<Boolean> enabled;
        final Observed<String[]> slots;
        final Observed<Boolean> layers;
        final Observed<Boolean> pads;

        PreparedDevice(Device device) {
            exists = observe(device.exists());
            name = observe(device.name());
            plugin = observe(device.isPlugin());
            enabled = observe(device.isEnabled());
            slots = observe(device.slotNames());
            layers = observe(device.hasLayers());
            pads = observe(device.hasDrumPads());
        }
    }

    private static final class PreparedChainBank {
        final Observed<Integer> total;
        final PreparedDevice[] devices;

        PreparedChainBank(DeviceBank bank) {
            total = observe(bank.itemCount());
            int width = Math.min(CHAIN_ROOT_BANK_WIDTH, bank.getSizeOfBank());
            devices = new PreparedDevice[width];
            for (int position = 0; position < width; position++) {
                devices[position] = new PreparedDevice(bank.getItemAt(position));
            }
        }
    }

    private static PreparedChainBank[] prepareCanonicalDeviceBanks(DeviceBank[] banks) {
        if (banks == null) return null;
        PreparedChainBank[] prepared = new PreparedChainBank[banks.length];
        for (int index = 0; index < banks.length; index++) {
            if (banks[index] != null) prepared[index] = new PreparedChainBank(banks[index]);
        }
        return prepared;
    }

    private final class ChainScanJob {
        private final PreparedChainBank root;
        private final int scanId;
        private final JsonArray nodes = new JsonArray();
        private final JsonArray warnings = new JsonArray();
        private JsonArray lastReturnedPath;
        private boolean complete = true;
        private boolean finished;

        ChainScanJob(PreparedChainBank root, int scanId) {
            this.root = root;
            this.scanId = scanId;
        }

        private JsonArray devicePath(int position) {
            JsonObject segment = new JsonObject();
            segment.addProperty("devicePosition", position);
            JsonArray path = new JsonArray();
            path.add(segment);
            return path;
        }

        private void warning(String code, String message) {
            JsonObject warning = new JsonObject();
            warning.addProperty("code", code);
            warning.addProperty("message", message);
            warnings.add(warning);
            complete = false;
        }

        private void addString(JsonObject row, String key, String value,
                               List<String> cold) {
            if (value == null) {
                row.add(key, com.google.gson.JsonNull.INSTANCE);
                cold.add(key);
            } else row.addProperty(key, value);
        }

        private void addBoolean(JsonObject row, String key, Boolean value,
                                List<String> cold) {
            if (value == null) {
                row.add(key, com.google.gson.JsonNull.INSTANCE);
                cold.add(key);
            } else row.addProperty(key, value);
        }

        private void readDevice(PreparedDevice device, int position) {
            JsonArray path = devicePath(position);
            if (device.exists.value == null) {
                warning("DEVICE_FIELD_UNOBSERVED",
                    "Device existence unobserved at " + path);
                return;
            }
            if (!device.exists.value) return;
            if (nodes.size() >= CHAIN_NODE_BUDGET) {
                warning("DEVICE_CHAIN_TRUNCATED",
                    "More top-level devices begin at " + path
                        + " after last returned path " + lastReturnedPath);
                return;
            }

            JsonObject row = new JsonObject();
            row.addProperty("kind", "device");
            row.add("path", path.deepCopy());
            row.add("parentPath", com.google.gson.JsonNull.INSTANCE);
            row.addProperty("depth", 0);
            row.addProperty("devicePosition", position);
            List<String> cold = new ArrayList<>();
            addString(row, "name", device.name.value, cold);
            addBoolean(row, "isPlugin", device.plugin.value, cold);
            addBoolean(row, "isEnabled", device.enabled.value, cold);
            JsonArray slotNames = new JsonArray();
            if (device.slots.value == null) {
                row.add("slotNames", com.google.gson.JsonNull.INSTANCE);
                cold.add("slotNames");
            } else {
                for (String slot : device.slots.value) slotNames.add(slot);
                row.add("slotNames", slotNames);
                if (device.slots.value.length > 0) {
                    warning("DEVICE_SLOT_CONTENTS_UNAVAILABLE",
                        "Named FX slots at " + path + " are opaque: " + slotNames);
                }
            }
            addBoolean(row, "hasLayers", device.layers.value, cold);
            addBoolean(row, "hasDrumPads", device.pads.value, cold);
            if (!cold.isEmpty()) {
                warning("DEVICE_FIELD_UNOBSERVED",
                    "Device fields unobserved at " + path + ": " + cold);
            }
            List<String> nested = new ArrayList<>();
            if (Boolean.TRUE.equals(device.layers.value)) nested.add("layers");
            if (Boolean.TRUE.equals(device.pads.value)) nested.add("drum pads");
            if (!nested.isEmpty()) {
                warning("DEVICE_NESTED_CONTENTS_UNAVAILABLE",
                    "Nested " + String.join(" and ", nested) + " at " + path
                        + " are unavailable to a cursor-independent Controller API v25 read.");
            }
            nodes.add(row);
            lastReturnedPath = path.deepCopy();
        }

        void advance() {
            if (finished) return;
            try {
                int visible = root.total.value == null ? root.devices.length
                    : Math.min(Math.max(0, root.total.value), root.devices.length);
                for (int position = 0; position < visible; position++) {
                    readDevice(root.devices[position], position);
                    if (nodes.size() >= CHAIN_NODE_BUDGET
                        && (position + 1 < visible
                            || (root.total.value != null
                                && root.total.value > root.devices.length))) {
                        if (warnings.size() == 0
                            || !"DEVICE_CHAIN_TRUNCATED".equals(
                                warnings.get(warnings.size() - 1).getAsJsonObject()
                                    .get("code").getAsString())) {
                            JsonArray next = devicePath(position + 1);
                            warning("DEVICE_CHAIN_TRUNCATED",
                                "More top-level devices begin at " + next
                                    + " after last returned path " + lastReturnedPath);
                        }
                        break;
                    }
                }
                finish();
            } catch (RuntimeException error) {
                warning("DEVICE_CHAIN_SCAN_FAILED",
                    "Scheduled chain scan stopped: " + error.getMessage());
                finish();
            }
        }

        private void finish() {
            if (finished) return;
            finished = true;
            Integer topLevelDeviceCount = root.total.value;
            if (topLevelDeviceCount == null) {
                warning("DEVICE_TOP_LEVEL_COUNT_UNOBSERVED",
                    "Canonical top-level DeviceBank.itemCount was not observed.");
            }
            JsonObject result = new JsonObject();
            result.addProperty("scanId", scanId);
            result.addProperty("complete", complete);
            result.addProperty("bankSize", CHAIN_NODE_BUDGET);
            result.add("nodes", nodes);
            result.addProperty("returnedCount", nodes.size());
            JsonObject counts = new JsonObject();
            counts.addProperty("devices", nodes.size());
            counts.addProperty("layers", 0);
            counts.addProperty("pads", 0);
            result.add("returnedNodeCounts", counts);
            if (topLevelDeviceCount == null) {
                result.add("topLevelDeviceCount", com.google.gson.JsonNull.INSTANCE);
            } else result.addProperty("topLevelDeviceCount", topLevelDeviceCount);
            result.addProperty("topLevelReturnedCount", nodes.size());
            result.add("lastReturnedPath", lastReturnedPath == null
                ? com.google.gson.JsonNull.INSTANCE : lastReturnedPath.deepCopy());
            result.add("warnings", warnings);
            synchronized (DeviceHandler.this) {
                chainScanResult = result;
                chainScanInProgress = false;
            }
        }
    }

    static JsonObject toPresetFormat(JsonObject discoveryResult) {
        JsonObject preset = new JsonObject();
        preset.addProperty("deviceName", discoveryResult.get("deviceName").getAsString());
        preset.addProperty("pageCount", discoveryResult.get("pageCount").getAsInt());

        JsonArray presetPages = new JsonArray();
        for (JsonElement pageEl : discoveryResult.getAsJsonArray("pages")) {
            JsonObject page = pageEl.getAsJsonObject();
            JsonObject presetPage = new JsonObject();
            presetPage.addProperty("pageIndex", page.get("index").getAsInt());

            JsonArray presetParams = new JsonArray();
            for (JsonElement paramEl : page.getAsJsonArray("parameters")) {
                JsonObject param = paramEl.getAsJsonObject();
                JsonObject presetParam = new JsonObject();
                presetParam.addProperty("index", param.get("index").getAsInt());
                presetParam.addProperty("value", param.get("value").getAsDouble());
                presetParams.add(presetParam);
            }
            presetPage.add("params", presetParams);
            presetPages.add(presetPage);
        }
        preset.add("pages", presetPages);
        return preset;
    }

    static void validatePageTag(String tag) {
        if (!VALID_PAGE_TAGS.contains(tag)) {
            throw new IllegalArgumentException(
                "Invalid page tag '" + tag + "'. Valid tags: " + VALID_PAGE_TAGS);
        }
    }

    private InsertionPoint getInsertionPoint(String position) {
        switch (position) {
            case "end":
                return cursorTrack.endOfDeviceChainInsertionPoint();
            case "before":
                return cursorDevice.beforeDeviceInsertionPoint();
            case "after":
                return cursorDevice.afterDeviceInsertionPoint();
            default:
                throw new IllegalArgumentException("position must be 'end', 'before', or 'after', got: " + position);
        }
    }
}
