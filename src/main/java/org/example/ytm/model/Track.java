package org.example.ytm.model;

/**
 * One YouTube Music track. Title, artist and duration come from the playlist
 * listing; the audio stream address is looked up by yt-dlp when the track is
 * about to play, and kept until YouTube says it expires.
 */
public final class Track {

    private final String id;
    private final String url;
    private final String title;
    private final String artist;
    private final String album;
    private volatile double duration;
    private volatile String streamUrl;
    private volatile long streamExpires;

    public Track(String id, String url, String title, String artist, String album, double duration) {
        this.id = id;
        this.url = url;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.duration = duration;
    }

    /** The YouTube video id. */
    public String id() {
        return id;
    }

    /** The watch page, which is what yt-dlp resolves. */
    public String url() {
        return url;
    }

    public String displayName() {
        return title.isEmpty() ? id : title;
    }

    public String title() {
        return title;
    }

    public String artist() {
        return artist;
    }

    /** The playlist the track was listed in. */
    public String album() {
        return album;
    }

    public double duration() {
        return duration;
    }

    /** Duration learned from the decoder when the listing had nothing to say. */
    public void setDurationIfUnknown(double seconds) {
        if (duration <= 0 && seconds > 0) duration = seconds;
    }

    /** The resolved audio stream, or null when it is unknown or about to expire. */
    public String streamUrl() {
        String stream = streamUrl;
        if (stream == null) return null;
        if (streamExpires > 0 && System.currentTimeMillis() / 1000 > streamExpires - 120) return null;
        return stream;
    }

    public void setStream(String url, long expiresEpochSeconds) {
        streamExpires = expiresEpochSeconds;
        streamUrl = url;
    }

    public void clearStream() {
        streamUrl = null;
    }

    /** Same video, even when listed in two playlists. */
    public boolean sameAs(Track other) {
        return other != null && id.equals(other.id);
    }
}
