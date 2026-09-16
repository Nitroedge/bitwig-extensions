package dev.bcrick.secondo.handlers;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;
import dev.bcrick.secondo.extension.StateCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns the engine's canonical track-bank coordinate.
 *
 * <p>The canonical bank is the visible flat document order exposed by Controller API v25.
 * The legacy main-track bank is retained only as a migration comparison source; it must not
 * be handed to indexed handlers once canonical wiring is complete. The document master keeps
 * its existing explicit handle and is removed from the numeric track-index mapping.</p>
 */
public class TrackBankManager {

    public static final int CANONICAL_BANK_SIZE = 16;
    static final String MASTER_TRACK_TYPE = "Master";

    private final TrackBank canonicalFlatTrackBank;
    private final TrackBank legacyMainComparisonBank;
    private final int bankSize;
    private final Boolean[] canonicalExists;
    private final Integer[] canonicalPositions;
    private final String[] canonicalTypes;
    private final Boolean[] legacyExists;
    private final Integer[] legacyPositions;
    private final String[] legacyNames;
    private StateCache stateCache;

    /**
     * Backward-compatible constructor for validation-only tests and callers with one bank.
     */
    public TrackBankManager(TrackBank trackBank, int bankSize) {
        this(trackBank, null, bankSize);
    }

    TrackBankManager(
            TrackBank canonicalFlatTrackBank,
            TrackBank legacyMainComparisonBank,
            int bankSize) {
        this.canonicalFlatTrackBank = canonicalFlatTrackBank;
        this.legacyMainComparisonBank = legacyMainComparisonBank;
        this.bankSize = bankSize;
        this.canonicalExists = new Boolean[bankSize];
        this.canonicalPositions = new Integer[bankSize];
        this.canonicalTypes = new String[bankSize];
        this.legacyExists = new Boolean[bankSize];
        this.legacyPositions = new Integer[bankSize];
        this.legacyNames = new String[bankSize];
    }

    /**
     * Creates the two fixed banks needed for the coordinate migration.
     */
    public static TrackBankManager createCanonical(
            ControllerHost host,
            int sendCount,
            int sceneCount) {
        Objects.requireNonNull(host, "host");
        TrackBank flatDocumentBank = host.createTrackBank(
            CANONICAL_BANK_SIZE,
            sendCount,
            sceneCount,
            true
        );
        TrackBank legacyMainBank = host.createMainTrackBank(
            CANONICAL_BANK_SIZE,
            sendCount,
            sceneCount
        );
        return new TrackBankManager(
            flatDocumentBank,
            legacyMainBank,
            CANONICAL_BANK_SIZE
        );
    }

    /**
     * Subscribe both banks exactly once and publish comparison evidence into the cache.
     */
    public void registerObservers(StateCache cache) {
        Objects.requireNonNull(cache, "cache");
        if (canonicalFlatTrackBank == null || legacyMainComparisonBank == null) {
            throw new IllegalStateException("Canonical and legacy banks are required");
        }
        if (stateCache != null) {
            throw new IllegalStateException("Track bank observers already registered");
        }
        stateCache = cache;

        for (int slot = 0; slot < bankSize; slot++) {
            final int bankSlot = slot;
            Track canonical = (Track) canonicalFlatTrackBank.getItemAt(slot);
            canonical.exists().markInterested();
            canonical.exists().addValueObserver(
                (BooleanValueChangedCallback) value -> observeCanonicalExists(bankSlot, value)
            );
            canonical.position().markInterested();
            canonical.position().addValueObserver(
                (IntegerValueChangedCallback) value -> observeCanonicalPosition(bankSlot, value)
            );
            canonical.trackType().markInterested();
            canonical.trackType().addValueObserver(
                (StringValueChangedCallback) value -> observeCanonicalType(bankSlot, value)
            );

            Track legacy = (Track) legacyMainComparisonBank.getItemAt(slot);
            legacy.exists().markInterested();
            legacy.exists().addValueObserver(
                (BooleanValueChangedCallback) value -> observeLegacyExists(bankSlot, value)
            );
            legacy.position().markInterested();
            legacy.position().addValueObserver(
                (IntegerValueChangedCallback) value -> observeLegacyPosition(bankSlot, value)
            );
            legacy.name().markInterested();
            legacy.name().addValueObserver(
                (StringValueChangedCallback) value -> observeLegacyName(bankSlot, value)
            );
        }
    }

    synchronized void observeCanonicalExists(int slot, boolean exists) {
        canonicalExists[slot] = exists;
        refreshMigrationEvidence(slot);
    }

    synchronized void observeCanonicalPosition(int slot, int position) {
        canonicalPositions[slot] = position;
        refreshMigrationEvidence(slot);
    }

    synchronized void observeCanonicalType(int slot, String type) {
        canonicalTypes[slot] = type;
        refreshMigrationEvidence(slot);
    }

    synchronized void observeLegacyExists(int slot, boolean exists) {
        legacyExists[slot] = exists;
        refreshMigrationEvidence(slot);
    }

    synchronized void observeLegacyPosition(int slot, int position) {
        legacyPositions[slot] = position;
        refreshMigrationEvidence(slot);
    }

    synchronized void observeLegacyName(int slot, String name) {
        legacyNames[slot] = name;
        refreshMigrationEvidence(slot);
    }

    private void refreshMigrationEvidence(int ignoredSlot) {
        if (stateCache == null) {
            return;
        }
        List<Integer> canonicalSlots = currentCanonicalSlots();
        for (int publicIndex = 0; publicIndex < canonicalSlots.size(); publicIndex++) {
            int canonicalSlot = canonicalSlots.get(publicIndex);
            Boolean comparisonExists =
                publicIndex < legacyExists.length ? legacyExists[publicIndex] : Boolean.FALSE;
            Integer comparisonPosition =
                publicIndex < legacyPositions.length ? legacyPositions[publicIndex] : null;
            String comparisonName =
                publicIndex < legacyNames.length ? legacyNames[publicIndex] : null;
            stateCache.updateTrackMigrationObservation(
                canonicalSlot,
                identityRequired(
                    comparisonExists,
                    comparisonPosition,
                    canonicalExists[canonicalSlot],
                    canonicalPositions[canonicalSlot]
                ),
                comparisonName,
                comparisonPosition
            );
        }
    }

    /**
     * The exact D-25-09 migration predicate. Names never participate.
     *
     * <p>A missing current flat row is handled by canonical range validation, so it does not
     * become an identity warning. A missing legacy row means no pre-migration coordinate existed
     * at that index and therefore no migration guard applies.</p>
     */
    public static boolean identityRequired(
            Boolean legacyEntryExists,
            Integer legacyPosition,
            Boolean canonicalEntryExists,
            Integer canonicalPosition) {
        if (!Boolean.TRUE.equals(legacyEntryExists)
                || !Boolean.TRUE.equals(canonicalEntryExists)) {
            return false;
        }
        return legacyPosition == null
            || canonicalPosition == null
            || !legacyPosition.equals(canonicalPosition);
    }

    /**
     * Returns flat-bank slots in canonical public order, excluding master and missing rows.
     */
    public static List<Integer> canonicalBankSlots(
            List<String> flatTrackTypes,
            List<Boolean> flatTrackExists) {
        Objects.requireNonNull(flatTrackTypes, "flatTrackTypes");
        Objects.requireNonNull(flatTrackExists, "flatTrackExists");
        int observedSlots = Math.min(
            Math.min(flatTrackTypes.size(), flatTrackExists.size()),
            CANONICAL_BANK_SIZE
        );
        List<Integer> canonicalSlots = new ArrayList<>(observedSlots);
        for (int bankSlot = 0; bankSlot < observedSlots; bankSlot++) {
            if (!Boolean.FALSE.equals(flatTrackExists.get(bankSlot))
                    && !MASTER_TRACK_TYPE.equals(flatTrackTypes.get(bankSlot))) {
                canonicalSlots.add(bankSlot);
            }
        }
        return List.copyOf(canonicalSlots);
    }

    /** Compatibility overload: an unspecified existence value is retained until observed false. */
    public static List<Integer> canonicalBankSlots(List<String> flatTrackTypes) {
        List<Boolean> exists = new ArrayList<>(flatTrackTypes.size());
        for (int i = 0; i < flatTrackTypes.size(); i++) {
            exists.add(null);
        }
        return canonicalBankSlots(flatTrackTypes, exists);
    }

    private synchronized List<Integer> currentCanonicalSlots() {
        if (canonicalFlatTrackBank == null) {
            List<Integer> slots = new ArrayList<>(bankSize);
            for (int i = 0; i < bankSize; i++) {
                slots.add(i);
            }
            return List.copyOf(slots);
        }
        return canonicalBankSlots(
            java.util.Arrays.asList(canonicalTypes),
            java.util.Arrays.asList(canonicalExists)
        );
    }

    /** Resolve a public non-master index to its physical FLATTEN bank slot. */
    public int canonicalBankSlot(int index) {
        if (canonicalFlatTrackBank == null) {
            if (index < 0 || index >= bankSize) {
                throw new IllegalArgumentException(
                    "Track index " + index + " out of range. Bank width is " + bankSize
                        + " (valid: 0\u2013" + (bankSize - 1) + ")."
                );
            }
            return index;
        }
        List<Integer> slots = currentCanonicalSlots();
        if (index < 0 || index >= slots.size()) {
            throw new IllegalArgumentException(
                "Track index " + index + " out of range. Observable canonical track count is "
                    + slots.size() + " within bank width " + bankSize + "."
            );
        }
        return slots.get(index);
    }

    public void validateIndex(int index) {
        canonicalBankSlot(index);
    }

    public Track getCanonicalTrack(int index) {
        if (canonicalFlatTrackBank == null) {
            throw new IllegalStateException("No canonical track bank is configured");
        }
        return (Track) canonicalFlatTrackBank.getItemAt(canonicalBankSlot(index));
    }

    public boolean isTrackIdentityRequired(int index) {
        int slot = canonicalBankSlot(index);
        synchronized (this) {
            return identityRequired(
                legacyExists[index],
                legacyPositions[index],
                canonicalExists[slot],
                canonicalPositions[slot]
            );
        }
    }

    public void selectByIndex(int index) {
        getCanonicalTrack(index).selectInEditor();
    }

    public int getBankSize() {
        return bankSize;
    }

    public TrackBank getCanonicalFlatTrackBank() {
        return canonicalFlatTrackBank;
    }

    public TrackBank getLegacyMainComparisonBank() {
        return legacyMainComparisonBank;
    }
}
