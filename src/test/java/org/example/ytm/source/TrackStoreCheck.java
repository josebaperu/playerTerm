package org.example.ytm.source;

import com.sun.net.httpserver.HttpServer;
import org.example.ytm.model.Track;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Checks that a saved track keeps the playlist directory and the track title,
 * carries its tags, and is what the next play would open. Run with the
 * project classes on the classpath.
 */
public final class TrackStoreCheck {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("track-store-");
        try {
            names();
            publishAndFind(root);
            leaveExistingFile(root);
            separateSameTitle(root);
            discardOnlyOurs(root);
            staysInsideRoot(root);
            downloadFromUrl(root);
        } finally {
            deleteTree(root);
        }
        System.out.println("ok");
    }

    private static void names() {
        Track track = track("abc", "Blue", "Ada", "Night Drive");
        check("Night Drive".equals(TrackStore.directoryName(track)), TrackStore.directoryName(track));
        check("Blue".equals(TrackStore.stem(track)), TrackStore.stem(track));

        Track slash = track("abc", "A/B", "x", "AC/DC: Live");
        check("AC DC Live".equals(TrackStore.directoryName(slash)), TrackStore.directoryName(slash));
        check("A B".equals(TrackStore.stem(slash)), TrackStore.stem(slash));

        Track unicode = track("abc", "Ahí Vamos", "", "Gustavo Cerati - Ahí Vamos");
        check("Ahí Vamos".equals(TrackStore.stem(unicode)), TrackStore.stem(unicode));
        check("Gustavo Cerati - Ahí Vamos".equals(TrackStore.directoryName(unicode)),
                TrackStore.directoryName(unicode));

        check("YouTube Music".equals(TrackStore.directoryName(track("abc", "Song", "", ".."))),
                TrackStore.directoryName(track("abc", "Song", "", "..")));
        check("YouTube Music".equals(TrackStore.directoryName(track("abc", "Song", "", ""))),
                "blank album");
    }

    private static void publishAndFind(Path root) throws Exception {
        TrackStore store = new TrackStore(root);
        Track track = track("abc_123", "Blue", "Ada", "Night Drive");
        Path source = tone(root.resolve("src-blue.webm"));
        Path saved = store.publish(source, track, 1, 4);
        Path expected = root.resolve("Night Drive").resolve("Blue.opus");
        check(expected.equals(saved), saved.toString());
        check(saved.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()), "escapes root");
        check(saved.equals(store.existing(track)), "existing");
        check(saved.equals(store.publish(source, track, 1, 4)), "published twice");
        check("Blue".equals(tag(saved, "title")), tag(saved, "title"));
        check("Ada".equals(tag(saved, "artist")), tag(saved, "artist"));
        check("Night Drive".equals(tag(saved, "album")), tag(saved, "album"));
        check(tag(saved, "track").startsWith("1"), tag(saved, "track"));
        check("abc_123".equals(tag(saved, "youtube_id")), tag(saved, "youtube_id"));
        store.close();
    }

    private static void leaveExistingFile(Path root) throws Exception {
        TrackStore store = new TrackStore(root);
        Track track = track("zzz999", "Blue", "Ada", "Already");
        Path plain = store.plainFile(track);
        Files.createDirectories(plain.getParent());
        toneTo(plain, "Keep me");
        long before = Files.size(plain);
        check(plain.equals(store.existing(track)), "plays the file that was already there");
        check(plain.equals(store.publish(tone(root.resolve("src-keep.webm")), track, 1, 1)), "did not replace it");
        check(before == Files.size(plain), "file was rewritten");
        check("Keep me".equals(tag(plain, "title")), tag(plain, "title"));
        check(tag(plain, "youtube_id").isEmpty(), "tagged a file that was already there");
        store.discard(track);
        check(Files.isRegularFile(plain), "deleted a file that was already there");
        store.close();
    }

    private static void separateSameTitle(Path root) throws Exception {
        TrackStore store = new TrackStore(root);
        Path source = tone(root.resolve("src-same.webm"));
        Track first = track("aaa", "Same", "Ada", "Mix");
        Track second = track("bbb", "Same", "Bea", "Mix");
        Path a = store.publish(source, first, 1, 2);
        Path b = store.publish(source, second, 2, 2);
        check(a.getFileName().toString().equals("Same.opus"), a.getFileName().toString());
        check(b.getFileName().toString().equals("Same [bbb].opus"), b.getFileName().toString());
        check(a.equals(store.existing(first)), "first");
        check(b.equals(store.existing(second)), "second");
        check("aaa".equals(tag(a, "youtube_id")), tag(a, "youtube_id"));
        check("bbb".equals(tag(b, "youtube_id")), tag(b, "youtube_id"));
        store.close();
    }

    private static void discardOnlyOurs(Path root) throws Exception {
        TrackStore store = new TrackStore(root);
        Track track = track("gone1", "Gone", "Ada", "Discard");
        Path saved = store.publish(tone(root.resolve("src-gone.webm")), track, 3, 5);
        check(Files.isRegularFile(saved), "saved");
        store.discard(track);
        check(!Files.exists(saved), "our file stayed after a failed play");
        check(store.existing(track) == null, "still found");
        store.close();
    }

    private static void staysInsideRoot(Path root) throws Exception {
        TrackStore store = new TrackStore(root);
        Track track = track("abc", "Song", "Ada", "foo/../../bar");
        Path saved = store.publish(tone(root.resolve("src-escape.webm")), track, 1, 1);
        Path normal = saved.toAbsolutePath().normalize();
        check(normal.startsWith(root.toAbsolutePath().normalize()), normal.toString());
        check(normal.getParent().getFileName().toString().equals(TrackStore.directoryName(track)), "dir");
        store.close();
    }

    /** yt-dlp saves a direct audio URL, which is the same path a stream takes. */
    private static void downloadFromUrl(Path root) throws Exception {
        Path song = tone(root.resolve("served.webm"));
        byte[] body = Files.readAllBytes(song);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/song.webm", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "audio/webm");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Track track = track("local1", "Local", "Ada", "Downloaded");
            Track remote = new Track("local1", "http://127.0.0.1:" + port + "/song.webm",
                    "Local", "Ada", "Downloaded", 1);
            Path audio = YtDlp.downloadOpus(remote);
            try {
                check(Files.isRegularFile(audio) && Files.size(audio) >= 256, "download was empty");
                TrackStore store = new TrackStore(root);
                Path saved = store.publish(audio, track, 1, 1);
                check(saved.getFileName().toString().equals("Local.opus"), saved.getFileName().toString());
                check("local1".equals(tag(saved, "youtube_id")), tag(saved, "youtube_id"));
                check(saved.equals(store.existing(track)), "downloaded file is not reused");
                store.close();
            } finally {
                Files.deleteIfExists(audio);
            }
        } finally {
            server.stop(0);
        }
    }

    private static Track track(String id, String title, String artist, String album) {
        return new Track(id, "https://music.youtube.com/watch?v=" + id, title, artist, album, 12);
    }

    private static Path tone(Path dest) throws Exception {
        toneTo(dest, null);
        return dest;
    }

    private static void toneTo(Path dest, String title) throws Exception {
        Files.createDirectories(dest.getParent());
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg());
        cmd.addAll(List.of("-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", "1", "-c:a", "libopus"));
        if (title != null) {
            cmd.add("-metadata");
            cmd.add("title=" + title);
        }
        cmd.add(dest.toString());
        run(cmd);
    }

    private static String tag(Path file, String key) throws Exception {
        Process p = new ProcessBuilder(ffprobe(), "-v", "error",
                "-show_entries", "format_tags:stream_tags",
                "-of", "default=noprint_wrappers=1",
                file.toString()).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.getErrorStream().readAllBytes();
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("ffprobe timed out");
        }
        String want = "tag:" + key;
        for (String line : new String(out, StandardCharsets.UTF_8).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            if (line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT).equals(want)) {
                return line.substring(eq + 1).trim();
            }
        }
        return "";
    }

    private static void run(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IOException(command.get(0) + " failed");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The temp directory is still removed on the next boot.
                }
            });
        }
    }

    private static String ffmpeg() {
        String override = System.getenv("PLAYERTERM_FFMPEG");
        return override != null && !override.isBlank() ? override : "ffmpeg";
    }

    private static String ffprobe() {
        String override = System.getenv("PLAYERTERM_FFPROBE");
        return override != null && !override.isBlank() ? override : "ffprobe";
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
