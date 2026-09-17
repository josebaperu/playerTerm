package org.example.player.audio;

/**
 * A single biquad section in transposed direct form II.
 * Coefficients follow the RBJ audio EQ cookbook.
 */
public final class Biquad {
    private double b0 = 1, b1, b2, a1, a2;
    private double z1, z2;

    /** Configures this section as a peaking EQ filter. */
    public void setPeaking(double sampleRate, double freq, double q, double gainDb) {
        if (freq >= sampleRate / 2.0) {
            // Band sits above Nyquist: pass audio through untouched.
            b0 = 1; b1 = b2 = a1 = a2 = 0;
            return;
        }
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);

        double nb0 = 1 + alpha * a;
        double nb1 = -2 * cos;
        double nb2 = 1 - alpha * a;
        double na0 = 1 + alpha / a;
        double na1 = -2 * cos;
        double na2 = 1 - alpha / a;

        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
    }

    public double process(double x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    public void reset() {
        z1 = z2 = 0;
    }
}
