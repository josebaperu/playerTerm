package org.example.player.tui;

/** A decoded mouse report, in zero based screen cells. */
public record MouseEvent(int x, int y, Button button, Action action) {

    public enum Button { LEFT, MIDDLE, RIGHT, WHEEL_UP, WHEEL_DOWN, NONE }

    public enum Action { PRESS, RELEASE, DRAG }

    public boolean isWheel() {
        return button == Button.WHEEL_UP || button == Button.WHEEL_DOWN;
    }
}
