package dev.bcrick.secondo.extension;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bcrick.secondo.SecondoVersion;
import dev.bcrick.secondo.handlers.ApplicationHandler;
import dev.bcrick.secondo.handlers.ArrangerClipHandler;
import dev.bcrick.secondo.handlers.ArrangerHandler;
import dev.bcrick.secondo.handlers.BrowserHandler;
import dev.bcrick.secondo.handlers.ClipHandler;
import dev.bcrick.secondo.handlers.DetailEditorHandler;
import dev.bcrick.secondo.handlers.DeviceHandler;
import dev.bcrick.secondo.handlers.DeviceLibrary;
import dev.bcrick.secondo.handlers.GrooveHandler;
import dev.bcrick.secondo.handlers.MacroHandler;
import dev.bcrick.secondo.handlers.MixerHandler;
import dev.bcrick.secondo.handlers.MasterDeviceHandler;
import dev.bcrick.secondo.handlers.MasterHandler;
import dev.bcrick.secondo.handlers.NoteHandler;
import dev.bcrick.secondo.handlers.NoteInputHandler;
import dev.bcrick.secondo.handlers.ProjectHandler;
import dev.bcrick.secondo.handlers.SceneHandler;
import dev.bcrick.secondo.handlers.SendHandler;
import dev.bcrick.secondo.handlers.TrackBankManager;
import dev.bcrick.secondo.handlers.TrackHandler;
import dev.bcrick.secondo.handlers.TransactionHandler;
import dev.bcrick.secondo.handlers.TransportHandler;
import dev.bcrick.secondo.rpc.CommandQueue;
import dev.bcrick.secondo.rpc.JsonRpcDispatcher;
import dev.bcrick.secondo.server.ServerManager;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SecondoExtension extends ControllerExtension {

    private static final int DEFAULT_PORT = 8787;
    private static final int TRACK_COUNT = 16;
    private static final int SEND_COUNT = 4;
    private static final int SCENE_COUNT = 16;
    // Public because both cursor clips are created with these dimensions and the handlers that
    // walk either grid must bound their walk by the same numbers. Promoted rather than
    // re-declared: a third declaration of 256/128 would be a new cross-declaration constant
    // needing its own consistency test to stay honest. As compile-time constants these are
    // inlined by javac, so referencing them adds no runtime dependency on this class.
    public static final int CLIP_GRID_WIDTH = 256;
    public static final int CLIP_GRID_HEIGHT = 128;

    // Device library resolution (upstream issue #1). Resolution order: explicit configuration
    // (Bitwig Settings -> Controllers -> Secondo) -> runtime discovery of a real Bitwig
    // install on this platform -> loud failure. No hardcoded platform-specific path constant --
    // that was the original defect (a macOS-only literal), and a corrected literal reproduces
    // the same defect class with a different value, since it breaks again on the next Bitwig
    // version bump. See docs/PHASE-0-FINDINGS.md, Finding 4.
    private static final String DEVICE_LIBRARY_OVERRIDE_LABEL = "Device Library Path (override)";
    private static final String DEVICE_LIBRARY_OVERRIDE_CATEGORY = "Secondo";

    private final ControllerHost host;
    private JsonRpcDispatcher dispatcher;
    private CommandQueue commandQueue;
    private ServerManager serverManager;
    private StateCache stateCache;

    protected SecondoExtension(SecondoDefinition definition, ControllerHost host) {
        super(definition, host);
        this.host = host;
    }

    @Override
    public void init() {
        // Create Bitwig API objects
        Transport transport = host.createTransport();
        TrackBank trackBank = host.createMainTrackBank(TRACK_COUNT, SEND_COUNT, SCENE_COUNT);
        trackBank.setShouldShowClipLauncherFeedback(true);
        trackBank.sceneBank().setIndication(true);
        MasterTrack masterTrack = host.createMasterTrack(0);
        Application application = host.createApplication();

        // Create cursor objects for device navigation
        CursorTrack cursorTrack = host.createCursorTrack("gig-cursor", "Secondo", 0, SCENE_COUNT, true);
        CursorDevice cursorDevice = cursorTrack.createCursorDevice("gig-device", "Gig Device", 0,
            CursorDeviceFollowMode.FOLLOW_SELECTION);
        CursorRemoteControlsPage remoteControlsPage = cursorDevice.createCursorRemoteControlsPage(8);

        // Create drum pad bank for reading drum pad names (128 = full MIDI range)
        com.bitwig.extension.controller.api.DrumPadBank drumPadBank = cursorDevice.createDrumPadBank(128);
        for (int i = 0; i < 128; i++) {
            com.bitwig.extension.controller.api.DrumPad pad =
                (com.bitwig.extension.controller.api.DrumPad) drumPadBank.getItemAt(i);
            pad.name().markInterested();
            pad.exists().markInterested();
        }

        // Create master cursor device for master bus FX
        CursorDevice masterCursorDevice = masterTrack.createCursorDevice("gig-master-device", 0);
        CursorRemoteControlsPage masterRemoteControlsPage = masterCursorDevice.createCursorRemoteControlsPage(8);

        // Create cursor clip for note editing
        Clip cursorClip = cursorTrack.createLauncherCursorClip("secondo-clip", "Gig Clip",
            CLIP_GRID_WIDTH, CLIP_GRID_HEIGHT);

        // Create cursor clip for ARRANGER timeline note editing. The factory called below is
        // declared on ControllerHost, NOT on Track, and has no Track overload -- so the launcher
        // line above cannot simply be copied with a different method name, and this is the ONE
        // call site of it in the tree. Consequences carried rather than discovered: it returns a
        // bare Clip, so
        // it has no isPinned() and no CursorClip.selectClip(Clip) (cursor/setPinned cannot apply
        // to it), and it does not follow cursorTrack -- which is why its observer registration
        // below takes one argument where the launcher's takes two.
        Clip arrangerClip = host.createArrangerCursorClip(CLIP_GRID_WIDTH, CLIP_GRID_HEIGHT);

        // Create project reference
        Project project = host.getProject();

        // Create note input for real-time MIDI injection
        NoteInput noteInput = host.getMidiInPort(0).createNoteInput("Secondo");
        Arpeggiator arpeggiator = noteInput.arpeggiator();
        NoteLatch noteLatch = noteInput.noteLatch();

        // Create popup browser for preset/device/sample browsing
        PopupBrowser popupBrowser = host.createPopupBrowser();

        // Create groove
        Groove groove = host.createGroove();

        // Create mixer
        Mixer mixer = host.createMixer();

        // Create arranger and cue marker bank
        Arranger arranger = host.createArranger();
        CueMarkerBank cueMarkerBank = arranger.createCueMarkerBank(16);

        // Create detail editor
        DetailEditor detailEditor = host.createDetailEditor();

        // Create infrastructure
        dispatcher = new JsonRpcDispatcher();
        commandQueue = new CommandQueue();
        serverManager = new ServerManager();
        stateCache = new StateCache();

        // Register all observers into StateCache
        stateCache.registerObservers(transport, trackBank, masterTrack, application, project);
        stateCache.registerClipObservers(trackBank);
        stateCache.registerDeviceObservers(cursorTrack, cursorDevice, remoteControlsPage);
        stateCache.registerClipCursorObservers(cursorClip, cursorTrack);
        stateCache.registerArrangerClipCursorObservers(arrangerClip);
        stateCache.registerArrangerObservers(arranger);
        stateCache.registerArrangementObservers(transport, cueMarkerBank);
        stateCache.registerSendObservers(trackBank, SEND_COUNT);
        stateCache.registerMixerObservers(trackBank);
        stateCache.registerGroupObservers(trackBank);
        stateCache.registerMasterDeviceObservers(masterCursorDevice, masterRemoteControlsPage);
        stateCache.registerBrowserObservers(popupBrowser);
        stateCache.registerFilterObservers(popupBrowser);
        stateCache.registerNoteInputObservers(arpeggiator, noteLatch);
        stateCache.registerGrooveObservers(groove);

        // Register session/snapshot handler
        dispatcher.register("session/snapshot", params -> stateCache.getSnapshot());

        // Register state/getTopics handler
        dispatcher.register("state/getTopics", params -> {
            JsonArray topics = new JsonArray();
            for (String topic : dev.bcrick.secondo.server.WsRpcServer.VALID_TOPICS) {
                topics.add(new JsonPrimitive(topic));
            }
            return topics;
        });

        // Register api/list handler
        dispatcher.register("api/list", params -> {
            JsonArray methods = new JsonArray();
            for (String method : dispatcher.getRegisteredMethods()) {
                methods.add(new JsonPrimitive(method));
            }
            return methods;
        });

        // Register api/version handler. It sits beside api/list on purpose: the release number is
        // a fact about this engine in the same sense the method list is, and putting it on the
        // dispatcher means a caller reaches it through the ordinary JSON-RPC path rather than
        // through a second code path to /health. secondo's `diagnose` tool refuses to GET
        // /health for exactly that reason, so this method is what its engine-version probe calls.
        // The value is SecondoVersion.VERSION -- the same constant getVersion() and the /health
        // body read (D-21-H).
        dispatcher.register("api/version", params -> {
            JsonObject version = new JsonObject();
            version.addProperty("version", SecondoVersion.VERSION);
            return version;
        });

        // Register handlers
        new ApplicationHandler(application, host, trackBank).register(dispatcher);
        new TransportHandler(transport, stateCache).register(dispatcher);
        TrackBankManager trackBankManager = new TrackBankManager(trackBank, TRACK_COUNT);
        new TrackHandler(trackBank, application, cursorTrack, trackBankManager, stateCache, noteInput).register(dispatcher);
        new MasterHandler(masterTrack).register(dispatcher);
        new ClipHandler(trackBank, trackBank.sceneBank(), cursorClip, stateCache).register(dispatcher);
        DeviceLibrary deviceLibrary = resolveDeviceLibrary();
        new DeviceHandler(cursorTrack, cursorDevice, remoteControlsPage, drumPadBank, deviceLibrary, transport, host, host::scheduleTask).register(dispatcher);
        new NoteHandler(cursorClip, stateCache).register(dispatcher);
        new ArrangerClipHandler(arrangerClip, stateCache).register(dispatcher);
        new SceneHandler(trackBank.sceneBank(), project, stateCache).register(dispatcher);
        new ArrangerHandler(arranger, transport, cueMarkerBank, arranger.getHorizontalScrollbarModel(), stateCache).register(dispatcher);
        new MasterDeviceHandler(masterTrack, masterCursorDevice, masterRemoteControlsPage, deviceLibrary, host::scheduleTask).register(dispatcher);
        new SendHandler(trackBank, SEND_COUNT).register(dispatcher);
        new ProjectHandler(project, stateCache).register(dispatcher);
        new TransactionHandler(dispatcher, stateCache).register(dispatcher);
        new BrowserHandler(popupBrowser, cursorDevice, stateCache).register(dispatcher);
        new NoteInputHandler(noteInput, arpeggiator, noteLatch, stateCache).register(dispatcher);
        new GrooveHandler(groove).register(dispatcher);
        new MixerHandler(mixer).register(dispatcher);
        new DetailEditorHandler(detailEditor).register(dispatcher);
        new MacroHandler(dispatcher, stateCache, host::scheduleTask).register(dispatcher);

        // Start servers
        try {
            serverManager.start(DEFAULT_PORT, this::handleRequest);
            host.println("Secondo started — HTTP on port " + DEFAULT_PORT
                + ", WebSocket on port " + (DEFAULT_PORT + 1));
        } catch (IOException e) {
            host.errorln("Failed to start Secondo servers: " + e.getMessage());
        }
    }

    @Override
    public void flush() {
        commandQueue.drainAndExecute(dispatcher);

        // Broadcast state change notifications with delta data to WebSocket clients
        if (serverManager.getWsClientCount() > 0) {
            JsonObject delta = stateCache.getDelta();
            if (delta != null) {
                serverManager.broadcastDelta(delta);
            }
        }
    }

    @Override
    public void exit() {
        if (serverManager != null) {
            serverManager.stop();
        }
        host.println("Secondo stopped");
    }

    private CompletableFuture<String> handleRequest(String requestJson) {
        CompletableFuture<String> future = commandQueue.enqueue(requestJson);
        host.requestFlush();
        return future;
    }

    // -----------------------------------------------------------------------------------------
    // Device library resolution (upstream issue #1, closed 2026-08-11 per QF-03).
    //
    // Resolution order, per the project's own decision (not the executor's judgement):
    //   1. Explicit configuration -- the "Device Library Path (override)" preference in
    //      Bitwig Settings -> Controllers -> Secondo, if the user has set one.
    //   2. Runtime discovery -- glob known install roots for the current platform. Chosen as the
    //      primary mechanism because it is version-independent and needs no per-platform
    //      registry/plist implementation to be upstreamable across macOS, Windows, and Linux.
    //   3. Loud failure -- if neither resolves to a directory containing at least one
    //      .bwdevice file, fall back to an empty library, but say so loudly and repeatedly.
    //      Silence, not the wrong path, was the actual defect Finding 4 identified: a scan
    //      failure must never look identical to "this Bitwig install genuinely has zero
    //      devices."
    // -----------------------------------------------------------------------------------------

    private DeviceLibrary resolveDeviceLibrary() {
        SettableStringValue override = host.getPreferences().getStringSetting(
            DEVICE_LIBRARY_OVERRIDE_LABEL, DEVICE_LIBRARY_OVERRIDE_CATEGORY, 400, "");
        override.markInterested();
        String overrideValue = override.get();

        Path chosen;
        String chosenReason;

        if (overrideValue != null && !overrideValue.isBlank()) {
            // User-typed input -- could contain any platform-illegal character, not just '*'.
            // Paths.get() throws InvalidPathException synchronously; catch it and fail loudly
            // rather than crash the extension the same way the unguarded glob string did.
            try {
                chosen = Paths.get(overrideValue.trim());
            } catch (java.nio.file.InvalidPathException e) {
                return failLoudly(
                    "the \"" + DEVICE_LIBRARY_OVERRIDE_LABEL + "\" preference is set to '"
                    + overrideValue.trim() + "', which is not a valid filesystem path: "
                    + e.getMessage());
            }
            chosenReason = "explicit configuration (\"" + DEVICE_LIBRARY_OVERRIDE_LABEL + "\" preference)";
        } else {
            List<Path> candidates = discoverDeviceLibraryCandidates();
            if (candidates.isEmpty()) {
                return failLoudly(
                    "no candidate device library directory was found by runtime discovery on this "
                    + "platform, and no \"" + DEVICE_LIBRARY_OVERRIDE_LABEL + "\" preference is set");
            } else if (candidates.size() == 1) {
                chosen = candidates.get(0);
                chosenReason = "the only device library directory discovered: " + chosen;
            } else {
                chosen = pickBestCandidate(candidates);
                chosenReason = "highest version-numbered install root among " + candidates.size()
                    + " candidates discovered (" + candidates + "), assumed to be the current install";
                host.println("Secondo: multiple Bitwig device library candidates found: "
                    + candidates + " -- chose " + chosen + " (" + chosenReason + ")");
            }
        }

        try {
            DeviceLibrary library = new DeviceLibrary(chosen);
            if (library.size() == 0) {
                return failLoudly(
                    "resolved device library directory " + chosen + " (source: " + chosenReason
                    + ") exists but contains zero .bwdevice files -- a resolved-but-wrong path is "
                    + "the same defect in nicer clothes, so this is treated as a failure, not a "
                    + "success");
            }
            host.println("Secondo: device library loaded: " + library.size() + " devices from "
                + chosen + " (source: " + chosenReason + ")");
            return library;
        } catch (IOException e) {
            return failLoudly(
                "failed to scan resolved device library directory " + chosen + " (source: "
                + chosenReason + "): " + e.getMessage());
        }
    }

    /**
     * Falls back to an empty device library, but makes the failure impossible to miss in the
     * Bitwig console -- multiple errorln calls rather than the single easily-scrolled-past line
     * the original swallowed-exception code produced. device/insertBitwigDevice and
     * device/listBitwigDevices remain visibly broken over RPC (an empty list / a resolution
     * failure), which is the same signal this whole investigation used to detect the original
     * defect -- so this is not a new silent-failure surface, it is the existing one now backed
     * by exhaustive resolution instead of one hardcoded guess.
     */
    private DeviceLibrary failLoudly(String reason) {
        host.errorln("############################################################");
        host.errorln("# SECONDO: Device library NOT found.");
        host.errorln("# Reason: " + reason);
        host.errorln("# device/insertBitwigDevice and device/listBitwigDevices will not find any");
        host.errorln("# devices until this is fixed.");
        host.errorln("# Fix: set the \"" + DEVICE_LIBRARY_OVERRIDE_LABEL + "\" preference in");
        host.errorln("#      Bitwig Settings -> Controllers -> " + DEVICE_LIBRARY_OVERRIDE_CATEGORY
            + " to the exact path of your");
        host.errorln("#      Bitwig installation's Library/devices directory.");
        host.errorln("############################################################");
        try {
            return new DeviceLibrary(Paths.get(""));
        } catch (IOException e2) {
            throw new RuntimeException("Failed to create empty device library fallback", e2);
        }
    }

    /**
     * Glob known install roots for the current platform. Windows and macOS are implemented and
     * verified against a real install this session (see docs/PHASE-0-FINDINGS.md); Linux has no
     * confirmed install convention for this fork and is deliberately left to explicit
     * configuration only, rather than guessing a path nobody has verified -- an unverified guess
     * is exactly the failure mode this fix exists to close, just moved to a new platform.
     */
    private List<Path> discoverDeviceLibraryCandidates() {
        List<String> globRoots = new ArrayList<>();
        if (host.platformIsWindows()) {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles == null || programFiles.isBlank()) {
                programFiles = "C:\\Program Files";
            }
            // "Bitwig Studio*" matches both the version-numbered install this machine actually
            // has ("Bitwig Studio6", no space before the digit) and an unversioned "Bitwig
            // Studio" install (glob '*' matches the empty string too) -- one pattern covers both
            // folder-naming conventions, not two.
            globRoots.add(programFiles + "\\Bitwig Studio*\\Library\\devices");
        } else if (host.platformIsMac()) {
            // The original hardcoded constant this replaces was exactly
            // "/Applications/Bitwig Studio.app/Contents/Resources/Library/devices" -- unversioned.
            // "Bitwig Studio*.app" matches that unversioned case (via '*' matching empty) and any
            // version-suffixed variant, the same one-pattern-covers-both-cases approach as Windows.
            globRoots.add("/Applications/Bitwig Studio*.app/Contents/Resources/Library/devices");
        }
        // else: Linux -- no glob root added; falls through to zero candidates, which the loud
        // failure path reports honestly rather than silently.

        List<Path> found = new ArrayList<>();
        for (String globRoot : globRoots) {
            found.addAll(globMatch(globRoot));
        }
        return found;
    }

    /**
     * Resolves a path pattern containing exactly one wildcard-bearing segment (e.g.
     * {@code C:\Program Files\Bitwig Studio*\Library\devices}) to every existing directory that
     * matches.
     *
     * <p><b>Deliberately operates on the pattern as a plain {@code String}, split on the path
     * separator, until the wildcard segment has been isolated.</b> An earlier version of this
     * method called {@code Paths.get(patternPath)} on the *entire* pattern string up front --
     * including the literal {@code *} -- which crashed the extension live in Bitwig on Windows
     * with {@code InvalidPathException: Illegal char <*> at index 30}: Windows' path parser
     * rejects {@code *} as an illegal filename character the moment it tries to construct a
     * {@link Path} from the string, before any segment-by-segment analysis ever runs. A {@code
     * Path} object is only ever constructed here from a segment that has already been confirmed
     * wildcard-free; the wildcard segment itself is resolved exclusively through {@link
     * Files#newDirectoryStream(Path, String)}, which treats its glob argument as a pattern
     * string matched against real directory-listing results, never as something parsed through
     * the platform path parser. This is the fix the crash asked for: enumerate real filesystem
     * entries and build only concrete, wildcard-free paths -- resolving to something wrong (or
     * throwing) is the same defect in different clothes as silently returning empty.
     */
    private List<Path> globMatch(String patternPath) {
        List<Path> results = new ArrayList<>();

        String separator = java.io.File.separator;
        String normalized = patternPath.replace('/', java.io.File.separatorChar)
            .replace('\\', java.io.File.separatorChar);
        String[] segments = normalized.split(java.util.regex.Pattern.quote(separator), -1);

        int wildcardIndex = -1;
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].contains("*")) {
                wildcardIndex = i;
                break;
            }
        }

        if (wildcardIndex < 0) {
            // No wildcard anywhere in the pattern -- safe to construct the literal path directly.
            Path literal;
            try {
                literal = Paths.get(patternPath);
            } catch (java.nio.file.InvalidPathException e) {
                host.errorln("Secondo: could not construct device library path '" + patternPath
                    + "': " + e.getMessage());
                return results;
            }
            if (Files.isDirectory(literal)) {
                results.add(literal);
            }
            return results;
        }

        // Build the search root from ONLY the literal (wildcard-free) segments that precede the
        // wildcard segment -- never touching the wildcard segment itself.
        StringBuilder parentBuilder = new StringBuilder();
        for (int i = 0; i < wildcardIndex; i++) {
            if (parentBuilder.length() > 0
                && parentBuilder.charAt(parentBuilder.length() - 1) != java.io.File.separatorChar) {
                parentBuilder.append(java.io.File.separatorChar);
            }
            parentBuilder.append(segments[i]);
        }
        Path parent;
        try {
            parent = Paths.get(parentBuilder.toString());
        } catch (java.nio.file.InvalidPathException e) {
            host.errorln("Secondo: could not construct device library search root '"
                + parentBuilder + "': " + e.getMessage());
            return results;
        }
        if (!Files.isDirectory(parent)) {
            return results;
        }

        String globSegment = segments[wildcardIndex];

        // Build the literal (wildcard-free) suffix that follows the wildcard segment.
        StringBuilder suffixBuilder = new StringBuilder();
        for (int i = wildcardIndex + 1; i < segments.length; i++) {
            if (suffixBuilder.length() > 0) {
                suffixBuilder.append(java.io.File.separatorChar);
            }
            suffixBuilder.append(segments[i]);
        }
        Path suffix = null;
        if (suffixBuilder.length() > 0) {
            try {
                suffix = Paths.get(suffixBuilder.toString());
            } catch (java.nio.file.InvalidPathException e) {
                host.errorln("Secondo: could not construct device library suffix '"
                    + suffixBuilder + "': " + e.getMessage());
                return results;
            }
        }

        // The ONLY place the wildcard segment is used: as a glob pattern string handed to the
        // directory-listing API, matched against real entry names it enumerates itself -- never
        // parsed as a Path.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, globSegment)) {
            for (Path match : stream) {
                Path candidate = suffix != null ? match.resolve(suffix) : match;
                if (Files.isDirectory(candidate)) {
                    results.add(candidate);
                }
            }
        } catch (IOException e) {
            host.errorln("Secondo: error scanning " + parent + " for pattern '" + globSegment
                + "': " + e.getMessage());
        }
        return results;
    }

    /**
     * Deterministically picks one candidate among several discovered device library directories:
     * the highest version number found in a "Bitwig Studio<N>" / "Bitwig Studio <N>"-style path
     * segment, ties broken by path string so the choice is reproducible. Logged by the caller
     * alongside the full candidate list, so a machine with two Bitwig versions installed never
     * silently picks the wrong one without saying so.
     */
    private Path pickBestCandidate(List<Path> candidates) {
        return candidates.stream()
            .max(Comparator.comparingInt(this::extractVersionNumber).thenComparing(Path::toString))
            .orElse(candidates.get(0));
    }

    private int extractVersionNumber(Path candidate) {
        for (int i = 0; i < candidate.getNameCount(); i++) {
            String segment = candidate.getName(i).toString();
            int idx = segment.toLowerCase().indexOf("bitwig studio");
            if (idx < 0) {
                continue;
            }
            String rest = segment.substring(idx + "bitwig studio".length());
            StringBuilder digits = new StringBuilder();
            for (char c : rest.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            if (digits.length() == 0) {
                return 0; // unversioned "Bitwig Studio" folder
            }
            try {
                return Integer.parseInt(digits.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return -1; // candidate didn't come from our own "Bitwig Studio*" glob -- shouldn't happen
    }
}
