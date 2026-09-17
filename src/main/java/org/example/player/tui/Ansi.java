package org.example.player.tui;

/** Terminal control sequences used by the renderer. */
public final class Ansi {

    public static final char ESC_CHAR = (char) 27;
    public static final String ESC = String.valueOf(ESC_CHAR);
    public static final String CSI = ESC + "[";

    public static final String RESET = CSI + "0m";
    public static final String CLEAR_SCREEN = CSI + "2J" + CSI + "H";
    public static final String HIDE_CURSOR = CSI + "?25l";
    public static final String SHOW_CURSOR = CSI + "?25h";
    public static final String ALT_SCREEN_ON = CSI + "?1049h";
    public static final String ALT_SCREEN_OFF = CSI + "?1049l";

    /** Button and drag tracking, reported in SGR form so wide terminals work. */
    public static final String MOUSE_ON = CSI + "?1000h" + CSI + "?1002h" + CSI + "?1006h";
    public static final String MOUSE_OFF = CSI + "?1006l" + CSI + "?1002l" + CSI + "?1000l";

    public static String moveTo(int row, int col) {
        return CSI + row + ";" + col + "H";
    }

    private Ansi() {
    }
}
