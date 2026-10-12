package org.example.player.audio;

import java.util.List;

/** Named gain curve over the five equalizer bands, in dB. */
public record EqPreset(String name, int[] gains) {

    public static final List<EqPreset> ALL = List.of(
            new EqPreset("Flat",      new int[]{  0,  0,  0,  0,  0}),
            new EqPreset("Bass",      new int[]{  6,  4,  0,  0,  0}),
            new EqPreset("Rock",      new int[]{  5,  2, -2,  2,  5}),
            new EqPreset("Pop",       new int[]{ -1,  2,  4,  2, -1}),
            new EqPreset("Jazz",      new int[]{  3,  1, -1,  2,  3}),
            new EqPreset("Classical", new int[]{  4,  2, -1,  2,  4}),
            new EqPreset("Vocal",     new int[]{ -2, -1,  3,  4,  1}),
            new EqPreset("Car",       new int[]{  3,  0, -1,  2,  4})
    );

    public static EqPreset byName(String name) {
        for (EqPreset p : ALL) {
            if (p.name().equalsIgnoreCase(name)) return p;
        }
        return ALL.get(0);
    }

    /** Index of the preset whose curve matches the given gains, or -1 for a custom curve. */
    public static int matching(int[] gains) {
        for (int i = 0; i < ALL.size(); i++) {
            if (java.util.Arrays.equals(ALL.get(i).gains(), gains)) return i;
        }
        return -1;
    }
}
