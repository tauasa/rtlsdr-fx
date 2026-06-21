package org.tauasa.apps.sdr.source;

/**
 * A producer of complex baseband IQ blocks. Implementations deliver fixed-size
 * blocks of interleaved float samples to a listener and accept tuning commands.
 */
public interface SignalSource {

    @FunctionalInterface
    interface IqListener {
        /** @param interleavedIq length {@code 2 * fftSize}: I, Q, I, Q, ... centred near 0. */
        void onBlock(float[] interleavedIq);
    }

    void start(IqListener listener) throws Exception;

    void stop();

    boolean isRunning();

    void setCenterFrequency(long hz);

    void setSampleRate(int samplesPerSecond);

    void setGain(int tenthsDb);

    void setAutoGain(boolean auto);

    String describe();
}
