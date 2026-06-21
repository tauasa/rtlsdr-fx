package org.tauasa.apps.sdr.source;

import org.tauasa.apps.sdr.rtl.RtlCommand;
import org.tauasa.apps.sdr.rtl.TunerType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Speaks the rtl_tcp protocol used by {@code rtl_tcp} from librtlsdr. On connect
 * the server sends a 12-byte dongle-info header ("RTL0" + tuner type + gain
 * count) and then streams raw 8-bit unsigned interleaved IQ. Commands are sent
 * back as five-byte (opcode + big-endian param) frames.
 */
public final class RtlTcpSource implements SignalSource {

    private final String host;
    private final int port;
    private final int fftSize;

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile Thread reader;
    private volatile boolean running;

    private TunerType tunerType = TunerType.UNKNOWN;
    private int tunerGainCount;

    public RtlTcpSource(String host, int port, int fftSize) {
        this.host = host;
        this.port = port;
        this.fftSize = fftSize;
    }

    @Override
    public void start(IqListener listener) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setTcpNoDelay(true);
        InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
        this.out = new BufferedOutputStream(s.getOutputStream());
        this.socket = s;

        byte[] hdr = in.readNBytes(12);
        if (hdr.length == 12 && hdr[0] == 'R' && hdr[1] == 'T' && hdr[2] == 'L' && hdr[3] == '0') {
            this.tunerType = TunerType.fromCode(beInt(hdr, 4));
            this.tunerGainCount = beInt(hdr, 8);
        }

        running = true;
        reader = new Thread(() -> readLoop(in, listener), "rtl-tcp-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(InputStream in, IqListener listener) {
        int blockBytes = fftSize * 2;          // 2 unsigned bytes per complex sample
        byte[] raw = new byte[blockBytes];
        float[] iq = new float[fftSize * 2];
        try {
            while (running) {
                int read = 0;
                while (read < blockBytes) {
                    int r = in.read(raw, read, blockBytes - read);
                    if (r < 0) {
                        running = false;
                        return;
                    }
                    read += r;
                }
                for (int i = 0; i < blockBytes; i++) {
                    iq[i] = (float) (((raw[i] & 0xFF) - 127.5) / 127.5);
                }
                listener.onBlock(iq);
            }
        } catch (IOException e) {
            running = false;
        }
    }

    private synchronized void send(int cmd, int param) {
        OutputStream o = this.out;
        if (o == null) {
            return;
        }
        try {
            byte[] b = new byte[5];
            b[0] = (byte) cmd;
            b[1] = (byte) (param >>> 24);
            b[2] = (byte) (param >>> 16);
            b[3] = (byte) (param >>> 8);
            b[4] = (byte) param;
            o.write(b);
            o.flush();
        } catch (IOException ignored) {
            // socket is going away; reader loop will notice
        }
    }

    @Override
    public void setCenterFrequency(long hz) {
        send(RtlCommand.SET_FREQUENCY, (int) hz);
    }

    @Override
    public void setSampleRate(int samplesPerSecond) {
        send(RtlCommand.SET_SAMPLE_RATE, samplesPerSecond);
    }

    @Override
    public void setAutoGain(boolean auto) {
        send(RtlCommand.SET_GAIN_MODE, auto ? 0 : 1);
        send(RtlCommand.SET_AGC_MODE, auto ? 1 : 0);
    }

    @Override
    public void setGain(int tenthsDb) {
        send(RtlCommand.SET_GAIN_MODE, 1);
        send(RtlCommand.SET_GAIN, tenthsDb);
    }

    @Override
    public void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        out = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String describe() {
        return "RTL-TCP " + host + ":" + port + "  [" + tunerType + ", " + tunerGainCount + " gain steps]";
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
