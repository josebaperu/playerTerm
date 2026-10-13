package org.example.ytm.scan;

import org.example.player.tui.Screen;
import org.example.player.tui.Style;
import org.example.player.ui.Theme;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** The library scan: one summary, a progress bar, and the log of what it did. */
public final class ScanUi {

    public static final int MIN_WIDTH = 56;
    public static final int MIN_HEIGHT = 16;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record Layout(int summaryTop, int summaryH, int logTop, int logH) {
        int logRows() {
            return Math.max(1, logH - 2);
        }
    }

    private ScanUi() {
    }

    /** Rows of the log that fit, so the keys can scroll it. */
    public static int logRows(int height) {
        if (height < MIN_HEIGHT) return 1;
        return layout(height).logRows();
    }

    public static void render(Screen s, ScanModel.Snapshot snap, int tick, int logOffset, boolean stopping) {
        s.clear();
        int w = s.width();
        int h = s.height();
        if (w < MIN_WIDTH || h < MIN_HEIGHT) {
            drawTooSmall(s, w, h);
            return;
        }
        Layout layout = layout(h);
        drawHeader(s, snap, w, tick, stopping);
        drawSummary(s, snap, 0, layout.summaryTop, w, layout.summaryH, tick);
        if (layout.logH >= 3) drawLog(s, snap, 0, layout.logTop, w, layout.logH, logOffset);
        drawFooter(s, snap, h - 1, w, stopping);
    }

    private static Layout layout(int height) {
        int body = Math.max(0, height - 3);
        int summary = Math.min(9, Math.max(7, body * 4 / 10));
        int logH = body - summary;
        if (logH < 4 && body >= 4) {
            logH = Math.min(4, body);
            summary = body - logH;
        }
        if (summary < 0) summary = 0;
        return new Layout(2, summary, 2 + summary, logH);
    }

    private static void drawTooSmall(Screen s, int w, int h) {
        String msg = "Terminal too small";
        String need = MIN_WIDTH + "x" + MIN_HEIGHT + " minimum, now " + w + "x" + h;
        s.put(Math.max(0, (w - msg.length()) / 2), Math.max(0, h / 2 - 1), msg, Theme.ALERT);
        s.put(Math.max(0, (w - need.length()) / 2), Math.max(0, h / 2), need, Theme.MUTED);
    }

    private static void drawHeader(Screen s, ScanModel.Snapshot snap, int w, int tick, boolean stopping) {
        int x = s.put(1, 0, "◉", Theme.GOLD_BOLD);
        x = s.put(x + 1, 0, "PLAYER YTM", Style.bold(Style.fg(Theme.C_WHITE)));
        s.put(x + 1, 0, "library scan", Theme.FAINT);

        String clock = LocalTime.now().format(CLOCK);
        String badge = badge(snap, tick, stopping);
        int right = w - 1 - clock.length();
        s.put(right, 0, clock, Theme.FAINT);
        right -= badge.length() + 2;
        s.put(Math.max(1, right), 0, badge, badgeStyle(snap.status()));
        s.hline(0, 1, w, '─', Theme.LINE);
    }

    private static String badge(ScanModel.Snapshot snap, int tick, boolean stopping) {
        String spin = switch ((tick / 4) % 4) {
            case 0 -> "◌";
            case 1 -> "◐";
            case 2 -> "◑";
            default -> "◒";
        };
        return switch (snap.status()) {
            case RUNNING -> (stopping ? spin + " STOPPING" : spin + " SCANNING");
            case DONE -> "✔ DONE";
            case CANCELLED -> "■ STOPPED";
            case FAILED -> "✖ FAILED";
        };
    }

    private static int badgeStyle(ScanModel.Status status) {
        return switch (status) {
            case RUNNING -> Theme.GOLD;
            case DONE -> Theme.LIVE;
            case FAILED -> Theme.ALERT;
            case CANCELLED -> Theme.MUTED;
        };
    }

    private static void drawSummary(Screen s, ScanModel.Snapshot snap, int x, int y, int w, int h, int tick) {
        s.box(x, y, w, h, "SCAN", Theme.ACCENT, Theme.ACCENT_BOLD);
        int innerTop = y + 1;
        int innerH = h - 2;
        int innerX = x + 2;
        int innerW = w - 4;
        if (innerH <= 0 || innerW <= 0) return;

        boolean bar = innerH >= 1;
        int textRows = bar ? innerH - 1 : innerH;
        List<Line> lines = summaryLines(snap, textRows);
        for (int i = 0; i < lines.size() && i < textRows; i++) {
            Line line = lines.get(i);
            s.putClipped(innerX, innerTop + i, line.text, innerW, line.style);
        }
        if (bar) drawProgress(s, snap, innerX, innerTop + innerH - 1, innerW, tick);
    }

    private record Line(String text, int style) {
    }

    /** Paths first, then the work. A short panel keeps the bottom lines, which are the album and the track. */
    private static List<Line> summaryLines(ScanModel.Snapshot snap, int rows) {
        List<Line> lines = new ArrayList<>();
        if (!snap.music().isEmpty()) lines.add(new Line("Music   " + snap.music(), Theme.MUTED));
        if (!snap.exportName().isEmpty()) lines.add(new Line("Export  " + snap.exportName(), Theme.MUTED));
        lines.add(new Line("on disk " + snap.onDisk()
                + "    downloaded " + snap.downloaded()
                + "    failed " + snap.failed()
                + "    new folders " + snap.foldersCreated(), Theme.TEXT));
        lines.add(new Line(headline(snap), Style.bold(Style.fg(Theme.C_WHITE))));
        String detail = detail(snap);
        if (!detail.isEmpty()) lines.add(new Line(detail, detailStyle(snap)));
        if (!snap.problem().isEmpty() && snap.status() == ScanModel.Status.FAILED) {
            lines.add(new Line(snap.problem(), Theme.ALERT));
        }
        if (lines.size() <= rows) return lines;
        return new ArrayList<>(lines.subList(lines.size() - rows, lines.size()));
    }

    private static String headline(ScanModel.Snapshot snap) {
        if (!snap.album().isEmpty() && snap.albumCount() > 0) {
            return snap.albumIndex() + "/" + snap.albumCount() + "  " + snap.album();
        }
        if (!snap.action().isEmpty()) return snap.action();
        return switch (snap.status()) {
            case DONE -> "Finished";
            case CANCELLED -> "Stopped";
            case FAILED -> "Failed";
            case RUNNING -> "Library scan";
        };
    }

    private static String detail(ScanModel.Snapshot snap) {
        if (snap.track().isEmpty() && snap.action().isEmpty()) return "";
        String label = snap.track().isEmpty() ? snap.action() : snap.action() + "  " + snap.track();
        if (snap.albumTrackTotal() > 0 && !snap.album().isEmpty()) {
            label = snap.albumTrackDone() + "/" + snap.albumTrackTotal() + "  " + label;
        }
        return label;
    }

    private static int detailStyle(ScanModel.Snapshot snap) {
        return switch (snap.action()) {
            case ScanModel.DOWNLOADING, ScanModel.LISTING, ScanModel.STARTING, ScanModel.CHECKING -> Theme.GOLD;
            case ScanModel.SAVED, ScanModel.ON_DISK -> Style.fg(Theme.C_LIVE);
            case ScanModel.FAILED -> Theme.ALERT;
            default -> Theme.TEXT;
        };
    }

    private static void drawProgress(Screen s, ScanModel.Snapshot snap, int x, int y, int w, int tick) {
        if (w < 8) return;
        int pctW = 4;
        int barW = w - pctW - 1;
        double progress = Math.max(0, Math.min(1, snap.progress()));
        int filled = (int) Math.round(progress * barW);
        boolean moving = snap.status() == ScanModel.Status.RUNNING
                && (ScanModel.LISTING.equals(snap.action())
                || ScanModel.DOWNLOADING.equals(snap.action())
                || ScanModel.CHECKING.equals(snap.action())
                || ScanModel.STARTING.equals(snap.action()));
        int marker = moving ? (tick / 2) % barW : -1;
        for (int i = 0; i < barW; i++) {
            boolean on = i < filled;
            char c = on ? '█' : '░';
            int style = on ? Style.fg(Theme.C_LIVE) : Theme.FAINT;
            if (i == marker) {
                c = on ? '▓' : '▒';
                style = Theme.GOLD;
            }
            s.set(x + i, y, c, style);
        }
        int percent = (int) Math.round(progress * 100);
        s.put(x + barW + 1, y, String.format("%3d%%", percent), Theme.GOLD);
    }

    private static void drawLog(Screen s, ScanModel.Snapshot snap, int x, int y, int w, int h, int logOffset) {
        s.box(x, y, w, h, "LOG", Theme.LINE, Theme.MUTED);
        int rows = h - 2;
        int innerX = x + 2;
        int innerW = w - 4;
        if (rows <= 0 || innerW <= 0) return;
        List<ScanModel.LogLine> log = snap.log();
        if (log.isEmpty()) {
            s.putClipped(innerX, y + 1, "Albums and downloads are logged here.", innerW, Theme.FAINT);
            return;
        }
        int offset = Math.max(0, Math.min(logOffset, Math.max(0, log.size() - rows)));
        int end = log.size() - offset;
        int start = Math.max(0, end - rows);
        for (int i = start; i < end; i++) {
            drawLogLine(s, log.get(i), innerX, y + 1 + (i - start), innerW);
        }
    }

    private static void drawLogLine(Screen s, ScanModel.LogLine line, int x, int y, int w) {
        int col = s.put(x, y, line.time(), Theme.FAINT);
        col += 2;
        if (!line.album().isEmpty() && w > 24) {
            int albumW = Math.min(22, Math.max(8, (w - 12) / 3));
            s.putClipped(col, y, line.album(), albumW, Theme.MUTED);
            col = x + 8 + 2 + albumW + 2;
        }
        int remain = x + w - col;
        s.putClipped(col, y, line.message(), remain, logStyle(line.level()));
    }

    private static int logStyle(ScanModel.Level level) {
        return switch (level) {
            case OK -> Style.fg(Theme.C_LIVE);
            case WARN -> Theme.GOLD;
            case ERROR -> Theme.ALERT;
            case INFO -> Theme.TEXT;
        };
    }

    private static void drawFooter(Screen s, ScanModel.Snapshot snap, int y, int w, boolean stopping) {
        String keys = snap.status() == ScanModel.Status.RUNNING && !stopping
                ? "q stop     ↑↓ log"
                : "q close    ↑↓ log";
        s.put(1, y, keys, Theme.FAINT);
        String hint = snap.status() == ScanModel.Status.RUNNING && !stopping
                ? "missing tracks are downloaded"
                : "";
        if (!hint.isEmpty() && w > keys.length() + hint.length() + 6) {
            s.put(w - 1 - hint.length(), y, hint, Theme.FAINT);
        }
    }
}
