package org.example.ytm.audio;

import org.example.player.audio.FormatInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ffmpeg child process decoding one stream to raw signed 16 bit little
 * endian PCM. Seeking is input side ({@code -ss} before {@code -i}), so a jump
 * costs one process restart and a ranged request rather than downloading
 * everything skipped.
 */
public final class TrackDecoder implements AutoCloseable {

    private static final Pattern DURATION =
            Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Pattern AUDIO_LINE =
            Pattern.compile("Audio:\\s*([A-Za-z0-9_]+).*?(\\d+)\\s*Hz(?:.*?(\\d+)\\s*kb/s)?");
    private static final String OUTPUT_CODEC = "pcm_s16le";

    private final Process process;
    private final InputStream pcm;
    private final Thread stderrPump;
    private final FormatInfo info;

    public TrackDecoder(String input, double startSeconds, int sampleRate, FormatInfo info) throws IOException {
        this.info = info;
        List<String> cmd = new java.util.ArrayList<>(List.of(binary(), "-hide_banner", "-nostdin", "-nostats",
                "-loglevel", "info"));
        if (input.startsWith("http")) {
            // Ride out dropped connections instead of ending the track early.
            cmd.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"));
        }
        if (startSeconds > 0.05) {
            cmd.add("-ss");
            cmd.add(String.format(java.util.Locale.ROOT, "%.3f", startSeconds));
        }
        cmd.addAll(List.of(
                "-i", input,
                "-vn",
                "-f", "s16le",
                "-acodec", "pcm_s16le",
                "-ac", "2",
                "-ar", Integer.toString(sampleRate),
                "-"));

        process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        pcm = process.getInputStream();
        stderrPump = new Thread(this::pumpStderr, "ffmpeg-stderr");
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    private static String binary() {
        String override = System.getenv("PLAYERTERM_FFMPEG");
        return override != null && !override.isBlank() ? override : "ffmpeg";
    }

    public int read(byte[] buffer, int offset, int length) throws IOException {
        return pcm.read(buffer, offset, length);
    }

    private void pumpStderr() {
        StringBuilder line = new StringBuilder();
        try (InputStream err = process.getErrorStream()) {
            int c;
            while ((c = err.read()) != -1) {
                if (c == '\n' || c == '\r') {
                    if (!line.isEmpty()) handleLine(line.toString());
                    line.setLength(0);
                } else {
                    line.append((char) c);
                }
            }
        } catch (IOException ignored) {
            // Process ended.
        }
    }

    private void handleLine(String raw) {
        Matcher duration = DURATION.matcher(raw);
        if (duration.find()) {
            info.duration = Integer.parseInt(duration.group(1)) * 3600
                    + Integer.parseInt(duration.group(2)) * 60
                    + Double.parseDouble(duration.group(3));
            return;
        }
        Matcher audio = AUDIO_LINE.matcher(raw);
        if (audio.find() && info.codec.isEmpty() && !audio.group(1).equalsIgnoreCase(OUTPUT_CODEC)) {
            info.codec = audio.group(1).toUpperCase();
            info.sampleRate = audio.group(2);
            if (audio.group(3) != null) info.bitrate = audio.group(3) + " kbps";
            return;
        }
        String lower = raw.toLowerCase();
        if ((lower.contains("error") || lower.contains("invalid") || lower.contains("no such file"))
                && !lower.contains("broken pipe") && !lower.contains("muxing")) {
            info.error = raw.replaceAll("^\\[[^]]*]\\s*", "").trim();
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            pcm.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        stderrPump.interrupt();
    }
}
