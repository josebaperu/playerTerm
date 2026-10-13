package org.example.ytm.scan;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What the scan screen shows. The scanner writes it from a background thread
 * and the render loop takes a {@link Snapshot} once per frame.
 */
public final class ScanModel {

    public enum Status { RUNNING, DONE, CANCELLED, FAILED }

    public enum Level { INFO, OK, WARN, ERROR }

    public enum Count { ON_DISK, DOWNLOADED, FAILED, FOLDER }

    public static final String STARTING = "reading the playlist export";
    public static final String LISTING = "listing";
    public static final String CHECKING = "checking";
    public static final String DOWNLOADING = "downloading";
    public static final String ON_DISK = "on disk";
    public static final String SAVED = "saved";
    public static final String FAILED = "failed";

    public record LogLine(String time, String album, String message, Level level) {
    }

    public record Snapshot(
            Status status,
            String music,
            String exportName,
            String problem,
            int albumCount,
            int albumIndex,
            String album,
            String action,
            String track,
            int albumTrackDone,
            int albumTrackTotal,
            int onDisk,
            int downloaded,
            int failed,
            int foldersCreated,
            List<LogLine> log,
            double progress) {
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int LOG_CAP = 1000;

    private final Object lock = new Object();
    private final Deque<LogLine> log = new ArrayDeque<>();
    private Status status = Status.RUNNING;
    private String music = "";
    private String exportName = "";
    private String problem = "";
    private int albumCount;
    private int albumIndex;
    private String album = "";
    private boolean albumOpen;
    private String action = "";
    private String track = "";
    private int albumTrackDone;
    private int albumTrackTotal;
    private int onDisk;
    private int downloaded;
    private int failed;
    private int foldersCreated;

    /** Shown on the first frames, before the export has been read. */
    public void starting(String music) {
        synchronized (lock) {
            this.music = music == null ? "" : music;
            this.action = STARTING;
        }
    }

    public void prepare(String music, String export, int albums) {
        synchronized (lock) {
            this.music = music == null ? "" : music;
            this.exportName = export == null ? "" : export;
            this.albumCount = Math.max(0, albums);
            if (this.action.isEmpty() || STARTING.equals(this.action)) this.action = albums == 0 ? "" : LISTING;
        }
    }

    public void beginAlbum(int index, String name) {
        synchronized (lock) {
            albumIndex = index;
            album = name == null ? "" : name;
            albumOpen = true;
            albumTrackDone = 0;
            albumTrackTotal = 0;
            action = LISTING;
            track = "";
        }
    }

    /** The line under the album name: what this track is doing right now. */
    public void activity(String action, String track, int done, int total) {
        synchronized (lock) {
            this.action = action == null ? "" : action;
            this.track = track == null ? "" : track;
            this.albumTrackDone = Math.max(0, done);
            this.albumTrackTotal = Math.max(0, total);
        }
    }

    public void count(Count kind) {
        synchronized (lock) {
            switch (kind) {
                case ON_DISK -> onDisk++;
                case DOWNLOADED -> downloaded++;
                case FAILED -> failed++;
                case FOLDER -> foldersCreated++;
            }
        }
    }

    public void endAlbum() {
        synchronized (lock) {
            albumOpen = false;
            if (albumTrackTotal > 0) albumTrackDone = albumTrackTotal;
            action = "";
            track = "";
        }
    }

    public void log(Level level, String album, String message) {
        synchronized (lock) {
            addLog(level, album, message);
        }
    }

    public void finish() {
        synchronized (lock) {
            if (status != Status.RUNNING) return;
            status = Status.DONE;
            albumOpen = false;
            action = "";
            track = "";
            addLog(Level.OK, "", summary());
        }
    }

    public void cancelled() {
        synchronized (lock) {
            if (status != Status.RUNNING) return;
            status = Status.CANCELLED;
            albumOpen = false;
            action = "";
            track = "";
            addLog(Level.WARN, "", "stopped, " + summary());
        }
    }

    public void fail(String problem) {
        synchronized (lock) {
            if (status != Status.RUNNING) return;
            status = Status.FAILED;
            albumOpen = false;
            this.problem = problem == null ? "" : problem;
            action = "";
            track = "";
            addLog(Level.ERROR, "", this.problem.isEmpty() ? "scan failed" : this.problem);
        }
    }

    public Status status() {
        synchronized (lock) {
            return status;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(status, music, exportName, problem, albumCount, albumIndex, album, action, track,
                    albumTrackDone, albumTrackTotal, onDisk, downloaded, failed, foldersCreated,
                    List.copyOf(new ArrayList<>(log)), progressLocked());
        }
    }

    /** Home is shown as {@code ~}. The scan screen is full of paths. */
    public static String displayPath(Path path) {
        if (path == null) return "";
        String home = System.getProperty("user.home");
        String full = path.toAbsolutePath().normalize().toString();
        if (home != null && full.startsWith(home)) {
            String rest = full.substring(home.length());
            return rest.isEmpty() ? "~" : "~" + rest;
        }
        return full;
    }

    private double progressLocked() {
        if (status == Status.DONE) return 1;
        if (albumCount <= 0) return 0;
        int finished = albumOpen ? Math.max(0, albumIndex - 1) : Math.max(0, albumIndex);
        double part = 0;
        if (albumOpen && albumTrackTotal > 0) {
            part = Math.min(1, albumTrackDone / (double) albumTrackTotal);
        }
        return Math.min(1, (finished + part) / (double) albumCount);
    }

    private String summary() {
        return downloaded + " downloaded, " + onDisk + " already on disk, " + failed + " failed";
    }

    private void addLog(Level level, String album, String message) {
        String text = message == null ? "" : message;
        log.addLast(new LogLine(LocalTime.now().format(CLOCK), album == null ? "" : album, text, level));
        while (log.size() > LOG_CAP) log.removeFirst();
    }
}
