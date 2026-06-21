package org.tauasa.apps.sdr.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FftTest {

    @Test
    void complexExponentialPeaksAtExpectedBin() {
        int n = 64;
        int k0 = 7;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            double ang = 2.0 * Math.PI * k0 * i / n;
            re[i] = Math.cos(ang);
            im[i] = Math.sin(ang);
        }
        Fft.transform(re, im);

        int peak = 0;
        double best = -1;
        for (int i = 0; i < n; i++) {
            double mag = re[i] * re[i] + im[i] * im[i];
            if (mag > best) {
                best = mag;
                peak = i;
            }
        }
        assertEquals(k0, peak, "energy should land in bin k0");
    }

    @Test
    void rejectsNonPowerOfTwo() {
        assertTrue(throwsIllegalArgument(new double[3], new double[3]));
    }

    private boolean throwsIllegalArgument(double[] re, double[] im) {
        try {
            Fft.transform(re, im);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
