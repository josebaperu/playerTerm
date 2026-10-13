package org.example.ytm.scan;

import org.example.player.tui.Key;
import org.example.player.tui.Screen;
import org.example.player.tui.Terminal;
import org.example.ytm.source.TrackStore;

import java.nio.file.Path;

/**
 * The {@code --scan} screen. It leaves the player closed and fills in album
 * folders that the export says should be in the music library.
 */
public final class ScanApp {

    private static final long FRAME_MS = 40;

    private ScanApp() {
    }

    public static int run(Path playlistsRoot) {
        return run(playlistsRoot, TrackStore.defaultRoot());
    }

    static int run(Path playlistsRoot, Path musicRoot) {
        if (!Terminal.isInteractive()) {
            System.err.println("playerYTM needs an interactive terminal (stdin is not a tty).");
            return 1;
        }
        ScanModel model = new ScanModel();
        model.starting(ScanModel.displayPath(musicRoot));
        LibraryScanner scanner = LibraryScanner.start(playlistsRoot, musicRoot, model);
        Terminal terminal = null;
        try {
            terminal = new Terminal(false);
            Terminal open = terminal;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                scanner.close();
                open.close();
            }));
            Screen screen = new Screen();
            screen.resize(terminal.width(), terminal.height());
            loop(terminal, screen, model, scanner);
        } finally {
            scanner.close();
            if (terminal != null) terminal.close();
        }
        return 0;
    }

    private static void loop(Terminal terminal, Screen screen, ScanModel model, LibraryScanner scanner) {
        long nextFrame = 0;
        int tick = 0;
        int logOffset = 0;
        boolean follow = true;
        boolean stopping = false;
        boolean running = true;
        while (running) {
            Key key = terminal.readKey(12);
            if (!key.isNone()) {
                if (key.is('q')) {
                    if (stopping || model.status() != ScanModel.Status.RUNNING) {
                        running = false;
                    } else {
                        stopping = true;
                        model.log(ScanModel.Level.WARN, "", "stopping");
                        scanner.cancel();
                    }
                } else if (key.type() == Key.Type.UP || key.is('k')) {
                    follow = false;
                    logOffset++;
                } else if (key.type() == Key.Type.DOWN || key.is('j')) {
                    if (logOffset > 0) logOffset--;
                    if (logOffset == 0) follow = true;
                } else if (key.type() == Key.Type.HOME || key.is('g')) {
                    follow = false;
                    logOffset = Integer.MAX_VALUE / 4;
                } else if (key.type() == Key.Type.END || key.is('G')) {
                    follow = true;
                    logOffset = 0;
                } else if (key.type() == Key.Type.PAGE_UP) {
                    follow = false;
                    logOffset += Math.max(1, ScanUi.logRows(screen.height()));
                } else if (key.type() == Key.Type.PAGE_DOWN) {
                    logOffset -= Math.max(1, ScanUi.logRows(screen.height()));
                    if (logOffset <= 0) {
                        logOffset = 0;
                        follow = true;
                    }
                }
            }
            if (terminal.refreshSize(false)) {
                screen.resize(terminal.width(), terminal.height());
                screen.invalidate();
            }
            long now = System.currentTimeMillis();
            if (now >= nextFrame) {
                nextFrame = now + FRAME_MS;
                tick++;
                ScanModel.Snapshot snap = model.snapshot();
                int rows = ScanUi.logRows(screen.height());
                int max = Math.max(0, snap.log().size() - rows);
                if (follow) logOffset = 0;
                else logOffset = Math.max(0, Math.min(logOffset, max));
                StringBuilder out = new StringBuilder(4096);
                ScanUi.render(screen, snap, tick, logOffset, stopping);
                screen.render(out);
                terminal.write(out);
            }
        }
    }
}
