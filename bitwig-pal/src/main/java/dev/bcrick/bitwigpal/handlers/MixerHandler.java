package dev.bcrick.bitwigpal.handlers;

import com.bitwig.extension.controller.api.Mixer;
import com.bitwig.extension.controller.api.SettableBooleanValue;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.bitwigpal.rpc.JsonRpcDispatcher;

import java.util.Set;

public class MixerHandler {

    private final Mixer mixer;

    private static final Set<String> SECTION_NAMES = Set.of(
        "meter", "io", "sends", "clipLauncher", "devices", "crossFade"
    );

    public MixerHandler(Mixer mixer) {
        this.mixer = mixer;
    }

    public void register(JsonRpcDispatcher dispatcher) {
        // mixer/getState — returns all 6 visibility booleans
        dispatcher.register("mixer/getState", params -> {
            JsonObject state = new JsonObject();
            state.addProperty("meter", mixer.isMeterSectionVisible().get());
            state.addProperty("io", mixer.isIoSectionVisible().get());
            state.addProperty("sends", mixer.isSendSectionVisible().get());
            state.addProperty("clipLauncher", mixer.isClipLauncherSectionVisible().get());
            state.addProperty("devices", mixer.isDeviceSectionVisible().get());
            state.addProperty("crossFade", mixer.isCrossFadeSectionVisible().get());
            return state;
        });

        // mixer/setSection — set visibility for a named section
        dispatcher.register("mixer/setSection", params -> {
            if (!params.has("section")) {
                throw new IllegalArgumentException("missing 'section' parameter");
            }
            if (!params.has("visible")) {
                throw new IllegalArgumentException("missing 'visible' parameter");
            }
            String section = params.get("section").getAsString();
            boolean visible = params.get("visible").getAsBoolean();

            if (!SECTION_NAMES.contains(section)) {
                throw new IllegalArgumentException("invalid section '" + section
                    + "', expected one of: meter, io, sends, clipLauncher, devices, crossFade");
            }

            getSection(section).set(visible);
            return new JsonPrimitive("ok");
        });

        // Zoom methods
        dispatcher.register("mixer/zoomInAll", params -> {
            mixer.zoomInTrackWidthsAll();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("mixer/zoomOutAll", params -> {
            mixer.zoomOutTrackWidthsAll();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("mixer/zoomInSelected", params -> {
            mixer.zoomInTrackWidthsSelected();
            return new JsonPrimitive("ok");
        });

        dispatcher.register("mixer/zoomOutSelected", params -> {
            mixer.zoomOutTrackWidthsSelected();
            return new JsonPrimitive("ok");
        });
    }

    private SettableBooleanValue getSection(String name) {
        return switch (name) {
            case "meter" -> mixer.isMeterSectionVisible();
            case "io" -> mixer.isIoSectionVisible();
            case "sends" -> mixer.isSendSectionVisible();
            case "clipLauncher" -> mixer.isClipLauncherSectionVisible();
            case "devices" -> mixer.isDeviceSectionVisible();
            case "crossFade" -> mixer.isCrossFadeSectionVisible();
            default -> throw new IllegalArgumentException("unknown section: " + name);
        };
    }
}
