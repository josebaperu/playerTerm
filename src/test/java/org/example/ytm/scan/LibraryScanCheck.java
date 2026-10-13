package org.example.ytm.scan;

import org.example.player.tui.Screen;
import org.example.ytm.model.Playlist;
import org.example.ytm.model.Track;
import org.example.ytm.source.TrackStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The scan decides what is missing without asking YouTube. Run with the
 * project classes on the classpath.
 */
public final class LibraryScanCheck {

    public static void main(String[] args) throws Exception {
        progress();
        fillsTheGaps();
        stopsBetweenTracks();
        screenShowsTheBarAndTheLog();
        System.out.println("scan ok");
    }

    private static void progress() {
        ScanModel model = new ScanModel();
        model.prepare("~/Music", "a.json", 2);
        checkNear(model.snapshot().progress(), 0, "before albums");
        model.beginAlbum(1, "A");
        model.activity(ScanModel.CHECKING, "t", 1, 4);
        checkNear(model.snapshot().progress(), 0.125, "first album, one of four");
        model.activity(ScanModel.CHECKING, "t", 4, 4);
        model.endAlbum();
        checkNear(model.snapshot().progress(), 0.5, "first album closed");
        model.beginAlbum(2, "B");
        model.activity(ScanModel.DOWNLOADING, "t", 1, 2);
        checkNear(model.snapshot().progress(), 0.75, "second album half way");
        model.endAlbum();
        model.finish();
        ScanModel.Snapshot done = model.snapshot();
        check(done.progress() == 1, "finished progress");
        check(done.status() == ScanModel.Status.DONE, "finished status");
        check(text(done).contains("downloaded"), "summary line");
    }

    private static void fillsTheGaps() throws Exception {
        Path lists = Files.createTempDirectory("scan-lists-");
        Path music = Files.createTempDirectory("scan-music-");
        try {
            Files.writeString(lists.resolve("playlists.json"), """
                    [
                      {"id":"broken","title":"Broken","url":"https://music.youtube.com/playlist?list=broken","updatedDate":1},
                      {"id":"empty","title":"Empty","url":"https://music.youtube.com/playlist?list=empty","updatedDate":2},
                      {"id":"morning","title":"Morning","url":"https://music.youtube.com/playlist?list=morning","updatedDate":3},
                      {"id":"night","title":"Night Drive","url":"https://music.youtube.com/playlist?list=night","updatedDate":4}
                    ]
                    """);
            Path morning = music.resolve(TrackStore.albumDirectory("Morning"));
            Files.createDirectories(morning);
            Files.writeString(morning.resolve("leftover.opus"), "not a real track, just a name");

            List<String> got = new ArrayList<>();
            FakeArchive archive = new FakeArchive(music, got);
            Map<String, List<Track>> remote = Map.of(
                    "morning", List.of(track("t3", "Keep", "Morning"), track("t4", "Need", "Morning")),
                    "night", List.of(track("t1", "Blue", "Night Drive"), track("t2", "Red", "Night Drive")),
                    "empty", List.of());
            ScanModel model = new ScanModel();
            new LibraryScanner(lists, model, new FakeLister(remote), archive).scan();

            check(got.equals(List.of("t4:2/2", "t1:1/2", "t2:2/2")), got.toString());
            check(Files.isDirectory(music.resolve("Empty")), "empty album folder");
            check(Files.isDirectory(music.resolve("Night Drive")), "created night drive");
            check(Files.isDirectory(morning), "morning stayed");
            check(!Files.exists(music.resolve("Broken")), "failed listing created a folder");
            ScanModel.Snapshot snap = model.snapshot();
            check(snap.status() == ScanModel.Status.DONE, snap.status().name());
            check(snap.progress() == 1, "scan progress");
            check(snap.onDisk() == 1, "on disk " + snap.onDisk());
            check(snap.downloaded() == 2, "downloaded " + snap.downloaded());
            check(snap.failed() == 2, "failed " + snap.failed());
            check(snap.foldersCreated() == 2, "folders " + snap.foldersCreated());
            String log = text(snap);
            check(log.contains("created Empty"), log);
            check(log.contains("created Night Drive"), log);
            check(log.contains("1 of 2 on disk, 1 missing"), log);
            check(log.contains("0 of 2 on disk, 2 missing"), log);
            check(log.contains("1 other file left in the folder"), log);
            check(log.contains("could not list: offline"), log);
            check(log.contains("no audio"), log);
            check(log.contains("saved Need"), log);
            check(log.contains("saved Blue"), log);
            check(log.contains("no tracks"), log);
        } finally {
            deleteTree(lists);
            deleteTree(music);
        }
    }

    /** A cancel asked for while one track is saving does not start the next download. */
    private static void stopsBetweenTracks() throws Exception {
        Path lists = Files.createTempDirectory("scan-stop-lists-");
        Path music = Files.createTempDirectory("scan-stop-music-");
        try {
            Files.writeString(lists.resolve("one.json"), """
                    [
                      {"id":"night","title":"Night Drive","url":"https://music.youtube.com/playlist?list=night","updatedDate":4}
                    ]
                    """);
            int[] calls = {0};
            LibraryScanner[] scanner = new LibraryScanner[1];
            LibraryScanner.MusicArchive archive = new LibraryScanner.MusicArchive() {
                @Override
                public Path root() {
                    return music;
                }

                @Override
                public Path existing(Track track) {
                    return null;
                }

                @Override
                public Path ensure(Track track, int number, int total) {
                    calls[0]++;
                    scanner[0].cancel();
                    return music.resolve(track.id());
                }

                @Override
                public String problem() {
                    return "";
                }
            };
            ScanModel model = new ScanModel();
            scanner[0] = new LibraryScanner(lists, model, new FakeLister(Map.of(
                    "night", List.of(track("t1", "Blue", "Night Drive"), track("t2", "Red", "Night Drive")))), archive);
            scanner[0].scan();
            check(calls[0] == 1, "downloads after stop " + calls[0]);
            check(model.status() == ScanModel.Status.CANCELLED, model.status().name());
            check(text(model.snapshot()).contains("stopped"), "stopped log");
        } finally {
            deleteTree(lists);
            deleteTree(music);
        }
    }

    private static void screenShowsTheBarAndTheLog() {
        ScanModel mid = new ScanModel();
        mid.prepare("~/Music", "export.json", 1);
        mid.beginAlbum(1, "Night Drive");
        mid.activity(ScanModel.DOWNLOADING, "Blue", 1, 2);
        mid.log(ScanModel.Level.INFO, "Night Drive", "downloading Blue");
        Screen screen = new Screen();
        screen.resize(80, 24);
        ScanUi.render(screen, mid.snapshot(), 0, 0, false);
        String frame = frame(screen);
        check(frame.contains("library scan"), "title");
        check(frame.contains("Night Drive"), "album");
        check(frame.contains("downloading"), "action");
        check(frame.contains("Blue"), "track");
        check(frame.contains("50%"), frame);
        check(frame.contains("█"), "filled bar");
        check(frame.contains("░"), "empty bar");
        check(frame.contains("q stop"), "stop key");

        mid.endAlbum();
        mid.finish();
        ScanUi.render(screen, mid.snapshot(), 0, 0, false);
        String done = frame(screen);
        check(done.contains("100%"), done);
        check(done.contains("q close"), "close key");
        check(done.contains("DONE"), "done badge");
        check(done.contains("downloaded"), "done log");
    }

    private static Track track(String id, String title, String album) {
        return new Track(id, "https://music.youtube.com/watch?v=" + id, title, "Ada", album, 12);
    }

    private static String text(ScanModel.Snapshot snap) {
        StringBuilder sb = new StringBuilder();
        for (ScanModel.LogLine line : snap.log()) sb.append(line.message()).append('\n');
        return sb.toString();
    }

    private static String frame(Screen screen) {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < screen.height(); y++) sb.append(screen.row(y)).append('\n');
        return sb.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The temp directory is still removed on the next boot.
                }
            });
        }
    }

    private static void checkNear(double value, double expect, String message) {
        check(Math.abs(value - expect) < 0.0001, message + " was " + value);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static final class FakeLister implements LibraryScanner.Lister {
        private final Map<String, List<Track>> tracks;

        private FakeLister(Map<String, List<Track>> tracks) {
            this.tracks = tracks;
        }

        @Override
        public LibraryScanner.Listing list(Playlist playlist) throws IOException {
            if ("broken".equals(playlist.id())) throw new IOException("offline");
            return new LibraryScanner.Listing(tracks.getOrDefault(playlist.id(), List.of()), false);
        }

        @Override
        public void retain(List<Playlist> playlists) {
        }
    }

    private static final class FakeArchive implements LibraryScanner.MusicArchive {
        private final Path root;
        private final List<String> got;

        private FakeArchive(Path root, List<String> got) {
            this.root = root;
            this.got = got;
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public Path existing(Track track) {
            return "t3".equals(track.id()) ? root.resolve("kept") : null;
        }

        @Override
        public Path ensure(Track track, int number, int total) {
            got.add(track.id() + ":" + number + "/" + total);
            if ("t2".equals(track.id())) return null;
            return root.resolve(track.id());
        }

        @Override
        public String problem() {
            return "no audio";
        }
    }
}
