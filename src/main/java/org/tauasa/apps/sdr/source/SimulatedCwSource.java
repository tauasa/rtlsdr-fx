package org.tauasa.apps.sdr.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Synthetic CW (Morse) source: a keyed carrier sitting at the centre of the
 * band. With {@code CW} mode the demodulator's BFO turns it into an audible tone,
 * so selecting this source plus CW mode is the no-hardware way to hear Morse.
 *
 * <p>Unlike {@link SimulatedSource} (which produces a fixed block per tick for
 * the display), this source is paced to wall-clock time at the true sample rate,
 * so the audio path receives correctly-timed samples and the Morse plays back at
 * the right speed. The carrier is a pure DC tone (I keyed, Q ~ 0) shaped by a few
 * milliseconds of rise/fall to avoid key clicks, over a low noise floor.
 */
public final class SimulatedCwSource implements SignalSource {

    private static final String MESSAGE = "CQ DE TAUASA K";
    private static final int WPM = 18;
    private static final double CARRIER = 0.35;
    private static final double NOISE = 0.0016;
    private static final double KEY_RAMP_SECONDS = 0.004; // ~4 ms, click-free keying

    private final int[] schedOn;  // 1 = key down, 0 = key up
    private final int[] schedLen; // length of the element in dot-units

    private volatile int sampleRate;
    private volatile long centerFreq;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-cw-source");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> task;
    private volatile boolean running;
    private final Random rnd = new Random();

    private int schedIdx;
    private long elementRemain; // samples left in the current element
    private double target;      // 1.0 key down, 0.0 key up
    private double env;         // smoothed keying envelope

    private int unitSamples;    // dot length in samples (depends on sample rate)
    private double keyCoeff;    // one-pole smoothing coefficient for the envelope
    private int cachedRate;     // sample rate the above were computed for
    private long lastNs;

    public SimulatedCwSource(int sampleRate, long centerFreq) {
        this.sampleRate = sampleRate;
        this.centerFreq = centerFreq;
        List<int[]> sched = buildSchedule(MESSAGE + "  "); // trailing word gap before the loop repeats
        this.schedOn = new int[sched.size()];
        this.schedLen = new int[sched.size()];
        for (int k = 0; k < sched.size(); k++) {
            schedOn[k] = sched.get(k)[0];
            schedLen[k] = sched.get(k)[1];
        }
        recomputeTiming();
    }

    /** International Morse for the characters used here. */
    private static String morse(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'A' -> ".-";   case 'B' -> "-..."; case 'C' -> "-.-."; case 'D' -> "-..";
            case 'E' -> ".";    case 'F' -> "..-."; case 'G' -> "--.";  case 'H' -> "....";
            case 'I' -> "..";   case 'J' -> ".---"; case 'K' -> "-.-";  case 'L' -> ".-..";
            case 'M' -> "--";   case 'N' -> "-.";   case 'O' -> "---";  case 'P' -> ".--.";
            case 'Q' -> "--.-"; case 'R' -> ".-.";  case 'S' -> "...";  case 'T' -> "-";
            case 'U' -> "..-";  case 'V' -> "...-"; case 'W' -> ".--";  case 'X' -> "-..-";
            case 'Y' -> "-.--"; case 'Z' -> "--..";
            case '0' -> "-----"; case '1' -> ".----"; case '2' -> "..---"; case '3' -> "...--";
            case '4' -> "....-"; case '5' -> "....."; case '6' -> "-...."; case '7' -> "--...";
            case '8' -> "---.."; case '9' -> "----.";
            case '/' -> "-..-."; case '=' -> "-...-"; case '.' -> ".-.-.-"; case ',' -> "--..--";
            case '?' -> "..--..";
            default -> "";
        };
    }

    /**
     * Builds an on/off schedule in dot-units: dot = 1, dash = 3, intra-character
     * gap = 1, inter-character gap = 3, word gap = 7.
     */
    private static List<int[]> buildSchedule(String message) {
        List<int[]> seq = new ArrayList<>();
        String[] words = message.toUpperCase().split(" ");
        for (String word : words) {
            for (int c = 0; c < word.length(); c++) {
                String code = morse(word.charAt(c));
                if (code.isEmpty()) {
                    continue;
                }
                for (int s = 0; s < code.length(); s++) {
                    seq.add(new int[]{1, code.charAt(s) == '-' ? 3 : 1});
                    seq.add(new int[]{0, 1}); // intra-character gap
                }
                seq.remove(seq.size() - 1);
                seq.add(new int[]{0, 3}); // letter gap
            }
            seq.remove(seq.size() - 1);
            seq.add(new int[]{0, 7}); // word gap
        }
        return seq;
    }

    private void recomputeTiming() {
        int rate = sampleRate;
        this.unitSamples = Math.max(1, (int) Math.round(rate * 1.2 / WPM));
        this.keyCoeff = Math.exp(-1.0 / (KEY_RAMP_SECONDS * rate));
        this.cachedRate = rate;
    }

    @Override
    public void start(IqListener listener) {
        running = true;
        schedIdx = 0;
        elementRemain = 0;
        env = 0;
        target = 0;
        lastNs = System.nanoTime();
        long periodMs = 20;
        task = exec.scheduleAtFixedRate(() -> {
            try {
                float[] block = generate();
                if (block.length > 0) {
                    listener.onBlock(block);
                }
            } catch (RuntimeException ignored) {
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    private float[] generate() {
        if (sampleRate != cachedRate) {
            recomputeTiming();
        }
        long now = System.nanoTime();
        long count = Math.round(sampleRate * (now - lastNs) / 1e9);
        lastNs = now;
        if (count <= 0) {
            return new float[0];
        }
        long cap = (long) (sampleRate * 0.25); // catch up gracefully after a stall
        if (count > cap) {
            count = cap;
        }

        int n = (int) count;
        float[] iq = new float[2 * n];
        for (int s = 0; s < n; s++) {
            if (elementRemain <= 0) {
                target = schedOn[schedIdx];
                elementRemain = (long) schedLen[schedIdx] * unitSamples;
                schedIdx = (schedIdx + 1) % schedOn.length;
            }
            elementRemain--;
            env = target + (env - target) * keyCoeff;
            double a = CARRIER * env;
            iq[2 * s] = (float) (a + NOISE * rnd.nextGaussian());     // carrier at DC (band centre)
            iq[2 * s + 1] = (float) (NOISE * rnd.nextGaussian());
        }
        return iq;
    }

    @Override
    public void stop() {
        running = false;
        if (task != null) {
            task.cancel(true);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void setCenterFrequency(long hz) {
        this.centerFreq = hz;
    }

    @Override
    public void setSampleRate(int samplesPerSecond) {
        this.sampleRate = samplesPerSecond;
    }

    @Override
    public void setGain(int tenthsDb) {
        // no-op for the simulator
    }

    @Override
    public void setAutoGain(boolean auto) {
        // no-op for the simulator
    }

    @Override
    public String describe() {
        return String.format("Simulated CW  [\"%s\" @ %d WPM]", MESSAGE, WPM);
    }
}
