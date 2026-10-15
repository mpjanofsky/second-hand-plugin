// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Deframer;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Frame;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Framer;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one connection to the clock alive and feeds it what {@link ClockRules} says. One thread owns the rules and
 * all writes; a second thread per connection only reads. Everything is stopped, joined, and the sockets closed before
 * {@link #stop} returns.
 */
public final class ClockLink {
    public enum State { UNAVAILABLE, CONNECTING, CONNECTED, RETRYING }

    public interface Listener {
        void onState(State state, String detail);
        void onLog(String message);
    }

    /** Waits, in ms. Tests shrink them. */
    public static final class Timing {
        public long retryFirst = 5000;
        public long retryMax = 60000;
        public long discoverResponseWait = 10000;
        public long frameGap = 300;
        public long stopJoin = 1500;
    }

    private static final int FAILURES_BEFORE_HINT = 5;
    private static final Object EOF = new Object();
    private static final Object WAKE = new Object();
    private static final Object STOP = new Object();

    private static final class Posted {
        final TimerEvent event;
        final long at;

        Posted(TimerEvent event, long at) {
            this.event = event;
            this.at = at;
        }
    }

    private static final class ZoneChange {
        final java.util.TimeZone zone;

        ZoneChange(java.util.TimeZone zone) {
            this.zone = zone;
        }
    }

    private static final class Read {
        final Frame frame;
        final ClockTransport.Socket from;

        Read(Frame frame, ClockTransport.Socket from) {
            this.frame = frame;
            this.from = from;
        }
    }

    private final ClockTransport transport;
    private final ClockRules rules;
    private final Listener listener;
    private final Timing timing;
    private final BlockingQueue<Object> inbox = new LinkedBlockingQueue<Object>();
    private Thread thread;
    private volatile boolean stopped;
    private volatile ClockTransport.Socket socket;

    public ClockLink(ClockTransport transport, ClockRules rules, Listener listener, Timing timing) {
        this.transport = transport;
        this.rules = rules;
        this.listener = listener;
        this.timing = timing;
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        transport.startListening(new Runnable() {
            @Override public void run() {
                inbox.add(WAKE);
            }
        });
        thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    loop();
                } catch (InterruptedException e) {
                    // stopping
                } catch (RuntimeException e) {
                    listener.onLog("link thread failed: " + e);
                    state(State.RETRYING, "Stopped by an internal error; see the log.");
                } finally {
                    closeSocket();
                }
            }
        }, "echo-wall-clock-link");
        thread.setDaemon(true);
        thread.start();
    }

    /** A timer event from the kiosk. Safe from any thread; never blocks. */
    public void post(TimerEvent event) {
        inbox.add(new Posted(event, System.currentTimeMillis()));
    }

    /** The kiosk's time zone changed. Safe from any thread; applied on the link thread. */
    public void setZone(java.util.TimeZone zone) {
        inbox.add(new ZoneChange(zone));
    }

    /** Try to connect now instead of waiting out the retry delay. */
    public void reconnectNow() {
        inbox.add(WAKE);
    }

    public synchronized void stop() {
        stopped = true;
        inbox.add(STOP);
        transport.stopListening();
        transport.abort();
        closeSocket();
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(timing.stopJoin);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                listener.onLog("link thread did not stop in time");
            }
        }
    }

    private void closeSocket() {
        ClockTransport.Socket s = socket;
        socket = null;
        if (s != null) {
            s.close();
        }
    }

    // ------------------------------------------------------------------ the loop

    private void loop() throws InterruptedException {
        long retry = timing.retryFirst;
        long nextAttempt = 0;
        int failures = 0;
        while (!stopped) {
            String why = transport.unavailableReason();
            if (why != null) {
                state(State.UNAVAILABLE, why);
                drain(2000);
                continue;
            }
            long now = System.currentTimeMillis();
            if (now < nextAttempt) {
                if (drain(nextAttempt - now)) {
                    nextAttempt = 0;
                    retry = timing.retryFirst;
                }
                continue;
            }
            state(State.CONNECTING, "Connecting to the clock.");
            ClockTransport.Socket s = null;
            try {
                s = transport.connect();
            } catch (IOException e) {
                listener.onLog("connect failed: " + e.getMessage());
            }
            if (stopped) {
                if (s != null) {
                    s.close();
                }
                return;
            }
            if (s == null) {
                failures++;
                state(State.RETRYING, failures < FAILURES_BEFORE_HINT
                        ? "Clock not reachable; trying again in " + (retry / 1000) + " s."
                        : "Clock still not reachable after " + failures + " tries. Check that it has power, is in range and"
                        + " paired with this kiosk; trying again in " + (retry / 1000) + " s.");
                nextAttempt = System.currentTimeMillis() + retry;
                retry = Math.min(retry * 2, timing.retryMax);
                continue;
            }
            retry = timing.retryFirst;
            failures = 0;
            session(s);
            rules.onDisconnected();
            closeSocket();
            if (!stopped) {
                state(State.RETRYING, "Link lost; reconnecting.");
                nextAttempt = System.currentTimeMillis() + timing.retryFirst;
            }
        }
    }

    /** Takes inbox items for up to {@code ms}, applying timer events to the rules while not connected. Returns true when WAKE ended the wait. */
    private boolean drain(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!stopped) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return false;
            }
            Object item = inbox.poll(left, TimeUnit.MILLISECONDS);
            if (item == null || item == STOP) {
                return false;
            }
            if (item == WAKE) {
                return true;
            }
            if (item instanceof Posted) {
                Posted p = (Posted) item;
                rules.onTimer(p.event, p.at);
            } else if (item instanceof ZoneChange) {
                rules.setZone(((ZoneChange) item).zone);
            }
            // Stale frames and EOFs from an earlier connection are dropped.
        }
        return false;
    }

    private State lastState;
    private String lastDetail;

    /** Reports a state only when it changed, so a retry loop does not rewrite the status line every second. */
    private void state(State state, String detail) {
        if (state != lastState || !detail.equals(lastDetail)) {
            lastState = state;
            lastDetail = detail;
            listener.onState(state, detail);
        }
    }

    // ------------------------------------------------------------------ one connection

    private void session(final ClockTransport.Socket s) throws InterruptedException {
        socket = s;
        inbox.remove(WAKE);
        final Framer framer = new Framer(1);
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                read(s);
            }
        }, "echo-wall-clock-read");
        reader.setDaemon(true);
        reader.start();
        try {
            send(s, framer, "Discover", Messages.discover());
            if (!awaitDiscoverResponse(s)) {
                listener.onLog("no Discover.Response in time; carrying on");
            }
            if (stopped) {
                return;
            }
            Thread.sleep(timing.frameGap);
            for (ClockCommand c : rules.onConnected(System.currentTimeMillis())) {
                send(s, framer, c.label, c.payload);
                Thread.sleep(timing.frameGap);
            }
            state(State.CONNECTED, status());
            while (!stopped) {
                long wait = Math.max(0, Math.min(rules.nextDue() - System.currentTimeMillis(), 60000));
                Object item = inbox.poll(wait, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (item == STOP) {
                    return;
                }
                if (item == EOF) {
                    listener.onLog("the clock closed the link");
                    return;
                }
                if (item instanceof Posted) {
                    Posted p = (Posted) item;
                    sendAll(s, framer, rules.onTimer(p.event, p.at));
                    state(State.CONNECTED, status());
                } else if (item instanceof Read) {
                    Read r = (Read) item;
                    if (r.from == s) {
                        handle(r.frame);
                    }
                } else if (item instanceof ZoneChange) {
                    rules.setZone(((ZoneChange) item).zone);
                } else if (item == WAKE) {
                    continue;
                }
                sendAll(s, framer, rules.onTick(now));
            }
        } catch (IOException e) {
            listener.onLog("link error: " + e.getMessage());
        } finally {
            closeSocket();
            reader.interrupt();
            try {
                reader.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean awaitDiscoverResponse(ClockTransport.Socket s) throws InterruptedException {
        long end = System.currentTimeMillis() + timing.discoverResponseWait;
        List<Object> deferred = new ArrayList<Object>();
        try {
            while (!stopped) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                Object item = inbox.poll(left, TimeUnit.MILLISECONDS);
                if (item == null || item == STOP) {
                    return false;
                }
                if (item == EOF) {
                    deferred.add(EOF);
                    return false;
                }
                if (item instanceof Read) {
                    Read r = (Read) item;
                    if (r.from == s && r.frame.checksumOk && Messages.isDiscoverResponse(r.frame.payload)) {
                        return true;
                    }
                } else if (item instanceof Posted || item instanceof ZoneChange) {
                    deferred.add(item);     // applied once the handshake is done
                }
            }
            return false;
        } finally {
            inbox.addAll(deferred);
        }
    }

    private void handle(Frame f) {
        if (!f.checksumOk) {
            listener.onLog("frame with a bad checksum ignored");
            return;
        }
        Messages.Incoming in = Messages.parse(f.payload);
        listener.onLog("from clock: " + (in == null ? "unreadable" : in.namespace + "/" + in.name));
    }

    private String status() {
        int n = rules.runningCount();
        return n == 0 ? "Connected to the clock." : "Connected to the clock; showing " + n + (n == 1 ? " timer." : " timers.");
    }

    private void sendAll(ClockTransport.Socket s, Framer framer, List<ClockCommand> commands) throws IOException, InterruptedException {
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) {
                Thread.sleep(timing.frameGap);
            }
            send(s, framer, commands.get(i).label, commands.get(i).payload);
        }
    }

    private void send(ClockTransport.Socket s, Framer framer, String label, byte[] payload) throws IOException {
        OutputStream out = s.out();
        out.write(framer.frame(payload));
        out.flush();
        listener.onLog("sent " + label);
    }

    private void read(ClockTransport.Socket s) {
        Deframer deframer = new Deframer();
        byte[] buf = new byte[1024];
        try {
            InputStream in = s.in();
            int n;
            while ((n = in.read(buf)) > 0) {
                for (Frame f : deframer.feed(buf, n)) {
                    inbox.add(new Read(f, s));
                }
            }
        } catch (IOException e) {
            // closed or lost: reported below
        }
        if (socket == s) {
            inbox.add(EOF);
        }
    }
}
