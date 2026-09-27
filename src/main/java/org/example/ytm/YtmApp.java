package org.example.ytm;

import org.example.player.audio.EqPreset;
import org.example.player.audio.Equalizer;
import org.example.ytm.audio.PlaybackState;
import org.example.ytm.audio.Player;
import org.example.player.config.Settings;
import org.example.ytm.model.Playlist;
import org.example.ytm.model.PlaylistLibrary;
import org.example.ytm.model.Row;
import org.example.ytm.model.Track;
import org.example.player.tui.Key;
import org.example.player.tui.MouseEvent;
import org.example.player.tui.Screen;
import org.example.player.tui.Terminal;
import org.example.ytm.ui.Ui;
import org.example.ytm.source.PlaylistLoader;
import org.example.ytm.source.PlaylistWatcher;
import org.example.ytm.source.YtDlp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Application state, key and mouse handling, and the render loop. */
public final class YtmApp {

    public enum Focus { LIBRARY, EQUALIZER }

    private static final long FRAME_MS = 40;
    private static final long MESSAGE_MS = 4000;
    private static final long DOUBLE_CLICK_MS = 450;
    private static final double SEEK_STEP = 5;

    private final Path configDir = configDirectory();
    private final Settings settings = new Settings(configDir.resolve("config.properties"));
    private final Player player = new Player();
    private final PlaylistLoader loader = new PlaylistLoader(() -> playlistsChanged = true);

    private PlaylistLibrary library;
    private PlaylistWatcher watcher;
    private volatile boolean playlistsChanged;
    /** A playlist the user asked to play before its tracks had arrived. */
    private Playlist pendingPlay;
    private List<Row> rows = new ArrayList<>();
    private int selected;
    private int scroll;
    private Focus focus = Focus.LIBRARY;
    private int eqBand;
    private boolean showHelp;
    private boolean running = true;
    private long tick;
    private String message = "";
    private long messageAt;
    private int draggingBand = -1;
    private int lastClickRow = -1;
    private long lastClickAt;
    private int lastWidth = 80;
    private int lastHeight = 24;

    public static int run(String[] args) {
        return new YtmApp().start(args);
    }

    private YtmApp() {
    }

    private static Path configDirectory() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("playerytm");
    }

    private void loadSettings() {
        player.setVolume(settings.getInt("volume", 70));
        Equalizer eq = player.equalizer();
        eq.setGains(settings.getInts("eq.gains", new int[Equalizer.BANDS]));
        eq.setPreamp(settings.getInt("eq.preamp", 0));
        eq.setEnabled(settings.getBool("eq.enabled", true));
    }

    private void saveSettings() {
        settings.set("volume", player.volume());
        settings.setInts("eq.gains", player.equalizer().gains());
        settings.set("eq.preamp", player.equalizer().preamp());
        settings.set("eq.enabled", player.equalizer().isEnabled());
        Track track = player.current();
        if (track != null) settings.set("last.track", track.url());
        settings.save();
    }

    private int start(String[] args) {
        String playlistsPath = null;
        boolean mouse = settings.getBool("mouse", true);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> {
                    printUsage();
                    return 0;
                }
                case "-p", "--playlists" -> {
                    if (i + 1 < args.length) playlistsPath = args[++i];
                }
                case "--no-mouse" -> mouse = false;
                default -> {
                    if (args[i].startsWith("-")) {
                        System.err.println("unknown option: " + args[i]);
                        printUsage();
                        return 2;
                    }
                    playlistsPath = args[i];
                }
            }
        }

        if (!hasBinary("ffmpeg", "PLAYERTERM_FFMPEG", "-version")) {
            System.err.println("ffmpeg was not found on PATH. Install it, or set PLAYERTERM_FFMPEG.");
            return 1;
        }
        if (!hasBinary(YtDlp.binary(), "PLAYERTERM_YTDLP", "--version")) {
            System.err.println("yt-dlp was not found on PATH. Install it, or set PLAYERTERM_YTDLP.");
            return 1;
        }
        Path root = PlaylistLibrary.resolveRoot(playlistsPath);
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            System.err.println("point playerYTM at your playlist exports with: playerYTM /path/to/folder");
            return 1;
        }
        if (!Terminal.isInteractive()) {
            System.err.println("playerYTM needs an interactive terminal (stdin is not a tty).");
            return 1;
        }

        loadSettings();
        library = new PlaylistLibrary(root);
        List<Playlist> fresh = library.reload();
        if (fresh != null) fresh.forEach(loader::request);
        rebuildRows();
        try {
            watcher = new PlaylistWatcher(root);
        } catch (IOException e) {
            System.err.println("cannot watch " + root + " for new exports: " + e.getMessage());
        }

        Terminal terminal = new Terminal(mouse);
        Runtime.getRuntime().addShutdownHook(new Thread(terminal::close));
        Screen screen = new Screen();
        try {
            screen.resize(terminal.width(), terminal.height());
            announceLibrary();
            loop(terminal, screen);
        } finally {
            player.close();
            loader.close();
            if (watcher != null) watcher.close();
            saveSettings();
            terminal.close();
        }
        return 0;
    }

    private static boolean hasBinary(String name, String envOverride, String versionFlag) {
        try {
            String bin = System.getenv(envOverride);
            Process p = new ProcessBuilder(bin == null || bin.isBlank() ? name : bin, versionFlag)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void printUsage() {
        System.out.println("""
                playerYTM - YouTube Music in the terminal, with a 10 band equalizer

                usage: playerYTM [options] [playlists folder]
                  -p, --playlists <dir> folder of playlist exports to read
                      --no-mouse        disable mouse reporting for this run
                  -h, --help            show this help

                reads the newest export in the folder, a JSON list of
                {id, title, url, updatedDate}, and follows it as new ones arrive.
                streams with yt-dlp and ffmpeg, one playlist at a time

                playlists:  $PLAYERTERM_PLAYLISTS, then ~/Downloads/ytm_playlists
                settings:   ~/.config/playerytm/config.properties""");
    }

    private void loop(Terminal terminal, Screen screen) {
        long nextFrame = 0;
        while (running) {
            Key key = terminal.readKey(12);
            if (!key.isNone()) handleKey(key);
            if (watcher != null && watcher.due()) reloadLibrary();
            if (playlistsChanged) playlistsLoaded();

            if (terminal.refreshSize(false)) {
                screen.resize(terminal.width(), terminal.height());
                screen.invalidate();
            }
            long now = System.currentTimeMillis();
            if (now >= nextFrame) {
                nextFrame = now + FRAME_MS;
                tick++;
                clampSelection(screen.width(), screen.height());
                StringBuilder out = new StringBuilder(8192);
                Ui.render(screen, this);
                screen.render(out);
                terminal.write(out);
            }
        }
    }

    // ------------------------------------------------------------------ keys

    private void handleKey(Key key) {
        if (key.type() == Key.Type.MOUSE) {
            handleMouse(key.mouse());
            return;
        }
        if (showHelp) {
            showHelp = key.type() != Key.Type.ESCAPE && !key.is('?') && !key.is('q')
                    && key.type() != Key.Type.ENTER;
            if (!showHelp) return;
        }

        if (key.type() == Key.Type.TAB || key.type() == Key.Type.SHIFT_TAB) {
            focus = focus == Focus.LIBRARY ? Focus.EQUALIZER : Focus.LIBRARY;
            reloadLibrary();
            return;
        }
        if (key.is('q')) {
            running = false;
            return;
        }
        if (key.is('?')) {
            showHelp = true;
            return;
        }
        if (key.is('+') || key.is('=')) {
            player.adjustVolume(5);
            note("volume " + player.volume() + "%");
            return;
        }
        if (key.is('-') || key.is('_')) {
            player.adjustVolume(-5);
            note("volume " + player.volume() + "%");
            return;
        }
        if (key.is('m')) {
            player.toggleMute();
            note(player.isMuted() ? "muted" : "unmuted");
            return;
        }
        if (key.is('p')) {
            player.togglePause();
            return;
        }
        if (key.is('s')) {
            player.stop();
            note("stopped");
            return;
        }
        if (key.is('n')) {
            player.next();
            return;
        }
        if (key.is('b')) {
            player.previous();
            return;
        }
        if (key.is(',')) {
            player.seekRelative(-SEEK_STEP);
            return;
        }
        if (key.is('.')) {
            player.seekRelative(SEEK_STEP);
            return;
        }
        if (key.is('e')) {
            Equalizer eq = player.equalizer();
            eq.setEnabled(!eq.isEnabled());
            note(eq.isEnabled() ? "equalizer on" : "equalizer bypassed");
            return;
        }
        if (key.is('r')) {
            player.equalizer().reset();
            note("equalizer flat");
            return;
        }
        if (key.is('[')) {
            cyclePreset(-1);
            return;
        }
        if (key.is(']')) {
            cyclePreset(1);
            return;
        }
        if (key.is('<')) {
            player.equalizer().setPreamp(player.equalizer().preamp() - 1);
            note("preamp " + player.equalizer().preamp() + " dB");
            return;
        }
        if (key.is('>')) {
            player.equalizer().setPreamp(player.equalizer().preamp() + 1);
            note("preamp " + player.equalizer().preamp() + " dB");
            return;
        }

        if (focus == Focus.EQUALIZER) {
            handleEqualizerKey(key);
        } else {
            handleLibraryKey(key);
        }
    }

    private void handleEqualizerKey(Key key) {
        Equalizer eq = player.equalizer();
        switch (key.type()) {
            case LEFT -> eqBand = (eqBand + Equalizer.BANDS - 1) % Equalizer.BANDS;
            case RIGHT -> eqBand = (eqBand + 1) % Equalizer.BANDS;
            case UP -> eq.setGain(eqBand, eq.gain(eqBand) + 1);
            case DOWN -> eq.setGain(eqBand, eq.gain(eqBand) - 1);
            case HOME -> eqBand = 0;
            case END -> eqBand = Equalizer.BANDS - 1;
            case CHAR -> {
                char c = key.ch();
                if (c == 'h') eqBand = (eqBand + Equalizer.BANDS - 1) % Equalizer.BANDS;
                else if (c == 'l') eqBand = (eqBand + 1) % Equalizer.BANDS;
                else if (c == 'k') eq.setGain(eqBand, eq.gain(eqBand) + 1);
                else if (c == 'j') eq.setGain(eqBand, eq.gain(eqBand) - 1);
                else if (c >= '0' && c <= '9') eqBand = c == '0' ? 9 : c - '1';
            }
            default -> { }
        }
    }

    private void handleLibraryKey(Key key) {
        int page = Math.max(1, Ui.libraryRows(lastHeight));
        switch (key.type()) {
            case UP -> moveSelection(-1);
            case DOWN -> moveSelection(1);
            case PAGE_UP -> moveSelection(-page);
            case PAGE_DOWN -> moveSelection(page);
            case HOME -> selected = 0;
            case END -> selected = Math.max(0, rows.size() - 1);
            case RIGHT -> expandSelected();
            case LEFT -> collapseSelected();
            case ENTER -> playSelected();
            case CHAR -> {
                switch (key.ch()) {
                    case 'j' -> moveSelection(1);
                    case 'k' -> moveSelection(-1);
                    case 'l' -> expandSelected();
                    case 'h' -> collapseSelected();
                    case 'g' -> selected = 0;
                    case 'G' -> selected = Math.max(0, rows.size() - 1);
                    case ' ' -> spaceOnSelection();
                    default -> { }
                }
            }
            default -> { }
        }
    }

    /** Space folds a playlist, and pauses when a track is selected. */
    private void spaceOnSelection() {
        Row row = currentRow();
        if (row == null) return;
        if (row.isPlaylist()) {
            togglePlaylist(row.playlist());
        } else {
            player.togglePause();
        }
    }

    /** Opening a playlist whose listing failed tries yt-dlp again. */
    private void togglePlaylist(Playlist playlist) {
        playlist.toggleExpanded();
        if (playlist.isExpanded() && playlist.load() == Playlist.Load.FAILED) {
            loader.requestNow(playlist);
            note("retrying " + playlist.name());
        }
        rebuildRows();
    }

    private void expandSelected() {
        Row row = currentRow();
        if (row == null) return;
        if (row.isPlaylist() && !row.playlist().isExpanded()) {
            togglePlaylist(row.playlist());
        } else {
            moveSelection(1);
        }
    }

    private void collapseSelected() {
        Row row = currentRow();
        if (row == null) return;
        if (row.isPlaylist() && row.playlist().isExpanded()) {
            togglePlaylist(row.playlist());
            return;
        }
        // Jump to the playlist this row lives in.
        Playlist parent = row.playlist();
        for (int i = selected - 1; i >= 0; i--) {
            Row candidate = rows.get(i);
            if (candidate.isPlaylist() && candidate.playlist() == parent) {
                selected = i;
                return;
            }
        }
    }

    private void playSelected() {
        Row row = currentRow();
        if (row == null) return;
        if (row.isPlaylist()) {
            playPlaylist(row.playlist());
        } else {
            Playlist playlist = row.playlist();
            List<Track> tracks = playlist.tracks();
            int index = tracks.indexOf(row.track());
            pendingPlay = null;
            player.play(tracks, Math.max(0, index));
            note("playing " + playlist.name());
        }
    }

    /**
     * Plays a playlist from the top. One still being listed plays as soon as
     * its tracks arrive.
     */
    private void playPlaylist(Playlist playlist) {
        List<Track> queue = playlist.tracks();
        if (queue.isEmpty()) {
            switch (playlist.load()) {
                case LOADED -> note(playlist.name() + " has no playable tracks");
                case FAILED -> {
                    pendingPlay = playlist;
                    loader.requestNow(playlist);
                    note("retrying " + playlist.name());
                }
                default -> {
                    pendingPlay = playlist;
                    loader.requestNow(playlist);
                    note("loading " + playlist.name() + "…");
                }
            }
            return;
        }
        pendingPlay = null;
        if (!playlist.isExpanded()) {
            playlist.setExpanded(true);
            rebuildRows();
        }
        player.play(queue, 0);
        note("playing " + playlist.name() + " · " + queue.size() + " tracks");
    }

    /** A playlist finished loading: show its tracks, and play it if that was asked for. */
    private void playlistsLoaded() {
        playlistsChanged = false;
        rebuildRows();
        Playlist waiting = pendingPlay;
        if (waiting == null) return;
        if (!library.playlists().contains(waiting)) {
            pendingPlay = null;
            return;
        }
        switch (waiting.load()) {
            case LOADED -> playPlaylist(waiting);
            case FAILED -> {
                pendingPlay = null;
                note(waiting.name() + ": " + waiting.error());
            }
            default -> { }
        }
    }

    // ----------------------------------------------------------------- mouse

    private void handleMouse(MouseEvent event) {
        if (showHelp) {
            if (event.action() == MouseEvent.Action.PRESS) showHelp = false;
            return;
        }
        if (event.action() == MouseEvent.Action.RELEASE) {
            draggingBand = -1;
            return;
        }
        if (event.action() == MouseEvent.Action.DRAG && draggingBand >= 0) {
            int gain = Ui.gainAtRow(lastHeight, event.y());
            if (gain != Integer.MIN_VALUE) player.equalizer().setGain(draggingBand, gain);
            return;
        }

        Ui.Hit hit = Ui.hitTest(lastWidth, lastHeight, event.x(), event.y());
        if (event.isWheel()) {
            handleWheel(event, hit);
            return;
        }
        if (event.button() != MouseEvent.Button.LEFT) return;
        boolean press = event.action() == MouseEvent.Action.PRESS;

        switch (hit.zone()) {
            case LIBRARY_ROW -> {
                if (press) clickLibrary(hit.index());
            }
            case NOW_PLAYING -> {
                if (press) player.togglePause();
            }
            case PROGRESS -> {
                double duration = player.duration();
                if (duration > 0) player.seek(duration * hit.value() / 1000.0);
            }
            case EQ_BAND -> {
                if (!press) return;
                focus = Focus.EQUALIZER;
                if (hit.index() >= 0) {
                    eqBand = hit.index();
                    if (hit.value() != Integer.MIN_VALUE) {
                        player.equalizer().setGain(eqBand, hit.value());
                        draggingBand = eqBand;
                    }
                }
            }
            case VOLUME -> {
                player.setVolume(hit.value());
                note("volume " + player.volume() + "%");
            }
            case NONE -> { }
        }
    }

    /** A playlist row folds on a single click; a track needs a second click to play. */
    private void clickLibrary(int rowOffset) {
        int index = scroll + rowOffset;
        if (index < 0 || index >= rows.size()) return;
        focus = Focus.LIBRARY;
        selected = index;
        Row row = rows.get(index);
        if (row.isPlaylist()) {
            togglePlaylist(row.playlist());
            lastClickRow = -1;
            return;
        }
        long now = System.currentTimeMillis();
        if (index == lastClickRow && now - lastClickAt < DOUBLE_CLICK_MS) {
            playSelected();
            lastClickRow = -1;
        } else {
            lastClickRow = index;
            lastClickAt = now;
        }
    }

    private void handleWheel(MouseEvent event, Ui.Hit hit) {
        int direction = event.button() == MouseEvent.Button.WHEEL_UP ? 1 : -1;
        switch (hit.zone()) {
            case LIBRARY_ROW -> {
                focus = Focus.LIBRARY;
                moveSelection(-direction);
            }
            case EQ_BAND -> {
                focus = Focus.EQUALIZER;
                if (hit.index() >= 0) eqBand = hit.index();
                Equalizer eq = player.equalizer();
                eq.setGain(eqBand, eq.gain(eqBand) + direction);
            }
            case VOLUME, NOW_PLAYING -> {
                player.adjustVolume(direction * 5);
                note("volume " + player.volume() + "%");
            }
            case PROGRESS -> player.seekRelative(direction * SEEK_STEP);
            case NONE -> { }
        }
    }

    // ----------------------------------------------------------------- state

    private void cyclePreset(int direction) {
        List<EqPreset> presets = EqPreset.ALL;
        int current = EqPreset.matching(player.equalizer().gains());
        int next = current < 0
                ? (direction > 0 ? 0 : presets.size() - 1)
                : (current + direction + presets.size()) % presets.size();
        EqPreset preset = presets.get(next);
        player.equalizer().apply(preset);
        player.equalizer().setEnabled(true);
        note("preset " + preset.name());
    }

    private Row currentRow() {
        if (rows.isEmpty() || selected < 0 || selected >= rows.size()) return null;
        return rows.get(selected);
    }

    private void moveSelection(int delta) {
        if (rows.isEmpty()) return;
        selected = Math.max(0, Math.min(rows.size() - 1, selected + delta));
    }

    /**
     * Switches to the newest export when there is a new one. Playlists that
     * are new, or whose link or date changed, are listed again.
     */
    private void reloadLibrary() {
        List<Playlist> stale = library.reload();
        if (stale == null) {
            if (!library.problem().isEmpty()) note(library.problem());
            return;
        }
        stale.forEach(loader::request);
        rebuildRows();
        lastClickRow = -1;
        announceLibrary();
    }

    private void announceLibrary() {
        if (!library.problem().isEmpty()) {
            note(library.problem());
        } else if (library.source() == null) {
            note("no playlist export in " + library.root());
        } else {
            int count = library.playlists().size();
            note(count + (count == 1 ? " playlist" : " playlists") + " from " + library.source().getFileName());
        }
    }

    /** Rebuilds the visible lines, keeping the cursor on the same row. */
    private void rebuildRows() {
        Row previous = currentRow();
        rows = library.rows();
        if (previous != null) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                boolean same = previous.isPlaylist()
                        ? row.isPlaylist() && row.playlist() == previous.playlist()
                        : !row.isPlaylist() && row.track() == previous.track()
                                && row.playlist() == previous.playlist();
                if (same) {
                    selected = i;
                    break;
                }
            }
        }
        if (selected >= rows.size()) selected = Math.max(0, rows.size() - 1);
    }

    private void clampSelection(int width, int height) {
        lastWidth = width;
        lastHeight = height;
        int visible = Ui.libraryRows(height);
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + visible) scroll = selected - visible + 1;
        int maxScroll = Math.max(0, rows.size() - visible);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    private void note(String text) {
        message = text;
        messageAt = System.currentTimeMillis();
    }

    public Player player() {
        return player;
    }

    public PlaylistLibrary library() {
        return library;
    }

    public List<Row> rows() {
        return rows;
    }

    public int selectedIndex() {
        return selected;
    }

    public int scrollOffset() {
        return scroll;
    }

    public Focus focus() {
        return focus;
    }

    public int eqBand() {
        return eqBand;
    }

    public boolean showHelp() {
        return showHelp;
    }

    public long tick() {
        return tick;
    }

    public String presetLabel() {
        int idx = EqPreset.matching(player.equalizer().gains());
        return idx < 0 ? "Custom" : EqPreset.ALL.get(idx).name();
    }

    public String statusMessage() {
        if (message.isEmpty() || System.currentTimeMillis() - messageAt > MESSAGE_MS) return "";
        return message;
    }

    public PlaybackState state() {
        return player.state();
    }
}
