package org.example.player.tui;

import java.util.Arrays;

/**
 * Double buffered character grid. Frames are diffed per row so only the cells
 * that changed are repainted, which keeps the spectrum flicker free.
 */
public final class Screen {

    private int width;
    private int height;
    private char[] chars = new char[0];
    private int[] styles = new int[0];
    private char[] shownChars = new char[0];
    private int[] shownStyles = new int[0];
    private boolean forceRepaint = true;

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void resize(int w, int h) {
        if (w == width && h == height) return;
        width = Math.max(1, w);
        height = Math.max(1, h);
        int n = width * height;
        chars = new char[n];
        styles = new int[n];
        shownChars = new char[n];
        shownStyles = new int[n];
        Arrays.fill(chars, ' ');
        Arrays.fill(shownChars, (char) 0);
        forceRepaint = true;
    }

    public void clear() {
        Arrays.fill(chars, ' ');
        Arrays.fill(styles, Style.DEFAULT);
    }

    public void set(int x, int y, char c, int style) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int i = y * width + x;
        chars[i] = c;
        styles[i] = style;
    }

    public int put(int x, int y, String text, int style) {
        int cx = x;
        for (int i = 0; i < text.length() && cx < width; i++, cx++) {
            set(cx, y, text.charAt(i), style);
        }
        return cx;
    }

    /** Writes text truncated to {@code max} columns, adding an ellipsis when cut. */
    public void putClipped(int x, int y, String text, int max, int style) {
        if (max <= 0) return;
        String s = text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
        put(x, y, s, style);
    }

    public void fill(int x, int y, int w, int h, char c, int style) {
        for (int row = y; row < y + h; row++) {
            for (int col = x; col < x + w; col++) {
                set(col, row, c, style);
            }
        }
    }

    public void hline(int x, int y, int w, char c, int style) {
        for (int i = 0; i < w; i++) set(x + i, y, c, style);
    }

    public void vline(int x, int y, int h, char c, int style) {
        for (int i = 0; i < h; i++) set(x, y + i, c, style);
    }

    /** Rounded box with an optional title rendered into the top border. */
    public void box(int x, int y, int w, int h, String title, int border, int titleStyle) {
        if (w < 2 || h < 2) return;
        hline(x + 1, y, w - 2, '─', border);
        hline(x + 1, y + h - 1, w - 2, '─', border);
        vline(x, y + 1, h - 2, '│', border);
        vline(x + w - 1, y + 1, h - 2, '│', border);
        set(x, y, '╭', border);
        set(x + w - 1, y, '╮', border);
        set(x, y + h - 1, '╰', border);
        set(x + w - 1, y + h - 1, '╯', border);
        if (title != null && !title.isEmpty() && w > 6) {
            String t = " " + title + " ";
            if (t.length() > w - 4) t = t.substring(0, w - 4);
            put(x + 2, y, t, titleStyle);
        }
    }

    /** Appends the escape sequences that turn the shown frame into the current one. */
    public void render(StringBuilder out) {
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            int first = -1;
            int last = -1;
            for (int x = 0; x < width; x++) {
                int i = rowStart + x;
                if (forceRepaint || chars[i] != shownChars[i] || styles[i] != shownStyles[i]) {
                    if (first < 0) first = x;
                    last = x;
                }
            }
            if (first < 0) continue;
            out.append(Ansi.moveTo(y + 1, first + 1));
            int current = Integer.MIN_VALUE;
            for (int x = first; x <= last; x++) {
                int i = rowStart + x;
                if (styles[i] != current) {
                    current = styles[i];
                    Style.emit(out, current);
                }
                out.append(chars[i]);
                shownChars[i] = chars[i];
                shownStyles[i] = styles[i];
            }
            out.append(Ansi.RESET);
        }
        forceRepaint = false;
    }

    public void invalidate() {
        forceRepaint = true;
    }
}
