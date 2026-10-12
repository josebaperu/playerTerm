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
        design(Kind.PEAK, sampleRate, freq, q, gainDb);
    }

    /** Configures this section as a low shelf. */
    public void setLowShelf(double sampleRate, double freq, double q, double gainDb) {
        design(Kind.LOW_SHELF, sampleRate, freq, q, gainDb);
    }

    /** Configures this section as a high shelf. */
    public void setHighShelf(double sampleRate, double freq, double q, double gainDb) {
        design(Kind.HIGH_SHELF, sampleRate, freq, q, gainDb);
    }

    /** Configures this section as a low pass. Gain is unused. */
    public void setLowPass(double sampleRate, double freq, double q) {
        design(Kind.LOW_PASS, sampleRate, freq, q, 0);
    }

    /** Configures this section as a high pass. Gain is unused. */
    public void setHighPass(double sampleRate, double freq, double q) {
        design(Kind.HIGH_PASS, sampleRate, freq, q, 0);
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

    private enum Kind { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS }

    /** RBJ cookbook, with a0 folded into the other coefficients. */
    private void design(Kind kind, double sampleRate, double freq, double q, double gainDb) {
        if (sampleRate <= 0 || freq <= 0) {
            b0 = 1;
            b1 = b2 = a1 = a2 = 0;
            return;
        }
        double f = Math.min(freq, sampleRate * 0.45);
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double nb0, nb1, nb2, na0, na1, na2;
        switch (kind) {
            case PEAK -> {
                nb0 = 1 + alpha * a;
                nb1 = -2 * cos;
                nb2 = 1 - alpha * a;
                na0 = 1 + alpha / a;
                na1 = -2 * cos;
                na2 = 1 - alpha / a;
            }
            case LOW_PASS -> {
                nb0 = (1 - cos) / 2;
                nb1 = 1 - cos;
                nb2 = (1 - cos) / 2;
                na0 = 1 + alpha;
                na1 = -2 * cos;
                na2 = 1 - alpha;
            }
            case HIGH_PASS -> {
                nb0 = (1 + cos) / 2;
                nb1 = -(1 + cos);
                nb2 = (1 + cos) / 2;
                na0 = 1 + alpha;
                na1 = -2 * cos;
                na2 = 1 - alpha;
            }
            case LOW_SHELF -> {
                double k = 2 * Math.sqrt(a) * alpha;
                nb0 = a * ((a + 1) - (a - 1) * cos + k);
                nb1 = 2 * a * ((a - 1) - (a + 1) * cos);
                nb2 = a * ((a + 1) - (a - 1) * cos - k);
                na0 = (a + 1) + (a - 1) * cos + k;
                na1 = -2 * ((a - 1) + (a + 1) * cos);
                na2 = (a + 1) + (a - 1) * cos - k;
            }
            case HIGH_SHELF -> {
                double k = 2 * Math.sqrt(a) * alpha;
                nb0 = a * ((a + 1) + (a - 1) * cos + k);
                nb1 = -2 * a * ((a - 1) + (a + 1) * cos);
                nb2 = a * ((a + 1) + (a - 1) * cos - k);
                na0 = (a + 1) - (a - 1) * cos + k;
                na1 = 2 * ((a - 1) - (a + 1) * cos);
                na2 = (a + 1) - (a - 1) * cos - k;
            }
            default -> {
                nb0 = na0 = 1;
                nb1 = nb2 = na1 = na2 = 0;
            }
        }
        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
    }
}
