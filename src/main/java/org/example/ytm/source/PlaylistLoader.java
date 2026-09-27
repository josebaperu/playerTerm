package org.example.ytm.source;

import org.example.ytm.model.Playlist;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Fetches playlist contents with yt-dlp, off the render thread. Every
 * playlist is queued as soon as it appears in an export and fetched one at a
 * time, in export order, so track counts fill in from the top while you
 * browse. A playlist you ask for directly (a retry, or play) jumps the queue.
 * {@code onChange} runs on the loader thread whenever a playlist finishes,
 * successfully or not.
 */
public final class PlaylistLoader implements AutoCloseable {

    private final BlockingDeque<Playlist> queue = new LinkedBlockingDeque<>();
    private final Thread worker;
    private final Runnable onChange;

    public PlaylistLoader(Runnable onChange) {
        this.onChange = onChange;
        worker = new Thread(this::run, "playlist-loader");
        worker.setDaemon(true);
        worker.start();
    }

    /** Queues a playlist behind the others unless it is already waiting or being fetched. */
    public void request(Playlist playlist) {
        if (playlist == null) return;
        Playlist.Load load = playlist.load();
        if (load == Playlist.Load.QUEUED || load == Playlist.Load.LOADING) return;
        playlist.markQueued();
        queue.addLast(playlist);
    }

    /**
     * Puts a playlist at the front of the queue, for one the user is waiting
     * on. One already waiting in the queue moves up; one being fetched right
     * now is left alone.
     */
    public void requestNow(Playlist playlist) {
        if (playlist == null || playlist.load() == Playlist.Load.LOADING) return;
        if (queue.remove(playlist) || (playlist.load() != Playlist.Load.QUEUED && playlist.load() != Playlist.Load.LOADING)) {
            playlist.markQueued();
            queue.addFirst(playlist);
        }
    }

    private void run() {
        try {
            while (true) {
                Playlist playlist = queue.take();
                playlist.markLoading();
                try {
                    playlist.setTracks(YtDlp.listPlaylist(playlist.url(), playlist.name()));
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    playlist.markFailed(e.getMessage());
                }
                onChange.run();
            }
        } catch (InterruptedException e) {
            // Shutting down.
        }
    }

    @Override
    public void close() {
        worker.interrupt();
    }
}
