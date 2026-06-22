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
    private long centerFrequency = 90_900_000;// 100_000_000L; // 100 MHz (FM band)
    private int gainTenthsDb = 0;
    private boolean autoGain = true;
    private int targetFps = 50;
    private int waterfallHeight = 400;//320;
    private double minDb = -100;
    private double maxDb = 0;
    private boolean audioEnabled = true;
    private String demodMode = "WFM"; // WFM | NFM | AM
    private double volume = 0.6;

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
}
