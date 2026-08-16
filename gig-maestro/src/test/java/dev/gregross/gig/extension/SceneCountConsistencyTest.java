package dev.gregross.gig.extension;

import dev.gregross.gig.handlers.ClipHandler;
import dev.gregross.gig.handlers.MacroHandler;
import dev.gregross.gig.handlers.SceneHandler;
import dev.gregross.gig.handlers.TrackHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            + "  src/main/java/dev/gregross/gig/handlers/TrackHandler.java -- VESTIGIAL SINCE "
            + "plan 06-08 (2026-08-16) and kept ON PURPOSE. It used to be passed to "
            + "cursorTrack.createParentTrack(SEND_COUNT, SCENE_COUNT) for track/createGroup; that "
            + "method now refuses with GROUP_CREATION_NOT_SUPPORTED_BY_API, because "
            + "createParentTrack is an object-proxy factory and Controller API v25 has no "
            + "group-track creation method at all (finding O-33). The declaration stays so this "
            + "guard keeps covering five sites rather than quietly shrinking to four, and so a "
            + "future re-wiring of TrackHandler to the scene bank inherits the agreement instead "
            + "of re-deriving a literal.\n"
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

    // -----------------------------------------------------------------------------------------
    // The sixth copy. Everything below exists because reflection could not reach it.
    // -----------------------------------------------------------------------------------------

    private static final String MACRO_HANDLER_SOURCE =
        "src/main/java/dev/gregross/gig/handlers/MacroHandler.java";

    /** Matches `int bankSize = <literal-or-identifier>;` in handleBuildSection. */
    private static final Pattern BANK_SIZE_ASSIGNMENT =
        Pattern.compile("\\bint\\s+bankSize\\s*=\\s*([A-Za-z_$][A-Za-z0-9_$]*|\\d+)\\s*;");

    /** Block comments (javadoc included) and line comments, stripped before matching. */
    private static final Pattern COMMENTS =
        Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static final String WHY_THIS_ONE_IS_DIFFERENT =
        "This assertion exists SEPARATELY from sceneCount_agreesAcrossAllFiveDeclarations, and it "
            + "is not redundant with it.\n"
            + "The five-way agreement test reads five `private static final int SCENE_COUNT` "
            + "declarations by reflection. MacroHandler's copy of the bank width was originally a "
            + "METHOD-LOCAL -- `int bankSize = 5; // matches SCENE_COUNT` inside "
            + "handleBuildSection. A method-local is not a field, so reflection cannot see it at "
            + "all: a guard covering only the five would have reported agreement across the whole "
            + "engine while a sixth copy sat one file away saying 5, and its comment asserted a "
            + "correspondence that nothing enforced. Certifying an agreement you never checked is "
            + "worse than having no guard, so this one reads the SOURCE FILE instead.\n"
            + "The local has since been promoted to MacroHandler.SCENE_BANK_SIZE. That does NOT "
            + "make this assertion removable -- the promotion is precisely what it now guards. If "
            + "someone re-inlines a literal there, or edits the promoted constant alone, nothing "
            + "else in this suite notices.";

    @Test
    void sceneCount_inlineBankSizeLiteralMatchesTheConstant() {
        Path source = locateMacroHandlerSource();
        String text;
        try {
            text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + source.toAbsolutePath(), e);
        }

        // Comments are stripped first, and deliberately. On the first run of this test the
        // pattern matched the javadoc sentence describing the OLD value rather than the code --
        // documentation text read as if it were the code, which is exactly the confusion this
        // whole test class exists to prevent. Only code is searched now.
        String code = COMMENTS.matcher(text).replaceAll(" ");

        Matcher matcher = BANK_SIZE_ASSIGNMENT.matcher(code);
        assertTrue(
            matcher.find(),
            "Could not find a `int bankSize = ...;` assignment in the CODE of "
                + MACRO_HANDLER_SOURCE + " (comments are excluded from the search).\n"
                + "This test reads that line as text, so renaming or restructuring it makes the "
                + "test unable to check anything -- which is a failure, not a pass. Re-point "
                + "BANK_SIZE_ASSIGNMENT at whatever now carries the scene bank width, or delete "
                + "this test only if the bank width genuinely no longer appears in that file.\n\n"
                + WHY_THIS_ONE_IS_DIFFERENT
        );

        String token = matcher.group(1);

        assertFalse(
            matcher.find(),
            "Found more than one `int bankSize = ...;` assignment in the CODE of "
                + MACRO_HANDLER_SOURCE + ". This test checks exactly one, so a second copy would "
                + "go unguarded -- which is the same failure mode that produced this test in the "
                + "first place. Guard both, or give them distinguishable names.\n\n"
                + WHY_THIS_ONE_IS_DIFFERENT
        );
        int inlineValue = token.matches("\\d+")
            ? Integer.parseInt(token)
            : readIntConstant(MacroHandler.class, token);

        assertEquals(
            sceneCountOf(StateCache.class),
            inlineValue,
            "MacroHandler's scene bank width (`int bankSize = " + token + ";` in "
                + MACRO_HANDLER_SOURCE + ") disagrees with SCENE_COUNT. It is used to convert an "
                + "absolute scene index into a bank-relative slot index after scrolling, so a "
                + "value that is too small makes macro/buildSection rename and populate the WRONG "
                + "SLOT rather than fail.\n\n"
                + WHY_THIS_ONE_IS_DIFFERENT
        );
    }

    /** Reads a private static int constant by name, for the promoted-constant route. */
    private static int readIntConstant(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception e) {
            throw new RuntimeException(
                "`int bankSize = " + name + ";` in " + MACRO_HANDLER_SOURCE + " names something "
                    + "this test cannot resolve as a static int constant on "
                    + owner.getName() + ".", e);
        }
    }

    /**
     * Gradle runs tests with the module directory as the working directory, but this test is
     * cheap to run from the repository root by hand too, so both are tried before failing.
     */
    private static Path locateMacroHandlerSource() {
        Path[] candidates = {
            Path.of(MACRO_HANDLER_SOURCE),
            Path.of("gig-maestro").resolve(MACRO_HANDLER_SOURCE),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "Could not locate " + MACRO_HANDLER_SOURCE + " from working directory "
                + Path.of("").toAbsolutePath() + ". This test reads engine source as text "
                + "because the value it guards is not reachable by reflection.");
    }
}
