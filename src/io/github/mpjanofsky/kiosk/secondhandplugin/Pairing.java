// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Finds the clock while its light pulses orange (hold its button) and bonds with it. Android shows a pairing consent
 * prompt on the kiosk that someone must tap; the plugin cannot accept it. Blocking: run it off the callback threads.
 */
final class Pairing {
    /** The name the clock advertises starts with this; the rest varies per unit. */
    static final String NAME_PREFIX = "EchoWallClk";

    interface Progress {
        /** Shown on the plugin's status line and logged. */
        void say(String message);

        /** Logged only. */
        void log(String message);
    }

    /** android.bluetooth.device.extra.PAIRING_VARIANT: 0 PIN, 2 passkey confirmation, 3 consent (Just Works). */
    private static final String EXTRA_VARIANT = "android.bluetooth.device.extra.PAIRING_VARIANT";

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Progress progress;
    private final String knownAddress;
    private final Object lock = new Object();
    private String foundAddress;
    private int seen;
    private int bondState = BluetoothDevice.BOND_NONE;
    private boolean bondingStarted;

    /** {@code knownAddress} is the clock's address from an earlier pairing, or empty: a discovery result carries no name once Android has forgotten the device, so the address is the fallback. */
    Pairing(Context context, BluetoothAdapter adapter, Progress progress, String knownAddress) {
        this.context = context;
        this.adapter = adapter;
        this.progress = progress;
        this.knownAddress = knownAddress == null ? "" : knownAddress.trim();
    }

    /** Returns the clock's address once it is bonded, or null after saying why not. */
    String run() throws InterruptedException {
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            String name = d.getName();
            if (name != null && name.startsWith(NAME_PREFIX)) {
                progress.say("The clock is already paired.");
                return d.getAddress();
            }
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                BluetoothDevice device = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) {
                    return;
                }
                synchronized (lock) {
                    String action = i.getAction();
                    if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                        String name = device.getName();
                        if (name == null) {
                            name = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                        }
                        boolean byName = name != null && name.startsWith(NAME_PREFIX);
                        boolean byAddress = !knownAddress.isEmpty() && knownAddress.equalsIgnoreCase(device.getAddress());
                        progress.log("discovered a device: " + (name == null ? "no name" : "named " + name)
                                + (byName ? " (the clock, by name)" : byAddress ? " (the clock, by address)" : ""));
                        if (byName || byAddress) {
                            foundAddress = device.getAddress();
                        } else {
                            seen++;
                            progress.say("Hold the clock's button until its light pulses orange. Looking for the clock... ("
                                    + seen + (seen == 1 ? " other device" : " other devices") + " seen so far.)");
                        }
                    } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                        int variant = i.getIntExtra(EXTRA_VARIANT, -1);
                        progress.log("pairing prompt, variant " + variant);
                        if (variant == 0 || variant == 1) {
                            progress.say("The clock asks for a PIN. Type it on the prompt on this screen.");
                        }
                    } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)
                            && device.getAddress().equalsIgnoreCase(foundAddress)) {
                        bondState = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                        if (bondState == BluetoothDevice.BOND_BONDING) {
                            bondingStarted = true;
                        }
                    }
                    lock.notifyAll();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        try {
            progress.say("Hold the clock's button until its light pulses orange. Looking for the clock...");
            adapter.startDiscovery();
            long end = System.currentTimeMillis() + 90000;
            synchronized (lock) {
                while (foundAddress == null && System.currentTimeMillis() < end) {
                    lock.wait(500);
                    // One Android discovery lasts only about 12 s; keep searching so the clock can enter pairing mode later.
                    if (foundAddress == null && !adapter.isDiscovering()) {
                        adapter.startDiscovery();
                    }
                }
            }
            adapter.cancelDiscovery();
            if (foundAddress == null) {
                progress.say("The clock was not found. Hold its button until its light pulses orange, then run Pair clock again.");
                return null;
            }
            progress.say("Found the clock. Tap Pair on the prompt that appears on this screen.");
            BluetoothDevice clock = adapter.getRemoteDevice(foundAddress);
            if (clock.getBondState() != BluetoothDevice.BOND_BONDED && !clock.createBond()) {
                progress.say("Android would not start pairing. Try again.");
                return null;
            }
            end = System.currentTimeMillis() + 60000;
            synchronized (lock) {
                while (clock.getBondState() != BluetoothDevice.BOND_BONDED && System.currentTimeMillis() < end) {
                    lock.wait(500);
                    if (bondingStarted && bondState == BluetoothDevice.BOND_NONE) {
                        break;      // started, then refused or timed out
                    }
                }
            }
            if (clock.getBondState() != BluetoothDevice.BOND_BONDED) {
                progress.say("Pairing did not finish. Tap Pair on the prompt within a minute, then try again.");
                return null;
            }
            progress.say("Paired.");
            return foundAddress;
        } finally {
            adapter.cancelDiscovery();
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // not registered
            }
        }
    }

    /** Removes the bond (the clock can then be paired to another controller, such as the Pi). Hidden API, by reflection. */
    static boolean forget(BluetoothAdapter adapter, String address) {
        try {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            Object ok = BluetoothDevice.class.getMethod("removeBond").invoke(d);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return false;
        }
    }
}
