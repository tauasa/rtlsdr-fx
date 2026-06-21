package org.tauasa.apps.sdr.ui;

/** Maps a normalised intensity in [0,1] to an ARGB int for the waterfall. */
public final class ColorMap {

    private ColorMap() {
    }

    // black -> navy -> blue -> cyan -> green -> yellow -> red -> white
    private static final int[][] STOPS = {
            {0, 0, 0}, {0, 0, 80}, {0, 40, 200}, {0, 200, 220},
            {40, 210, 60}, {240, 230, 40}, {240, 60, 30}, {255, 255, 255}
    };

    public static int color(double v) {
        if (v <= 0) {
            return argb(STOPS[0]);
        }
        if (v >= 1) {
            return argb(STOPS[STOPS.length - 1]);
        }
        double scaled = v * (STOPS.length - 1);
        int i = (int) scaled;
        double f = scaled - i;
        int[] a = STOPS[i];
        int[] b = STOPS[i + 1];
        int r = (int) (a[0] + (b[0] - a[0]) * f);
        int g = (int) (a[1] + (b[1] - a[1]) * f);
        int bl = (int) (a[2] + (b[2] - a[2]) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int argb(int[] c) {
        return 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
    }
}
