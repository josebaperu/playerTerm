package org.example.player.ui;

import org.example.player.PlayerApp;
import org.example.player.audio.Equalizer;
import org.example.player.audio.FormatInfo;
import org.example.player.audio.PlaybackState;
import org.example.player.audio.Player;
import org.example.player.model.MusicFolder;
import org.example.player.model.Row;
import org.example.player.model.Track;
import org.example.player.tui.Screen;
import org.example.player.tui.Style;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Draws the whole interface into the screen buffer, once per frame. */
public final class Ui {

    private static final String EIGHTHS = " ▁▂▃▄▅▆▇█";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final int MIN_WIDTH = 62;
    public static final int MIN_HEIGHT = 18;
    private static final int VOLUME_BAR_X = 5;
    private static final int VOLUME_BAR_W = 16;
    private static final int EQ_TRACK_X = 6;
    private static final int TIME_W = 5;

    private Ui() {
    }

    public static void render(Screen s, PlayerApp app) {
        s.clear();
        int w = s.width();
        int h = s.height();
        if (w < MIN_WIDTH || h < MIN_HEIGHT) {
            drawTooSmall(s, w, h);
            return;
        }

        Layout l = layout(h);
        int leftW = libraryPanelWidth(w);
        int rightX = leftW + 1;
        int rightW = w - leftW - 1;
        int mainH = l.mainH();
        int npH = nowPlayingHeight(mainH);
        int spectrumH = mainH - npH;
        if (spectrumH < 3) {
            npH = mainH;
            spectrumH = 0;
        }

        drawHeader(s, app, w);
        drawLibrary(s, app, 0, l.mainTop(), leftW, mainH);
        drawNowPlaying(s, app, rightX, l.mainTop(), rightW, npH);
        if (spectrumH >= 3) drawSpectrum(s, app, rightX, l.mainTop() + npH, rightW, spectrumH);
        drawEqualizer(s, app, 0, l.eqTop(), w, l.eqPanelH(), l.eqRows());
        drawStatus(s, app, h - 2, w);
        drawFooter(s, app, h - 1, w);
        if (app.showHelp()) drawHelp(s, app, w, h);
    }

    // ---------------------------------------------------------------- layout

    /**
     * @param mainTop  first row of the upper half
     * @param mainH    height of the upper half
     * @param eqTop    first row of the equalizer panel
     * @param eqPanelH height of the equalizer panel, including its border
     * @param eqRows   slider rows inside it, always odd so 0 dB sits centred
     */
    private record Layout(int mainTop, int mainH, int eqTop, int eqPanelH, int eqRows) {
    }

    private static Layout layout(int height) {
        int body = Math.max(8, height - 4);
        int eqPanelH = Math.max(7, body / 2);
        int eqRows = eqPanelH - 4;
        if (eqRows % 2 == 0) eqRows--;
        eqRows = Math.max(3, eqRows);
        int mainH = body - eqPanelH;
        return new Layout(2, mainH, 2 + mainH, eqPanelH, eqRows);
    }

    private static int libraryPanelWidth(int width) {
        return Math.max(28, Math.min(48, width * 42 / 100));
    }

    private static int nowPlayingHeight(int mainH) {
        return mainH >= 15 ? 10 : mainH >= 11 ? 8 : Math.max(5, mainH - 5);
    }

    /** Rows of the library list, used for paging and scrolling. */
    public static int libraryRows(int height) {
        if (height < MIN_HEIGHT) return 1;
        return Math.max(1, layout(height).mainH() - 2);
    }

    private static int progressRowFor(int height) {
        Layout l = layout(height);
        int npH = nowPlayingHeight(l.mainH());
        if (l.mainH() - npH < 3) npH = l.mainH();
        return l.mainTop() + (npH >= 8 ? 4 : 3);
    }

    private static int[] progressSpan(int width) {
        int leftW = libraryPanelWidth(width);
        int ix = leftW + 1 + 2;
        int iw = width - leftW - 1 - 4;
        int start = ix + TIME_W + 1;
        int end = ix + iw - TIME_W - 2;
        return new int[]{start, end};
    }

    // ------------------------------------------------------------ hit testing

    /** Regions the pointer can land on. */
    public enum Zone { NONE, LIBRARY_ROW, NOW_PLAYING, PROGRESS, EQ_BAND, VOLUME }

    /**
     * @param zone  what was clicked
     * @param index library row offset within the visible window, or band number
     * @param value volume percent, band gain, or seek permille of the track
     */
    public record Hit(Zone zone, int index, int value) {
        public static final Hit NONE = new Hit(Zone.NONE, -1, Integer.MIN_VALUE);
    }

    public static Hit hitTest(int width, int height, int x, int y) {
        if (width < MIN_WIDTH || height < MIN_HEIGHT) return Hit.NONE;
        Layout l = layout(height);

        if (y == height - 2 && x >= VOLUME_BAR_X && x < VOLUME_BAR_X + VOLUME_BAR_W) {
            int percent = (int) Math.round((x - VOLUME_BAR_X) * 100.0 / (VOLUME_BAR_W - 1));
            return new Hit(Zone.VOLUME, -1, Math.max(0, Math.min(100, percent)));
        }
        int leftW = libraryPanelWidth(width);
        if (x < leftW && y > l.mainTop() && y < l.mainTop() + l.mainH() - 1) {
            return new Hit(Zone.LIBRARY_ROW, y - l.mainTop() - 1, Integer.MIN_VALUE);
        }
        if (y >= l.eqTop() && y < l.eqTop() + l.eqPanelH()) {
            int colW = Math.max(3, (width - 8) / Equalizer.BANDS);
            int band = -1;
            if (x >= EQ_TRACK_X && x < EQ_TRACK_X + colW * Equalizer.BANDS) {
                band = Math.min(Equalizer.BANDS - 1, (x - EQ_TRACK_X) / colW);
            }
            return new Hit(Zone.EQ_BAND, band, gainAtRow(height, y));
        }
        if (x > leftW && y >= l.mainTop() && y < l.eqTop()) {
            int[] span = progressSpan(width);
            if (y == progressRowFor(height) && x >= span[0] && x <= span[1]) {
                int permille = (int) Math.round((x - span[0]) * 1000.0 / Math.max(1, span[1] - span[0]));
                return new Hit(Zone.PROGRESS, -1, Math.max(0, Math.min(1000, permille)));
            }
            int npH = nowPlayingHeight(l.mainH());
            if (l.mainH() - npH < 3) npH = l.mainH();
            if (y < l.mainTop() + npH) return new Hit(Zone.NOW_PLAYING, -1, Integer.MIN_VALUE);
        }
        return Hit.NONE;
    }

    /** Gain a screen row represents, clamped so a drag past the panel pins. */
    public static int gainAtRow(int height, int y) {
        if (height < MIN_HEIGHT) return Integer.MIN_VALUE;
        Layout l = layout(height);
        int top = l.eqTop() + 1;
        int rows = l.eqRows();
        if (y < l.eqTop() || y >= l.eqTop() + l.eqPanelH()) return Integer.MIN_VALUE;
        if (y < top) return Equalizer.MAX_GAIN_DB;
        if (y >= top + rows) return -Equalizer.MAX_GAIN_DB;
        double step = (2.0 * Equalizer.MAX_GAIN_DB) / (rows - 1);
        int gain = (int) Math.round(Equalizer.MAX_GAIN_DB - (y - top) * step);
        return Math.max(-Equalizer.MAX_GAIN_DB, Math.min(Equalizer.MAX_GAIN_DB, gain));
    }

    // --------------------------------------------------------------- painting

    private static void drawTooSmall(Screen s, int w, int h) {
        String msg = "Terminal too small";
        String need = MIN_WIDTH + "x" + MIN_HEIGHT + " minimum, now " + w + "x" + h;
        s.put(Math.max(0, (w - msg.length()) / 2), Math.max(0, h / 2 - 1), msg, Theme.ALERT);
        s.put(Math.max(0, (w - need.length()) / 2), Math.max(0, h / 2), need, Theme.MUTED);
    }

    private static void drawHeader(Screen s, PlayerApp app, int w) {
        Player p = app.player();
        int x = s.put(1, 0, "◉", Theme.GOLD_BOLD);
        x = s.put(x + 1, 0, "PLAYERTERM", Style.bold(Style.fg(Theme.C_WHITE)));
        s.put(x + 1, 0, "terminal player", Theme.FAINT);

        String badge = stateBadge(p.state());
        String clock = LocalTime.now().format(CLOCK);
        FormatInfo info = p.info();
        String detail = info.codec.isEmpty() ? "" : info.codec.toLowerCase()
                + (info.bitrate.isEmpty() ? "" : " " + info.bitrate);

        int right = w - 1 - clock.length();
        s.put(right, 0, clock, Theme.FAINT);
        if (!detail.isEmpty()) {
            right -= detail.length() + 2;
            s.put(right, 0, detail, Theme.MUTED);
        }
        right -= badge.length() + 2;
        s.put(right, 0, badge, stateStyle(p.state()));
        s.hline(0, 1, w, '─', Theme.LINE);
    }

    private static String stateBadge(PlaybackState state) {
        return switch (state) {
            case PLAYING -> "▶ PLAYING";
            case PAUSED -> "⏸ PAUSED";
            case STOPPED -> "■ STOPPED";
            case ERROR -> "✖ ERROR";
            case IDLE -> "○ IDLE";
        };
    }

    private static int stateStyle(PlaybackState state) {
        return switch (state) {
            case PLAYING -> Theme.LIVE;
            case PAUSED -> Theme.GOLD;
            case ERROR -> Theme.ALERT;
            default -> Theme.MUTED;
        };
    }

    private static void drawLibrary(Screen s, PlayerApp app, int x, int y, int w, int h) {
        boolean focused = app.focus() == PlayerApp.Focus.LIBRARY;
        int border = focused ? Theme.ACCENT : Theme.LINE;
        List<Row> rows = app.rows();
        String title = "LIBRARY " + (rows.isEmpty() ? "empty" : (app.selectedIndex() + 1) + "/" + rows.size());
        s.box(x, y, w, h, title, border, focused ? Theme.ACCENT_BOLD : Theme.MUTED);

        int visibleRows = h - 2;
        int innerW = w - 2;
        int scroll = app.scrollOffset();
        Track playing = app.player().current();

        if (rows.isEmpty()) {
            s.putClipped(x + 2, y + 2, "no audio found in", innerW - 2, Theme.FAINT);
            s.putClipped(x + 2, y + 3, app.library().root().toString(), innerW - 2, Theme.MUTED);
            return;
        }

        for (int r = 0; r < visibleRows; r++) {
            int idx = scroll + r;
            if (idx >= rows.size()) break;
            Row row = rows.get(idx);
            boolean selected = idx == app.selectedIndex();
            int ly = y + 1 + r;
            if (selected) s.fill(x + 1, ly, innerW, 1, ' ', Theme.SELECTED);

            int base = selected ? Theme.SELECTED : Theme.TEXT;
            int dim = selected ? Theme.SELECTED_DIM : Theme.FAINT;
            int indent = Math.min(row.depth() * 2, Math.max(0, innerW - 12));
            int cx = x + 1 + indent;

            if (row.isFolder()) {
                MusicFolder folder = row.folder();
                String marker = folder.isExpanded() ? "▾" : "▸";
                s.put(cx, ly, marker, selected ? base : Theme.ACCENT);
                int nameStyle = selected ? Style.bold(Theme.SELECTED) : Style.bold(Style.fg(Theme.C_TEXT));
                int count = folder.tracks().size();
                String suffix = count > 0 ? "  " + count : "";
                int max = innerW - indent - 2 - suffix.length();
                s.putClipped(cx + 2, ly, folder.name(), max, nameStyle);
                if (!suffix.isEmpty()) {
                    s.put(x + innerW - suffix.length() + 1, ly, suffix.trim(), dim);
                }
            } else {
                Track track = row.track();
                boolean isPlaying = playing != null && playing.path().equals(track.path());
                String marker = isPlaying ? "▶" : "♪";
                int markerStyle = isPlaying
                        ? (selected ? Style.bold(Style.of(Theme.C_LIVE, Theme.C_SELECT_BG)) : Theme.LIVE)
                        : dim;
                s.put(cx, ly, marker, markerStyle);
                String time = track.duration() > 0 ? formatTime(track.duration()) : "";
                int max = innerW - indent - 2 - (time.isEmpty() ? 0 : time.length() + 1);
                int nameStyle = isPlaying && !selected ? Style.fg(Theme.C_LIVE) : base;
                s.putClipped(cx + 2, ly, track.displayName(), max, nameStyle);
                if (!time.isEmpty()) s.put(x + innerW - time.length() + 1, ly, time, dim);
            }
        }
        if (rows.size() > visibleRows) {
            drawScrollbar(s, x + w - 1, y + 1, visibleRows, rows.size(), scroll);
        }
    }

    private static void drawScrollbar(Screen s, int x, int y, int rows, int total, int scroll) {
        int thumb = Math.max(1, rows * rows / total);
        int maxScroll = Math.max(1, total - rows);
        int pos = (int) Math.round((double) scroll / maxScroll * (rows - thumb));
        for (int i = 0; i < rows; i++) {
            boolean on = i >= pos && i < pos + thumb;
            s.set(x, y + i, on ? '┃' : '│', on ? Theme.ACCENT : Theme.LINE);
        }
    }

    private static void drawNowPlaying(Screen s, PlayerApp app, int x, int y, int w, int h) {
        Player p = app.player();
        s.box(x, y, w, h, "NOW PLAYING", Theme.LINE, Theme.MUTED);
        int ix = x + 2;
        int iw = w - 4;
        if (iw < 6) return;

        Track track = p.current();
        if (track == null) {
            s.putClipped(ix, y + 2, "Nothing playing", iw, Theme.MUTED);
            s.putClipped(ix, y + 3, "Pick a folder and press enter", iw, Theme.FAINT);
            return;
        }

        s.put(ix, y + 1, marquee(track.displayName(), iw, app.tick()), Theme.TITLE);
        String by = track.artist().isEmpty() ? "" : track.artist();
        if (!track.album().isEmpty()) by = by.isEmpty() ? track.album() : by + " · " + track.album();
        s.putClipped(ix, y + 2, by, iw, Theme.ACCENT);

        drawProgress(s, p, ix, progressRowFor(s.height()), iw);

        int detailRow = progressRowFor(s.height()) + 1;
        FormatInfo info = p.info();
        StringBuilder detail = new StringBuilder();
        detail.append(track.extension().toUpperCase());
        if (!info.sampleRate.isEmpty()) detail.append(" · ").append(info.sampleRate).append(" Hz");
        if (!info.bitrate.isEmpty()) detail.append(" · ").append(info.bitrate);
        if (!p.queue().isEmpty()) {
            detail.append("  ·  track ").append(p.queueIndex() + 1).append('/').append(p.queue().size());
        }
        if (detailRow < y + h - 1) s.putClipped(ix, detailRow, detail.toString(), iw, Theme.FAINT);

        if (!p.lastError().isEmpty() && p.state() == PlaybackState.ERROR) {
            s.putClipped(ix, y + h - 3, "✖ " + p.lastError(), iw, Theme.ALERT);
        }
        if (h >= 8) drawMeters(s, p, ix, y + h - 2, iw, h >= 10);
    }

    private static void drawProgress(Screen s, Player p, int x, int y, int w) {
        double duration = p.duration();
        double position = Math.max(0, p.position());
        String elapsed = formatTime(position);
        String total = duration > 0 ? formatTime(duration) : "--:--";

        s.put(x, y, elapsed, Theme.MUTED);
        s.put(x + w - total.length(), y, total, Theme.MUTED);

        int barX = x + TIME_W + 1;
        int barEnd = x + w - TIME_W - 2;
        int barW = barEnd - barX + 1;
        if (barW < 4) return;
        double fraction = duration > 0 ? Math.min(1, position / duration) : 0;
        int filled = (int) Math.round(fraction * (barW - 1));
        for (int i = 0; i < barW; i++) {
            boolean done = i < filled;
            boolean head = i == filled;
            char c = head ? '◉' : done ? '━' : '─';
            int style = head ? Style.bold(Style.fg(Theme.C_WHITE))
                    : done ? Theme.ACCENT_BOLD : Theme.LINE;
            s.set(barX + i, y, c, style);
        }
    }

    private static void drawMeters(Screen s, Player p, int x, int y, int w, boolean twoRows) {
        int barW = Math.max(8, Math.min(w - 8, 44));
        if (twoRows) {
            drawMeter(s, "L", p.spectrum().levelLeft(), x, y - 1, barW);
            drawMeter(s, "R", p.spectrum().levelRight(), x, y, barW);
        } else {
            float mix = Math.max(p.spectrum().levelLeft(), p.spectrum().levelRight());
            drawMeter(s, "OUT", mix, x, y, barW);
        }
    }

    private static void drawMeter(Screen s, String label, float level, int x, int y, int w) {
        s.put(x, y, label, Theme.FAINT);
        int bx = x + label.length() + 1;
        int filled = (int) Math.round(Math.min(1f, level) * w);
        for (int i = 0; i < w; i++) {
            boolean on = i < filled;
            double t = (double) i / Math.max(1, w - 1);
            int color = t > 0.9 ? Theme.C_ALERT : t > 0.75 ? Theme.C_GOLD : Theme.C_LIVE;
            s.set(bx + i, y, on ? '▬' : '·', on ? Style.fg(color) : Theme.LINE);
        }
    }

    private static void drawSpectrum(Screen s, PlayerApp app, int x, int y, int w, int h) {
        if (h < 3) return;
        Player p = app.player();
        boolean live = p.state() == PlaybackState.PLAYING;
        s.box(x, y, w, h, "SPECTRUM", Theme.LINE, Theme.MUTED);

        int ix = x + 1;
        int iy = y + 1;
        int iw = w - 2;
        int ih = h - 2;
        if (iw < 4 || ih < 1) return;

        int columns = iw / 2;
        double[] bars = p.spectrum().bars(columns);
        double[] peaks = p.spectrum().peaks();

        for (int c = 0; c < columns; c++) {
            double v = live ? bars[c] : 0;
            int cx = ix + c * 2;
            int totalEighths = (int) Math.round(v * ih * 8);
            for (int r = 0; r < ih; r++) {
                int rowFromBottom = ih - 1 - r;
                int eighths = Math.max(0, Math.min(8, totalEighths - rowFromBottom * 8));
                if (eighths == 0) continue;
                char ch = EIGHTHS.charAt(eighths);
                double heat = (double) rowFromBottom / Math.max(1, ih - 1);
                int style = Style.fg(Theme.heat(0.15 + heat * 0.85));
                s.set(cx, iy + r, ch, style);
                if (iw > columns * 2 - 1) s.set(cx + 1, iy + r, ch, style);
            }
            if (live && peaks.length > c && peaks[c] > 0.02) {
                int peakRow = ih - 1 - (int) Math.min(ih - 1, Math.round(peaks[c] * (ih - 1)));
                s.set(cx, iy + peakRow, '▔', Style.fg(Theme.C_WHITE));
                if (iw > columns * 2 - 1) s.set(cx + 1, iy + peakRow, '▔', Style.fg(Theme.C_WHITE));
            }
        }
        if (!live) {
            String idle = p.state() == PlaybackState.PAUSED ? "paused" : "silence";
            s.put(ix + Math.max(0, (iw - idle.length()) / 2), iy + ih / 2, idle, Theme.FAINT);
        }
    }

    private static void drawEqualizer(Screen s, PlayerApp app, int x, int y, int w, int h, int rows) {
        Equalizer eq = app.player().equalizer();
        boolean focused = app.focus() == PlayerApp.Focus.EQUALIZER;
        int border = focused ? Theme.ACCENT : Theme.LINE;

        String title = "EQUALIZER · " + app.presetLabel() + " · " + (eq.isEnabled() ? "on" : "bypass")
                + " · pre " + signed(eq.preamp()) + " dB";
        s.box(x, y, w, h, title, border, focused ? Theme.ACCENT_BOLD : Theme.MUTED);

        int gutter = x + 1;
        int trackX = x + EQ_TRACK_X;
        int trackW = w - 8;
        int colW = Math.max(3, trackW / Equalizer.BANDS);
        int top = y + 1;
        int zeroRow = top + (rows - 1) / 2;

        s.put(gutter, top, "+" + Equalizer.MAX_GAIN_DB, Theme.FAINT);
        s.put(gutter, zeroRow, "  0", Theme.FAINT);
        s.put(gutter, top + rows - 1, "-" + Equalizer.MAX_GAIN_DB, Theme.FAINT);
        s.hline(trackX, zeroRow, colW * Equalizer.BANDS, '·', Theme.LINE);

        int handleW = Math.min(3, Math.max(1, colW - 1));
        int[] gains = eq.gains();
        boolean bypassed = !eq.isEnabled();

        for (int b = 0; b < Equalizer.BANDS; b++) {
            int cx = trackX + b * colW + (colW - handleW) / 2;
            int gain = gains[b];
            boolean selected = focused && b == app.eqBand();
            int handleRow = top + (int) Math.round((Equalizer.MAX_GAIN_DB - gain)
                    * (rows - 1) / (2.0 * Equalizer.MAX_GAIN_DB));
            if (gain != 0 && handleRow == zeroRow) handleRow += gain > 0 ? -1 : 1;

            int fillStyle = selected ? Style.fg(Theme.C_SELECT_FILL)
                    : bypassed ? Theme.LINE : Style.fg(Theme.C_ACCENT_SOFT);
            for (int r = 0; r < rows; r++) {
                int ry = top + r;
                if (ry == zeroRow) continue;
                boolean between = (ry > handleRow && ry < zeroRow) || (ry < handleRow && ry > zeroRow);
                if (between) {
                    for (int i = 0; i < handleW; i++) s.set(cx + i, ry, '▒', fillStyle);
                } else if (selected && ry != handleRow) {
                    s.set(cx + handleW / 2, ry, '│', Theme.SELECT_GUIDE);
                }
            }

            double heat = (gain + Equalizer.MAX_GAIN_DB) / (2.0 * Equalizer.MAX_GAIN_DB);
            int handleStyle = selected ? Theme.SELECT_HANDLE
                    : bypassed ? Style.fg(Theme.C_FAINT) : Style.bold(Style.fg(Theme.heat(heat)));
            for (int i = 0; i < handleW; i++) s.set(cx + i, handleRow, '█', handleStyle);

            String label = Equalizer.LABELS[b];
            int labelX = trackX + b * colW + Math.max(0, (colW - label.length()) / 2);
            s.put(labelX, top + rows, label, selected ? Theme.SELECT_HANDLE : Theme.MUTED);

            String value = signed(gain);
            int valueX = trackX + b * colW + Math.max(0, (colW - value.length()) / 2);
            int valueStyle = gain == 0 || bypassed ? Theme.FAINT : Style.fg(Theme.heat(heat));
            s.put(valueX, top + rows + 1, value, selected ? Style.bold(valueStyle) : valueStyle);
        }
    }

    private static void drawStatus(Screen s, PlayerApp app, int y, int w) {
        Player p = app.player();
        int x = 1;
        x = s.put(x, y, "VOL ", Theme.MUTED);
        int filled = (int) Math.round(p.volume() / 100.0 * VOLUME_BAR_W);
        for (int i = 0; i < VOLUME_BAR_W; i++) {
            boolean on = i < filled && !p.isMuted();
            s.set(x + i, y, on ? '▰' : '▱', on ? Theme.ACCENT : Theme.LINE);
        }
        x += VOLUME_BAR_W + 1;
        String vol = p.isMuted() ? "muted" : p.volume() + "%";
        x = s.put(x, y, String.format("%-6s", vol), p.isMuted() ? Theme.ALERT : Theme.TEXT);

        String message = app.statusMessage();
        if (!message.isEmpty()) {
            s.putClipped(x + 2, y, message, w - x - 3, Theme.GOLD);
        } else {
            String root = app.library().root().toString();
            s.putClipped(x + 2, y, root, w - x - 3, Theme.FAINT);
        }
    }

    private static void drawFooter(Screen s, PlayerApp app, int y, int w) {
        String keys = app.focus() == PlayerApp.Focus.LIBRARY
                ? "↑↓ move  space fold/pause  ⏎ play  n/b track  ,. seek  tab eq  +- vol  m mute  ? help  q quit"
                : "←→ band  ↑↓ gain  [ ] preset  e bypass  r reset  < > preamp  tab library  +- vol  ? help  q quit";
        s.putClipped(1, y, keys, w - 2, Theme.FAINT);
    }

    private static void drawHelp(Screen s, PlayerApp app, int w, int h) {
        String[] lines = {
                "LIBRARY",
                "  ↑ / ↓ / j / k     move                  ⏎          play folder or track",
                "  space             fold a folder, or pause a track",
                "  → / ←             expand / collapse      PgUp/PgDn  page",
                "  n / b             next / previous track  s          stop",
                "  , / .             seek 5s back / on      g / G      top / bottom",
                "  +/- / *           collapse all / expand all",
                "",
                "EQUALIZER  (tab switches panels and reloads files)",
                "  ← / →             select band            ↑ / ↓      band gain ±1 dB",
                "  [ / ]             preset                 0-9        pick band",
                "  e                 bypass                 r          reset to flat",
                "  < / >             preamp ±1 dB",
                "",
                "MOUSE",
                "  click a folder    expands or collapses it",
                "  click now playing pauses or resumes; click the bar to seek",
                "  click a track     selects, click again to play; wheel scrolls or adjusts",
                "",
                "SOUND",
                "  + / -             volume                 m          mute",
                "  q                 quit",
                "",
                "MUSIC FOLDER",
                "  " + app.library().root()
        };
        int boxW = Math.min(w - 4, 76);
        int boxH = Math.min(h - 4, lines.length + 4);
        int x = (w - boxW) / 2;
        int y = (h - boxH) / 2;
        s.fill(x, y, boxW, boxH, ' ', Style.bg(Theme.TEXT, 233));
        s.box(x, y, boxW, boxH, "KEYS", Style.bg(Theme.ACCENT, 233), Style.bg(Theme.ACCENT_BOLD, 233));
        for (int i = 0; i < lines.length && i < boxH - 3; i++) {
            String line = lines[i];
            int style = line.startsWith("  ") ? Style.bg(Theme.TEXT, 233) : Style.bg(Theme.GOLD_BOLD, 233);
            s.putClipped(x + 2, y + 1 + i, line, boxW - 4, style);
        }
        String close = " press ? or esc to close ";
        s.put(x + boxW - close.length() - 2, y + boxH - 1, close, Style.bg(Theme.FAINT, 233));
    }

    public static String formatTime(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) seconds = 0;
        int total = (int) Math.round(seconds);
        int minutes = total / 60;
        int secs = total % 60;
        if (minutes >= 60) {
            return String.format("%d:%02d:%02d", minutes / 60, minutes % 60, secs);
        }
        return String.format("%02d:%02d", minutes, secs);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    /** Scrolls text that does not fit, at a readable pace. */
    private static String marquee(String text, int width, long tick) {
        if (text.length() <= width) return text;
        String padded = text + "   ·   ";
        int offset = (int) ((tick / 5) % padded.length());
        return (padded + padded).substring(offset, offset + width);
    }
}
