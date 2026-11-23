// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Everything that touches Android for one running configuration: the Bluetooth adapter, the timer broadcasts and
 * the link. Built on a plugin thread, never in a callback. {@link #stop} undoes all of it.
 */
final class ClockService {
    private final PluginHost host;
    private Context context;
    private BluetoothAdapter adapter;
    private ClockLink link;
    private BroadcastReceiver receiver;
    private BroadcastReceiver systemReceiver;
    private String address = "";

    /** The status tile: the verdict last reached, whether the tile is on, and what is on the Overview now. */
    private final Object tileLock = new Object();
    private String verdictLevel = "";
    private String verdictText = "";
    private boolean verdictKnown;
    private boolean tileShown = true;
    private boolean tilePublished;
    private String publishedLevel = "";
    private String publishedText = "";

    /** The link's state, for the Reconnect button's result. */
    private final LinkWatch watch = new LinkWatch();

    ClockService(PluginHost host) {
        this.host = host;
    }

    /** Starts (or restarts) with the given settings. */
    synchronized void start(Map<String, Object> settings) {
        stop();
        Object a = settings.get("address");
        address = a == null ? "" : String.valueOf(a).trim();
        synchronized (tileLock) {
            verdictKnown = false;   // a new link starts from its own first state
        }
        showTile(shownSetting(settings));
        watch.update(null);
        context = context();
        if (context == null) {
            status("Could not reach Android. See the log.", true);
            verdict("off", "Could not reach Android. See the log.");
            return;
        }
        adapter = BluetoothAdapter.getDefaultAdapter();
        ClockLink.Listener listener = new ClockLink.Listener() {
            @Override public void onState(ClockLink.State state, String detail) {
                status(detail, state == ClockLink.State.RETRYING);
                watch.update(state);
                // Each retry passes through Connecting; keeping the last verdict stops the tile flicking to gray and back.
                if (state != ClockLink.State.CONNECTING || !tileHasVerdict()) {
                    verdict(StatusVerdict.level(state, !address.isEmpty()), detail);
                }
            }

            @Override public void onLog(String message) {
                log(message);
            }
        };
        link = new ClockLink(new AndroidTransport(adapter, address), new ClockRules(TimeZone.getDefault()), listener, new ClockLink.Timing());
        registerReceiver();
        registerSystemReceiver();
        link.start();
    }

    synchronized void stop() {
        if (receiver != null && context != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // not registered
            }
        }
        receiver = null;
        if (systemReceiver != null && context != null) {
            try {
                context.unregisterReceiver(systemReceiver);
            } catch (RuntimeException ignored) {
                // not registered
            }
        }
        systemReceiver = null;
        if (link != null) {
            link.stop();
            link = null;
        }
    }

    /** Asks the link to try now. False when there is no link (no Android context). */
    synchronized boolean reconnect() {
        if (link == null) {
            return false;
        }
        link.reconnectNow();
        return true;
    }

    /** Wakes the link and waits up to {@code millis} for the attempt it starts to end; true when the clock answered. */
    boolean reconnectAndWait(long millis) throws InterruptedException {
        return watch.wakeAndWait(new LinkWatch.Wake() {
            @Override public boolean wake() {
                return reconnect();
            }
        }, millis);
    }

    private boolean tileHasVerdict() {
        synchronized (tileLock) {
            return verdictKnown;
        }
    }

    /** The "show status tile" setting; absent means on, the default. */
    static boolean shownSetting(Map<String, Object> settings) {
        Object v = settings.get("status_tile");
        return v == null || Boolean.parseBoolean(String.valueOf(v));
    }

    /** Turns the Overview tile on or off. Off removes it; on shows the latest verdict again. Never called from stop. */
    void showTile(boolean shown) {
        synchronized (tileLock) {
            tileShown = shown;
            if (!shown && tilePublished) {
                tilePublished = false;
                try {
                    host.removeStatusTile(StatusVerdict.KEY);
                } catch (RuntimeException e) {
                    log("could not remove the status tile: " + e);
                }
            } else if (shown && verdictKnown) {
                publishTile();
            }
        }
    }

    /** Records the link's verdict and puts it on the tile if the tile is on and the verdict changed. Takes only tileLock, because the link thread calls it while stop() waits for that thread. */
    private void verdict(String level, String text) {
        synchronized (tileLock) {
            verdictLevel = level;
            verdictText = StatusVerdict.text(text);
            verdictKnown = true;
            if (tileShown) {
                publishTile();
            }
        }
    }

    /** Publishes only when the level or text differs from what is on the Overview now. Caller holds tileLock. */
    private void publishTile() {
        if (tilePublished && verdictLevel.equals(publishedLevel) && verdictText.equals(publishedText)) {
            return;
        }
        try {
            host.publishStatusTile(StatusVerdict.KEY, StatusVerdict.TITLE, verdictLevel, verdictText);
            tilePublished = true;
            publishedLevel = verdictLevel;
            publishedText = verdictText;
        } catch (UnsupportedOperationException | LinkageError olderKiosk) {
            tileShown = false;   // Kiosk Satellite before status tiles: nothing to show
        } catch (RuntimeException e) {
            log("could not publish the status tile: " + e);
        }
    }

    /** Pairs with the clock and returns its address, or null. Blocks up to about two minutes. */
    String pair() throws InterruptedException {
        Context c;
        BluetoothAdapter b;
        String known;
        synchronized (this) {
            known = address;
            c = context != null ? context : context();
            b = adapter != null ? adapter : BluetoothAdapter.getDefaultAdapter();
        }
        if (c == null || b == null || !b.isEnabled()) {
            status("Bluetooth is off or unavailable.", true);
            verdict("off", "Bluetooth is off or unavailable.");
            return null;
        }
        return new Pairing(c, b, new Pairing.Progress() {
            @Override public void say(String message) {
                status(message, false);
                log(message);
            }

            @Override public void log(String message) {
                ClockService.this.log(message);
            }
        }, known).run();
    }

    /** Removes the Bluetooth bond; true when Android reports it removed. */
    synchronized boolean forget() {
        return adapter != null && !address.isEmpty() && Pairing.forget(adapter, address);
    }

    private void registerReceiver() {
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                ClockLink l = link;
                Bundle extras = intent.getExtras();
                if (l == null || extras == null) {
                    return;
                }
                TimerEvent e = TimerEvent.fromBroadcast(intent.getAction(), extras.get("timer_id"),
                        extras.get("seconds_left"), extras.get("is_active"));
                if (e != null) {
                    l.post(e);
                } else {
                    log("ignored a timer broadcast it could not read: " + intent.getAction());
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        for (TimerEvent.Type t : TimerEvent.Type.values()) {
            filter.addAction(TimerEvent.ACTION_PREFIX + t.name());
        }
        // Kiosk Satellite sends these as implicit broadcasts (no package, no permission) so Tasker and the like get
        // them too. From Android 14 an implicit intent only reaches exported receivers, even inside the sending app,
        // so a not-exported receiver heard nothing there (issue #1). Exported means any app on the kiosk can send a
        // fake timer, as it always could before Android 13 (no flag meant exported); TimerEvent and ClockRules bound
        // what such a broadcast can do: known actions only, a short id, at most 24 h, a capped number of timers.
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /** Time zone changes (the clock follows the kiosk) and the clock's own Bluetooth connection (wake the link at once). */
    private void registerSystemReceiver() {
        systemReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                ClockLink l = link;
                if (l == null || intent.getAction() == null) {
                    return;
                }
                if (Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                    l.setZone(TimeZone.getDefault());
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null && address.equalsIgnoreCase(d.getAddress())) {
                        l.reconnectNow();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        // Protected system broadcasts only (other apps cannot send them), so Android allows registering without a
        // flag, and the Android 14 implicit-intent rule does not apply: it covers app-sent intents. ACL_CONNECTED
        // comes from the Bluetooth app, which a not-exported receiver would miss, so no flag is the right choice.
        context.registerReceiver(systemReceiver, filter);
    }

    private Context context() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread"); // ks-rules: allow sdk-only Android framework class (not KS) for a Context; the SDK offers none. See PLAN Decisions.
            return (Application) thread.getMethod("currentApplication").invoke(null);
        } catch (Exception e) {
            log("no Android context: " + e);
            return null;
        }
    }

    void saveAddress(String value) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("address", value);
        try {
            try {
                host.updateSettings(values);   // saves only the address and keeps every other setting
            } catch (UnsupportedOperationException | LinkageError olderApp) {
                host.saveSettings(values);     // Kiosk Satellite before 2026.10.30; the address is the only setting
            }
        } catch (RuntimeException e) {
            log("could not save the address: " + e);
        }
    }

    /** Where a settings-button run hears progress; null when none is running. */
    private volatile SetupReporter reporter;

    void reporter(SetupReporter value) {
        reporter = value;
    }

    private void status(String message, boolean error) {
        SetupReporter r = reporter;
        if (r != null && !error) {
            r.progress(message.startsWith(Pairing.HINT) ? message.substring(Pairing.HINT.length()) : message);
        }
        try {
            host.status(message, error);
        } catch (RuntimeException ignored) {
            // the host is revoked once the plugin has stopped
        }
    }

    private void log(String message) {
        try {
            host.log(message);
        } catch (RuntimeException ignored) {
            // the host is revoked once the plugin has stopped
        }
    }
}
