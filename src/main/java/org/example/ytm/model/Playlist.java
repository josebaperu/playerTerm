package org.example.ytm.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of the playlist export: a title and a YouTube Music link. Its
 * tracks are fetched by yt-dlp on a background thread, so the list is swapped
 * in whole rather than edited in place.
 */
public final class Playlist {

    public enum Load { PENDING, LOADING, LOADED, FAILED }

    private final String id;
    private volatile String title;
    private volatile String url;
    private volatile long updatedDate;
    private volatile List<Track> tracks = List.of();
    private volatile Load load = Load.PENDING;
    private volatile String error = "";
    private boolean expanded;

    public Playlist(String id, String title, String url, long updatedDate) {
        this.id = id;
        this.title = title;
        this.url = url;
        this.updatedDate = updatedDate;
    }

    public String id() {
        return id;
    }

    public String name() {
        return title;
    }

    public String url() {
        return url;
    }

    public long updatedDate() {
        return updatedDate;
    }

    /**
     * Takes the title, link and date from a newer export. Returns whether the
     * tracks should be fetched again.
     */
    public boolean update(String title, String url, long updatedDate) {
        boolean stale = !url.equals(this.url) || updatedDate != this.updatedDate;
        this.title = title;
        this.url = url;
        this.updatedDate = updatedDate;
        return stale;
    }

    public List<Track> tracks() {
        return tracks;
    }

    /**
     * Installs a fresh listing. Tracks already known keep their object, so a
     * resolved stream and the now playing marker survive a refresh.
     */
    public void setTracks(List<Track> fresh) {
        Map<String, Track> known = new HashMap<>();
        for (Track t : tracks) known.put(t.id(), t);
        tracks = fresh.stream().map(t -> known.getOrDefault(t.id(), t)).toList();
        error = "";
        load = Load.LOADED;
    }

    public Load load() {
        return load;
    }

    public void markLoading() {
        load = Load.LOADING;
    }

    public void markFailed(String message) {
        error = message == null ? "" : message;
        load = Load.FAILED;
    }

    public String error() {
        return error;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean value) {
        expanded = value;
    }

    public void toggleExpanded() {
        expanded = !expanded;
    }
}
