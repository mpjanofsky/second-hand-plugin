// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The Bluetooth side, as far as {@link ClockLink} needs it. The real one is {@link AndroidTransport}; tests use a fake. */
public interface ClockTransport {
    /** An open RFCOMM channel 4 connection to the clock. */
    interface Socket {
        InputStream in() throws IOException;
        OutputStream out() throws IOException;
        void close();
    }

    /** Why the next connect cannot even be tried (no clock chosen, Bluetooth off), or null when it can. */
    String unavailableReason();

    /** Blocks until connected. Throws IOException when the clock does not answer, or when {@link #abort} was called. */
    Socket connect() throws IOException;

    /** Interrupts a connect in progress. Safe from any thread. */
    void abort();

    /** Accepts the clock's call-in (it connects to us after a pairing or a dropped link) and runs {@code onCallIn} for each. */
    void startListening(Runnable onCallIn);

    void stopListening();
}
