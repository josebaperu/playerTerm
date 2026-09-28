package org.example.ytm.source;

import org.example.ytm.model.Playlist;
import org.example.ytm.model.Track;

import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Fetches playlist contents with yt-dlp, off the render thread. A listing
 * already saved for the same link and date is restored immediately, so a
 * restart does not scan that playlist again. Everything else is queued as
 * soon as it appears in an export and fetched one at a time, in export order,
 * so track counts fill in from the top while you browse. A playlist you ask
 * for directly (a retry, or play) jumps the queue. {@code onChange} runs
 * whenever a playlist finishes, successfully or not.
 */
public final class PlaylistLoader implements AutoCloseable {

    private final BlockingDeque<Playlist> queue = new LinkedBlockingDeque<>();
    private final Thread worker;
    private final Runnable onChange;
    private final PlaylistCache cache;

    public PlaylistLoader(Runnable onChange) {
        this(onChange, new PlaylistCache(PlaylistCache.defaultDirectory()));
    }

    public PlaylistLoader(Runnable onChange, PlaylistCache cache) {
        this.onChange = onChange;
        this.cache = cache;
        worker = new Thread(this::run, "playlist-loader");
        worker.setDaemon(true);
        worker.start();
    }

    /** Queues a playlist behind the others unless it is already waiting, being fetched, or saved. */
    public void request(Playlist playlist) {
        if (playlist == null) return;
        Playlist.Load load = playlist.load();
        if (load == Playlist.Load.QUEUED || load == Playlist.Load.LOADING) return;
        if (load != Playlist.Load.FAILED && restore(playlist)) return;
        playlist.markQueued();
        queue.addLast(playlist);
    }

    /**
     * Puts a playlist at the front of the queue, for one the user is waiting
     * on. One already waiting in the queue moves up; one being fetched right
     * now is left alone. A retry after a failure lists it again; any other
     * request uses a saved listing when the link and date still match.
     */
    public void requestNow(Playlist playlist) {
        if (playlist == null || playlist.load() == Playlist.Load.LOADING) return;
        if (playlist.load() != Playlist.Load.FAILED && restore(playlist)) {
            queue.remove(playlist);
            return;
        }
        if (queue.remove(playlist) || (playlist.load() != Playlist.Load.QUEUED && playlist.load() != Playlist.Load.LOADING)) {
            playlist.markQueued();
            queue.addFirst(playlist);
        }
    }

    /** Forgets saved listings whose playlists are no longer in the library. */
    public void retain(List<Playlist> playlists) {
        cache.retain(playlists);
    }

    /** Installs a saved listing. Returns whether yt-dlp can be skipped. */
    private boolean restore(Playlist playlist) {
        List<Track> tracks = cache.load(playlist);
        if (tracks == null) return false;
        playlist.setTracks(tracks);
        onChange.run();
        return true;
    }

    private void run() {
        try {
            while (true) {
                Playlist playlist = queue.take();
                // Read the link before the scan. A newer export can replace it
                // while yt-dlp is still walking the old one, and that listing
                // must not be saved against the new link.
                String url = playlist.url();
                long updated = playlist.updatedDate();
                String name = playlist.name();
                playlist.markLoading();
                try {
                    List<Track> tracks = YtDlp.listPlaylist(url, name);
                    if (stillCurrent(playlist, url, updated)) {
                        playlist.setTracks(tracks);
                        cache.store(playlist.id(), url, updated, tracks);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    if (stillCurrent(playlist, url, updated)) playlist.markFailed(e.getMessage());
                }
                // A link that changed while this scan ran was not queued: a
                // request ignores a playlist already being fetched.
                if (!stillCurrent(playlist, url, updated) && !queue.contains(playlist)) {
                    playlist.markQueued();
                    queue.addFirst(playlist);
                }
                onChange.run();
            }
        } catch (InterruptedException e) {
            // Shutting down.
        }
    }

    private static boolean stillCurrent(Playlist playlist, String url, long updated) {
        return url.equals(playlist.url()) && updated == playlist.updatedDate();
    }

    @Override
    public void close() {
        worker.interrupt();
    }
}
