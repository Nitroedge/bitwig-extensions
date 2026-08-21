package dev.bcrick.bitwigpal.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitwigPalDefinitionTest {

    private final BitwigPalDefinition definition = new BitwigPalDefinition();

    @Test
    void name_returnsBitwigPal() {
        assertEquals("Bitwig Pal", definition.getName());
    }

    @Test
    void author_returnsBcrick() {
        assertEquals("bcrick", definition.getAuthor());
    }

    @Test
    void apiVersion_returns25() {
        assertEquals(25, definition.getRequiredAPIVersion());
    }

    @Test
    void id_isNotNull() {
        assertNotNull(definition.getId());
    }

    @Test
    void midiPorts_correctCounts() {
        assertEquals(1, definition.getNumMidiInPorts());
        assertEquals(0, definition.getNumMidiOutPorts());
    }
}
