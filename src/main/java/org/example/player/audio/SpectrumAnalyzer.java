package org.example.player.audio;

/**
 * Keeps a rolling window of the audio being played and turns it into
 * log spaced spectrum bars. The audio thread only copies samples in;
 * the FFT itself runs on the render thread.
 */
public final class SpectrumAnalyzer {

    private static final int FFT_SIZE = 2048;
    private static final double MIN_DB = -72;
    private static final double MAX_DB = -6;

    private final double sampleRate;
    private final float[] ring = new float[FFT_SIZE];
    private final double[] window = new double[FFT_SIZE];
    private final double[] re = new double[FFT_SIZE];
    private final double[] im = new double[FFT_SIZE];
    private final double[] magnitude = new double[FFT_SIZE / 2];

    private int writeIndex;
    private double[] smoothed = new double[0];
    private double[] peaks = new double[0];
    private double[] peakFall = new double[0];
    private volatile float levelLeft;
    private volatile float levelRight;

    public SpectrumAnalyzer(double sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
        }
    }

    /** Feeds interleaved stereo samples from the audio thread. */
    public void feed(float[] buffer, int count) {
        float peakL = 0, peakR = 0;
        synchronized (ring) {
            for (int i = 0; i + 1 < count; i += 2) {
                float l = buffer[i];
                float r = buffer[i + 1];
                ring[writeIndex] = (l + r) * 0.5f;
                writeIndex = (writeIndex + 1) % FFT_SIZE;
                float al = Math.abs(l);
                float ar = Math.abs(r);
                if (al > peakL) peakL = al;
                if (ar > peakR) peakR = ar;
            }
        }
        levelLeft = decayTowards(levelLeft, peakL);
        levelRight = decayTowards(levelRight, peakR);
    }

    private static float decayTowards(float current, float target) {
        return target > current ? target : current * 0.72f + target * 0.28f;
    }

    public float levelLeft() {
        return levelLeft;
    }

    public float levelRight() {
        return levelRight;
    }

    public void silence() {
        levelLeft = 0;
        levelRight = 0;
        synchronized (ring) {
            java.util.Arrays.fill(ring, 0f);
        }
        java.util.Arrays.fill(smoothed, 0);
        java.util.Arrays.fill(peaks, 0);
    }

    /**
     * Returns {@code bars} values in 0..1, smoothed over time.
     * Call from the render thread at frame rate.
     */
    public double[] bars(int bars) {
        if (bars <= 0) return new double[0];
        if (smoothed.length != bars) {
            smoothed = new double[bars];
            peaks = new double[bars];
            peakFall = new double[bars];
        }
        synchronized (ring) {
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] = ring[(writeIndex + i) % FFT_SIZE] * window[i];
                im[i] = 0;
            }
        }
        fft(re, im);
        double norm = 2.0 / FFT_SIZE;
        for (int i = 0; i < magnitude.length; i++) {
            magnitude[i] = Math.hypot(re[i], im[i]) * norm;
        }

        double lowHz = 32;
        double highHz = Math.min(18000, sampleRate / 2 - 1);
        double binHz = sampleRate / FFT_SIZE;
        for (int b = 0; b < bars; b++) {
            double f0 = lowHz * Math.pow(highHz / lowHz, (double) b / bars);
            double f1 = lowHz * Math.pow(highHz / lowHz, (double) (b + 1) / bars);
            int i0 = (int) Math.floor(f0 / binHz);
            int i1 = Math.max(i0 + 1, (int) Math.ceil(f1 / binHz));
            i0 = Math.max(1, Math.min(magnitude.length - 1, i0));
            i1 = Math.max(i0 + 1, Math.min(magnitude.length, i1));

            double sum = 0;
            for (int i = i0; i < i1; i++) sum = Math.max(sum, magnitude[i]);
            // Gentle tilt so highs, which carry less energy, stay visible.
            double tilt = 1.0 + 1.6 * ((double) b / bars);
            double db = 20 * Math.log10(sum * tilt + 1e-9);
            double v = (db - MIN_DB) / (MAX_DB - MIN_DB);
            v = Math.max(0, Math.min(1, v));

            smoothed[b] = v > smoothed[b] ? v : smoothed[b] * 0.62 + v * 0.38;
            if (smoothed[b] >= peaks[b]) {
                peaks[b] = smoothed[b];
                peakFall[b] = 0;
            } else {
                peakFall[b] += 0.0016;
                peaks[b] = Math.max(smoothed[b], peaks[b] - peakFall[b]);
            }
        }
        return smoothed.clone();
    }

    /** Peak hold positions matching the last {@link #bars(int)} call. */
    public double[] peaks() {
        return peaks.clone();
    }

    /** In place iterative radix-2 FFT. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
