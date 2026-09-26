package org.example.ytm.source;

import org.example.ytm.model.Playlist;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches playlist contents with yt-dlp, off the render thread. Every
 * playlist is queued as soon as it appears in an export, so track counts fill
 * in while you browse. {@code onChange} runs on the loader thread whenever a
 * playlist finishes, successfully or not.
 */
public final class PlaylistLoader implements AutoCloseable {

    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "playlist-loader");
        t.setDaemon(true);
        return t;
    });
    private final Runnable onChange;

    public PlaylistLoader(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Queues a playlist unless it is already being fetched. */
    public void request(Playlist playlist) {
        if (playlist == null || playlist.load() == Playlist.Load.LOADING) return;
        playlist.markLoading();
        String url = playlist.url();
        workers.submit(() -> {
            try {
                playlist.setTracks(YtDlp.listPlaylist(url, playlist.name()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                playlist.markFailed(e.getMessage());
            }
            onChange.run();
        });
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
