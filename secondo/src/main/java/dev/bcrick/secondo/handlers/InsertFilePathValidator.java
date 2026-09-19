package dev.bcrick.secondo.handlers;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The ONE engine-side file-path rule shared by every route that hands a filesystem path to
 * {@link com.bitwig.extension.controller.api.InsertionPoint#insertFile(String)}.
 *
 * <p>Extracted by Phase 29 plan 29-04 from {@code ClipHandler.validateClipFilePath}, which was
 * {@code private static} and hard-coded both the extension and the noun its four messages open
 * with. D-29-23 asked for the clip rule to be REUSED by {@code device/insertFile}; reuse as a
 * no-op was not available, so the rule was moved here and parameterised instead. The intent --
 * one rule, not two that can drift -- is what this class exists to hold. There must never be a
 * second copy of {@link #LOCAL_DRIVE_ROOT} anywhere under
 * {@code engine/secondo/src/main/java}.
 *
 * <p>Call sites, and the arguments each passes:
 * <ul>
 *   <li>{@code clip/insertFile} -- {@code (path, ".bwclip", "clip file path")}</li>
 *   <li>{@code device/insertFile} -- {@code (path, ".bwpreset", "preset file path")}</li>
 *   <li>{@code masterDevice/insertFile} -- {@code (path, ".bwpreset", "preset file path")}</li>
 * </ul>
 *
 * <p>The clip call site's four message strings must survive byte-identically: {@code
 * ClipHandlerTest} asserts on them and {@code src/secondo/tools/browse.py} mirrors their
 * fragments, so one changed character here is a break in two other places.
 */
final class InsertFilePathValidator {

    /**
     * A local drive-letter root as {@link Path#getRoot()} prints it on Windows: one letter, a
     * colon and one backslash. Anything else (a UNC root, a device root) is not local.
     */
    private static final Pattern LOCAL_DRIVE_ROOT = Pattern.compile("[A-Za-z]:\\\\");

    private InsertFilePathValidator() {
    }

    /** True when the first two characters are each a backslash or a forward slash. */
    private static boolean startsWithTwoSeparators(String path) {
        return path.length() >= 2 && isSeparator(path.charAt(0)) && isSeparator(path.charAt(1));
    }

    private static boolean isSeparator(char c) {
        return c == '\\' || c == '/';
    }

    /**
     * D-26-22: the engine-side file checks, in the contract's order. Each failure is an
     * IllegalArgumentException (-32602) and insertFile is never reached.
     *
     * <ol>
     *   <li>not absolute: "&lt;noun&gt; is not absolute: "</li>
     *   <li>not on a local drive-letter root: "&lt;noun&gt; is a network path: "</li>
     *   <li>final component does not end in the extension (any case) or is only the extension:
     *       "&lt;noun&gt; does not end in &lt;extension&gt;: "</li>
     *   <li>not a regular file: "&lt;noun&gt; is not an existing file: "</li>
     * </ol>
     *
     * <p>CR-01: the network test reads the parsed root, not a string prefix. Windows parses ANY
     * two leading separators, in any mix of backslash and forward slash, as a UNC root, so the
     * original two-literal prefix test let a backslash-then-slash or slash-then-backslash UNC
     * spelling through to {@link Files#isRegularFile}, which is an outbound SMB request on
     * Bitwig's control-surface thread (T-26-01, and T-29-10 for the device routes). A string
     * prefix was never the right test. The device-prefixed spellings
     * (backslash-backslash-question-mark, backslash-backslash-dot) may not parse at all, so they
     * are refused by the two-separator rule whether or not {@link Path#of} accepts them; a device
     * path to a local drive is refused too. The rule and the order are the ones
     * src/secondo/tools/browse.py and mock/state.py use (IN-02: a name is longer than the
     * extension alone). Every check before the existence check is a pure string parse that
     * touches nothing on disk or on the network.
     *
     * <p>A path the platform cannot parse at all (InvalidPathException) cannot be proven absolute
     * or existing; it skips the absoluteness check and ends at the last message unless an
     * earlier rule refuses it.
     *
     * <p>Accepted residual (owner answer (ii), 2026-09-16, T-26-64): the existence check still
     * runs on the control-surface thread for a local drive-letter root, so a mapped network drive
     * letter whose share is offline can stall the extension until Windows times the connection
     * out; moving the check off that thread needs Phase 29's deferrable responses.
     *
     * <p>T-26-64 has a SECOND site from Phase 29 plan 29-04: {@code device/insertFile} and
     * {@code masterDevice/insertFile} reach this same existence check, and their registrations in
     * {@code DeviceHandler} and {@code MasterDeviceHandler} name T-26-64 too, so a reader
     * arriving from either end finds the other. Phase 29's own deferral mechanism was considered
     * as the fix and rejected: using it would make those routes deferring ones, contradicting
     * D-29-20, whose reasoning -- v25 cannot enumerate inside a slot or a layer, so there is
     * nothing for a deferred verify to verify against -- is sound. The residual is ACCEPTED, and
     * nothing enforces its avoidance (T-29-09).
     *
     * @param path      the caller's raw path text, unmodified, and what each message echoes
     * @param extension the required final-component suffix, lower-case and dot-led (".bwclip")
     * @param noun      the phrase each of the four messages opens with ("clip file path")
     */
    static void validate(String path, String extension, String noun) {
        Path parsed;
        try {
            parsed = Path.of(path);
        } catch (InvalidPathException e) {
            parsed = null;
        }
        if (parsed != null && !parsed.isAbsolute()) {
            throw new IllegalArgumentException(noun + " is not absolute: " + path);
        }
        if (startsWithTwoSeparators(path)
                || (parsed != null && (parsed.getRoot() == null
                    || !LOCAL_DRIVE_ROOT.matcher(parsed.getRoot().toString()).matches()))) {
            throw new IllegalArgumentException(noun + " is a network path: " + path);
        }
        if (!hasExtensionName(finalComponent(path, parsed), extension)) {
            throw new IllegalArgumentException(
                noun + " does not end in " + extension + ": " + path);
        }
        if (parsed == null || !Files.isRegularFile(parsed)) {
            throw new IllegalArgumentException(noun + " is not an existing file: " + path);
        }
    }

    /** The final component: the parsed file name, else the raw text after the last separator. */
    private static String finalComponent(String path, Path parsed) {
        if (parsed != null) {
            Path name = parsed.getFileName();
            return name == null ? "" : name.toString();
        }
        int last = Math.max(path.lastIndexOf('\\'),path.lastIndexOf('/'));
        return path.substring(last + 1);
    }

    /** IN-02: ends in the extension in any case and is longer than the extension alone. */
    private static boolean hasExtensionName(String name, String extension) {
        return name.toLowerCase(Locale.ROOT).endsWith(extension)
            && name.length() > extension.length();
    }
}
