package org.example.ytm.source;

import org.example.ytm.model.Track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The yt-dlp calls the player needs: list a playlist without touching the
 * videos, turn one track into a direct audio stream ffmpeg can open, and
 * download that audio into a file.
 * The first call copies Firefox's YouTube cookies into
 * {@code ~/.cache/playerytm/cookies.txt}. Later calls pass only
 * {@code --cookies} and that file, so Firefox can be closed. Both block,
 * so they belong on a background thread; interrupting the caller kills
 * the child process.
 */
public final class YtDlp {

    private static final String SEP = "\u001f";
    private static final Pattern EXPIRE = Pattern.compile("[?&/]expire[=/](\\d+)");
    private static final long LIST_TIMEOUT_S = 120;
    private static final long RESOLVE_TIMEOUT_S = 45;
    private static final long DOWNLOAD_TIMEOUT_S = 900;
    private static final String COOKIE_BROWSER = "firefox";
    private static final Object COOKIE_LOCK = new Object();
    /** True while one call is still copying Firefox cookies into the file. */
    private static boolean exporting;
    /** True once the cookie file holds a YouTube cookie. */
    private static volatile boolean cookiesReady;

    private YtDlp() {
    }

    public static String binary() {
        String override = System.getenv("PLAYERTERM_YTDLP");
        return override != null && !override.isBlank() ? override : "yt-dlp";
    }

    /** Netscape cookie jar written by yt-dlp. Same cache root as playlist listings. */
    private static Path cookieFile() {
        return PlaylistCache.defaultDirectory().getParent().resolve("cookies.txt");
    }

    /**
     * {@code yt-dlp --cookies <file>}, or, until that file exists,
     * {@code yt-dlp --cookies-from-browser firefox --cookies <file>}.
     * Passing both makes yt-dlp load Firefox and write the jar on exit.
     */
    private static String[] command(boolean fromBrowser, String... args) {
        String[] cmd = new String[args.length + (fromBrowser ? 5 : 3)];
        int i = 0;
        cmd[i++] = binary();
        if (fromBrowser) {
            cmd[i++] = "--cookies-from-browser";
            cmd[i++] = COOKIE_BROWSER;
        }
        cmd[i++] = "--cookies";
        cmd[i++] = cookieFile().toString();
        System.arraycopy(args, 0, cmd, i, args.length);
        return cmd;
    }

    /** The tracks of a playlist, album or single video, in their listed order. */
    public static List<Track> listPlaylist(String url, String album) throws IOException, InterruptedException {
        String fields = String.join(SEP, "%(id)s", "%(title)s", "%(artists.0,artist,channel,uploader|)s",
                "%(duration|0)s", "%(url|)s");
        String out = call(LIST_TIMEOUT_S, "--flat-playlist", "--ignore-errors", "--no-warnings",
                "--print", fields, url);
        List<Track> tracks = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] f = line.split(SEP, -1);
            if (f.length < 5 || f[0].isBlank() || f[0].equals("NA")) continue;
            String id = f[0].trim();
            String title = na(f[1]);
            // Deleted and private videos stay in playlists as placeholders.
            if (title.equals("[Deleted video]") || title.equals("[Private video]")) continue;
            String link = na(f[4]);
            if (link.isEmpty() || !link.startsWith("http")) link = "https://music.youtube.com/watch?v=" + id;
            tracks.add(new Track(id, link, title, stripTopic(na(f[2])), album, parseDouble(f[3])));
        }
        return tracks;
    }

    /** Looks up the best audio stream for a track and remembers it on the track. */
    public static String resolve(Track track) throws IOException, InterruptedException {
        String cached = track.streamUrl();
        if (cached != null) return cached;
        String out = call(RESOLVE_TIMEOUT_S, "-f", "bestaudio/best", "--no-playlist",
                "--no-warnings", "--print", "urls", track.url());
        String stream = out.lines().map(String::trim).filter(l -> l.startsWith("http")).findFirst().orElse(null);
        if (stream == null) throw new IOException("yt-dlp found no audio stream");
        Matcher m = EXPIRE.matcher(stream);
        long expires = m.find() ? Long.parseLong(m.group(1)) : System.currentTimeMillis() / 1000 + 3600;
        track.setStream(stream, expires);
        return stream;
    }

    /**
     * Downloads the best audio as an opus file and returns that temp file.
     * The caller deletes it. A source that is already opus is copied; anything
     * else is converted. Belongs on a background thread.
     */
    public static Path downloadOpus(Track track) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("playerytm-");
        try {
            List<String> args = new ArrayList<>();
            args.addAll(List.of("-f", "bestaudio[acodec^=opus]/bestaudio/best",
                    "--no-playlist", "--no-warnings", "--no-progress", "--no-simulate",
                    "-x", "--audio-format", "opus", "--audio-quality", "0"));
            String ffmpeg = System.getenv("PLAYERTERM_FFMPEG");
            if (ffmpeg != null && !ffmpeg.isBlank()) {
                args.add("--ffmpeg-location");
                args.add(ffmpeg);
            }
            args.add("-o");
            args.add(dir.resolve("audio.%(ext)s").toString());
            args.add("--print");
            args.add("after_move:filepath");
            args.add(track.url());
            String out = call(DOWNLOAD_TIMEOUT_S, args.toArray(String[]::new));
            Path saved = savedAudio(dir, out);
            if (saved == null) throw new IOException("yt-dlp saved no audio");
            Path kept = Files.createTempFile("playerytm-", extension(saved));
            Files.deleteIfExists(kept);
            Files.move(saved, kept);
            return kept;
        } finally {
            deleteTree(dir);
        }
    }

    /** The file yt-dlp printed, or the largest file left in the temp directory. */
    private static Path savedAudio(Path dir, String printed) throws IOException {
        Path best = null;
        long size = -1;
        for (String line : printed.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Path path;
            try {
                path = Path.of(trimmed);
            } catch (java.nio.file.InvalidPathException e) {
                continue;
            }
            if (!Files.isRegularFile(path) || !path.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize())) {
                continue;
            }
            long n = Files.size(path);
            if (n > size) {
                best = path;
                size = n;
            }
        }
        if (best != null) return best;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry) || entry.getFileName().toString().endsWith(".part")) continue;
                long n = Files.size(entry);
                if (n > size) {
                    best = entry;
                    size = n;
                }
            }
        }
        return best;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : ".opus";
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp file is outside the music folder.
                }
            });
        } catch (IOException ignored) {
            // The download itself already failed or finished.
        }
    }

    /**
     * Runs yt-dlp. A missing cookie file is filled from Firefox by this call;
     * other calls wait for that write, then use the file alone.
     */
    private static String call(long timeoutSeconds, String... args) throws IOException, InterruptedException {
        try {
            return invoke(timeoutSeconds, args);
        } catch (IOException e) {
            if (!staleCookies(e.getMessage())) throw e;
            synchronized (COOKIE_LOCK) {
                cookiesReady = false;
                Files.deleteIfExists(cookieFile());
            }
            return invoke(timeoutSeconds, args);
        }
    }

    private static String invoke(long timeoutSeconds, String... args) throws IOException, InterruptedException {
        boolean fromBrowser = false;
        synchronized (COOKIE_LOCK) {
            while (!cookiesReady && exporting) COOKIE_LOCK.wait();
            if (!cookiesReady && hasYoutubeCookies(cookieFile())) {
                cookiesReady = true;
                restrict(cookieFile());
            }
            fromBrowser = !cookiesReady;
            if (fromBrowser) exporting = true;
        }
        if (fromBrowser) {
            try {
                Files.createDirectories(cookieFile().getParent());
            } catch (IOException e) {
                finishExport();
                throw e;
            }
        }
        try {
            return run(timeoutSeconds, command(fromBrowser, args));
        } finally {
            if (fromBrowser) finishExport();
        }
    }

    private static void finishExport() {
        synchronized (COOKIE_LOCK) {
            exporting = false;
            Path file = cookieFile();
            if (hasYoutubeCookies(file)) {
                cookiesReady = true;
                restrict(file);
            }
            COOKIE_LOCK.notifyAll();
        }
    }

    /** A jar yt-dlp can reuse: a Netscape row whose domain is YouTube. */
    private static boolean hasYoutubeCookies(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try {
            if (Files.size(file) < 32) return false;
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                return lines.anyMatch(YtDlp::youtubeCookieRow);
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean youtubeCookieRow(String line) {
        if (line.startsWith("#HttpOnly_")) line = line.substring("#HttpOnly_".length());
        else if (line.isEmpty() || line.charAt(0) == '#') return false;
        int tab = line.indexOf('\t');
        if (tab <= 0) return false;
        String domain = line.substring(0, tab);
        return domain.equals("youtube.com") || domain.endsWith(".youtube.com");
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // The jar is still usable when the filesystem has no POSIX modes.
        }
    }

    private static boolean staleCookies(String message) {
        if (message == null) return false;
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("no longer valid")
                || text.contains("sign in to confirm")
                || text.contains("not a bot")
                || text.contains("login required");
    }

    /** Runs a command and returns its output; the last error line becomes the exception. */
    private static String run(long timeoutSeconds, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).start();
        p.getOutputStream().close();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outPump = pump(p.getInputStream(), stdout);
        Thread errPump = pump(p.getErrorStream(), stderr);
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("yt-dlp timed out");
            }
            outPump.join(2000);
            errPump.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
        String out = stdout.toString(StandardCharsets.UTF_8);
        if (p.exitValue() != 0 && out.isBlank()) {
            throw new IOException(lastError(stderr.toString(StandardCharsets.UTF_8), p.exitValue()));
        }
        return out;
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
        }, "yt-dlp-pipe");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String lastError(String stderr, int exit) {
        String last = "";
        for (String line : stderr.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("ERROR:")) last = trimmed.substring(6).trim();
            else if (last.isEmpty() && !trimmed.isEmpty()) last = trimmed;
        }
        last = last.replaceFirst("^\\[[^]]*]\\s*", "");
        return last.isEmpty() ? "yt-dlp exited with " + exit : last;
    }

    private static String na(String value) {
        String v = value == null ? "" : value.trim();
        return v.equals("NA") ? "" : v;
    }

    /** Auto-generated artist channels are called "Name - Topic". */
    private static String stripTopic(String artist) {
        return artist.endsWith(" - Topic") ? artist.substring(0, artist.length() - 8) : artist;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
