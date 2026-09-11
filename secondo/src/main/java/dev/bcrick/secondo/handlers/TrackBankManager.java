package dev.bcrick.secondo.handlers;

import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Utility class for track bank index validation and selection.
 * Isolates paging/index concerns from the RPC handler layer.
 */
public class TrackBankManager {

    private final TrackBank trackBank;
    private final int bankSize;

    public TrackBankManager(TrackBank trackBank, int bankSize) {
        this.trackBank = trackBank;
        this.bankSize = bankSize;
    }

    /**
     * Validates that the given index is within the bank range [0, bankSize).
     *
     * @param index the track index to validate
     * @throws IllegalArgumentException if index is out of range, with message including bank width and invalid index
     */
    public void validateIndex(int index) {
        if (index < 0 || index >= bankSize) {
            throw new IllegalArgumentException(
                "Track index " + index + " out of range. Bank width is " + bankSize + " (valid: 0–" + (bankSize - 1) + ")."
            );
        }
    }

    /**
     * Selects a track by bank index. Validates the index first, then
     * calls selectInEditor() which causes the CursorTrack to follow.
     *
     * @param index the track index to select (0-based)
     * @throws IllegalArgumentException if index is out of range
     */
    public void selectByIndex(int index) {
        validateIndex(index);
        Track track = (Track) trackBank.getItemAt(index);
        track.selectInEditor();
    }

    /**
     * Returns the bank size (number of tracks in the bank).
     */
    public int getBankSize() {
        return bankSize;
    }
}
