package org.tauasa.apps.sdr.dsp;

/**
 * A direct-form-I biquad filter with an RBJ "cookbook" band-pass designer.
 *
 * <p>Used by the CW demodulator as the narrow audio filter that pulls a Morse
 * tone out of the noise. The band-pass is the constant-0&nbsp;dB-peak-gain
 * variant, so the tone at the centre frequency passes at unity and everything
 * either side rolls off at 6&nbsp;dB/octave times the order.
 */
public final class Biquad {

    private final double b0;
    private final double b1;
    private final double b2;
    private final double a1;
    private final double a2;

    private double x1;
    private double x2;
    private double y1;
    private double y2;

    private Biquad(double b0, double b1, double b2, double a1, double a2) {
        this.b0 = b0;
        this.b1 = b1;
        this.b2 = b2;
        this.a1 = a1;
        this.a2 = a2;
    }

    /**
     * RBJ band-pass with 0&nbsp;dB peak gain, centred at {@code f0} with the
     * given -3&nbsp;dB {@code bandwidth} (both in Hz).
     */
    public static Biquad bandPass(double f0, double bandwidth, double sampleRate) {
        double w0 = 2.0 * Math.PI * f0 / sampleRate;
        double q = Math.max(0.5, f0 / Math.max(1.0, bandwidth));
        double alpha = Math.sin(w0) / (2.0 * q);
        double cosw = Math.cos(w0);
        double a0 = 1.0 + alpha;
        return new Biquad(
                alpha / a0,
                0.0,
                -alpha / a0,
                (-2.0 * cosw) / a0,
                (1.0 - alpha) / a0);
    }

    /** Clears the filter memory. */
    public void reset() {
        x1 = 0.0;
        x2 = 0.0;
        y1 = 0.0;
        y2 = 0.0;
    }

    /** Filters a single sample. */
    public float process(float x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;
        return (float) y;
    }
}
