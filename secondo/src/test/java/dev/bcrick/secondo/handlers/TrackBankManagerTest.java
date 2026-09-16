package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.TrackBank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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

    @Test
    void canonicalFlatBankIncludesGroupsEffectsAndExcludesMaster() {
        ControllerHost host = mock(ControllerHost.class);
        TrackBank flatDocumentBank = mock(TrackBank.class);
        TrackBank legacyMainBank = mock(TrackBank.class);
        when(host.createTrackBank(
            TrackBankManager.CANONICAL_BANK_SIZE,
            8,
            16,
            true
        )).thenReturn(flatDocumentBank);
        when(host.createMainTrackBank(TrackBankManager.CANONICAL_BANK_SIZE, 8, 16))
            .thenReturn(legacyMainBank);

        TrackBankManager canonical = TrackBankManager.createCanonical(host, 8, 16);

        assertSame(flatDocumentBank, canonical.getCanonicalFlatTrackBank());
        assertSame(legacyMainBank, canonical.getLegacyMainComparisonBank());
        assertEquals(16, canonical.getBankSize());
        verify(host).createTrackBank(16, 8, 16, true);
        verify(host).createMainTrackBank(16, 8, 16);
        verify(host, never()).createMasterTrack(anyInt());

        // Bitwig's FLATTEN order is already document preorder. Filtering only the exact
        // master row keeps an ordinary track, a group and nested children, then effects.
        List<Integer> canonicalSlots = TrackBankManager.canonicalBankSlots(List.of(
            "Instrument", // ordinary track
            "Group",      // group parent
            "Audio",      // direct child
            "Group",      // nested group child
            "Hybrid",     // nested grandchild
            "Effect",     // effect track
            "Master"      // separate explicit target, never a public track index
        ));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), canonicalSlots);
        assertFalse(canonicalSlots.contains(6), "Master must not receive a canonical track index");
    }

    @Test
    void identityRequiredMatchesObservedPositionPredicate() {
        TrackBank flatDocumentBank = mock(TrackBank.class);
        TrackBank legacyMainBank = mock(TrackBank.class);
        assertTrue(TrackBankManager.identityRequired(true, null, true, 0),
            "unobserved legacy position is risky");
        assertTrue(TrackBankManager.identityRequired(true, 0, true, null),
            "unobserved canonical position is risky");
        assertTrue(TrackBankManager.identityRequired(true, 3, true, 4),
            "different observed positions are risky");
        assertFalse(TrackBankManager.identityRequired(true, 3, true, 3),
            "equal observed positions preserve the old coordinate");
        assertFalse(TrackBankManager.identityRequired(false, null, true, 3),
            "a missing legacy entry had no old coordinate");
        assertFalse(TrackBankManager.identityRequired(true, 3, false, null),
            "a missing flat entry is handled as an ordinary range miss");

        TrackBankManager mapped =
            new TrackBankManager(flatDocumentBank, legacyMainBank, 4);
        mapped.observeCanonicalExists(0, true);
        mapped.observeCanonicalType(0, "Instrument");
        mapped.observeCanonicalPosition(0, 0);
        mapped.observeLegacyExists(0, true);
        mapped.observeLegacyPosition(0, 0);

        mapped.observeCanonicalExists(1, true);
        mapped.observeCanonicalType(1, "Master");
        mapped.observeCanonicalPosition(1, 99);

        mapped.observeCanonicalExists(2, true);
        mapped.observeCanonicalType(2, "Effect");
        mapped.observeCanonicalPosition(2, 1);
        mapped.observeLegacyExists(1, true);
        mapped.observeLegacyPosition(1, 9);

        mapped.observeCanonicalExists(3, false);
        mapped.observeCanonicalType(3, "Instrument");

        assertEquals(0, mapped.canonicalBankSlot(0));
        assertEquals(2, mapped.canonicalBankSlot(1),
            "master can consume any physical bank slot without receiving a public index");
        assertFalse(mapped.isTrackIdentityRequired(0));
        assertTrue(mapped.isTrackIdentityRequired(1));
        assertThrows(IllegalArgumentException.class, () -> mapped.canonicalBankSlot(2),
            "a missing current flat entry must take the ordinary range path");
    }

}
