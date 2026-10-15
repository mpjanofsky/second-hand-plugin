// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the raw byte stream from the clock into frames. Feed it whatever each read returns: frames may be
 * split across reads or arrive several to a read. Bytes outside a frame are ignored; an F0 inside a frame
 * starts over; a frame longer than {@link #MAX_FRAME} is dropped, so a garbage stream can't grow the buffer.
 */
public final class Deframer {
    public static final int MAX_FRAME = 4096;

    private byte[] buf = new byte[256];
    private int len;
    private boolean inFrame;
    private boolean escaped;

    public List<Frame> feed(byte[] data, int count) {
        List<Frame> out = new ArrayList<Frame>();
        for (int i = 0; i < count; i++) {
            int b = data[i] & 0xFF;
            if (b == Framer.STX) {
                inFrame = true;
                escaped = false;
                len = 0;
            } else if (!inFrame) {
                continue;
            } else if (b == Framer.ETX) {
                Frame frame = finish();
                if (frame != null) {
                    out.add(frame);
                }
                inFrame = false;
            } else if (escaped) {
                add(Framer.ESC ^ b);
                escaped = false;
            } else if (b == Framer.ESC) {
                escaped = true;
            } else {
                add(b);
            }
        }
        return out;
    }

    private void add(int b) {
        if (len >= MAX_FRAME) {
            inFrame = false;
            return;
        }
        if (len == buf.length) {
            byte[] bigger = new byte[buf.length * 2];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        buf[len++] = (byte) b;
    }

    private Frame finish() {
        if (len < 5) {
            return null;
        }
        int cmd = buf[0] & 0xFF;
        int err = buf[1] & 0xFF;
        int seq = buf[2] & 0xFF;
        byte[] payload = new byte[len - 5];
        System.arraycopy(buf, 3, payload, 0, payload.length);
        int found = ((buf[len - 2] & 0xFF) << 8) | (buf[len - 1] & 0xFF);
        int sum = cmd + err;
        for (byte p : payload) {
            sum += p & 0xFF;
        }
        return new Frame(cmd, err, seq, payload, found == (sum & 0xFFFF));
    }
}
