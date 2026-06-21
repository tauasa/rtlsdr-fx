package org.tauasa.apps.sdr.rtl;

/**
 * rtl_tcp command opcodes. Each command on the wire is 5 bytes:
 * one opcode byte followed by a big-endian 32-bit parameter.
 */
public final class RtlCommand {

    private RtlCommand() {
    }

    public static final int SET_FREQUENCY = 0x01;       // Hz
    public static final int SET_SAMPLE_RATE = 0x02;     // samples/sec
    public static final int SET_GAIN_MODE = 0x03;       // 0 = auto, 1 = manual
    public static final int SET_GAIN = 0x04;            // tenths of a dB
    public static final int SET_FREQ_CORRECTION = 0x05; // ppm
    public static final int SET_AGC_MODE = 0x08;        // 0 = off, 1 = on
}
