package org.example.player.model;

import org.example.player.audio.TagReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Album order comes from album.m3u, and a missing file is built from track numbers.
 * Run with the project classes on the classpath.
 */
public final class AlbumPlaylistCheck {

    public static void main(String[] args) throws Exception {
        hiddenUntilThePlaylistExists();
        roundTripFollowsTheFile();
        editedPlaylistIsKept();
        newFileIsAppendedOnReload();
        parachutesTrackNumbers();
        System.out.println("ok");
    }

    /** An expanded folder draws no track rows until album.m3u has been applied. */
    private static void hiddenUntilThePlaylistExists() throws Exception {
        Path root = Files.createTempDirectory("album-hidden");
        try {
            Path album = root.resolve("Album");
            Files.createDirectories(album);
            Files.writeString(album.resolve("c.opus"), "");
            Files.writeString(album.resolve("a.opus"), "");
            MusicLibrary library = new MusicLibrary(root);
            MusicFolder folder = library.tree().children().get(0);
            folder.setExpanded(true);
            List<Row> rows = library.rows();
            check(rows.size() == 1 && rows.get(0).isFolder(), "tracks hidden before album.m3u");
            check(!folder.isOrderReady(), "order not ready");
        } finally {
            delete(root);
        }
    }

    /** Writing from track numbers, then reading the file, beats filename order. */
    private static void roundTripFollowsTheFile() throws Exception {
        Path root = Files.createTempDirectory("album-order");
        try {
            Path album = root.resolve("Album");
            Files.createDirectories(album);
            Files.writeString(album.resolve("a.opus"), "");
            Files.writeString(album.resolve("b.opus"), "");
            Files.writeString(album.resolve("c.opus"), "");
            MusicLibrary library = new MusicLibrary(root);
            MusicFolder folder = library.tree().children().get(0);
            for (Track track : folder.tracks()) {
                int number = switch (track.fileName()) {
                    case "b" -> 1;
                    case "c" -> 2;
                    case "a" -> 3;
                    default -> 0;
                };
                track.applyTags(track.fileName(), "Artist", "Album", number, 12);
            }
            check(names(folder).equals(List.of("a", "b", "c")), "scan order " + names(folder));

            CountDownLatch done = new CountDownLatch(1);
            TagReader tags = new TagReader();
            try {
                tags.writeAlbumOrder(folder, done::countDown);
                check(done.await(5, TimeUnit.SECONDS), "playlist write");
            } finally {
                tags.close();
            }
            check(AlbumPlaylist.exists(folder), "album.m3u created");
            check(names(folder).equals(List.of("a", "b", "c")), "list stays hidden from the writer");

            String body = Files.readString(AlbumPlaylist.file(folder), StandardCharsets.UTF_8);
            check(body.startsWith("#EXTM3U\n"), "extended header");
            check(body.contains("b.opus\n") && body.indexOf("b.opus") < body.indexOf("c.opus")
                    && body.indexOf("c.opus") < body.indexOf("a.opus"), "file sequence\n" + body);

            AlbumPlaylist.apply(folder);
            folder.setOrderReady(true);
            folder.setExpanded(true);
            check(names(folder).equals(List.of("b", "c", "a")), "applied " + names(folder));
            List<String> shown = new ArrayList<>();
            for (Row row : library.rows()) {
                if (!row.isFolder()) shown.add(row.track().fileName());
            }
            check(shown.equals(List.of("b", "c", "a")), "rendered " + shown);
        } finally {
            delete(root);
        }
    }

    /** A playlist that already exists is read as-is, including a hand-edited order. */
    private static void editedPlaylistIsKept() throws Exception {
        Path root = Files.createTempDirectory("album-edit");
        try {
            Path album = root.resolve("Album");
            Files.createDirectories(album);
            Files.writeString(album.resolve("a.opus"), "");
            Files.writeString(album.resolve("b.opus"), "");
            Files.writeString(album.resolve("c.opus"), "");
            MusicLibrary library = new MusicLibrary(root);
            MusicFolder folder = library.tree().children().get(0);
            for (Track track : folder.tracks()) {
                track.applyTags(track.fileName(), "", "", 1, 1);
            }
            Files.writeString(album.resolve("album.m3u"), """
                    #EXTM3U
                    #EXTINF:1,first
                    sub/c.opus
                    a.opus
                    b.opus
                    """);
            AlbumPlaylist.apply(folder);
            check(names(folder).equals(List.of("c", "a", "b")), "edited order " + names(folder));

            String before = Files.readString(album.resolve("album.m3u"));
            CountDownLatch done = new CountDownLatch(1);
            TagReader tags = new TagReader();
            try {
                tags.writeAlbumOrder(folder, done::countDown);
                check(done.await(5, TimeUnit.SECONDS), "second open");
            } finally {
                tags.close();
            }
            check(Files.readString(album.resolve("album.m3u")).equals(before), "existing album.m3u left in place");
        } finally {
            delete(root);
        }
    }

    /** A file added later keeps its place after the playlist entries. */
    private static void newFileIsAppendedOnReload() throws Exception {
        Path root = Files.createTempDirectory("album-reload");
        try {
            Path album = root.resolve("Album");
            Files.createDirectories(album);
            Files.writeString(album.resolve("a.opus"), "");
            Files.writeString(album.resolve("b.opus"), "");
            MusicLibrary library = new MusicLibrary(root);
            MusicFolder folder = library.tree().children().get(0);
            Files.writeString(album.resolve("album.m3u"), "#EXTM3U\nb.opus\na.opus\n");
            AlbumPlaylist.apply(folder);
            folder.setOrderReady(true);
            Files.writeString(album.resolve("c.opus"), "");
            library.reload();
            check(names(folder).equals(List.of("b", "a", "c")), "reload order " + names(folder));
        } finally {
            delete(root);
        }
    }

    /** Real album tags: Parachutes is not alphabetical. */
    private static void parachutesTrackNumbers() {
        Path album = Path.of(System.getProperty("user.home"), "Music", "Coldplay - Parachutes");
        if (!Files.isDirectory(album)) return;
        List<Track> tracks = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(album, "*.opus")) {
            for (Path file : stream) tracks.add(new Track(file));
        } catch (Exception e) {
            return;
        }
        if (tracks.size() < 5) return;
        tracks.sort(Comparator.comparing(track -> track.path().getFileName().toString(), MusicLibrary::compareNatural));
        for (Track track : tracks) TagReader.read(track);
        List<String> titles = new ArrayList<>();
        for (Track track : AlbumPlaylist.sequenced(tracks)) titles.add(track.title());
        check(titles.indexOf("Don't Panic") == 0, "opener " + titles);
        check(titles.indexOf("Don't Panic") < titles.indexOf("Shiver"), "Don't Panic before Shiver " + titles);
        check(titles.indexOf("Shiver") < titles.indexOf("Yellow"), "Shiver before Yellow " + titles);
        check(titles.indexOf("Yellow") < titles.indexOf("Trouble"), "Yellow before Trouble " + titles);
        check(titles.get(titles.size() - 1).startsWith("Everything's Not Lost"), "closer " + titles);
    }

    private static List<String> names(MusicFolder folder) {
        List<String> names = new ArrayList<>();
        for (Track track : folder.tracks()) names.add(track.fileName());
        return names;
    }

    private static void delete(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best-effort cleanup of the temp library.
                }
            });
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
