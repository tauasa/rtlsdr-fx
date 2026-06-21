package org.tauasa.apps.sdr.dsp;

/** Analysis window functions. */
public final class Window {

    private Window() {
    }

    public static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
        }
        return w;
    }

    public static double[] blackman(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            double x = 2.0 * Math.PI * i / (n - 1);
            w[i] = 0.42 - 0.5 * Math.cos(x) + 0.08 * Math.cos(2.0 * x);
        }
        return w;
    }
}
