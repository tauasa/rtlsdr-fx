package org.tauasa.apps.sdr.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.paint.Color;

import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Scrolling waterfall. New rows enter at the top; the existing image is shifted
 * down one pixel each frame. The off-screen {@link WritableImage} is one pixel
 * per FFT bin wide and is drawn scaled to fill the canvas.
 */
public final class WaterfallView {

    private final Canvas canvas = new Canvas();
    private final WritableImage img;
    private final int[] rowBuf;
    private final int[] scrollBuf;
    private final int width;
    private final int height;
    private double minDb;
    private double maxDb;
    private Palette palette = Palette.CLASSIC;
    private final WritablePixelFormat<IntBuffer> fmt = PixelFormat.getIntArgbInstance();

    public WaterfallView(int fftSize, int height, double minDb, double maxDb) {
        this.width = fftSize;
        this.height = height;
        this.minDb = minDb;
        this.maxDb = maxDb;
        this.img = new WritableImage(width, height);
        this.rowBuf = new int[width];
        this.scrollBuf = new int[width * (height - 1)];

        int[] black = new int[width * height];
        Arrays.fill(black, 0xFF000000);
        img.getPixelWriter().setPixels(0, 0, width, height, fmt, black, 0, width);
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public void setRange(double minDb, double maxDb) {
        this.minDb = minDb;
        this.maxDb = maxDb;
    }

    public Palette getPalette() {
        return palette;
    }

    public void setPalette(Palette palette) {
        if (palette != null) {
            this.palette = palette;
        }
    }

    /** Adds one spectrum row to the top of the waterfall. Call on the FX thread. */
    public void pushRow(float[] power) {
        if (power == null || power.length != width) {
            return;
        }
        double span = maxDb - minDb;
        for (int i = 0; i < width; i++) {
            rowBuf[i] = palette.color((power[i] - minDb) / span);
        }
        PixelReader pr = img.getPixelReader();
        PixelWriter pw = img.getPixelWriter();
        pr.getPixels(0, 0, width, height - 1, fmt, scrollBuf, 0, width);
        pw.setPixels(0, 1, width, height - 1, fmt, scrollBuf, 0, width);
        pw.setPixels(0, 0, width, 1, fmt, rowBuf, 0, width);
    }

    public void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, w, h);
        if (w < 2 || h < 2) {
            return;
        }
        g.setImageSmoothing(false);
        g.drawImage(img, 0, 0, w, h);
    }
}
