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

    ClockService(PluginHost host) {
        this.host = host;
    }

    /** Starts (or restarts) with the given settings. */
    synchronized void start(Map<String, Object> settings) {
        stop();
        Object a = settings.get("address");
        address = a == null ? "" : String.valueOf(a).trim();
        context = context();
        if (context == null) {
            status("Could not reach Android. See the log.", true);
            return;
        }
        adapter = BluetoothAdapter.getDefaultAdapter();
        ClockLink.Listener listener = new ClockLink.Listener() {
            @Override public void onState(ClockLink.State state, String detail) {
                status(detail, state == ClockLink.State.RETRYING);
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

    synchronized void reconnect() {
        if (link != null) {
            link.reconnectNow();
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
        if (Build.VERSION.SDK_INT >= 33) {
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
        // System broadcasts: delivered whatever the export flag, which only matters for other apps' broadcasts.
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
            host.saveSettings(values);
        } catch (RuntimeException e) {
            log("could not save the address: " + e);
        }
    }

    private void status(String message, boolean error) {
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
