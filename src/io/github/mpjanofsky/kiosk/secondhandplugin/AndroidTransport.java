// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.secondhandplugin;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * RFCOMM to the clock on the kiosk's own Bluetooth adapter. The plugin runs inside Kiosk Satellite and borrows its
 * Bluetooth permission. Channel 4 is reached by reflection ({@code createRfcommSocket(4)}, secure): the clock's
 * service record is not found by UUID. The first connect right after pairing usually fails; the link retries.
 */
final class AndroidTransport implements ClockTransport {
    static final int DATA_CHANNEL = 4;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter adapter;
    private final String address;
    private volatile BluetoothSocket connecting;
    private volatile BluetoothServerSocket server;
    private volatile boolean listening;
    private Thread listener;

    AndroidTransport(BluetoothAdapter adapter, String address) {
        this.adapter = adapter;
        this.address = address == null ? "" : address.trim().toUpperCase(Locale.US);
    }

    @Override public String unavailableReason() {
        if (adapter == null) {
            return "This kiosk has no Bluetooth.";
        }
        try {
            if (!adapter.isEnabled()) {
                return "Bluetooth is off.";
            }
            if (address.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(address)) {
                return "No clock paired yet. Run Pair clock.";
            }
            if (adapter.getRemoteDevice(address).getBondState() != BluetoothDevice.BOND_BONDED) {
                return "The clock is not paired. Run Pair clock.";
            }
            return null;
        } catch (SecurityException e) {
            return "Kiosk Satellite may not use Bluetooth on this device.";
        }
    }

    @Override public Socket connect() throws IOException {
        BluetoothSocket s;
        try {
            adapter.cancelDiscovery();
            BluetoothDevice device = adapter.getRemoteDevice(address);
            s = (BluetoothSocket) BluetoothDevice.class.getMethod("createRfcommSocket", int.class).invoke(device, DATA_CHANNEL);
        } catch (Exception e) {
            throw new IOException("cannot create the socket: " + e);
        }
        connecting = s;
        try {
            s.connect();
        } catch (IOException e) {
            close(s);
            throw e;
        } finally {
            connecting = null;
        }
        return new Wrapped(s);
    }

    @Override public void abort() {
        BluetoothSocket s = connecting;
        if (s != null) {
            close(s);
        }
    }

    @Override public synchronized void startListening(final Runnable onCallIn) {
        if (listening || adapter == null) {
            return;
        }
        listening = true;
        listener = new Thread(new Runnable() {
            @Override public void run() {
                listen(onCallIn);
            }
        }, "echo-wall-clock-listen");
        listener.setDaemon(true);
        listener.start();
    }

    @Override public synchronized void stopListening() {
        listening = false;
        BluetoothServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        Thread t = listener;
        listener = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** The clock connects to us after a pairing or a dropped link, looking for a serial port. Answer by connecting back. */
    private void listen(Runnable onCallIn) {
        long pause = 1000;
        while (listening) {
            BluetoothServerSocket ss = null;
            try {
                if (!adapter.isEnabled()) {
                    Thread.sleep(5000);
                    continue;
                }
                ss = adapter.listenUsingRfcommWithServiceRecord("Serial Port", SPP);
                server = ss;
                pause = 1000;
                while (listening) {
                    BluetoothSocket in = ss.accept();
                    try {
                        String from = in.getRemoteDevice().getAddress();
                        if (address.isEmpty() || address.equalsIgnoreCase(from)) {
                            onCallIn.run();
                        }
                    } finally {
                        close(in);
                    }
                }
            } catch (IOException e) {
                // closed, or the stack restarted: listen again unless stopped
            } catch (SecurityException e) {
                return;
            } catch (InterruptedException e) {
                return;
            } finally {
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (IOException ignored) {
                        // already closed
                    }
                }
                server = null;
            }
            if (listening) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    return;
                }
                pause = Math.min(pause * 2, 30000);
            }
        }
    }

    private static void close(BluetoothSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static final class Wrapped implements Socket {
        private final BluetoothSocket s;

        Wrapped(BluetoothSocket s) {
            this.s = s;
        }

        @Override public InputStream in() throws IOException {
            return s.getInputStream();
        }

        @Override public OutputStream out() throws IOException {
            return s.getOutputStream();
        }

        @Override public void close() {
            AndroidTransport.close(s);
        }
    }
}
