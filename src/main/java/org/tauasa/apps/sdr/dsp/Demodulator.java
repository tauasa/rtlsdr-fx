package org.tauasa.apps.sdr.dsp;

/**
 * Turns a complex baseband IQ stream (centred on the tuned frequency) into mono
 * audio at a fixed {@value #AUDIO_RATE} Hz. The signal of interest is assumed to
 * sit at the centre of the captured band (i.e. the dongle is tuned onto the
 * station), which is the common case for a single-channel receiver.
 *
 * <p>Chain: front-end low-pass + decimation to a manageable IF rate, then the
 * mode-specific discriminator (FM phase difference, or AM envelope), then an
 * audio low-pass + decimation, optional FM de-emphasis, and finally a linear
 * resample that pins the output to exactly {@value #AUDIO_RATE} Hz no matter what
 * sample rate the device is running at.
 *
 * <p>All public methods are synchronized so the audio thread can reconfigure the
 * chain (sample-rate or mode change) without racing the streaming callback.
 */
public final class Demodulator {

    public enum Mode {
        WFM("Wideband FM"),
        NFM("Narrowband FM"),
        AM("AM"),
        CW("CW (Morse)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final int AUDIO_RATE = 48_000;

    private static final int IF_TARGET = 240_000; // desired rate after front-end decimation

    private Mode mode = Mode.WFM;
    private int inputRate;

    private ComplexFirDecimator front;
    private RealFirDecimator audioStage;
    private double ifRate;
    private double preAudioRate;

    // FM discriminator state
    private float lastI;
    private float lastQ;
    private float fmGain;
    // DC blocker (post-FM) state
    private float dcState;
    // AM envelope DC state
    private float amDc;
    // FM de-emphasis (WFM) one-pole state
    private float deemphState;
    private float deemphAlpha;
    private boolean useDeemphasis;
    // CW (Morse): BFO rotator at the IF rate + narrow audio band-pass
    private double cwPitch = 700.0;     // sidetone / BFO pitch (Hz)
    private double cwBandwidth = 300.0; // CW filter bandwidth (Hz)
    private double cwOscR;
    private double cwOscI;
    private double cwCosInc;
    private double cwSinInc;
    private int cwOscN;
    private Biquad cwFilter;
    // linear resampler state
    private double resampleStep;
    private double resamplePos;
    private float resamplePrev;

    // scratch buffers (grown on demand)
    private float[] ifBuf;     // interleaved IQ after front-end
    private float[] demodBuf;  // real, at ifRate
    private float[] audioBuf;  // real, at preAudioRate
    private float[] outBuf;    // real, at AUDIO_RATE

    public synchronized void setMode(Mode m) {
        if (m != null) {
            this.mode = m;
            if (inputRate > 0) {
                configure(inputRate);
            }
        }
    }

    public synchronized Mode getMode() {
        return mode;
    }

    /**
     * Sets the CW sidetone pitch and filter bandwidth (Hz). A non-positive value
     * leaves the corresponding parameter unchanged. Rebuilds the chain if already
     * configured so the change takes effect immediately.
     */
    public synchronized void setCwParams(double pitch, double bandwidth) {
        if (pitch > 0) {
            this.cwPitch = pitch;
        }
        if (bandwidth > 0) {
            this.cwBandwidth = bandwidth;
        }
        if (inputRate > 0) {
            configure(inputRate);
        }
    }

    public synchronized double getCwPitch() {
        return cwPitch;
    }

    public synchronized double getCwBandwidth() {
        return cwBandwidth;
    }

    /** (Re)builds the filter chain for the given device sample rate. */
    public synchronized void configure(int inputRate) {
        this.inputRate = inputRate;

        int decim1 = Math.max(1, (int) Math.round(inputRate / (double) IF_TARGET));
        this.ifRate = inputRate / (double) decim1;

        // Front-end anti-alias low-pass just below the new Nyquist.
        double frontCutNorm = 0.45 / decim1; // = 0.45 * ifRate / inputRate
        this.front = new ComplexFirDecimator(Fir.lowPass(63, frontCutNorm), decim1);

        // Audio bandwidth + de-emphasis depend on mode.
        double audioCutHz;
        switch (mode) {
            case WFM -> {
                audioCutHz = 15_000;
                fmGain = (float) (ifRate / (2.0 * Math.PI * 75_000.0)); // ~unity at full deviation
                useDeemphasis = true;
            }
            case NFM -> {
                audioCutHz = 3_400;
                fmGain = (float) (ifRate / (2.0 * Math.PI * 5_000.0));
                useDeemphasis = false;
            }
            case CW -> {
                // pass the pitch tone produced by the BFO mix below
                audioCutHz = Math.min(AUDIO_RATE * 0.45, cwPitch + cwBandwidth + 400);
                fmGain = 1f;
                useDeemphasis = false;
            }
            default -> { // AM
                audioCutHz = 4_500;
                fmGain = 1f;
                useDeemphasis = false;
            }
        }

        int decim2 = Math.max(1, (int) Math.round(ifRate / AUDIO_RATE));
        this.preAudioRate = ifRate / decim2;
        double audioCutNorm = Math.min(0.45, audioCutHz / ifRate);
        this.audioStage = new RealFirDecimator(Fir.lowPass(63, audioCutNorm), decim2);

        // 75 us de-emphasis applied at the final audio rate.
        double tau = 75e-6;
        this.deemphAlpha = (float) (1.0 - Math.exp(-1.0 / (tau * AUDIO_RATE)));

        this.resampleStep = preAudioRate / AUDIO_RATE;
        this.resamplePos = 0.0;
        this.resamplePrev = 0f;

        // reset filter/discriminator memory
        this.lastI = 0f;
        this.lastQ = 0f;
        this.dcState = 0f;
        this.amDc = 0f;
        this.deemphState = 0f;

        // CW: a BFO rotator at the IF rate shifts a centre carrier up to the
        // pitch, then a band-pass at the audio rate forms the tight CW filter.
        double ncoInc = 2.0 * Math.PI * cwPitch / ifRate;
        this.cwCosInc = Math.cos(ncoInc);
        this.cwSinInc = Math.sin(ncoInc);
        this.cwOscR = 1.0;
        this.cwOscI = 0.0;
        this.cwOscN = 0;
        this.cwFilter = Biquad.bandPass(cwPitch, cwBandwidth, AUDIO_RATE);

        ensureBuffers(1 << 14);
    }

    private void ensureBuffers(int complexInput) {
        int ifMax = front.maxOutput(complexInput);
        if (ifBuf == null || ifBuf.length < ifMax * 2) {
            ifBuf = new float[ifMax * 2];
            demodBuf = new float[ifMax];
            int aMax = audioStage.maxOutput(ifMax);
            audioBuf = new float[aMax];
            outBuf = new float[(int) (aMax / Math.max(0.05, resampleStep)) + 8];
        }
    }

    /**
     * Demodulates one IQ block.
     *
     * @param iq            interleaved IQ (I,Q,...)
     * @param complexInput  number of complex samples in {@code iq}
     * @return a freshly-allocated array of mono audio samples at {@value #AUDIO_RATE} Hz
     */
    public synchronized float[] process(float[] iq, int complexInput) {
        if (inputRate <= 0 || front == null) {
            return new float[0];
        }
        ensureBuffers(complexInput);

        int ifCount = front.process(iq, complexInput, ifBuf);

        // Discriminator -> real signal at ifRate
        if (mode == Mode.AM) {
            for (int k = 0; k < ifCount; k++) {
                float i = ifBuf[2 * k];
                float q = ifBuf[2 * k + 1];
                float env = (float) Math.sqrt(i * i + q * q);
                amDc += 0.0005f * (env - amDc); // track and remove the carrier DC level
                demodBuf[k] = env - amDc;
            }
        } else if (mode == Mode.CW) {
            // mix up by the pitch (centre carrier -> audible tone), take real part
            double oR = cwOscR;
            double oI = cwOscI;
            double cI = cwCosInc;
            double sI = cwSinInc;
            int nn = cwOscN;
            for (int k = 0; k < ifCount; k++) {
                float i = ifBuf[2 * k];
                float q = ifBuf[2 * k + 1];
                demodBuf[k] = (float) (i * oR - q * oI); // Re{(i + jq) * e^{j theta}}
                double nR = oR * cI - oI * sI;
                oI = oR * sI + oI * cI;
                oR = nR;
                if ((++nn & 1023) == 0) { // periodic renormalise to fight drift
                    double m = Math.sqrt(oR * oR + oI * oI);
                    if (m != 0.0) {
                        oR /= m;
                        oI /= m;
                    }
                }
            }
            cwOscR = oR;
            cwOscI = oI;
            cwOscN = nn;
        } else {
            for (int k = 0; k < ifCount; k++) {
                float i = ifBuf[2 * k];
                float q = ifBuf[2 * k + 1];
                // angle of current * conj(previous)
                float re = i * lastI + q * lastQ;
                float im = q * lastI - i * lastQ;
                lastI = i;
                lastQ = q;
                float angle = (float) Math.atan2(im, re);
                float v = angle * fmGain;
                dcState += 0.0008f * (v - dcState); // block residual DC from mistuning
                demodBuf[k] = v - dcState;
            }
        }

        int audioCount = audioStage.process(demodBuf, ifCount, audioBuf);

        // Resample preAudioRate -> AUDIO_RATE (linear interpolation).
        int outCount = 0;
        double pos = resamplePos;
        while (pos < audioCount) {
            int idx = (int) pos;
            float frac = (float) (pos - idx);
            float a = (idx == 0) ? resamplePrev : audioBuf[idx - 1];
            float b = audioBuf[idx];
            if (outCount >= outBuf.length) {
                break;
            }
            outBuf[outCount++] = a + frac * (b - a);
            pos += resampleStep;
        }
        if (audioCount > 0) {
            resamplePrev = audioBuf[audioCount - 1];
            resamplePos = pos - audioCount;
            if (resamplePos < 0) {
                resamplePos = 0;
            }
        }

        // FM de-emphasis (one-pole low-pass) at the audio rate.
        if (useDeemphasis) {
            float s = deemphState;
            float al = deemphAlpha;
            for (int k = 0; k < outCount; k++) {
                s += al * (outBuf[k] - s);
                outBuf[k] = s;
            }
            deemphState = s;
        }

        // CW: narrow band-pass at the pitch, with makeup gain for the narrow
        // filter and the real-part halving in the BFO mix.
        if (mode == Mode.CW && cwFilter != null) {
            for (int k = 0; k < outCount; k++) {
                outBuf[k] = cwFilter.process(outBuf[k]) * 2.0f;
            }
        }

        float[] result = new float[outCount];
        System.arraycopy(outBuf, 0, result, 0, outCount);
        return result;
    }
}
