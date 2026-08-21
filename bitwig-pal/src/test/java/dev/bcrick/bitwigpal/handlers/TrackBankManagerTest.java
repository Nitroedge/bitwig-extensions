package dev.bcrick.bitwigpal.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackBankManagerTest {

    private static final int BANK_SIZE = 64;

    // Uses null TrackBank — only testing pure validation logic, not Bitwig API calls
    private final TrackBankManager manager = new TrackBankManager(null, BANK_SIZE);

    // --- validateIndex: valid indices ---

    @Test
    void validateIndex_zero_passes() {
        assertDoesNotThrow(() -> manager.validateIndex(0));
    }

    @Test
    void validateIndex_maxValid_passes() {
        assertDoesNotThrow(() -> manager.validateIndex(63));
    }

    @Test
    void validateIndex_midRange_passes() {
        assertDoesNotThrow(() -> manager.validateIndex(32));
    }

    // --- validateIndex: out-of-range ---

    @Test
    void validateIndex_negativeOne_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> manager.validateIndex(-1));
        assertTrue(ex.getMessage().contains("-1"), "Message should include invalid index");
        assertTrue(ex.getMessage().contains("64"), "Message should include bank width");
    }

    @Test
    void validateIndex_equalToBankSize_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> manager.validateIndex(64));
        assertTrue(ex.getMessage().contains("64"), "Message should include invalid index");
    }

    @Test
    void validateIndex_largeOutOfRange_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> manager.validateIndex(999));
        assertTrue(ex.getMessage().contains("999"), "Message should include invalid index");
        assertTrue(ex.getMessage().contains("64"), "Message should include bank width");
    }

    @Test
    void validateIndex_largeNegative_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> manager.validateIndex(-100));
        assertTrue(ex.getMessage().contains("-100"), "Message should include invalid index");
    }

    // --- Error message format ---

    @Test
    void errorMessage_includesBankWidth() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> manager.validateIndex(70));
        assertTrue(ex.getMessage().contains("64"), "Message should include bank width of 64");
        assertTrue(ex.getMessage().contains("0–63"), "Message should include valid range");
    }

    @Test
    void errorMessage_includesInvalidIndex() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> manager.validateIndex(100));
        assertTrue(ex.getMessage().contains("100"), "Message should include the invalid index value");
    }

    // --- getBankSize ---

    @Test
    void getBankSize_returnsConfiguredSize() {
        assertEquals(64, manager.getBankSize());
    }

    @Test
    void getBankSize_customSize() {
        TrackBankManager small = new TrackBankManager(null, 8);
        assertEquals(8, small.getBankSize());
        assertDoesNotThrow(() -> small.validateIndex(7));
        assertThrows(IllegalArgumentException.class, () -> small.validateIndex(8));
    }
}
