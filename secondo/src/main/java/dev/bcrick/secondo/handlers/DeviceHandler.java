package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.CursorTrack;
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

    // Discovery state
    private volatile JsonObject discoveryResult = null;
    private volatile boolean discoveryInProgress = false;

    public DeviceHandler(CursorTrack cursorTrack, CursorDevice cursorDevice,
                         CursorRemoteControlsPage remoteControlsPage,
                         DrumPadBank drumPadBank,
                         DeviceLibrary deviceLibrary, Transport transport,
                         ControllerHost host, TaskScheduler scheduler) {
        this.cursorTrack = cursorTrack;
        this.cursorDevice = cursorDevice;
        this.remoteControlsPage = remoteControlsPage;
        this.drumPadBank = drumPadBank;
        this.deviceLibrary = deviceLibrary;
        this.transport = transport;
        this.host = host;
        this.scheduler = scheduler;
    }

    public void register(JsonRpcDispatcher dispatcher) {
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
