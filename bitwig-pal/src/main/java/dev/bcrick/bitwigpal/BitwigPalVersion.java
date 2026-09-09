package dev.bcrick.bitwigpal;

/**
 * The engine's release version, declared exactly once.
 *
 * <p><b>Why this class exists.</b> Decision <b>D-21-H</b> (bitwig-pal,
 * {@code .planning/phases/21-installation-and-distribution-decision/21-CONTEXT.md}) says there is
 * ONE version string for the whole product. Before this class there were four independent
 * {@code "0.1.0"} literals, three of them in this repository -- {@code build.gradle.kts}'s
 * {@code version}, {@code BitwigPalDefinition.getVersion()}, and a hard-coded
 * {@code "{\"status\":\"ok\",\"version\":\"0.1.0\"}"} inside {@code HttpRpcServer.handleHealth}.
 * The third was the one that mattered most, because it was the ONLY version string reachable over
 * the wire: bitwig-pal's {@code diagnose} tool reported a version mismatch by reading it, so a
 * literal that drifted made the mismatch report itself wrong. Every one of those sites now reads
 * this constant.
 *
 * <p><b>The twins this constant has, and what pins each pair.</b> One number cannot live in one
 * place across two repositories and two languages, so the copies that remain are declared
 * deliberately and each pair is held equal by a test rather than by a comment:
 *
 * <ul>
 *   <li>{@code bitwig-pal/build.gradle.kts}'s {@code version} line -- pinned to this constant by
 *       {@code dev.bcrick.bitwigpal.VersionConsistencyTest}, which reads that build file as text
 *       (the Gradle value is not visible to a JUnit test any other way).</li>
 *   <li>bitwig-pal's {@code bitwig_pal.__version__} and {@code pyproject.toml}'s
 *       {@code [project].version} -- pinned to this constant from the other side of the repository
 *       boundary by {@code tests/test_engine_constant_consistency.py}, which regexes this file.</li>
 *   <li>the mock harness's {@code mock.state.ENGINE_VERSION}, which models what
 *       {@code api/version} answers. That one is deliberately NOT an import of the product's
 *       number -- a harness that could not disagree with the product would be useless for
 *       detecting a disagreement -- so its default is pinned equal by test instead.</li>
 * </ul>
 *
 * <p><b>Where this value is published.</b> {@code BitwigPalDefinition.getVersion()} (what Bitwig's
 * Controllers panel shows), the {@code /health} response body, and the {@code api/version} RPC
 * method registered in {@code BitwigPalExtension.init()} beside {@code api/list}.
 * {@code api/version} is the one bitwig-pal's {@code diagnose} probe actually calls: it goes
 * through the dispatcher like every other fact on the wire, so the tool needs no second code path
 * to {@code /health}.
 */
public final class BitwigPalVersion {

    /**
     * The single source of the engine's version. Changing it here is the whole change; the tests
     * named in this class's documentation fail until its twins are moved with it.
     */
    public static final String VERSION = "0.2.0";

    private BitwigPalVersion() {
        // Constant holder; never instantiated.
    }
}
