package dev.bcrick.secondo.extension;

import dev.bcrick.secondo.handlers.DeviceHandler;
import dev.bcrick.secondo.handlers.MacroHandler;
import dev.bcrick.secondo.handlers.MasterDeviceHandler;
import dev.bcrick.secondo.handlers.ParkedRemoteControls;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static dev.bcrick.secondo.extension.StateCacheTestHelper.flushDelayOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the engine's own scheduler delay, which is carried by four independent constants.
 *
 * <p>{@code FLUSH_DELAY_MS} is declared separately in {@code MacroHandler},
 * {@code DeviceHandler}, {@code MasterDeviceHandler} and, since Phase 29 plan 29-05,
 * {@code ParkedRemoteControls}. Nothing in the compiler links them, so changing one alone leaves
 * three handlers deferring their continuations by one figure and the fourth by another -- and
 * because every one of those continuations is a task that runs LATER and
 * returns nothing to the caller, the disagreement does not surface as a build error or as a
 * failed call. It surfaces as a read that came back too early, on some operations and not others.
 *
 * <p>All four constants are {@code private static final}, so the read goes through
 * {@link StateCacheTestHelper#flushDelayOf(Class)}, whose {@code setAccessible(true)} is what
 * lets a test in package {@code dev.bcrick.secondo.extension} read four declarations that live in
 * {@code dev.bcrick.secondo.handlers}. This is the third application of the pattern
 * {@link TrackCountConsistencyTest} opened and {@link SceneCountConsistencyTest} extended.
 *
 * <p><b>THE DECLARATION NOBODY NAMED IS THE ONE THIS CLASS IS MOST AFRAID OF, AND ONE ARRIVED.</b>
 * The reflection test below can only ever check the classes it names: a handler that declares its
 * own copy would be scheduled by a number no test reads, while every reflection assertion here
 * stayed green. That is what {@code flushDelay_isDeclaredInExactlyTheFilesThisTestNames} is for --
 * it searches the whole of {@code src/main/java/dev/bcrick/secondo} as source text and fails if
 * the set of declaring files is not exactly the set this class knows about. It is not a
 * hypothetical: {@code ParkedRemoteControls} took the constant in plan 29-05 for WR-09's re-park
 * window, and the handler's own javadoc says it is spelled this way SO THAT this test finds it.
 * Neither test method carries a COUNT in its name any more, deliberately -- the two that did had
 * to be renamed the first time the count moved, and a name that has to be edited to stay true is
 * a name that will one day be left untrue.
 *
 * <p><b>WHAT THIS CLASS DOES NOT PROVE, and it matters because the quantity has been chased from
 * outside for a whole phase.</b> It proves the SOURCE DECLARES 100 in all four places and that
 * nothing silently changed one of them. It does NOT measure the scheduler hop. An external clock
 * sees the scheduler delay plus Bitwig's own work plus the observer flush as one sum and
 * separates none of them, so a consistent upper bound taken live is not a measurement of this
 * constant and this test is not one either. It is a pin, and a pin is what was asked for.
 */
class FlushDelayConsistencyTest {

    /**
     * Long, actionable and copy-pasteable, as a constant rather than an inline concatenation:
     * this is the message a future reader gets when the four drift apart, and it has to say
     * what each declaration is FOR, not merely that they differ.
     */
    private static final String DISAGREEMENT =
        "FLUSH_DELAY_MS disagrees across the four files that declare it. Each one defers "
            + "something different, and they are only correct together:\n"
            + "  src/main/java/dev/bcrick/secondo/handlers/MacroHandler.java -- schedules the "
            + "post-flush continuation of macro/createSound after macro/createTrack (:125-132) "
            + "and the deferred stages of eleven other macro flows: the section build (:174), "
            + "the per-page parameter writes at FLUSH_DELAY_MS * (2i + 1) (:251), the page walk "
            + "at FLUSH_DELAY_MS * (i + 1) (:296), the sound build (:385), the cumulative track "
            + "and clip stages (:457, :459, :489), the automation write's lead-in and per-point "
            + "steps (:555, :572) and the final restore (:749)\n"
            + "  src/main/java/dev/bcrick/secondo/handlers/DeviceHandler.java -- schedules "
            + "device-parameter and device-selection continuations: the page switch at "
            + "FLUSH_DELAY_MS * (i * 2) and the write that follows it at FLUSH_DELAY_MS * "
            + "(i * 2 + 1) (:186, :197), i.e. a 2N-1 HOP MULTIPLIER for an N-page write; the "
            + "re-selection after a delete (:254); the discovery scan's page walk (:504) and the "
            + "estimatedMs it hands back to the caller (:545)\n"
            + "  src/main/java/dev/bcrick/secondo/handlers/MasterDeviceHandler.java -- schedules "
            + "master-chain continuations: the re-selection after an insert (:123) and the "
            + "per-page write at FLUSH_DELAY_MS * i (:262), i.e. an N-1 HOP MULTIPLIER, because "
            + "the first page is applied synchronously and each later page carries its switch "
            + "and its write in ONE runnable\n"
            + "  src/main/java/dev/bcrick/secondo/handlers/ParkedRemoteControls.java -- does NOT "
            + "schedule BY it; it is the WIDTH OF THE RE-PARK WINDOW (WR-09, plan 29-05). A page "
            + "cursor that has drifted off its own index schedules at most one zero-delay re-park "
            + "per FLUSH_DELAY_MS, because the scheduled set takes one observer flush to come "
            + "back and a second task inside that window could only repeat it. Same quantity, "
            + "same reason -- one flush -- which is why it is THIS constant rather than a "
            + "private literal beside it that no test would read\n"
            + "THE TWO MULTIPLIERS ARE DELIBERATELY DIFFERENT IN SHAPE AND IDENTICAL IN THE "
            + "CONSTANT. DeviceHandler pays 2N-1 hops for N pages and MasterDeviceHandler pays "
            + "N-1 for the same method name, and that asymmetry is real, load-bearing and "
            + "recorded downstream (secondo, src/secondo/verify.py:1028-1029, and its "
            + "scheduler_hops(..., master=) argument). A reader who changes one declaration "
            + "because 'the other handler needs a different delay' has confused the multiplier "
            + "with the constant: it is the MULTIPLIER that differs per handler, and the "
            + "constant that must not.\n"
            + "Change all four or none.";

    @Test
    void flushDelay_agreesAcrossEveryDeclarationThisTestNames() {
        long macro = flushDelayOf(MacroHandler.class);
        long device = flushDelayOf(DeviceHandler.class);
        long masterDevice = flushDelayOf(MasterDeviceHandler.class);
        long parkedRemoteControls = flushDelayOf(ParkedRemoteControls.class);

        boolean allAgree = macro == device && macro == masterDevice
            && macro == parkedRemoteControls;

        assertTrue(
            allAgree,
            DISAGREEMENT + "\nObserved: "
                + "MacroHandler.java=" + macro + ", "
                + "DeviceHandler.java=" + device + ", "
                + "MasterDeviceHandler.java=" + masterDevice + ", "
                + "ParkedRemoteControls.java=" + parkedRemoteControls + "."
        );
    }

    @Test
    void flushDelay_isOneHundredAndFourDownstreamConstantsMirrorIt() {
        assertEquals(
            100L,
            flushDelayOf(MacroHandler.class),
            "The engine's scheduler delay is 100 ms, and FOUR constants in a DIFFERENT "
                + "REPOSITORY mirror this number by hand:\n"
                + "  secondo src/secondo/verify.py  FLUSH_DELAY_MS         (the write "
                + "path's per-hop allowance)\n"
                + "  secondo src/secondo/verify.py  DEVICE_FLUSH_DELAY_MS  (the device "
                + "family's per-hop allowance, which varies its HOP COUNT per operation and "
                + "never this figure)\n"
                + "  secondo mock/switches.py          MACRO_FLUSH_DELAY_MS   (the harness's "
                + "model of the macro deferral)\n"
                + "  secondo mock/switches.py          DEVICE_SCHEDULE_MS     (the harness's "
                + "model of the device deferral)\n"
                + "None of the four imports this constant -- they cannot, across two "
                + "repositories, and the harness pair deliberately would not even if it could, "
                + "because a harness that imported the product's constant could not disagree "
                + "with it. So this assertion is one half of the link and secondo's "
                + "tests/test_engine_constant_consistency.py is the other: that test reads THIS "
                + "FILE'S SOURCE and fails if the harness switches drift from it.\n"
                + "If this number is being changed, change it in all four engine declarations "
                + "AND open secondo's four -- a ceiling sized for a 100 ms hop against an "
                + "engine that now waits longer reports a good operation as unconfirmed, which "
                + "is the silent-wrong-output class both repositories are built against."
        );
    }

    // -----------------------------------------------------------------------------------------
    // The fourth declaration. Everything below exists because reflection cannot look for a
    // constant in a class nobody named.
    // -----------------------------------------------------------------------------------------

    private static final String SOURCE_ROOT = "src/main/java/dev/bcrick/secondo";

    /**
     * The files this class's reflection half checks, as repository-relative paths. This list IS
     * the test: a further file declaring the constant, or one of these no longer declaring it,
     * fails against it. It held three until plan 29-05 added the fourth, which is the event the
     * class comment above was written in fear of -- the guard worked, and the correct response
     * was to name the new file in BOTH halves rather than to relax either.
     */
    private static final List<String> DECLARING_FILES = List.of(
        "handlers/MacroHandler.java",
        "handlers/DeviceHandler.java",
        "handlers/MasterDeviceHandler.java",
        "handlers/ParkedRemoteControls.java"
    );

    /** Matches `FLUSH_DELAY_MS = <literal-or-identifier>;` however it is declared. */
    private static final Pattern FLUSH_DELAY_ASSIGNMENT =
        Pattern.compile("\\bFLUSH_DELAY_MS\\s*=\\s*([A-Za-z_$][A-Za-z0-9_$]*|\\d+)\\s*;");

    /** Block comments (javadoc included) and line comments, stripped before matching. */
    private static final Pattern COMMENTS =
        Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static final String WHY_THE_SOURCE_IS_SEARCHED =
        "This assertion exists SEPARATELY from flushDelay_agreesAcrossEveryDeclarationThisTest"
            + "Names, and it is not redundant with it.\n"
            + "The agreement test reads NAMED classes by reflection. It cannot "
            + "look for one more, because naming the class is how reflection finds anything: a "
            + "new handler declaring its own `private static final long FLUSH_DELAY_MS` would "
            + "schedule its continuations by a number no test reads, while every reflection "
            + "assertion in this class stayed green. Certifying an agreement you never checked "
            + "is worse than having no guard -- SceneCountConsistencyTest was written after "
            + "exactly that happened with a sixth copy of the scene bank width -- so this one "
            + "reads the SOURCE TREE instead and fails on a set difference in either direction:\n"
            + "  a file declaring it that this class does not name  -> UNGUARDED, add it to "
            + "DECLARING_FILES and to the reflection test\n"
            + "  a file this class names that no longer declares it -> the reflection test is "
            + "checking something that moved; re-point both halves\n"
            + "Comments are stripped before matching, and deliberately: on the first run of "
            + "SceneCountConsistencyTest the pattern matched a javadoc sentence describing the "
            + "OLD value rather than the code -- documentation text read as if it were the code, "
            + "which is the confusion this family of tests exists to prevent.";

    @Test
    void flushDelay_isDeclaredInExactlyTheFilesThisTestNames() throws IOException {
        Path root = locateSourceRoot();

        Map<String, String> declarations = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();

        try (Stream<Path> tree = Files.walk(root)) {
            List<Path> javaFiles = tree
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();

            for (Path file : javaFiles) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                // Comments stripped first -- see WHY_THE_SOURCE_IS_SEARCHED. Only code is
                // searched, so a javadoc sentence quoting the constant is not a declaration.
                String code = COMMENTS.matcher(text).replaceAll(" ");

                Matcher matcher = FLUSH_DELAY_ASSIGNMENT.matcher(code);
                if (!matcher.find()) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                declarations.put(relative, matcher.group(1));

                // The "found more than one" assertion, per file. A second declaration inside
                // one class would be a compile error, but a second ASSIGNMENT -- a non-final
                // copy, or a nested class carrying its own -- would not, and this test reads
                // only the first match, so a second copy would go unchecked.
                if (matcher.find()) {
                    duplicates.add(relative);
                }
            }
        }

        assertTrue(
            duplicates.isEmpty(),
            "Found more than one `FLUSH_DELAY_MS = ...;` assignment in the CODE of "
                + duplicates + ". This test reads exactly one per file, so a second copy would "
                + "go unguarded -- which is the same failure mode that produced this test in "
                + "the first place. Guard both, or give them distinguishable names.\n\n"
                + WHY_THE_SOURCE_IS_SEARCHED
        );

        assertEquals(
            List.copyOf(DECLARING_FILES).stream().sorted().toList(),
            declarations.keySet().stream().sorted().toList(),
            "The set of files under " + SOURCE_ROOT + " declaring FLUSH_DELAY_MS is not the set "
                + "this test names.\n\n" + WHY_THE_SOURCE_IS_SEARCHED
        );

        // And the source text agrees with what reflection read, which is what makes the two
        // halves one guard rather than two: a declaration that compiles to something other
        // than the literal beside it would pass one half and fail the other.
        long reflected = flushDelayOf(MacroHandler.class);
        for (Map.Entry<String, String> entry : declarations.entrySet()) {
            assertEquals(
                String.valueOf(reflected),
                entry.getValue(),
                "The source text of " + entry.getKey() + " declares FLUSH_DELAY_MS = "
                    + entry.getValue() + ", which is not what reflection reads from the compiled "
                    + "class (" + reflected + ").\n\n" + DISAGREEMENT
            );
        }
    }

    /**
     * Gradle runs tests with the module directory as the working directory, but this test is
     * cheap to run from the repository root by hand too, so both are tried before failing.
     */
    private static Path locateSourceRoot() {
        Path[] candidates = {
            Path.of(SOURCE_ROOT),
            Path.of("secondo").resolve(SOURCE_ROOT),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "Could not locate " + SOURCE_ROOT + " from working directory "
                + Path.of("").toAbsolutePath() + ". This test reads engine source as text "
                + "because a declaration in a class nobody named is not reachable by "
                + "reflection.");
    }
}
