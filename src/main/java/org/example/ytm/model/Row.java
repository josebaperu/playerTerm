package org.example.ytm.model;

/** One visible line of the library: a playlist, or a track inside one. */
public record Row(Kind kind, int depth, Playlist playlist, Track track) {

    public enum Kind { PLAYLIST, TRACK }

    public static Row playlist(Playlist playlist) {
        return new Row(Kind.PLAYLIST, 0, playlist, null);
    }

    public static Row track(Track track, Playlist parent) {
        return new Row(Kind.TRACK, 1, parent, track);
    }

    public boolean isPlaylist() {
        return kind == Kind.PLAYLIST;
    }
}
