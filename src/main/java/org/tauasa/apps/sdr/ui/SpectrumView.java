package org.tauasa.apps.sdr.ui;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/** Draws the live power spectrum plus a slowly-decaying peak-hold trace. */
public final class SpectrumView {

    private static final double HANDLE_RADIUS = 8;
    /** Press/release moves less than this many pixels count as a click, not a drag. */
    private static final double CLICK_MOVE_THRESHOLD = 3;
    /** Compresses the trace/peak curves toward the bottom so they swing less tall, without affecting the dB grid. */
    private static final double TRACE_VERTICAL_SCALE = 0.8;

    private static final Font AXIS_FONT = Font.font(10);
    /** Used for the marker label and the bottom frequency labels while a scan is running. */
    private static final Font SCANNING_FONT = Font.font("System", FontWeight.BOLD, 16);

    private final Canvas canvas = new Canvas();
    private float[] peak;
    private double minDb;
    private double maxDb;

    private Color traceColor = Color.rgb(80, 200, 255);
    private Color fillColor = Color.rgb(80, 200, 255, 0.25);
    private Color peakColor = Color.rgb(238, 238, 7);
    private Color markerColor = Color.rgb(255, 200, 80);

    private boolean dragging = false;
    private double lineX = -1;
    private double pressX = -1;
    private long lastCenterFreq;
    private int lastSampleRate;
    private Consumer<Long> onFrequencyChanged;
    private Runnable onSpectrumClicked;
    private final PauseTransition singleClickDelay = new PauseTransition(Duration.millis(300));

    private boolean scanning = false;

    public SpectrumView(double minDb, double maxDb) {
        this.minDb = minDb;
        this.maxDb = maxDb;
        installDragHandlers();
    }

    public void setOnFrequencyChanged(Consumer<Long> cb) {
        this.onFrequencyChanged = cb;
    }

    /** Fired on a single (non-dragged) click anywhere on the spectrum — pauses/resumes a running scan. */
    public void setOnSpectrumClicked(Runnable cb) {
        this.onSpectrumClicked = cb;
    }

    /** Marks a scan as running or stopped; the marker's label is drawn larger while true. */
    public void setScanning(boolean scanning) {
        this.scanning = scanning;
    }

    /** Where the marker currently sits: the drag position while being dragged, centre otherwise. */
    private double currentMarkerX() {
        return dragging ? lineX : canvas.getWidth() / 2.0;
    }

    private void installDragHandlers() {
        canvas.setOnMouseMoved(e -> {
            double cx = currentMarkerX();
            canvas.setCursor(Math.abs(e.getX() - cx) <= HANDLE_RADIUS
                    ? Cursor.H_RESIZE : Cursor.DEFAULT);
        });
        canvas.setOnMousePressed(e -> {
            double cx = currentMarkerX();
            if (Math.abs(e.getX() - cx) <= HANDLE_RADIUS) {
                dragging = true;
                lineX = e.getX();
                pressX = e.getX();
                canvas.setCursor(Cursor.H_RESIZE);
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (dragging) {
                lineX = Math.max(0, Math.min(canvas.getWidth(), e.getX()));
            }
        });
        canvas.setOnMouseReleased(e -> {
            if (dragging) {
                dragging = false;
                canvas.setCursor(Cursor.DEFAULT);
                // only treat this as a drag-to-retune if the mouse actually moved; a
                // press+release with no movement is a click, handled below instead
                if (Math.abs(lineX - pressX) > CLICK_MOVE_THRESHOLD
                        && onFrequencyChanged != null && lastSampleRate > 0) {
                    double offset = (lineX / canvas.getWidth() - 0.5) * lastSampleRate;
                    onFrequencyChanged.accept(lastCenterFreq + Math.round(offset));
                }
                lineX = -1;
            }
        });
        canvas.setOnMouseClicked(e -> {
            // ignore clicks that were actually a drag (e.g. dragging the marker to retune)
            if (!e.isStillSincePress()) {
                return;
            }
            if (e.getClickCount() == 2) {
                // a double-click just cancels any pending single-click (pause/resume) action
                singleClickDelay.stop();
            } else if (e.getClickCount() == 1) {
                singleClickDelay.setOnFinished(ev -> {
                    if (onSpectrumClicked != null) {
                        onSpectrumClicked.run();
                    }
                });
                singleClickDelay.playFromStart();
            }
        });
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

    public Color getMarkerColor() {
        return markerColor;
    }

    public void setMarkerColor(Color c) {
        if (c != null) {
            this.markerColor = c;
        }
    }

    public void render(float[] power, long centerFreq, int sampleRate) {
        lastCenterFreq = centerFreq;
        lastSampleRate = sampleRate;
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
        g.setFont(AXIS_FONT);
        int dbLines = 6;
        for (int i = 0; i <= dbLines; i++) {
            double db = maxDb - (maxDb - minDb) * i / dbLines;
            double y = dbToY(db, h);
            g.setStroke(Color.rgb(38, 44, 56));
            g.strokeLine(0, y, w, y);
            g.setFill(Color.WHITE);
            g.fillText(String.format("%.0f", db), 3, y - 2);
        }

        // frequency grid + labels
        int fdiv = 8;
        g.setFont(scanning ? SCANNING_FONT : AXIS_FONT);
        double freqLabelWidth = scanning ? 76 : 36;
        for (int i = 0; i <= fdiv; i++) {
            double x = w * i / fdiv;
            g.setStroke(Color.rgb(38, 44, 56));
            g.strokeLine(x, 0, x, h);
            double freqHz = centerFreq + ((double) i / fdiv - 0.5) * sampleRate;
            g.setFill(Color.WHITE);
            g.fillText(String.format("%.3f", freqHz / 1e6), Math.min(x + 2, w - freqLabelWidth), h - 3);
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
            double y = traceY(peak[i], h);
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
            g.lineTo(w * i / (n - 1), traceY(power[i], h));
        }
        g.lineTo(w, h);
        g.closePath();
        g.setFill(fillColor);
        g.fill();

        g.setStroke(traceColor);
        g.setLineWidth(0.8);
        g.beginPath();
        for (int i = 0; i < n; i++) {
            double x = w * i / (n - 1);
            double y = traceY(power[i], h);
            if (i == 0) {
                g.moveTo(x, y);
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();

        // draggable centre-frequency marker; its label is drawn larger while a scan is running
        double lx = currentMarkerX();
        g.setStroke(markerColor);
        g.setLineWidth(1.5);
        g.setLineDashes(6, 4);
        g.strokeLine(lx, 0, lx, h);
        g.setLineDashes();

        double freqAtLine = dragging
                ? centerFreq + (lx / w - 0.5) * sampleRate
                : centerFreq;
        g.setFill(markerColor);
        g.setFont(scanning ? SCANNING_FONT : AXIS_FONT);
        double labelWidth = scanning ? 96 : 60;
        double labelX = (lx + 4 + labelWidth < w) ? lx + 4 : lx - labelWidth - 4;
        g.fillText(String.format("%.4f MHz", freqAtLine / 1e6), labelX, scanning ? 18 : 14);
    }

    private double dbToY(double db, double h) {
        double t = (db - minDb) / (maxDb - minDb);
        t = Math.max(0, Math.min(1, t));
        return h - t * h;
    }

    /** Like dbToY, but pulls the result toward the bottom baseline so the trace swings less tall. */
    private double traceY(double db, double h) {
        double y = dbToY(db, h);
        return h - (h - y) * TRACE_VERTICAL_SCALE;
    }
}
