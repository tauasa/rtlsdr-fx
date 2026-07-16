package org.tauasa.apps.sdr.scan;

import org.springframework.stereotype.Service;
import org.tauasa.apps.sdr.config.SdrProperties;
import org.tauasa.apps.sdr.service.SdrService;
import org.tauasa.apps.sdr.service.SpectrumFrame;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Sweeps the tuner across a configured frequency range looking for active
 * transmissions. At each step it retunes, waits {@link #getSettleMs()} for
 * the tuner and spectrum to stabilise, then compares the peak bin of the
 * latest {@link SpectrumFrame} against the frame's noise floor (its median
 * bin power). A peak that clears the floor by {@link #getThresholdDb()}
 * counts as a hit: the scanner lingers there for {@link #getLingerMs()} —
 * giving a listener time to hear it — before resuming the sweep.
 *
 * <p>Runs its own daemon thread; retuning and frame evaluation never touch
 * the JavaFX Application Thread. Callers on the FX thread should marshal the
 * {@code onTune}/{@code onDetect}/{@code onStopped} callbacks themselves.
 */
@Service
public final class Scanner {

    private final SdrService sdr;

    private volatile long startFrequency;
    private volatile long stopFrequency;
    private volatile long stepHz;
    private volatile double thresholdDb;
    private volatile long settleMs;
    private volatile long lingerMs;
    private volatile boolean loop;

    private final AtomicReference<SpectrumFrame> latestFrame = new AtomicReference<>();
    private volatile Thread scanThread;
    private volatile boolean scanning;
    private volatile boolean paused;
    private final Object pauseMonitor = new Object();

    private volatile LongConsumer onTune = f -> { };
    private volatile LongConsumer onDetect = f -> { };
    private volatile Runnable onStopped = () -> { };

    public Scanner(SdrService sdr, SdrProperties props) {
        this.sdr = sdr;
        this.startFrequency = props.getScanStartFrequency();
        this.stopFrequency = props.getScanStopFrequency();
        this.stepHz = props.getScanStepHz();
        this.thresholdDb = props.getScanThresholdDb();
        this.settleMs = props.getScanSettleMs();
        this.lingerMs = props.getScanLingerMs();
        this.loop = props.isScanLoop();
        sdr.setScanListener(latestFrame::set);
    }

    public void setOnTune(LongConsumer onTune) {
        this.onTune = onTune != null ? onTune : f -> { };
    }

    public void setOnDetect(LongConsumer onDetect) {
        this.onDetect = onDetect != null ? onDetect : f -> { };
    }

    public void setOnStopped(Runnable onStopped) {
        this.onStopped = onStopped != null ? onStopped : () -> { };
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isPaused() {
        return paused;
    }

    public synchronized void start() {
        if (scanning) {
            return;
        }
        scanning = true;
        paused = false;
        latestFrame.set(null);
        Thread t = new Thread(this::runLoop, "scanner");
        t.setDaemon(true);
        scanThread = t;
        t.start();
    }

    public synchronized void stop() {
        scanning = false;
        paused = false;
        Thread t = scanThread;
        scanThread = null;
        if (t != null) {
            t.interrupt();
        }
        wakePaused();
    }

    /** Halts the sweep in place — the tuner stays parked on the current frequency until {@link #resume()}. */
    public void pause() {
        paused = true;
    }

    /** Continues the sweep from wherever it was paused. */
    public void resume() {
        paused = false;
        wakePaused();
    }

    private void wakePaused() {
        synchronized (pauseMonitor) {
            pauseMonitor.notifyAll();
        }
    }

    private void runLoop() {
        long freq = sdr.getCenterFrequency();
        try {
            while (scanning) {
                if (paused) {
                    synchronized (pauseMonitor) {
                        while (paused && scanning) {
                            pauseMonitor.wait();
                        }
                    }
                    if (!scanning) {
                        break;
                    }
                }
                latestFrame.set(null);
                sdr.setCenterFrequency(freq);
                onTune.accept(freq);
                Thread.sleep(settleMs);
                if (!scanning) {
                    break;
                }
                SpectrumFrame frame = latestFrame.get();
                if (frame != null && isHit(frame)) {
                    onDetect.accept(freq);
                    Thread.sleep(lingerMs);
                }
                if (!scanning) {
                    break;
                }
                freq += stepHz;
                if (freq > stopFrequency) {
                    if (!loop) {
                        break;
                    }
                    freq = startFrequency;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            scanning = false;
            paused = false;
            onStopped.run();
        }
    }

    private boolean isHit(SpectrumFrame frame) {
        float[] db = frame.powerDb();
        if (db.length == 0) {
            return false;
        }
        float[] sorted = db.clone();
        Arrays.sort(sorted);
        float noiseFloor = sorted[sorted.length / 2]; // median bin power
        float peak = sorted[sorted.length - 1];
        return (peak - noiseFloor) >= thresholdDb;
    }

    // ---- live-configurable parameters (read by the dialog, written back on Apply) ----

    public long getStartFrequency() { return startFrequency; }
    public void setStartFrequency(long hz) { this.startFrequency = hz; }

    public long getStopFrequency() { return stopFrequency; }
    public void setStopFrequency(long hz) { this.stopFrequency = hz; }

    public long getStepHz() { return stepHz; }
    public void setStepHz(long hz) { this.stepHz = Math.max(1, hz); }

    public double getThresholdDb() { return thresholdDb; }
    public void setThresholdDb(double db) { this.thresholdDb = db; }

    public long getSettleMs() { return settleMs; }
    public void setSettleMs(long ms) { this.settleMs = Math.max(0, ms); }

    public long getLingerMs() { return lingerMs; }
    public void setLingerMs(long ms) { this.lingerMs = Math.max(0, ms); }

    public boolean isLoop() { return loop; }
    public void setLoop(boolean loop) { this.loop = loop; }
}
