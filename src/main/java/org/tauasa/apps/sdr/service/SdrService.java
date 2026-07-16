package org.tauasa.apps.sdr.service;

import org.springframework.stereotype.Service;
import org.tauasa.apps.sdr.audio.AudioPlayer;
import org.tauasa.apps.sdr.config.SdrProperties;
import org.tauasa.apps.sdr.dsp.Demodulator;
import org.tauasa.apps.sdr.dsp.SpectrumProcessor;
import org.tauasa.apps.sdr.source.RtlTcpSource;
import org.tauasa.apps.sdr.source.SignalSource;
import org.tauasa.apps.sdr.source.SimulatedCwSource;
import org.tauasa.apps.sdr.source.SimulatedSource;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Central coordinator. Owns the active {@link SignalSource}, runs incoming IQ
 * blocks through the {@link SpectrumProcessor}, paces output to the configured
 * display rate (the socket is always fully drained regardless) and pushes the
 * resulting {@link SpectrumFrame} to a sink that the UI installs.
 */
@Service
public class SdrService {

    private final SdrProperties props;
    private final SpectrumProcessor processor;
    private final AtomicLong seq = new AtomicLong();

    private final Demodulator demod = new Demodulator();
    private final AudioPlayer audio = new AudioPlayer();
    private volatile boolean audioEnabled;

    private volatile SignalSource source;
    private volatile Consumer<SpectrumFrame> sink = frame -> { };
    private volatile Consumer<SpectrumFrame> scanListener;

    private volatile long centerFreq;
    private volatile int sampleRate;
    private final long minFramePeriodNs;
    private volatile long lastFrameNs;

    public SdrService(SdrProperties props) {
        this.props = props;
        this.processor = new SpectrumProcessor(props.getFftSize());
        this.centerFreq = props.getCenterFrequency();
        this.sampleRate = props.getSampleRate();
        this.minFramePeriodNs = 1_000_000_000L / Math.max(1, props.getTargetFps());
        this.demod.setMode(parseMode(props.getDemodMode()));
        this.demod.setCwParams(props.getCwPitch(), props.getCwBandwidth());
        this.demod.configure(sampleRate);
        this.audio.setVolume((float) props.getVolume());
    }

    private static Demodulator.Mode parseMode(String name) {
        try {
            return Demodulator.Mode.valueOf(name.trim().toUpperCase());
        } catch (Exception e) {
            return Demodulator.Mode.WFM;
        }
    }

    public int fftSize() {
        return processor.size();
    }

    public boolean isRunning() {
        SignalSource s = source;
        return s != null && s.isRunning();
    }

    public String describe() {
        SignalSource s = source;
        return s == null ? "stopped" : s.describe();
    }

    public void setFrameSink(Consumer<SpectrumFrame> sink) {
        this.sink = sink;
    }

    /** Registers (or clears, via {@code null}) an additional frame observer used by the {@link org.tauasa.apps.sdr.scan.Scanner}. */
    public void setScanListener(Consumer<SpectrumFrame> scanListener) {
        this.scanListener = scanListener;
    }

    public synchronized void startRtl(String host, int port) throws Exception {
        stop();
        beginWith(new RtlTcpSource(host, port, processor.size()));
    }

    public synchronized void startSimulated() {
        stop();
        try {
            beginWith(new SimulatedSource(processor.size(), sampleRate, centerFreq, props.getTargetFps()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void startSimulatedCw() {
        stop();
        try {
            beginWith(new SimulatedCwSource(sampleRate, centerFreq));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void beginWith(SignalSource s) throws Exception {
        this.source = s;
        s.start(this::onBlock);
        s.setSampleRate(sampleRate);
        s.setCenterFrequency(centerFreq);
        if (props.isAutoGain()) {
            s.setAutoGain(true);
        } else {
            s.setGain(props.getGainTenthsDb());
        }
    }

    private void onBlock(float[] iq) {
        // Audio needs every sample for continuity, so demodulate before the
        // display-pacing gate below (which intentionally drops frames).
        if (audioEnabled) {
            float[] a = demod.process(iq, iq.length / 2);
            if (a.length > 0) {
                audio.submit(a, a.length);
            }
        }

        long now = System.nanoTime();
        if (now - lastFrameNs < minFramePeriodNs) {
            return; // pace the display; the source keeps draining its socket
        }
        lastFrameNs = now;
        float[] power = processor.process(iq);
        SpectrumFrame frame = new SpectrumFrame(power, centerFreq, sampleRate, processor.size(), seq.incrementAndGet());
        sink.accept(frame);
        Consumer<SpectrumFrame> sl = scanListener;
        if (sl != null) {
            sl.accept(frame);
        }
    }

    public synchronized void stop() {
        SignalSource s = source;
        if (s != null) {
            s.stop();
        }
        source = null;
    }

    public void setCenterFrequency(long hz) {
        this.centerFreq = hz;
        SignalSource s = source;
        if (s != null) {
            s.setCenterFrequency(hz);
        }
    }

    public long getCenterFrequency() {
        return centerFreq;
    }

    public void setSampleRate(int sps) {
        this.sampleRate = sps;
        demod.configure(sps);
        SignalSource s = source;
        if (s != null) {
            s.setSampleRate(sps);
        }
    }

    public void setGain(int tenthsDb) {
        SignalSource s = source;
        if (s != null) {
            s.setGain(tenthsDb);
        }
    }

    public void setAutoGain(boolean auto) {
        SignalSource s = source;
        if (s != null) {
            s.setAutoGain(auto);
        }
    }

    // ---- audio ----

    public synchronized void setAudioEnabled(boolean on) {
        if (on) {
            demod.configure(sampleRate); // match the current device rate
            try {
                audio.start();
                audioEnabled = true;
            } catch (Exception e) {
                audioEnabled = false;
                throw new RuntimeException("Audio output unavailable: " + e.getMessage(), e);
            }
        } else {
            audioEnabled = false;
            audio.stop();
        }
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public void setVolume(double volume) {
        audio.setVolume((float) volume);
    }

    public void setDemodMode(Demodulator.Mode mode) {
        demod.setMode(mode);
    }

    public Demodulator.Mode getDemodMode() {
        return demod.getMode();
    }

    public void setCwParams(double pitch, double bandwidth) {
        demod.setCwParams(pitch, bandwidth);
    }

    public double getCwPitch() {
        return demod.getCwPitch();
    }

    public double getCwBandwidth() {
        return demod.getCwBandwidth();
    }

    @PreDestroy
    public void shutdown() {
        stop();
        audio.stop();
    }
}
