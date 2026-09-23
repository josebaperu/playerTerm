package org.example.player.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A directory that holds audio files, or leads to one. */
public final class MusicFolder {

    private final Path path;
    private final String name;
    private final MusicFolder parent;
    private final List<MusicFolder> children = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();
    private boolean expanded;
    private volatile boolean tagsRequested;
    /** Set when a reload finds new tracks in a folder that was already probed. */
    private volatile boolean retag;

    public MusicFolder(Path path, String name, MusicFolder parent) {
        this.path = path;
        this.name = name;
        this.parent = parent;
    }

    public Path path() {
        return path;
    }

    public String name() {
        return name;
    }

    public MusicFolder parent() {
        return parent;
    }

    public List<MusicFolder> children() {
        return children;
    }

    public List<Track> tracks() {
        return tracks;
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

    public boolean hasContent() {
        return !children.isEmpty() || !tracks.isEmpty();
    }

    public boolean tagsRequested() {
        return tagsRequested;
    }

    public void markTagsRequested() {
        tagsRequested = true;
    }

    public void clearTagsRequested() {
        tagsRequested = false;
    }

    public void markRetag() {
        retag = true;
    }

    /** Returns whether this folder should be probed again, and clears the flag. */
    public boolean takeRetag() {
        boolean pending = retag;
        retag = false;
        return pending;
    }

    /** Depth below the library root, used to indent the tree. */
    public int depth() {
        int d = 0;
        for (MusicFolder f = parent; f != null && f.parent != null; f = f.parent) d++;
        return d;
    }

    /** Tracks in this folder and, in tree order, every folder beneath it. */
    public List<Track> tracksDeep() {
        List<Track> out = new ArrayList<>(tracks);
        for (MusicFolder child : children) out.addAll(child.tracksDeep());
        return out;
    }
}
