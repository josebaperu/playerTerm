package org.example.ytm.source;

import org.example.ytm.model.Playlist;
import org.example.ytm.model.Track;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Saved playlist listings, so a restart can show the tracks without asking
 * yt-dlp to walk the playlist again. A listing is reused only when the
 * export still has the same link and date; a changed export is listed again
 * and replaces the saved one. Audio stream addresses are not saved: YouTube
 * expires them, and they are looked up when a track is played.
 */
public final class PlaylistCache {

    private final Path directory;

    public PlaylistCache(Path directory) {
        this.directory = directory;
    }

    /** {@code $XDG_CACHE_HOME/playerytm/playlists}, or {@code ~/.cache/playerytm/playlists}. */
    public static Path defaultDirectory() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("playerytm").resolve("playlists");
    }

    /**
     * The listing saved for this playlist's link and date, or null when there
     * is nothing usable. An empty list is a real listing of a playlist with
     * no playable tracks.
     */
    public List<Track> load(Playlist playlist) {
        Path file = file(playlist.id());
        if (!Files.isRegularFile(file)) return null;
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> root)) return null;
        if (!playlist.id().equals(text(root.get("id")))) return null;
        if (!playlist.url().equals(text(root.get("url")))) return null;
        if (!(root.get("updatedDate") instanceof Double updated) || updated.longValue() != playlist.updatedDate()) {
            return null;
        }
        if (!(root.get("tracks") instanceof List<?> items)) return null;
        List<Track> tracks = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String id = text(map.get("id"));
            if (id.isEmpty()) continue;
            String link = text(map.get("url"));
            if (link.isEmpty()) link = "https://music.youtube.com/watch?v=" + id;
            double duration = map.get("duration") instanceof Double d && Double.isFinite(d) ? d : 0;
            tracks.add(new Track(id, link, text(map.get("title")), text(map.get("artist")), playlist.name(), duration));
        }
        return tracks;
    }

    /**
     * Remembers a listing under the link and date it was fetched for. Those
     * are passed in, rather than read back off the playlist, because a newer
     * export can change the playlist while the file is being written.
     */
    public void store(String id, String url, long updatedDate, List<Track> tracks) {
        Path file = file(id);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.writeString(tmp, toJson(id, url, updatedDate, tracks), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // The listing is still in memory; the next launch lists it again.
            }
        }
    }

    /** Drops listings for playlists that are no longer in the library. */
    public void retain(List<Playlist> playlists) {
        if (!Files.isDirectory(directory)) return;
        Set<String> keep = new HashSet<>();
        for (Playlist playlist : playlists) keep.add(file(playlist.id()).getFileName().toString());
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (!keep.contains(name) && isCacheFile(name)) Files.deleteIfExists(entry);
            }
        } catch (IOException ignored) {
            // A leftover file costs another listing only if its playlist returns.
        }
    }

    private Path file(String id) {
        return directory.resolve(digest(id) + ".json");
    }

    private static String toJson(String id, String url, long updatedDate, List<Track> tracks) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(quote(id));
        sb.append(",\"url\":").append(quote(url));
        sb.append(",\"updatedDate\":").append(updatedDate);
        sb.append(",\"tracks\":[");
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(quote(track.id()));
            sb.append(",\"url\":").append(quote(track.url()));
            sb.append(",\"title\":").append(quote(track.title()));
            sb.append(",\"artist\":").append(quote(track.artist()));
            sb.append(",\"duration\":").append(number(track.duration()));
            sb.append('}');
        }
        sb.append("]}\n");
        return sb.toString();
    }

    private static String quote(String value) {
        if (value == null) value = "";
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0";
        if (value == Math.rint(value) && Math.abs(value) < (1L << 53)) return Long.toString((long) value);
        return Double.toString(value);
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }

    /** Cache files are named by the sha256 of the playlist id, so nothing else in the folder is removed. */
    private static boolean isCacheFile(String name) {
        if (name.length() != 64 + 5 || !name.endsWith(".json")) return false;
        for (int i = 0; i < 64; i++) {
            char c = name.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    private static String digest(String id) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
