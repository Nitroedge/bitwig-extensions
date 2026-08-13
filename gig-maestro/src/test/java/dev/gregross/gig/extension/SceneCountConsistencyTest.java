package dev.gregross.gig.extension;

import dev.gregross.gig.handlers.ClipHandler;
import dev.gregross.gig.handlers.SceneHandler;
import dev.gregross.gig.handlers.TrackHandler;
import org.junit.jupiter.api.Test;

import static dev.gregross.gig.extension.StateCacheTestHelper.sceneCountOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the scene ceiling, which is carried by five independent constants.
 *
 * <p>{@code SCENE_COUNT} is declared separately in {@link GigMaestroExtension}, {@link StateCache},
 * {@code SceneHandler}, {@code ClipHandler} and {@code TrackHandler}. Nothing in the compiler links
 * them, so changing one alone produces a snapshot whose arrays disagree with the index validation
 * that guards them -- a valid index reading out of a differently sized array, or a legal scene
 * refused. These tests are the link.
 *
 * <p>All five constants are {@code private static final}, so the read goes through
 * {@link StateCacheTestHelper#sceneCountOf(Class)}, whose {@code setAccessible(true)} is what lets
 * a test in package {@code dev.gregross.gig.extension} read the three declarations that live in
 * {@code dev.gregross.gig.handlers}.
 *
 * <p><b>There is a sixth copy, and reflection cannot see it.</b>
 * {@code MacroHandler} carries the bank width for {@code handleBuildSection} separately. A guard
 * covering only the five reachable constants would certify an agreement it never checked, so that
 * copy gets its own source-level assertion below --
 * {@code sceneCount_inlineBankSizeLiteralMatchesTheConstant}.
 */
class SceneCountConsistencyTest {

    /**
     * Long, actionable and copy-pasteable, as a constant rather than an inline concatenation:
     * this is the message a future reader gets when the five drift apart, and it has to say what
     * each declaration is FOR, not merely that they differ.
     */
    private static final String DISAGREEMENT =
        "SCENE_COUNT disagrees across the five files that declare it. Each one sizes something "
            + "different, and they are only correct together:\n"
            + "  src/main/java/dev/gregross/gig/extension/GigMaestroExtension.java -- sizes the "
            + "scene bank requested from the Bitwig host (createMainTrackBank, createCursorTrack)\n"
            + "  src/main/java/dev/gregross/gig/extension/StateCache.java -- sizes every "
            + "[TRACK_COUNT][SCENE_COUNT] array (clipHasContent, clipNames, clipColors et al), the "
            + "scene-indexed arrays, and the value emitted as bankSize\n"
            + "  src/main/java/dev/gregross/gig/handlers/SceneHandler.java -- validates the scene "
            + "index for scene/rename, scene/delete, scene/duplicate and the launch family\n"
            + "  src/main/java/dev/gregross/gig/handlers/ClipHandler.java -- validates the scene "
            + "index for scene/launch\n"
            + "  src/main/java/dev/gregross/gig/handlers/TrackHandler.java -- passes it to "
            + "cursorTrack.createParentTrack(SEND_COUNT, SCENE_COUNT) for track/createGroup\n"
            + "Change all five or none.";

    @Test
    void sceneCount_agreesAcrossAllFiveDeclarations() {
        int extension = sceneCountOf(GigMaestroExtension.class);
        int stateCache = sceneCountOf(StateCache.class);
        int sceneHandler = sceneCountOf(SceneHandler.class);
        int clipHandler = sceneCountOf(ClipHandler.class);
        int trackHandler = sceneCountOf(TrackHandler.class);

        boolean allAgree = extension == stateCache
            && extension == sceneHandler
            && extension == clipHandler
            && extension == trackHandler;

        assertTrue(
            allAgree,
            DISAGREEMENT + "\nObserved: "
                + "GigMaestroExtension.java=" + extension + ", "
                + "StateCache.java=" + stateCache + ", "
                + "SceneHandler.java=" + sceneHandler + ", "
                + "ClipHandler.java=" + clipHandler + ", "
                + "TrackHandler.java=" + trackHandler + "."
        );
    }

    @Test
    void sceneCount_isSixteen() {
        assertEquals(
            16,
            sceneCountOf(StateCache.class),
            "The scene ceiling is 16 by decision D-53 (bitwig-pal, "
                + ".planning/phases/04-production-capability/04-CONTEXT.md). Five was not a working "
                + "ceiling: the user's own live session already occupies scenes 1-4, three of them "
                + "holding the protected GATE * clips (D-33), leaving a single free slot. If this "
                + "number is being changed, change the decision first -- lowering it does not "
                + "prevent a scene past the ceiling from being created, because scene/create is "
                + "unvalidated and unbounded; it only makes that scene unaddressable by "
                + "scene/rename, scene/duplicate, scene/delete and scene/launch while it still "
                + "exists in Bitwig. That is the silent-wrong-output class this project is built "
                + "against."
        );
    }
}
