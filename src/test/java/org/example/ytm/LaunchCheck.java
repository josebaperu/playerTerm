package org.example.ytm;

/**
 * {@code --scan} selects the library scan. No arguments leave the player as it is.
 * Run with the project classes on the classpath.
 */
public final class LaunchCheck {

    public static void main(String[] args) throws Exception {
        YtmApp.Launch none = YtmApp.Launch.parse(new String[]{});
        check(none.exit() < 0, "default exit");
        check(!none.scan(), "default is the player");
        check(!none.mouseOff(), "mouse stays as configured");
        check(none.playlistsPath() == null, "no folder");

        YtmApp.Launch scan = YtmApp.Launch.parse(new String[]{"--scan", "--playlists", "/tmp/exports"});
        check(scan.exit() < 0 && scan.scan(), "scan flag");
        check("/tmp/exports".equals(scan.playlistsPath()), scan.playlistsPath());

        YtmApp.Launch both = YtmApp.Launch.parse(new String[]{"/tmp/y", "--no-mouse", "--scan"});
        check(both.scan() && both.mouseOff(), "scan with the other options");
        check("/tmp/y".equals(both.playlistsPath()), both.playlistsPath());

        YtmApp.Launch bad = YtmApp.Launch.parse(new String[]{"--scanx"});
        check(bad.exit() == 2 && !bad.scan(), "unknown option");

        org.example.ytm.scan.LibraryScanCheck.main(args);
        System.out.println("ok");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
