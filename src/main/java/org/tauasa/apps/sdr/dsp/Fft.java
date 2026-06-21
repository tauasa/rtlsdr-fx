package org.tauasa.apps.sdr.dsp;

/**
 * Iterative in-place radix-2 Cooley-Tukey FFT. Operates on parallel
 * real/imaginary arrays whose length must be a power of two.
 */
public final class Fft {

    private Fft() {
    }

    /** Forward FFT, in place. {@code re} and {@code im} must share length n = 2^k. */
    public static void transform(double[] re, double[] im) {
        int n = re.length;
        if (n == 0) {
            return;
        }
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("length must be a power of two, was " + n);
        }
        if (im.length != n) {
            throw new IllegalArgumentException("re/im length mismatch");
        }

        // Bit-reversal permutation.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }

        // Butterfly stages.
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len; // forward transform
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int k = 0; k < half; k++) {
                    int a = i + k;
                    int b = a + half;
                    double tRe = re[b] * curRe - im[b] * curIm;
                    double tIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - tRe;
                    im[b] = im[a] - tIm;
                    re[a] += tRe;
                    im[a] += tIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
