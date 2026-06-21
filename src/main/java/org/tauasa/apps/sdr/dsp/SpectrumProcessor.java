package org.tauasa.apps.sdr.dsp;

/**
 * Turns a block of interleaved complex IQ samples into an fft-shifted power
 * spectrum expressed in decibels. Not thread-safe on its own; callers should
 * invoke {@link #process(float[])} from a single thread (the source reader).
 */
public final class SpectrumProcessor {

    private final int n;
    private final double[] window;
    private final double[] re;
    private final double[] im;
    private final double windowGain;

    public SpectrumProcessor(int n) {
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a power of two >= 2");
        }
        this.n = n;
        this.window = Window.hann(n);
        this.re = new double[n];
        this.im = new double[n];
        double g = 0;
        for (double w : window) {
            g += w;
        }
        this.windowGain = g; // coherent gain, normalises a full-scale tone toward 0 dB
    }

    public int size() {
        return n;
    }

    /**
     * @param interleaved length {@code 2n}: index {@code 2k} = I, {@code 2k+1} = Q.
     * @return fresh array of length {@code n}, fft-shifted (DC centred), power in dB.
     */
    public float[] process(float[] interleaved) {
        for (int k = 0; k < n; k++) {
            double w = window[k];
            re[k] = interleaved[2 * k] * w;
            im[k] = interleaved[2 * k + 1] * w;
        }
        Fft.transform(re, im);

        float[] out = new float[n];
        double norm = 1.0 / windowGain;
        int half = n / 2;
        for (int k = 0; k < n; k++) {
            int src = (k + half) % n; // fftshift: place DC at the centre
            double rr = re[src] * norm;
            double ii = im[src] * norm;
            double power = rr * rr + ii * ii;
            out[k] = (float) (10.0 * Math.log10(power + 1e-12));
        }
        return out;
    }
}
