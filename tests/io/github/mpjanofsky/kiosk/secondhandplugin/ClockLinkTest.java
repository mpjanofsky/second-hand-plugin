// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** The link against a fake clock: handshake order, retry, reconnect and resend, call-in, timers, and a clean stop. */
public final class ClockLinkTest {
    private static final class Recorder implements ClockLink.Listener {
        final List<ClockLink.State> states = Collections.synchronizedList(new ArrayList<ClockLink.State>());
        final List<String> logs = Collections.synchronizedList(new ArrayList<String>());

        @Override public void onState(ClockLink.State state, String detail) {
            states.add(state);
        }

        @Override public void onLog(String message) {
            logs.add(message);
        }
    }

    private static ClockLink.Timing fast() {
        ClockLink.Timing t = new ClockLink.Timing();
        t.retryFirst = 40;
        t.retryMax = 160;
        t.discoverResponseWait = 300;
        t.frameGap = 2;
        t.stopJoin = 1500;
        return t;
    }

    private static void waitFor(String what, java.util.concurrent.Callable<Boolean> cond) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (cond.call()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static List<String> sent(FakeClock c) {
        synchronized (c.sent) {
            return new ArrayList<String>(c.sent);
        }
    }

    private static ClockLink make(FakeClock clock, Recorder rec) {
        return new ClockLink(clock, new ClockRules(TimeZone.getTimeZone("America/New_York")), rec, fast());
    }

    public static void main(String[] args) throws Exception {
        handshakeOrder();
        timerReachesClock();
        retriesAfterFailedConnect();
        reconnectsAndResends();
        callInReconnectsNow();
        unavailableThenAvailable();
        noDiscoverResponseCarriesOn();
        stopIsPromptEvenWhileBlocked();
        System.out.println("ClockLinkTest ok");
    }

    private static void handshakeOrder() throws Exception {
        final FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        ClockLink link = make(clock, rec);
        link.start();
        waitFor("handshake", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).size() >= 3; }
        });
        assert sent(clock).subList(0, 3).equals(Arrays.asList("Discover", "StateUpdate", "ClearIndicator")) : sent(clock);
        assert clock.listening : "call-in listener running";
        link.stop();
        assert !clock.listening;
    }

    private static void timerReachesClock() throws Exception {
        final FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        ClockLink link = make(clock, rec);
        link.start();
        waitFor("connected", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).size() >= 3; }
        });
        link.post(new TimerEvent(TimerEvent.Type.STARTED, "01JAAAAAAAAAAAAAAAAAAAAAAA", 90, true));
        waitFor("SetAlert", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).contains("SetAlert"); }
        });
        link.post(new TimerEvent(TimerEvent.Type.CANCELLED, "01JAAAAAAAAAAAAAAAAAAAAAAA", 0, false));
        waitFor("DeleteAlert", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).contains("DeleteAlert"); }
        });
        link.stop();
    }

    private static void retriesAfterFailedConnect() throws Exception {
        final FakeClock clock = new FakeClock();
        clock.failConnects = 2;
        Recorder rec = new Recorder();
        ClockLink link = make(clock, rec);
        link.start();
        waitFor("connected after two failures", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).size() >= 3; }
        });
        assert clock.connects == 3 : clock.connects;
        assert rec.states.contains(ClockLink.State.RETRYING);
        link.stop();
    }

    private static void reconnectsAndResends() throws Exception {
        final FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        ClockLink link = make(clock, rec);
        link.start();
        waitFor("first handshake", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).size() >= 3; }
        });
        link.post(new TimerEvent(TimerEvent.Type.STARTED, "01JAAAAAAAAAAAAAAAAAAAAAAA", 600, true));
        waitFor("SetAlert", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).contains("SetAlert"); }
        });
        clock.sent.clear();
        clock.drop();
        waitFor("second handshake with the timer resent", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).equals(Arrays.asList("Discover", "StateUpdate", "ClearIndicator", "SetAlert")); }
        });
        assert clock.connects == 2;
        link.stop();
    }

    private static void callInReconnectsNow() throws Exception {
        final FakeClock clock = new FakeClock();
        clock.failConnects = 1;
        ClockLink.Timing t = fast();
        t.retryFirst = 60000;       // would wait a minute; the call-in must cut that short
        Recorder rec = new Recorder();
        ClockLink link = new ClockLink(clock, new ClockRules(TimeZone.getTimeZone("UTC")), rec, t);
        link.start();
        waitFor("first failure", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return clock.connects == 1; }
        });
        Thread.sleep(50);
        clock.callIn.run();
        waitFor("connected after call-in", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).size() >= 3; }
        });
        link.stop();
    }

    private static void unavailableThenAvailable() throws Exception {
        final FakeClock clock = new FakeClock();
        clock.unavailable = "No clock paired yet.";
        final Recorder rec = new Recorder();
        ClockLink link = make(clock, rec);
        link.start();
        waitFor("unavailable state", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return rec.states.contains(ClockLink.State.UNAVAILABLE); }
        });
        Thread.sleep(100);
        assert clock.connects == 0;
        assert rec.states.size() == 1 : "state is reported once, not every poll: " + rec.states;
        clock.unavailable = null;
        link.reconnectNow();
        waitFor("connected once available", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).size() >= 3; }
        });
        link.stop();
    }

    private static void noDiscoverResponseCarriesOn() throws Exception {
        final FakeClock clock = new FakeClock();
        clock.answerDiscover = false;
        Recorder rec = new Recorder();
        ClockLink link = make(clock, rec);
        link.start();
        waitFor("handshake without a response", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).size() >= 3; }
        });
        link.stop();
    }

    private static void stopIsPromptEvenWhileBlocked() throws Exception {
        final FakeClock clock = new FakeClock();
        clock.answerDiscover = false;
        Recorder rec = new Recorder();
        ClockLink.Timing t = fast();
        t.discoverResponseWait = 60000;     // the link is waiting for a response that never comes
        ClockLink link = new ClockLink(clock, new ClockRules(TimeZone.getTimeZone("UTC")), rec, t);
        link.start();
        waitFor("Discover sent", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() { return sent(clock).contains("Discover"); }
        });
        long t0 = System.currentTimeMillis();
        link.stop();
        assert System.currentTimeMillis() - t0 < 1000 : "stop took " + (System.currentTimeMillis() - t0);
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            assert !th.getName().startsWith("echo-wall-clock") || !th.isAlive() : "thread left: " + th.getName();
        }
    }
}
