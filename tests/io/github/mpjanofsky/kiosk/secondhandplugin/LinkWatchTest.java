// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

/** Reconnect's result is the outcome of the attempt it starts: connected, failed, or a short answer when no attempt can run. */
public final class LinkWatchTest {
    /** Plays the link: on wake, reports the given states from another thread after a short delay. */
    static LinkWatch.Wake link(final LinkWatch watch, final long delayMs, final ClockLink.State... states) {
        return new LinkWatch.Wake() {
            @Override public boolean wake() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            return;
                        }
                        for (ClockLink.State s : states) {
                            watch.update(s);
                        }
                    }
                }).start();
                return true;
            }
        };
    }

    static final LinkWatch.Wake NEVER_CALLED = new LinkWatch.Wake() {
        @Override public boolean wake() {
            throw new AssertionError("woke the link");
        }
    };

    public static void main(String[] args) throws Exception {
        // Retrying, then the attempt connects after a slow (2 s) Bluetooth connect: success, not a timeout.
        LinkWatch w = new LinkWatch();
        w.update(ClockLink.State.RETRYING);
        assert w.wakeAndWait(link(w, 2000, ClockLink.State.CONNECTING, ClockLink.State.CONNECTED), 15000);

        // The attempt fails: the answer comes when it ends, well before the cap.
        w = new LinkWatch();
        w.update(ClockLink.State.RETRYING);
        long t0 = System.currentTimeMillis();
        assert !w.wakeAndWait(link(w, 100, ClockLink.State.CONNECTING, ClockLink.State.RETRYING), 15000);
        assert System.currentTimeMillis() - t0 < 2000;

        // Nothing happens: the cap ends the wait.
        w = new LinkWatch();
        w.update(ClockLink.State.RETRYING);
        assert !w.wakeAndWait(link(w, 0), 300);

        // Already connected, Bluetooth off, or no link: answered without waiting.
        w = new LinkWatch();
        w.update(ClockLink.State.CONNECTED);
        assert w.wakeAndWait(NEVER_CALLED, 15000);
        w.update(ClockLink.State.UNAVAILABLE);
        assert !w.wakeAndWait(NEVER_CALLED, 15000);
        w.update(null);
        assert !w.wakeAndWait(new LinkWatch.Wake() {
            @Override public boolean wake() {
                return false;
            }
        }, 15000);

        System.out.println("LinkWatchTest ok");
    }
}
