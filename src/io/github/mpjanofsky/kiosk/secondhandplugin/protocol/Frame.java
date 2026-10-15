// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin.protocol;

/** One frame received from the clock, after unescaping. {@code checksumOk} is false when the checksum did not match. */
public final class Frame {
    public final int cmd;
    public final int err;
    public final int seq;
    public final byte[] payload;
    public final boolean checksumOk;

    Frame(int cmd, int err, int seq, byte[] payload, boolean checksumOk) {
        this.cmd = cmd;
        this.err = err;
        this.seq = seq;
        this.payload = payload;
        this.checksumOk = checksumOk;
    }
}
