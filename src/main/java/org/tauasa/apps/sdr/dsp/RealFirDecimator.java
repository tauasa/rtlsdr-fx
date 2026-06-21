package org.tauasa.apps.sdr.dsp;

/**
 * A decimating low-pass FIR for a single (real) channel, e.g. the demodulated
 * audio before it is brought down to the playback rate. Keeps its delay line
 * across calls.
 */
public final class RealFirDecimator {

    private final float[] taps;
    private final int decim;
    private final int n;
    private final float[] z;
    private int pos;
    private int phase;

    public RealFirDecimator(float[] taps, int decim) {
        this.taps = taps;
        this.decim = Math.max(1, decim);
        this.n = taps.length;
        this.z = new float[n];
    }

    public int maxOutput(int samples) {
        return samples / decim + 1;
    }

    /**
     * @param in     real input samples
     * @param count  number of valid samples in {@code in}
     * @param out    output buffer
     * @return number of output samples written
     */
    public int process(float[] in, int count, float[] out) {
        int outCount = 0;
        for (int s = 0; s < count; s++) {
            z[pos] = in[s];
            if (++pos == n) {
                pos = 0;
            }
            if (++phase >= decim) {
                phase = 0;
                float acc = 0f;
                int idx = pos - 1;
                if (idx < 0) {
                    idx += n;
                }
                for (int k = 0; k < n; k++) {
                    acc += taps[k] * z[idx];
                    if (--idx < 0) {
                        idx += n;
                    }
                }
                out[outCount++] = acc;
            }
        }
        return outCount;
    }
}
