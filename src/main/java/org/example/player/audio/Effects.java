package org.example.player.audio;

import org.example.player.config.Settings;

/**
 * Stereo effects after the equalizer. Ported from aaradio's processor, minus
 * mono and the Android spatializer:
 *
 * <pre>
 *   bass → width → virtual surround | crossfeed → reverb → leveler → limiter
 * </pre>
 *
 * Surround already contains a crossfeed, so the crossfeed stage stays idle
 * while surround is on. Toggles cross-fade over 30 ms. Volume is applied just
 * before the limiter, which only pulls peaks down past about −0.3 dBFS.
 */
public final class Effects {

    public static final int BASS = 0;
    public static final int WIDTH = 1;
    public static final int SURROUND = 2;
    public static final int CROSSFEED = 3;
    public static final int REVERB = 4;
    public static final int LEVELER = 5;
    public static final int COUNT = 6;
    public static final String[] NAMES = {
            "Bass", "Width", "Surround", "Crossfeed", "Reverb", "Leveler"
    };

    /** Room sizes for {@link #REVERB}. */
    public enum ReverbPreset {
        ROOM("Room", 0.45, 0.5, 0.10),
        CLUB("Club", 0.68, 0.4, 0.14),
        HALL("Hall", 0.88, 0.25, 0.18);

        public final String label;
        final double room;
        final double damp;
        final double wet;

        ReverbPreset(String label, double room, double damp, double wet) {
            this.label = label;
            this.room = room;
            this.damp = damp;
            this.wet = wet;
        }

        public static ReverbPreset byName(String name) {
            for (ReverbPreset preset : values()) {
                if (preset.label.equalsIgnoreCase(name) || preset.name().equalsIgnoreCase(name)) return preset;
            }
            return ROOM;
        }
    }

    private final double sampleRate;
    private final Stage[] stages;
    private final Limiter limiter = new Limiter();
    private final float[] mix = new float[COUNT];
    private final boolean[] cleared = new boolean[COUNT];
    private final float[] left;
    private final float[] right;
    private final float[] dryL;
    private final float[] dryR;
    private final float fadeStep;

    private volatile boolean bass;
    private volatile double bassAmount = 0.5;
    private volatile boolean widthOn;
    private volatile double widthAmount = 1.4;
    private volatile boolean surround;
    private volatile boolean crossfeed;
    private volatile boolean reverb;
    private volatile ReverbPreset reverbPreset = ReverbPreset.ROOM;
    private volatile boolean leveler;

    public Effects(double sampleRate) {
        this(sampleRate, 2048);
    }

    /** {@code maxFrames} is the largest block {@link #process} will be given. */
    Effects(double sampleRate, int maxFrames) {
        this.sampleRate = sampleRate;
        this.fadeStep = (float) (1.0 / (0.03 * sampleRate));
        BassStage bassStage = new BassStage();
        WidthStage widthStage = new WidthStage();
        SurroundStage surroundStage = new SurroundStage();
        CrossfeedStage crossfeedStage = new CrossfeedStage();
        ReverbStage reverbStage = new ReverbStage();
        LevelerStage levelerStage = new LevelerStage();
        stages = new Stage[] {bassStage, widthStage, surroundStage, crossfeedStage, reverbStage, levelerStage};
        for (Stage stage : stages) stage.configure(sampleRate);
        limiter.configure(sampleRate);
        left = new float[maxFrames];
        right = new float[maxFrames];
        dryL = new float[maxFrames];
        dryR = new float[maxFrames];
        for (int i = 0; i < COUNT; i++) cleared[i] = true;
    }

    public boolean enabled(int index) {
        return switch (index) {
            case BASS -> bass;
            case WIDTH -> widthOn;
            case SURROUND -> surround;
            case CROSSFEED -> crossfeed && !surround;
            case REVERB -> reverb;
            case LEVELER -> leveler;
            default -> false;
        };
    }

    /** Crossfeed's own switch is hidden while surround is doing that job. */
    public boolean dimmed(int index) {
        return index == CROSSFEED && surround;
    }

    /** The value drawn at the right of an effect row. */
    public String rowValue(int index) {
        return switch (index) {
            case BASS -> bass ? percent(bassAmount) : "off";
            case WIDTH -> widthOn ? percent(widthAmount) : "off";
            case SURROUND -> surround ? "on" : "off";
            case CROSSFEED -> surround ? "incl." : crossfeed ? "on" : "off";
            case REVERB -> reverb ? reverbPreset.label : "off";
            case LEVELER -> leveler ? "on" : "off";
            default -> "";
        };
    }

    /** One line for the status row after a key or a click. */
    public String describe(int index) {
        if (index < 0 || index >= COUNT) return "";
        if (index == REVERB && !reverb) return "reverb off · " + reverbPreset.label;
        return NAMES[index].toLowerCase() + " " + rowValue(index);
    }

    public void toggle(int index) {
        if (index < 0 || index >= COUNT) return;
        if (index == CROSSFEED && surround) return;
        setOn(index, !rawOn(index));
    }

    /**
     * ←→ on a row. Bass, width and the reverb room move; turning bass or width
     * this way switches that effect on so the change is audible. Returns false
     * for a row that has no amount.
     */
    public boolean adjust(int index, int direction) {
        switch (index) {
            case BASS -> {
                bassAmount = snap(clamp(bassAmount + direction * 0.05, 0.1, 1.0));
                bass = true;
                return true;
            }
            case WIDTH -> {
                widthAmount = snap(clamp(widthAmount + direction * 0.05, 0.0, 2.0));
                widthOn = true;
                return true;
            }
            case REVERB -> {
                ReverbPreset[] all = ReverbPreset.values();
                int i = reverbPreset.ordinal() + direction;
                reverbPreset = all[Math.floorMod(i, all.length)];
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Wheel on a row: amounts move, and a plain toggle follows the wheel. */
    public void nudge(int index, int direction) {
        if (!adjust(index, direction)) setOn(index, direction > 0);
    }

    public void read(Settings settings) {
        bass = settings.getBool("fx.bass", false);
        bassAmount = snap(clamp(settings.getDouble("fx.bassAmount", 0.5), 0.1, 1.0));
        widthOn = settings.getBool("fx.width", false);
        widthAmount = snap(clamp(settings.getDouble("fx.widthAmount", 1.4), 0.0, 2.0));
        surround = settings.getBool("fx.surround", false);
        crossfeed = settings.getBool("fx.crossfeed", false);
        reverb = settings.getBool("fx.reverb", false);
        reverbPreset = ReverbPreset.byName(settings.getString("fx.reverbPreset", "Room"));
        leveler = settings.getBool("fx.leveler", false);
    }

    public void write(Settings settings) {
        settings.set("fx.bass", bass);
        settings.set("fx.bassAmount", bassAmount);
        settings.set("fx.width", widthOn);
        settings.set("fx.widthAmount", widthAmount);
        settings.set("fx.surround", surround);
        settings.set("fx.crossfeed", crossfeed);
        settings.set("fx.reverb", reverb);
        settings.set("fx.reverbPreset", reverbPreset.label);
        settings.set("fx.leveler", leveler);
    }

    /** Clears delay lines. A new track or a seek starts dry. */
    public synchronized void flush() {
        for (Stage stage : stages) stage.reset();
        limiter.reset();
        boolean[] on = targets();
        for (int i = 0; i < COUNT; i++) {
            mix[i] = on[i] ? 1f : 0f;
            cleared[i] = !on[i];
        }
    }

    /**
     * Processes interleaved stereo in place, then applies {@code volume} and the limiter.
     * {@code count} is the number of floats, two per frame.
     */
    public void process(float[] interleaved, int count, float volume) {
        int n = count / 2;
        if (n <= 0) return;
        synchronized (this) {
            if (n > left.length) {
                // The player blocks are fixed. A longer one is handled in slices.
                for (int offset = 0; offset < n; offset += left.length) {
                    int slice = Math.min(left.length, n - offset);
                    processSlice(interleaved, offset * 2, slice, volume);
                }
                return;
            }
            processSlice(interleaved, 0, n, volume);
        }
    }

    private void processSlice(float[] interleaved, int floatOffset, int n, float volume) {
        for (int f = 0; f < n; f++) {
            left[f] = interleaved[floatOffset + f * 2];
            right[f] = interleaved[floatOffset + f * 2 + 1];
        }
        runStages(n);
        limiter.process(left, right, n, volume);
        for (int f = 0; f < n; f++) {
            interleaved[floatOffset + f * 2] = left[f];
            interleaved[floatOffset + f * 2 + 1] = right[f];
        }
    }

    private void runStages(int n) {
        boolean[] on = targets();
        pushAmounts();
        for (int s = 0; s < COUNT; s++) {
            float target = on[s] ? 1f : 0f;
            if (mix[s] == 0f && target == 0f) {
                if (!cleared[s]) {
                    stages[s].reset();
                    cleared[s] = true;
                }
                continue;
            }
            cleared[s] = false;
            if (mix[s] == 1f && target == 1f) {
                stages[s].process(left, right, n);
                continue;
            }
            System.arraycopy(left, 0, dryL, 0, n);
            System.arraycopy(right, 0, dryR, 0, n);
            stages[s].process(left, right, n);
            float m = mix[s];
            for (int i = 0; i < n; i++) {
                m = target > m ? Math.min(1f, m + fadeStep) : Math.max(0f, m - fadeStep);
                left[i] = dryL[i] + (left[i] - dryL[i]) * m;
                right[i] = dryR[i] + (right[i] - dryR[i]) * m;
            }
            mix[s] = m;
        }
    }

    private boolean[] targets() {
        return new boolean[] {
                bass,
                widthOn,
                surround,
                crossfeed && !surround,
                reverb,
                leveler
        };
    }

    private void pushAmounts() {
        ((BassStage) stages[BASS]).amount = bassAmount;
        ((WidthStage) stages[WIDTH]).width = widthAmount;
        ((ReverbStage) stages[REVERB]).preset = reverbPreset;
    }

    private boolean rawOn(int index) {
        return switch (index) {
            case BASS -> bass;
            case WIDTH -> widthOn;
            case SURROUND -> surround;
            case CROSSFEED -> crossfeed;
            case REVERB -> reverb;
            case LEVELER -> leveler;
            default -> false;
        };
    }

    private void setOn(int index, boolean value) {
        switch (index) {
            case BASS -> bass = value;
            case WIDTH -> widthOn = value;
            case SURROUND -> surround = value;
            case CROSSFEED -> {
                if (!surround) crossfeed = value;
            }
            case REVERB -> reverb = value;
            case LEVELER -> leveler = value;
            default -> { }
        }
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Snaps to 5% steps, the same grid the width slider uses. */
    private static double snap(double value) {
        return Math.round(value * 20.0) / 20.0;
    }

    // ---------------------------------------------------------------- DSP

    private abstract static class Stage {
        double fs = 44100;

        final void configure(double sampleRate) {
            fs = sampleRate;
            onConfigure();
        }

        abstract void onConfigure();

        abstract void reset();

        abstract void process(float[] l, float[] r, int n);
    }

    /** Harmonics of the sub-120 Hz band, plus a little of the real bass. */
    private static final class BassStage extends Stage {
        double amount = 0.5;
        private final Biquad lp1 = new Biquad();
        private final Biquad lp2 = new Biquad();
        private final Biquad hp1 = new Biquad();
        private final Biquad hp2 = new Biquad();
        private final Biquad lpH = new Biquad();
        private double env;
        private double release;

        @Override void onConfigure() {
            lp1.setLowPass(fs, 120, 0.707);
            lp2.setLowPass(fs, 120, 0.707);
            hp1.setHighPass(fs, 120, 0.707);
            hp2.setHighPass(fs, 120, 0.707);
            lpH.setLowPass(fs, 900, 0.707);
            release = smoothing(0.05, fs);
            env = 0;
        }

        @Override void reset() {
            lp1.reset();
            lp2.reset();
            hp1.reset();
            hp2.reset();
            lpH.reset();
            env = 0;
        }

        @Override void process(float[] l, float[] r, int n) {
            for (int i = 0; i < n; i++) {
                double low = lp2.process(lp1.process((l[i] + r[i]) * 0.5));
                env = Math.max(Math.abs(low), env * release);
                double sat = Math.tanh(low / (env + 1e-6) * 2.5) * env;
                double harmonics = lpH.process(hp2.process(hp1.process(sat)));
                float add = (float) (amount * (1.4 * harmonics + 0.35 * low));
                l[i] += add;
                r[i] += add;
            }
        }
    }

    /** Mid/side width. Only the part above 150 Hz is widened, so bass stays centred. */
    private static final class WidthStage extends Stage {
        double width = 1.4;
        private final Biquad hp = new Biquad();

        @Override void onConfigure() {
            hp.setHighPass(fs, 150, 0.707);
        }

        @Override void reset() {
            hp.reset();
        }

        @Override void process(float[] l, float[] r, int n) {
            for (int i = 0; i < n; i++) {
                double mid = (l[i] + r[i]) * 0.5;
                double side = (l[i] - r[i]) * 0.5;
                double sideHigh = hp.process(side);
                double s = width >= 1 ? side + (width - 1) * sideHigh : side * width;
                l[i] = (float) (mid + s);
                r[i] = (float) (mid - s);
            }
        }
    }

    /**
     * Headphone crossfeed after Bauer: a delayed, low-passed copy of each
     * channel feeds the other ear, and the direct path gets a matching high
     * boost so mono content stays flat.
     */
    private static final class CrossfeedStage extends Stage {
        private final double g = Math.pow(10, -4.5 / 20);
        private final OnePole lpL = new OnePole(700);
        private final OnePole lpR = new OnePole(700);
        private final Delay dL = new Delay(0.002);
        private final Delay dR = new Delay(0.002);
        private int delay = 13;

        @Override void onConfigure() {
            lpL.configure(fs);
            lpR.configure(fs);
            dL.configure(fs);
            dR.configure(fs);
            delay = (int) Math.round(0.0003 * fs);
        }

        @Override void reset() {
            lpL.reset();
            lpR.reset();
            dL.reset();
            dR.reset();
        }

        @Override void process(float[] l, float[] r, int n) {
            double norm = 1 / (1 + g);
            for (int i = 0; i < n; i++) {
                double x = l[i];
                double y = r[i];
                double lowL = lpL.process(x);
                double lowR = lpR.process(y);
                dL.write(lowL);
                dR.write(lowR);
                l[i] = (float) ((x + g * (x - lowL) + g * dR.read(delay)) * norm);
                r[i] = (float) ((y + g * (y - lowR) + g * dL.read(delay)) * norm);
            }
        }
    }

    /**
     * Headphone surround. The stereo pair is placed as speakers at ±30°, the
     * side signal as rear speakers at ±110°, with a few early reflections.
     */
    private static final class SurroundStage extends Stage {
        private final Path frontContraL = new Path(0.00026, 2000, 0.75);
        private final Path frontContraR = new Path(0.00026, 2000, 0.75);
        private final Path rearContraL = new Path(0.00065, 1000, 0.6);
        private final Path rearContraR = new Path(0.00065, 1000, 0.6);
        private final OnePole directShadowL = new OnePole(2000);
        private final OnePole directShadowR = new OnePole(2000);
        private final OnePole rearIpsiL = new OnePole(6000);
        private final OnePole rearIpsiR = new OnePole(6000);
        private final Delay ambienceDelay = new Delay(0.03);
        private final Delay reflections = new Delay(0.03);
        private final OnePole reflLp = new OnePole(5000);
        private int ambSamples;
        private int[] taps = new int[0];
        private final double[] tapGains = {0.22, 0.18, 0.14, 0.1};
        private final double[] tapTimes = {0.0071, 0.0113, 0.0169, 0.0235};

        @Override void onConfigure() {
            for (Path path : new Path[] {frontContraL, frontContraR, rearContraL, rearContraR}) path.configure(fs);
            for (OnePole pole : new OnePole[] {directShadowL, directShadowR, rearIpsiL, rearIpsiR, reflLp}) {
                pole.configure(fs);
            }
            ambienceDelay.configure(fs);
            reflections.configure(fs);
            ambSamples = (int) Math.round(0.015 * fs);
            taps = new int[tapTimes.length];
            for (int i = 0; i < tapTimes.length; i++) taps[i] = (int) Math.round(tapTimes[i] * fs);
        }

        @Override void reset() {
            for (Path path : new Path[] {frontContraL, frontContraR, rearContraL, rearContraR}) path.reset();
            for (OnePole pole : new OnePole[] {directShadowL, directShadowR, rearIpsiL, rearIpsiR, reflLp}) pole.reset();
            ambienceDelay.reset();
            reflections.reset();
        }

        @Override void process(float[] l, float[] r, int n) {
            double norm = 1 / 1.9;
            for (int i = 0; i < n; i++) {
                double x = l[i];
                double y = r[i];
                double dirL = x + 0.75 * (x - directShadowL.process(x));
                double dirR = y + 0.75 * (y - directShadowR.process(y));
                ambienceDelay.write((x - y) * 0.5);
                double amb = ambienceDelay.read(ambSamples) * 0.55;
                double rearL = rearIpsiL.process(amb);
                double rearR = rearIpsiR.process(-amb);
                reflections.write(reflLp.process((x + y) * 0.5));
                double reflL = 0;
                double reflR = 0;
                for (int t = 0; t < taps.length; t++) {
                    double v = reflections.read(taps[t]) * tapGains[t];
                    if ((t & 1) == 0) reflL += v;
                    else reflR += v;
                }
                double outL = dirL + frontContraR.process(y) + rearL + rearContraR.process(-amb) + reflL;
                double outR = dirR + frontContraL.process(x) + rearR + rearContraL.process(amb) + reflR;
                l[i] = (float) (outL * norm);
                r[i] = (float) (outR * norm);
            }
        }

        private static final class Path {
            private final double itd;
            private final OnePole shadow;
            private final Delay delay = new Delay(0.002);
            private final double gain;
            private int samples;

            Path(double itdSeconds, double shadowHz, double gain) {
                this.itd = itdSeconds;
                this.shadow = new OnePole(shadowHz);
                this.gain = gain;
            }

            void configure(double fs) {
                shadow.configure(fs);
                delay.configure(fs);
                samples = (int) Math.round(itd * fs);
            }

            void reset() {
                shadow.reset();
                delay.reset();
            }

            double process(double x) {
                delay.write(shadow.process(x));
                return gain * delay.read(samples);
            }
        }
    }

    /** Jezar's public-domain Freeverb. */
    private static final class ReverbStage extends Stage {
        ReverbPreset preset = ReverbPreset.ROOM;
        private static final int[] COMB = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        private static final int[] ALLPASS = {556, 441, 341, 225};
        private static final int SPREAD = 23;
        private Comb[] combsL = new Comb[0];
        private Comb[] combsR = new Comb[0];
        private Allpass[] apL = new Allpass[0];
        private Allpass[] apR = new Allpass[0];

        @Override void onConfigure() {
            reset();
        }

        @Override void reset() {
            double k = fs / 44100.0;
            combsL = combs(COMB, 0, k);
            combsR = combs(COMB, SPREAD, k);
            apL = allpasses(ALLPASS, 0, k);
            apR = allpasses(ALLPASS, SPREAD, k);
        }

        @Override void process(float[] l, float[] r, int n) {
            double feedback = preset.room * 0.28 + 0.7;
            double damp = preset.damp * 0.4;
            double wet = preset.wet * 3;
            for (int i = 0; i < n; i++) {
                double input = (l[i] + r[i]) * 0.015;
                double outL = 0;
                double outR = 0;
                for (Comb comb : combsL) outL += comb.process(input, feedback, damp);
                for (Comb comb : combsR) outR += comb.process(input, feedback, damp);
                for (Allpass allpass : apL) outL = allpass.process(outL);
                for (Allpass allpass : apR) outR = allpass.process(outR);
                l[i] = (float) (l[i] + outL * wet);
                r[i] = (float) (r[i] + outR * wet);
            }
        }

        private static Comb[] combs(int[] tunings, int spread, double k) {
            Comb[] out = new Comb[tunings.length];
            for (int i = 0; i < tunings.length; i++) out[i] = new Comb(size(tunings[i] + spread, k));
            return out;
        }

        private static Allpass[] allpasses(int[] tunings, int spread, double k) {
            Allpass[] out = new Allpass[tunings.length];
            for (int i = 0; i < tunings.length; i++) out[i] = new Allpass(size(tunings[i] + spread, k));
            return out;
        }

        private static int size(int tuning, double k) {
            return Math.max(1, (int) Math.round(tuning * k));
        }

        private static final class Comb {
            private final double[] buf;
            private int idx;
            private double store;

            Comb(int size) {
                buf = new double[size];
            }

            double process(double x, double feedback, double damp) {
                double out = buf[idx];
                store = out * (1 - damp) + store * damp;
                if (Math.abs(store) < 1e-18) store = 0;
                buf[idx] = x + store * feedback;
                if (++idx >= buf.length) idx = 0;
                return out;
            }
        }

        private static final class Allpass {
            private final double[] buf;
            private int idx;

            Allpass(int size) {
                buf = new double[size];
            }

            double process(double x) {
                double b = buf[idx];
                buf[idx] = x + b * 0.5;
                if (++idx >= buf.length) idx = 0;
                return b - x;
            }
        }
    }

    /** Slow RMS compressor with make-up gain. Silence is not boosted. */
    private static final class LevelerStage extends Stage {
        private static final double THRESHOLD_DB = -26;
        private static final double RATIO = 3;
        private static final double MAKEUP_DB = 9;
        private double ms;
        private double gainDb;
        private double det;
        private double attack;
        private double release;

        @Override void onConfigure() {
            det = smoothing(0.08, fs);
            attack = smoothing(0.02, fs);
            release = smoothing(0.5, fs);
            reset();
        }

        @Override void reset() {
            ms = 0;
            gainDb = 0;
        }

        @Override void process(float[] l, float[] r, int n) {
            for (int i = 0; i < n; i++) {
                double p = (l[i] * l[i] + r[i] * r[i]) * 0.5;
                ms = p + det * (ms - p);
                double level = 10 * Math.log10(ms + 1e-12);
                double compressed = level > THRESHOLD_DB
                        ? THRESHOLD_DB + (level - THRESHOLD_DB) / RATIO - level
                        : 0;
                double makeup = MAKEUP_DB * clamp((level + 60) / 10, 0, 1);
                double target = compressed + makeup;
                double coef = target < gainDb ? attack : release;
                gainDb = target + coef * (gainDb - target);
                float g = (float) Math.pow(10, gainDb / 20);
                l[i] *= g;
                r[i] *= g;
            }
        }
    }

    /** Instant attack, smooth release. Only acts above about −0.3 dBFS. */
    private static final class Limiter {
        private double gain = 1;
        private double release;

        void configure(double fs) {
            release = smoothing(0.06, fs);
            gain = 1;
        }

        void reset() {
            gain = 1;
        }

        void process(float[] l, float[] r, int n, float volume) {
            double ceiling = 0.966;
            for (int i = 0; i < n; i++) {
                double lv = l[i] * volume;
                double rv = r[i] * volume;
                double peak = Math.max(Math.abs(lv), Math.abs(rv));
                double released = 1 + release * (gain - 1);
                gain = peak * released > ceiling ? ceiling / peak : released;
                l[i] = (float) (lv * gain);
                r[i] = (float) (rv * gain);
            }
        }
    }

    /** First-order low-pass, used for head shadow and smoothing. */
    private static final class OnePole {
        private final double freq;
        private double a = 1;
        private double y;

        OnePole(double freq) {
            this.freq = freq;
        }

        void configure(double fs) {
            a = 1 - Math.exp(-2 * Math.PI * freq / fs);
            reset();
        }

        void reset() {
            y = 0;
        }

        double process(double x) {
            y += a * (x - y);
            return y;
        }
    }

    /** Integer-sample delay. The buffer length is the next power of two. */
    private static final class Delay {
        private final double maxSeconds;
        private double[] buf = new double[1];
        private int mask;
        private int pos;

        Delay(double maxSeconds) {
            this.maxSeconds = maxSeconds;
        }

        void configure(double fs) {
            int size = 1;
            int need = (int) (maxSeconds * fs) + 2;
            while (size < need) size <<= 1;
            buf = new double[size];
            mask = size - 1;
            pos = 0;
        }

        void reset() {
            java.util.Arrays.fill(buf, 0);
        }

        void write(double x) {
            buf[pos] = x;
            pos = (pos + 1) & mask;
        }

        /** Sample written {@code delay} samples before the most recent one. */
        double read(int delay) {
            return buf[(pos - 1 - delay) & mask];
        }
    }

    private static double smoothing(double seconds, double fs) {
        return Math.exp(-1.0 / (seconds * fs));
    }
}
