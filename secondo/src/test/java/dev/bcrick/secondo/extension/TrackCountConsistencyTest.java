package dev.bcrick.secondo.extension;

import org.junit.jupiter.api.Test;

import static dev.bcrick.secondo.extension.StateCacheTestHelper.trackCountOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the track ceiling, which is carried by two independent constants.
 *
 * <p>{@code TRACK_COUNT} is declared separately in {@link SecondoExtension} (it sizes the
 * main track bank requested from the Bitwig host) and in {@link StateCache} (it sizes every
 * track-indexed array and is the value emitted as {@code bankSize}). Nothing in the compiler
 * links them, so changing one alone produces a silently truncated or over-allocated snapshot
 * rather than a build error. These tests are the link.
 *
 * <p>Both constants are {@code private static final}, so the read goes through
 * {@link StateCacheTestHelper#trackCountOf(Class)}; this test lives in package
 * {@code dev.bcrick.secondo.extension} for that reason.
 */
class TrackCountConsistencyTest {

    @Test
    void trackCount_agreesBetweenExtensionAndStateCache() {
        assertEquals(
            trackCountOf(SecondoExtension.class),
            trackCountOf(StateCache.class),
            "TRACK_COUNT disagrees between "
                + "src/main/java/dev/bcrick/secondo/extension/SecondoExtension.java and "
                + "src/main/java/dev/bcrick/secondo/extension/StateCache.java. "
                + "The first sizes the track bank requested from the host; the second sizes "
                + "StateCache's arrays and the emitted bankSize. Change both or neither."
        );
    }

    @Test
    void trackCount_isSixteen() {
        assertEquals(
            16,
            trackCountOf(StateCache.class),
            "The track ceiling is 16 by the ROADMAP Phase 2 locked track-ceiling decision "
                + "(secondo, .planning/ROADMAP.md Phase 2 override block): a hard ceiling, "
                + "deliberately not configurable. If this number is being changed, change the "
                + "decision first -- a silent downgrade here shrinks every session snapshot "
                + "bitwig_look reads."
        );
    }
}
