package dev.bcrick.bitwigpal.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeviceLibraryTest {

    @TempDir
    Path tempDir;

    private DeviceLibrary library;

    @BeforeEach
    void setUp() throws IOException {
        // Create fake .bwdevice files
        Files.createFile(tempDir.resolve("Polymer.bwdevice"));
        Files.createFile(tempDir.resolve("Delay-2.bwdevice"));
        Files.createFile(tempDir.resolve("Compressor.bwdevice"));
        Files.createFile(tempDir.resolve("Chorus+.bwdevice"));
        Files.createFile(tempDir.resolve("EQ-5.bwdevice"));

        library = new DeviceLibrary(tempDir);
    }

    @Test
    void resolvesExactMatch() throws IOException {
        Path path = library.resolve("Polymer");
        assertEquals("Polymer.bwdevice", path.getFileName().toString());
    }

    @Test
    void resolvesCaseInsensitive() throws IOException {
        Path path = library.resolve("polymer");
        assertEquals("Polymer.bwdevice", path.getFileName().toString());

        Path path2 = library.resolve("DELAY-2");
        assertEquals("Delay-2.bwdevice", path2.getFileName().toString());
    }

    @Test
    void resolvesMixedCase() throws IOException {
        Path path = library.resolve("cOMPRESSOR");
        assertEquals("Compressor.bwdevice", path.getFileName().toString());
    }

    @Test
    void throwsOnNotFoundWithSuggestions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> library.resolve("Polymorph"));

        assertTrue(ex.getMessage().contains("Unknown device: 'Polymorph'"));
        assertTrue(ex.getMessage().contains("Did you mean:"));
        assertTrue(ex.getMessage().contains("Polymer"));
    }

    @Test
    void throwsOnCompletelyUnknown() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> library.resolve("zzzzzzzzzzzzzzzzz"));

        assertTrue(ex.getMessage().contains("Unknown device: 'zzzzzzzzzzzzzzzzz'"));
    }

    @Test
    void listDevicesReturnsSorted() {
        List<String> devices = library.listDevices();
        assertEquals(5, devices.size());
        // Case-insensitive sort order
        assertEquals("Chorus+", devices.get(0));
        assertEquals("Compressor", devices.get(1));
        assertEquals("Delay-2", devices.get(2));
        assertEquals("EQ-5", devices.get(3));
        assertEquals("Polymer", devices.get(4));
    }

    @Test
    void sizeMatchesFileCount() {
        assertEquals(5, library.size());
    }

    @Test
    void emptyDirectoryReturnsEmptyLibrary() throws IOException {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectory(emptyDir);
        DeviceLibrary emptyLib = new DeviceLibrary(emptyDir);

        assertEquals(0, emptyLib.size());
        assertTrue(emptyLib.listDevices().isEmpty());
    }

    @Test
    void nonExistentDirectoryReturnsEmptyLibrary() throws IOException {
        DeviceLibrary noDir = new DeviceLibrary(tempDir.resolve("nonexistent"));

        assertEquals(0, noDir.size());
        assertTrue(noDir.listDevices().isEmpty());
    }

    @Test
    void ignoresNonBwdeviceFiles() throws IOException {
        Files.createFile(tempDir.resolve("README.txt"));
        Files.createFile(tempDir.resolve("something.preset"));
        DeviceLibrary refreshed = new DeviceLibrary(tempDir);

        // Should still have only the 5 .bwdevice files
        assertEquals(5, refreshed.size());
    }

    @Test
    void prefixMatchSuggestedFirst() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> library.resolve("Comp"));

        assertTrue(ex.getMessage().contains("Compressor"));
    }
}
