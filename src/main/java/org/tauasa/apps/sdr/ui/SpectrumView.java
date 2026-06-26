package org.tauasa.apps.sdr.ui;

import java.util.Arrays;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/** Draws the live power spectrum plus a slowly-decaying peak-hold trace. */
public final class SpectrumView {

    private final Canvas canvas = new Canvas();
    private float[] peak;
    private double minDb;
    private double maxDb;

    private Color traceColor = Color.rgb(80, 200, 255);
    private Color fillColor = Color.rgb(80, 200, 255, 0.25);
    private Color peakColor = Color.rgb(238, 238, 7);

    public SpectrumView(double minDb, double maxDb) {
        this.minDb = minDb;
        this.maxDb = maxDb;
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public void setRange(double minDb, double maxDb) {
        this.minDb = minDb;
        this.maxDb = maxDb;
    }

    public Color getTraceColor() {
        return traceColor;
    }

    /** Sets the live-trace colour; the translucent fill under the curve follows it. */
    public void setTraceColor(Color c) {
        if (c != null) {
            this.traceColor = c;
            this.fillColor = Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.25);
        }
    }

    public Color getPeakColor() {
        return peakColor;
    }

    public void setPeakColor(Color c) {
        if (c != null) {
            this.peakColor = c;
        }
    }

    public void render(float[] power, long centerFreq, int sampleRate) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.rgb(10, 12, 18));
        g.fillRect(0, 0, w, h);
        if (power == null || w < 2 || h < 2) {
            return;
        }
        int n = power.length;

        // dB grid + labels
        g.setLineWidth(1);
        g.setFont(Font.font(10));
        int dbLines = 6;
        for (int i = 0; i <= dbLines; i++) {
            double db = maxDb - (maxDb - minDb) * i / dbLines;
            double y = dbToY(db, h);
            g.setStroke(Color.rgb(38, 44, 56));
            g.strokeLine(0, y, w, y);
            g.setFill(Color.rgb(120, 130, 145));
            g.fillText(String.format("%.0f", db), 3, y - 2);
        }

        // frequency grid + labels
        int fdiv = 8;
        for (int i = 0; i <= fdiv; i++) {
            double x = w * i / fdiv;
            g.setStroke(Color.rgb(38, 44, 56));
            g.strokeLine(x, 0, x, h);
            double freqHz = centerFreq + ((double) i / fdiv - 0.5) * sampleRate;
            g.setFill(Color.rgb(120, 130, 145));
            g.fillText(String.format("%.3f", freqHz / 1e6), Math.min(x + 2, w - 36), h - 3);
        }

        // peak hold
        if (peak == null || peak.length != n) {
            peak = new float[n];
            Arrays.fill(peak, (float) minDb);
        }
        for (int i = 0; i < n; i++) {
            peak[i] = Math.max(peak[i] - 0.25f, power[i]);
        }
        g.setStroke(peakColor);
        g.setLineWidth(1);
        g.beginPath();
        for (int i = 0; i < n; i++) {
            double x = w * i / (n - 1);
            double y = dbToY(peak[i], h);
            if (i == 0) {
                g.moveTo(x, y);
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();

        // live trace, filled under the curve
        g.beginPath();
        g.moveTo(0, h);
        for (int i = 0; i < n; i++) {
            g.lineTo(w * i / (n - 1), dbToY(power[i], h));
        }
        g.lineTo(w, h);
        g.closePath();
        g.setFill(fillColor);
        g.fill();

        g.setStroke(traceColor);
        g.setLineWidth(1.3);
        g.beginPath();
        for (int i = 0; i < n; i++) {
            double x = w * i / (n - 1);
            double y = dbToY(power[i], h);
            if (i == 0) {
                g.moveTo(x, y);
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();
    }

    private double dbToY(double db, double h) {
        double t = (db - minDb) / (maxDb - minDb);
        t = Math.max(0, Math.min(1, t));
        return h - t * h;
    }
}
