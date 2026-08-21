package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.CursorDevice;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.InsertionPoint;
import com.bitwig.extension.controller.api.MasterTrack;
import com.bitwig.extension.controller.api.RemoteControl;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;
import dev.bcrick.bitwigpal.rpc.TaskScheduler;

import static dev.bcrick.bitwigpal.rpc.JsonParamValidator.*;

import java.nio.file.Path;

public class MasterDeviceHandler {

    private static final int PARAM_COUNT = 8;
    private static final long FLUSH_DELAY_MS = 100;

    private final MasterTrack masterTrack;
    private final CursorDevice cursorDevice;
    private final CursorRemoteControlsPage remoteControlsPage;
    private final DeviceLibrary deviceLibrary;
    private final TaskScheduler scheduler;

    public MasterDeviceHandler(MasterTrack masterTrack, CursorDevice cursorDevice,
                                CursorRemoteControlsPage remoteControlsPage,
                                DeviceLibrary deviceLibrary, TaskScheduler scheduler) {
        this.masterTrack = masterTrack;
        this.cursorDevice = cursorDevice;
        this.remoteControlsPage = remoteControlsPage;
        this.deviceLibrary = deviceLibrary;
        this.scheduler = scheduler;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        // Device chain navigation
        dispatcher.register("masterDevice/selectNext", params -> {
            cursorDevice.selectNext();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/selectPrevious", params -> {
            cursorDevice.selectPrevious();
            return new JsonPrimitive("ok");
        });

        // Preset navigation
        dispatcher.register("masterDevice/nextPreset", params -> {
            cursorDevice.switchToNextPreset();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/previousPreset", params -> {
            cursorDevice.switchToPreviousPreset();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/nextPresetCategory", params -> {
            cursorDevice.switchToNextPresetCategory();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/previousPresetCategory", params -> {
            cursorDevice.switchToPreviousPresetCategory();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/nextPresetCreator", params -> {
            cursorDevice.switchToNextPresetCreator();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/previousPresetCreator", params -> {
            cursorDevice.switchToPreviousPresetCreator();
            return new JsonPrimitive("ok");
        });

        // Device state
        dispatcher.register("masterDevice/setEnabled", params -> {
            boolean enabled = requireBoolean(params, "enabled");
            cursorDevice.isEnabled().set(enabled);
            return new JsonPrimitive("ok");
        });

        // Device insertion
        dispatcher.register("masterDevice/insertBitwigDevice", params -> {
            String name = requireString(params, "name");
            String position = optionalString(params, "position", "end");
            Path devicePath = deviceLibrary.resolve(name);
            getInsertionPoint(position).insertFile(devicePath.toString());
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/insertPluginDevice", params -> {
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

        // Device removal
        dispatcher.register("masterDevice/remove", params -> {
            cursorDevice.deleteObject();
            scheduler.schedule(() -> cursorDevice.selectFirstInChannel(masterTrack), FLUSH_DELAY_MS);
            return new JsonPrimitive("ok");
        });

        // Parameter page navigation
        dispatcher.register("masterDevice/selectPage", params -> {
            int index = requireInt(params, "index");
            remoteControlsPage.selectedPageIndex().set(index);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/nextPage", params -> {
            remoteControlsPage.selectNextPage(false);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/previousPage", params -> {
            remoteControlsPage.selectPreviousPage(false);
            return new JsonPrimitive("ok");
        });

        // Nested device chain navigation
        dispatcher.register("masterDevice/enterSlot", params -> {
            String name = requireString(params, "name");
            cursorDevice.selectFirstInSlot(name);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/exitToParent", params -> {
            cursorDevice.selectParent();
            return new JsonPrimitive("ok");
        });

        // Layer and drum pad navigation
        dispatcher.register("masterDevice/enterLayer", params -> {
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

        dispatcher.register("masterDevice/enterKeyPad", params -> {
            int key = requireInt(params, "key");
            if (key < 0 || key > 127) {
                throw new IllegalArgumentException("key must be 0-127, got " + key);
            }
            cursorDevice.selectFirstInKeyPad(key);
            return new JsonPrimitive("ok");
        });

        // Parameter page tag filtering
        dispatcher.register("masterDevice/selectPageByTag", params -> {
            String tag = requireString(params, "tag").toLowerCase();
            DeviceHandler.validatePageTag(tag);
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

        // Parameter mutation
        dispatcher.register("masterDevice/setParameterValue", params -> {
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
        dispatcher.register("masterDevice/setParameters", params -> {
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

            // Apply first page immediately (this flush cycle)
            JsonObject firstPage = pages.get(0).getAsJsonObject();
            int firstPageIndex = firstPage.get("pageIndex").getAsInt();
            JsonArray firstPageParams = firstPage.getAsJsonArray("params");
            remoteControlsPage.selectedPageIndex().set(firstPageIndex);
            for (JsonElement paramEl : firstPageParams) {
                JsonObject p = paramEl.getAsJsonObject();
                remoteControlsPage.getParameter(p.get("index").getAsInt())
                    .value().setImmediately(p.get("value").getAsDouble());
            }

            // Schedule subsequent pages across flush cycles
            for (int i = 1; i < pages.size(); i++) {
                JsonObject page = pages.get(i).getAsJsonObject();
                int pageIndex = page.get("pageIndex").getAsInt();
                JsonArray pageParams = page.getAsJsonArray("params");
                long delay = FLUSH_DELAY_MS * i;
                scheduler.schedule(() -> {
                    remoteControlsPage.selectedPageIndex().set(pageIndex);
                    for (JsonElement paramEl : pageParams) {
                        JsonObject p = paramEl.getAsJsonObject();
                        remoteControlsPage.getParameter(p.get("index").getAsInt())
                            .value().setImmediately(p.get("value").getAsDouble());
                    }
                }, delay);
            }

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("pageCount", pages.size());
            result.addProperty("paramCount", totalParams);
            return result;
        });

        // --- Remote control mapping ---

        dispatcher.register("masterDevice/setParameterMapping", params -> {
            int index = requireInt(params, "index");
            if (index < 0 || index >= PARAM_COUNT) {
                throw new IllegalArgumentException("index must be 0-" + (PARAM_COUNT - 1));
            }
            boolean enabled = requireBoolean(params, "enabled");
            remoteControlsPage.getParameter(index).isBeingMapped().set(enabled);
            return new JsonPrimitive("ok");
        });

        dispatcher.register("masterDevice/getParameterMapping", params -> {
            JsonArray result = new JsonArray();
            for (int i = 0; i < PARAM_COUNT; i++) {
                result.add(remoteControlsPage.getParameter(i).isBeingMapped().get());
            }
            return result;
        });
    }

    private InsertionPoint getInsertionPoint(String position) {
        switch (position) {
            case "end":
                return masterTrack.endOfDeviceChainInsertionPoint();
            case "before":
                return cursorDevice.beforeDeviceInsertionPoint();
            case "after":
                return cursorDevice.afterDeviceInsertionPoint();
            default:
                throw new IllegalArgumentException("position must be 'end', 'before', or 'after', got: " + position);
        }
    }

}
