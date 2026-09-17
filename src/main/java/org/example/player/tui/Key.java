package org.example.player.tui;

/** A decoded key press, or a mouse report when {@code type} is MOUSE. */
public record Key(Key.Type type, char ch, MouseEvent mouse) {

    public enum Type {
        CHAR, UP, DOWN, LEFT, RIGHT, ENTER, ESCAPE, BACKSPACE, TAB, SHIFT_TAB,
        PAGE_UP, PAGE_DOWN, HOME, END, DELETE, MOUSE, NONE
    }

    public static final Key NONE = new Key(Type.NONE, '\0', null);

    public static Key of(Type type) {
        return new Key(type, '\0', null);
    }

    public static Key ofChar(char c) {
        return new Key(Type.CHAR, c, null);
    }

    public static Key ofMouse(MouseEvent event) {
        return new Key(Type.MOUSE, '\0', event);
    }

    public boolean is(char c) {
        return type == Type.CHAR && (ch == c || Character.toLowerCase(ch) == c);
    }

    public boolean isNone() {
        return type == Type.NONE;
    }
}
