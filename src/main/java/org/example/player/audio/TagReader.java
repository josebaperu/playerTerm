package org.example.player.audio;

import org.example.player.model.MusicFolder;
import org.example.player.model.Track;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fills in titles, artists and durations with ffprobe, off the render thread.
 * A folder is read once, the first time it is opened or played, so browsing a
 * large library never blocks on metadata.
 */
public final class TagReader implements AutoCloseable {

    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tag-reader");
        t.setDaemon(true);
        return t;
    });

    /** Queues a folder's tracks, unless they were requested already. */
    public void requestFolder(MusicFolder folder) {
        if (folder == null || folder.tagsRequested() || folder.tracks().isEmpty()) return;
        folder.markTagsRequested();
        workers.submit(() -> {
            for (Track track : folder.tracks()) {
                if (Thread.currentThread().isInterrupted()) return;
                read(track);
            }
        });
    }

    /** Reads one track now; used for whatever is about to play. */
    public static void read(Track track) {
        if (track.isTagged()) return;
        String title = null;
        String artist = null;
        String album = null;
        int number = 0;
        double duration = 0;

        ProcessBuilder pb = new ProcessBuilder(binary(),
                "-v", "quiet",
                // Vorbis keeps its comments on the stream, not the format, so ask
                // for both and take whichever arrives first.
                "-show_entries", "format=duration:format_tags:stream_tags",
                "-of", "default=noprint_wrappers=1",
                track.path().toString());
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String key = line.substring(0, eq).toLowerCase(Locale.ROOT);
                    String value = line.substring(eq + 1).trim();
                    if (value.isEmpty() || value.equals("N/A")) continue;
                    switch (key) {
                        case "duration" -> { if (duration <= 0) duration = parseDouble(value); }
                        case "tag:title" -> { if (title == null) title = value; }
                        case "tag:artist" -> { if (artist == null) artist = value; }
                        case "tag:album" -> { if (album == null) album = value; }
                        case "tag:track", "tag:tracknumber" -> { if (number == 0) number = parseTrackNumber(value); }
                        default -> { }
                    }
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException e) {
            // ffprobe missing or unreadable file: keep the file name.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        track.applyTags(title, artist, album, number, duration);
    }

    private static String binary() {
        String override = System.getenv("PLAYERTERM_FFPROBE");
        return override != null && !override.isBlank() ? override : "ffprobe";
    }

    /** Track tags look like "7" or "7/16". */
    private static int parseTrackNumber(String value) {
        String head = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;
        try {
            return Integer.parseInt(head.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
