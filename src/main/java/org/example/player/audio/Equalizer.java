package org.example.player.audio;

/**
 * Five band equalizer with a manual preamp, applied to interleaved stereo
 * float samples. The ends are shelves and the middle bands are peaks, about
 * two octaves apart, so five controls still cover the range. Gain changes
 * take effect on the next processed block.
 *
 * <p>The safety limiter lives at the end of {@link Effects}, after every
 * stage that can add level.
 */
public final class Equalizer {

    public static final double[] FREQUENCIES = {60, 230, 910, 3600, 14000};
    public static final String[] LABELS = {"60", "230", "910", "3.6k", "14k"};
    private static final double[] Q = {0.707, 0.9, 0.9, 0.9, 0.707};
    private enum Kind { LOW_SHELF, PEAK, HIGH_SHELF }
    private static final Kind[] KINDS = {
            Kind.LOW_SHELF, Kind.PEAK, Kind.PEAK, Kind.PEAK, Kind.HIGH_SHELF
    };

    public static final int BANDS = 5;
    public static final int MAX_GAIN_DB = 12;

    private final double sampleRate;
    private final Biquad[] left = new Biquad[BANDS];
    private final Biquad[] right = new Biquad[BANDS];
    private final int[] gains = new int[BANDS];

    private volatile boolean enabled = true;
    private volatile int preampDb = 0;
    private volatile boolean dirty = true;

    public Equalizer(double sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < BANDS; i++) {
            left[i] = new Biquad();
            right[i] = new Biquad();
        }
    }

    public synchronized int gain(int band) {
        return gains[band];
    }

    public synchronized int[] gains() {
        return gains.clone();
    }

    public synchronized void setGain(int band, int db) {
        int clamped = Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, db));
        if (gains[band] != clamped) {
            gains[band] = clamped;
            dirty = true;
        }
    }

    public synchronized void setGains(int[] values) {
        for (int i = 0; i < BANDS && i < values.length; i++) {
            gains[i] = Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, values[i]));
        }
        dirty = true;
    }

    /**
     * A saved curve from the five band equalizer. A blank value, or a curve
     * saved by the old ten band equalizer, comes back flat.
     */
    public static int[] gainsFromSaved(String raw) {
        int[] flat = new int[BANDS];
        if (raw == null || raw.isBlank()) return flat;
        String[] parts = raw.split(",");
        if (parts.length != BANDS) return flat;
        int[] out = new int[BANDS];
        for (int i = 0; i < BANDS; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return flat;
            }
        }
        return out;
    }

    public void apply(EqPreset preset) {
        setGains(preset.gains());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public int preamp() {
        return preampDb;
    }

    public void setPreamp(int db) {
        preampDb = Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, db));
    }

    public void reset() {
        setGains(new int[BANDS]);
        setPreamp(0);
    }

    /** Clears filter state; call when the audio source changes. */
    public synchronized void flush() {
        for (int i = 0; i < BANDS; i++) {
            left[i].reset();
            right[i].reset();
        }
    }

    private synchronized void refresh() {
        if (!dirty) return;
        for (int i = 0; i < BANDS; i++) {
            tune(left[i], i);
            tune(right[i], i);
        }
        dirty = false;
    }

    private void tune(Biquad filter, int band) {
        double freq = FREQUENCIES[band];
        double q = Q[band];
        int gain = gains[band];
        switch (KINDS[band]) {
            case LOW_SHELF -> filter.setLowShelf(sampleRate, freq, q, gain);
            case HIGH_SHELF -> filter.setHighShelf(sampleRate, freq, q, gain);
            case PEAK -> filter.setPeaking(sampleRate, freq, q, gain);
        }
    }

    /** Processes interleaved stereo samples in place. Bypassed audio is left untouched. */
    public void process(float[] buffer, int count) {
        if (!enabled) return;
        refresh();
        double pre = Math.pow(10.0, preampDb / 20.0);
        synchronized (this) {
            for (int i = 0; i + 1 < count; i += 2) {
                double l = buffer[i] * pre;
                double r = buffer[i + 1] * pre;
                for (int b = 0; b < BANDS; b++) {
                    l = left[b].process(l);
                    r = right[b].process(r);
                }
                buffer[i] = (float) l;
                buffer[i + 1] = (float) r;
            }
        }
    }
}
