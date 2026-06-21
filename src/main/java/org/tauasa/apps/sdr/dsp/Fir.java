package org.tauasa.apps.sdr.dsp;

/** Small FIR design helpers. */
public final class Fir {

    private Fir() {
    }

    /**
     * Designs a windowed-sinc (Hamming) low-pass FIR with unity DC gain.
     *
     * @param numTaps number of taps (an odd value gives a linear-phase Type-I filter)
     * @param fcNorm  cutoff frequency as a fraction of the sample rate, i.e. {@code fc / fs}
     *                (so the usable range is {@code 0 < fcNorm < 0.5})
     */
    public static float[] lowPass(int numTaps, double fcNorm) {
        if (numTaps < 1) {
            numTaps = 1;
        }
        if (fcNorm <= 0) {
            fcNorm = 1e-4;
        }
        if (fcNorm >= 0.5) {
            fcNorm = 0.4999;
        }
        float[] h = new float[numTaps];
        int m = numTaps - 1;
        double wc = 2.0 * Math.PI * fcNorm; // radians per sample
        double sum = 0.0;
        for (int n = 0; n < numTaps; n++) {
            double x = n - m / 2.0;
            double sinc = (Math.abs(x) < 1e-9) ? wc : Math.sin(wc * x) / x;
            double win = (m == 0) ? 1.0 : 0.54 - 0.46 * Math.cos(2.0 * Math.PI * n / m);
            double v = sinc * win;
            h[n] = (float) v;
            sum += v;
        }
        // Normalise so the DC gain is exactly 1.0 (cancels the missing 1/pi factor too).
        if (sum != 0.0) {
            for (int n = 0; n < numTaps; n++) {
                h[n] /= (float) sum;
            }
        }
        return h;
    }
}
