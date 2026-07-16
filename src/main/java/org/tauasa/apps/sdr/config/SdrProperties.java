package org.tauasa.apps.sdr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bound from {@code sdr.*} in application.yml. */
@Component
@ConfigurationProperties(prefix = "sdr")
public class SdrProperties {

    private String host = "127.0.0.1";
    private int port = 1234;
    private int fftSize = 2048;
    private int sampleRate = 2_400_000;
    private long centerFrequency = 90_900_000L; // 90.9000 MHz (FM band)
    private int gainTenthsDb = 0;
    private boolean autoGain = true;
    private int targetFps = 50;
    private int waterfallHeight = 320;
    private double minDb = -100;
    private double maxDb = 0;
    private boolean audioEnabled = false;
    private String demodMode = "WFM"; // WFM | NFM | AM | CW
    private double volume = 0.6;
    private double cwPitch = 700;      // CW sidetone / BFO pitch (Hz)
    private double cwBandwidth = 300;  // CW filter bandwidth (Hz)

    // Scan (search) settings — sweeps the tunable range looking for transmissions.
    private long scanStartFrequency = 24_000_000L;    // 24 MHz — bottom of the typical R820T2 tuning range
    private long scanStopFrequency = 1_766_000_000L;  // 1766 MHz — top of the typical R820T2 tuning range
    private long scanStepHz = 200_000L;               // retune step between capture windows (Hz)
    private double scanThresholdDb = 12.0;             // peak-above-noise-floor needed to call it a "hit" (dB)
    private long scanSettleMs = 150;                   // time to let the tuner/spectrum settle before measuring
    private long scanLingerMs = 2000;                  // dwell time on a detected signal before resuming (ms)
    private boolean scanLoop = true;                   // wrap back to the start frequency at the end of the range

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getFftSize() { return fftSize; }
    public void setFftSize(int fftSize) { this.fftSize = fftSize; }

    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

    public long getCenterFrequency() { return centerFrequency; }
    public void setCenterFrequency(long centerFrequency) { this.centerFrequency = centerFrequency; }

    public int getGainTenthsDb() { return gainTenthsDb; }
    public void setGainTenthsDb(int gainTenthsDb) { this.gainTenthsDb = gainTenthsDb; }

    public boolean isAutoGain() { return autoGain; }
    public void setAutoGain(boolean autoGain) { this.autoGain = autoGain; }

    public int getTargetFps() { return targetFps; }
    public void setTargetFps(int targetFps) { this.targetFps = targetFps; }

    public int getWaterfallHeight() { return waterfallHeight; }
    public void setWaterfallHeight(int waterfallHeight) { this.waterfallHeight = waterfallHeight; }

    public double getMinDb() { return minDb; }
    public void setMinDb(double minDb) { this.minDb = minDb; }

    public double getMaxDb() { return maxDb; }
    public void setMaxDb(double maxDb) { this.maxDb = maxDb; }

    public boolean isAudioEnabled() { return audioEnabled; }
    public void setAudioEnabled(boolean audioEnabled) { this.audioEnabled = audioEnabled; }

    public String getDemodMode() { return demodMode; }
    public void setDemodMode(String demodMode) { this.demodMode = demodMode; }

    public double getVolume() { return volume; }
    public void setVolume(double volume) { this.volume = volume; }

    public double getCwPitch() { return cwPitch; }
    public void setCwPitch(double cwPitch) { this.cwPitch = cwPitch; }

    public double getCwBandwidth() { return cwBandwidth; }
    public void setCwBandwidth(double cwBandwidth) { this.cwBandwidth = cwBandwidth; }

    public long getScanStartFrequency() { return scanStartFrequency; }
    public void setScanStartFrequency(long scanStartFrequency) { this.scanStartFrequency = scanStartFrequency; }

    public long getScanStopFrequency() { return scanStopFrequency; }
    public void setScanStopFrequency(long scanStopFrequency) { this.scanStopFrequency = scanStopFrequency; }

    public long getScanStepHz() { return scanStepHz; }
    public void setScanStepHz(long scanStepHz) { this.scanStepHz = scanStepHz; }

    public double getScanThresholdDb() { return scanThresholdDb; }
    public void setScanThresholdDb(double scanThresholdDb) { this.scanThresholdDb = scanThresholdDb; }

    public long getScanSettleMs() { return scanSettleMs; }
    public void setScanSettleMs(long scanSettleMs) { this.scanSettleMs = scanSettleMs; }

    public long getScanLingerMs() { return scanLingerMs; }
    public void setScanLingerMs(long scanLingerMs) { this.scanLingerMs = scanLingerMs; }

    public boolean isScanLoop() { return scanLoop; }
    public void setScanLoop(boolean scanLoop) { this.scanLoop = scanLoop; }
}
