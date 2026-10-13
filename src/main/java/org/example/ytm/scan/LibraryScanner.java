package org.example.ytm.scan;

import org.example.ytm.model.Playlist;
import org.example.ytm.model.PlaylistLibrary;
import org.example.ytm.model.Track;
import org.example.ytm.source.PlaylistCache;
import org.example.ytm.source.TrackStore;
import org.example.ytm.source.YtDlp;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks the playlist export, compares each album folder in the music library,
 * and downloads tracks that are not already there. One album at a time, so
 * the screen can show which track is in flight. {@code q} interrupts it.
 */
public final class LibraryScanner implements AutoCloseable {

    /** A playlist's tracks, and whether they came from the saved listing. */
    record Listing(List<Track> tracks, boolean fromCache) {
    }

    /** Reads a playlist. The real one uses the listing cache, then yt-dlp. */
    interface Lister {
        Listing list(Playlist playlist) throws IOException, InterruptedException;

        void retain(List<Playlist> playlists);
    }

    /** The music folder. {@link TrackStore} is the real one; tests substitute their own. */
    interface MusicArchive {
        Path root();

        Path existing(Track track);

        Path ensure(Track track, int number, int total) throws InterruptedException;

        String problem();
    }

    private final Path playlistsRoot;
    private final ScanModel model;
    private final Lister lister;
    private final MusicArchive archive;
    private final AutoCloseable onClose;
    private volatile boolean cancel;
    private Thread thread;

    /** Starts the scan on a background thread so the screen stays live. */
    public static LibraryScanner start(Path playlistsRoot, Path musicRoot, ScanModel model) {
        TrackStore store = new TrackStore(musicRoot);
        LibraryScanner scanner = new LibraryScanner(playlistsRoot, model, new CacheLister(), new TrackArchive(store), store);
        Thread worker = new Thread(scanner::scan, "library-scan");
        worker.setDaemon(true);
        scanner.thread = worker;
        worker.start();
        return scanner;
    }

    LibraryScanner(Path playlistsRoot, ScanModel model, Lister lister, MusicArchive archive) {
        this(playlistsRoot, model, lister, archive, null);
    }

    private LibraryScanner(Path playlistsRoot, ScanModel model, Lister lister, MusicArchive archive, AutoCloseable onClose) {
        this.playlistsRoot = playlistsRoot;
        this.model = model;
        this.lister = lister;
        this.archive = archive;
        this.onClose = onClose;
    }

    /** Asks the scan to stop. A download in flight is killed with the thread. */
    public void cancel() {
        cancel = true;
        Thread worker = thread;
        if (worker != null) worker.interrupt();
    }

    @Override
    public void close() {
        cancel();
        Thread worker = thread;
        if (worker != null) {
            try {
                worker.join(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (onClose != null) {
            try {
                onClose.close();
            } catch (Exception ignored) {
                // The scan has already stopped.
            }
        }
    }

    void scan() {
        try {
            if (stopped()) {
                model.cancelled();
                return;
            }
            runAlbums();
            if (stopped()) model.cancelled();
            else model.finish();
        } catch (InterruptedException e) {
            model.cancelled();
        } catch (Exception e) {
            String message = e.getMessage();
            model.fail(brief(message == null || message.isBlank() ? e.getClass().getSimpleName() : message));
        }
    }

    private void runAlbums() throws InterruptedException {
        Path music = archive.root();
        if (Files.exists(music) && !Files.isDirectory(music)) {
            model.fail(ScanModel.displayPath(music) + " is not a directory");
            return;
        }
        PlaylistLibrary library = new PlaylistLibrary(playlistsRoot);
        library.reload();
        if (!library.problem().isEmpty() && library.playlists().isEmpty()) {
            model.fail(library.problem());
            return;
        }
        String export = library.source() == null ? "" : ScanModel.displayPath(library.source());
        List<Playlist> playlists = library.playlists();
        model.prepare(ScanModel.displayPath(music), export, playlists.size());
        if (playlists.isEmpty()) {
            model.log(ScanModel.Level.INFO, "", "no playlist export in " + ScanModel.displayPath(playlistsRoot));
            return;
        }
        lister.retain(playlists);
        model.log(ScanModel.Level.INFO, "", plural(playlists.size(), "playlist", "playlists"));
        int index = 0;
        for (Playlist playlist : playlists) {
            if (stopped()) {
                model.cancelled();
                return;
            }
            index++;
            scanAlbum(playlist, index);
        }
    }

    private void scanAlbum(Playlist playlist, int index) throws InterruptedException {
        String name = playlist.name();
        model.beginAlbum(index, name);
        Listing listing;
        try {
            listing = lister.list(playlist);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage();
            model.log(ScanModel.Level.ERROR, name, "could not list: " + brief(message == null ? "failed" : message));
            model.count(ScanModel.Count.FAILED);
            model.endAlbum();
            return;
        }
        List<Track> tracks = listing.tracks() == null ? List.of() : listing.tracks();
        playlist.setTracks(tracks);
        tracks = playlist.tracks();
        model.activity(ScanModel.LISTING, "", 0, tracks.size());
        model.log(ScanModel.Level.INFO, name, listing.fromCache()
                ? "saved listing, " + plural(tracks.size(), "track", "tracks")
                : "listed " + plural(tracks.size(), "track", "tracks"));

        String dirName = TrackStore.albumDirectory(name);
        Path base = archive.root().toAbsolutePath().normalize();
        Path dir = base.resolve(dirName).normalize();
        if (dir.equals(base) || !dir.startsWith(base)) {
            model.log(ScanModel.Level.ERROR, name, "album folder escapes the music folder");
            model.count(ScanModel.Count.FAILED);
            model.endAlbum();
            return;
        }

        List<Track> missing = new ArrayList<>();
        int done = 0;
        for (Track track : tracks) {
            if (stopped()) {
                model.cancelled();
                return;
            }
            model.activity(ScanModel.CHECKING, track.displayName(), done, tracks.size());
            if (archive.existing(track) != null) {
                done++;
                model.count(ScanModel.Count.ON_DISK);
                model.activity(ScanModel.ON_DISK, track.displayName(), done, tracks.size());
            } else {
                missing.add(track);
            }
        }

        boolean created = false;
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
                created = true;
                model.count(ScanModel.Count.FOLDER);
                model.log(ScanModel.Level.OK, name, "created " + dirName);
            } catch (IOException e) {
                model.log(ScanModel.Level.ERROR, name, "could not create " + dirName + ": " + brief(e.getMessage()));
                model.count(ScanModel.Count.FAILED);
                model.endAlbum();
                return;
            }
        }
        if (tracks.isEmpty()) {
            model.log(ScanModel.Level.INFO, name, "no tracks");
        } else if (missing.isEmpty()) {
            model.log(ScanModel.Level.OK, name, plural(tracks.size(), "track", "tracks") + " on disk");
        } else {
            int present = tracks.size() - missing.size();
            model.log(ScanModel.Level.WARN, name, present + " of " + tracks.size() + " on disk, "
                    + plural(missing.size(), "missing", "missing"));
        }
        if (!created) {
            int extras = otherFiles(dir, tracks);
            if (extras > 0) {
                model.log(ScanModel.Level.WARN, name, plural(extras, "other file", "other files") + " left in the folder");
            }
        }

        Set<String> missingIds = new HashSet<>();
        for (Track track : missing) missingIds.add(track.id());
        int number = 0;
        for (Track track : tracks) {
            number++;
            if (!missingIds.contains(track.id())) continue;
            if (stopped()) {
                model.cancelled();
                return;
            }
            model.activity(ScanModel.DOWNLOADING, track.displayName(), done, tracks.size());
            model.log(ScanModel.Level.INFO, name, "downloading " + track.displayName());
            Path saved;
            try {
                saved = archive.ensure(track, number, tracks.size());
            } catch (InterruptedException e) {
                throw e;
            }
            done++;
            if (saved == null) {
                model.count(ScanModel.Count.FAILED);
                String why = brief(archive.problem());
                if (why.isEmpty()) why = "could not save " + track.displayName();
                model.log(ScanModel.Level.ERROR, name, why);
                model.activity(ScanModel.FAILED, track.displayName(), done, tracks.size());
            } else {
                model.count(ScanModel.Count.DOWNLOADED);
                model.log(ScanModel.Level.OK, name, "saved " + track.displayName());
                model.activity(ScanModel.SAVED, track.displayName(), done, tracks.size());
            }
        }
        model.endAlbum();
    }

    /** Opus files in the folder whose names belong to none of the remote tracks. */
    private static int otherFiles(Path dir, List<Track> tracks) {
        if (!Files.isDirectory(dir)) return 0;
        int extras = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.opus")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                boolean known = false;
                for (Track track : tracks) {
                    if (TrackStore.isSaveName(track, file)) {
                        known = true;
                        break;
                    }
                }
                if (!known) extras++;
            }
        } catch (IOException e) {
            return extras;
        }
        return extras;
    }

    private boolean stopped() {
        if (Thread.interrupted()) cancel = true;
        return cancel;
    }

    private static String plural(int n, String singular, String plural) {
        if (n == 1 && !singular.equals(plural)) return "1 " + singular;
        return n + " " + plural;
    }

    private static String brief(String message) {
        if (message == null || message.isBlank()) return "";
        String cleaned = message.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) : cleaned;
    }

    private static final class CacheLister implements Lister {
        private final PlaylistCache cache = new PlaylistCache(PlaylistCache.defaultDirectory());

        @Override
        public Listing list(Playlist playlist) throws IOException, InterruptedException {
            List<Track> saved = cache.load(playlist);
            if (saved != null) return new Listing(saved, true);
            List<Track> tracks = YtDlp.listPlaylist(playlist.url(), playlist.name());
            cache.store(playlist.id(), playlist.url(), playlist.updatedDate(), tracks);
            return new Listing(tracks, false);
        }

        @Override
        public void retain(List<Playlist> playlists) {
            cache.retain(playlists);
        }
    }

    private static final class TrackArchive implements MusicArchive {
        private final TrackStore store;

        private TrackArchive(TrackStore store) {
            this.store = store;
        }

        @Override
        public Path root() {
            return store.root();
        }

        @Override
        public Path existing(Track track) {
            return store.existing(track);
        }

        @Override
        public Path ensure(Track track, int number, int total) throws InterruptedException {
            return store.ensure(track, number, total);
        }

        @Override
        public String problem() {
            return store.problem();
        }
    }
}
