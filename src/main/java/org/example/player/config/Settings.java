package org.example.player.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persisted user state: volume, equalizer curve and the last station played. */
public final class Settings {

    private final Path file;
    private final Properties props = new Properties();

    public Settings(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        } catch (IOException ignored) {
            // Start from defaults.
        }
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(fallback)).trim());
    }

    public String getString(String key, String fallback) {
        return props.getProperty(key, fallback);
    }

    public int[] getInts(String key, int[] fallback) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        String[] parts = raw.split(",");
        int[] out = new int[fallback.length];
        System.arraycopy(fallback, 0, out, 0, fallback.length);
        for (int i = 0; i < parts.length && i < out.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // Keep the fallback for this band.
            }
        }
        return out;
    }

    public void set(String key, Object value) {
        props.setProperty(key, String.valueOf(value));
    }

    public void setInts(String key, int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        props.setProperty(key, sb.toString());
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "playerTerm settings");
            }
        } catch (IOException ignored) {
            // Nothing we can do about an unwritable config directory.
        }
    }
}
