package org.example.player.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-folder play order stored as {@code album.m3u} beside the audio files.
 * The visible list always follows that file. A missing file is written from
 * track-number tags, then read back, so the first open and every later open
 * take the same path.
 */
public final class AlbumPlaylist {

    public static final String FILE_NAME = "album.m3u";

    private AlbumPlaylist() {
    }

    public static Path file(MusicFolder folder) {
        return folder.path().resolve(FILE_NAME);
    }

    public static boolean exists(MusicFolder folder) {
        return Files.isRegularFile(file(folder));
    }

    /**
     * Track-number order. Untagged files keep their natural filename order
     * and sit after anything that has a number.
     */
    public static List<Track> sequenced(List<Track> tracks) {
        List<Track> ordered = new ArrayList<>(tracks);
        ordered.sort(Comparator
                .comparingInt(AlbumPlaylist::rank)
                .thenComparing(track -> track.path().getFileName().toString(), MusicLibrary::compareNatural));
        return ordered;
    }

    /** Writes {@code album.m3u} for {@code ordered}, replacing any partial file. */
    public static void write(MusicFolder folder, List<Track> ordered) throws IOException {
        Path dest = file(folder);
        Path tmp = dest.resolveSibling(FILE_NAME + ".tmp");
        StringBuilder body = new StringBuilder();
        body.append("#EXTM3U\n");
        for (Track track : ordered) {
            String name = track.path().getFileName().toString();
            long seconds = track.duration() > 0 ? Math.round(track.duration()) : -1;
            body.append("#EXTINF:").append(seconds).append(',').append(label(track)).append('\n');
            // A name that starts with '#' would be read back as a comment.
            body.append(name.startsWith("#") ? "./" + name : name).append('\n');
        }
        Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reorders {@code folder}'s tracks to match {@code album.m3u}. Files the
     * playlist does not mention are kept, after the ones it lists.
     */
    public static void apply(MusicFolder folder) {
        Path source = file(folder);
        if (!Files.isRegularFile(source)) return;
        List<String> names;
        try {
            names = readNames(source);
        } catch (IOException e) {
            return;
        }
        Map<String, Track> byExact = new HashMap<>();
        Map<String, Track> byLower = new HashMap<>();
        for (Track track : folder.tracks()) {
            String name = track.path().getFileName().toString();
            byExact.putIfAbsent(name, track);
            byLower.putIfAbsent(name.toLowerCase(Locale.ROOT), track);
        }
        List<Track> next = new ArrayList<>(folder.tracks().size());
        Set<Track> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : names) {
            Track track = byExact.get(name);
            if (track == null) track = byLower.get(name.toLowerCase(Locale.ROOT));
            if (track != null && used.add(track)) next.add(track);
        }
        for (Track track : folder.tracks()) {
            if (used.add(track)) next.add(track);
        }
        folder.tracks().clear();
        folder.tracks().addAll(next);
    }

    /** Applies an existing playlist in this folder and every folder under it. */
    public static void applyDeep(MusicFolder folder) {
        apply(folder);
        for (MusicFolder child : folder.children()) applyDeep(child);
    }

    static List<String> readNames(Path file) throws IOException {
        List<String> names = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') continue;
            int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static int rank(Track track) {
        int number = track.trackNumber();
        return number > 0 ? number : Integer.MAX_VALUE;
    }

    private static String label(Track track) {
        String title = oneLine(track.displayName());
        String artist = oneLine(track.artist());
        if (artist.isBlank()) return title;
        return artist + " - " + title;
    }

    private static String oneLine(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
