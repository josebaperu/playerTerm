package org.example.player.model;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The music directory as a tree of folders. Scanning only touches the file
 * system, never the files themselves, so even a large library opens instantly;
 * tags are filled in later by {@link org.example.player.audio.TagReader}.
 */
public final class MusicLibrary {

    /** Formats the player accepts. ffmpeg decodes all of them. */
    public static final Set<String> EXTENSIONS = Set.of("mp3", "flac", "wav", "ogg", "oga", "opus");
    private static final int MAX_DEPTH = 12;
    private static final Comparator<Path> BY_NAME =
            Comparator.comparing(p -> p.getFileName().toString(), MusicLibrary::compareNatural);

    private final Path root;
    private final MusicFolder tree;
    private int trackCount;
    private int folderCount;

    public MusicLibrary(Path root) {
        this.root = root;
        this.tree = new MusicFolder(root, root.getFileName() == null ? root.toString()
                : root.getFileName().toString(), null);
        scan(tree, 0, new HashSet<>());
        prune(tree);
    }

    public Path root() {
        return root;
    }

    public MusicFolder tree() {
        return tree;
    }

    public int trackCount() {
        return trackCount;
    }

    public int folderCount() {
        return folderCount;
    }

    public boolean isEmpty() {
        return !tree.hasContent();
    }

    private void scan(MusicFolder folder, int depth, Set<Path> seen) {
        if (depth > MAX_DEPTH) return;
        Path real;
        try {
            real = folder.path().toRealPath();
        } catch (IOException e) {
            return;
        }
        if (!seen.add(real)) return;   // symlink loop

        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder.path())) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (Files.isDirectory(entry)) {
                    dirs.add(entry);
                } else if (isAudio(name)) {
                    files.add(entry);
                }
            }
        } catch (IOException e) {
            return;
        }

        dirs.sort(BY_NAME);
        for (Path dir : dirs) {
            MusicFolder child = new MusicFolder(dir, dir.getFileName().toString(), folder);
            scan(child, depth + 1, seen);
            folder.children().add(child);
        }

        files.sort(BY_NAME);
        for (Path file : files) folder.tracks().add(new Track(file));
    }

    /** Drops branches that contain no audio at all, such as a folder of artwork. */
    private boolean prune(MusicFolder folder) {
        folder.children().removeIf(child -> !prune(child));
        boolean keep = folder.hasContent();
        if (keep) {
            folderCount++;
            trackCount += folder.tracks().size();
        }
        return keep;
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

    public static boolean isAudio(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        return EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The visible lines, following what is currently expanded. */
    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        appendChildren(tree, 0, out);
        return out;
    }

    private void appendChildren(MusicFolder folder, int depth, List<Row> out) {
        for (MusicFolder child : folder.children()) {
            out.add(Row.folder(child, depth));
            if (child.isExpanded()) appendChildren(child, depth + 1, out);
        }
        for (Track track : folder.tracks()) {
            out.add(Row.track(track, folder, depth));
        }
    }

    /** Expands every folder on the path to this one so it can be shown. */
    public void revealFolder(MusicFolder folder) {
        for (MusicFolder f = folder.parent(); f != null; f = f.parent()) f.setExpanded(true);
    }

    public void collapseAll() {
        forEachFolder(tree, f -> f.setExpanded(false));
    }

    public void expandAll() {
        forEachFolder(tree, f -> f.setExpanded(true));
    }

    private void forEachFolder(MusicFolder folder, java.util.function.Consumer<MusicFolder> action) {
        for (MusicFolder child : folder.children()) {
            action.accept(child);
            forEachFolder(child, action);
        }
    }

    /**
     * Resolves the music directory: an explicit path, then PLAYERTERM_MUSIC,
     * then the XDG music dir, then ~/Music.
     */
    public static Path resolveRoot(String explicit) {
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit).toAbsolutePath();

        String env = System.getenv("PLAYERTERM_MUSIC");
        if (env != null && !env.isBlank()) return Path.of(env).toAbsolutePath();

        String xdg = System.getenv("XDG_MUSIC_DIR");
        if (xdg != null && !xdg.isBlank()) return Path.of(expandHome(xdg)).toAbsolutePath();

        Path configured = fromUserDirs();
        if (configured != null) return configured;

        return Path.of(System.getProperty("user.home"), "Music");
    }

    /** Reads XDG_MUSIC_DIR out of ~/.config/user-dirs.dirs. */
    private static Path fromUserDirs() {
        Path file = Path.of(System.getProperty("user.home"), ".config", "user-dirs.dirs");
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("XDG_MUSIC_DIR")) continue;
                int quote = trimmed.indexOf('"');
                int end = trimmed.lastIndexOf('"');
                if (quote < 0 || end <= quote) continue;
                return Path.of(expandHome(trimmed.substring(quote + 1, end))).toAbsolutePath();
            }
        } catch (IOException ignored) {
            // No XDG config; fall back to ~/Music.
        }
        return null;
    }

    private static String expandHome(String value) {
        String home = System.getProperty("user.home");
        if (value.startsWith("$HOME")) return home + value.substring(5);
        if (value.startsWith("~")) return home + value.substring(1);
        return value;
    }
}
