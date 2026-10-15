// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

import java.io.ByteArrayOutputStream;

/**
 * Builds outgoing SPP frames: {@code F0 | 02 | 00 | seq | escaped(payload + checksum16_be) | F1}.
 * The checksum is {@code (0x02 + 0x00 + sum(payload)) & 0xFFFF}. Bytes F0/F1/F2 inside the payload or
 * checksum go out as {@code F2, (byte ^ F2)}. The sequence number never takes a reserved value, so it
 * needs no escaping. A real Echo starts at 1; use one Framer per connection.
 */
public final class Framer {
    static final int STX = 0xF0;
    static final int ETX = 0xF1;
    static final int ESC = 0xF2;
    static final int CMD = 0x02;
    static final int ERR = 0x00;

    private int seq;

    public Framer() {
        this(1);
    }

    public Framer(int firstSeq) {
        this.seq = firstSeq & 0xFF;
    }

    static boolean reserved(int b) {
        return b == STX || b == ETX || b == ESC;
    }

    /** Wraps one protobuf payload in a frame and advances the sequence number. */
    public byte[] frame(byte[] payload) {
        int sum = CMD + ERR;
        for (byte b : payload) {
            sum += b & 0xFF;
        }
        sum &= 0xFFFF;
        int thisSeq = seq;
        seq = (seq + 1) & 0xFF;
        while (reserved(seq)) {
            seq = (seq + 1) & 0xFF;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        out.write(STX);
        out.write(CMD);
        out.write(ERR);
        out.write(thisSeq);
        for (byte b : payload) {
            escape(out, b & 0xFF);
        }
        escape(out, sum >> 8);
        escape(out, sum & 0xFF);
        out.write(ETX);
        return out.toByteArray();
    }

    private static void escape(ByteArrayOutputStream out, int b) {
        if (reserved(b)) {
            out.write(ESC);
            out.write(ESC ^ b);
        } else {
            out.write(b);
        }
    }
}
