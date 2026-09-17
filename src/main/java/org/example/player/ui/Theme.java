package org.example.player.ui;

import org.example.player.tui.Style;

/** 256 colour palette shared by every panel. */
public final class Theme {

    public static final int C_ACCENT = 44;
    public static final int C_ACCENT_SOFT = 37;
    public static final int C_GOLD = 214;
    public static final int C_TEXT = 252;
    public static final int C_MUTED = 245;
    public static final int C_FAINT = 240;
    public static final int C_LINE = 238;
    public static final int C_LIVE = 48;
    public static final int C_ALERT = 203;
    public static final int C_SELECT_BG = 236;
    public static final int C_WHITE = 231;
    public static final int C_SELECT_FILL = 250;
    public static final int C_SELECT_GUIDE = 244;

    public static final int TEXT = Style.fg(C_TEXT);
    public static final int MUTED = Style.fg(C_MUTED);
    public static final int FAINT = Style.fg(C_FAINT);
    public static final int LINE = Style.fg(C_LINE);
    public static final int ACCENT = Style.fg(C_ACCENT);
    public static final int ACCENT_BOLD = Style.bold(Style.fg(C_ACCENT));
    public static final int GOLD = Style.fg(C_GOLD);
    public static final int GOLD_BOLD = Style.bold(Style.fg(C_GOLD));
    public static final int LIVE = Style.bold(Style.fg(C_LIVE));
    public static final int ALERT = Style.bold(Style.fg(C_ALERT));
    public static final int TITLE = Style.bold(Style.fg(C_WHITE));
    public static final int SELECTED = Style.bold(Style.of(C_WHITE, C_SELECT_BG));
    /** The equalizer band under the cursor: white, so it stands out of the heat ramp. */
    public static final int SELECT_HANDLE = Style.bold(Style.fg(C_WHITE));
    public static final int SELECT_GUIDE = Style.fg(C_SELECT_GUIDE);
    public static final int SELECTED_DIM = Style.of(C_MUTED, C_SELECT_BG);

    /** Blue to red ramp used by the spectrum and the equalizer handles. */
    public static int heat(double t) {
        int[] ramp = {33, 39, 45, 51, 50, 49, 48, 82, 118, 154, 184, 214, 208, 202, 196};
        int i = (int) Math.round(Math.max(0, Math.min(1, t)) * (ramp.length - 1));
        return ramp[i];
    }

    private Theme() {
    }
}
