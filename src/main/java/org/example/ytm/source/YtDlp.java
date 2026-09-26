package org.example.ytm.source;

import org.example.ytm.model.Track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The yt-dlp calls the player needs: list a playlist without touching the
 * videos, and turn one track into a direct audio stream ffmpeg can open.
 * Both block, so they belong on a background thread; interrupting the
 * caller kills the child process.
 */
public final class YtDlp {

    private static final String SEP = "\u001f";
    private static final Pattern EXPIRE = Pattern.compile("[?&/]expire[=/](\\d+)");
    private static final long LIST_TIMEOUT_S = 120;
    private static final long RESOLVE_TIMEOUT_S = 45;

    private YtDlp() {
    }

    public static String binary() {
        String override = System.getenv("PLAYERTERM_YTDLP");
        return override != null && !override.isBlank() ? override : "yt-dlp";
    }

    /** The tracks of a playlist, album or single video, in their listed order. */
    public static List<Track> listPlaylist(String url, String album) throws IOException, InterruptedException {
        String fields = String.join(SEP, "%(id)s", "%(title)s", "%(artists.0,artist,channel,uploader|)s",
                "%(duration|0)s", "%(url|)s");
        String out = run(LIST_TIMEOUT_S, binary(), "--flat-playlist", "--ignore-errors", "--no-warnings",
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
        String out = run(RESOLVE_TIMEOUT_S, binary(), "-f", "bestaudio/best", "--no-playlist",
                "--no-warnings", "--print", "urls", track.url());
        String stream = out.lines().map(String::trim).filter(l -> l.startsWith("http")).findFirst().orElse(null);
        if (stream == null) throw new IOException("yt-dlp found no audio stream");
        Matcher m = EXPIRE.matcher(stream);
        long expires = m.find() ? Long.parseLong(m.group(1)) : System.currentTimeMillis() / 1000 + 3600;
        track.setStream(stream, expires);
        return stream;
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
