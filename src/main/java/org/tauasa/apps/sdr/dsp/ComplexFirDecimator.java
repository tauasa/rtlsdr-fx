package org.tauasa.apps.sdr.dsp;

/**
 * A decimating low-pass FIR applied identically to the I and Q channels of an
 * interleaved complex stream. Keeps its delay line across calls so block
 * boundaries are seamless. One complex output is produced per {@code decim}
 * complex inputs.
 */
public final class ComplexFirDecimator {

    private final float[] taps;
    private final int decim;
    private final int n;
    private final float[] zi;
    private final float[] zq;
    private int pos;    // next write index into the ring buffers
    private int phase;  // inputs accumulated since the last output

    public ComplexFirDecimator(float[] taps, int decim) {
        this.taps = taps;
        this.decim = Math.max(1, decim);
        this.n = taps.length;
        this.zi = new float[n];
        this.zq = new float[n];
    }

    /** Maximum complex outputs {@link #process} can emit for the given input count. */
    public int maxOutput(int complexSamples) {
        return complexSamples / decim + 1;
    }

    /**
     * @param in           interleaved IQ input (I,Q,I,Q,...)
     * @param complexInput number of complex samples available in {@code in}
     * @param out          interleaved IQ output buffer
     * @return number of complex output samples written to {@code out}
     */
    public int process(float[] in, int complexInput, float[] out) {
        int outCount = 0;
        for (int s = 0; s < complexInput; s++) {
            zi[pos] = in[2 * s];
            zq[pos] = in[2 * s + 1];
            if (++pos == n) {
                pos = 0;
            }
            if (++phase >= decim) {
                phase = 0;
                float accI = 0f;
                float accQ = 0f;
                int idx = pos - 1;
                if (idx < 0) {
                    idx += n;
                }
                for (int k = 0; k < n; k++) {
                    float t = taps[k];
                    accI += t * zi[idx];
                    accQ += t * zq[idx];
                    if (--idx < 0) {
                        idx += n;
                    }
                }
                out[2 * outCount] = accI;
                out[2 * outCount + 1] = accQ;
                outCount++;
            }
        }
        return outCount;
    }
}
