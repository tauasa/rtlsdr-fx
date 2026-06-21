package org.tauasa.apps.sdr.rtl;

/** Tuner chips reported in the rtl_tcp dongle-info header. */
public enum TunerType {
    UNKNOWN, E4000, FC0012, FC0013, FC2580, R820T, R828D;

    public static TunerType fromCode(int code) {
        return switch (code) {
            case 1 -> E4000;
            case 2 -> FC0012;
            case 3 -> FC0013;
            case 4 -> FC2580;
            case 5 -> R820T;
            case 6 -> R828D;
            default -> UNKNOWN;
        };
    }
}
