// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

/**
 * Follows the link's state so Reconnect can report the clock's answer rather than a fixed timeout's: a Bluetooth
 * connect can take several seconds either way. Plain Java, so it is tested off-device.
 */
final class LinkWatch {
    interface Wake {
        /** Asks the link to try now; false when there is no link. */
        boolean wake();
    }

    private boolean connected;
    private boolean unavailable;
    private long attemptsEnded;

    /** Records the link's state; null when no link runs. Any state but Connecting ends an attempt. */
    synchronized void update(ClockLink.State state) {
        connected = state == ClockLink.State.CONNECTED;
        unavailable = state == ClockLink.State.UNAVAILABLE;
        if (state != null && state != ClockLink.State.CONNECTING) {
            attemptsEnded++;
        }
        notifyAll();
    }

    /**
     * Wakes the link and waits for the attempt it starts to end. True at once when already connected (the link ignores
     * a wake then); false at once when Bluetooth is off or no clock is set, since the link makes no attempt then.
     */
    boolean wakeAndWait(Wake wake, long millis) throws InterruptedException {
        long mark;
        synchronized (this) {
            if (connected) {
                return true;
            }
            if (unavailable) {
                return false;
            }
            mark = attemptsEnded;   // read before waking, so a fast attempt is not missed
        }
        // Woken outside the lock: the link thread reports states through update() while it handles the wake.
        if (!wake.wake()) {
            return false;
        }
        long end = System.currentTimeMillis() + millis;
        synchronized (this) {
            while (attemptsEnded == mark && !connected) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                wait(left);
            }
            return connected;
        }
    }
}
