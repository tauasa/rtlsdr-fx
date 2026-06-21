package org.tauasa.apps.sdr.source;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generates synthetic IQ so the app is fully usable without an RTL-SDR dongle.
 * Produces a couple of steady tones plus one slowly sweeping, amplitude-
 * modulated tone over complex Gaussian noise, which gives the spectrum and
 * waterfall something lively to draw.
 */
public final class SimulatedSource implements SignalSource {

    private final int fftSize;
    private final int fps;
    private volatile int sampleRate;
    private volatile long centerFreq;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-source");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> task;
    private volatile boolean running;

    private final Random rnd = new Random();
    private double phase1;
    private double phase2;
    private double phase3;
    private long startNs;

    public SimulatedSource(int fftSize, int sampleRate, long centerFreq, int fps) {
        this.fftSize = fftSize;
        this.sampleRate = sampleRate;
        this.centerFreq = centerFreq;
        this.fps = Math.max(10, fps);
    }

    @Override
    public void start(IqListener listener) {
        running = true;
        startNs = System.nanoTime();
        long periodMs = Math.max(5, 1000 / fps);
        task = exec.scheduleAtFixedRate(() -> {
            try {
                listener.onBlock(generate());
            } catch (RuntimeException ignored) {
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    private float[] generate() {
        int n = fftSize;
        float[] iq = new float[2 * n];
        double fs = sampleRate;
        double t = (System.nanoTime() - startNs) / 1e9;

        double f1 = 0.18 * fs / 2;                      // steady tone, right of centre
        double f2 = -0.42 * fs / 2;                     // steady tone, left of centre
        double f3 = (0.30 * Math.sin(t * 0.25)) * fs / 2; // sweeping tone
        double a1 = 0.30;
        double a2 = 0.18;
        double a3 = 0.22 * (0.5 + 0.5 * Math.sin(t * 1.3)); // amplitude modulated

        double d1 = 2 * Math.PI * f1 / fs;
        double d2 = 2 * Math.PI * f2 / fs;
        double d3 = 2 * Math.PI * f3 / fs;

        for (int k = 0; k < n; k++) {
            double re = a1 * Math.cos(phase1) + a2 * Math.cos(phase2) + a3 * Math.cos(phase3);
            double im = a1 * Math.sin(phase1) + a2 * Math.sin(phase2) + a3 * Math.sin(phase3);
            re += 0.05 * rnd.nextGaussian();
            im += 0.05 * rnd.nextGaussian();
            iq[2 * k] = (float) re;
            iq[2 * k + 1] = (float) im;
            phase1 += d1;
            phase2 += d2;
            phase3 += d3;
        }
        phase1 %= 2 * Math.PI;
        phase2 %= 2 * Math.PI;
        phase3 %= 2 * Math.PI;
        return iq;
    }

    @Override
    public void stop() {
        running = false;
        if (task != null) {
            task.cancel(true);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void setCenterFrequency(long hz) {
        this.centerFreq = hz;
    }

    @Override
    public void setSampleRate(int samplesPerSecond) {
        this.sampleRate = samplesPerSecond;
    }

    @Override
    public void setGain(int tenthsDb) {
        // no-op for the simulator
    }

    @Override
    public void setAutoGain(boolean auto) {
        // no-op for the simulator
    }

    @Override
    public String describe() {
        return String.format("Simulated source  [%.3f Msps]", sampleRate / 1e6);
    }
}
