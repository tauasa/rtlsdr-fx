package org.tauasa.apps.sdr.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Plays mono 16-bit PCM audio at {@value #RATE} Hz through the default output
 * device. Float samples (roughly in [-1, 1]) are submitted from the demodulation
 * thread; a dedicated daemon thread drains a small queue into the
 * {@link SourceDataLine}. The queue is bounded and drops the oldest chunk when it
 * backs up, which keeps latency low if the consumer ever falls behind.
 */
public final class AudioPlayer {

    public static final int RATE = 48_000;

    private final AudioFormat format =
            new AudioFormat(RATE, 16, 1, true, false); // signed PCM, little-endian, mono
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);

    private volatile SourceDataLine line;
    private volatile Thread writer;
    private volatile boolean running;
    private volatile float volume = 0.6f;

    public synchronized void start() throws LineUnavailableException {
        if (running) {
            return;
        }
        line = AudioSystem.getSourceDataLine(format);
        line.open(format, RATE * 2 / 5); // ~200 ms buffer
        line.start();
        running = true;
        writer = new Thread(this::writeLoop, "audio-writer");
        writer.setDaemon(true);
        writer.start();
    }

    public synchronized void stop() {
        running = false;
        Thread w = writer;
        if (w != null) {
            w.interrupt();
        }
        queue.clear();
        SourceDataLine l = line;
        if (l != null) {
            l.stop();
            l.flush();
            l.close();
        }
        line = null;
        writer = null;
    }

    public boolean isRunning() {
        return running;
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
    }

    /** Submits mono audio (~[-1,1]); non-blocking, drops the oldest chunk if backed up. */
    public void submit(float[] audio, int count) {
        if (!running || count <= 0) {
            return;
        }
        float vol = volume;
        byte[] pcm = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            float s = audio[i] * vol;
            if (s > 1f) {
                s = 1f;
            } else if (s < -1f) {
                s = -1f;
            }
            int v = (int) (s * 32767f);
            pcm[2 * i] = (byte) (v & 0xFF);
            pcm[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        if (!queue.offer(pcm)) {
            queue.poll();      // drop oldest
            queue.offer(pcm);
        }
    }

    private void writeLoop() {
        try {
            while (running) {
                byte[] chunk = queue.take();
                SourceDataLine l = line;
                if (l != null) {
                    l.write(chunk, 0, chunk.length);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
