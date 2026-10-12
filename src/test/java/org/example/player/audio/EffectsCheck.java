package org.example.player.audio;

import org.example.player.ui.Ui;

import java.util.Arrays;

/**
 * DSP and hit-test checks for the five band equalizer and the effect chain.
 * Run with the project classes on the classpath.
 */
public final class EffectsCheck {

    private static final int RATE = 44100;

    public static void main(String[] args) {
        savedCurve();
        bypassIsTransparent();
        lowShelfBoostsBass();
        crossfeedFeedsTheOtherEar();
        surroundOwnsCrossfeed();
        everythingOnStaysFinite();
        effectsSitBesideTheBands();
        System.out.println("ok");
    }

    private static void savedCurve() {
        check(Arrays.equals(new int[Equalizer.BANDS], Equalizer.gainsFromSaved(null)), "null curve");
        check(Arrays.equals(new int[Equalizer.BANDS], Equalizer.gainsFromSaved("1,2,3,4,5,6,7,8,9,0")), "ten band curve");
        check(Arrays.equals(new int[] {1, 2, 3, 4, 5}, Equalizer.gainsFromSaved("1,2,3,4,5")), "five band curve");
    }

    private static void bypassIsTransparent() {
        Equalizer eq = new Equalizer(RATE);
        eq.setEnabled(false);
        Effects fx = new Effects(RATE);
        fx.flush();
        float[] buf = tone(440, 440, 0.25, 0.2);
        float[] original = buf.clone();
        eq.process(buf, buf.length);
        fx.process(buf, buf.length, 1f);
        for (int i = 0; i < buf.length; i++) {
            if (buf[i] != original[i]) fail("bypass changed sample " + i + " to " + buf[i]);
        }
    }

    private static void lowShelfBoostsBass() {
        float[] low = tone(50, 50, 0.4, 0.12);
        float[] high = tone(4000, 4000, 0.4, 0.12);
        shape(low, 12, 0, 0, 0, 0);
        shape(high, 12, 0, 0, 0, 0);
        double boosted = rmsDb(low) - rmsDb(high);
        if (boosted < 6) fail("low shelf only gained " + boosted + " dB over 4 kHz");

        float[] flatLow = tone(50, 50, 0.4, 0.12);
        float[] flatHigh = tone(4000, 4000, 0.4, 0.12);
        shape(flatLow, 0, 0, 0, 0, 0);
        shape(flatHigh, 0, 0, 0, 0, 0);
        double flat = Math.abs(rmsDb(flatLow) - rmsDb(flatHigh));
        if (flat > 1.5) fail("flat equalizer tilted the spectrum by " + flat + " dB");
    }

    private static void crossfeedFeedsTheOtherEar() {
        Effects fx = new Effects(RATE);
        fx.toggle(Effects.CROSSFEED);
        fx.flush();
        float[] buf = tone(300, 0, 0.3, 0.4);
        fx.process(buf, buf.length, 1f);
        double right = rms(buf, 1);
        if (right < 1e-3) fail("crossfeed left the right channel silent (" + right + ")");
    }

    private static void surroundOwnsCrossfeed() {
        float[] only = tone(300, 3000, 0.4, 0.3);
        float[] both = only.clone();
        Effects surround = new Effects(RATE);
        surround.toggle(Effects.SURROUND);
        surround.flush();
        Effects withCrossfeed = new Effects(RATE);
        withCrossfeed.toggle(Effects.CROSSFEED);
        withCrossfeed.toggle(Effects.SURROUND);
        withCrossfeed.flush();
        surround.process(only, only.length, 1f);
        withCrossfeed.process(both, both.length, 1f);
        for (int i = 0; i < only.length; i++) {
            if (only[i] != both[i]) fail("surround still ran crossfeed");
        }
        if (!withCrossfeed.dimmed(Effects.CROSSFEED)) fail("crossfeed row should rest while surround is on");
    }

    private static void everythingOnStaysFinite() {
        Equalizer eq = new Equalizer(RATE);
        eq.setEnabled(true);
        eq.apply(EqPreset.byName("Bass"));
        Effects fx = new Effects(RATE);
        fx.toggle(Effects.BASS);
        for (int i = 0; i < 20; i++) fx.adjust(Effects.BASS, 1);
        fx.toggle(Effects.WIDTH);
        for (int i = 0; i < 20; i++) fx.adjust(Effects.WIDTH, 1);
        fx.toggle(Effects.CROSSFEED);
        fx.toggle(Effects.SURROUND);
        fx.toggle(Effects.REVERB);
        fx.adjust(Effects.REVERB, 1);
        fx.adjust(Effects.REVERB, 1);
        fx.toggle(Effects.LEVELER);
        fx.flush();
        float[] buf = tone(60, 5000, 0.5, 0.9);
        eq.process(buf, buf.length);
        fx.process(buf, buf.length, 1f);
        double peak = 0;
        double energy = 0;
        int from = buf.length / 2;
        for (int i = from; i < buf.length; i++) {
            float s = buf[i];
            if (!Float.isFinite(s)) fail("non-finite sample");
            peak = Math.max(peak, Math.abs(s));
            energy += s * (double) s;
        }
        if (peak > 0.97) fail("peak " + peak + " passed the limiter");
        if (energy <= 0) fail("effects silenced the signal");
    }

    /** The right-hand columns of the bottom panel are effects, the left are bands. */
    private static void effectsSitBesideTheBands() {
        int w = 80;
        int h = 24;
        int eqW = w - Ui.effectsPanelWidth(w);
        boolean sawEffect = false;
        boolean sawBand = false;
        for (int y = 0; y < h; y++) {
            Ui.Hit effect = Ui.hitTest(w, h, eqW + 2, y, 0);
            Ui.Hit band = Ui.hitTest(w, h, 10, y, 0);
            if (effect.zone() == Ui.Zone.EFFECT && effect.index() >= 0) sawEffect = true;
            if (band.zone() == Ui.Zone.EQ_BAND && band.index() >= 0 && band.index() < Equalizer.BANDS) sawBand = true;
            if (band.zone() == Ui.Zone.EQ_BAND && band.index() >= Equalizer.BANDS) fail("band " + band.index());
        }
        if (!sawEffect) fail("effects panel has no clickable row");
        if (!sawBand) fail("equalizer has no clickable band");
    }

    private static void shape(float[] buf, int... gains) {
        Equalizer eq = new Equalizer(RATE);
        eq.setEnabled(true);
        eq.setGains(gains);
        Effects fx = new Effects(RATE);
        fx.flush();
        eq.process(buf, buf.length);
        fx.process(buf, buf.length, 1f);
    }

    /** Interleaved stereo. A frequency of 0 leaves that channel silent. */
    private static float[] tone(double leftHz, double rightHz, double seconds, double amp) {
        int frames = (int) Math.round(RATE * seconds);
        float[] buf = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            if (leftHz > 0) buf[i * 2] = (float) (Math.sin(2 * Math.PI * leftHz * i / RATE) * amp);
            if (rightHz > 0) buf[i * 2 + 1] = (float) (Math.sin(2 * Math.PI * rightHz * i / RATE) * amp);
        }
        return buf;
    }

    /** RMS of the left channel over the second half of the buffer, in dBFS. */
    private static double rmsDb(float[] buf) {
        double value = rms(buf, 0);
        return 20 * Math.log10(value + 1e-12);
    }

    private static double rms(float[] buf, int channel) {
        int frames = buf.length / 2;
        int from = frames / 2;
        double sum = 0;
        for (int i = from; i < frames; i++) {
            float s = buf[i * 2 + channel];
            sum += s * (double) s;
        }
        return Math.sqrt(sum / (frames - from));
    }

    private static void check(boolean ok, String message) {
        if (!ok) fail(message);
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
