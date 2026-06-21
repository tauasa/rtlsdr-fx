package org.tauasa.apps.sdr.service;

import org.springframework.stereotype.Service;
import org.tauasa.apps.sdr.config.SdrProperties;
import org.tauasa.apps.sdr.dsp.SpectrumProcessor;
import org.tauasa.apps.sdr.source.RtlTcpSource;
import org.tauasa.apps.sdr.source.SignalSource;
import org.tauasa.apps.sdr.source.SimulatedSource;

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

    private volatile SignalSource source;
    private volatile Consumer<SpectrumFrame> sink = frame -> { };

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
        long now = System.nanoTime();
        if (now - lastFrameNs < minFramePeriodNs) {
            return; // pace the display; the source keeps draining its socket
        }
        lastFrameNs = now;
        float[] power = processor.process(iq);
        sink.accept(new SpectrumFrame(power, centerFreq, sampleRate, processor.size(), seq.incrementAndGet()));
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

    public void setSampleRate(int sps) {
        this.sampleRate = sps;
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
}
