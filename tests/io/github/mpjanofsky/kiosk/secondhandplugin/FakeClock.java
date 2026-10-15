// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Deframer;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Frame;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Framer;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Messages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** A pretend clock and Bluetooth stack: records what the link writes and answers Discover like the real one. */
final class FakeClock implements ClockTransport {
    final List<String> sent = Collections.synchronizedList(new ArrayList<String>());   // namespace/name or name=value
    final List<byte[]> payloads = Collections.synchronizedList(new ArrayList<byte[]>());
    volatile int failConnects;
    volatile boolean answerDiscover = true;
    volatile String unavailable;
    volatile int connects;
    volatile Runnable callIn;
    volatile boolean listening;
    volatile FakeSocket current;
    private volatile Thread connecting;

    @Override public String unavailableReason() {
        return unavailable;
    }

    @Override public Socket connect() throws IOException {
        connects++;
        connecting = Thread.currentThread();
        try {
            if (failConnects > 0) {
                failConnects--;
                throw new IOException("read failed, socket might closed or timeout, read ret: -1");
            }
            FakeSocket s = new FakeSocket();
            current = s;
            return s;
        } finally {
            connecting = null;
        }
    }

    @Override public void abort() {}

    @Override public void startListening(Runnable onCallIn) {
        callIn = onCallIn;
        listening = true;
    }

    @Override public void stopListening() {
        listening = false;
    }

    /** The clock drops the link. */
    void drop() {
        FakeSocket s = current;
        if (s != null) {
            s.closeFromClock();
        }
    }

    final class FakeSocket implements Socket {
        private final BlockingQueue<byte[]> toLink = new LinkedBlockingQueue<byte[]>();
        private final byte[] END = new byte[0];
        private volatile boolean closed;
        private final Deframer deframer = new Deframer();
        private final Framer clockFramer = new Framer(0);

        @Override public InputStream in() {
            return new InputStream() {
                private byte[] cur = new byte[0];
                private int pos;

                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n <= 0 ? -1 : one[0] & 0xFF;
                }

                @Override public int read(byte[] b, int off, int len) throws IOException {
                    while (pos >= cur.length) {
                        try {
                            cur = toLink.take();
                        } catch (InterruptedException e) {
                            throw new IOException("interrupted");
                        }
                        pos = 0;
                        if (cur == END) {
                            toLink.add(END);
                            return -1;
                        }
                    }
                    int n = Math.min(len, cur.length - pos);
                    System.arraycopy(cur, pos, b, off, n);
                    pos += n;
                    return n;
                }
            };
        }

        @Override public OutputStream out() throws IOException {
            return new OutputStream() {
                private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

                @Override public void write(int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (closed) {
                        throw new IOException("closed");
                    }
                    for (Frame f : deframer.feed(java.util.Arrays.copyOfRange(b, off, off + len), len)) {
                        if (!f.checksumOk) {
                            sent.add("BAD CHECKSUM");
                            continue;
                        }
                        Messages.Incoming in = Messages.parse(f.payload);
                        String name = in == null ? "?" : in.name;
                        sent.add(name);
                        payloads.add(f.payload);
                        if ("Discover".equals(name) && answerDiscover) {
                            toLink.add(discoverResponse());
                        }
                    }
                }
            };
        }

        private byte[] discoverResponse() {
            byte[] hdr = concat(field(1, "Alexa.Discovery"), field(2, "Discover.Response"));
            return clockFramer.frame(field(1, concat(field(1, hdr), field(2, new byte[] {0x0a, 0x04, 't', 'e', 's', 't'}))));
        }

        void closeFromClock() {
            toLink.add(END);
        }

        @Override public void close() {
            closed = true;
            toLink.add(END);
        }
    }

    private static byte[] field(int n, String s) {
        return field(n, s.getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    private static byte[] field(int n, byte[] v) {
        byte[] out = new byte[v.length + 2];
        out[0] = (byte) ((n << 3) | 2);
        out[1] = (byte) v.length;
        System.arraycopy(v, 0, out, 2, v.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
