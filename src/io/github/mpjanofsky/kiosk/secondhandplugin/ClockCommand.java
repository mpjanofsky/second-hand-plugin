// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

/** One message to send to the clock: the protobuf payload for a frame, and a short label for the log. */
public final class ClockCommand {
    public final String label;
    public final byte[] payload;

    public ClockCommand(String label, byte[] payload) {
        this.label = label;
        this.payload = payload;
    }
}
