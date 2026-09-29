package org.example.player.tui;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Raw mode terminal access built on stty, with escape sequence decoding for
 * the keys the app cares about. No third party terminal library involved.
 */
public final class Terminal implements AutoCloseable {

    private final PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
            false, StandardCharsets.UTF_8);
    private final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();
    private final Thread reader;
    private String savedMode;
    private boolean closed;
    private int width = 80;
    private int height = 24;
    private long lastSizeCheck;

    private final boolean mouseEnabled;

    public Terminal(boolean enableMouse) {
        this.mouseEnabled = enableMouse;
        savedMode = stty("-g");
        stty("raw", "-echo", "-ixon");
        out.print(Ansi.ALT_SCREEN_ON);
        out.print(Ansi.HIDE_CURSOR);
        out.print(Ansi.CLEAR_SCREEN);
        if (mouseEnabled) out.print(Ansi.MOUSE_ON);
        out.print(Ansi.KEYBOARD_DISAMBIGUATE);
        out.flush();
        refreshSize(true);

        reader = new Thread(() -> {
            InputStream in = System.in;
            try {
                int c;
                while ((c = in.read()) != -1) input.put(c);
            } catch (IOException | InterruptedException ignored) {
                // Shutting down.
            }
        }, "tty-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Re-reads the window size at most a few times per second. */
    public boolean refreshSize(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSizeCheck < 400) return false;
        lastSizeCheck = now;
        String size = stty("size");
        if (size == null) return false;
        String[] parts = size.trim().split("\\s+");
        if (parts.length != 2) return false;
        try {
            int h = Integer.parseInt(parts[0]);
            int w = Integer.parseInt(parts[1]);
            if (h != height || w != width) {
                height = h;
                width = w;
                return true;
            }
        } catch (NumberFormatException ignored) {
            // Keep the previous size.
        }
        return false;
    }

    public void write(CharSequence text) {
        out.print(text);
        out.flush();
    }

    /** Waits up to {@code timeoutMs} for a key press. */
    public Key readKey(long timeoutMs) {
        Integer first = poll(timeoutMs);
        if (first == null) return Key.NONE;
        int b = first;
        return switch (b) {
            case 27 -> readEscapeSequence();
            case 13, 10 -> Key.of(Key.Type.ENTER);
            case 9 -> Key.of(Key.Type.TAB);
            case 127, 8 -> Key.of(Key.Type.BACKSPACE);
            case 3, 4 -> Key.ofChar('q');
            default -> b >= 0x80 ? Key.ofChar(decodeUtf8(b)) : Key.ofChar((char) b);
        };
    }

    private Key readEscapeSequence() {
        Integer next = poll(40);
        if (next == null) return Key.of(Key.Type.ESCAPE);
        if (next == '[' || next == 'O') {
            Integer raw = poll(40);
            if (raw == null) return Key.of(Key.Type.ESCAPE);
            int code = raw;
            if (next == '[' && code == '<') return readMouseReport();
            if (code >= 'A' && code <= 'Z') return functionLetter(code);
            if (code >= '0' && code <= '9') return readParameterized(code);
            return Key.NONE;
        }
        return Key.of(Key.Type.ESCAPE);
    }

    /** {@code CSI A} / {@code SS3 A} and the other single-letter function keys. */
    private static Key functionLetter(int code) {
        return switch (code) {
            case 'A' -> Key.of(Key.Type.UP);
            case 'B' -> Key.of(Key.Type.DOWN);
            case 'C' -> Key.of(Key.Type.RIGHT);
            case 'D' -> Key.of(Key.Type.LEFT);
            case 'H' -> Key.of(Key.Type.HOME);
            case 'F' -> Key.of(Key.Type.END);
            case 'Z' -> Key.of(Key.Type.SHIFT_TAB);
            default -> Key.NONE;
        };
    }

    /**
     * A parameterized CSI sequence, {@code CSI number ; modifiers u} or
     * {@code ~}. Play, pause and the combined play/pause key are the kitty
     * protocol code points 57428, 57429 and 57430. A release (event type 3)
     * is ignored.
     */
    private Key readParameterized(int firstDigit) {
        StringBuilder body = new StringBuilder();
        body.append((char) firstDigit);
        int terminator = -1;
        Integer c;
        while ((c = poll(40)) != null) {
            int ch = c;
            if (ch == '~' || ch == 'u' || (ch >= 'A' && ch <= 'Z')) {
                terminator = ch;
                break;
            }
            if ((ch >= '0' && ch <= '9') || ch == ';' || ch == ':') {
                body.append((char) ch);
                continue;
            }
            return Key.NONE;
        }
        if (terminator < 0) return Key.NONE;
        return decodeParameterized(body.toString(), terminator);
    }

    static Key decodeParameterized(String body, int terminator) {
        String[] fields = body.split(";", -1);
        int keyCode = leadingNumber(fields[0]);
        int modifier = 1;
        int eventType = 1;
        if (fields.length >= 2 && !fields[1].isEmpty()) {
            String[] sub = fields[1].split(":", -1);
            if (!sub[0].isEmpty()) modifier = parseInt(sub[0], 1);
            if (sub.length >= 2 && !sub[1].isEmpty()) eventType = parseInt(sub[1], 1);
        }
        if (eventType == 3) return Key.NONE;

        int mods = Math.max(0, modifier - 1);
        boolean shift = (mods & 1) != 0;
        boolean alt = (mods & 2) != 0;
        boolean ctrl = (mods & 4) != 0;

        if (terminator == 'u') {
            // Raw mode used to deliver ctrl-c / ctrl-d as bytes 3 and 4, which quit.
            // Disambiguate mode sends them as CSI u instead.
            if ((keyCode == 99 || keyCode == 100) && ctrl && !alt) return Key.ofChar('q');
            return switch (keyCode) {
                case 57428, 57429, 57430 -> Key.of(Key.Type.MEDIA_PLAY_PAUSE);
                case 27 -> Key.of(Key.Type.ESCAPE);
                case 13 -> Key.of(Key.Type.ENTER);
                case 127, 8 -> Key.of(Key.Type.BACKSPACE);
                case 9 -> Key.of(shift ? Key.Type.SHIFT_TAB : Key.Type.TAB);
                default -> Key.NONE;
            };
        }
        if (terminator == '~') {
            return switch (keyCode) {
                case 1, 7 -> Key.of(Key.Type.HOME);
                case 3 -> Key.of(Key.Type.DELETE);
                case 4, 8 -> Key.of(Key.Type.END);
                case 5 -> Key.of(Key.Type.PAGE_UP);
                case 6 -> Key.of(Key.Type.PAGE_DOWN);
                default -> Key.NONE;
            };
        }
        if (keyCode == 1) return functionLetter(terminator);
        return Key.NONE;
    }

    /** The number before a ':' sub-field, or -1 when the field is not a number. */
    private static int leadingNumber(String field) {
        int cut = field.indexOf(':');
        return parseInt(cut < 0 ? field : field.substring(0, cut), -1);
    }

    private static int parseInt(String text, int fallback) {
        if (text == null || text.isEmpty()) return fallback;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Decodes an SGR mouse report, {@code CSI < button ; col ; row M|m}.
     * Bit 5 of the button marks motion, bit 6 marks the wheel, and the final
     * character is {@code M} for press and {@code m} for release.
     */
    private Key readMouseReport() {
        StringBuilder fields = new StringBuilder();
        char terminator = 0;
        Integer c;
        while ((c = poll(40)) != null) {
            if (c == 'M' || c == 'm') {
                terminator = (char) (int) c;
                break;
            }
            fields.append((char) (int) c);
        }
        if (terminator == 0) return Key.NONE;

        String[] parts = fields.toString().split(";");
        if (parts.length != 3) return Key.NONE;
        try {
            int flags = Integer.parseInt(parts[0]);
            int x = Integer.parseInt(parts[1]) - 1;
            int y = Integer.parseInt(parts[2]) - 1;
            boolean motion = (flags & 32) != 0;
            boolean wheel = (flags & 64) != 0;
            int button = flags & 3;

            MouseEvent.Button which;
            if (wheel) {
                which = button == 0 ? MouseEvent.Button.WHEEL_UP : MouseEvent.Button.WHEEL_DOWN;
            } else {
                which = switch (button) {
                    case 0 -> MouseEvent.Button.LEFT;
                    case 1 -> MouseEvent.Button.MIDDLE;
                    case 2 -> MouseEvent.Button.RIGHT;
                    default -> MouseEvent.Button.NONE;
                };
            }
            MouseEvent.Action action = terminator == 'm'
                    ? MouseEvent.Action.RELEASE
                    : motion ? MouseEvent.Action.DRAG : MouseEvent.Action.PRESS;
            return Key.ofMouse(new MouseEvent(x, y, which, action));
        } catch (NumberFormatException e) {
            return Key.NONE;
        }
    }

    /** Assembles a UTF-8 code point from the byte stream (BMP only). */
    private char decodeUtf8(int first) {
        int extra = (first & 0xE0) == 0xC0 ? 1 : (first & 0xF0) == 0xE0 ? 2 : 3;
        int value = switch (extra) {
            case 1 -> first & 0x1F;
            case 2 -> first & 0x0F;
            default -> first & 0x07;
        };
        for (int i = 0; i < extra; i++) {
            Integer b = poll(30);
            if (b == null) break;
            value = (value << 6) | (b & 0x3F);
        }
        return (char) value;
    }

    private Integer poll(long timeoutMs) {
        try {
            return input.poll(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void drainInput() {
        input.clear();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        out.print(Ansi.KEYBOARD_POP);
        if (mouseEnabled) out.print(Ansi.MOUSE_OFF);
        out.print(Ansi.RESET);
        out.print(Ansi.SHOW_CURSOR);
        out.print(Ansi.ALT_SCREEN_OFF);
        out.flush();
        if (savedMode != null && !savedMode.isBlank()) {
            stty(savedMode.trim().split("\\s+"));
        } else {
            stty("sane");
        }
        reader.interrupt();
    }

    /** Runs stty against the controlling terminal and returns its output. */
    private static String stty(String... args) {
        try {
            StringBuilder cmd = new StringBuilder("stty");
            for (String a : args) cmd.append(' ').append(a);
            cmd.append(" < /dev/tty");
            Process p = new ProcessBuilder("sh", "-c", cmd.toString())
                    .redirectErrorStream(true)
                    .start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return result;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** True when stdin is a terminal, i.e. the UI can run at all. */
    public static boolean isInteractive() {
        return System.console() != null;
    }
}
