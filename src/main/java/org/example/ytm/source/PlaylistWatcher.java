package org.example.ytm.source;

import org.example.ytm.model.PlaylistLibrary;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * Watches the playlists folder for new or rewritten exports. Events are only
 * recorded here; the app asks {@link #due()} each frame and reloads once the
 * folder has been quiet briefly, so a file still being written is read after
 * the writing stops rather than halfway through.
 */
public final class PlaylistWatcher implements AutoCloseable {

    private static final long SETTLE_MS = 300;

    private final WatchService service;
    private final Thread thread;
    private volatile long lastEventAt;
    private long handledAt;

    public PlaylistWatcher(Path dir) throws IOException {
        service = FileSystems.getDefault().newWatchService();
        dir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        thread = new Thread(this::run, "playlist-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            while (true) {
                WatchKey key = service.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    boolean overflow = event.kind() == StandardWatchEventKinds.OVERFLOW;
                    if (overflow || (event.context() instanceof Path name
                            && PlaylistLibrary.isExport(name.toString()))) {
                        lastEventAt = System.currentTimeMillis();
                    }
                }
                if (!key.reset()) return;   // folder deleted
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // Shutting down.
        }
    }

    /** True once per burst of changes, after the folder has settled. */
    public boolean due() {
        long last = lastEventAt;
        if (last <= handledAt || System.currentTimeMillis() - last < SETTLE_MS) return false;
        handledAt = last;
        return true;
    }

    @Override
    public void close() {
        try {
            service.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        thread.interrupt();
    }
}
