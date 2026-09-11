package dev.bcrick.secondo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the engine's release version, which is carried by two things the compiler does not link:
 * {@link SecondoVersion#VERSION} and the {@code version = "..."} line in this module's
 * {@code build.gradle.kts}.
 *
 * <p>This is the {@code SceneCountConsistencyTest} idiom applied to a value reflection cannot
 * reach for a different reason. There, the sixth copy was a method-local; here, the second copy
 * lives in a BUILD SCRIPT, which is not on the test classpath and has no runtime representation a
 * JUnit test can query. So this test reads the build file as TEXT, exactly as that class reads
 * {@code MacroHandler.java} as text, and for the same stated reason: certifying an agreement you
 * never checked is worse than having no guard.
 *
 * <p><b>What each copy is FOR, because they are only correct together.</b>
 * {@code SecondoVersion.VERSION} is what the extension REPORTS -- through
 * {@code SecondoDefinition.getVersion()} in Bitwig's Controllers panel, through the
 * {@code /health} body, and through the {@code api/version} RPC that secondo's {@code diagnose}
 * tool reads. The Gradle {@code version} is what the BUILD is stamped with. A drift means an
 * artefact whose build metadata disagrees with what the running extension says about itself, and
 * secondo's version-mismatch diagnosis -- which exists to tell a user their extension is older
 * than their server -- would be comparing against the wrong number while reporting confidently.
 *
 * <p><b>What this test does NOT cover, and where that half lives.</b> The same version is also
 * declared on the secondo (Python) side, at {@code pyproject.toml}'s {@code [project].version}
 * and {@code secondo.__version__}. Those live in a different repository, so they are pinned to
 * {@link SecondoVersion} from that side by
 * {@code tests/test_engine_constant_consistency.py}. A green run here says nothing about them.
 */
class VersionConsistencyTest {

    private static final String BUILD_SCRIPT = "build.gradle.kts";

    /** Matches a top-level `version = "..."` assignment in the build script. */
    private static final Pattern VERSION_ASSIGNMENT =
        Pattern.compile("^\\s*version\\s*=\\s*\"([^\"]*)\"\\s*$", Pattern.MULTILINE);

    /** Block and line comments, stripped before matching. */
    private static final Pattern COMMENTS =
        Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /** A quoted three-part version literal, the shape this engine must no longer restate. */
    private static final Pattern QUOTED_VERSION = Pattern.compile("\"\\d+\\.\\d+\\.\\d+\"");

    private static final String WHY =
        "The engine's version is declared twice on purpose and the two copies are only correct "
            + "together:\n"
            + "  src/main/java/dev/bcrick/secondo/SecondoVersion.java -- VERSION, the value "
            + "the extension REPORTS: getVersion() in Bitwig's Controllers panel, the /health "
            + "body, and the api/version RPC that secondo's diagnose tool reads.\n"
            + "  " + BUILD_SCRIPT + " -- version, the value the BUILD is stamped with.\n"
            + "Decision D-21-H (secondo) says there is ONE version string for the product. "
            + "Change both, or neither. The Python half of the same pin -- pyproject.toml and "
            + "secondo.__version__ -- is asserted from the other repository by "
            + "tests/test_engine_constant_consistency.py and is NOT covered here.";

    @Test
    void version_agreesBetweenTheConstantAndTheBuildScript() {
        assertEquals(
            declaredGradleVersion(),
            SecondoVersion.VERSION,
            WHY
        );
    }

    @Test
    void version_isTheReleaseThisEngineWasCutFor() {
        assertEquals(
            "0.2.0",
            SecondoVersion.VERSION,
            "The engine's version is 0.2.0 (secondo decision D-21-H, phase 21). This "
                + "assertion is not redundant with the agreement test above: that one would stay "
                + "green if BOTH copies were moved to a value nothing else in the project "
                + "expects, and the version is compared across a repository boundary -- "
                + "secondo's diagnose tool reports a MISMATCH between what api/version answers "
                + "and what the server is. If this number is genuinely changing, change it in "
                + "pyproject.toml, secondo.__version__ and mock.state.ENGINE_VERSION in the "
                + "same change, or every one of those pins fails."
        );
    }

    @Test
    void version_isDeclaredExactlyOnceInTheEngineSources() {
        Path mainSources = locate("src/main/java");
        List<Path> offenders = new ArrayList<>();

        try (var walk = Files.walk(mainSources)) {
            List<Path> javaFiles = walk
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
            for (Path java : javaFiles) {
                if (java.getFileName().toString().equals("SecondoVersion.java")) {
                    continue;
                }
                String text = new String(Files.readAllBytes(java), StandardCharsets.UTF_8);
                String code = COMMENTS.matcher(text).replaceAll(" ");
                if (QUOTED_VERSION.matcher(code).find()) {
                    offenders.add(java);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to walk " + mainSources.toAbsolutePath(), e);
        }

        assertTrue(
            offenders.isEmpty(),
            "a quoted x.y.z version literal survives in engine main sources outside "
                + "SecondoVersion.java: " + offenders + ".\n"
                + "There used to be two such literals -- SecondoDefinition.getVersion() and a "
                + "hard-coded version inside HttpRpcServer.handleHealth -- and the second was the "
                + "only version string reachable over the wire, so its drift made secondo's "
                + "version-mismatch report itself wrong. Read SecondoVersion.VERSION instead of "
                + "restating the number.\n\n" + WHY
        );
    }

    private static String declaredGradleVersion() {
        Path script = locate(BUILD_SCRIPT);
        String text;
        try {
            text = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + script.toAbsolutePath(), e);
        }

        // Comments stripped first, and deliberately: the build file carries a comment ABOVE the
        // version line explaining the pin, and documentation text describing a value must never
        // be read as if it were the value. That confusion is on the record -- it happened on the
        // first run of SceneCountConsistencyTest, which is why that class strips comments too.
        String code = COMMENTS.matcher(text).replaceAll(" ");

        Matcher matcher = VERSION_ASSIGNMENT.matcher(code);
        assertTrue(
            matcher.find(),
            "Could not find a top-level `version = \"...\"` assignment in the CODE of "
                + BUILD_SCRIPT + " (comments are excluded from the search). This test reads that "
                + "line as text -- a Gradle value has no runtime a JUnit test can query -- so a "
                + "rename or a restructure makes it unable to check anything, which is a FAILURE "
                + "and not a pass. Re-point VERSION_ASSIGNMENT at whatever now carries the "
                + "module's version.\n\n" + WHY
        );

        String value = matcher.group(1);

        assertFalse(
            matcher.find(),
            "Found more than one top-level `version = \"...\"` assignment in the CODE of "
                + BUILD_SCRIPT + ". This test checks exactly one, so a second would go "
                + "unguarded -- the same failure mode that produced this test.\n\n" + WHY
        );

        return value;
    }

    /**
     * Gradle runs tests with the module directory as the working directory, but this test is
     * cheap to run from the repository root by hand too, so both are tried before failing. Copied
     * from {@code SceneCountConsistencyTest.locateMacroHandlerSource} rather than shared, because
     * the two classes live in different packages and a helper visible to both would be a third
     * thing to keep in step.
     */
    private static Path locate(String relative) {
        Path[] candidates = {
            Path.of(relative),
            Path.of("secondo").resolve(relative),
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "Could not locate " + relative + " from working directory "
                + Path.of("").toAbsolutePath() + ". This test reads build and source files as "
                + "text because the values it guards are not reachable by reflection.");
    }
}
