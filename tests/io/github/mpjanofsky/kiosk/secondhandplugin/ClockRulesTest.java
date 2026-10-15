// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.AlertToken;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.ClockTime;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Messages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/** Every state of the timer rules, with a hand-driven clock. The timings follow the spike's voice-timer run. */
public final class ClockRulesTest {
    private static final TimeZone NY = TimeZone.getTimeZone("America/New_York");
    private static final long T0 = 1791993600000L;      // 2026-10-14 12:00:00 EDT
    private static final String A = "01JAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String B = "01JBBBBBBBBBBBBBBBBBBBBBBB";

    public static void main(String[] args) {
        connectSendsTimeAndClear();
        zoneChangeResendsTime();
        startShowsTimer();
        cancelClearsIt();
        finishPulsesOnceThenDismissClears();
        dismissInsideFirstSecond();
        pauseAndResume();
        updateChangesEnd();
        severalTimers();
        nothingWhileDisconnected();
        reconnectResendsOnlyRunning();
        missedFinishIsNotReplayed();
        halfHourTimeInfo();
        staleTimersAreForgotten();
        unknownEventsAreIgnored();
        broadcastParsing();
        System.out.println("ClockRulesTest ok");
    }

    private static ClockRules connected() {
        ClockRules r = new ClockRules(NY);
        r.onConnected(T0);
        return r;
    }

    private static TimerEvent ev(TimerEvent.Type t, String id, int left, boolean active) {
        return new TimerEvent(t, id, left, active);
    }

    private static List<String> labels(List<ClockCommand> c) {
        List<String> out = new ArrayList<String>();
        for (ClockCommand x : c) {
            out.add(x.label);
        }
        return out;
    }

    private static void is(List<ClockCommand> got, String... want) {
        assert labels(got).equals(Arrays.asList(want)) : labels(got) + " != " + Arrays.asList(want);
    }

    private static boolean same(ClockCommand c, byte[] payload) {
        return Arrays.equals(c.payload, payload);
    }

    private static void connectSendsTimeAndClear() {
        ClockRules r = new ClockRules(NY);
        List<ClockCommand> c = r.onConnected(T0);
        is(c, "timeinfo", "ClearIndicator");
        assert same(c.get(0), Messages.timeInfo("2026-10-14T12:00:00-04:00"));
        assert same(c.get(1), Messages.clearIndicator());
    }

    private static void zoneChangeResendsTime() {
        ClockRules r = new ClockRules(NY);
        r.onConnected(T0);
        r.setZone(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
        List<ClockCommand> c = r.onTick(T0 + 1000);
        is(c, "timeinfo");
        assert same(c.get(0), Messages.timeInfo("2026-10-14T09:00:01-07:00"));
    }

    private static void startShowsTimer() {
        ClockRules r = connected();
        List<ClockCommand> c = r.onTimer(ev(TimerEvent.Type.STARTED, A, 90, true), T0 + 1000);
        is(c, "SetAlert TIMER");
        assert same(c.get(0), Messages.setTimer(AlertToken.fromTimerId(A), "2026-10-14T12:01:31-04:00")) : "end = now + 90 s";
        assert r.runningCount() == 1;
    }

    private static void cancelClearsIt() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 120, true), T0);
        List<ClockCommand> c = r.onTimer(ev(TimerEvent.Type.CANCELLED, A, 0, false), T0 + 5000);
        is(c, "DeleteAlert");
        assert same(c.get(0), Messages.deleteAlert(AlertToken.fromTimerId(A)));
        assert r.runningCount() == 0;
        is(r.onTimer(ev(TimerEvent.Type.CANCELLED, A, 0, false), T0 + 6000));    // a second cancel is a no-op
    }

    private static void finishPulsesOnceThenDismissClears() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 90, true), T0);
        long end = T0 + 90000;
        is(r.onTimer(ev(TimerEvent.Type.FINISHED, A, 0, false), end));
        assert r.nextDue() == end + 1000 : "active is due 1 s after the end";
        is(r.onTick(end + 500));
        List<ClockCommand> c = r.onTick(end + 1000);
        is(c, "timers=active");
        assert same(c.get(0), Messages.timersActive());
        is(r.onTick(end + 12000));                                              // once, never repeated
        assert r.nextDue() > end + 12000;
        c = r.onTimer(ev(TimerEvent.Type.DISMISSED, A, 0, false), end + 8000);
        is(c, "DeleteAlert", "timers=cleared");
        assert same(c.get(1), Messages.timersCleared());
        is(r.onTimer(ev(TimerEvent.Type.DISMISSED, A, 0, false), end + 9000));   // already gone
    }

    private static void dismissInsideFirstSecond() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 10, true), T0);
        r.onTimer(ev(TimerEvent.Type.FINISHED, A, 0, false), T0 + 10000);
        is(r.onTimer(ev(TimerEvent.Type.DISMISSED, A, 0, false), T0 + 10400), "DeleteAlert");
        is(r.onTick(T0 + 11000));                                               // the pulse is cancelled, not sent late
    }

    private static void pauseAndResume() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 600, true), T0);
        is(r.onTimer(ev(TimerEvent.Type.UPDATED, A, 540, false), T0 + 60000), "DeleteAlert");
        assert r.runningCount() == 0;
        List<ClockCommand> c = r.onTimer(ev(TimerEvent.Type.UPDATED, A, 540, true), T0 + 120000);
        is(c, "SetAlert TIMER");
        assert same(c.get(0), Messages.setTimer(AlertToken.fromTimerId(A), ClockTime.format(T0 + 120000 + 540000, NY))) : "same token, new end";
    }

    private static void updateChangesEnd() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 300, true), T0);
        List<ClockCommand> c = r.onTimer(ev(TimerEvent.Type.UPDATED, A, 420, true), T0 + 10000);
        is(c, "SetAlert TIMER");
        assert same(c.get(0), Messages.setTimer(AlertToken.fromTimerId(A), ClockTime.format(T0 + 430000, NY)));
        assert r.runningCount() == 1;
    }

    private static void severalTimers() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 90, true), T0);
        r.onTimer(ev(TimerEvent.Type.STARTED, B, 120, true), T0 + 1000);
        assert r.runningCount() == 2;
        is(r.onTimer(ev(TimerEvent.Type.CANCELLED, B, 0, false), T0 + 5000), "DeleteAlert");
        r.onTimer(ev(TimerEvent.Type.FINISHED, A, 0, false), T0 + 90000);
        assert r.runningCount() == 0;
        is(r.onTick(T0 + 91000), "timers=active");
    }

    private static void nothingWhileDisconnected() {
        ClockRules r = new ClockRules(NY);
        is(r.onTimer(ev(TimerEvent.Type.STARTED, A, 90, true), T0));
        assert r.runningCount() == 1 : "still tracked for the next connect";
        is(r.onTick(T0 + 3600000));
        assert r.nextDue() == Long.MAX_VALUE;
    }

    private static void reconnectResendsOnlyRunning() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 600, true), T0);
        r.onTimer(ev(TimerEvent.Type.STARTED, B, 30, true), T0);
        r.onDisconnected();
        r.onTimer(ev(TimerEvent.Type.STARTED, "01JCCCCCCCCCCCCCCCCCCCCCCC", 900, true), T0 + 10000);   // started while the link was down
        List<ClockCommand> c = r.onConnected(T0 + 60000);                       // B has ended by now
        is(c, "timeinfo", "ClearIndicator", "SetAlert TIMER", "SetAlert TIMER");
        assert same(c.get(2), Messages.setTimer(AlertToken.fromTimerId(A), ClockTime.format(T0 + 600000, NY)));
        assert same(c.get(3), Messages.setTimer(AlertToken.fromTimerId("01JCCCCCCCCCCCCCCCCCCCCCCC"), ClockTime.format(T0 + 10000 + 900000, NY)));
    }

    private static void missedFinishIsNotReplayed() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 30, true), T0);
        r.onDisconnected();
        r.onTimer(ev(TimerEvent.Type.FINISHED, A, 0, false), T0 + 30000);
        List<ClockCommand> c = r.onConnected(T0 + 120000);
        is(c, "timeinfo", "ClearIndicator");
        is(r.onTick(T0 + 130000));
        is(r.onTimer(ev(TimerEvent.Type.DISMISSED, A, 0, false), T0 + 140000));
    }

    private static void halfHourTimeInfo() {
        ClockRules r = new ClockRules(NY);
        long start = T0 + 7 * 60000;                                            // 12:07
        r.onConnected(start);
        long half = T0 + 30 * 60000;                                            // 12:30
        assert r.nextDue() == half : "next timeinfo is on the half hour";
        is(r.onTick(half - 1));
        List<ClockCommand> c = r.onTick(half);
        is(c, "timeinfo");
        assert same(c.get(0), Messages.timeInfo("2026-10-14T12:30:00-04:00"));
        assert r.nextDue() == T0 + 60 * 60000 : "then 13:00";
        // Late tick (a stalled thread) sends once and moves to the next boundary.
        is(r.onTick(T0 + 3 * 3600000 + 5000), "timeinfo");
        assert r.nextDue() == T0 + 3 * 3600000 + 1800000;
    }

    private static void staleTimersAreForgotten() {
        ClockRules r = connected();
        r.onTimer(ev(TimerEvent.Type.STARTED, A, 60, true), T0);
        r.onTick(T0 + 60000 + ClockRules.STALE_MS + 1);
        assert r.runningCount() == 0;
    }

    private static void unknownEventsAreIgnored() {
        ClockRules r = connected();
        is(r.onTimer(ev(TimerEvent.Type.UPDATED, "nope", 10, false), T0));
        is(r.onTimer(ev(TimerEvent.Type.DISMISSED, "nope", 0, false), T0));
        is(r.onTimer(ev(TimerEvent.Type.STARTED, "nope", 10, false), T0));    // a start that isn't active is not shown
        // A finish we never saw start (plugin started mid-timer) still pulses.
        is(r.onTimer(ev(TimerEvent.Type.FINISHED, "late", 0, false), T0));
        is(r.onTick(T0 + 1000), "timers=active");
    }

    private static void broadcastParsing() {
        String p = TimerEvent.ACTION_PREFIX;
        TimerEvent e = TimerEvent.fromBroadcast(p + "STARTED", "id1", Integer.valueOf(90), Boolean.TRUE);
        assert e.type == TimerEvent.Type.STARTED && e.timerId.equals("id1") && e.secondsLeft == 90 && e.active;
        e = TimerEvent.fromBroadcast(p + "FINISHED", "id1", Long.valueOf(0), Boolean.FALSE);
        assert e.type == TimerEvent.Type.FINISHED && !e.active;
        e = TimerEvent.fromBroadcast(p + "UPDATED", "id1", Double.valueOf(41.9), null);
        assert e.secondsLeft == 41 && !e.active;
        assert TimerEvent.fromBroadcast(p + "NOPE", "id1", null, null) == null;
        assert TimerEvent.fromBroadcast("other.action", "id1", null, null) == null;
        assert TimerEvent.fromBroadcast(p + "STARTED", "", null, null) == null;
        assert TimerEvent.fromBroadcast(null, "id1", null, null) == null;
        assert TimerEvent.fromBroadcast(p + "STARTED", null, null, null) == null;
        // Values that arrive as text are still understood.
        e = TimerEvent.fromBroadcast(p + "STARTED", "id1", "90", "true");
        assert e.secondsLeft == 90 && e.active;
        e = TimerEvent.fromBroadcast(p + "STARTED", "id1", " 12.5 ", "TRUE");
        assert e.secondsLeft == 12 && e.active;
        e = TimerEvent.fromBroadcast(p + "STARTED", "id1", "soon", "no");
        assert e.secondsLeft == 0 && !e.active;
        assert TimerEvent.fromBroadcast(p + "STARTED", "id1", Long.valueOf(-5), Boolean.TRUE).secondsLeft == 0;
        assert TimerEvent.fromBroadcast(p + "STARTED", "id1", Long.valueOf(99999999999L), Boolean.TRUE).secondsLeft == 86400;
    }
}
