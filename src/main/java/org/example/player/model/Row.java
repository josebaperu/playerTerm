package org.example.player.model;

/** One visible line of the library tree: a folder, or a track inside one. */
public record Row(Kind kind, int depth, MusicFolder folder, Track track) {

    public enum Kind { FOLDER, TRACK }

    public static Row folder(MusicFolder folder, int depth) {
        return new Row(Kind.FOLDER, depth, folder, null);
    }

    public static Row track(Track track, MusicFolder parent, int depth) {
        return new Row(Kind.TRACK, depth, parent, track);
    }

    public boolean isFolder() {
        return kind == Kind.FOLDER;
    }
}
