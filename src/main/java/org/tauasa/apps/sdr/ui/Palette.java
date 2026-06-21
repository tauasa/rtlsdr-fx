package org.tauasa.apps.sdr.ui;

import java.util.List;

/**
 * A named waterfall colour gradient. Maps a normalised intensity in [0,1] to an
 * ARGB int by linearly interpolating between evenly-spaced colour stops.
 * Replaces the old fixed {@code ColorMap}; several presets are provided and the
 * active palette is selectable from the Settings dialog.
 */
public final class Palette {

    private final String name;
    private final int[][] stops;

    public Palette(String name, int[][] stops) {
        this.name = name;
        this.stops = stops;
    }

    public String name() {
        return name;
    }

    /** Maps intensity in [0,1] to a fully-opaque ARGB int. */
    public int color(double v) {
        if (v <= 0) {
            return argb(stops[0]);
        }
        if (v >= 1) {
            return argb(stops[stops.length - 1]);
        }
        double scaled = v * (stops.length - 1);
        int i = (int) scaled;
        double f = scaled - i;
        int[] a = stops[i];
        int[] b = stops[i + 1];
        int r = (int) (a[0] + (b[0] - a[0]) * f);
        int g = (int) (a[1] + (b[1] - a[1]) * f);
        int bl = (int) (a[2] + (b[2] - a[2]) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int argb(int[] c) {
        return 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    @Override
    public String toString() {
        return name;
    }

    // ---- presets ----

    /** Classic SDR waterfall: black -> navy -> blue -> cyan -> green -> yellow -> red -> white. */
    public static final Palette CLASSIC = new Palette("Classic", new int[][]{
            {0, 0, 0}, {0, 0, 80}, {0, 40, 200}, {0, 200, 220},
            {40, 210, 60}, {240, 230, 40}, {240, 60, 30}, {255, 255, 255}
    });

    public static final Palette INFERNO = new Palette("Inferno", new int[][]{
            {0, 0, 4}, {40, 11, 84}, {101, 21, 110}, {159, 42, 99},
            {212, 72, 66}, {245, 125, 21}, {250, 193, 39}, {252, 255, 164}
    });

    public static final Palette ICE = new Palette("Ice", new int[][]{
            {0, 0, 0}, {0, 10, 40}, {0, 30, 90}, {0, 70, 150},
            {0, 130, 200}, {80, 190, 230}, {180, 230, 245}, {255, 255, 255}
    });

    public static final Palette GREEN = new Palette("Green (CRT)", new int[][]{
            {0, 0, 0}, {0, 20, 0}, {0, 60, 0}, {0, 110, 10},
            {0, 170, 20}, {40, 220, 40}, {150, 245, 90}, {230, 255, 200}
    });

    public static final Palette GRAYSCALE = new Palette("Grayscale", new int[][]{
            {0, 0, 0}, {255, 255, 255}
    });

    public static List<Palette> all() {
        return List.of(CLASSIC, INFERNO, ICE, GREEN, GRAYSCALE);
    }

    public static Palette byName(String name) {
        for (Palette p : all()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return CLASSIC;
    }
}
