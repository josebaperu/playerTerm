package org.example.player.tui;

/**
 * Packs a 256 colour foreground, background and attribute flags into one int
 * so the screen buffer stays primitive.
 */
public final class Style {

    public static final int DEFAULT = 0;
    private static final int FG_MASK = 0x1FF;
    private static final int BG_SHIFT = 9;
    private static final int BG_MASK = 0x1FF << BG_SHIFT;
    private static final int BOLD = 1 << 18;
    private static final int DIM = 1 << 19;
    private static final int REVERSE = 1 << 20;
    private static final int ITALIC = 1 << 21;

    private Style() {
    }

    public static int fg(int color) {
        return (color + 1) & FG_MASK;
    }

    public static int bg(int style, int color) {
        return (style & ~BG_MASK) | (((color + 1) & FG_MASK) << BG_SHIFT);
    }

    public static int of(int fg, int bg) {
        return fg(fg) | (((bg + 1) & FG_MASK) << BG_SHIFT);
    }

    public static int bold(int style) {
        return style | BOLD;
    }

    public static int dim(int style) {
        return style | DIM;
    }

    public static int italic(int style) {
        return style | ITALIC;
    }

    public static int reverse(int style) {
        return style | REVERSE;
    }

    /** Appends the SGR sequence for this style. */
    public static void emit(StringBuilder out, int style) {
        out.append(Ansi.CSI).append('0');
        int fg = style & FG_MASK;
        if (fg != 0) out.append(";38;5;").append(fg - 1);
        int bg = (style & BG_MASK) >>> BG_SHIFT;
        if (bg != 0) out.append(";48;5;").append(bg - 1);
        if ((style & BOLD) != 0) out.append(";1");
        if ((style & DIM) != 0) out.append(";2");
        if ((style & ITALIC) != 0) out.append(";3");
        if ((style & REVERSE) != 0) out.append(";7");
        out.append('m');
    }
}
