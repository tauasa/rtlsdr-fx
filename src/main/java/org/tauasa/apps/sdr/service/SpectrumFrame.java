package org.tauasa.apps.sdr.service;

/**
 * One computed spectrum, handed from the DSP thread to the UI.
 *
 * @param powerDb         fft-shifted power per bin in dB (length = fftSize)
 * @param centerFrequency tuned centre frequency in Hz
 * @param sampleRate      sample rate in samples/sec (spectrum span)
 * @param fftSize         number of bins
 * @param sequence        monotonically increasing frame id
 */
public record SpectrumFrame(float[] powerDb, long centerFrequency, int sampleRate, int fftSize, long sequence) {
}
