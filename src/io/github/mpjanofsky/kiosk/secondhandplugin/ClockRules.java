// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.AlertToken;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.ClockTime;
import io.github.mpjanofsky.kiosk.secondhandplugin.protocol.Messages;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * What to tell the clock, and when. No clock, no threads, no Android: events and the time go in, commands
 * come out, so every state is testable. Mirrors what a real Echo sends (protocol notes, section 5b and 6):
 * <ul>
 * <li>timer started or resumed: SetAlert with the end time; the same timer always has the same token;</li>
 * <li>paused or cancelled: DeleteAlert;</li>
 * <li>timer reached zero: StateUpdate timers=active, once, about 1 s after the end;</li>
 * <li>stopped while ringing: DeleteAlert and timers=cleared;</li>
 * <li>on every connect: timeinfo, ClearIndicator, then SetAlert for each timer still running;</li>
 * <li>timeinfo again on every half hour.</li>
 * </ul>
 * Nothing is sent while the clock is not connected, and nothing is replayed late: a timer that finished
 * while the link was down is not pulsed after reconnecting, because a pulse for an old event would mislead.
 * Not thread-safe: call it from one thread.
 */
public final class ClockRules {
    /** A real Echo sends timers=active about a second after the end time. */
    static final long RING_DELAY_MS = 1000;
    /** A timer we never heard finish is forgotten this long after its end time. */
    static final long STALE_MS = 5 * 60 * 1000;
    /** A ringing timer nobody stopped is forgotten after this long. */
    static final long RINGING_MS = 10 * 60 * 1000;
    private static final long HALF_HOUR_MS = 30 * 60 * 1000;

    private TimeZone zone;
    private boolean connected;
    private long nextTimeInfo = Long.MAX_VALUE;
    /** Timers counting down: id -> end time in epoch ms. Insertion order is start order. */
    private final Map<String, Long> running = new LinkedHashMap<String, Long>();
    /** Timers that reached zero and have not been stopped: id -> when the clock was told. */
    private final Map<String, Long> ringing = new LinkedHashMap<String, Long>();
    /** Timers that reached zero, waiting for timers=active: id -> when to send it. */
    private final Map<String, Long> ringDue = new LinkedHashMap<String, Long>();

    public ClockRules(TimeZone zone) {
        this.zone = zone;
    }

    /** The kiosk's time zone changed: later times use it, and the clock gets a fresh timeinfo at the next tick. */
    public void setZone(TimeZone zone) {
        this.zone = zone;
        if (connected) {
            nextTimeInfo = 0;
        }
    }

    /** The link is up and the Discover exchange is done. Returns the commands to send, in order. */
    public List<ClockCommand> onConnected(long now) {
        connected = true;
        nextTimeInfo = nextHalfHour(now);
        ringDue.clear();
        ringing.clear();
        List<ClockCommand> out = new ArrayList<ClockCommand>();
        out.add(timeInfo(now));
        out.add(new ClockCommand("ClearIndicator", Messages.clearIndicator()));
        dropStale(now);
        for (Map.Entry<String, Long> t : running.entrySet()) {
            if (t.getValue() > now) {
                out.add(setAlert(t.getKey(), t.getValue()));
            }
        }
        return out;
    }

    public void onDisconnected() {
        connected = false;
        nextTimeInfo = Long.MAX_VALUE;
        ringDue.clear();
        ringing.clear();
    }

    public List<ClockCommand> onTimer(TimerEvent e, long now) {
        List<ClockCommand> out = new ArrayList<ClockCommand>();
        String id = e.timerId;
        switch (e.type) {
            case STARTED:
                if (e.active) {
                    start(id, e, now, out);
                }
                break;
            case UPDATED:
                if (e.active) {
                    start(id, e, now, out);     // resumed, or time added or removed: same token, new end
                } else if (running.remove(id) != null) {
                    delete(id, out);            // paused
                }
                break;
            case CANCELLED:
                if (running.remove(id) != null) {
                    delete(id, out);
                }
                break;
            case FINISHED:
                running.remove(id);
                ringDue.put(id, now + RING_DELAY_MS);
                break;
            case DISMISSED:
                if (ringDue.remove(id) != null) {
                    // Stopped within the first second: the clock never pulsed. Just clear the alert.
                    delete(id, out);
                } else if (ringing.remove(id) != null) {
                    delete(id, out);
                    out.add(new ClockCommand("timers=cleared", Messages.timersCleared()));
                } else if (running.remove(id) != null) {
                    delete(id, out);
                }
                break;
            default:
                break;
        }
        return connected ? out : new ArrayList<ClockCommand>();
    }

    /** Call when {@link #nextDue} has passed. */
    public List<ClockCommand> onTick(long now) {
        List<ClockCommand> out = new ArrayList<ClockCommand>();
        Iterator<Map.Entry<String, Long>> it = ringDue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> due = it.next();
            if (due.getValue() <= now) {
                it.remove();
                if (connected) {
                    ringing.put(due.getKey(), now);
                    out.add(new ClockCommand("timers=active", Messages.timersActive()));
                }
            }
        }
        if (connected && now >= nextTimeInfo) {
            out.add(timeInfo(now));
            nextTimeInfo = nextHalfHour(now);
        }
        dropStale(now);
        return out;
    }

    /** When {@link #onTick} next has something to do, in epoch ms, or Long.MAX_VALUE. */
    public long nextDue() {
        long next = nextTimeInfo;
        for (long due : ringDue.values()) {
            next = Math.min(next, due);
        }
        return next;
    }

    /** How many timers are counting down, for the status line. */
    public int runningCount() {
        return running.size();
    }

    private void start(String id, TimerEvent e, long now, List<ClockCommand> out) {
        long end = now + e.secondsLeft * 1000L;
        running.put(id, end);
        ringDue.remove(id);
        ringing.remove(id);
        out.add(setAlert(id, end));
    }

    private void delete(String id, List<ClockCommand> out) {
        out.add(new ClockCommand("DeleteAlert", Messages.deleteAlert(AlertToken.fromTimerId(id))));
    }

    private ClockCommand setAlert(String id, long end) {
        return new ClockCommand("SetAlert TIMER", Messages.setTimer(AlertToken.fromTimerId(id), ClockTime.format(end, zone)));
    }

    private ClockCommand timeInfo(long now) {
        return new ClockCommand("timeinfo", Messages.timeInfo(ClockTime.format(now, zone)));
    }

    private void dropStale(long now) {
        Iterator<Map.Entry<String, Long>> r = running.entrySet().iterator();
        while (r.hasNext()) {
            if (r.next().getValue() + STALE_MS < now) {
                r.remove();
            }
        }
        Iterator<Map.Entry<String, Long>> g = ringing.entrySet().iterator();
        while (g.hasNext()) {
            if (g.next().getValue() + RINGING_MS < now) {
                g.remove();
            }
        }
    }

    /** The next :00 or :30 after {@code now}, in the zone's own time (a real Echo re-sends timeinfo on the half hour). */
    long nextHalfHour(long now) {
        long local = now + zone.getOffset(now);
        long boundary = (local / HALF_HOUR_MS + 1) * HALF_HOUR_MS;
        return boundary - zone.getOffset(now);
    }
}
