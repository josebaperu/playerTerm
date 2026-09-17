package org.example.player.model;

import java.nio.file.Path;

/**
 * One audio file. Tags and duration arrive later, on a background thread, so
 * the fields that ffprobe fills are volatile and the display name falls back
 * to the file name until then.
 */
public final class Track {

    private final Path path;
    private final String fileName;
    private volatile String title = "";
    private volatile String artist = "";
    private volatile String album = "";
    private volatile int trackNumber;
    private volatile double duration;
    private volatile boolean tagged;

    public Track(Path path) {
        this.path = path;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        this.fileName = dot > 0 ? name.substring(0, dot) : name;
    }

    public Path path() {
        return path;
    }

    public String fileName() {
        return fileName;
    }

    /** Tag title once known, the file name before that. */
    public String displayName() {
        return title.isEmpty() ? fileName : title;
    }

    public String title() {
        return title;
    }

    public String artist() {
        return artist;
    }

    public String album() {
        return album;
    }

    public int trackNumber() {
        return trackNumber;
    }

    public double duration() {
        return duration;
    }

    public boolean isTagged() {
        return tagged;
    }

    public void applyTags(String title, String artist, String album, int trackNumber, double duration) {
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.trackNumber = trackNumber;
        this.duration = duration;
        this.tagged = true;
    }

    /** Duration learned from the decoder when ffprobe had nothing to say. */
    public void setDurationIfUnknown(double seconds) {
        if (duration <= 0 && seconds > 0) duration = seconds;
    }

    public String extension() {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
