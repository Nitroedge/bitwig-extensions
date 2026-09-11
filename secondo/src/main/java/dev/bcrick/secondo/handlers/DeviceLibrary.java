package dev.bcrick.secondo.handlers;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans a Bitwig device library directory for .bwdevice files and provides
 * case-insensitive name-to-path resolution with close-match suggestions on miss.
 */
public class DeviceLibrary {

    private static final String EXTENSION = ".bwdevice";

    private final Map<String, Path> deviceMap; // lowercase name → full path
    private final List<String> deviceNames;    // original-case, sorted

    public DeviceLibrary(Path libraryDir) throws IOException {
        Map<String, Path> map = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();

        if (Files.isDirectory(libraryDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(libraryDir, "*" + EXTENSION)) {
                for (Path file : stream) {
                    String filename = file.getFileName().toString();
                    String name = filename.substring(0, filename.length() - EXTENSION.length());
                    map.put(name.toLowerCase(), file);
                    names.add(name);
                }
            }
        }

        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        this.deviceMap = Collections.unmodifiableMap(map);
        this.deviceNames = Collections.unmodifiableList(names);
    }

    /**
     * Resolve a device name to its .bwdevice file path.
     * Matching is case-insensitive.
     *
     * @throws IllegalArgumentException if the device is not found, with close matches in the message
     */
    public Path resolve(String name) {
        Path path = deviceMap.get(name.toLowerCase());
        if (path != null) {
            return path;
        }

        List<String> suggestions = findClosestMatches(name, 5);
        StringBuilder msg = new StringBuilder("Unknown device: '").append(name).append("'.");
        if (!suggestions.isEmpty()) {
            msg.append(" Did you mean: ").append(String.join(", ", suggestions)).append("?");
        }
        throw new IllegalArgumentException(msg.toString());
    }

    /**
     * Returns a sorted list of all available device names (original case).
     */
    public List<String> listDevices() {
        return deviceNames;
    }

    /**
     * Returns the number of devices in the library.
     */
    public int size() {
        return deviceNames.size();
    }

    private List<String> findClosestMatches(String query, int maxResults) {
        String lowerQuery = query.toLowerCase();
        List<ScoredName> scored = new ArrayList<>();

        for (String name : deviceNames) {
            String lowerName = name.toLowerCase();

            // Prefix match gets highest priority
            if (lowerName.startsWith(lowerQuery) || lowerQuery.startsWith(lowerName)) {
                scored.add(new ScoredName(name, 0));
            } else if (lowerName.contains(lowerQuery) || lowerQuery.contains(lowerName)) {
                scored.add(new ScoredName(name, 1));
            } else {
                int dist = levenshtein(lowerQuery, lowerName);
                if (dist <= Math.max(3, lowerQuery.length() / 2)) {
                    scored.add(new ScoredName(name, dist + 2));
                }
            }
        }

        scored.sort((a, b) -> Integer.compare(a.score, b.score));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(maxResults, scored.size()); i++) {
            result.add(scored.get(i).name);
        }
        return result;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        return prev[b.length()];
    }

    private record ScoredName(String name, int score) {}
}
