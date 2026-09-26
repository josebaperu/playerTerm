package org.example.ytm.model;

import org.example.ytm.source.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The playlists from the newest export in the playlists folder. Each export is
 * a JSON array of {@code {id, title, url, updatedDate}}; only the most recently
 * written file counts, so a new download replaces the library.
 */
public final class PlaylistLibrary {

    private static final Comparator<Playlist> BY_TITLE =
            Comparator.comparing(Playlist::name, PlaylistLibrary::compareNatural);

    private final Path root;
    private final List<Playlist> playlists = new ArrayList<>();
    private Path source;
    private FileTime sourceTime;
    private long sourceSize = -1;
    private String problem = "";

    public PlaylistLibrary(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** The export the library was read from, or null before one was found. */
    public Path source() {
        return source;
    }

    /** Why the newest export could not be used, empty when it could. */
    public String problem() {
        return problem;
    }

    public List<Playlist> playlists() {
        return playlists;
    }

    public int trackCount() {
        int n = 0;
        for (Playlist p : playlists) n += p.tracks().size();
        return n;
    }

    public boolean isEmpty() {
        return playlists.isEmpty();
    }

    /**
     * Reads the newest export when it differs from the one already loaded.
     * Playlists that are still listed keep their expansion and tracks.
     * Returns the playlists whose tracks need fetching, or null when nothing
     * changed. A file that does not parse, such as one still being written,
     * leaves the library as it was.
     */
    public List<Playlist> reload() {
        Path newest = newestExport();
        if (newest == null) {
            problem = "";
            if (source == null && playlists.isEmpty()) return null;
            source = null;
            sourceTime = null;
            sourceSize = -1;
            playlists.clear();
            return List.of();
        }
        FileTime time;
        long size;
        String text;
        try {
            time = Files.getLastModifiedTime(newest);
            size = Files.size(newest);
            if (newest.equals(source) && time.equals(sourceTime) && size == sourceSize) return null;
            text = Files.readString(newest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problem = newest.getFileName() + ": " + e.getMessage();
            return null;
        }

        List<Playlist> parsed;
        try {
            parsed = parse(text);
        } catch (IllegalArgumentException e) {
            problem = newest.getFileName() + ": not a playlist export (" + e.getMessage() + ")";
            return null;
        }
        problem = "";
        source = newest;
        sourceTime = time;
        sourceSize = size;

        Map<String, Playlist> existing = new HashMap<>();
        for (Playlist p : playlists) existing.put(p.id(), p);
        List<Playlist> next = new ArrayList<>();
        List<Playlist> stale = new ArrayList<>();
        for (Playlist fresh : parsed) {
            Playlist kept = existing.get(fresh.id());
            if (kept == null) {
                next.add(fresh);
                stale.add(fresh);
            } else {
                if (kept.update(fresh.name(), fresh.url(), fresh.updatedDate())) stale.add(kept);
                next.add(kept);
            }
        }
        next.sort(BY_TITLE);
        playlists.clear();
        playlists.addAll(next);
        return stale;
    }

    /** The most recently written export in the folder, ignoring partial downloads. */
    private Path newestExport() {
        Path best = null;
        FileTime bestTime = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (!isExport(entry.getFileName().toString()) || !Files.isRegularFile(entry)) continue;
                FileTime time = Files.getLastModifiedTime(entry);
                int cmp = bestTime == null ? 1 : time.compareTo(bestTime);
                // Same timestamp: the exporter names files after the time, so the larger name is newer.
                if (cmp > 0 || (cmp == 0 && entry.getFileName().toString()
                        .compareTo(best.getFileName().toString()) > 0)) {
                    best = entry;
                    bestTime = time;
                }
            }
        } catch (IOException e) {
            problem = "cannot read " + root + ": " + e.getMessage();
            return null;
        }
        return best;
    }

    public static boolean isExport(String fileName) {
        if (fileName.startsWith(".")) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".json");
    }

    private static List<Playlist> parse(String text) {
        if (!(Json.parse(text) instanceof List<?> items)) {
            throw new IllegalArgumentException("expected a list");
        }
        List<Playlist> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String url = text(map.get("url"));
            if (url.isEmpty()) continue;
            String id = text(map.get("id"));
            if (id.isEmpty()) id = url;
            if (seen.put(id, Boolean.TRUE) != null) continue;
            String title = text(map.get("title"));
            long updated = map.get("updatedDate") instanceof Double d ? d.longValue() : 0;
            out.add(new Playlist(id, title.isEmpty() ? url : title, url, updated));
        }
        return out;
    }

    private static String text(Object value) {
        return value instanceof String s ? s.trim() : "";
    }

    /** The visible lines, following what is currently expanded. */
    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (Playlist playlist : playlists) {
            out.add(Row.playlist(playlist));
            if (!playlist.isExpanded()) continue;
            for (Track track : playlist.tracks()) out.add(Row.track(track, playlist));
        }
        return out;
    }

    /**
     * Case-insensitive order that reads runs of digits as numbers, so
     * "2 Song" sorts before "10 Song" and "Disc 2" before "Disc 10".
     */
    static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                // Compare as numbers without parsing, so long runs cannot overflow.
                String na = stripZeros(a.substring(startA, i));
                String nb = stripZeros(b.substring(startB, j));
                if (na.length() != nb.length()) return Integer.compare(na.length(), nb.length());
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        int cmp = Integer.compare(a.length() - i, b.length() - j);
        return cmp != 0 ? cmp : a.compareTo(b);
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
        return digits.substring(k);
    }

    /**
     * Resolves the playlists folder: an explicit path, then
     * PLAYERTERM_PLAYLISTS, then ~/Downloads/ytm_playlists.
     */
    public static Path resolveRoot(String explicit) {
        if (explicit != null && !explicit.isBlank()) return Path.of(expandHome(explicit)).toAbsolutePath();
        String env = System.getenv("PLAYERTERM_PLAYLISTS");
        if (env != null && !env.isBlank()) return Path.of(expandHome(env)).toAbsolutePath();
        return Path.of(System.getProperty("user.home"), "Downloads", "ytm_playlists");
    }

    private static String expandHome(String value) {
        String home = System.getProperty("user.home");
        if (value.startsWith("$HOME")) return home + value.substring(5);
        if (value.startsWith("~")) return home + value.substring(1);
        return value;
    }
}
