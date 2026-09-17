package org.example.player.audio;

import org.example.player.model.Track;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.List;

/**
 * Plays a queue of files: ffmpeg decodes, the equalizer shapes, the sound card
 * receives. One session thread owns the queue and handles pause, seek, track
 * changes and running on to the next track.
 */
public final class Player implements AutoCloseable {

    public static final int SAMPLE_RATE = 44100;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
    private static final int FRAMES_PER_BLOCK = 1024;
    private static final int BLOCK_BYTES = FRAMES_PER_BLOCK * 4;
    private static final int LINE_BUFFER_BYTES = SAMPLE_RATE * 4 / 8;   // ~125 ms

    private final Equalizer equalizer = new Equalizer(SAMPLE_RATE);
    private final SpectrumAnalyzer spectrum = new SpectrumAnalyzer(SAMPLE_RATE);
    private final FormatInfo info = new FormatInfo();
    private final Object pauseLock = new Object();

    private volatile Session session;
    private volatile List<Track> queue = List.of();
    private volatile int index = -1;
    private volatile Track current;
    private volatile PlaybackState state = PlaybackState.IDLE;
    private volatile boolean paused;
    private volatile double position;
    private volatile int volume = 70;
    private volatile boolean muted;
    private volatile String lastError = "";

    public Equalizer equalizer() {
        return equalizer;
    }

    public SpectrumAnalyzer spectrum() {
        return spectrum;
    }

    public FormatInfo info() {
        return info;
    }

    public Track current() {
        return current;
    }

    public List<Track> queue() {
        return queue;
    }

    public int queueIndex() {
        return index;
    }

    public PlaybackState state() {
        return state;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isActive() {
        return state == PlaybackState.PLAYING || state == PlaybackState.PAUSED;
    }

    public double position() {
        return position;
    }

    /** Duration from the tags, falling back to what the decoder reported. */
    public double duration() {
        Track track = current;
        if (track != null && track.duration() > 0) return track.duration();
        return info.duration;
    }

    public String lastError() {
        return lastError;
    }

    public int volume() {
        return volume;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setVolume(int value) {
        volume = Math.max(0, Math.min(100, value));
        if (volume > 0) muted = false;
    }

    public void adjustVolume(int delta) {
        setVolume(volume + delta);
    }

    public void toggleMute() {
        muted = !muted;
    }

    private float gain() {
        if (muted) return 0f;
        double v = volume / 100.0;
        return (float) (v * v);
    }

    /** Starts a folder's tracks at the given position in the list. */
    public synchronized void play(List<Track> tracks, int start) {
        stop();
        if (tracks.isEmpty()) return;
        queue = List.copyOf(tracks);
        index = Math.max(0, Math.min(queue.size() - 1, start));
        paused = false;
        position = 0;
        lastError = "";
        state = PlaybackState.PLAYING;
        equalizer.flush();
        session = new Session(index, 0);
        session.start();
    }

    public synchronized void stop() {
        Session running = session;
        session = null;
        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        if (running != null) running.shutdown();
        spectrum.silence();
        position = 0;
        if (state != PlaybackState.ERROR) {
            state = current == null ? PlaybackState.IDLE : PlaybackState.STOPPED;
        }
    }

    /** Pause and resume; restarts the queue if playback had ended. */
    public void togglePause() {
        if (session == null) {
            Track track = current;
            if (track != null && !queue.isEmpty()) play(queue, Math.max(0, index));
            return;
        }
        synchronized (pauseLock) {
            paused = !paused;
            state = paused ? PlaybackState.PAUSED : PlaybackState.PLAYING;
            pauseLock.notifyAll();
        }
    }

    public void next() {
        Session running = session;
        if (running != null) {
            running.request(Request.NEXT, 0);
        } else if (index + 1 < queue.size()) {
            play(queue, index + 1);
        }
    }

    /** Restarts the track when more than three seconds in, as players do. */
    public void previous() {
        Session running = session;
        if (running != null) {
            running.request(position > 3 ? Request.RESTART : Request.PREVIOUS, 0);
        } else if (index > 0) {
            play(queue, index - 1);
        }
    }

    public void seek(double seconds) {
        Session running = session;
        if (running == null) return;
        double target = Math.max(0, seconds);
        double total = duration();
        if (total > 0) target = Math.min(target, Math.max(0, total - 0.5));
        running.request(Request.SEEK, target);
    }

    public void seekRelative(double delta) {
        seek(position + delta);
    }

    @Override
    public void close() {
        stop();
    }

    private enum Request { NONE, NEXT, PREVIOUS, RESTART, SEEK }

    private enum Outcome { FINISHED, NEXT, PREVIOUS, RESTART, SEEK, FAILED, STOPPED }

    private final class Session {
        private final Thread thread;
        private final int startIndex;
        private final double startAt;
        private volatile boolean stopping;
        private volatile Request request = Request.NONE;
        private volatile double requestedPosition;
        private volatile TrackDecoder decoder;
        private volatile SourceDataLine line;

        Session(int startIndex, double startAt) {
            this.startIndex = startIndex;
            this.startAt = startAt;
            this.thread = new Thread(this::run, "player-session");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void request(Request what, double where) {
            requestedPosition = where;
            request = what;
            synchronized (pauseLock) {
                paused = false;
                state = PlaybackState.PLAYING;
                pauseLock.notifyAll();
            }
        }

        void shutdown() {
            stopping = true;
            TrackDecoder d = decoder;
            if (d != null) d.close();
            SourceDataLine l = line;
            if (l != null) {
                l.stop();
                l.flush();
            }
            thread.interrupt();
            try {
                thread.join(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void run() {
            int i = startIndex;
            double from = startAt;
            while (!stopping && i >= 0 && i < queue.size()) {
                index = i;
                Track track = queue.get(i);
                current = track;
                TagReader.read(track);
                info.clear();
                position = from;

                Outcome outcome = playTrack(track, from);
                if (stopping || outcome == Outcome.STOPPED) return;
                from = 0;
                switch (outcome) {
                    case SEEK -> from = requestedPosition;
                    case RESTART -> from = 0;
                    case PREVIOUS -> i = Math.max(0, i - 1);
                    case NEXT, FINISHED, FAILED -> i++;
                    default -> i++;
                }
            }
            if (!stopping) {
                state = PlaybackState.STOPPED;
                spectrum.silence();
                session = null;
            }
        }

        private Outcome playTrack(Track track, double from) {
            byte[] raw = new byte[BLOCK_BYTES];
            float[] samples = new float[FRAMES_PER_BLOCK * 2];
            long framesWritten = 0;

            try (TrackDecoder d = new TrackDecoder(track.path(), from, SAMPLE_RATE, info)) {
                decoder = d;
                DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
                SourceDataLine out = (SourceDataLine) AudioSystem.getLine(lineInfo);
                out.open(FORMAT, LINE_BUFFER_BYTES);
                out.start();
                line = out;
                if (!paused) state = PlaybackState.PLAYING;

                try {
                    while (!stopping) {
                        Outcome pending = awaitResume(out);
                        if (pending != null) return pending;

                        Request pendingRequest = request;
                        if (pendingRequest != Request.NONE) {
                            request = Request.NONE;
                            out.stop();
                            out.flush();
                            return switch (pendingRequest) {
                                case NEXT -> Outcome.NEXT;
                                case PREVIOUS -> Outcome.PREVIOUS;
                                case RESTART -> Outcome.RESTART;
                                case SEEK -> Outcome.SEEK;
                                default -> Outcome.FINISHED;
                            };
                        }

                        int filled = readFully(d, raw);
                        if (filled <= 0) break;
                        int frames = filled / 4;
                        toFloat(raw, filled, samples);
                        equalizer.process(samples, frames * 2, gain());
                        spectrum.feed(samples, frames * 2);
                        toBytes(samples, frames * 2, raw);
                        out.write(raw, 0, frames * 4);
                        framesWritten += frames;

                        long buffered = (out.getBufferSize() - out.available()) / 4L;
                        position = from + Math.max(0, framesWritten - buffered) / (double) SAMPLE_RATE;
                        track.setDurationIfUnknown(info.duration);
                    }
                } finally {
                    line = null;
                    out.stop();
                    out.flush();
                    out.close();
                }
                return stopping ? Outcome.STOPPED : Outcome.FINISHED;
            } catch (LineUnavailableException e) {
                lastError = "no audio device: " + e.getMessage();
                state = PlaybackState.ERROR;
                return Outcome.STOPPED;
            } catch (Exception e) {
                if (!stopping) lastError = track.fileName() + ": " + e.getMessage();
                return Outcome.FAILED;
            } finally {
                decoder = null;
            }
        }

        /** Blocks while paused, with the line stopped so the sound cuts at once. */
        private Outcome awaitResume(SourceDataLine out) {
            if (!paused) return null;
            out.stop();
            synchronized (pauseLock) {
                while (paused && !stopping) {
                    try {
                        pauseLock.wait(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Outcome.STOPPED;
                    }
                }
            }
            if (stopping) return Outcome.STOPPED;
            out.start();
            return null;
        }

        private int readFully(TrackDecoder d, byte[] buffer) throws java.io.IOException {
            int total = 0;
            while (total < buffer.length && !stopping && request == Request.NONE) {
                int n = d.read(buffer, total, buffer.length - total);
                if (n < 0) return total > 0 ? total - (total % 4) : -1;
                total += n;
            }
            return total - (total % 4);
        }
    }

    private static void toFloat(byte[] raw, int length, float[] out) {
        int n = length / 2;
        for (int i = 0; i < n; i++) {
            int lo = raw[i * 2] & 0xFF;
            int hi = raw[i * 2 + 1];
            out[i] = (short) ((hi << 8) | lo) / 32768f;
        }
    }

    private static void toBytes(float[] samples, int count, byte[] out) {
        for (int i = 0; i < count; i++) {
            int v = Math.round(samples[i] * 32767f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[i * 2] = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }
}
