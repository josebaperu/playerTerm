package org.example.ytm.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read a playlist export: objects become maps, arrays
 * lists, numbers doubles. Throws {@link IllegalArgumentException} on anything
 * malformed, which is also what a half-written file looks like.
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json json = new Json(text);
        json.skipSpace();
        Object value = json.value();
        json.skipSpace();
        if (json.pos != text.length()) throw json.error("trailing data");
        return value;
    }

    private Object value() {
        if (pos >= text.length()) throw error("unexpected end");
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') throw error("expected a key");
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            out.put(key, value());
            skipSpace();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw error("expected , or }");
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            out.add(value());
            skipSpace();
            char c = next();
            if (c == ']') return out;
            if (c != ',') throw error("expected , or ]");
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error("bad escape");
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad escape");
                    }
                    pos += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        if (start == pos) throw error("unexpected character");
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) throw error("unexpected character");
        pos += word.length();
        return value;
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= text.length()) throw error("unexpected end");
        return text.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) throw error("expected " + c);
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at offset " + pos);
    }
}
