package org.example.player.audio;

/**
 * Ten band peaking equalizer with preamp and a soft limiter, applied to
 * interleaved stereo float samples. Gain changes take effect on the next
 * processed block, so sliders respond without interrupting playback.
 */
public final class Equalizer {

    public static final double[] FREQUENCIES = {31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final String[] LABELS = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
    public static final int BANDS = 10;
    public static final int MAX_GAIN_DB = 12;
    /** Roughly one octave of bandwidth per band. */
    private static final double Q = 1.41;
    private static final double LIMIT_KNEE = 0.86;

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
            left[i].setPeaking(sampleRate, FREQUENCIES[i], Q, gains[i]);
            right[i].setPeaking(sampleRate, FREQUENCIES[i], Q, gains[i]);
        }
        dirty = false;
    }

    /**
     * Processes interleaved stereo samples in place.
     *
     * @param extraGain linear gain applied after the filter bank (volume)
     */
    public void process(float[] buffer, int count, float extraGain) {
        boolean on = enabled;
        if (on) refresh();
        double pre = on ? Math.pow(10.0, preampDb / 20.0) : 1.0;
        double g = pre * extraGain;

        synchronized (this) {
            for (int i = 0; i + 1 < count; i += 2) {
                double l = buffer[i] * g;
                double r = buffer[i + 1] * g;
                if (on) {
                    for (int b = 0; b < BANDS; b++) {
                        l = left[b].process(l);
                        r = right[b].process(r);
                    }
                }
                buffer[i] = (float) limit(l);
                buffer[i + 1] = (float) limit(r);
            }
        }
    }

    /** Soft knee limiter: transparent below the knee, saturating above it. */
    private static double limit(double x) {
        double a = Math.abs(x);
        if (a <= LIMIT_KNEE) return x;
        double over = (a - LIMIT_KNEE) / (1.0 - LIMIT_KNEE);
        double shaped = LIMIT_KNEE + (1.0 - LIMIT_KNEE) * Math.tanh(over);
        return Math.copySign(shaped, x);
    }
}
