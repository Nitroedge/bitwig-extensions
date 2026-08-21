package dev.bcrick.bitwigpal.extension;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

import java.util.UUID;

public class BitwigPalDefinition extends ControllerExtensionDefinition {

    private static final UUID EXTENSION_UUID = UUID.fromString("514eed2b-a1b0-44e7-836d-d14389b7b15e");

    @Override
    public String getName() {
        return "Bitwig Pal";
    }

    @Override
    public String getAuthor() {
        return "bcrick";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public UUID getId() {
        return EXTENSION_UUID;
    }

    @Override
    public int getRequiredAPIVersion() {
        return 25;
    }

    @Override
    public String getHardwareVendor() {
        return "bcrick";
    }

    @Override
    public String getHardwareModel() {
        return "Bitwig Pal";
    }

    @Override
    public int getNumMidiInPorts() {
        return 1;
    }

    @Override
    public int getNumMidiOutPorts() {
        return 0;
    }

    @Override
    public void listAutoDetectionMidiPortNames(AutoDetectionMidiPortNamesList list, PlatformType platformType) {
        // No MIDI ports — pure network extension
    }

    @Override
    public ControllerExtension createInstance(ControllerHost host) {
        return new BitwigPalExtension(this, host);
    }
}
