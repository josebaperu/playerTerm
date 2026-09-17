package org.example.player.audio;

import java.util.List;

/** Named gain curve over the ten equalizer bands, in dB. */
public record EqPreset(String name, int[] gains) {

    public static final List<EqPreset> ALL = List.of(
            new EqPreset("Flat",        new int[]{  0,  0,  0,  0,  0,  0,  0,  0,  0,  0}),
            new EqPreset("Rock",        new int[]{  5,  4,  3,  1, -1, -1,  1,  3,  4,  5}),
            new EqPreset("Pop",         new int[]{ -1,  1,  3,  4,  4,  2,  0, -1, -1, -1}),
            new EqPreset("Jazz",        new int[]{  4,  3,  1,  2, -1, -1,  0,  1,  3,  4}),
            new EqPreset("Classical",   new int[]{  4,  3,  2,  1, -1, -1,  0,  2,  3,  4}),
            new EqPreset("Bass Boost",  new int[]{  8,  7,  5,  3,  1,  0,  0,  0,  0,  0}),
            new EqPreset("Treble Lift", new int[]{  0,  0,  0,  0,  0,  1,  3,  5,  6,  7}),
            new EqPreset("Vocal",       new int[]{ -3, -2,  0,  2,  5,  5,  3,  1,  0, -1}),
            new EqPreset("Electronic",  new int[]{  6,  5,  1,  0, -2,  1,  0,  2,  5,  6}),
            new EqPreset("Loudness",    new int[]{  7,  5,  2,  0, -2, -1,  1,  3,  6,  7}),
            new EqPreset("Late Night",  new int[]{ -4, -3, -1,  2,  4,  4,  2,  0, -2, -4}),
            new EqPreset("Podcast",     new int[]{ -6, -5, -2,  2,  4,  4,  2, -1, -3, -5})
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
