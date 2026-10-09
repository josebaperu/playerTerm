package org.example.ytm.source;

import org.example.player.model.MusicLibrary;
import org.example.ytm.model.Track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Saves a streamed track into the music folder and finds it again later.
 * The playlist name is the directory and the track title is the file, the
 * same names the library shows. Title, artist, album and track number are
 * written into the file; a {@code youtube_id} tag ties that file back to the
 * video. A second track that would reuse a name already taken by a different
 * video is saved beside it, with the video id in the file name.
 */
public final class TrackStore implements AutoCloseable {

    static final String ID_TAG = "youtube_id";
    private static final String EXTENSION = ".opus";
    private static final int NAME_BYTES = 160;
    private static final long MIN_BYTES = 256;
    private static final long FFMPEG_TIMEOUT_S = 180;
    private static final long PROBE_TIMEOUT_S = 10;

    private final Path root;
    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "track-store");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** Finished files already matched to a track during this run. */
    private final Map<String, Path> found = new ConcurrentHashMap<>();
    /** Tracks whose save is queued or running, so a prefetch does not start a second one. */
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();
    private volatile String problem = "";

    public TrackStore(Path root) {
        this.root = root;
    }

    /** The music folder playerTerm uses: {@code $PLAYERTERM_MUSIC}, then XDG, then {@code ~/Music}. */
    public static Path defaultRoot() {
        return MusicLibrary.resolveRoot(null);
    }

    public Path root() {
        return root;
    }

    /** Why the last save failed, or empty after one finishes. */
    public String problem() {
        return problem;
    }

    /**
     * A finished copy of this track, or null when it still has to be streamed.
     * The plain name counts when its {@code youtube_id} tag is this video or the
     * file has no video id, so a file already in the folder plays as it is. A
     * tag for a different video is not played by mistake.
     */
    public Path existing(Track track) {
        String key = key(track);
        Path known = found.get(key);
        if (known != null && isComplete(known)) return known;
        if (known != null) found.remove(key, known);

        Path marked = markedFile(track);
        if (isComplete(marked)) {
            found.put(key, marked);
            return marked;
        }
        Path plain = plainFile(track);
        if (isComplete(plain)) {
            // A file already in this place, with no video id of its own, is this track.
            String id = readId(plain);
            if (id.isEmpty() || track.id().equals(id)) {
                found.put(key, plain);
                return plain;
            }
        }
        return null;
    }

    /**
     * The file used when this track is the first one with its title:
     * {@code <music>/<playlist>/<title>.opus}.
     */
    public Path plainFile(Track track) {
        return root.resolve(directoryName(track)).resolve(stem(track) + EXTENSION);
    }

    /**
     * Downloads the track in the background when no finished copy exists.
     * {@code number} and {@code total} are the playlist position, written as
     * the track tag; pass a number below 1 to leave it out.
     */
    public void request(Track track, int number, int total) {
        if (track == null || track.id().isBlank() || track.url().isBlank()) return;
        if (existing(track) != null) return;
        String key = key(track);
        if (pending.putIfAbsent(key, Boolean.TRUE) != null) return;
        try {
            workers.submit(() -> save(track, number, total, key));
        } catch (RejectedExecutionException e) {
            pending.remove(key);
        }
    }

    /**
     * Deletes a copy this player wrote when playback could not read it, so the
     * next attempt streams the track and saves a fresh one. A file that was
     * already in the folder, with no video id, is left where it is.
     */
    public void discard(Track track) {
        if (track == null || track.id().isBlank()) return;
        synchronized (lock(track)) {
            String key = key(track);
            Path file = found.remove(key);
            if (file == null) {
                Path marked = markedFile(track);
                if (isComplete(marked)) file = marked;
                else {
                    Path plain = plainFile(track);
                    if (isComplete(plain) && track.id().equals(readId(plain))) file = plain;
                }
            }
            if (file == null) return;
            Path normal = file.toAbsolutePath().normalize();
            Path base = root.toAbsolutePath().normalize();
            if (normal.equals(base) || !normal.startsWith(base)) return;
            if (!track.id().equals(readId(normal))) return;
            try {
                Files.deleteIfExists(normal);
            } catch (IOException ignored) {
                // Playback already fell back to the stream.
            }
        }
    }

    private void save(Track track, int number, int total, String key) {
        Path audio = null;
        try {
            if (existing(track) != null) return;
            audio = YtDlp.downloadOpus(track);
            synchronized (lock(track)) {
                Path dest = claim(track);
                if (dest == null) return;
                writeTagged(audio, dest, track, number, total);
                found.put(key, dest);
                problem = "";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // The stream still plays. The next play tries the save again.
            problem = "Couldn't save " + track.displayName() + ": " + brief(e);
        } finally {
            if (audio != null) {
                try {
                    Files.deleteIfExists(audio);
                } catch (IOException ignored) {
                    // The temp file is outside the music folder.
                }
            }
            pending.remove(key);
        }
    }

    /** Where a new copy goes, or null when a finished one is already in place. */
    private Path claim(Track track) {
        if (isComplete(markedFile(track))) return null;
        Path plain = plainFile(track);
        if (isComplete(plain)) {
            String id = readId(plain);
            // Missing id means the file was already there. Do not replace it.
            if (id.isEmpty() || track.id().equals(id)) return null;
            return markedFile(track);
        }
        return plain;
    }

    /**
     * Tags {@code source} and publishes it under this track's name. Returns
     * the file now on disk, including one that was already there. Used by the
     * background save and by tests that already have an audio file.
     */
    Path publish(Path source, Track track, int number, int total) throws IOException, InterruptedException {
        synchronized (lock(track)) {
            Path dest = claim(track);
            if (dest == null) return existing(track);
            writeTagged(source, dest, track, number, total);
            found.put(key(track), dest);
            return dest;
        }
    }

    private void writeTagged(Path source, Path destination, Track track, int number, int total)
            throws IOException, InterruptedException {
        Files.createDirectories(destination.getParent());
        Path part = destination.resolveSibling(destination.getFileName().toString() + ".part");
        Files.deleteIfExists(part);
        try {
            boolean wrote = remux(source, part, track, number, total, true) && tagged(part, track.id());
            if (!wrote) wrote = remux(source, part, track, number, total, false) && tagged(part, track.id());
            if (!wrote) throw new IOException("could not write " + destination.getFileName());
            // An unfinished earlier attempt must not block the rename.
            if (Files.exists(destination) && !isComplete(destination)) Files.deleteIfExists(destination);
            move(part, destination);
        } catch (IOException | InterruptedException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // The part is not a finished track, so playback will not use it.
            }
            if (e instanceof InterruptedException interrupted) throw interrupted;
            throw (IOException) e;
        }
    }

    /** Copies the audio into an opus file, or transcodes when the stream is not already opus. */
    private static boolean remux(Path source, Path part, Track track, int number, int total, boolean copy)
            throws IOException, InterruptedException {
        Files.deleteIfExists(part);
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg());
        cmd.addAll(List.of("-y", "-hide_banner", "-nostdin", "-loglevel", "error",
                "-i", source.toString(), "-map", "0:a:0", "-vn"));
        if (copy) cmd.addAll(List.of("-c", "copy"));
        else cmd.addAll(List.of("-c:a", "libopus", "-b:a", "160k"));
        cmd.add("-map_metadata");
        cmd.add("-1");
        meta(cmd, "title", track.title().isEmpty() ? track.id() : track.title());
        meta(cmd, "artist", track.artist());
        meta(cmd, "album", track.album());
        if (number > 0) {
            String value = total >= number ? number + "/" + total : Integer.toString(number);
            meta(cmd, "track", value);
            meta(cmd, "tracknumber", value);
        }
        meta(cmd, ID_TAG, track.id());
        cmd.add("-f");
        cmd.add("ogg");
        cmd.add(part.toString());
        try {
            run(cmd, FFMPEG_TIMEOUT_S);
            return true;
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // The retry writes the same part again.
            }
            return false;
        }
    }

    private static void meta(List<String> cmd, String key, String value) {
        if (value == null) return;
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.isEmpty()) return;
        cmd.add("-metadata");
        cmd.add(key + "=" + cleaned);
    }

    /** The part file plays and carries this video's id, so the rename will not publish a dud. */
    private static boolean tagged(Path file, String id) throws IOException, InterruptedException {
        if (!isComplete(file)) return false;
        Probe probe = probe(file);
        return probe.duration > 0 && id.equals(probe.youtubeId);
    }

    private static void move(Path part, Path destination) throws IOException {
        try {
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readId(Path file) {
        try {
            return probe(file).youtubeId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isComplete(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) >= MIN_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private Object lock(Track track) {
        return locks.computeIfAbsent(directoryName(track) + "\0" + stem(track), k -> new Object());
    }

    private static String key(Track track) {
        return directoryName(track) + "\0" + track.id();
    }

    private Path markedFile(Track track) {
        return root.resolve(directoryName(track)).resolve(stem(track) + " [" + safeId(track.id()) + "]" + EXTENSION);
    }

    /** Playlist title, with characters a directory name cannot keep turned into spaces. */
    static String directoryName(Track track) {
        String name = limit(sanitize(track.album()), NAME_BYTES);
        return name.isEmpty() ? "YouTube Music" : name;
    }

    /** The track title, which is the name shown under the playlist. */
    static String stem(Track track) {
        String name = limit(sanitize(track.title()), NAME_BYTES);
        if (name.isEmpty()) name = limit(sanitize(track.id()), NAME_BYTES);
        return name.isEmpty() ? "track" : name;
    }

    /** A single path segment: no separators, no hidden-file dot, no trailing dot. */
    static String sanitize(String raw) {
        String normalized = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 32 || cp == 127 || "/\\:*?\"<>|".indexOf(cp) >= 0) out.append(' ');
            else out.appendCodePoint(cp);
        }
        String collapsed = out.toString().replaceAll("\\s+", " ").trim();
        int start = 0;
        while (start < collapsed.length() && collapsed.charAt(start) == '.') start++;
        int end = collapsed.length();
        while (end > start && (collapsed.charAt(end - 1) == '.' || collapsed.charAt(end - 1) == ' ')) end--;
        String name = collapsed.substring(start, end).trim();
        if (name.isEmpty() || name.equals("..")) return "";
        return name;
    }

    private static String safeId(String id) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length() && sb.length() < 32; i++) {
            char c = id.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "track" : sb.toString();
    }

    /** Cuts on a UTF-8 boundary so a long title still fits in one file name. */
    private static String limit(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int end = maxBytes;
        // Landed inside a character: step back to its leading byte and drop it.
        if ((bytes[end] & 0xC0) == 0x80) {
            while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        }
        String cut = new String(bytes, 0, end, StandardCharsets.UTF_8);
        if (cut.endsWith("\uFFFD")) cut = cut.substring(0, cut.length() - 1);
        cut = cut.replaceAll("[. ]+$", "");
        return cut.isEmpty() ? "unknown" : cut;
    }

    private static Probe probe(Path file) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(ffprobe(), "-v", "quiet",
                "-show_entries", "format=duration:format_tags:stream_tags",
                "-of", "default=noprint_wrappers=1",
                file.toString()).start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Thread pump = pump(p.getInputStream(), stdout);
        p.getErrorStream().close();
        try {
            if (!p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffprobe timed out");
            }
            pump.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
        double duration = 0;
        String id = "";
        for (String line : stdout.toString(StandardCharsets.UTF_8).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            if (value.isEmpty() || value.equals("N/A")) continue;
            if (key.equals("duration") && duration <= 0) duration = parseDouble(value);
            else if ((key.equals("tag:" + ID_TAG) || key.equals(ID_TAG)) && id.isEmpty()) id = value;
        }
        return new Probe(duration, id);
    }

    private static void run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread pump = pump(p.getErrorStream(), stderr);
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(command.get(0) + " timed out");
            }
            pump.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
        if (p.exitValue() != 0) throw new IOException(lastLine(stderr.toString(StandardCharsets.UTF_8), p.exitValue()));
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream into) {
        Thread t = new Thread(() -> {
            try (in) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (into) {
                        into.write(buf, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // Process went away.
            }
        }, "track-store-pipe");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String lastLine(String stderr, int exit) {
        String last = "";
        for (String line : stderr.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) last = trimmed;
        }
        return last.isEmpty() ? "ffmpeg exited with " + exit : last;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) message = e.getClass().getSimpleName();
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 140 ? message.substring(0, 140) : message;
    }

    private static String ffmpeg() {
        String override = System.getenv("PLAYERTERM_FFMPEG");
        return override != null && !override.isBlank() ? override : "ffmpeg";
    }

    private static String ffprobe() {
        String override = System.getenv("PLAYERTERM_FFPROBE");
        return override != null && !override.isBlank() ? override : "ffprobe";
    }

    private record Probe(double duration, String youtubeId) {
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
